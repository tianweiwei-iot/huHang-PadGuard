package com.padguard.server.controller

import com.padguard.server.common.ApiResponse
import com.padguard.server.dto.*
import com.padguard.server.security.RateLimit
import com.padguard.server.service.AdminService
import org.springframework.web.bind.annotation.*

/**
 * 管控后台（admin）HTTP 端点。
 *
 * 统一前缀 `/v1/admin`，鉴权与权限闸门由 [com.padguard.server.security.AdminAuthInterceptor]
 * 与 [com.padguard.server.security.AdminPermissionService] 在链路上兜住：
 * - 登录接口 `/v1/admin/login` 在 [com.padguard.server.config.WebConfig] 中已排除鉴权；
 * - 其余端点经 AdminAuthInterceptor 注入 request attribute "adminId"，并强校验 scope=admin；
 * - 所有**写操作**的 RBAC 闸门在 [AdminService] 内部首行 `require(adminId, area, action)` 处执行；
 * - 所有**读操作**（S13）同样在 [AdminService] 首行过 `require(adminId, area, "read")`，
 *   敏感列表（家长手机号、订单、审计）不再对无 read 权限的角色可见；
 *   前端仅做 UI 隐藏，越权请求到后端必被 FORBIDDEN 拒绝。
 *
 * 因此本 Controller 只负责参数绑定与转发，不在控制器层重复做权限判断（避免两处闸门漂移）。
 */
