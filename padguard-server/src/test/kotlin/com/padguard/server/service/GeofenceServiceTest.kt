package com.padguard.server.service

import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.Device
import com.padguard.server.domain.GeofenceZone
import com.padguard.server.domain.LocationTrack
import com.padguard.server.dto.GeofenceZoneDto
import com.padguard.server.dto.LocationPoint
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.GeofenceZoneRepository
import com.padguard.server.repository.LocationTrackRepository
import com.padguard.server.security.DeviceOwnershipGuard
import com.padguard.server.service.AlertService
import com.padguard.server.service.GeofenceService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import java.util.*

/**
 * `GeofenceService` 是信任关键路径（孩子安全区域的进入/离开告警），此前**零单测**。
 * 这里用 Mockito 隔离仓储，验证：
 *  - [GeofenceService.detectTransitions] 进入/离开状态机的边界告警语义（只在切换点告警，不刷屏）；
 *  - 开关（alertOnExit / alertOnEnter）与启用态（enabled）的精确作用；
 *  - [GeofenceService.updateZone]/[GeofenceService.deleteZone] 的越权防护：跨设备访问别人的围栏按"不存在"处理，不泄露归属；
 *  - [GeofenceService.currentLocation] 无轨迹时回退到设备登记的经纬度，绝不返回异常或裸 0,0 误定位。
 * 不加载 Spring 上下文。
 *
 * 注意：Kotlin 非空参数 + Mockito 占位 matcher 必须补 `?: 默认值`（如 `eq(x) ?: x`、
 * `any(Pageable::class.java) ?: PageRequest.of(0,1)`），否则非空检查会抛 `eq(...) must not be null`
 * 并污染 Matcher 栈引发级联失败。
 */
class GeofenceServiceTest {
    private val deviceRepository = mock(DeviceRepository::class.java)
    private val locationTrackRepository = mock(LocationTrackRepository::class.java)
    private val geofenceZoneRepository = mock(GeofenceZoneRepository::class.java)
    private val alertService = mock(AlertService::class.java)
    private val ownershipGuard = mock(DeviceOwnershipGuard::class.java)

    private val userId = "u1"
    private val deviceId = "d1"
    private val otherDevice = "d-other"

    private fun svc(): GeofenceService = GeofenceService(
        deviceRepository, locationTrackRepository, geofenceZoneRepository, alertService, ownershipGuard
    )

    /** 以 (0,0) 为圆心、半径 1000m 的围栏；alertOnExit 默认开、alertOnEnter 默认关 */
    private fun zone(
        id: String = "z1",
        deviceId: String = this.deviceId,
        enabled: Boolean = true,
        alertOnExit: Boolean = true,
        alertOnEnter: Boolean = false
    ): GeofenceZone = GeofenceZone().also {
        it.id = id; it.deviceId = deviceId; it.enabled = enabled
        it.name = "家"; it.centerLat = 0.0; it.centerLng = 0.0; it.radiusMeters = 1000
        it.alertOnExit = alertOnExit; it.alertOnEnter = alertOnEnter; it.createdAt = 1L
    }

    private fun owned(): Device = Device().also { it.id = deviceId; it.userId = userId }

    @Test
    fun detectTransitions_alertsOnExit() {
        `when`(ownershipGuard.requireOwned(userId, deviceId)).thenReturn(owned())
        `when`(geofenceZoneRepository.findByDeviceIdOrderByCreatedAtAsc(deviceId)).thenReturn(listOf(zone()))
        // 上一帧在围栏内，本帧到 (1°,0°) ≈ 111km 外 → 离开
        val prev = LocationTrack().also { it.lat = 0.0; it.lng = 0.0 }
        val points = listOf(LocationPoint(lat = 1.0, lng = 0.0, ts = 1000L))

        svc().detectTransitions(deviceId, points, prev)

        verify(alertService).create(eq(deviceId) ?: deviceId, anyString(), anyString(), anyString(), eq("GEOFENCE_EXIT") ?: "GEOFENCE_EXIT")
        verify(alertService, never()).create(eq(deviceId) ?: deviceId, anyString(), anyString(), anyString(), eq("GEOFENCE_ENTER") ?: "GEOFENCE_ENTER")
    }

