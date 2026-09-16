package com.padguard.server.controller

import com.padguard.server.common.ApiResponse
import com.padguard.server.dto.*
import com.padguard.server.service.AuthService
import com.padguard.server.service.CommandService
import com.padguard.server.service.DeviceService
import com.padguard.server.service.GroupService
import com.padguard.server.service.PolicyService
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
    private val deviceService: DeviceService
) {
    @PostMapping("/policies/{deviceId}/lock")
    fun lock(
        @RequestAttribute("userId") userId: String,
        @PathVariable deviceId: String,
        @RequestBody(required = false) req: LockRequest?
    ): ApiResponse<*> {
        deviceService.getDevice(userId, deviceId) // 校验设备归属
        return ApiResponse.ok(
            commandService.issueCommand(deviceId, "LOCK_SCREEN", mapOf("reason" to req?.reason))
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

    @GetMapping("/commands/{msgId}")
    fun command(@RequestAttribute("userId") userId: String, @PathVariable msgId: String) =
        ApiResponse.ok(commandService.getCommand(msgId))
}
