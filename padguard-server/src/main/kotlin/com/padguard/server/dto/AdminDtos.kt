package com.padguard.server.dto

/**
 * 管控后台（admin）专用 DTO。
 *
 * 重要约定：字段名与形状**严格对齐前端 admin 视图的取用点**，否则 Live 模式会渲染失败。
 * 尤其注意：状态一律用**小写字符串**（active/suspended/trial/expired/paid/refunded/unpaid...），
 * 与 mock 数据保持一致；金额用「分」整数（amountFen / priceFen），由前端统一转「元」展示。
 */

/** 登录返回 */
data class AdminLoginResponse(val token: String, val admin: AdminDto)

/** 管理员概要（前端用 name / roleName / roleId / username / status / mfa / lastLoginAt / id） */
data class AdminDto(
    val id: String,
    val username: String,
    val name: String?,
    val roleId: String,
    val roleName: String,
    val email: String?,
    val status: String,
    val mfa: Boolean,
    val createdAt: Long,
    val lastLoginAt: Long?
)

/** 家长账号列表行 */
data class ParentRow(
    val id: String,
    val phone: String,
    val name: String,
    val email: String?,
    val status: String,
    val registeredAt: String?,
    val lastLoginAt: String?,
    val plan: String,
    val planName: String,
    val expiredAt: String?,
    val deviceCount: Int,
    val childrenCount: Int,
    val region: String,
    val channel: String,
    val remark: String,
    val tags: List<String>
)

/** 家长账号详情（含设备与订阅） */
data class ParentDetail(
    val id: String,
    val phone: String,
    val email: String?,
    val name: String,
    val status: String,
    val plan: String,
    val planName: String,
    val expiredAt: String?,
    val region: String,
    val registeredAt: String?,
    val lastLoginAt: String?,
    val deviceCount: Int,
    val devices: List<DeviceMini>,
    val subscription: SubMini?
)

data class DeviceMini(
    val name: String?,
    val deviceId: String,
    val controlMode: String?,
    val online: Boolean,
    val todayUsageMinutes: Int
)

data class SubMini(val planName: String, val status: String, val autoRenew: Boolean)

/** 设备列表行 */
data class DeviceRow(
    val deviceId: String,
    val deviceSn: String?,
    val name: String?,
    val parentName: String?,
    val parentPhone: String?,
    val model: String?,
    val osVersion: String?,
    val controlMode: String?,
    val online: Boolean,
    val lastOnlineAt: String?,
    val remoteLocked: Boolean,
    val minorMode: Boolean,
    val todayUsageMinutes: Int,
    val region: String,
    val boundAt: String?
)

/** 套餐（含权益描述，供计费页展示） */
data class AdminPlanDto(
    val id: String,
    val name: String,
    val priceFen: Int,
    val deviceLimit: Int,
    val features: List<String>
)

/** 订阅行 */
data class SubRow(
    val parentPhone: String,
    val parentName: String,
    val plan: String,
    val planName: String,
    val status: String,
    val startedAt: String?,
    val expiredAt: String?,
    val autoRenew: Boolean,
    val channel: String
)

/** 订单行 */
data class OrderRow(
    val orderNo: String,
    val parentPhone: String,
    val parentName: String,
    val plan: String,
    val planName: String,
    val amountFen: Int,
    val status: String,
    val channel: String,
    val createdAt: String?
)

/** 营收月报 */
data class RevenueMonth(val month: String, val amount: Int, val newSubs: Int, val churn: Int)

data class NameCount(val name: String, val count: Int)

data class Kpis(
    val parentsTotal: Long,
    val parentsActive: Long,
    val parentsSuspended: Long,
    val devicesTotal: Long,
    val devicesOnline: Long,
    val subsActive: Long,
    val subsTrial: Long,
    val monthRevenue: Int,
    val serverStatus: String
)

data class DashboardResponse(
    val kpis: Kpis,
    val revenueSeries: List<Int>,
    val planDist: List<NameCount>,
    val regionDist: List<NameCount>,
    val alertsOpen: Long,
    val recent: List<AuditItem>
)

/** 服务端监控指标 */
data class ServerMetrics(
    val status: String,
    val db: String,
    val uptimeMs: Long,
    val startedAt: String,
    val jvmHeapUsedMb: Int,
    val jvmHeapMaxMb: Int,
    val threads: Int,
    val requestsPerMin: Int,
    val errorRate: String,
    val dbConnections: String,
    val instances: List<Instance>,
    val logs: List<LogLine>
) {
    data class Instance(val name: String, val host: String, val status: String, val cpu: String, val mem: String)
    data class LogLine(val time: String, val level: String, val msg: String)
}

/** 审计日志项（前端用 time/admin/actionName/area/target/ip/result） */
data class AuditItem(
    val id: String,
    val time: String,
    val admin: String,
    val action: String,
    val actionName: String,
    val area: String,
    val target: String,
    val ip: String?,
    val result: String
)

/** 风险告警项 */
data class AlertItem(
    val id: String,
    val level: String,
    val type: String,
    val message: String?,
    val relatedParent: String,
    val status: String,
    val time: String
)

/** 系统设置 */
data class AdminSettingsDto(
    val brand: String,
    val currency: String,
    val trialDays: Int,
    val passwordPolicy: String,
    val mfaRequired: Boolean,
    val mockPay: Boolean,
    val plans: List<AdminPlanDto>
)

// ---------------- 请求体 ----------------
data class AdminLoginRequest(val username: String, val password: String)
data class AdminCreateParentRequest(
    val phone: String,
    val name: String? = null,
    val email: String? = null,
    val plan: String? = null,
    val region: String? = null,
    val remark: String? = null
)

/** 家长账号修改（仅可维护资料字段；手机号是登录主键，不允许在此变更） */
data class AdminUpdateParentRequest(
    val name: String? = null,
    val email: String? = null,
    val region: String? = null,
    val remark: String? = null
)

/** 管理员账号修改（姓名 / 邮箱 / 状态；角色变更走独立的 assignRole） */
data class AdminUpdateAdminRequest(
    val name: String? = null,
    val email: String? = null,
    val status: String? = null
)
data class AdminManualSubscriptionRequest(val parentPhone: String, val plan: String, val days: Int = 365)
data class AdminCreateRequest(
    val username: String,
    val name: String? = null,
    val email: String? = null,
    val roleId: String
)
data class AdminAssignRoleRequest(val roleId: String)
data class AdminRolePrivilegesDto(
    val id: String,
    val name: String? = null,
    val desc: String? = null,
    val isSystem: Boolean = true,
    val privileges: Map<String, List<String>>
)
data class AdminSettingsSaveDto(
    val brand: String? = null,
    val currency: String? = null,
    val trialDays: Int? = null,
    val passwordPolicy: String? = null,
    val mfaRequired: Boolean? = null,
    val mockPay: Boolean? = null,
    val plans: List<AdminPlanPriceDto>? = null
)
data class AdminPlanPriceDto(val id: String, val priceFen: Int)
