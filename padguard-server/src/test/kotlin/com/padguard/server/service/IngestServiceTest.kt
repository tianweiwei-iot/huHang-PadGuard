package com.padguard.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.common.BizException
import com.padguard.server.domain.Device
import com.padguard.server.domain.LocationTrack
import com.padguard.server.domain.UsageLog
import com.padguard.server.dto.LocationPoint
import com.padguard.server.dto.LocationsRequest
import com.padguard.server.dto.LogItem
import com.padguard.server.dto.LogsRequest
import com.padguard.server.dto.UsageStatsDto
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.LocationTrackRepository
import com.padguard.server.repository.UsageLogRepository
import com.padguard.server.ws.WebSocketPushService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.PageRequest
import java.util.*

/**
 * `IngestService` 是孩子端全部遥测（行为日志 / 定位轨迹）的**服务端 ingestion 入口**：
 * 它喂给今日用量看板缓存、地理围栏越界检测、临时解锁工单、实时 WS 推送，以及全部统计聚合。
 * 历史上它**零单测**，一旦回归改坏，会静默污染统计 / 围栏 / 时长核算，且难以从 UI 复现。
 *
 * 这里用 Mockito 隔离全部 10 个依赖，验证下列不可随意改动的不变量：
 *  - 空批 / 设备已撤回授权（PIPL）→ 停止采集，且无任何落库、无推送；
 *  - `logId` 幂等：批内重复 + 跨库已存在 双重去重，accepted/duplicated 计数准确；
 *  - `UNLOCK_REQUEST` 只对新行建工单，重复上报不刷出一堆工单；
 *  - 位置上报：按 **ts 最大点**（而非列表末项）更新设备最新坐标，防乱序上报错位；
 *  - 仅当配置了逆地理 Key 才回填地址，默认零开销；
 *  - 今日实时推送只对**用量类**日志触发，且整段 runCatching 不拖累入库。
 *
 * 不加载 Spring 上下文。沿用 `GeofenceServiceTest` 的既定范式：
 * Kotlin 非空参数 + Mockito 占位 matcher 必须写成「带类型的 matcher `?:` 同类型默认值」形式
 * （如 `any(Pageable::class.java) ?: PageRequest.of(0, 1)`、`anyMap() ?: emptyMap<String, Any?>()`）。
 * 裸 `any()` 对非空参数会触发 Kotlin 非空检查 → `any(...) must not be null`；裸 `eq(x) ?: x` /
 * `any(Class) ?: x` 会把真实值当参数传入却仍注册 matcher，污染 Matcher 栈引发级联失败。
 * 这里 `?:` 是死代码（matcher 返回非空类型），只让 matcher 生效、规避非空检查。
 */
class IngestServiceTest {
    private val deviceId = "dev1"
    private val userId = "u1"

    private data class Harness(
        val svc: IngestService,
        val usageLogRepository: UsageLogRepository,
        val deviceRepository: DeviceRepository,
        val statisticsService: StatisticsService,
        val webSocketPush: WebSocketPushService,
        val unlockService: UnlockService,
        val locationTrackRepository: LocationTrackRepository,
        val geofenceService: GeofenceService,
        val locationAddressBackfill: LocationAddressBackfill
    )

    private fun harness(
        device: Device,
        knownLogIds: Set<String> = emptySet(),
        geocodeConfigured: Boolean = false,
        todaySnap: UsageStatsDto? = null
    ): Harness {
        val usageLogRepository = mock(UsageLogRepository::class.java)
        val deviceRepository = mock(DeviceRepository::class.java)
        val geofenceService = mock(GeofenceService::class.java)
        val unlockService = mock(UnlockService::class.java)
        val statisticsService = mock(StatisticsService::class.java)
        val webSocketPush = mock(WebSocketPushService::class.java)
        val locationTrackRepository = mock(LocationTrackRepository::class.java)
        val geoCoderService = mock(GeoCoderService::class.java)
        val locationAddressBackfill = mock(LocationAddressBackfill::class.java)

        `when`(deviceRepository.findById(anyString())).thenReturn(Optional.of(device))
        `when`(deviceRepository.save(any(Device::class.java) ?: device)).thenAnswer { it.arguments[0] }
        `when`(usageLogRepository.findExistingLogIds(anyList() ?: emptyList<String>())).thenReturn(knownLogIds)
        `when`(usageLogRepository.saveAll(anyList())).thenAnswer { it.arguments[0] }
        `when`(
            locationTrackRepository.findByDeviceIdOrderByTsDesc(
                anyString(), any(Pageable::class.java) ?: PageRequest.of(0, 1)
            )
        ).thenReturn(emptyList())
        `when`(locationTrackRepository.saveAll(anyList())).thenAnswer { it.arguments[0] }
        `when`(geoCoderService.isConfigured()).thenReturn(geocodeConfigured)
        if (todaySnap != null) {
            `when`(statisticsService.today(anyString(), anyString())).thenReturn(todaySnap)
        }

        val svc = IngestService(
            usageLogRepository, deviceRepository, ObjectMapper(),
            geofenceService, unlockService, statisticsService,
            webSocketPush, locationTrackRepository, geoCoderService, locationAddressBackfill
        )
        return Harness(svc, usageLogRepository, deviceRepository, statisticsService, webSocketPush,
            unlockService, locationTrackRepository, geofenceService, locationAddressBackfill)
    }

