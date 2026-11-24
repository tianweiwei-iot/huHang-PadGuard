package com.padguard.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.common.SERVER_ZONE_ID
import com.padguard.server.domain.Device
import com.padguard.server.domain.DeviceEvent
import com.padguard.server.domain.UsageLog
import com.padguard.server.repository.AlertRepository
import com.padguard.server.repository.DeviceEventRepository
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.InstalledAppRepository
import com.padguard.server.repository.UsageLogRepository
import com.padguard.server.security.DeviceOwnershipGuard
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.PageRequest
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * `StatisticsService` 的 `web()`（网址访问统计）/ `violations()`（违规事件统计）/
 * `export()`（CSV 导出并落盘）三条家长端看板/导出路径此前**零单测**。
 * 它们同样是信任关键：网址统计驱动"孩子去了哪些网站"的展示，违规统计驱动"越界/超时"告警聚合，
 * 导出报表含孩子使用明细且必须通过 `ownerUserId` 归属校验（防下载链接泄露后被别人拉走）。
 *
 * 沿用 [StatisticsServiceCoverageTest] 已验证的 Mockito 占位风格（非空参数 matcher 必须补 `?: 默认值`），
 * 不加载 Spring 上下文，规避全量集成测试的 OOM 风险。
 */
class StatisticsServiceWebViolationsExportTest {
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

    private fun stubOwned() {
        `when`(ownershipGuard.requireOwned(userId, deviceId))
            .thenReturn(Device(id = deviceId, userId = userId, name = "t", consentRevoked = false))
    }

