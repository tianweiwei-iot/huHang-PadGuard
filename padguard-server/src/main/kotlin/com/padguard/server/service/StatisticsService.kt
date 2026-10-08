package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.UsageLog
import com.padguard.server.dto.*
import com.padguard.server.repository.AlertRepository
import com.padguard.server.repository.DeviceEventRepository
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.InstalledAppRepository
import com.padguard.server.repository.UsageLogRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@Service
class StatisticsService(
    private val deviceRepository: DeviceRepository,
    private val usageLogRepository: UsageLogRepository,
    private val deviceEventRepository: DeviceEventRepository,
    private val alertRepository: AlertRepository,
    private val installedAppRepository: InstalledAppRepository,
    private val fileStorageService: FileStorageService,
    private val objectMapper: ObjectMapper
) {
    private val zone = ZoneId.of("Asia/Shanghai")

    /**
     * 今日用量缓存：孩子端每 10 秒上行一次，家长端既有 10 秒轮询兜底、又新增 WS 实时推送，
     * 二者都会频繁调用 [today]。每次都重扫全天的 usage_logs + 反序列化 payload 并不便宜，
     * 故在此做一层短 TTL 缓存（[TODAY_CACHE_TTL_MS]）：
     * - 新日志入库时由 [invalidateToday] 失效，保证推送/轮询拿到的是最新值；
     * - 两次入库之间的轮询直接命中缓存，省去重扫与反序列化。
     * 缓存按 deviceId 维度，单设备单条，内存占用可忽略。
     */
    private val todayCache = ConcurrentHashMap<String, CachedToday>()
    private data class CachedToday(val dto: UsageStatsDto, val ts: Long)
    private companion object {
        const val TODAY_CACHE_TTL_MS = 15_000L
    }

    /** 新 usage 日志入库后调用，使该设备接下来的 today() 重算而非命中旧缓存 */
    fun invalidateToday(deviceId: String) {
        todayCache.remove(deviceId)
    }

    fun today(userId: String, deviceId: String): UsageStatsDto {
        requireOwned(userId, deviceId)
        val cached = todayCache[deviceId]
        if (cached != null && System.currentTimeMillis() - cached.ts < TODAY_CACHE_TTL_MS) {
            return cached.dto
        }
        val (start, end) = dayRange(0)
        val logs = usageLogRepository.findByDeviceIdAndTimestampBetween(deviceId, start, end)
        val idx = PayloadIndex(logs, ::parsePayload)
        val totalMin = sumMinutes(logs, idx)
        val appUsages = topApps(logs, idx, 50)
        val dto = UsageStatsDto(deviceId, todayStr(), totalMin, appUsages)
        todayCache[deviceId] = CachedToday(dto, System.currentTimeMillis())
        return dto
    }

    fun usage(userId: String, deviceId: String, period: String): StatisticsReportDto {
        requireOwned(userId, deviceId)
        val days = when (period) { "weekly" -> 7; "monthly" -> 30; else -> 1 }
        val (start, end) = range(days)
        val logs = usageLogRepository.findByDeviceIdAndTimestampBetween(deviceId, start, end)
        val events = deviceEventRepository.findByDeviceIdAndTimestampBetween(deviceId, start, end)
        val idx = PayloadIndex(logs, ::parsePayload)
        val daily = (0 until days).map { d ->
            val (ds, de) = dayRange(d)
            val dayLogs = logs.filter { (it.timestamp ?: 0) in ds..de }
            DailyUsageDto(dayStr(d), sumMinutes(dayLogs, idx), dayLogs.count { it.type == "APP_USAGE" })
        }
        // 计数下沉到 SQL：原先是捞回该用户全部 ACTIVE 告警再在内存里 count 出属于本设备的，
        // 告警累积后这里会拉回大量用不到的行。
        val alertCount = alertRepository.countByUserIdAndStatusAndDeviceId(userId, "ACTIVE", deviceId).toInt()
        return StatisticsReportDto(
            deviceId = deviceId, period = period, startDate = dayStr(days - 1), endDate = todayStr(),
            totalUsageMinutes = sumMinutes(logs, idx), dailyUsages = daily,
            topApps = topApps(logs, idx, 10), violationCount = events.size, alertCount = alertCount
        )
    }

    /**
     * 单应用在指定周期内的逐次使用记录（按时间倒序）。
     *
     * 数据源就是已有的 `APP_USAGE` 用量日志：孩子端每次上报一个使用段（带 startAt / endAt），
     * 因此不需要孩子端再做任何新上报 —— 历史一直都在库里，缺的只是把它按应用捞出来的出口。
     *
     * 之所以此前的 "应用使用历史" 页只能报错：该出口从未实现，而家长端的入口已经通到二级页，
     * 点进去必然落到错误态。这里补上，同时保持与 [topApps] 一致的时区口径（服务端统一时区）。
     *
     * @param packageName 目标应用包名
     * @param period      daily / weekly / monthly
     */
    fun appUsageHistory(
        userId: String,
        deviceId: String,
        packageName: String,
        period: String
    ): List<AppUsageHistoryEntryDto> {
        requireOwned(userId, deviceId)
        if (packageName.isBlank()) return emptyList()
        val days = when (period) { "weekly" -> 7; "monthly" -> 30; else -> 1 }
        val (start, end) = range(days)
        val logs = usageLogRepository.findByDeviceIdAndTimestampBetween(deviceId, start, end)
        val idx = PayloadIndex(logs, ::parsePayload)

        val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")

        return logs.asSequence()
            .filter { it.type == "APP_USAGE" }
            .map { idx.of(it) }
            .filter { it["packageName"] as? String == packageName }
            .mapNotNull { p ->
                // 缺 startAt/endAt 的旧日志无法还原"一次打开到关闭"的区间，直接跳过而不是塞 0——
                // 占位数据会让家长误以为孩子"在某个时间打开了但没关"，比不显示更糟。
                val s = asLong(p["startAt"]) ?: return@mapNotNull null
                val e = asLong(p["endAt"]) ?: return@mapNotNull null
                if (e < s) return@mapNotNull null
                val startDt = LocalDateTime.ofInstant(Instant.ofEpochMilli(s), zone)
                val endDt = LocalDateTime.ofInstant(Instant.ofEpochMilli(e), zone)
                AppUsageHistoryEntryDto(
                    date = startDt.format(dateFmt),
                    startTime = startDt.format(timeFmt),
                    endTime = endDt.format(timeFmt),
                    usageSeconds = durationSecFrom(p).takeIf { it > 0 } ?: ((e - s) / 1000).toInt()
                )
            }
            .sortedByDescending { "${it.date} ${it.startTime}" }
            .toList()
    }

    fun web(userId: String, deviceId: String, period: String): WebActivityStatsDto {
        requireOwned(userId, deviceId)
        val days = when (period) { "weekly" -> 7; "monthly" -> 30; else -> 1 }
        val (start, end) = range(days)
        val logs = usageLogRepository.findByDeviceIdAndTimestampBetween(deviceId, start, end)
        val idx = PayloadIndex(logs, ::parsePayload)
        val visits = logs.filter { it.type == "URL_VISIT" }
        val blocked = logs.filter { it.type == "URL_BLOCKED" }
        val domains = visits.mapNotNull { domainOf(it, idx) }
            .groupingBy { it }.eachCount()
            .toList().sortedByDescending { it.second }.take(10)
            .map { DomainVisitDto(it.first, it.second, System.currentTimeMillis()) }
        return WebActivityStatsDto(visits.size, domains, blocked.size)
    }

    fun violations(userId: String, deviceId: String, period: String): ViolationStatsDto {
        requireOwned(userId, deviceId)
        val days = when (period) { "weekly" -> 7; "monthly" -> 30; else -> 1 }
        val (start, end) = range(days)
        val events = deviceEventRepository.findByDeviceIdAndTimestampBetween(deviceId, start, end)
        val byCategory = events.groupingBy { it.type ?: "OTHER" }.eachCount()
        val trend = (0 until days).map { d ->
            val (ds, de) = dayRange(d)
            DailyViolationDto(dayStr(d), events.count { (it.timestamp ?: 0) in ds..de })
        }
        return ViolationStatsDto(events.size, byCategory, trend)
    }

    fun export(userId: String, deviceId: String, period: String): ExportResultDto {
        val report = usage(userId, deviceId, period)
        val sb = StringBuilder()
        sb.appendLine("设备,$deviceId,周期,$period")
        sb.appendLine("总使用时长(分钟),${report.totalUsageMinutes}")
        sb.appendLine("违规次数,${report.violationCount}")
        sb.appendLine("告警数,${report.alertCount}")
        sb.appendLine("日期,时长(分钟),违规数")
        report.dailyUsages?.forEach { sb.appendLine("${it.date},${it.usageMinutes},${it.violationCount}") }
        val bytes = sb.toString().toByteArray(Charsets.UTF_8)
        // 导出报表含孩子的使用明细，归属到请求者，避免下载链接泄露后被别人拉走
        val url = fileStorageService.store(
            "text/csv", bytes, "report_${deviceId}_$period.csv", ownerUserId = userId
        )
        return ExportResultDto(url, "report_${deviceId}_$period.csv", bytes.size.toLong())
    }

    // ---------- 工具 ----------

    /** 从**已解析**的 payload 取时长，避免对同一份 JSON 重复反序列化 */
    private fun durationSecFrom(payload: Map<String, Any?>): Int {
        val raw = payload["durationSec"] ?: return 0
        return when (raw) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull() ?: 0
            else -> 0
        }
    }

    /**
     * 汇总一批日志的使用时长（分钟）。
     *
     * ## 上报语义：累计值 + 当天取最大值
     * 新版孩子端上报的是「当天累计值」（durationSec = 截至当前的当天累计秒），并带 `cumulative=true` 标记。
     * 同一天会收到多条（每 10s 一条），必须按「天」取最大值才能还原当天真实累计——
     * 直接求和会把同一条累计值累加 N 次，导致家长端"今日已用"被高估数倍
     * （2026-10-03 现场：孩子端 3 分钟，家长端 1 小时 40 分）。
     *
     * ## 兼容旧版增量日志
     * 升级前孩子端报的是「增量」且不含 `cumulative` 标记：对这类日志必须求和
     * （MAX 只会取到单段增量，严重低估）。判别规则：某一天内只要有一条带 `cumulative=true`，
     * 当天就按 MAX 聚合；否则（全是旧增量）按 SUM 聚合。过渡当天若新旧混合，
     * 新日志的累计值恒 ≥ 旧增量，MAX 自然取到真实累计，两种写法都能正确还原。
     *
     * ## 周期总时长
     * 跨天调用时（周/月视图）先按天取 MAX 再跨天求和，即得到周期总时长；
     * 单天调用（今日）则退化为"当天 MAX"，三种调用场景统一正确。
     */
    private fun sumMinutes(logs: List<UsageLog>, idx: PayloadIndex): Int {
        val typed = logs.filter { it.type == "USAGE_TOTAL" || it.type == "APP_USAGE" }
        if (typed.isEmpty()) return 0
        // 优先用 USAGE_TOTAL；若整批没有（旧版孩子端只报 APP_USAGE）才回退 APP_USAGE。
        // 有 USAGE_TOTAL 时直接忽略 APP_USAGE，避免同一段时长被算两遍。
        val source = if (typed.any { it.type == "USAGE_TOTAL" }) {
            typed.filter { it.type == "USAGE_TOTAL" }
        } else {
            typed
        }
        val totalSec = source
            .groupBy { LocalDate.ofInstant(Instant.ofEpochMilli(it.timestamp ?: 0), zone) }
            .values
            .sumOf { dayLogs ->
                val cumulative = dayLogs.any { idx.of(it)["cumulative"]?.toString() == "true" }
                if (cumulative) {
                    dayLogs.maxOfOrNull { durationSecFrom(idx.of(it)).toLong() } ?: 0L
                } else {
                    dayLogs.sumOf { durationSecFrom(idx.of(it)).toLong() }
                }
            }
        return (totalSec / 60).toInt()
    }

    /**
     * 按应用聚合使用明细（时长 + 起止时间 + 图标 + 启动次数）。
     *
     * 时长聚合与 [sumMinutes] 同理：上报的是「当天累计值」，需按 (应用, 天) 取 MAX 还原每天真实用量，
     * 再跨天求和得到周期总用量；旧版增量日志（无 `cumulative` 标记）则按段求和。
     * 起止时间取全部段 min/max 即还原出当天使用区间；图标按包名回查 installed_apps 补上。
     */
    private fun topApps(logs: List<UsageLog>, idx: PayloadIndex, limit: Int): List<AppUsageDto> {
        val appLogs = logs.filter { it.type == "APP_USAGE" }
        if (appLogs.isEmpty()) return emptyList()

        val iconOf = installedAppRepository.findByDeviceId(deviceIdOf(appLogs))
            .associate { it.packageName to it.iconUrl }

        val index = idx
        return appLogs
            .groupBy { index.of(it)["packageName"] as? String ?: "unknown" }
            .map { (pkg, ls) ->
                val payloads = ls.map { index.of(it) }
                // 按 (应用, 天) 取 MAX（累计）/ 求和（旧增量），再跨天求和 = 周期总用量
                val totalSec = ls
                    .groupBy { LocalDate.ofInstant(Instant.ofEpochMilli(it.timestamp ?: 0), zone) }
                    .values
                    .sumOf { dayLogs ->
                        val cumulative = dayLogs.any { index.of(it)["cumulative"]?.toString() == "true" }
                        if (cumulative) {
                            dayLogs.maxOfOrNull { durationSecFrom(index.of(it)).toLong() } ?: 0L
                        } else {
                            dayLogs.sumOf { durationSecFrom(index.of(it)).toLong() }
                        }
                    }
                val starts = payloads.mapNotNull { asLong(it["startAt"]) }
                val ends = payloads.mapNotNull { asLong(it["endAt"]) }
                AppUsageDto(
                    packageName = pkg,
                    appName = payloads.firstNotNullOfOrNull { it["appName"] as? String } ?: pkg,
                    usageMinutes = (totalSec / 60).toInt(),
                    iconUrl = iconOf[pkg],
                    startAt = starts.minOrNull(),
                    endAt = ends.maxOrNull(),
                    launchCount = ls.size
                )
            }
            .sortedByDescending { it.usageMinutes }
            .take(limit)
    }

    /** 从这批日志反推设备 ID（聚合图标时需要按设备查台账） */
    private fun deviceIdOf(logs: List<UsageLog>): String = logs.first().deviceId

    /** 起止时间在 JSON 里可能是数字也可能是字符串，两种都要认 */
    private fun asLong(raw: Any?): Long? = when (raw) {
        is Number -> raw.toLong().takeIf { it > 0 }
        is String -> raw.toLongOrNull()?.takeIf { it > 0 }
        else -> null
    }

    private fun domainOf(log: UsageLog, idx: PayloadIndex): String? {
        val url = idx.of(log)["url"] as? String ?: return null
        return runCatching { java.net.URI(url).host }.getOrNull() ?: url.split("/").getOrNull(0)
    }

    private fun parsePayload(log: UsageLog): Map<String, Any?> =
        log.payloadJson?.let { runCatching { objectMapper.readValue(it, Map::class.java) as Map<String, Any?> }.getOrNull() } ?: emptyMap()

    private fun requireOwned(userId: String, deviceId: String) {
        val dev = deviceRepository.findById(deviceId).orElse(null)
            ?: throw BizException(ParentErr.DEVICE_NOT_FOUND, "设备不存在", Audience.PARENT)
        if (dev.userId != userId) {
            throw BizException(ParentErr.DEVICE_NOT_OWNED, "设备不属于当前用户", Audience.PARENT)
        }
    }

    private fun range(days: Int): Pair<Long, Long> {
        val end = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val start = LocalDate.now(zone).minusDays((days - 1).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        return start to end
    }

    private fun dayRange(offsetDays: Int): Pair<Long, Long> {
        val day = LocalDate.now(zone).minusDays(offsetDays.toLong())
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start to end
    }

    private fun dayStr(offsetDays: Int): String =
        LocalDate.now(zone).minusDays(offsetDays.toLong()).format(DateTimeFormatter.ISO_LOCAL_DATE)

    private fun todayStr(): String = LocalDate.now(zone).format(DateTimeFormatter.ISO_LOCAL_DATE)
}

/**
 * 一批日志的 payload 解析索引：保证同一条日志的 JSON 在一次统计请求里只反序列化一次。
 *
 * 统计接口会对同一批日志做多轮遍历（总时长、按应用聚合、按天分桶、域名统计……），
 * 若每轮都重新解析 payload，就是数倍的反序列化开销——而这是纯浪费，
 * 同一条日志的 payload 文本在各轮里完全一致。
 *
 * JPA 实体未重写 equals/hashCode，因此以实例身份作为 HashMap 键是安全的。
 * 未预热的日志回退为现解析，保证调用方漏传时语义依然正确（不会静默返回空 map）。
 */
private class PayloadIndex(
    logs: List<UsageLog>,
    private val parse: (UsageLog) -> Map<String, Any?>
) {
    private val cache: Map<UsageLog, Map<String, Any?>> = logs.associateWith(parse)

    fun of(log: UsageLog): Map<String, Any?> = cache[log] ?: parse(log)
}