    @Test
    fun detectTransitions_alertsOnEnter() {
        `when`(ownershipGuard.requireOwned(userId, deviceId)).thenReturn(owned())
        `when`(geofenceZoneRepository.findByDeviceIdOrderByCreatedAtAsc(deviceId)).thenReturn(
            listOf(zone(alertOnExit = false, alertOnEnter = true))
        )
        // 上一帧在围栏外，本帧回到圆心 → 进入
        val prev = LocationTrack().also { it.lat = 1.0; it.lng = 0.0 }
        val points = listOf(LocationPoint(lat = 0.0, lng = 0.0, ts = 1000L))

        svc().detectTransitions(deviceId, points, prev)

        verify(alertService).create(eq(deviceId) ?: deviceId, anyString(), anyString(), anyString(), eq("GEOFENCE_ENTER") ?: "GEOFENCE_ENTER")
        verify(alertService, never()).create(eq(deviceId) ?: deviceId, anyString(), anyString(), anyString(), eq("GEOFENCE_EXIT") ?: "GEOFENCE_EXIT")
    }

    @Test
    fun detectTransitions_noAlertWhenFlagOff() {
        `when`(ownershipGuard.requireOwned(userId, deviceId)).thenReturn(owned())
        `when`(geofenceZoneRepository.findByDeviceIdOrderByCreatedAtAsc(deviceId)).thenReturn(
            listOf(zone(alertOnExit = false, alertOnEnter = false))
        )
        val prev = LocationTrack().also { it.lat = 0.0; it.lng = 0.0 }
        val points = listOf(LocationPoint(lat = 1.0, lng = 0.0, ts = 1000L))

        svc().detectTransitions(deviceId, points, prev)

        verify(alertService, never()).create(anyString(), anyString(), anyString(), anyString(), anyString())
    }

    @Test
    fun detectTransitions_skipsDisabledZone() {
        `when`(ownershipGuard.requireOwned(userId, deviceId)).thenReturn(owned())
        `when`(geofenceZoneRepository.findByDeviceIdOrderByCreatedAtAsc(deviceId)).thenReturn(
            listOf(zone(enabled = false))
        )
        val prev = LocationTrack().also { it.lat = 0.0; it.lng = 0.0 }
        val points = listOf(LocationPoint(lat = 1.0, lng = 0.0, ts = 1000L))

        svc().detectTransitions(deviceId, points, prev)

        verify(alertService, never()).create(anyString(), anyString(), anyString(), anyString(), anyString())
    }

    @Test
    fun detectTransitions_alertsEachBoundaryCrossing() {
        `when`(ownershipGuard.requireOwned(userId, deviceId)).thenReturn(owned())
        `when`(geofenceZoneRepository.findByDeviceIdOrderByCreatedAtAsc(deviceId)).thenReturn(
            listOf(zone(alertOnExit = true, alertOnEnter = true))
        )
        // 内 → 外 → 内：应各告警一次（离开 + 进入），不刷屏
        val prev = LocationTrack().also { it.lat = 0.0; it.lng = 0.0 }
        val points = listOf(
            LocationPoint(lat = 1.0, lng = 0.0, ts = 1L),
            LocationPoint(lat = 0.0, lng = 0.0, ts = 2L)
        )

        svc().detectTransitions(deviceId, points, prev)

        verify(alertService).create(eq(deviceId) ?: deviceId, anyString(), anyString(), anyString(), eq("GEOFENCE_EXIT") ?: "GEOFENCE_EXIT")
        verify(alertService).create(eq(deviceId) ?: deviceId, anyString(), anyString(), anyString(), eq("GEOFENCE_ENTER") ?: "GEOFENCE_ENTER")
        verify(alertService, times(2)).create(anyString(), anyString(), anyString(), anyString(), anyString())
    }

    @Test
    fun detectTransitions_noAlertWhenNoZones() {
        `when`(ownershipGuard.requireOwned(userId, deviceId)).thenReturn(owned())
        `when`(geofenceZoneRepository.findByDeviceIdOrderByCreatedAtAsc(deviceId)).thenReturn(emptyList())

        svc().detectTransitions(deviceId, listOf(LocationPoint(lat = 0.0, lng = 0.0, ts = 1L)), null)

        verify(alertService, never()).create(anyString(), anyString(), anyString(), anyString(), anyString())
    }

