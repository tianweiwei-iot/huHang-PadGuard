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

/**
 * 在线态惰性超时阈值（与 [DeviceService] 的 ONLINE_STALE_MS 保持一致，单位毫秒）。
 *
 * 主判定由 [PresenceMonitorService] 在约 5 秒内把离线写回库，这里只是"库里的值可能
 * 略微滞后"时的读侧兜底，因此取 40 秒（= 落库刷新间隔 15s 的 2 倍余量）。
 * 改造前这里是 2 分钟：后台设备列表会把已关机两分钟内的设备继续显示为在线。
 */
const val ONLINE_STALE_MS: Long = 40_000L

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
    /** 家长账号注销：不可逆，含解绑设备与订单归属脱敏，仅超级管理员可执行 */
    const val PARENTS_ERASE = "PARENTS_ERASE"
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

    /** 手动清理 uploads/ 磁盘孤儿文件（盘上有、库里没记录）。危险运维操作，必须留痕。 */
    const val STORAGE_SWEEP = "STORAGE_SWEEP"
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

/** 与 [paginate] 相同的钳制规则（page 从 1 起、size 1..200），供 SQL 分页路径复用。 */
fun clampPage(page: Int): Int = if (page < 1) 1 else page
fun clampSize(size: Int): Int = when {
    size < 1 -> 20
    size > 200 -> 200
    else -> size
}

/**
 * 把 Spring Data 的 [org.springframework.data.domain.Page] 转成本项目的 [PageResult]。
 *
 * 两个细节与 [paginate] 严格对齐，否则"内存分页"与"SQL 分页"两条路径会给前端不同的信封：
 * ① 总数为 0 时 totalPages 返回 1（`Page.getTotalPages()` 返回 0，前端会据此画出"第 0 页"）；
 * ② size 用调用方钳制后的值，而不是 `Page.getSize()`（它反映的是请求值）。
 */
