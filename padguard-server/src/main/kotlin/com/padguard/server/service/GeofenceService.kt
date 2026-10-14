package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.GeofenceZone
import com.padguard.server.domain.LocationTrack
import com.padguard.server.dto.GeofenceZoneDto
import com.padguard.server.dto.LocationDto
import com.padguard.server.dto.LocationPoint
import com.padguard.server.dto.TrackPointDto
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.GeofenceZoneRepository
import com.padguard.server.repository.LocationTrackRepository
import com.padguard.server.security.DeviceOwnershipGuard
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import java.util.UUID
import kotlin.math.*

/**
 * 定位 / 电子围栏服务（多围栏，一对多）。
 *
 * - 一台设备可配置多个安全区域（[GeofenceZone]）。
 * - 实时位置、轨迹查询与围栏 CRUD 都在这里；越界检测在 [detectTransitions]，
 *   由 [com.padguard.server.service.IngestService] 在孩子端上报时调用。
 */
@Service
class GeofenceService(
    private val deviceRepository: DeviceRepository,
    private val locationTrackRepository: LocationTrackRepository,
    private val geofenceZoneRepository: GeofenceZoneRepository,
    private val alertService: AlertService,
    private val ownershipGuard: DeviceOwnershipGuard
) {
    fun currentLocation(userId: String, deviceId: String): LocationDto {
        val dev = ownershipGuard.requireOwned(userId, deviceId)
        val latest = locationTrackRepository
            .findByDeviceIdOrderByTsDesc(deviceId, PageRequest.of(0, 1)).firstOrNull()
        val lat = latest?.lat ?: dev.latitude ?: 0.0
        val lng = latest?.lng ?: dev.longitude ?: 0.0
        return LocationDto(
            latitude = lat, longitude = lng, address = latest?.address,
            accuracy = latest?.accuracy?.toFloat(),
            timestamp = latest?.ts ?: System.currentTimeMillis(),
            fresh = latest?.fresh
        )
    }

    /**
     * 全量历史轨迹时间线：返回最近 [days] 天内的定位点（按时间倒序）。
     * 与"越界轨迹"的区别：这里返回的是**所有**上报点，由前端按日分组回放，
     * 不再局限于越界后那段（旧版语义不准确，现已纠正）。
     */
    fun trackHistory(userId: String, deviceId: String, limit: Int, days: Int): List<TrackPointDto> {
        ownershipGuard.requireOwned(userId, deviceId)
        val since = System.currentTimeMillis() - (days.coerceIn(1, 30) * 24L * 3600 * 1000)
        val page = locationTrackRepository
            .findByDeviceIdAndTsGreaterThanEqualOrderByTsDesc(
                deviceId, since, PageRequest.of(0, limit.coerceAtLeast(1).coerceAtMost(10000))
            )
        return page.map {
            TrackPointDto(
                latitude = it.lat ?: 0.0, longitude = it.lng ?: 0.0,
                address = it.address,
                accuracy = it.accuracy?.toFloat(), timestamp = it.ts ?: 0,
                fresh = it.fresh
            )
        }
    }

    fun listZones(userId: String, deviceId: String): List<GeofenceZoneDto> {
        ownershipGuard.requireOwned(userId, deviceId)
        return geofenceZoneRepository.findByDeviceIdOrderByCreatedAtAsc(deviceId).map { it.toDto() }
    }

    fun createZone(userId: String, deviceId: String, req: GeofenceZoneDto): GeofenceZoneDto {
        ownershipGuard.requireOwned(userId, deviceId)
        val zone = GeofenceZone(
            id = UUID.randomUUID().toString(), deviceId = deviceId,
            enabled = req.enabled, name = req.name?.takeIf { it.isNotBlank() } ?: "安全区域",
            centerLat = req.centerLatitude, centerLng = req.centerLongitude,
            radiusMeters = req.radiusMeters, alertOnExit = req.alertOnExit,
            alertOnEnter = req.alertOnEnter, createdAt = System.currentTimeMillis()
        )
        geofenceZoneRepository.save(zone)
        return zone.toDto()
    }

    fun updateZone(userId: String, deviceId: String, id: String, req: GeofenceZoneDto): GeofenceZoneDto {
        ownershipGuard.requireOwned(userId, deviceId)
        val zone = geofenceZoneRepository.findById(id).orElse(null)
            ?: throw BizException(ParentErr.GEOFENCE_NOT_FOUND, "围栏不存在")
        if (zone.deviceId != deviceId) {
            // 越权访问他人的围栏：按"不存在"处理，不泄露归属。
            throw BizException(ParentErr.GEOFENCE_NOT_FOUND, "围栏不存在")
        }
        zone.enabled = req.enabled
        zone.name = req.name?.takeIf { it.isNotBlank() } ?: "安全区域"
        zone.centerLat = req.centerLatitude
        zone.centerLng = req.centerLongitude
        zone.radiusMeters = req.radiusMeters
        zone.alertOnExit = req.alertOnExit
        zone.alertOnEnter = req.alertOnEnter
        geofenceZoneRepository.save(zone)
        return zone.toDto()
    }

    fun deleteZone(userId: String, deviceId: String, id: String) {
        ownershipGuard.requireOwned(userId, deviceId)
        val zone = geofenceZoneRepository.findById(id).orElse(null)
            ?: throw BizException(ParentErr.GEOFENCE_NOT_FOUND, "围栏不存在")
        // 越权访问他人的围栏：按"不存在"处理，与 updateZone 行为一致，不泄露归属。
        if (zone.deviceId != deviceId) {
            throw BizException(ParentErr.GEOFENCE_NOT_FOUND, "围栏不存在")
        }
        geofenceZoneRepository.delete(zone)
    }

    /**
     * 批量围栏进入/离开检测（一对多）。
     *
     * 原 [detectGeofenceExits] 只处理单围栏、且只告警"离开"。改为遍历该设备的**每一个**
     * 启用围栏，分别判定进入（[GeofenceZone.alertOnEnter]）与离开（[GeofenceZone.alertOnExit]）
     * 状态切换并告警。状态机只告警"进入 -> 离开"或"离开 -> 进入"的边界点，避免连续点刷屏。
     *
     * @param points 本批上报的定位点（调用方已完成批量落库）
     * @param prevPoint 本批之前数据库中最新的一个点，用于判断批次起点是否刚穿越围栏边界
     */
    fun detectTransitions(deviceId: String, points: List<LocationPoint>, prevPoint: LocationTrack?) {
        val zones = geofenceZoneRepository.findByDeviceIdOrderByCreatedAtAsc(deviceId)
        if (zones.isEmpty()) return

        val ordered = points.filter { it.lat != null && it.lng != null }
            .sortedBy { it.ts ?: 0L }
        if (ordered.isEmpty()) return

        for (zone in zones) {
            if (!zone.enabled) continue
            val centerLat = zone.centerLat ?: continue
            val centerLng = zone.centerLng ?: continue
            val radius = zone.radiusMeters ?: continue

            var prevInside = prevPoint?.lat != null && prevPoint.lng != null &&
                haversine(prevPoint.lat!!, prevPoint.lng!!, centerLat, centerLng) <= radius

            for (p in ordered) {
                val inside = haversine(p.lat!!, p.lng!!, centerLat, centerLng) <= radius
                when {
                    !inside && prevInside && zone.alertOnExit -> alertService.create(
                        deviceId, "WARNING", "离开安全区域",
                        "设备已离开「${zone.name ?: "安全区域"}」", "GEOFENCE_EXIT"
                    )
                    inside && !prevInside && zone.alertOnEnter -> alertService.create(
                        deviceId, "NOTICE", "进入安全区域",
                        "设备已进入「${zone.name ?: "安全区域"}」", "GEOFENCE_ENTER"
                    )
                }
                prevInside = inside
            }
        }
    }

    private fun GeofenceZone.toDto() = GeofenceZoneDto(
        id = id, deviceId = deviceId, enabled = enabled, name = name,
        centerLatitude = centerLat ?: 0.0, centerLongitude = centerLng ?: 0.0,
        radiusMeters = radiusMeters ?: 0, alertOnExit = alertOnExit,
        alertOnEnter = alertOnEnter, createdAt = createdAt
    )

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return (r * 2 * atan2(sqrt(a), sqrt(1 - a))).toInt()
    }
}
