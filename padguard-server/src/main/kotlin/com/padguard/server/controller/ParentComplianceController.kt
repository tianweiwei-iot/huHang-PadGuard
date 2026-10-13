package com.padguard.server.controller

import com.padguard.server.common.ApiResponse
import com.padguard.server.dto.*
import com.padguard.server.service.AuditService
import com.padguard.server.service.ContentFilterService
import org.springframework.web.bind.annotation.*

/**
 * 合规与内容管控端点（审计 + 内容管控）。
 * 家长端统一响应包装 { code, message, data, timestamp }。
 */
@RestController
@RequestMapping("/v1")
class ParentComplianceController(
    private val auditService: AuditService,
    private val contentFilterService: ContentFilterService
) {

    // ==================== 审计日志 ====================

    /**
     * 查询审计日志。可按设备与动作过滤；deviceId 与 action 均可选。
     * 用于家长/校园端追溯"谁在何时对哪台设备做了什么"。
     */
    @GetMapping("/audit-logs")
    fun auditLogs(
        @RequestAttribute("userId") userId: String,
        @RequestParam(required = false) deviceId: String?,
        @RequestParam(required = false) action: String?,
        @RequestParam(required = false, defaultValue = "0") page: Int,
        @RequestParam(required = false, defaultValue = "20") size: Int
    ) = ApiResponse.ok(auditService.query(userId, deviceId, action, page, size))

    // ==================== 内容管控 ====================

    /** 网址分类库清单（含默认屏蔽标记），供家长端选择"按分类屏蔽"。 */
    @GetMapping("/content/categories")
    fun categories() = ApiResponse.ok(contentFilterService.listCategories())

    /** 切换搜索引擎强制安全搜索。 */
    @PutMapping("/policies/{deviceId}/web/safe-search")
    fun safeSearch(
        @RequestAttribute("userId") userId: String,
        @PathVariable deviceId: String,
        @RequestBody req: SafeSearchRequest
    ) = ApiResponse.ok<Any?>(run { contentFilterService.setSafeSearch(userId, deviceId, req.enabled); null })

    /** 设置"按分类屏蔽"的自定义分类（会与默认屏蔽项合并后下发）。 */
    @PutMapping("/policies/{deviceId}/web/categories")
    fun categoriesBlock(
        @RequestAttribute("userId") userId: String,
        @PathVariable deviceId: String,
        @RequestBody req: CategoryBlockRequest
    ) = ApiResponse.ok<Any?>(run { contentFilterService.setBlockedCategories(userId, deviceId, req.categories); null })

    /** 文本敏感内容扫描（本地词典兜底；涉密内容只返回掩码命中，不全文展示）。 */
    @PostMapping("/content/scan-text")
    fun scanText(@RequestBody req: TextScanRequest) =
        ApiResponse.ok(contentFilterService.scanText(req.text))
}
