package com.padguard.server.controller

import com.padguard.server.common.ApiResponse
import com.padguard.server.dto.*
import com.padguard.server.service.PolicyVersionService
import org.springframework.web.bind.annotation.*

/**
 * 第二步 · 策略版本历史 / 回滚 / 定时生效 / 批量下发的家长端入口。
 *
 * 挂在家长端 `/v1` 前缀下，由 `ParentAuthInterceptor` 统一鉴权；每个端点都把 `userId`
 * 传进 service 做设备归属校验（[com.padguard.server.security.DeviceOwnershipGuard]），
 * 批量端点对**每一台**设备单独校验，绝不因为「列表里有一台是自己的」就放行整批。
 *
 * 风险操作（回滚 / 批量）均写审计；返回体里 `rollbackActive` 用于家长端提示
 * 「当前处于回滚态，设置页显示的是回滚前配置」，避免前后端状态被误读为一致。
 */
@RestController
@RequestMapping("/v1")
class PolicyVersionController(
    private val policyVersionService: PolicyVersionService
) {

    /** 版本历史：倒序、最多 100 条、不含包体。 */
    @GetMapping("/policies/{deviceId}/versions")
    fun versions(
        @RequestAttribute("userId") userId: String,
        @PathVariable deviceId: String,
        @RequestParam(defaultValue = "20") limit: Int
    ) = ApiResponse.ok(policyVersionService.listVersions(userId, deviceId, limit))

    /** 回滚到历史版本：生成更高版本号，内容取目标快照。 */
    @PostMapping("/policies/{deviceId}/rollback")
    fun rollback(
        @RequestAttribute("userId") userId: String,
        @PathVariable deviceId: String,
        @RequestBody req: RollbackPolicyRequest
    ) = ApiResponse.ok(policyVersionService.rollback(userId, deviceId, req))

    /** 设置 / 清除定时生效窗口（不改变策略内容）。 */
    @PutMapping("/policies/{deviceId}/schedule")
    fun schedule(
        @RequestAttribute("userId") userId: String,
        @PathVariable deviceId: String,
        @RequestBody req: PolicyScheduleRequest
    ) = ApiResponse.ok(mapOf("version" to policyVersionService.setSchedule(userId, deviceId, req)))

    /** 批量重新发布当前策略（逐设备独立结果）。 */
    @PostMapping("/policies/batch/publish")
    fun batchPublish(
        @RequestAttribute("userId") userId: String,
        @RequestBody req: BatchPublishRequest
    ) = ApiResponse.ok(policyVersionService.batchPublish(userId, req))

    /** 批量下发管控指令（白名单类型，逐设备独立结果）。 */
    @PostMapping("/commands/batch")
    fun batchCommand(
        @RequestAttribute("userId") userId: String,
        @RequestBody req: BatchCommandRequest
    ) = ApiResponse.ok(policyVersionService.batchCommand(userId, req))
}