    @Test
    fun updateZone_crossDeviceAccess_throwsNotFound() {
        `when`(ownershipGuard.requireOwned(userId, deviceId)).thenReturn(owned())
        // 该围栏属于"别的设备"：按"不存在"处理，杜绝越权与归属泄露
        `when`(geofenceZoneRepository.findById("z-x")).thenReturn(Optional.of(zone(id = "z-x", deviceId = otherDevice)))

        val ex = assertThrows<BizException> {
            svc().updateZone(
                userId, deviceId, "z-x",
                GeofenceZoneDto(
                    id = "z-x", deviceId = deviceId, enabled = true, name = "x",
                    centerLatitude = 0.0, centerLongitude = 0.0, radiusMeters = 100,
                    alertOnExit = true, alertOnEnter = false
                )
            )
        }
        assertEquals(ParentErr.GEOFENCE_NOT_FOUND, ex.code)
    }

    @Test
    fun deleteZone_crossDeviceAccess_throwsNotFound() {
        `when`(ownershipGuard.requireOwned(userId, deviceId)).thenReturn(owned())
        `when`(geofenceZoneRepository.findById("z-x")).thenReturn(Optional.of(zone(id = "z-x", deviceId = otherDevice)))

        val ex = assertThrows<BizException> { svc().deleteZone(userId, deviceId, "z-x") }
        assertEquals(ParentErr.GEOFENCE_NOT_FOUND, ex.code)
        verify(geofenceZoneRepository, never()).delete(any())
    }

    @Test
    fun currentLocation_fallsBackToDeviceCoordsWhenNoTrack() {
        `when`(ownershipGuard.requireOwned(userId, deviceId)).thenReturn(
            Device().also { it.id = deviceId; it.userId = userId; it.latitude = 31.23; it.longitude = 121.47 }
        )
        `when`(
            locationTrackRepository.findByDeviceIdOrderByTsDesc(
                eq(deviceId) ?: deviceId, any(Pageable::class.java) ?: PageRequest.of(0, 1)
            )
        ).thenReturn(emptyList())

        val loc = svc().currentLocation(userId, deviceId)

        assertEquals(31.23, loc.latitude, 0.0001)
        assertEquals(121.47, loc.longitude, 0.0001)
    }

    private fun zoneDto(lat: Double = 0.0, lng: Double = 0.0, radius: Int = 1000) =
        GeofenceZoneDto(
            deviceId = deviceId, enabled = true, name = "家",
            centerLatitude = lat, centerLongitude = lng, radiusMeters = radius,
            alertOnExit = true, alertOnEnter = false
        )

    @Test
    fun createZone_rejectsOutOfRangeGeometry() {
        `when`(ownershipGuard.requireOwned(userId, deviceId)).thenReturn(owned())

        val badRadius = assertThrows<BizException> { svc().createZone(userId, deviceId, zoneDto(radius = -1)) }
        val hugeRadius = assertThrows<BizException> { svc().createZone(userId, deviceId, zoneDto(radius = Int.MAX_VALUE)) }
        val badLat = assertThrows<BizException> { svc().createZone(userId, deviceId, zoneDto(lat = 91.0)) }
        val badLng = assertThrows<BizException> { svc().createZone(userId, deviceId, zoneDto(lng = 181.0)) }

        listOf(badRadius, hugeRadius, badLat, badLng).forEach {
            assertEquals(ParentErr.PARAM_ERROR, it.code)
        }
        verify(geofenceZoneRepository, never()).save(any())
    }

    @Test
    fun updateZone_rejectsOutOfRangeGeometry() {
        `when`(ownershipGuard.requireOwned(userId, deviceId)).thenReturn(owned())
        `when`(geofenceZoneRepository.findById("z1")).thenReturn(Optional.of(zone()))

        val ex = assertThrows<BizException> {
            svc().updateZone(userId, deviceId, "z1", zoneDto(radius = 0))
        }
        assertEquals(ParentErr.PARAM_ERROR, ex.code)
        verify(geofenceZoneRepository, never()).save(any())
    }
}
