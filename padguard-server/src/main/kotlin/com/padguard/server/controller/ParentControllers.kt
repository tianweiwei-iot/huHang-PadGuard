package com.padguard.server.controller

import com.padguard.server.common.ApiResponse
import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.Device
import com.padguard.server.dto.*
import com.padguard.server.service.AuthService
import com.padguard.server.service.CommandKey
import com.padguard.server.service.CommandService
import com.padguard.server.service.CommandType
import com.padguard.server.service.DeviceService
import com.padguard.server.service.EnrollmentService
import com.padguard.server.service.GroupService
import com.padguard.server.service.IngestService
import com.padguard.server.service.PolicyExtensionService
import com.padguard.server.service.PolicyService
import com.padguard.server.security.GuardPasswords
import com.padguard.server.security.RateLimit
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/v1/auth")
class AuthController(private val authService: AuthService, private val rateLimit: RateLimit) {

    @PostMapping("/login/password")
    fun loginPassword(@RequestBody req: LoginPasswordRequest, httpRequest: jakarta.servlet.http.HttpServletRequest) =
        ApiResponse.ok(
            run {
                rateLimit.require("login", httpRequest, req.phone)
                authService.loginWithPassword(req)
            }
        )

    @PostMapping("/register")
    fun register(@RequestBody req: RegisterRequest, httpRequest: jakarta.servlet.http.HttpServletRequest) =
        ApiResponse.ok(
            run {
                // 频控必须在建号之前：注册会写 users 表并顺带建一份试用订阅台账，
                // 是唯一"匿名即可写库"的入口。放在业务前，被限流时库里不会留下半成品账号。
                // 只按 IP 计数（注册的手机号每次都是新的，按账号计数没有意义）。
                rateLimit.require("register", httpRequest)
                authService.register(req)
            }
        )

    @PostMapping("/login/sms")
    fun loginSms(
        @RequestBody req: LoginSmsRequest,
        httpRequest: jakarta.servlet.http.HttpServletRequest
    ) = ApiResponse.ok(
        run {
            // F-02：匿名可达端点必须与其他爆破面一样先过频控再进业务。
            // 短信登录当前在非开发 profile 被硬拒，但业务闸门在频控之后，
            // 不能因此让它成为零成本探测 / 轰炸入口。
            rateLimit.require("login_sms", httpRequest, req.phone)
            authService.loginWithSms(req)
        }
    )

    @PostMapping("/sms/send")
    fun sendSms(
        @RequestBody req: SendSmsRequest,
        httpRequest: jakarta.servlet.http.HttpServletRequest
    ) = ApiResponse.ok<Any?>(
        run {
            // 空实现阶段也要限流：接入服务商后这里是唯一「匿名即可触发外部计费调用」的入口。
            rateLimit.require("sms_send", httpRequest, req.phone)
            null
        }
    )

    @PostMapping("/refresh")
    fun refresh(@RequestHeader("Refresh-Token") refreshToken: String) =
        ApiResponse.ok(authService.refresh(refreshToken))

    /**
     * 退出登录。
     *
     * 改造前这里是 `ApiResponse.ok(null)` —— 一个空实现：客户端删掉本地令牌就算"登出"，
     * 服务端手里的 access token 还能用满 120 分钟，refresh token 更能续期 30 天。
     * 令牌一旦泄漏（截图、代理日志、二手设备），"我已经登出了"完全不能止损。
     * 现在把账号的 token_version +1，使该账号已签发的全部令牌立即失效。
     */
    @PostMapping("/logout")
    fun logout(@RequestAttribute("userId") userId: String) = ApiResponse.ok(authService.logout(userId))

    /**
     * 修改登录密码（需已登录；未列入拦截器放行清单，失败即 401）。
     *
     * 返回一对**新令牌**而不是空回执：改密码会让本账号全部旧令牌失效（含当前这台），
     * 客户端必须立刻用新令牌替换本地存的那对，否则下一个请求就被登出。
     *
     * 限流用独立场景名：与 "login" 分桶，避免家长在登录页试错把改密码的额度吃掉
     * （两者是不同动作，共用一个桶会让"刚被锁过的人改不了密码"）。
     */
    @PostMapping("/password")
    fun changePassword(
        @RequestAttribute("userId") userId: String,
        @RequestBody req: ChangePasswordRequest,
        httpRequest: jakarta.servlet.http.HttpServletRequest
    ) = ApiResponse.ok(
        run {
            rateLimit.require("password_change", httpRequest, userId)
            authService.changePassword(userId, req)
        }
    )

