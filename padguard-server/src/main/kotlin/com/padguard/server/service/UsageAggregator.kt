package com.padguard.server.service

import com.padguard.server.domain.UsageLog
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 用量统计的**流式折叠器**：把"两层聚合"从多趟遍历改成单趟增量累加。
 *
 * ## 为什么需要它
 * `usage_logs` 是孩子端每 10 秒上行一条、30 天保留的表，单设备一个月就有约 26 万行。
 * 原实现先把整段日志**全量捞进内存**、再按天 `filter` 一遍、再按应用 `groupBy` 一遍：
 * - 峰值内存 ≈ 行数 × （实体开销 + payload 字符串），月视图轻松上 GB，并发两个请求就可能 OOM；
 * - CPU 是 O(天数 × 行数)：30 天视图做 30 趟全扫，等于把同一批数据读了 30 次。
 *
 * 现在改成：分页拉取 → 逐条折叠进极小的累加器 → 丢弃。峰值内存只与
 * 「天数 × 应用数」有关（几十～几百个桶），与行数无关；CPU 从 O(天数 × 行数) 降为 O(行数)。
 *
 * ## 语义必须与旧实现逐位一致（这是重构的硬约束）
 * 孩子端上行的 `durationSec` 是**当天累计值**（带 `cumulative=true`），同一天有上百条，
 * 必须按「(应用, 天) 取 MAX」还原，直接求和会把同一条累计值累加 N 次
 * （曾线上表现为孩子端 3 分钟、家长端 1 小时 40 分）。旧版增量日志（无该标记）则必须求和。
 * 因此每个桶记录三个量：`是否出现过 cumulative`、`最大 durationSec`、`全部 durationSec 之和`——
 * 有 cumulative 取最大，否则取和。这与旧 `sumMinutes` / `topApps` 的判别规则完全同构。
 *
 * ## 总量口径的「按天选源」
 * 单日总量优先取 `USAGE_TOTAL`；某天**完全没有** `USAGE_TOTAL`（旧版孩子端只报 `APP_USAGE`）
 * 才回退到当天 `APP_USAGE`。这是**按天**判定而非整批判定：只要有一天只有 APP_USAGE，
 * 那天就必须回退，否则那天会显示 0。
 */