@RestController
@RequestMapping("/v1/admin")
class AdminController(
    private val adminService: AdminService,
    private val rateLimit: RateLimit
) {

    // ---------- 登录（免鉴权） ----------
    @PostMapping("/login")
    fun login(@RequestBody req: AdminLoginRequest, httpRequest: jakarta.servlet.http.HttpServletRequest): ApiResponse<AdminLoginResponse> =
        ApiResponse.ok(
            run {
                rateLimit.require("admin_login", httpRequest, req.username)
                adminService.login(req.username, req.password)
            }
        )

    // ---------- 仪表盘 ----------
    @GetMapping("/dashboard")
    fun dashboard(@RequestAttribute("adminId") adminId: String): ApiResponse<DashboardResponse> =
        ApiResponse.ok(adminService.dashboard(adminId))

    // ---------- 家长账号 ----------
    @GetMapping("/parents")
    fun listParents(
        @RequestAttribute("adminId") adminId: String,
        @RequestParam(required = false) keyword: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) plan: String?
    ): ApiResponse<List<ParentRow>> = ApiResponse.ok(adminService.listParents(adminId, keyword, status, plan))

    @GetMapping("/parents/{id}")
    fun parentDetail(
        @RequestAttribute("adminId") adminId: String,
        @PathVariable id: String
    ): ApiResponse<ParentDetail?> = ApiResponse.ok(adminService.parentDetail(adminId, id))

    @PostMapping("/parents")
    fun createParent(
        @RequestAttribute("adminId") adminId: String,
        @RequestBody req: AdminCreateParentRequest
    ): ApiResponse<Map<String, Any>> = ApiResponse.ok(adminService.createParent(adminId, req))

    @PostMapping("/parents/{id}/suspend")
    fun suspendParent(
        @RequestAttribute("adminId") adminId: String,
        @PathVariable id: String
    ): ApiResponse<Map<String, Any>> = ApiResponse.ok(adminService.suspendParent(adminId, id))

    @PostMapping("/parents/{id}/activate")
    fun activateParent(
        @RequestAttribute("adminId") adminId: String,
        @PathVariable id: String
    ): ApiResponse<Map<String, Any>> = ApiResponse.ok(adminService.activateParent(adminId, id))

    @PostMapping("/parents/{id}/reset-password")
    fun resetParentPassword(
        @RequestAttribute("adminId") adminId: String,
        @PathVariable id: String
    ): ApiResponse<Map<String, Any>> = ApiResponse.ok(adminService.resetParentPassword(adminId, id))

    @PutMapping("/parents/{id}")
    fun updateParent(
        @RequestAttribute("adminId") adminId: String,
        @PathVariable id: String,
        @RequestBody req: AdminUpdateParentRequest
    ): ApiResponse<Map<String, Any>> = ApiResponse.ok(adminService.updateParent(adminId, id, req))

    @DeleteMapping("/parents/{id}")
    fun deleteParent(
        @RequestAttribute("adminId") adminId: String,
        @PathVariable id: String
    ): ApiResponse<Map<String, Any>> = ApiResponse.ok(adminService.deleteParent(adminId, id))

    // ---------- 设备 ----------
    @GetMapping("/devices")
    fun listDevices(
        @RequestAttribute("adminId") adminId: String,
        @RequestParam(required = false) keyword: String?,
        @RequestParam(required = false) online: String?
    ): ApiResponse<List<DeviceRow>> = ApiResponse.ok(adminService.listDevices(adminId, keyword, online))

    @PostMapping("/devices/{id}/lock")
    fun lockDevice(
        @RequestAttribute("adminId") adminId: String,
        @PathVariable id: String
    ): ApiResponse<Map<String, Any>> = ApiResponse.ok(adminService.lockDevice(adminId, id))

    @PostMapping("/devices/{id}/unlock")
    fun unlockDevice(
        @RequestAttribute("adminId") adminId: String,
        @PathVariable id: String
    ): ApiResponse<Map<String, Any>> = ApiResponse.ok(adminService.unlockDevice(adminId, id))

    // ---------- 订阅计费 ----------
    @GetMapping("/billing/plans")
    fun listPlans(@RequestAttribute("adminId") adminId: String): ApiResponse<List<AdminPlanDto>> =
        ApiResponse.ok(adminService.listPlans(adminId))

    @GetMapping("/billing/subscriptions")
    fun listSubscriptions(
        @RequestAttribute("adminId") adminId: String,
        @RequestParam(required = false) status: String?
    ): ApiResponse<List<SubRow>> = ApiResponse.ok(adminService.listSubscriptions(adminId, status))

    @GetMapping("/billing/orders")
    fun listOrders(
        @RequestAttribute("adminId") adminId: String,
        @RequestParam(required = false) status: String?
    ): ApiResponse<List<OrderRow>> = ApiResponse.ok(adminService.listOrders(adminId, status))

    @PostMapping("/billing/subscriptions/manual")
    fun manualActivate(
        @RequestAttribute("adminId") adminId: String,
        @RequestBody req: AdminManualSubscriptionRequest
    ): ApiResponse<Map<String, Any>> = ApiResponse.ok(adminService.manualActivate(adminId, req))

    @PostMapping("/billing/orders/{orderNo}/refund")
    fun refund(
        @RequestAttribute("adminId") adminId: String,
        @PathVariable orderNo: String
    ): ApiResponse<Map<String, Any>> = ApiResponse.ok(adminService.refund(adminId, orderNo))

    @GetMapping("/billing/revenue")
    fun revenue(@RequestAttribute("adminId") adminId: String): ApiResponse<List<RevenueMonth>> =
        ApiResponse.ok(adminService.revenue(adminId))

    // ---------- RBAC ----------
    @GetMapping("/rbac/roles")
    fun listRoles(@RequestAttribute("adminId") adminId: String): ApiResponse<*> =
        ApiResponse.ok(adminService.listRoles(adminId))

    @PutMapping("/rbac/roles")
    fun saveRole(
        @RequestAttribute("adminId") adminId: String,
        @RequestBody role: AdminRolePrivilegesDto
    ): ApiResponse<Map<String, Any>> = ApiResponse.ok(adminService.saveRole(adminId, role))

    @GetMapping("/rbac/admins")
    fun listAdmins(@RequestAttribute("adminId") adminId: String): ApiResponse<List<AdminDto>> =
        ApiResponse.ok(adminService.listAdmins(adminId))

    @PostMapping("/rbac/admins")
    fun createAdmin(
        @RequestAttribute("adminId") adminId: String,
        @RequestBody req: AdminCreateRequest
    ): ApiResponse<Map<String, Any>> = ApiResponse.ok(adminService.createAdmin(adminId, req))

    @PostMapping("/rbac/admins/{adminId}/role")
    fun assignRole(
        @RequestAttribute("adminId") adminId: String,
        @PathVariable("adminId") targetAdminId: String,
        @RequestBody req: AdminAssignRoleRequest
    ): ApiResponse<Map<String, Any>> = ApiResponse.ok(adminService.assignRole(adminId, targetAdminId, req.roleId))

    @PutMapping("/rbac/admins/{adminId}")
    fun updateAdmin(
        @RequestAttribute("adminId") adminId: String,
        @PathVariable("adminId") targetAdminId: String,
        @RequestBody req: AdminUpdateAdminRequest
    ): ApiResponse<Map<String, Any>> = ApiResponse.ok(adminService.updateAdmin(adminId, targetAdminId, req))

    @DeleteMapping("/rbac/admins/{adminId}")
    fun deleteAdmin(
        @RequestAttribute("adminId") adminId: String,
        @PathVariable("adminId") targetAdminId: String
    ): ApiResponse<Map<String, Any>> = ApiResponse.ok(adminService.deleteAdmin(adminId, targetAdminId))

    @PostMapping("/rbac/admins/{adminId}/reset-password")
    fun resetAdminPassword(
        @RequestAttribute("adminId") adminId: String,
        @PathVariable("adminId") targetAdminId: String
    ): ApiResponse<Map<String, Any>> = ApiResponse.ok(adminService.resetAdminPassword(adminId, targetAdminId))

    // ---------- 服务端监控 ----------
    @GetMapping("/server/metrics")
    fun serverMetrics(@RequestAttribute("adminId") adminId: String): ApiResponse<ServerMetrics> =
        ApiResponse.ok(adminService.serverMetrics(adminId))

    // ---------- 审计 / 告警 ----------
    @GetMapping("/audit")
    fun audit(
        @RequestAttribute("adminId") adminId: String,
        @RequestParam(required = false) area: String?
    ): ApiResponse<List<AuditItem>> = ApiResponse.ok(adminService.audit(adminId, area))

    @GetMapping("/alerts")
    fun alerts(@RequestAttribute("adminId") adminId: String): ApiResponse<List<AlertItem>> =
        ApiResponse.ok(adminService.alerts(adminId))

    @PostMapping("/alerts/{id}/close")
    fun closeAlert(
        @RequestAttribute("adminId") adminId: String,
        @PathVariable id: String
    ): ApiResponse<Map<String, Any>> = ApiResponse.ok(adminService.closeAlert(adminId, id))

    // ---------- 系统设置 ----------
    @GetMapping("/settings")
    fun settings(@RequestAttribute("adminId") adminId: String): ApiResponse<AdminSettingsDto> =
        ApiResponse.ok(adminService.settings(adminId))

    @PutMapping("/settings")
    fun saveSettings(
        @RequestAttribute("adminId") adminId: String,
        @RequestBody req: AdminSettingsSaveDto
    ): ApiResponse<AdminSettingsDto> = ApiResponse.ok(adminService.saveSettings(adminId, req))
}