    /**
     * 查询当前账号的活跃登录会话（「登录设备」管理页）。
     *
     * 只列未吊销会话，且不外泄 refresh_jti / userId / revoked 等敏感字段
     * （[AuthService.listSessions] 已映射到脱敏 DTO [UserSessionView]）。
     * 已登录态只读操作，无需额外频控。
     */
    @GetMapping("/sessions")
    fun listSessions(@RequestAttribute("userId") userId: String) =
        ApiResponse.ok(authService.listSessions(userId))

    /**
     * 单设备退出登录：只吊销指定会话的 refresh token，不影响其他设备。
     *
     * 与 [logout] 的「整账号踢下线」不同，这里只置位那一台的 revoked，
     * 其 access token 仍有效至 ≤120 分钟自然过期（标准单设备退出语义）。
     * 已登录态写操作，套用轻量频控防止误刷 / 滥用。
     */
    @DeleteMapping("/sessions/{sessionId}")
    fun revokeSession(
        @RequestAttribute("userId") userId: String,
        @PathVariable sessionId: String,
        httpRequest: jakarta.servlet.http.HttpServletRequest
    ) = ApiResponse.ok(
        run {
            rateLimit.require("revoke_session", httpRequest, userId)
            authService.revokeSession(userId, sessionId)
        }
    )
}

