package com.padguard.server.admin

/**
 * PadGuard 管控后台 · Admin 端点参考实现（骨架）
 * ------------------------------------------------------------------
 * 落地说明（务必阅读）：
 * 1. 现有 parent 端鉴权拦截器覆盖 `/v1/` 与 `/api/v1/` 前缀，会要求 parent token。
 *    admin 端点统一放在 `/v1/admin/` 下，需要：
 *      - 新增 `AdminAuthInterceptor`，order 排在 parent 鉴权拦截器之前；
 *      - 在 parent 鉴权拦截器的 exclude 列表里加入 `/v1/admin/**`，
 *        改由 AdminAuthInterceptor 校验管理员令牌与 RBAC 权限。
 * 2. 权限模型：area:action（与前端一致）。后端在每个写操作前用
 *    `AdminPermissionService.require(admin, "parents", "suspend")` 校验，
 *    前端仅做 UI 隐藏，**真正的闸门必须在后端**（防越权）。
 * 3. 本文件为骨架，复用现有 `BillingService` / `DeviceService` / `CommandService`；
 *    家长账号、管理员、审计日志的仓储按项目实际实体命名对接（见 TODO）。
 * 4. 注释中路径一律写 `/v1/`，不要写 `/v1/**`（Kotlin 块注释陷阱）。
 * ------------------------------------------------------------------
 */
