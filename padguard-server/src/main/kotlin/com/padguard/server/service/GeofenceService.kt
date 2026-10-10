package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.Geofence
import com.padguard.server.domain.LocationTrack
import com.padguard.server.dto.GeofenceDto
import com.padguard.server.dto.LocationDto
import com.padguard.server.dto.LocationPoint
import com.padguard.server.dto.TrackPointDto
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.GeofenceRepository
import com.padguard.server.repository.LocationTrackRepository
import com.padguard.server.security.DeviceOwnershipGuard
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import java.util.UUID
import kotlin.math.*

@Service
class GeofenceService(
    private val deviceRepository: DeviceRepository,
    private val locationTrackRepository: LocationTrackRepository,
    private val geofenceRepository: GeofenceRepository,
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
            latitude = lat, longitude = lng, accuracy = latest?.accuracy?.toFloat(),
            timestamp = latest?.ts ?: System.currentTimeMillis()
        )
    }

    fun trackHistory(userId: String, deviceId: String, limit: Int): List<TrackPointDto> {
        ownershipGuard.requireOwned(userId, deviceId)
        return locationTrackRepository
            .findByDeviceIdOrderByTsDesc(deviceId, PageRequest.of(0, limit.coerceAtLeast(1)))
            .map {
                TrackPointDto(
                    latitude = it.lat ?: 0.0, longitude = it.lng ?: 0.0,
                    accuracy = it.accuracy?.toFloat(), timestamp = it.ts ?: 0
                )
            }
    }

    fun getGeofence(userId: String, deviceId: String): GeofenceDto {
        ownershipGuard.requireOwned(userId, deviceId)
        val g = geofenceRepository.findById(deviceId).orElse(null)
        return if (g != null) GeofenceDto(
            deviceId = deviceId, enabled = g.enabled, name = g.name,
            centerLatitude = g.centerLat ?: 0.0, centerLongitude = g.centerLng ?: 0.0,
            radiusMeters = g.radiusMeters ?: 0, alertOnExit = g.alertOnExit
        ) else GeofenceDto(
            deviceId = deviceId, enabled = false, name = null,
            centerLatitude = 0.0, centerLongitude = 0.0, radiusMeters = 0, alertOnExit = true
        )
    }

    fun updateGeofence(userId: String, deviceId: String, req: GeofenceDto): GeofenceDto {
        ownershipGuard.requireOwned(userId, deviceId)
        val g = geofenceRepository.findById(deviceId).orElse(Geofence(deviceId = deviceId))
        g.enabled = req.enabled
        g.name = req.name
        g.centerLat = req.centerLatitude
        g.centerLng = req.centerLongitude
        g.radiusMeters = req.radiusMeters
        g.alertOnExit = req.alertOnExit
        geofenceRepository.save(g)
        return getGeofence(userId, deviceId)
    }

    /**
     * 批量围栏越界（离开安全区）检测。
     *
     * 原 [ingestPoint] 每点一次 DB 保存 + 一次「查前一点」回查（N 点 = N 次写 + N 次读），
     * 是定位上行的主要延迟来源。改为：批量保存交给调用方一次性 saveAll，
     * 这里只在内存里按时间序走一遍「上一点的状态」，仅对**批前最后一点**做一次回查。
     *
     * 只告警「进入 -> 离开」的越界（离开安全区），进入安全区不告警，避免孩子一直在区内时反复刷屏。
     *
     * @param points 本批上报的定位点（调用方已完成批量落库）
     * @param prevPoint 本批之前数据库中最新的一个点（调用方在落库前查询得到），
     *                  用于判断「批次起点」是否刚从区内越出围栏
     */
    fun detectGeofenceExits(deviceId: String, points: List<LocationPoint>, prevPoint: LocationTrack?) {
        val g = geofenceRepository.findById(deviceId).orElse(null)
        if (g == null || !g.enabled || !g.alertOnExit) return
        if (g.centerLat == null || g.centerLng == null || g.radiusMeters == null) return

        val centerLat = g.centerLat!!
        val centerLng = g.centerLng!!
        val radius = g.radiusMeters!!

        val ordered = points.filter { it.lat != null && it.lng != null }
            .sortedBy { it.ts ?: 0L }
        if (ordered.isEmpty()) return

        var prevInside = prevPoint?.lat != null && prevPoint.lng != null &&
            haversine(prevPoint.lat!!, prevPoint.lng!!, centerLat, centerLng) <= radius

        for (p in ordered) {
            val inside = haversine(p.lat!!, p.lng!!, centerLat, centerLng) <= radius
            if (!inside && prevInside) {
                alertService.create(
                    deviceId, "WARNING", "离开电子围栏",
                    "设备已离开设定安全区域", "GEOFENCE_EXIT"
                )
            }
            prevInside = inside
        }
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return (r * 2 * atan2(sqrt(a), sqrt(1 - a))).toInt()
    }
}
