package com.padguard.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.common.SERVER_ZONE_ID
import com.padguard.server.domain.Device
import com.padguard.server.domain.UsageLog
import com.padguard.server.repository.DeviceEventRepository
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.InstalledAppRepository
import com.padguard.server.repository.AlertRepository
import com.padguard.server.repository.UsageLogRepository
import com.padguard.server.security.DeviceOwnershipGuard
import com.padguard.server.service.StatisticsService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.*
import org.springframework.data.domain.Pageable
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.*

/**
 * `StatisticsService.appUsageHistory` 是第二十九轮之后补上的功能出口，此前零单测覆盖。
 * 这里用 Mockito 隔离仓储、用真实 ObjectMapper 解析 payload，验证：
 *  - 按包名过滤、逐次打开记录的还原与时长计算；
 *  - 缺 startAt/endAt 的旧日志被跳过（而非塞 0 占位）；
 *  - 时区统一走 [SERVER_ZONE_ID]，date/startTime 为该口径下的字符串；
 *  - 空包名、无匹配记录等边界安全返回空。
 * 不依赖 Spring 上下文，避免全量集成测试的 OOM 风险。
 */
class StatisticsServiceAppHistoryTest {
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
                any(Pageable::class.java) ?: org.springframework.data.domain.PageRequest.of(0, 1)
            )
        ).thenReturn(logs)
    }

    /** 服务端统一时区下今天某个本地时刻对应的 epoch 毫秒 */
    private fun atToday(hour: Int, minute: Int, second: Int): Long =
        ZonedDateTime.of(LocalDate.now(SERVER_ZONE_ID), LocalTime.of(hour, minute, second), SERVER_ZONE_ID)
            .toInstant().toEpochMilli()

    private fun log(id: String, pkg: String?, startAt: Long?, endAt: Long?, durationSec: Int?): UsageLog {
        val payload = mutableMapOf<String, Any>("packageName" to (pkg ?: ""))
        if (pkg != null) payload["appName"] = "App$pkg"
        startAt?.let { payload["startAt"] = it }
        endAt?.let { payload["endAt"] = it }
        durationSec?.let { payload["durationSec"] = it }
        return UsageLog(
            id = id, deviceId = deviceId, logId = id, type = "APP_USAGE",
            timestamp = startAt ?: System.currentTimeMillis(),
            payloadJson = objectMapper.writeValueAsString(payload)
        )
    }

    @Test
    fun filtersByPackageName_andRestoresDuration() {
        val s = atToday(9, 15, 30)
        val logs = listOf(
            log("a1", "com.a", s, s + 600_000, 600),        // 10 分钟
            log("a2", "com.a", s + 7_200_000, s + 7_500_000, 300), // 5 分钟，更晚
            log("b1", "com.b", s, s + 120_000, 120)
        )
        stubLogs(logs)

        val result = svc().appUsageHistory(userId, deviceId, "com.a", "weekly")

        assertEquals(2, result.size)
        // 按开始时间倒序：更晚的 a2（11:15:30）应在前
        assertEquals("11:15:30", result[0].startTime)
        assertEquals("09:15:30", result[1].startTime)
        // 时长应直接取 payload 的 durationSec
        assertEquals(300, result[0].usageSeconds)
        assertEquals(600, result[1].usageSeconds)
        // 时区口径：date 为服务端今天
        assertEquals(LocalDate.now(SERVER_ZONE_ID).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE), result[0].date)
    }

    @Test
    fun fallsBackToEndMinusStart_whenDurationMissing() {
        val s = atToday(10, 0, 0)
        val logs = listOf(log("a3", "com.a", s, s + 90_000, null)) // 90 秒，无 durationSec
        stubLogs(logs)

        val result = svc().appUsageHistory(userId, deviceId, "com.a", "daily")

        assertEquals(1, result.size)
        assertEquals(90, result[0].usageSeconds)
        assertEquals("10:00:00", result[0].startTime)
        assertEquals("10:01:30", result[0].endTime)
    }

    @Test
    fun skipsLogsMissingStartOrEnd() {
        val s = atToday(8, 0, 0)
        val logs = listOf(
            log("a1", "com.a", s, s + 60_000, 60),
            log("a2", "com.a", null, null, 999) // 缺起止时间，必须跳过
        )
        stubLogs(logs)

        val result = svc().appUsageHistory(userId, deviceId, "com.a", "weekly")
        assertEquals(1, result.size)
        assertEquals(60, result[0].usageSeconds)
    }

    @Test
    fun blankPackageName_returnsEmpty() {
        val logs = listOf(log("a1", "com.a", atToday(8, 0, 0), atToday(8, 1, 0), 60))
        stubLogs(logs)

        val result = svc().appUsageHistory(userId, deviceId, "   ", "weekly")
        assertTrue(result.isEmpty())
        // 空包名走早返回，不应触发任何仓储查询
        verify(usageLogRepository, never()).findByDeviceIdAndTypeInAndTimestampBetween(
            anyString() ?: "", any() ?: emptyList<String>(), anyLong(), anyLong(),
            any(Pageable::class.java) ?: org.springframework.data.domain.PageRequest.of(0, 1)
        )
    }

    @Test
    fun unknownPackage_returnsEmpty() {
        val logs = listOf(log("a1", "com.a", atToday(8, 0, 0), atToday(8, 1, 0), 60))
        stubLogs(logs)

        val result = svc().appUsageHistory(userId, deviceId, "com.nope", "weekly")
        assertTrue(result.isEmpty())
    }

    @Test
    fun ownershipGuard_enforcedOnDevice() {
        stubLogs(listOf(log("a1", "com.a", atToday(8, 0, 0), atToday(8, 1, 0), 60)))
        svc().appUsageHistory(userId, deviceId, "com.a", "weekly")
        verify(ownershipGuard).requireOwned(userId, deviceId)
    }
}