import com.padguard.server.common.ApiResponse
import com.padguard.server.service.BillingService
import com.padguard.server.service.CommandService
import com.padguard.server.service.CommandType
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/v1/admin")
class AdminController(
    private val billingService: BillingService,
    private val deviceService: DeviceService,
    private val commandService: CommandService,
    private val adminService: AdminService,          // TODO: 新建，封装家长/管理员/审计的只读与写操作
    private val adminPerm: AdminPermissionService,    // TODO: 新建，area:action 校验
) {

    // ---------- 登录 ----------
    @PostMapping("/login")
    fun login(@RequestBody req: AdminLoginRequest) =
        ApiResponse.ok(adminService.login(req.username, req.password))

    // ---------- 仪表盘 ----------
    @GetMapping("/dashboard")
    fun dashboard(@RequestAttribute("adminId") adminId: String) =
        ApiResponse.ok(adminService.dashboard())

    // ---------- 家长账号 ----------
    @GetMapping("/parents")
    fun parents(
        @RequestAttribute("adminId") adminId: String,
        @RequestParam(required = false) keyword: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) plan: String?
    ) = ApiResponse.ok(adminService.listParents(keyword, status, plan))

    @GetMapping("/parents/{id}")
    fun parentDetail(@RequestAttribute("adminId") adminId: String, @PathVariable id: String) =
        ApiResponse.ok(adminService.parentDetail(id))

    @PostMapping("/parents")
    fun createParent(@RequestAttribute("adminId") adminId: String, @RequestBody req: AdminCreateParentRequest) =
        ApiResponse.ok(adminService.createParent(req))

    @PostMapping("/parents/{id}/suspend")
    fun suspendParent(@RequestAttribute("adminId") adminId: String, @PathVariable id: String) =
        run { adminPerm.require(adminId, "parents", "suspend"); adminService.suspendParent(id); ApiResponse.ok<Any?>(null) }

    @PostMapping("/parents/{id}/activate")
    fun activateParent(@RequestAttribute("adminId") adminId: String, @PathVariable id: String) =
        run { adminPerm.require(adminId, "parents", "write"); adminService.activateParent(id); ApiResponse.ok<Any?>(null) }

    @PostMapping("/parents/{id}/reset-password")
    fun resetPassword(@RequestAttribute("adminId") adminId: String, @PathVariable id: String) =
        run { adminPerm.require(adminId, "parents", "reset_pwd"); ApiResponse.ok(adminService.resetParentPassword(id)) }

    // ---------- 设备 ----------
    @GetMapping("/devices")
    fun devices(@RequestAttribute("adminId") adminId: String, @RequestParam(required = false) keyword: String?, @RequestParam(required = false) online: String?) =
        ApiResponse.ok(adminService.listDevices(keyword, online))

    @PostMapping("/devices/{id}/lock")
    fun lockDevice(@RequestAttribute("adminId") adminId: String, @PathVariable id: String) =
        run { adminPerm.require(adminId, "devices", "lock"); commandService.issueCommand(id, CommandType.LOCK_SCREEN, emptyMap()); ApiResponse.ok<Any?>(null) }

    @PostMapping("/devices/{id}/unlock")
    fun unlockDevice(@RequestAttribute("adminId") adminId: String, @PathVariable id: String) =
        run { adminPerm.require(adminId, "devices", "unlock"); commandService.issueCommand(id, CommandType.UNLOCK, emptyMap()); ApiResponse.ok<Any?>(null) }

    // ---------- 订阅计费 ----------
    @GetMapping("/billing/plans")
    fun plans(@RequestAttribute("adminId") adminId: String) = ApiResponse.ok(billingService.plans())

    @GetMapping("/billing/subscriptions")
    fun subscriptions(@RequestAttribute("adminId") adminId: String, @RequestParam(required = false) status: String?) =
        ApiResponse.ok(adminService.listSubscriptions(status))

    @GetMapping("/billing/orders")
    fun orders(@RequestAttribute("adminId") adminId: String, @RequestParam(required = false) status: String?) =
        ApiResponse.ok(adminService.listOrders(status))

    @PostMapping("/billing/subscriptions/manual")
    fun manualActivate(@RequestAttribute("adminId") adminId: String, @RequestBody req: AdminManualSubscriptionRequest) =
        run { adminPerm.require(adminId, "billing", "manual"); ApiResponse.ok(adminService.manualActivate(req)) }

    @PostMapping("/billing/orders/{orderNo}/refund")
    fun refund(@RequestAttribute("adminId") adminId: String, @PathVariable orderNo: String) =
        run { adminPerm.require(adminId, "billing", "refund"); ApiResponse.ok(adminService.refund(orderNo)) }

    @GetMapping("/billing/revenue")
    fun revenue(@RequestAttribute("adminId") adminId: String) = ApiResponse.ok(adminService.revenue())

    // ---------- RBAC ----------
    @GetMapping("/rbac/roles")
    fun roles(@RequestAttribute("adminId") adminId: String) = ApiResponse.ok(adminService.listRoles())

    @PutMapping("/rbac/roles")
    fun saveRole(@RequestAttribute("adminId") adminId: String, @RequestBody role: AdminRoleDto) =
        run { adminPerm.require(adminId, "rbac", "write"); ApiResponse.ok(adminService.saveRole(role)) }

    @GetMapping("/rbac/admins")
    fun admins(@RequestAttribute("adminId") adminId: String) = ApiResponse.ok(adminService.listAdmins())

    @PostMapping("/rbac/admins")
    fun createAdmin(@RequestAttribute("adminId") adminId: String, @RequestBody req: AdminCreateRequest) =
        run { adminPerm.require(adminId, "rbac", "write"); ApiResponse.ok(adminService.createAdmin(req)) }

    @PostMapping("/rbac/admins/{adminId}/role")
    fun assignRole(@RequestAttribute("adminId") adminId: String, @PathVariable adminId: String, @RequestBody req: AdminAssignRoleRequest) =
        run { adminPerm.require(adminId, "rbac", "assign"); ApiResponse.ok(adminService.assignRole(req)) }

    // ---------- 服务端 ----------
    @GetMapping("/server/metrics")
    fun metrics(@RequestAttribute("adminId") adminId: String) = ApiResponse.ok(adminService.serverMetrics())

    // ---------- 审计 / 告警 ----------
    @GetMapping("/audit")
    fun audit(@RequestAttribute("adminId") adminId: String, @RequestParam(required = false) area: String?) =
        ApiResponse.ok(adminService.audit(area))

    @GetMapping("/alerts")
    fun alerts(@RequestAttribute("adminId") adminId: String) = ApiResponse.ok(adminService.alerts())

    @PostMapping("/alerts/{id}/close")
    fun closeAlert(@RequestAttribute("adminId") adminId: String, @PathVariable id: String) =
        ApiResponse.ok<Any?>(run { adminService.closeAlert(id); null })

    // ---------- 设置 ----------
    @GetMapping("/settings")
    fun settings(@RequestAttribute("adminId") adminId: String) = ApiResponse.ok(adminService.settings())

    @PutMapping("/settings")
    fun saveSettings(@RequestAttribute("adminId") adminId: String, @RequestBody body: AdminSettingsDto) =
        run { adminPerm.require(adminId, "settings", "write"); ApiResponse.ok(adminService.saveSettings(body)) }
}

// ---- DTO（示例，按实际字段补齐）----
data class AdminLoginRequest(val username: String, val password: String)
data class AdminCreateParentRequest(val phone: String, val name: String, val email: String?, val plan: String, val region: String?, val remark: String?)
data class AdminManualSubscriptionRequest(val parentPhone: String, val plan: String, val days: Int)
data class AdminCreateRequest(val username: String, val name: String, val email: String?, val roleId: String)
data class AdminAssignRoleRequest(val roleId: String)
data class AdminRoleDto(val id: String, val name: String, val desc: String, val isSystem: Boolean, val privileges: Map<String, List<String>>)
data class AdminSettingsDto(val brand: String, val currency: String, val trialDays: Int, val passwordPolicy: String, val mfaRequired: Boolean, val mockPay: Boolean, val plans: List<PlanPriceDto>)
data class PlanPriceDto(val id: String, val priceFen: Int)
