package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.UsageLog
import com.padguard.server.dto.*
import com.padguard.server.repository.AlertRepository
import com.padguard.server.repository.DeviceEventRepository
import com.padguard.server.repository.DeviceRepository
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
    private fun sumMinutes(logs: List<UsageLog>): Int =
        logs.filter { it.type == "APP_USAGE" }.sumOf { log ->
            (parsePayload(log)["durationSec"] as? Number)?.toInt()?.div(60) ?: 0
        }

    private fun topApps(logs: List<UsageLog>, limit: Int): List<AppUsageDto> {
        val byPkg = logs.filter { it.type == "APP_USAGE" }.groupBy { parsePayload(it)["packageName"] as? String ?: "unknown" }
        return byPkg.map { (pkg, ls) ->
            val min = ls.sumOf { (parsePayload(it)["durationSec"] as? Number)?.toInt()?.div(60) ?: 0 }
            AppUsageDto(pkg, parsePayload(ls.first())["appName"] as? String ?: pkg, min, null)
        }.sortedByDescending { it.usageMinutes }.take(limit)
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
