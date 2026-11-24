package com.padguard.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.common.SERVER_ZONE_ID
import com.padguard.server.domain.Device
import com.padguard.server.domain.UsageLog
import com.padguard.server.repository.AlertRepository
import com.padguard.server.repository.DeviceEventRepository
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.InstalledAppRepository
import com.padguard.server.repository.UsageLogRepository
import com.padguard.server.security.DeviceOwnershipGuard
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.*
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.PageRequest
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * `StatisticsService` 的 `appUsageHistory` 此前零单测，本轮补齐后，这里进一步覆盖：
 *  - [appUsageHistory] 的结果上限保护（重度使用应用月视图不应无界返回）；
 *  - [today] / [usage] 这两条家长端看板主路径的聚合口径与返回结构（此前同样零覆盖）。
 * 沿用 [StatisticsServiceAppHistoryTest] 已验证的 Mockito 占位风格（非可空 matcher 必须补 `?: 默认值`），
 * 不加载 Spring 上下文，规避全量集成测试的 OOM 风险。
 */
class StatisticsServiceCoverageTest {
    private val deviceRepository = mock(DeviceRepository::class.java)
    private val ownershipGuard = mock(DeviceOwnershipGuard::class.java)
    private val usageLogRepository = mock(UsageLogRepository::class.java)
    private val deviceEventRepository = mock(DeviceEventRepository::class.java)
    private val alertRepository = mock(AlertRepository::class.java)
    private val installedAppRepository = mock(InstalledAppRepository::class.java)
    private val fileStorageService = mock(FileStorageService::class.java)
    private val objectMapper = ObjectMapper()
    private val usageSessionService = mock(UsageSessionService::class.java)

    private val userId = "u1"
    private val deviceId = "d1"

    private fun svc(): StatisticsService = StatisticsService(
        deviceRepository, ownershipGuard, usageLogRepository, deviceEventRepository,
        alertRepository, installedAppRepository, fileStorageService, objectMapper, usageSessionService
    )

    private fun stubLogs(logs: List<UsageLog>) {
        `when`(ownershipGuard.requireOwned(userId, deviceId))
            .thenReturn(Device(id = deviceId, userId = userId, name = "t", consentRevoked = false))
        `when`(
            usageLogRepository.findByDeviceIdAndTypeInAndTimestampBetween(
                anyString() ?: "",
                any() ?: emptyList<String>(),
                anyLong(),
                anyLong(),
                any(Pageable::class.java) ?: PageRequest.of(0, 1)
            )
        ).thenReturn(logs)
    }

    private fun atToday(hour: Int, minute: Int, second: Int): Long =
        ZonedDateTime.of(LocalDate.now(SERVER_ZONE_ID), LocalTime.of(hour, minute, second), SERVER_ZONE_ID)
            .toInstant().toEpochMilli()

    private fun usageLog(id: String, type: String, pkg: String?, startAt: Long?, endAt: Long?, durationSec: Int?): UsageLog {
        val payload = mutableMapOf<String, Any>()
        if (pkg != null) {
            payload["packageName"] = pkg
            payload["appName"] = "App$pkg"
        }
        startAt?.let { payload["startAt"] = it }
        endAt?.let { payload["endAt"] = it }
        durationSec?.let { payload["durationSec"] = it }
        return UsageLog(
            id = id, deviceId = deviceId, logId = id, type = type,
            timestamp = startAt ?: System.currentTimeMillis(),
            payloadJson = if (payload.isEmpty()) null else objectMapper.writeValueAsString(payload)
        )
    }

    @Test
    fun appUsageHistory_capsResultAtMax() {
        // 600 条同一应用的打开记录（远超上限 500），验证服务端截断而非全量返回
        val base = atToday(0, 0, 0)
        val logs = (0 until 600).map { i ->
            val ts = base + i * 1000L
            usageLog("g$i", "APP_USAGE", "com.a", ts, ts + 1000, 10)
        }
        stubLogs(logs)

        val result = svc().appUsageHistory(userId, deviceId, "com.a", "monthly")

        // 关键：返回被截断到最近的 500 条（= StatisticsService 内 APP_USAGE_HISTORY_MAX），避免无界增长撑大响应
        assertEquals(500, result.size)
        // 倒序首条应为最晚一次（i=599 → 00:09:59）
        assertEquals("00:09:59", result[0].startTime)
        // 整体保持时间倒序
        for (i in 1 until result.size) {
            assertTrue(result[i].startTime <= result[i - 1].startTime, "index $i 不是倒序")
        }
        // 末条应为被截断窗口内最旧的一次（i=100 → 00:01:40），更早的 0..99 已丢弃
        assertEquals("00:01:40", result[result.size - 1].startTime)
    }

    @Test
    fun today_reportsAggregatedMinutesAndDate() {
        // 一条 USAGE_TOTAL，durationSec=3600 → 60 分钟，作为今日唯一用量源
        stubLogs(listOf(usageLog("t1", "USAGE_TOTAL", null, atToday(12, 0, 0), atToday(13, 0, 0), 3600)))
        `when`(usageSessionService.usedMinutesIfActive(anyString())).thenReturn(null)
        `when`(installedAppRepository.findByDeviceId(deviceId)).thenReturn(emptyList())

        val dto = svc().today(userId, deviceId)

        assertEquals(deviceId, dto.deviceId)
        assertEquals(LocalDate.now(SERVER_ZONE_ID).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE), dto.date)
        assertEquals(60, dto.totalUsageMinutes)
        // 无 APP_USAGE 明细，不返回应用榜单
        assertTrue(dto.appUsages.isNullOrEmpty())
    }

    @Test
    fun usage_buildsDailySeriesAndCounts() {
        // 今日一条 USAGE_TOTAL，durationSec=600 → 10 分钟
        stubLogs(listOf(usageLog("t1", "USAGE_TOTAL", null, atToday(12, 0, 0), atToday(12, 10, 0), 600)))
        `when`(usageSessionService.usedMinutesIfActive(anyString())).thenReturn(null)
        `when`(installedAppRepository.findByDeviceId(deviceId)).thenReturn(emptyList())
        `when`(deviceEventRepository.findByDeviceIdAndTimestampBetween(anyString() ?: "", anyLong(), anyLong()))
            .thenReturn(listOf(com.padguard.server.domain.DeviceEvent(), com.padguard.server.domain.DeviceEvent()))
        `when`(alertRepository.countByUserIdAndStatusAndDeviceId(userId, "ACTIVE", deviceId)).thenReturn(3)

        val report = svc().usage(userId, deviceId, "daily")

        assertEquals(deviceId, report.deviceId)
        assertEquals("daily", report.period)
        assertEquals(10, report.totalUsageMinutes)
        // 单日视图应有一条逐日数据，且当天分钟数=10
        assertEquals(1, report.dailyUsages?.size)
        assertEquals(10, report.dailyUsages?.get(0)?.usageMinutes)
        // 违规次数取事件列表长度，告警数取计数下沉结果
        assertEquals(2, report.violationCount)
        assertEquals(3, report.alertCount)
    }
}