    private fun newDevice(consentRevoked: Boolean = false): Device =
        Device().apply {
            // 注意：apply 内 this 是 Device，故 RHS 必须显式指向测试类的 deviceId/userId 字段，
            // 否则 `this.userId = userId` 会把 Device.userId（默认 null）赋给自己，造出"无主设备"。
            id = this@IngestServiceTest.deviceId
            userId = this@IngestServiceTest.userId
            this.consentRevoked = consentRevoked
        }

    // ---- saveLogs ----

    @Test
    fun `saveLogs 空批 直接返回零计数且不落库`() {
        val h = harness(newDevice())
        val res = h.svc.saveLogs(LogsRequest(deviceId, emptyList()))
        assertEquals(mapOf("accepted" to 0, "duplicated" to 0), res)
        verify(h.usageLogRepository, never()).saveAll(anyList())
    }

    @Test
    fun `saveLogs 设备已撤回授权 停止采集且不落库`() {
        val h = harness(newDevice(consentRevoked = true))
        val logs = listOf(LogItem("a", "USAGE_TOTAL", 1000L, mapOf("durationSec" to 300)))
        val res = h.svc.saveLogs(LogsRequest(deviceId, logs))
        assertEquals(0, res["accepted"])
        assertEquals(logs.size, res["duplicated"])
        assertEquals(1, res["revoked"])
        verify(h.usageLogRepository, never()).saveAll(anyList())
    }

    @Test
    fun `saveLogs 新用量日志入库 并触发今日实时推送`() {
        val device = newDevice()
        val h = harness(device, todaySnap = UsageStatsDto(deviceId, "2026-11-09", 60, null))
        val logs = listOf(
            LogItem("a", "USAGE_TOTAL", 1000L, mapOf("durationSec" to 300)),
            LogItem("b", "APP_USAGE", 1001L, mapOf("durationSec" to 120))
        )
        val res = h.svc.saveLogs(LogsRequest(deviceId, logs))
        assertEquals(2, res["accepted"])
        assertEquals(0, res["duplicated"])
        verify(h.usageLogRepository).saveAll(anyList())
        // 推送路径：先失效缓存再推最新快照（只向该孩子的家长推送）
        verify(h.statisticsService).invalidateToday(eq(deviceId) ?: deviceId)
        verify(h.statisticsService).today(eq(userId) ?: userId, eq(deviceId) ?: deviceId)
        verify(h.webSocketPush).pushToUser(eq(userId) ?: userId, anyMap() ?: emptyMap<String, Any?>())
    }

    @Test
    fun `saveLogs 批内重复与跨库已存在双重去重 accepted 计数准确`() {
        // knownLogIds 含 "b"；批内 "a" 出现两次
        val h = harness(newDevice(), knownLogIds = setOf("b"))
        val logs = listOf(
            LogItem("a", "USAGE_TOTAL", 1L),
            LogItem("a", "USAGE_TOTAL", 2L), // 批内重复，distinctBy 后只剩一个
            LogItem("b", "USAGE_TOTAL", 3L), // 跨库已存在
            LogItem("c", "USAGE_TOTAL", 4L)
        )
        val res = h.svc.saveLogs(LogsRequest(deviceId, logs))
        assertEquals(2, res["accepted"])   // 只剩 a、c
        assertEquals(2, res["duplicated"])  // b 跨库 + a 批内重复
        verify(h.usageLogRepository).saveAll(anyList())
    }

    @Test
    fun `saveLogs UNLOCK_REQUEST 只对新行建工单 不重复刷单`() {
        val h = harness(newDevice())
        val logs = listOf(
            LogItem("u1", "UNLOCK_REQUEST", 10L, mapOf("requestId" to "r1")),
            LogItem("u2", "UNLOCK_REQUEST", 11L, mapOf("requestId" to "r2")) // 两条不同 logId 都新鲜
        )
        h.svc.saveLogs(LogsRequest(deviceId, logs))
        // UNLOCK_REQUEST 不在用量推送类型集合，故不应触发今日推送
        verify(h.webSocketPush, never()).pushToUser(anyString(), anyMap() ?: emptyMap<String, Any?>())
        verify(h.unlockService, times(2)).createFromRequest(anyString(), any())
    }

