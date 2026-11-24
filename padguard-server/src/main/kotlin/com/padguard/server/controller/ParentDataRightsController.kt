package com.padguard.server.controller

import com.padguard.server.common.ApiResponse
import com.padguard.server.dto.*
import com.padguard.server.service.ComplianceService
import org.springframework.web.bind.annotation.*

/**
 * 数据主体权利端点（第四步合规 P0）。
 *
 * 全部经父端点鉴权拦截器注入 `userId`（见 WebConfig），且涉及设备时由 [ComplianceService] 内部再做归属校验。
 * 统一响应包装 { code, message, data, timestamp }。
 */
@RestController
@RequestMapping("/v1")
class ParentDataRightsController(
    private val complianceService: ComplianceService
) {

    /** 撤回授权：停止对该设备（或名下全部设备）的个人信息采集。 */
    @PostMapping("/compliance/consent/withdraw")
    fun withdraw(
        @RequestAttribute("userId") userId: String,
        @RequestBody req: WithdrawConsentRequest?
    ) = ApiResponse.ok(mapOf("affected" to complianceService.withdrawConsent(userId, req?.deviceIds)))

    /** 恢复授权。 */
    @PostMapping("/compliance/consent/reinstate")
    fun reinstate(
        @RequestAttribute("userId") userId: String,
        @RequestBody req: WithdrawConsentRequest?
    ) = ApiResponse.ok(mapOf("affected" to complianceService.reinstateConsent(userId, req?.deviceIds)))

    /** 不满十四周岁监护人单独同意（家长申报年龄 + 同意文案版本留痕）。 */
    @PostMapping("/compliance/guardian-consent/under14")
    fun under14GuardianConsent(
        @RequestAttribute("userId") userId: String,
        @RequestBody req: Under14GuardianConsentRequest
    ) = ApiResponse.ok(complianceService.submitUnder14GuardianConsent(userId, req))

    /** 查询单独同意状态（家长端回显，避免重复勾选却无凭证）。 */
    @GetMapping("/compliance/guardian-consent/under14/{deviceId}")
    fun under14GuardianConsentStatus(
        @RequestAttribute("userId") userId: String,
        @PathVariable deviceId: String
    ) = ApiResponse.ok(complianceService.guardianConsentStatus(userId, deviceId))

    /** 导出本账号全部个人数据（结构化）。 */
    @GetMapping("/compliance/export")
    fun export(@RequestAttribute("userId") userId: String) =
        ApiResponse.ok(complianceService.exportData(userId))

    /** 提交账号注销请求（进入冷静期）。 */
    @PostMapping("/compliance/account/deletion/request")
    fun deletionRequest(
        @RequestAttribute("userId") userId: String,
        @RequestBody req: AccountDeletionRequestReq?
    ) = ApiResponse.ok(complianceService.requestAccountDeletion(userId, req?.reason))

    /** 凭凭证确认注销（级联清除 + 匿名化）。 */
    @PostMapping("/compliance/account/deletion/confirm")
    fun deletionConfirm(
        @RequestAttribute("userId") userId: String,
        @RequestBody req: AccountDeletionConfirmReq
    ) = ApiResponse.ok<Any?>(run { complianceService.confirmAccountDeletion(userId, req.ticket); null })

    /** 取消注销请求。 */
    @PostMapping("/compliance/account/deletion/cancel")
    fun deletionCancel(
        @RequestAttribute("userId") userId: String,
        @RequestBody req: AccountDeletionCancelReq
    ) = ApiResponse.ok<Any?>(run { complianceService.cancelAccountDeletion(userId, req.ticket); null })

    /** 提交申诉。 */
    @PostMapping("/compliance/appeals")
    fun submitAppeal(
        @RequestAttribute("userId") userId: String,
        @RequestBody req: SubmitAppealRequest
    ) = ApiResponse.ok(mapOf("appealId" to complianceService.submitAppeal(userId, req.deviceId, req.subject, req.detail, req.contact)))

    /** 查询本人申诉列表。 */
    @GetMapping("/compliance/appeals")
    fun listAppeals(@RequestAttribute("userId") userId: String) =
        ApiResponse.ok(complianceService.listAppeals(userId))

    /** 查询单条申诉。 */
    @GetMapping("/compliance/appeals/{id}")
    fun getAppeal(
        @RequestAttribute("userId") userId: String,
        @PathVariable id: String
    ) = ApiResponse.ok(complianceService.getAppeal(userId, id))
}