@RestController
@RequestMapping("/v1/devices")
class ParentDeviceController(
    private val deviceService: DeviceService,
    private val groupService: GroupService,
    private val enrollmentService: EnrollmentService,
    private val ingestService: IngestService
) {

    /**
     * 打开（或复用）一次设备接入，一次性返回**完整连接信息**：
     * 会话 ID、6 位接入码、二维码载荷、有效期。
     *
     * 二维码与接入码在这里同源（同一个会话的两种呈现），家长端无需、也不应该
     * 为两个展示位置各发一次请求 —— 那正是"二维码里的码和显示的配对码不是同一个"的成因。
     * 幂等：同一家长名下已有进行中的会话时直接复用，避免"退出去再进来"把孩子手里的码作废。
     */
    @PostMapping("/enrollments")
    fun openEnrollment(@RequestAttribute("userId") userId: String) =
        ApiResponse.ok(enrollmentService.open(userId))

    /**
     * 查询本次接入的当前状态。
     *
     * 这是 WS 推送之外的必然正确兜底：推送可能因断线丢失，但查询永远能拿到会话的真实状态。
     * 返回 null 表示当前没有任何接入会话。
     */
    @GetMapping("/enrollments/current")
    fun currentEnrollment(@RequestAttribute("userId") userId: String) =
        ApiResponse.ok(enrollmentService.current(userId))

    /** 换一个接入码（原码立即作废）：家长怀疑码已泄露、或孩子端一直绑不上时使用 */
    @PostMapping("/enrollments/refresh")
    fun refreshEnrollment(@RequestAttribute("userId") userId: String) =
        ApiResponse.ok(enrollmentService.refresh(userId))

    /** 作废本次接入（家长主动放弃） */
    @DeleteMapping("/enrollments/current")
    fun cancelEnrollment(@RequestAttribute("userId") userId: String) =
        run { enrollmentService.cancel(userId); ApiResponse.ok<Any?>(null) }

    /**
     * 批量导入设备台账（Excel / CSV 解析后的行）。
     *
     * 逐行独立处理，失败行回传原因，家长能直接对着表格改。
     * 与绑定共用同一套"按 deviceSn 复用台账"的口径，导入进来的设备和扫码绑定来的没有区别。
     */
    @PostMapping("/import")
    fun importDevices(
        @RequestAttribute("userId") userId: String,
        @RequestBody req: com.padguard.server.dto.DeviceImportRequest
    ) = ApiResponse.ok(deviceService.importDevices(userId, req))

    // ==================== 局域网发现 ====================

    /**
     * 发现同一局域网内可以接入的平板。
     *
     * 传入 [ServletRequest] 只为取调用者 IP 做同网段判定：这个功能本身就是"我这张网里有什么"，
     * 拿不到调用者网络位置就无法回答。
     */
    @GetMapping("/lan/discover")
    fun discoverLan(
        @RequestAttribute("userId") userId: String,
        request: jakarta.servlet.http.HttpServletRequest
    ) = ApiResponse.ok(deviceService.discoverLanDevices(userId, com.padguard.server.common.ClientIps.viaTrustedProxy(request)))

    /** 接入一台局域网发现的平板 */
    @PostMapping("/lan/bind")
    fun bindLan(
        @RequestAttribute("userId") userId: String,
        @RequestBody req: com.padguard.server.dto.LanBindRequest
    ) = ApiResponse.ok(deviceService.bindLanDevice(userId, req))

    @GetMapping
    fun list(@RequestAttribute("userId") userId: String, @RequestParam groupId: String? = null) =
        ApiResponse.ok(
            if (groupId.isNullOrBlank()) deviceService.listDevices(userId)
            else deviceService.listDevices(userId).filter { it.groupId == groupId }
        )

    @GetMapping("/{deviceId}")
    fun detail(@RequestAttribute("userId") userId: String, @PathVariable deviceId: String) =
        ApiResponse.ok(deviceService.getDevice(userId, deviceId))

    /**
     * 拉取指定设备的某一类上报日志（按时间倒序，最多 [limit] 条）。
     *
     * 用途：家长端「通知记录」展示孩子端代收的系统通知（type=NOTIFICATION_COLLECTED）。
     * 租户隔离由 [DeviceService.getDevice] 兜底（非本人设备直接抛 DEVICE_NOT_OWNED）。
     * 仅返回元数据（id/type/timestamp/payloadJson），payloadJson 是孩子端已最小化的「包名 + 标题」，
     * 绝不返回通知正文，符合隐私最小化原则。
     */
    @GetMapping("/{deviceId}/logs")
    fun listDeviceLogs(
        @RequestAttribute("userId") userId: String,
        @PathVariable deviceId: String,
        @RequestParam type: String,
        @RequestParam(defaultValue = "200") limit: Int
    ): ApiResponse<List<DeviceLogView>> {
        deviceService.getDevice(userId, deviceId)
        val logs = ingestService.listDeviceLogs(deviceId, type, limit)
        return ApiResponse.ok(logs.map { DeviceLogView(it.logId, it.type, it.timestamp, it.payloadJson) })
    }

    @PostMapping("/{deviceId}/unbind")
    fun unbind(@RequestAttribute("userId") userId: String, @PathVariable deviceId: String) =
        run { deviceService.unbind(userId, deviceId); ApiResponse.ok<Any?>(null) }

    @PutMapping("/{deviceId}/name")
    fun rename(
        @RequestAttribute("userId") userId: String, @PathVariable deviceId: String,
        @RequestBody req: RenameDeviceRequest
    ) = run { deviceService.rename(userId, deviceId, req.name); ApiResponse.ok<Any?>(null) }

    @PutMapping("/{deviceId}/group")
    fun assignGroup(
        @RequestAttribute("userId") userId: String, @PathVariable deviceId: String,
        @RequestBody req: AssignGroupRequest
    ): ApiResponse<*> {
        groupService.requireOwnedDevice(userId, deviceId)
        groupService.requireOwnedGroup(userId, req.groupId)
        deviceService.assignToGroup(userId, deviceId, req.groupId)
        return ApiResponse.ok<Any?>(null)
    }

    @GetMapping("/groups")
    fun groups(@RequestAttribute("userId") userId: String, @RequestParam sceneType: String? = null) =
        ApiResponse.ok(groupService.listGroups(userId, sceneType))

    @PostMapping("/groups")
    fun createGroup(
        @RequestAttribute("userId") userId: String, @RequestBody req: CreateGroupRequest
    ) = ApiResponse.ok(groupService.createGroup(userId, req.name, req.sceneType))
}

