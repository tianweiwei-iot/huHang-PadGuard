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
import com.padguard.server.service.PolicyService
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/v1/auth")
class AuthController(private val authService: AuthService) {

    @PostMapping("/login/password")
    fun loginPassword(@RequestBody req: LoginPasswordRequest) = ApiResponse.ok(authService.loginWithPassword(req))

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
    private val deviceRepository: DeviceRepository
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
        ApiResponse.ok(commandService.getCommand(msgId))
}
