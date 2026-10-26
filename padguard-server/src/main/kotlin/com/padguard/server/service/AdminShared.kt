package com.padguard.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.common.SERVER_ZONE_ID
import com.padguard.server.domain.*
import com.padguard.server.dto.AdminDto
import com.padguard.server.dto.PageResult
import com.padguard.server.dto.RevenueMonth
import com.padguard.server.repository.AdminRepository
import com.padguard.server.repository.PaymentOrderRepository
import com.padguard.server.repository.SystemSettingRepository
import com.padguard.server.security.AdminPermissionService
import org.springframework.core.env.Environment
import java.sql.Connection
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import javax.sql.DataSource

/**
 * 后台管理域的跨领域共享工具与常量。
 *
 * 后台管理域跨领域共享的纯函数 / 常量 / 数据类，全部以包级（top-level）
 * 声明，供拆分后的各子 [org.springframework.stereotype.Service] 直接复用，无需层层传参或重复定义。
 * 这些符号原本是 AdminService 的 private 成员，现在仍只在 `com.padguard.server.service` 包内可见、被使用。
 */

/** 监控指标用：进程启动时刻（近似应用启动） */
val startedAt: Long = System.currentTimeMillis()

/** 在线态惰性超时阈值（与 DeviceService 一致，单位毫秒） */
const val ONLINE_STALE_MS: Long = 120_000L

val FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/** 套餐权益描述（运营展示用，不进数据库） */
val PLAN_FEATURES: Map<String, List<String>> = mapOf(
    "MONTHLY" to listOf("3 台设备", "远程锁屏/解锁", "分龄合规", "风险预警", "历史报表"),
    "QUARTERLY" to listOf("3 台设备", "全部月度权益", "季度更省"),
    "YEARLY" to listOf("5 台设备", "全部功能", "实时监控看屏", "电子围栏", "优先客服")
)

/** 后台管理操作审计动作常量（与 audit 视图 area 分类对应） */
object AdminAudit {
    const val PARENTS_SUSPEND = "PARENTS_SUSPEND"
    const val PARENTS_ACTIVATE = "PARENTS_ACTIVATE"
    const val PARENTS_RESET_PWD = "PARENTS_RESET_PWD"
    const val PARENTS_CREATE = "PARENTS_CREATE"
    const val PARENTS_UPDATE = "PARENTS_UPDATE"
    const val PARENTS_DELETE = "PARENTS_DELETE"
    const val DEVICES_LOCK = "DEVICES_LOCK"
    const val DEVICES_UNBIND = "DEVICE_UNBIND"
    const val DEVICES_UNLOCK = "DEVICES_UNLOCK"
    const val BILLING_MANUAL = "BILLING_MANUAL"
    const val BILLING_REFUND = "BILLING_REFUND"
    const val RBAC_ASSIGN = "RBAC_ASSIGN"
    const val RBAC_CREATE = "RBAC_CREATE"
    const val RBAC_UPDATE = "RBAC_UPDATE"
    const val RBAC_DELETE = "RBAC_DELETE"
    const val RBAC_RESET_PWD = "RBAC_RESET_PWD"
    const val SETTINGS_WRITE = "SETTINGS_WRITE"
}

fun fmt(ms: Long?): String? {
    if (ms == null || ms == 0L) return null
    return FMT.format(Instant.ofEpochMilli(ms).atZone(SERVER_ZONE_ID))
}

fun online(dev: Device): Boolean {
    if (dev.onlineStatus != "ONLINE") return false
    val last = dev.lastOnlineAt ?: return false
    return System.currentTimeMillis() - last <= ONLINE_STALE_MS
}

/**
 * 北京时间下的 "yyyy-MM-dd" 日键，用于按天切分 usage_sessions。
 */
fun todayKey(now: Long): String =
    Instant.ofEpochMilli(now).atZone(SERVER_ZONE_ID).toLocalDate().toString()

/**
 * 设备当日已用时长（分钟），从 usage_sessions 派生：从计时起点到现在、扣除护眼强制休息、受当日额度封顶。
 *
 * 这是 B1 修复的核心 —— 此前后台设备列表把"今日用量"恒写为 0，家长看到的永远是 0h0m（前端对 0 显示"—"）。
 * 现在改为读取真实计时会话：startEpoch 为空（今日尚未开始计时）或会话不属于今天时返回 0，
 * 否则返回 (now - start - rest) ∩ (quota + extended)。
 */
fun todayUsedMinutes(session: UsageSession?, now: Long): Int {
    val s = session ?: return 0
    if (s.dayKey != todayKey(now)) return 0
    val start = s.startEpoch ?: return 0
    val raw = (now - start) - s.restAccumulatedMs
    if (raw <= 0) return 0
    val capMs = (s.quotaMinutes + s.extendedMinutes) * 60_000L
    val capped = if (capMs > 0) minOf(raw, capMs) else raw
    return ((capped + 59_999) / 60_000).toInt()
}