@RestController
@RequestMapping("/v1")
class PolicyController(
    private val commandService: CommandService,
    private val policyService: PolicyService,
    private val deviceService: DeviceService,
    private val policyExtensionService: PolicyExtensionService
) {
    @PostMapping("/policies/{deviceId}/lock")
    fun lock(
        @RequestAttribute("userId") userId: String,
        @PathVariable deviceId: String,
        @RequestBody(required = false) req: LockRequest?
    ): ApiResponse<*> {
        deviceService.getDevice(userId, deviceId) // 校验设备归属
        // 先落状态再下发指令：家长端靠这个字段把按钮切成"解锁"，
        // 顺序颠倒会出现"指令已下发但界面仍显示锁屏"的错觉。
        setRemoteLocked(deviceId, true)
        return ApiResponse.ok(
            commandService.issueCommand(
                deviceId, CommandType.LOCK_SCREEN, mapOf(CommandKey.REASON to req?.reason)
            )
        )
    }

    /**
     * 一键解锁：解除远程锁屏 / 时段锁 / 限额锁。
     *
     * 之前服务端根本没有这个接口（只有"锁"没有"开"），家长端点解锁必然 404，
     * 而锁屏指令一旦下发，设备在策略下次刷新前不会自行恢复 —— 等于单向闸门。
     * 这里补上对称的解锁通道。
     */
    @PostMapping("/policies/{deviceId}/unlock")
    fun unlock(
        @RequestAttribute("userId") userId: String,
        @PathVariable deviceId: String,
        @RequestBody(required = false) req: LockRequest?
    ): ApiResponse<*> {
        deviceService.getDevice(userId, deviceId)
        setRemoteLocked(deviceId, false)
        return ApiResponse.ok(
            commandService.issueCommand(
                deviceId, CommandType.UNLOCK, mapOf(CommandKey.REASON to req?.reason)
            )
        )
    }

    /**
     * 整机管控 暂停 / 恢复（家长端「释放 / 恢复对平板的全部管控」主开关）。
     *
     * 这是应对「取消应用管控后仍进不了设置」「切换权限被系统强重应用导致管控残缺」
     * 的根治入口：家长一键把设备整体交还（active=false）或收回（active=true）。
     * 孩子端 [GuardMasterSwitch] 收到后统一释放 / 重铺锁屏浮层、设置 / 应用拦截、DO 权限与 Kiosk。
     *
     * 与锁屏对称：先校验归属再下发指令；指令本身只关心是否被服务端接受，
     * 故走 [com.padguard.server.service.CommandService.issueCommand] 返回指令回执。
     */
    @PostMapping("/policies/{deviceId}/management-active")
    fun setManagementActive(
        @RequestAttribute("userId") userId: String,
        @PathVariable deviceId: String,
        @RequestBody(required = false) req: ManagementActiveRequest?
    ): ApiResponse<*> {
        deviceService.getDevice(userId, deviceId) // 校验设备归属
        val active = req?.active ?: true
        return ApiResponse.ok(
            commandService.issueCommand(
                deviceId, CommandType.SET_MANAGEMENT_ACTIVE, mapOf(CommandKey.ACTIVE to active)
            )
        )
    }

    /**
     * 远程揭示管理控制台：孩子端桌面图标隐藏后，把管理台"要回来"的远程通道。
     *
     * 与锁屏等指令对称：校验设备归属 → 下发 [CommandType.REVEAL_MANAGEMENT_UI]。
     * 孩子端收到后点亮图标并拉起管理台（EXTRA_REVEAL 会话，配置结束自动重新隐藏）。
     * 不做密码校验是有意设计：本接口已由家长登录态鉴权，且它是孩子端
     * 「未设密码即隐藏图标」策略下家长不被锁在门外的兜底自救路。
     */
    @PostMapping("/policies/{deviceId}/reveal-console")
    fun revealConsole(
        @RequestAttribute("userId") userId: String,
        @PathVariable deviceId: String
    ): ApiResponse<*> {
        deviceService.getDevice(userId, deviceId) // 校验设备归属
        return ApiResponse.ok(
            commandService.issueCommand(deviceId, CommandType.REVEAL_MANAGEMENT_UI, emptyMap())
        )
    }

    /**
     * 操作密码闸门：家长端设置 / 清除「设备操作密码」。
     *
     * ## 数据流（必须和服务端下发、孩子端落库口径一致）
     * 1. 家长在家长端输入明文密码，经 TLS 发到本接口；
     * 2. 服务端按 `SHA-256(明文)` 的 Base64 哈希落库（无盐，保证跨设备一致），**明文永不出服务端**；
     * 3. [com.padguard.server.service.PolicyExtensionService.buildPolicyPackage] 把哈希写进策略包 `guard.passwordHash`；
     * 4. 孩子端 [com.padguard.child.guard.GuardPasswordManager] 只存哈希，校验时本地重算 SHA-256 比对，
     *    明文永不出机；解锁窗口内，系统设置 / 卸载 / 未知来源安装被放行。
     *
     * [req.clear] 为真 → 清除密码（空串非 null 语义，孩子端收到后清本地密码）；
     * 否则 [req.password] 非空且长度达标 → 设置；两者皆空 → 无操作。
     */
    @PostMapping("/policies/{deviceId}/guard-password")
    fun setGuardPassword(
        @RequestAttribute("userId") userId: String,
        @PathVariable deviceId: String,
        @RequestBody req: GuardPasswordRequest
    ): ApiResponse<*> {
        deviceService.getDevice(userId, deviceId) // 校验设备归属
        // 计算目标哈希：clear→空串(非 null) 语义；password→SHA-256 哈希；两者皆空→null 表示无操作。
        // 落库改字段收口到 DeviceService.setGuardPasswordHash（事务内托管实体，避免脱管实体整行 merge 覆盖安全列）。
        val hash = when {
            req.clear -> ""
            !req.password.isNullOrBlank() -> {
                if (req.password.length < GuardPasswords.MIN_LENGTH)
                    throw BizException(
                        ParentErr.PARAM_ERROR,
                        "密码长度至少 ${GuardPasswords.MIN_LENGTH} 位",
                        Audience.PARENT
                    )
                GuardPasswords.hash(req.password)
            }
            else -> null
        }
        if (hash != null) deviceService.setGuardPasswordHash(deviceId, hash)
        // 重建策略包并推送：设备下次拉取（或 MQTT 通知后立即拉取）即生效。
        runCatching { policyExtensionService.rebuildPolicy(deviceId) }
            .onFailure { log.warn("rebuild policy after guard-password failed: ${it.message}") }
        return ApiResponse.ok<Any?>(null)
    }

    /**
     * 受管控水印开关。
     *
     * ## 为什么需要它（能力边界必须说清楚）
     * Android 从 12 起在 MediaProjection 会话期间由**系统**绘制一个红色录制指示器，
     * 它画在系统层、不进入应用画面，Device Owner / 系统签名都无法关闭 ——
     * 这是 Android 的隐私红线，任何"录屏时不显示红点"的诉求都无法实现。
     *
     * 能做的是什么：本应用自己叠加一个半透明水印（走悬浮窗），
     * 明确告知"这台设备处于受控状态"。它同样不是"去掉红点"，
     * 而是给家长一个**可自定义、可关闭**的替代提示手段。
     * 关闭时下发 `enabled=false`，孩子端收起悬浮窗。
     */
    @PostMapping("/policies/{deviceId}/watermark")
    fun watermark(
        @RequestAttribute("userId") userId: String,
        @PathVariable deviceId: String,
        @RequestBody(required = false) req: WatermarkRequest?
    ): ApiResponse<*> {
        deviceService.getDevice(userId, deviceId)
        val content = req?.content
            ?.trim()
            ?.take(WatermarkRequest.MAX_CONTENT_LENGTH)
            .orEmpty()
        return ApiResponse.ok(
            commandService.issueCommand(
                deviceId, CommandType.SET_WATERMARK,
                mapOf(
                    CommandKey.ENABLED to (req?.enabled ?: true),
                    CommandKey.CONTENT to content
                )
            )
        )
    }

    /**
     * 记录远程锁屏状态。
     *
     * 只改这一个字段并立即落库：家长端下一次拉设备列表就能读到，
     * 按钮也就跟着切成"锁屏"/"解锁"。写失败不能让整个接口失败 ——
     * 指令已经下发、孩子端确实会锁上，因状态落库失败而报错只会误导家长以为没生效。
     */
    private fun setRemoteLocked(deviceId: String, locked: Boolean) {
        // 落库收口到 DeviceService.setRemoteLocked（事务内托管实体，避免脱管实体整行 merge
        // 覆盖并发写入的其它列）。写失败仍不阻断主流程：指令已下发、孩子端会锁上。
        runCatching { deviceService.setRemoteLocked(deviceId, locked) }
            .onFailure { log.warn("persist remoteLocked=$locked failed: ${it.message}") }
    }

    companion object {
        private val log = LoggerFactory.getLogger(PolicyController::class.java)
    }

    /** 限时解锁：payload 中的 durationMinutes 到点后自动恢复管控 */
    @PostMapping("/policies/{deviceId}/temp-unlock")
    fun tempUnlock(
        @RequestAttribute("userId") userId: String,
        @PathVariable deviceId: String,
        @RequestBody req: TempUnlockRequest
    ): ApiResponse<*> {
        deviceService.getDevice(userId, deviceId)
        val minutes = req.durationMinutes.takeIf { it > 0 } ?: 30
        return ApiResponse.ok(
            commandService.issueCommand(
                deviceId, CommandType.TEMP_UNLOCK,
                mapOf(
                    CommandKey.DURATION_MINUTES to minutes,
                    CommandKey.PACKAGE_NAME to (req.packageName ?: ""),
                    CommandKey.REASON to req.reason
                )
            )
        )
    }

    @PostMapping("/policies/{deviceId}/mode")
    fun mode(
        @RequestAttribute("userId") userId: String,
        @PathVariable deviceId: String,
        @RequestBody req: ModeRequest
    ): ApiResponse<*> {
        deviceService.getDevice(userId, deviceId)
        return ApiResponse.ok(mapOf("version" to policyService.applyMode(deviceId, req.mode)))
    }

    /**
     * 一键设置未成年人模式（合规底座）。
     *
     * 开启：`{"enabled": true, "ageBand": "BAND_8_12"}`，其余字段省略即按该档位的合规默认值。
     * 关闭：`{"enabled": false}` —— 端上会同时解除由分龄派生出来的时长/护眼/宵禁限制。
     * 临时豁免：`{"enabled": true, "exemptMinutes": 30}`，只压过宵禁与护眼，不动每日总额度。
     */
    @PostMapping("/policies/{deviceId}/minor-mode")
    fun minorMode(
        @RequestAttribute("userId") userId: String,
        @PathVariable deviceId: String,
        @RequestBody req: MinorModeRequest
    ): ApiResponse<*> {
        deviceService.getDevice(userId, deviceId)
        return ApiResponse.ok(mapOf("version" to policyService.applyMinorMode(deviceId, req)))
    }

    /**
     * 读取未成年人模式当前配置。
     *
     * 单独给一个 GET 而不是让家长端去解整个策略包：
     * 策略包是"下发给孩子端"的结构，字段名与端上模型强耦合，
     * 家长端直接解会在孩子端改字段时静默读到 null（表现为"开关显示关闭但设备其实锁着"）。
     */
    @GetMapping("/policies/{deviceId}/minor-mode")
    fun getMinorMode(
        @RequestAttribute("userId") userId: String,
        @PathVariable deviceId: String
    ): ApiResponse<*> {
        deviceService.getDevice(userId, deviceId)
        return ApiResponse.ok(policyService.minorModeOf(deviceId))
    }

    /** 各龄档的合规默认值，供家长端在"选择年龄段"界面直接展示后果 */
    @GetMapping("/policies/age-bands")
    fun ageBands(): ApiResponse<*> = ApiResponse.ok(policyService.ageBandDefaults())

    @GetMapping("/commands/{msgId}")
    fun command(@RequestAttribute("userId") userId: String, @PathVariable msgId: String) =
        ApiResponse.ok(commandService.getCommand(userId, msgId))
}

/** 设备上报日志的精简视图：仅暴露元数据，payloadJson 是已最小化的「包名 + 标题」，不含通知正文 */
data class DeviceLogView(
    val id: String?,
    val type: String?,
    val timestamp: Long?,
    val payloadJson: String?
)
