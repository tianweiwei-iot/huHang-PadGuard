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
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

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

    fun today(userId: String, deviceId: String): UsageStatsDto {
        requireOwned(userId, deviceId)
        val (start, end) = dayRange(0)
        val logs = usageLogRepository.findByDeviceIdAndTimestampBetween(deviceId, start, end)
        val totalMin = sumMinutes(logs)
        val appUsages = topApps(logs, 50)
        return UsageStatsDto(deviceId, todayStr(), totalMin, appUsages)
    }

    fun usage(userId: String, deviceId: String, period: String): StatisticsReportDto {
        requireOwned(userId, deviceId)
        val days = when (period) { "weekly" -> 7; "monthly" -> 30; else -> 1 }
        val (start, end) = range(days)
        val logs = usageLogRepository.findByDeviceIdAndTimestampBetween(deviceId, start, end)
        val events = deviceEventRepository.findByDeviceIdAndTimestampBetween(deviceId, start, end)
        val daily = (0 until days).map { d ->
            val (ds, de) = dayRange(d)
            val dayLogs = logs.filter { (it.timestamp ?: 0) in ds..de }
            DailyUsageDto(dayStr(d), sumMinutes(dayLogs), dayLogs.count { it.type == "APP_USAGE" })
        }
        val alertCount = alertRepository.findByUserIdAndStatus(userId, "ACTIVE", Pageable.unpaged()).count { it.deviceId == deviceId }
        return StatisticsReportDto(
            deviceId = deviceId, period = period, startDate = dayStr(days - 1), endDate = todayStr(),
            totalUsageMinutes = sumMinutes(logs), dailyUsages = daily,
            topApps = topApps(logs, 10), violationCount = events.size, alertCount = alertCount
        )
    }

    fun web(userId: String, deviceId: String, period: String): WebActivityStatsDto {
        requireOwned(userId, deviceId)
        val days = when (period) { "weekly" -> 7; "monthly" -> 30; else -> 1 }
        val (start, end) = range(days)
        val logs = usageLogRepository.findByDeviceIdAndTimestampBetween(deviceId, start, end)
        val visits = logs.filter { it.type == "URL_VISIT" }
        val blocked = logs.filter { it.type == "URL_BLOCKED" }
        val domains = visits.mapNotNull { domainOf(it) }
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
        val url = fileStorageService.store("text/csv", bytes, "report_${deviceId}_$period.csv")
        return ExportResultDto(url, "report_${deviceId}_$period.csv", bytes.size.toLong())
    }

    // ---------- 工具 ----------
    /**
     * 解析单条 APP_USAGE 的时长（秒）。
     *
     * 兼容两种上报形态：早期孩子在端把 `durationSec` 序列化成字符串（"120"），
     * 服务端若只用 `as? Number` 强转会得到 null、时长永远被算成 0，
     * 家长看板"真实使用时长"始终为空。这里同时认数字与字符串，避免契约演进踩坑。
     */
    private fun durationSecOf(log: UsageLog): Int {
        val raw = parsePayload(log)["durationSec"] ?: return 0
        return when (raw) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull() ?: 0
            else -> 0
        }
    }

    private fun sumMinutes(logs: List<UsageLog>): Int =
        logs.filter { it.type == "APP_USAGE" }.sumOf { durationSecOf(it) / 60 }

    /**
     * 按应用聚合使用明细（时长 + 起止时间 + 图标 + 启动次数）。
     *
     * 三处容易踩空的细节：
     * 1. **起止时间取 min/max 而不是最后一条**：上报是按增量分段的，
     *    只有跨全部段取最小起点与最大终点，才是"今天从几点用到几点"；
     * 2. **时长以秒求和后再转分钟**：各段 `durationSec/60` 会把不足 1 分钟的使用直接抹成 0，
     *    孩子开一下应用又退出来，家长端就完全看不到这个应用；
     * 3. **图标来自台账而不是日志**：图标随应用台账上报，使用日志里没有，
     *    这里按包名回查 installed_apps 补上，查不到返回 null 让客户端回落默认图标。
     */
    private fun topApps(logs: List<UsageLog>, limit: Int): List<AppUsageDto> {
        val appLogs = logs.filter { it.type == "APP_USAGE" }
        if (appLogs.isEmpty()) return emptyList()

        val iconOf = installedAppRepository.findByDeviceId(deviceIdOf(appLogs))
            .associate { it.packageName to it.iconUrl }

        return appLogs
            .groupBy { parsePayload(it)["packageName"] as? String ?: "unknown" }
            .map { (pkg, ls) ->
                val payloads = ls.map { parsePayload(it) }
                val totalSec = ls.sumOf { durationSecOf(it) }
                val starts = payloads.mapNotNull { asLong(it["startAt"]) }
                val ends = payloads.mapNotNull { asLong(it["endAt"]) }
                AppUsageDto(
                    packageName = pkg,
                    appName = payloads.firstNotNullOfOrNull { it["appName"] as? String } ?: pkg,
                    usageMinutes = totalSec / 60,
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

    private fun domainOf(log: UsageLog): String? {
        val url = parsePayload(log)["url"] as? String ?: return null
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