/** 套餐归类（用于后台筛选与分布统计）。PERMANENT 单独成类，避免被误判为 free。 */
fun planCategory(planKey: String?): String = when ((planKey ?: "").uppercase()) {
    "TRIAL" -> "free"
    "MONTHLY", "QUARTERLY" -> "monthly"
    "YEARLY" -> "yearly"
    "PERMANENT" -> "permanent"
    else -> "free"
}

fun planLabel(planKey: String?): String =
    if (planKey.isNullOrBlank()) "免费版" else (Plan.of(planKey)?.label ?: "免费版")

fun subStatusLower(status: String): String = status.lowercase()

fun orderStatusLower(status: String): String = when (status.uppercase()) {
    "PAID" -> "paid"
    "REFUNDED" -> "refunded"
    "PENDING" -> "unpaid"
    "CLOSED" -> "closed"
    "FAILED" -> "failed"
    else -> status.lowercase()
}

fun toAdminDto(a: Admin, adminPerm: AdminPermissionService? = null): AdminDto = AdminDto(
    id = a.id, username = a.username, name = a.displayName, roleId = a.role,
    roleName = AdminRoles.roleName(a.role), email = a.email, status = a.status,
    mfa = a.mfaEnabled, createdAt = a.createdAt, lastLoginAt = a.lastLoginAt,
    privileges = adminPerm?.rolePrivileges(a.role) ?: emptyMap()
)

/**
 * 内存分页（B3/B4）：对已经过筛选的全量集合做切片，返回分页信封。
 * 单页上限 200，避免超大 page/size 拖垮服务端；page 从 1 开始。
 */
fun <T> paginate(all: List<T>, page: Int, size: Int): PageResult<T> {
    val p = if (page < 1) 1 else page
    val s = when {
        size < 1 -> 20
        size > 200 -> 200
        else -> size
    }
    val total = all.size
    val totalPages = if (total == 0) 1 else (total + s - 1) / s
    val from = (p - 1) * s
    if (from >= total) return PageResult(emptyList(), p, s, total, totalPages)
    val to = minOf(from + s, total)
    return PageResult(all.subList(from, to), p, s, total, totalPages)
}

/**
 * 读操作的权限闸门。早期服务端只对写操作 require，全系统没有任何一处校验过 read —— 因此
 * 在 RBAC 矩阵里给某角色勾选或取消「查看」在服务端毫无区别。现在所有返回业务列表的方法都在首行过这道闸门。
 */
fun requireRead(adminPerm: AdminPermissionService, adminId: String, area: String) =
    adminPerm.require(adminId, area, "read")

/**
 * 防止越权提升为超级管理员：super 角色只能由 super 本身授予或变更。
 */
fun ensureNotEscalatingToSuper(adminRepository: AdminRepository, actorId: String, roleId: String) {
    if (roleId == "super" && adminRepository.findById(actorId).orElse(null)?.role != "super") {
        throw BizException(ParentErr.FORBIDDEN, "只有超级管理员可授予或变更超级管理员角色", Audience.PARENT)
    }
}

fun dbUp(dataSource: DataSource): Boolean = runCatching {
    dataSource.connection.use { conn: Connection ->
        conn.createStatement().use { st -> st.executeQuery("SELECT 1").use { rs -> rs.next() } }
    }
}.getOrDefault(false)

/**
 * 系统设置存储结构（admin.settings）。默认值必须与 `padguard.billing.trial-days` 一致（当前同为 30 天）。
 */
data class SettingsStore(
    val brand: String = "PadGuard",
    val currency: String = "CNY",
    /**
     * 新用户注册后赠送的免费试用天数。默认值必须与 `padguard.billing.trial-days` 一致（免费内测阶段延长为 30 天）：
     * 这里是运营可见并可改的唯一入口，[com.padguard.server.service.BillingService] 创建试用时会优先读它。
     */
    val trialDays: Int = 30,
    val passwordPolicy: String = "medium",
    val mfaRequired: Boolean = false,
    val mockPay: Boolean = false,
    val planPrices: Map<String, Int> = emptyMap()
)

fun loadSettingsStore(repo: SystemSettingRepository, om: ObjectMapper): SettingsStore =
    runCatching {
        repo.findById("admin.settings").orElse(null)?.valueJson
            ?.let { om.readValue(it, SettingsStore::class.java) }
    }.getOrNull() ?: SettingsStore()

/** 按后台「系统设置 → 密码策略」生成初始 / 重置密码；策略读取失败回落 medium。 */
fun genPassword(systemSettingRepository: SystemSettingRepository, om: ObjectMapper): String {
    val policy = runCatching { loadSettingsStore(systemSettingRepository, om).passwordPolicy }.getOrDefault("medium")
    val (len, withSymbol) = when (policy) {
        "low" -> 8 to false
        "high" -> 16 to true
        else -> 12 to true
    }
    val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
    val rnd = java.security.SecureRandom()
    val core = (1..len).joinToString("") { chars[rnd.nextInt(chars.length)].toString() }
    return if (withSymbol) core + "!" else core
}

