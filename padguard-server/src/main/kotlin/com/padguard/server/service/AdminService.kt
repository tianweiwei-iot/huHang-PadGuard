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

    // ============================ 仪表盘 ============================
    fun dashboard(adminId: String) = dashboardService.dashboard(adminId)

    // ============================ 家长账号 ============================
    fun listParents(adminId: String, keyword: String?, status: String?, plan: String?) =
        parentService.listParents(adminId, keyword, status, plan)

    fun parentDetail(adminId: String, id: String) = parentService.parentDetail(adminId, id)

    fun suspendParent(adminId: String, id: String) = parentService.suspendParent(adminId, id)

    fun activateParent(adminId: String, id: String) = parentService.activateParent(adminId, id)

    fun resetParentPassword(adminId: String, id: String) = parentService.resetParentPassword(adminId, id)

    fun createParent(adminId: String, req: AdminCreateParentRequest) = parentService.createParent(adminId, req)

    fun updateParent(adminId: String, id: String, req: AdminUpdateParentRequest) =
        parentService.updateParent(adminId, id, req)

    fun deleteParent(adminId: String, id: String) = parentService.deleteParent(adminId, id)

    // ============================ 设备 ============================
    fun listDevices(adminId: String, keyword: String?, online: String?) =
        deviceService.listDevices(adminId, keyword, online)

    fun lockDevice(adminId: String, deviceId: String) = deviceService.lockDevice(adminId, deviceId)

    fun unlockDevice(adminId: String, deviceId: String) = deviceService.unlockDevice(adminId, deviceId)

    // ============================ 订阅计费 ============================
    fun listPlans(adminId: String) = billingService.listPlans(adminId)

    fun listSubscriptions(adminId: String, status: String?) = billingService.listSubscriptions(adminId, status)

    fun listOrders(adminId: String, status: String?) = billingService.listOrders(adminId, status)

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
    fun audit(adminId: String, area: String?) = auditService.audit(adminId, area)

    fun alerts(adminId: String) = auditService.alerts(adminId)

    fun closeAlert(adminId: String, id: String) = auditService.closeAlert(adminId, id)

    // ============================ 系统设置 ============================
    fun settings(adminId: String) = settingsService.settings(adminId)

    fun saveSettings(adminId: String, req: AdminSettingsSaveDto) = settingsService.saveSettings(adminId, req)
}