internal class UsageAggregator(
    private val zone: ZoneId,
    private val payloadOf: (UsageLog) -> Map<String, Any?>
) {
    /** 单个聚合桶：同时保留「取MAX」与「求和」两种口径所需的三元信息 */
    private class Bucket {
        var hasCumulative = false
        var maxSec = 0L
        var sumSec = 0L
        var count = 0

        fun add(sec: Long, cumulative: Boolean) {
            count++
            sumSec += sec
            if (sec > maxSec) maxSec = sec
            if (cumulative) hasCumulative = true
        }

        /** 有累计标记 → 按天取 MAX；纯旧版增量 → 按天求和 */
        fun totalSec(): Long = if (hasCumulative) maxSec else sumSec
    }

    private class DayBucket {
        val usageTotal = Bucket()
        val appUsage = Bucket()

        /** 当天 APP_USAGE 日志条数（家长端「违规/使用次数」列沿用这个口径） */
        var appLogCount = 0

        fun totalSec(): Long = if (usageTotal.count > 0) usageTotal.totalSec() else appUsage.totalSec()
    }

    private class PkgBucket {
        var appName: String? = null
        var startAt: Long? = null
        var endAt: Long? = null
        var launchCount = 0
        val byDay = HashMap<LocalDate, Bucket>()

        fun totalSec(): Long = byDay.values.sumOf { it.totalSec() }
    }

    private val days = HashMap<LocalDate, DayBucket>()
    private val packages = HashMap<String, PkgBucket>()
    private var accepted = 0

    fun accept(log: UsageLog) {
        val type = log.type ?: return
        if (type != TYPE_TOTAL && type != TYPE_APP) return
        accepted++

        val payload = payloadOf(log)
        val date = dayOf(log)
        val sec = durationSecOf(payload)
        val cumulative = payload["cumulative"]?.toString() == "true"

        val day = days.getOrPut(date) { DayBucket() }
        if (type == TYPE_TOTAL) {
            day.usageTotal.add(sec, cumulative)
            return
        }

        day.appUsage.add(sec, cumulative)
        day.appLogCount++

        val pkg = (payload["packageName"] as? String)?.takeUnless { it.isBlank() } ?: UNKNOWN_PACKAGE
        val bucket = packages.getOrPut(pkg) { PkgBucket() }
        if (bucket.appName == null) bucket.appName = payload["appName"] as? String
        bucket.launchCount++
        asLong(payload["startAt"])?.let { if (bucket.startAt == null || it < bucket.startAt!!) bucket.startAt = it }
        asLong(payload["endAt"])?.let { if (bucket.endAt == null || it > bucket.endAt!!) bucket.endAt = it }
        bucket.byDay.getOrPut(date) { Bucket() }.add(sec, cumulative)
    }

    /** 是否折叠到了任何用量类日志（用于区分「真的没用」与「压根没数据」） */
    fun isEmpty(): Boolean = accepted == 0

    /** 周期总时长（分钟）：各天「当天真实累计」之和 */
    fun totalMinutes(): Int = minutes(days.values.sumOf { it.totalSec() })

    /** 指定某天的使用分钟数（今日看板与逐日趋势共用同一份折叠结果） */
    fun minutesOn(date: LocalDate): Int = minutes(days[date]?.totalSec() ?: 0L)

    /** 指定某天的 APP_USAGE 条数（逐日趋势第三列） */
    fun appLogCountOn(date: LocalDate): Int = days[date]?.appLogCount ?: 0

    /**
     * 按应用聚合（时长降序取前 limit）。
     *
     * 时长是「先按 (应用, 天) 取当天真实累计 → 再跨天求和」，与 [totalMinutes] 同源，
     * 保证「各应用时长之和」与「总量」在正常数据下自洽。
     * 起止时间取全部段的 min/max 还原当天使用区间；图标由调用方按包名回查补上。
     */
    fun topApps(limit: Int, iconOf: (String) -> String?): List<com.padguard.server.dto.AppUsageDto> {
        if (packages.isEmpty() || limit <= 0) return emptyList()
        return packages.map { (pkg, b) ->
            com.padguard.server.dto.AppUsageDto(
                packageName = pkg,
                appName = b.appName ?: pkg,
                usageMinutes = minutes(b.totalSec()),
                iconUrl = iconOf(pkg),
                startAt = b.startAt,
                endAt = b.endAt,
                launchCount = b.launchCount
            )
        }.sortedByDescending { it.usageMinutes }.take(limit)
    }

    private fun dayOf(log: UsageLog): LocalDate =
        LocalDate.ofInstant(Instant.ofEpochMilli(log.timestamp ?: 0L), zone)

    companion object {
        const val TYPE_TOTAL = "USAGE_TOTAL"
        const val TYPE_APP = "APP_USAGE"
        const val UNKNOWN_PACKAGE = "unknown"

        /** 参与时长聚合的两类日志 */
        val USAGE_TYPES = listOf(TYPE_TOTAL, TYPE_APP)

        private fun minutes(sec: Long): Int = (sec / 60).toInt()

        /** payload 里的时长：可能是数字也可能是字符串，两种都要认 */
        private fun durationSecOf(payload: Map<String, Any?>): Long = when (val raw = payload["durationSec"]) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull() ?: 0L
            else -> 0L
        }

        /** 起止时间在 JSON 里可能是数字也可能是字符串，两种都要认；<=0 视为缺失 */
        fun asLong(raw: Any?): Long? = when (raw) {
            is Number -> raw.toLong().takeIf { it > 0 }
            is String -> raw.toLongOrNull()?.takeIf { it > 0 }
            else -> null
        }
    }
}

/**
 * 用量日志的分页读取器。
 *
 * ## 为什么必须分页
 * 见 [UsageAggregator] 类注释：一次 `findBy...Between` 把 26 万行连同 payload 文本读进内存，
 * 是"月视图一打开服务端就卡住 / OOM"的直接原因。这里按 [PAGE_SIZE] 分批取、折叠完即丢弃。
 *
 * ## 排序为什么必须显式且带次级键
 * 分页靠 OFFSET 推进，若 SQL 没有确定顺序，两次查询之间的行次序可能不同 →
 * 同一行被读两次、另一行被漏掉，聚合结果静默偏差。因此固定 `(timestamp, id)` 升序：
 * timestamp 保证时间局部性（走 `idx_usage_logs_device_ts`），id 兜住同毫秒并列的情况。
 *
 * ## 为什么不用 count
 * 只需要"是否还有下一页"，用 `size < PAGE_SIZE` 判断即可；
 * 每页多跑一次 `count(*)` 在全表 26 万行时是纯浪费。
 */
internal object UsageLogPager {
    const val PAGE_SIZE = 2_000

    val SORT: Sort = Sort.by(Sort.Order.asc("timestamp"), Sort.Order.asc("id"))

    fun pageable(page: Int): PageRequest = PageRequest.of(page, PAGE_SIZE, SORT)

    /**
     * 逐页读取并消费，返回实际消费的行数。
     *
     * @param fetch 取第 N 页（由调用方绑定设备/时间/类型条件）
     */
    fun read(fetch: (PageRequest) -> List<UsageLog>, consume: (UsageLog) -> Unit): Int {
        var page = 0
        var total = 0
        while (true) {
            val batch = fetch(pageable(page))
            if (batch.isEmpty()) break
            batch.forEach {
                consume(it)
                total++
            }
            // 不满一页 = 已经是最后一页；满页则继续，直到取到空页（正好整除时多一次空查询）
            if (batch.size < PAGE_SIZE) break
            page++
        }
        return total
    }
}