fun <T, R> toPageResult(
    page: org.springframework.data.domain.Page<T>,
    pageNo: Int,
    size: Int,
    transform: (T) -> R
): PageResult<R> {
    val total = page.totalElements.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val totalPages = if (total == 0) 1 else (total + size - 1) / size
    return PageResult(page.content.map(transform), pageNo, size, total, totalPages)
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
internal fun monthKey(ts: Long): String =
    Instant.ofEpochMilli(ts).atZone(SERVER_ZONE_ID).toLocalDate().let { "%04d-%02d".format(it.year, it.monthValue) }

/**
 * 营收分桶的输入单元：只有创建时间与金额。
 *
 * 存在的意义是不必为了画一根曲线把整张订单表的实体读进内存 ——
 * 见 [PaymentOrderRepository.findPaidAmountsSince]。
 */
data class PaidOrder(val createdAt: Long, val amountFen: Int)

/**
 * 近 [months] 个月窗口的起点时刻（含最早那个月的 1 日 00:00）。
 *
 * 取整月起点而不是 `now - 30*months` 天：后者会在月末把本该算进窗口首月的订单切出去，
 * 曲线最左端凭空少一截。
 */
private fun windowStart(months: Int, now: Long): Long {
    val zone = SERVER_ZONE_ID
    val firstMonth = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        .minusMonths((months - 1).coerceAtLeast(0).toLong())
        .withDayOfMonth(1)
    return firstMonth.atStartOfDay(zone).toInstant().toEpochMilli()
}

/**
 * 读出近 [months] 个月内已支付的订单（只查两列）。
 *
 * 调用方通常是"本月收入 + 近 N 月曲线"一起要 —— 务必**只调一次**再分别聚合。
 * 分两次查会让两个数字来自不同快照：两次查询之间有订单入账时，
 * 页面就会显示"本月营收 ¥X，但营收曲线最后一点是 ¥Y"。
 */
fun paidOrdersSince(
    paymentOrderRepository: PaymentOrderRepository,
    months: Int,
    now: Long = System.currentTimeMillis()
): List<PaidOrder> {
    val since = windowStart(months, now)
    return paymentOrderRepository.findPaidAmountsSince(OrderStatus.PAID.name, since).map { row ->
        PaidOrder(createdAt = (row[0] as Number).toLong(), amountFen = (row[1] as Number).toInt())
    }
}

/** 本月已支付金额（分）。传入的必须是同一批 [PaidOrder]，窗口需覆盖本月。 */
fun monthPaidSumOf(orders: List<PaidOrder>, now: Long): Int {
    val thisMonth = monthKey(now)
    return orders.filter { monthKey(it.createdAt) == thisMonth }.sumOf { it.amountFen }
}

fun inMonth(ts: Long, y: Int, m: Int): Boolean {
    // m 是 Calendar.MONTH 语义（0 基），与传入方保持一致后统一换算。
    return monthKey(ts) == "%04d-%02d".format(y, m + 1)
}

/** 近 [n] 个月的营收序列。传入的必须是同一批 [PaidOrder]，窗口需覆盖 n 个月。 */
fun lastMonthsRevenueOf(
    orders: List<PaidOrder>,
    n: Int,
    now: Long = System.currentTimeMillis()
): List<RevenueMonth> {
    val buckets = TreeMap<String, RevenueMonthAccum>()
    val today = Instant.ofEpochMilli(now).atZone(SERVER_ZONE_ID).toLocalDate()
    for (i in (n - 1) downTo 0) {
        val d = today.minusMonths(i.toLong())
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

fun lastMonthsRevenue(paymentOrderRepository: PaymentOrderRepository, n: Int): List<RevenueMonth> {
    val now = System.currentTimeMillis()
    return lastMonthsRevenueOf(paidOrdersSince(paymentOrderRepository, n, now), n, now)
}

data class RevenueMonthAccum(var month: String, var amount: Int = 0, var newSubs: Int = 0)

/**
 * 审计动作 →（领域, 中文名）的**唯一事实源**。
 *
 * 曾经是一个 `when` 表达式。改成表驱动不是为了好看，而是为了能反查：
 * 后台审计页按"领域"筛选时，必须先把领域映射成一组 action 才能下推到 SQL
 * （`where action in (...)`）。如果正向是 when、反向另写一份列表，两份必然漂移 ——
 * 新加一个动作、正向识别得到、反向漏掉，表现为"该动作只在不筛选时出现，一按领域筛就消失"。
 * 现在正反查都从这张表派生，物理上不可能不一致。
 */
val AUDIT_ACTION_META: Map<String, Pair<String, String>> = mapOf(
    AdminAudit.PARENTS_SUSPEND to ("parents" to "冻结家长账号"),
    AdminAudit.PARENTS_ACTIVATE to ("parents" to "激活家长账号"),
    AdminAudit.PARENTS_RESET_PWD to ("parents" to "重置家长密码"),
    AdminAudit.PARENTS_CREATE to ("parents" to "新建家长账号"),
    AdminAudit.PARENTS_UPDATE to ("parents" to "修改家长资料"),
    AdminAudit.PARENTS_DELETE to ("parents" to "删除家长账号"),
    AdminAudit.PARENTS_ERASE to ("parents" to "注销家长账号"),
    AdminAudit.DEVICES_LOCK to ("devices" to "远程锁屏"),
    AdminAudit.DEVICES_UNLOCK to ("devices" to "远程解锁"),
    AdminAudit.DEVICES_UNBIND to ("devices" to "解绑设备"),
    AdminAudit.BILLING_MANUAL to ("billing" to "手动开通订阅"),
    AdminAudit.BILLING_REFUND to ("billing" to "订单退款"),
    AdminAudit.RBAC_ASSIGN to ("rbac" to "变更管理员角色"),
    AdminAudit.RBAC_CREATE to ("rbac" to "新建管理员"),
    AdminAudit.RBAC_UPDATE to ("rbac" to "修改管理员"),
    AdminAudit.RBAC_DELETE to ("rbac" to "删除管理员"),
    AdminAudit.RBAC_RESET_PWD to ("rbac" to "重置管理员密码"),
    "RBAC_ROLE_SAVE" to ("rbac" to "保存角色权限"),
    AdminAudit.SETTINGS_WRITE to ("settings" to "修改系统配置"),
    AdminAudit.STORAGE_SWEEP to ("settings" to "清理磁盘孤儿文件"),
    "DEVICE_BIND" to ("devices" to "设备绑定"),
    "POLICY_WEB_UPDATE" to ("parents" to "策略变更(网页)"),
    "POLICY_APP_UPDATE" to ("parents" to "策略变更(应用)"),
    "UNLOCK_APPROVE" to ("devices" to "解锁审批通过"),
    "UNLOCK_REJECT" to ("devices" to "解锁审批拒绝"),
    "COMMAND_ISSUE" to ("devices" to "指令下发")
)

/**
 * 某领域下的全部动作。
 *
 * 未知领域返回**空集合**，而不是"全部动作"：调用方据此返回空列表。
 * 这条区别很重要 —— 若把空集合当成"不过滤"，前端传一个拼错的 area
 * 就会静默退化成全量列表，运营以为筛过了，实际看的是全领域数据。
 */
fun auditActionsOf(area: String): List<String> =
    AUDIT_ACTION_META.entries.filter { it.value.first == area }.map { it.key }

/** 未登记的动作归入空领域：仍会出现在"不筛选"的列表里，但不会被任何领域筛选命中。 */
fun resolveAuditAction(action: String): Pair<String, String> =
    AUDIT_ACTION_META[action] ?: ("" to action)
