package com.padguard.server.controller

import com.padguard.server.common.ApiResponse
import com.padguard.server.domain.Device
import com.padguard.server.dto.*
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.service.AuthService
import com.padguard.server.service.CommandKey
import com.padguard.server.service.CommandService
import com.padguard.server.service.CommandType
import com.padguard.server.service.DeviceService
import com.padguard.server.service.GroupService
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
    fun register(@RequestBody req: RegisterRequest) = ApiResponse.ok(authService.register(req))

    @PostMapping("/login/sms")
    fun loginSms(@RequestBody req: LoginSmsRequest) = ApiResponse.ok(authService.loginWithSms(req))

    @PostMapping("/sms/send")
    fun sendSms(@RequestBody req: SendSmsRequest) = ApiResponse.ok<Any?>(null)

    @PostMapping("/refresh")
    fun refresh(@RequestHeader("Refresh-Token") refreshToken: String) =
        ApiResponse.ok(authService.refresh(refreshToken))

    @PostMapping("/logout")
    fun logout() = ApiResponse.ok<Any?>(null)
}

@RestController
@RequestMapping("/v1/devices")
class ParentDeviceController(
    private val deviceService: DeviceService,
    private val groupService: GroupService
) {

    @PostMapping("/bind-code")
    fun bindCode(@RequestAttribute("userId") userId: String) =
        ApiResponse.ok(deviceService.generateBindCode(userId))

    /**
     * 批量导入设备台账（Excel / CSV 解析后的行）。
     *
     * 逐个 устройства 独立处理，失败行回传原因，家长能直接对着表格改。
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
    ) = ApiResponse.ok(deviceService.discoverLanDevices(userId, com.padguard.server.common.ClientIps.of(request)))

    /** 接入一台局域网发现的平板 */
    @PostMapping("/lan/bind")
    fun bindLan(
        @RequestAttribute("userId") userId: String,
        @RequestBody req: com.padguard.server.dto.LanBindRequest
    ) = ApiResponse.ok(deviceService.bindLanDevice(userId, req))

    /**
     * 家长端轮询绑定码状态，判定孩子端是否已完成绑定。
     *
     * 不能直接让家长端"等设备列表里出现新 deviceId"：重绑同一台平板时
     * 服务端按硬件指纹复用台账，deviceId 不变，那样判定必然超时。
     */
    @GetMapping("/bind-code/{code}/status")
    fun bindCodeStatus(
        @RequestAttribute("userId") userId: String,
        @PathVariable code: String
    ) = ApiResponse.ok(deviceService.bindCodeStatus(code, userId))

    @GetMapping
    fun list(@RequestAttribute("userId") userId: String, @RequestParam groupId: String? = null) =
        ApiResponse.ok(
            if (groupId.isNullOrBlank()) deviceService.listDevices(userId)
            else deviceService.listDevices(userId).filter { it.groupId == groupId }
        )

    @GetMapping("/{deviceId}")
    fun detail(@RequestAttribute("userId") userId: String, @PathVariable deviceId: String) =
        ApiResponse.ok(deviceService.getDevice(userId, deviceId))

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
    private val deviceRepository: DeviceRepository,
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
        val dev = deviceRepository.findById(deviceId).orElse(null)
            ?: return ApiResponse.fail<Any?>(400, "设备不存在")
        when {
            req.clear -> dev.guardPasswordHash = "" // 显式清除：空串(非 null) 语义
            !req.password.isNullOrBlank() -> {
                if (req.password.length < GuardPasswords.MIN_LENGTH)
                    return ApiResponse.fail<Any?>(400, "密码长度至少 ${GuardPasswords.MIN_LENGTH} 位")
                dev.guardPasswordHash = GuardPasswords.hash(req.password)
            }
            else -> { /* 既不清也不设：保持原值，无操作 */ }
        }
        deviceRepository.save(dev)
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
        val dev = deviceRepository.findById(deviceId).orElse(null) ?: return
        dev.remoteLocked = locked
        runCatching { deviceRepository.save(dev) }
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
     * 一键设置未成年人模式（P1 合规底座）。
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
