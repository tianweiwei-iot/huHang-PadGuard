package com.padguard.server.service

import com.padguard.server.dto.*
import org.springframework.stereotype.Service

/**
 * 管控后台（admin）业务聚合层门面。
 *
 * 管控后台（admin）业务聚合层门面，按领域拆分为下列独立 `@Service`，本类仅作统一入口，
 * **对外公开方法签名完全不变**（控制器 `AdminController` 与测试无需任何改动）：
 *
 * - [AdminAuthService]          登录
 * - [AdminDashboardService]     仪表盘聚合
 * - [AdminParentService]        家长账号
 * - [AdminDeviceService]        设备
 * - [AdminBillingService]       订阅计费
 * - [AdminRbacService]          权限 / 角色 / 管理员
 * - [AdminServerMonitorService] 服务端监控
 * - [AdminAuditService]         审计 / 告警
 * - [AdminSettingsService]      系统设置
 *
 * 写操作的服务端 RBAC 闸门、审计落库、DTO 对齐约定全部保留在各自的子服务中；
 * 跨领域共享的纯函数 / 常量 / 数据类统一收敛到 [AdminShared]（包级声明）。
 */
@Service
class AdminService(
    private val authService: AdminAuthService,
    private val dashboardService: AdminDashboardService,
    private val parentService: AdminParentService,
    private val deviceService: AdminDeviceService,
    private val billingService: AdminBillingService,
    private val rbacService: AdminRbacService,
    private val monitorService: AdminServerMonitorService,
    private val auditService: AdminAuditService,
    private val settingsService: AdminSettingsService
) {
    // ============================ 登录 ============================
    fun login(username: String, password: String) = authService.login(username, password)

    /** 登出吊销：自增令牌版本，使所有历史令牌失效 */
    fun logout(adminId: String) = authService.logout(adminId)

    // ============================ 仪表盘 ============================
    fun dashboard(adminId: String) = dashboardService.dashboard(adminId)

    // ============================ 家长账号 ============================
    fun listParents(adminId: String, keyword: String?, status: String?, plan: String?, page: Int = 1, size: Int = 20) =
        parentService.listParents(adminId, keyword, status, plan, page, size)

    fun parentDetail(adminId: String, id: String) = parentService.parentDetail(adminId, id)

    fun suspendParent(adminId: String, id: String) = parentService.suspendParent(adminId, id)

    fun activateParent(adminId: String, id: String) = parentService.activateParent(adminId, id)

    fun resetParentPassword(adminId: String, id: String) = parentService.resetParentPassword(adminId, id)

    fun createParent(adminId: String, req: AdminCreateParentRequest) = parentService.createParent(adminId, req)

    fun updateParent(adminId: String, id: String, req: AdminUpdateParentRequest) =
        parentService.updateParent(adminId, id, req)

    fun deleteParent(adminId: String, id: String) = parentService.deleteParent(adminId, id)

    // ============================ 设备 ============================
    fun listDevices(adminId: String, keyword: String?, online: String?, page: Int = 1, size: Int = 20) =
        deviceService.listDevices(adminId, keyword, online, page, size)

    fun lockDevice(adminId: String, deviceId: String) = deviceService.lockDevice(adminId, deviceId)

    fun unlockDevice(adminId: String, deviceId: String) = deviceService.unlockDevice(adminId, deviceId)

    fun unbindDevice(adminId: String, deviceId: String) = deviceService.unbindDevice(adminId, deviceId)

    // ============================ 订阅计费 ============================
    fun listPlans(adminId: String) = billingService.listPlans(adminId)

    fun listSubscriptions(adminId: String, status: String?, page: Int = 1, size: Int = 20) = billingService.listSubscriptions(adminId, status, page, size)

    fun listOrders(adminId: String, status: String?, page: Int = 1, size: Int = 20) = billingService.listOrders(adminId, status, page, size)

    fun manualActivate(adminId: String, req: AdminManualSubscriptionRequest) =
        billingService.manualActivate(adminId, req)

    fun refund(adminId: String, orderNo: String) = billingService.refund(adminId, orderNo)

    fun revenue(adminId: String) = billingService.revenue(adminId)

    // ============================ RBAC ============================
    fun permissionSchema(adminId: String) = rbacService.permissionSchema(adminId)

    fun listRoles(adminId: String) = rbacService.listRoles(adminId)

    fun createRole(adminId: String, req: AdminRoleCreateRequest) = rbacService.createRole(adminId, req)

    fun deleteRole(adminId: String, roleId: String) = rbacService.deleteRole(adminId, roleId)

    fun saveRole(adminId: String, role: AdminRolePrivilegesDto) = rbacService.saveRole(adminId, role)

    fun resetRolePrivileges(adminId: String, roleId: String) = rbacService.resetRolePrivileges(adminId, roleId)

    fun listAdmins(adminId: String) = rbacService.listAdmins(adminId)

    fun createAdmin(adminId: String, req: AdminCreateRequest) = rbacService.createAdmin(adminId, req)

    fun assignRole(adminId: String, targetAdminId: String, roleId: String) =
        rbacService.assignRole(adminId, targetAdminId, roleId)

    fun updateAdmin(adminId: String, targetAdminId: String, req: AdminUpdateAdminRequest) =
        rbacService.updateAdmin(adminId, targetAdminId, req)

    fun deleteAdmin(adminId: String, targetAdminId: String) = rbacService.deleteAdmin(adminId, targetAdminId)

    fun resetAdminPassword(adminId: String, targetAdminId: String) =
        rbacService.resetAdminPassword(adminId, targetAdminId)

    fun changeOwnPassword(adminId: String, currentPassword: String, newPassword: String) =
        rbacService.changeOwnPassword(adminId, currentPassword, newPassword)

    // ============================ 服务端监控 ============================
    fun serverMetrics(adminId: String) = monitorService.serverMetrics(adminId)

    fun serverLogs(adminId: String) = monitorService.serverLogs(adminId)

    // ============================ 审计 / 告警 ============================
    fun auditAreas(adminId: String) = auditService.auditAreas(adminId)

    fun audit(adminId: String, area: String?) = auditService.audit(adminId, area)

    /** 带截断元信息的审计列表，对外接口走这个，避免调用方把"最近 200 条"当成"全部历史"。 */
    fun auditPage(adminId: String, area: String?) = auditService.auditPage(adminId, area)

    fun alerts(adminId: String) = auditService.alerts(adminId)

    fun closeAlert(adminId: String, id: String) = auditService.closeAlert(adminId, id)

    // ============================ 系统设置 ============================
    fun settings(adminId: String) = settingsService.settings(adminId)

    fun saveSettings(adminId: String, req: AdminSettingsSaveDto) = settingsService.saveSettings(adminId, req)
}