    private fun stubUsageLogs(logs: List<UsageLog>) {
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

    private fun webLog(id: String, type: String, url: String?): UsageLog {
        val payload = if (url != null) mapOf("url" to url) else emptyMap<String, Any>()
        return UsageLog(
            id = id, deviceId = deviceId, logId = id, type = type,
            timestamp = atToday(12, 0, 0),
            payloadJson = if (payload.isEmpty()) null else objectMapper.writeValueAsString(payload)
        )
    }

    private fun usageLog(id: String, type: String, durationSec: Int?): UsageLog {
        val payload = if (durationSec != null) mapOf("durationSec" to durationSec) else emptyMap<String, Any>()
        return UsageLog(
            id = id, deviceId = deviceId, logId = id, type = type,
            timestamp = atToday(12, 0, 0),
            payloadJson = if (payload.isEmpty()) null else objectMapper.writeValueAsString(payload)
        )
    }

    private fun deviceEvent(id: String, type: String, ts: Long): DeviceEvent =
        DeviceEvent().apply { this.id = id; this.deviceId = deviceId; this.type = type; this.timestamp = ts }

    // ---- web() ----

    @Test
    fun web_countsVisitsAndBlockedAndAggregatesTopDomains() {
        stubOwned()
        // 3 条访问：a.com 两次、b.com 一次；1 条拦截
        stubUsageLogs(
            listOf(
                webLog("v1", "URL_VISIT", "https://a.com/news/1"),
                webLog("v2", "URL_VISIT", "https://a.com/news/2"),
                webLog("v3", "URL_VISIT", "https://b.com/p"),
                webLog("b1", "URL_BLOCKED", "https://evil.com/x")
            )
        )

        val dto = svc().web(userId, deviceId, "daily")

        assertEquals(3, dto.totalVisits)
        assertEquals(1, dto.blockedAttempts)
        // 域名聚合：a.com 出现 2 次排第一，b.com 1 次排第二；拦截不计域名
        assertNotNull(dto.topDomains)
        assertEquals(2, dto.topDomains!!.size)
        assertEquals("a.com", dto.topDomains!![0].domain)
        assertEquals(2, dto.topDomains!![0].visitCount)
        assertEquals("b.com", dto.topDomains!![1].domain)
        assertEquals(1, dto.topDomains!![1].visitCount)
    }

    @Test
    fun web_emptyReturnsZeroAndNullDomains() {
        stubOwned()
        stubUsageLogs(emptyList())

        val dto = svc().web(userId, deviceId, "daily")

        assertEquals(0, dto.totalVisits)
        assertEquals(0, dto.blockedAttempts)
        assertTrue(dto.topDomains.isNullOrEmpty())
    }

    // ---- violations() ----

    @Test
    fun violations_groupsByCategoryAndBuildsDailyTrend() {
        stubOwned()
        val now = atToday(10, 0, 0)
        `when`(deviceEventRepository.findByDeviceIdAndTimestampBetween(anyString() ?: "", anyLong(), anyLong()))
            .thenReturn(
                listOf(
                    deviceEvent("e1", "CURFEW_VIOLATION", now),
                    deviceEvent("e2", "CURFEW_VIOLATION", now),
                    deviceEvent("e3", "APP_BLOCK_VIOLATION", now)
                )
            )

        val dto = svc().violations(userId, deviceId, "daily")

        assertEquals(3, dto.totalCount)
        // 按类别聚合：CURFEW_VIOLATION=2, APP_BLOCK_VIOLATION=1
        assertEquals(2, dto.byCategory?.get("CURFEW_VIOLATION"))
        assertEquals(1, dto.byCategory?.get("APP_BLOCK_VIOLATION"))
        // 单日视图趋势恰好 1 条，且当天计满全部 3 条
        assertNotNull(dto.trend)
        assertEquals(1, dto.trend!!.size)
        assertEquals(3, dto.trend!![0].count)
    }

    @Test
    fun violations_emptyReturnsZero() {
        stubOwned()
        `when`(deviceEventRepository.findByDeviceIdAndTimestampBetween(anyString() ?: "", anyLong(), anyLong()))
            .thenReturn(emptyList())

        val dto = svc().violations(userId, deviceId, "weekly")

        assertEquals(0, dto.totalCount)
        assertTrue(dto.byCategory.isNullOrEmpty())
        // 周视图趋势长度 = 7（覆盖全周期，零事件也应有占位）
        assertEquals(7, dto.trend?.size)
        assertTrue(dto.trend!!.all { it.count == 0 })
    }

    // ---- export() ----

    @Test
    fun export_storesCsvWithOwnerAndReturnsDto() {
        stubOwned()
        // 一条 USAGE_TOTAL，durationSec=600 → 10 分钟，作为今日唯一用量源
        stubUsageLogs(listOf(usageLog("t1", "USAGE_TOTAL", 600)))
        `when`(deviceEventRepository.findByDeviceIdAndTimestampBetween(anyString() ?: "", anyLong(), anyLong()))
            .thenReturn(emptyList())
        `when`(alertRepository.countByUserIdAndStatusAndDeviceId(userId, "ACTIVE", deviceId)).thenReturn(2)
        `when`(installedAppRepository.findByDeviceId(deviceId)).thenReturn(emptyList())
        `when`(
            fileStorageService.store(
                anyString() ?: "", any(ByteArray::class.java) ?: ByteArray(0),
                anyString() ?: "", any(), any()
            )
        ).thenReturn("https://files.example/report.csv")

        val result = svc().export(userId, deviceId, "daily")

        assertEquals("https://files.example/report.csv", result.fileUrl)
        assertEquals("report_${deviceId}_daily.csv", result.fileName)
        assertTrue(result.fileSize > 0, "导出文件大小应大于 0")
        // 归属校验：落盘必须带 ownerUserId，防下载链接泄露后被别人拉走
        verify(fileStorageService).store(
            eq("text/csv") ?: "text/csv",
            any(ByteArray::class.java) ?: ByteArray(0),
            eq("report_${deviceId}_daily.csv") ?: "",
            eq(userId),
            any()
        )
    }

    @Test
    fun export_csvContentReflectsAggregatedTotals() {
        stubOwned()
        stubUsageLogs(listOf(usageLog("t1", "USAGE_TOTAL", 600)))
        `when`(deviceEventRepository.findByDeviceIdAndTimestampBetween(anyString() ?: "", anyLong(), anyLong()))
            .thenReturn(emptyList())
        `when`(alertRepository.countByUserIdAndStatusAndDeviceId(userId, "ACTIVE", deviceId)).thenReturn(2)
        `when`(installedAppRepository.findByDeviceId(deviceId)).thenReturn(emptyList())

        // 用 thenAnswer 抓取落盘字节（避免 ArgumentCaptor.capture() 在 Kotlin 下返回平台类型触发 NPE）
        var capturedBytes: ByteArray? = null
        `when`(
            fileStorageService.store(
                anyString() ?: "", any(ByteArray::class.java) ?: ByteArray(0),
                anyString() ?: "", any(), any()
            )
        ).thenAnswer { inv ->
            capturedBytes = inv.arguments[1] as ByteArray
            "https://files.example/report.csv"
        }

        svc().export(userId, deviceId, "daily")

        assertNotNull(capturedBytes, "导出应调用 fileStorageService.store 落盘")
        val csv = String(capturedBytes!!, Charsets.UTF_8)
        // CSV 表头与聚合结果应落盘：总时长 10 分钟、违规 0、告警 2、含设备号
        assertTrue(csv.contains(deviceId), "CSV 应含设备号")
        assertTrue(csv.contains("总使用时长(分钟),10"), "CSV 应含聚合后的总时长 10 分钟，实际: $csv")
        assertTrue(csv.contains("违规次数,0"), "CSV 应含违规次数 0")
        assertTrue(csv.contains("告警数,2"), "CSV 应含告警数 2")
    }
}