/** 当前是否处于开发类 profile（local/dev）。示例支付仅允许在此类环境开启。 */
fun isDevProfile(env: Environment): Boolean =
    env.activeProfiles.any { it.equals("local", true) || it.equals("dev", true) }

/**
 * 北京时间下的 "yyyy-MM" 分桶键。
 *
 * 后台营收统计的"本月/近 N 月"必须按服务端统一时区切月，不能跟着 JVM 默认时区走：
 * 容器未锁 TZ 时按月边界会整体偏移，月末最后一天与月初第一天的订单会被算进相邻月份，
 * 表现为"后台月收入与订单明细对不上"，且这类差异只在月末出现，极难复现。
 */
private fun monthKey(ts: Long): String =
    Instant.ofEpochMilli(ts).atZone(SERVER_ZONE_ID).toLocalDate().let { "%04d-%02d".format(it.year, it.monthValue) }

fun monthPaidSum(paymentOrderRepository: PaymentOrderRepository): Int {
    val thisMonth = monthKey(System.currentTimeMillis())
    return paymentOrderRepository.findAllByOrderByCreatedAtDesc()
        .filter { it.status == OrderStatus.PAID.name && monthKey(it.createdAt) == thisMonth }
        .sumOf { it.amountFen }
}

fun inMonth(ts: Long, y: Int, m: Int): Boolean {
    // m 是 Calendar.MONTH 语义（0 基），与传入方保持一致后统一换算。
    return monthKey(ts) == "%04d-%02d".format(y, m + 1)
}

fun lastMonthsRevenue(paymentOrderRepository: PaymentOrderRepository, n: Int): List<RevenueMonth> {
    val orders = paymentOrderRepository.findAllByOrderByCreatedAtDesc().filter { it.status == OrderStatus.PAID.name }
    val buckets = TreeMap<String, RevenueMonthAccum>()
    val now = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(SERVER_ZONE_ID).toLocalDate()
    for (i in (n - 1) downTo 0) {
        val d = now.minusMonths(i.toLong())
        val key = "%04d-%02d".format(d.year, d.monthValue)
        buckets[key] = RevenueMonthAccum(key)
    }
    for (o in orders) {
        buckets[monthKey(o.createdAt)]?.let { acc ->
            acc.amount += o.amountFen
            acc.newSubs += 1
        }
    }
    return buckets.values.map { RevenueMonth(it.month, it.amount, it.newSubs, 0) }
}

data class RevenueMonthAccum(var month: String, var amount: Int = 0, var newSubs: Int = 0)

/** 审计动作 → （领域, 中文名），覆盖后台操作与常见家长端动作 */
fun resolveAuditAction(action: String): Pair<String, String> = when (action) {
    AdminAudit.PARENTS_SUSPEND -> "parents" to "冻结家长账号"
    AdminAudit.PARENTS_ACTIVATE -> "parents" to "激活家长账号"
    AdminAudit.PARENTS_RESET_PWD -> "parents" to "重置家长密码"
    AdminAudit.PARENTS_CREATE -> "parents" to "新建家长账号"
    AdminAudit.PARENTS_UPDATE -> "parents" to "修改家长资料"
    AdminAudit.PARENTS_DELETE -> "parents" to "删除家长账号"
    AdminAudit.DEVICES_LOCK -> "devices" to "远程锁屏"
    AdminAudit.DEVICES_UNLOCK -> "devices" to "远程解锁"
    AdminAudit.BILLING_MANUAL -> "billing" to "手动开通订阅"
    AdminAudit.BILLING_REFUND -> "billing" to "订单退款"
    AdminAudit.RBAC_ASSIGN -> "rbac" to "变更管理员角色"
    AdminAudit.RBAC_CREATE -> "rbac" to "新建管理员"
    AdminAudit.RBAC_UPDATE -> "rbac" to "修改管理员"
    AdminAudit.RBAC_DELETE -> "rbac" to "删除管理员"
    AdminAudit.RBAC_RESET_PWD -> "rbac" to "重置管理员密码"
    "RBAC_ROLE_SAVE" -> "rbac" to "保存角色权限"
    AdminAudit.SETTINGS_WRITE -> "settings" to "修改系统配置"
    "DEVICE_BIND" -> "devices" to "设备绑定"
    "DEVICE_UNBIND" -> "devices" to "设备解绑"
    "POLICY_WEB_UPDATE" -> "parents" to "策略变更(网页)"
    "POLICY_APP_UPDATE" -> "parents" to "策略变更(应用)"
    "UNLOCK_APPROVE" -> "devices" to "解锁审批通过"
    "UNLOCK_REJECT" -> "devices" to "解锁审批拒绝"
    "COMMAND_ISSUE" -> "devices" to "指令下发"
    else -> "" to action
}
