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
    /** 今日已用分钟；**null 表示今日尚未开始计时**，前端须显示"—"而非 0 */
    val todayUsageMinutes: Int?
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
    /**
     * 今日已用分钟；**null 表示今日尚未开始计时**（孩子还没确认 / 设备当天没被使用）。
     *
     * 早先这里恒为 0，前端于是每台设备都显示"0h0m"—— 把一个"没有数据"的事实
     * 粉饰成了"今天一分钟都没用"，运营照着它判断活跃度必然失真。
     * 改成可空后，无数据就是无数据，前端显示"—"。
     */
    val todayUsageMinutes: Int?,
    /** 家长所属地区；无归属或家长未填时为 null（同样显示"—"，不要显示空字符串） */
    val region: String?,
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

/**
 * 单个 KPI 的趋势。
 *
 * 三个字段**全部可空**是刻意的：算不出来就必须说算不出来，
 * 而不是返回一条看起来很正常的假曲线。先前前端写死 `[3,5,4,6,7,9,8]` 这类字面量数组、
 * 文案硬编码成"环比 +12%"且方向与箭头自相矛盾（down 配 +12%），
 * 运营照着它做的判断就是错的 —— 这比"没有趋势图"危险得多。
 * 前端拿到 null 应当**不渲染**，而不是回落到任何硬编码数组。
 */
data class KpiTrend(
    /** 近 N 天（或近 N 月）序列，sparkline 数据源；数据不足以计算时为 null */
    val series: List<Int>?,
    /** 变化量：增长类 KPI 为"近 7 天新增数"；营收为"本月 − 上月"的差额 */
    val delta: Int?,
    /** delta 的单位：`count`=条数、`fen`=分（金额，前端按 ¥ 格式化） */
    val unit: String,
    /** 方向：up / down / flat；无法判定时为 null */
    val direction: String?,
    /** 变化量的语义标签，如"近 7 天""较上月"；由服务端给出以保证前后端口径一致 */
    val label: String
)

/** 仪表盘四张 KPI 卡的趋势数据（与 [Kpis] 四个卡片一一对应） */
data class DashboardTrends(
    val parent: KpiTrend,
    val device: KpiTrend,
    val subscription: KpiTrend,
    /** 营收：series 为近 6 月营收（分），delta 为本月较上月的差额（分） */
    val revenue: KpiTrend
)

data class DashboardResponse(
    val kpis: Kpis,
    val revenueSeries: List<Int>,
    val planDist: List<NameCount>,
    val regionDist: List<NameCount>,
    val alertsOpen: Long,
    val recent: List<AuditItem>,
    /** KPI 趋势；仅在有足够历史数据时返回，否则前端不画趋势线 */
    val trends: DashboardTrends? = null
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
    /** 最近 N 分钟（默认 30）每分钟请求数，用于趋势图；服务端重启后从 0 累积 */
    val qpmHistory: List<Int> = emptyList(),
    val errorRate: String,
    /** 最近请求平均耗时（毫秒） */
    val avgLatencyMs: Long = 0,
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

/**
 * 审计日志分页（截断）返回体。
 *
 * 关键在 [truncated] / [maxRows]：服务端对单次返回条数有上限。
 * 若只给一个裸数组，运营看到"共 200 条"会当这就是全部历史，
 * 进而据此下"最近没有异常操作"的结论 —— 而真实情况可能只是被截断了。
 * 把"我截断了、上限多少、筛选后实际匹配多少条"如实返回，比给一个看起来完整的列表更安全。
 */
data class AuditPageDto(
    val items: List<AuditItem>,
    /** true 表示 [items] 被 [maxRows] 截断，还有更多记录未返回 */
    val truncated: Boolean,
    /** 单次返回条数上限 */
    val maxRows: Int,
    /** 当前筛选条件下匹配到的总条数（截断前） */
    val total: Int
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
/** 自助修改自身密码：需当前密码；新密码至少 8 位（由服务端强制） */
data class AdminChangePasswordRequest(val currentPassword: String, val newPassword: String)
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
/** 新建自定义角色（后台 RBAC → 新建角色）。id 由前端按 name 生成的稳定标识，小写字母/数字/下划线 */
data class AdminRoleCreateRequest(
    val id: String,
    val name: String,
    val description: String? = null,
    val privileges: Map<String, List<String>> = emptyMap()
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
