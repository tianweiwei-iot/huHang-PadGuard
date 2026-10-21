package com.padguard.server.controller

import com.padguard.server.common.ApiResponse
import com.padguard.server.dto.*
import com.padguard.server.security.RateLimit
import com.padguard.server.service.AdminAuthService
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
    private val adminAuthService: AdminAuthService,
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

    /**
     * 退出登录：服务端吊销当前令牌。
     *
     * 仅清浏览器本地 token 是不够的 —— JWT 在过期前始终有效，复制走的令牌仍能操作后台。
     * 本端点把 `admins.token_version` +1，使该管理员名下**全部**已签发令牌立即失效。
     *
     * 关于"重复退出"：已吊销的令牌再调本端点会被 [com.padguard.server.security.AdminAuthInterceptor]
     * 拦在 401。这是预期行为（目标状态已是"未登录"），前端需把 401 同样视为退出成功并清理本地态。
     */
    @PostMapping("/logout")
    fun logout(@RequestAttribute("adminId") adminId: String): ApiResponse<Map<String, Any>> =
        ApiResponse.ok(adminAuthService.logout(adminId))

    // ---------- 自助修改当前管理员密码（需当前密码，免 RBAC 闸门） ----------
    @PostMapping("/me/password")
    fun changeOwnPassword(
        @RequestAttribute("adminId") adminId: String,
        @RequestBody req: AdminChangePasswordRequest
    ): ApiResponse<Map<String, Any>> =
        ApiResponse.ok(adminService.changeOwnPassword(adminId, req.currentPassword, req.newPassword))

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
    /**
     * 权限元数据（领域 + 动作的中文名、危险等级、系统锁定角色）。
     *
     * 前端矩阵据此渲染，不再自带白名单副本 —— 这是"前端有 monitor 列、服务端却不敢认"
     * 这类静默失效的根治手段：新增权限只需改服务端一处。
     *
     * 注：原前端自带的 `monitor` 领域已移除。家长端的实时监控接口服务于「家长查看自己绑定的
     * 设备」，后台无对应视图；设备在运营侧的管控已由 `devices` 领域的 lock/unlock 覆盖。
     * 保留一个无端点支撑的领域只会让"配了不生效"重演。
     * （此处不可书写含连续斜杠与星号的路径：Kotlin 块注释不支持嵌套。）
     */
    @GetMapping("/rbac/permissions")
    fun permissionSchema(@RequestAttribute("adminId") adminId: String): ApiResponse<*> =
        ApiResponse.ok(adminService.permissionSchema(adminId))

    @GetMapping("/rbac/roles")
    fun listRoles(@RequestAttribute("adminId") adminId: String): ApiResponse<*> =
        ApiResponse.ok(adminService.listRoles(adminId))

    @PutMapping("/rbac/roles")
    fun saveRole(
        @RequestAttribute("adminId") adminId: String,
        @RequestBody role: AdminRolePrivilegesDto
    ): ApiResponse<Map<String, Any>> = ApiResponse.ok(adminService.saveRole(adminId, role))

    /** 还原某角色的权限到系统默认值（清除后台调整记录） */
    @DeleteMapping("/rbac/roles/{roleId}/privileges")
    fun resetRolePrivileges(
        @RequestAttribute("adminId") adminId: String,
        @PathVariable roleId: String
    ): ApiResponse<Map<String, Any>> = ApiResponse.ok(adminService.resetRolePrivileges(adminId, roleId))

    /** 新建自定义角色（落库 admin_roles，立即可用于指派） */
    @PostMapping("/rbac/roles")
    fun createRole(
        @RequestAttribute("adminId") adminId: String,
        @RequestBody req: AdminRoleCreateRequest
    ): ApiResponse<Map<String, Any>> = ApiResponse.ok(adminService.createRole(adminId, req))

    /** 删除自定义角色（内置角色 / 仍有成员的角色会被拒绝） */
    @DeleteMapping("/rbac/roles/{roleId}")
    fun deleteRole(
        @RequestAttribute("adminId") adminId: String,
        @PathVariable roleId: String
    ): ApiResponse<Map<String, Any>> = ApiResponse.ok(adminService.deleteRole(adminId, roleId))

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

    /** 运行日志（进程内环形缓冲，最近的 WARN/ERROR 与本服务 INFO 业务事件） */
    @GetMapping("/server/logs")
    fun serverLogs(@RequestAttribute("adminId") adminId: String): ApiResponse<List<ServerMetrics.LogLine>> =
        ApiResponse.ok(adminService.serverLogs(adminId))

    // ---------- 审计 / 告警 ----------
    /**
     * 审计领域下拉的元数据。由服务端权限定义单向派生（含此前前端漏掉的 `audit` 领域），
     * 前端不再自带副本，避免"服务端有 7 个领域、下拉只有 6 项"这类入口缺失。
     */
    @GetMapping("/audit/areas")
    fun auditAreas(@RequestAttribute("adminId") adminId: String): ApiResponse<List<Map<String, String>>> =
        ApiResponse.ok(adminService.auditAreas(adminId))

    /**
     * 审计日志（按领域筛选后截断）。
     *
     * 返回的是 **分页对象** 而非裸数组：裸数组会让消费方把"最近 200 条"当作"全部历史"，
     * 进而在被截断的情况下得出"该领域最近无异常"的错误结论。
     * `truncated=true` 时前端必须显式提示，不能静默展示。
     */
    @GetMapping("/audit")
    fun audit(
        @RequestAttribute("adminId") adminId: String,
        @RequestParam(required = false) area: String?
    ): ApiResponse<AuditPageDto> = ApiResponse.ok(adminService.auditPage(adminId, area))

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