    // ---- saveLocations ----

    @Test
    fun `saveLocations 空轨迹 返回零且不落库`() {
        val h = harness(newDevice())
        assertEquals(0, h.svc.saveLocations(LocationsRequest(deviceId, emptyList())))
        verify(h.locationTrackRepository, never()).saveAll(anyList())
    }

    @Test
    fun `saveLocations 设备已撤回授权 停止采集位置`() {
        val h = harness(newDevice(consentRevoked = true))
        val points = listOf(LocationPoint(31.2, 121.4, ts = 100L))
        assertEquals(0, h.svc.saveLocations(LocationsRequest(deviceId, points)))
        verify(h.locationTrackRepository, never()).saveAll(anyList())
    }

    @Test
    fun `saveLocations 按 ts 最大点更新设备坐标 而非列表末项`() {
        val device = newDevice()
        val h = harness(device)
        // 故意乱序：末项 ts 最小，中间 ts 最大
        val points = listOf(
            LocationPoint(1.0, 1.0, ts = 100L),
            LocationPoint(3.0, 3.0, ts = 300L), // 应取此点
            LocationPoint(2.0, 2.0, ts = 200L)
        )
        assertEquals(3, h.svc.saveLocations(LocationsRequest(deviceId, points)))
        verify(h.geofenceService).detectTransitions(eq(deviceId) ?: deviceId, anyList() ?: emptyList<LocationPoint>(), any())
        verify(h.locationTrackRepository).saveAll(anyList())
        // 设备最新坐标被设为 ts 最大点（3.0,3.0），而非列表末项（2.0,2.0）
        assertEquals(3.0, device.latitude)
        assertEquals(3.0, device.longitude)
        verify(h.locationAddressBackfill, never()).backfill(anyList() ?: emptyList<LocationTrackAddressInput>())
    }

    @Test
    fun `saveLocations 配置逆地理 Key 时才回填地址`() {
        val h = harness(newDevice(), geocodeConfigured = true)
        val points = listOf(LocationPoint(31.2, 121.4, ts = 100L))
        h.svc.saveLocations(LocationsRequest(deviceId, points))
        verify(h.locationAddressBackfill).backfill(anyList() ?: emptyList<LocationTrackAddressInput>())
    }

    @Test
    fun `saveLocations 未配置逆地理 Key 不发起回填 零开销`() {
        val h = harness(newDevice(), geocodeConfigured = false)
        val points = listOf(LocationPoint(31.2, 121.4, ts = 100L))
        h.svc.saveLocations(LocationsRequest(deviceId, points))
        verify(h.locationAddressBackfill, never()).backfill(anyList() ?: emptyList<LocationTrackAddressInput>())
    }

    @Test
    fun `saveLocations 超范围坐标被钳制 NaN 与无限值置空`() {
        // 心跳链路早有同款钳制（ChildEndpointBoundaryTest B-01），轨迹主路径漏配——
        // 999° 会污染 haversine 围栏判定并误触 GEOFENCE 告警；NaN/Inf 不能落库参与计算。
        val device = newDevice()
        val h = harness(device)
        val points = listOf(
            LocationPoint(999.0, -999.0, ts = 100L),
            LocationPoint(Double.NaN, Double.POSITIVE_INFINITY, ts = 200L),
            LocationPoint(31.2, 121.4, ts = 300L)
        )
        assertEquals(3, h.svc.saveLocations(LocationsRequest(deviceId, points)))

        org.mockito.Mockito.verify(h.locationTrackRepository).saveAll(
            org.mockito.ArgumentMatchers.argThat<List<LocationTrack>> { list ->
                list.size == 3 &&
                    list[0].lat == 90.0 && list[0].lng == -180.0 &&   // 钳制到边界
                    list[1].lat == null && list[1].lng == null &&     // 非有限值置空
                    list[2].lat == 31.2 && list[2].lng == 121.4       // 正常值不动
            }
        )
        // 设备最新位置取 ts 最大点（第 3 点），不受畸形点污染
        assertEquals(31.2, device.latitude)
        assertEquals(121.4, device.longitude)
    }

    @Test
    fun `saveLocations 批量超过上限整批拒绝`() {
        // S-4 同款：saveAll 会整批物化实体，恶意/异常设备的超大批次必须入口拒绝
        // （companion 为 private，这里用与 MAX_POINTS_PER_BATCH 相等的字面量锁定契约）
        val h = harness(newDevice())
        val points = (1..2_001).map {
            LocationPoint(31.2, 121.4, ts = it.toLong())
        }
        assertThrows(BizException::class.java) {
            h.svc.saveLocations(LocationsRequest(deviceId, points))
        }
        verify(h.locationTrackRepository, never()).saveAll(anyList())
    }
}
