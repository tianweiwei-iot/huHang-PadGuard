package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.Geofence
import com.padguard.server.domain.LocationTrack
import com.padguard.server.dto.GeofenceDto
import com.padguard.server.dto.LocationDto
import com.padguard.server.dto.TrackPointDto
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.GeofenceRepository
import com.padguard.server.repository.LocationTrackRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import java.util.UUID
import kotlin.math.*

@Service
class GeofenceService(
    private val deviceRepository: DeviceRepository,
    private val locationTrackRepository: LocationTrackRepository,
    private val geofenceRepository: GeofenceRepository,
    private val alertService: AlertService
) {
    fun currentLocation(deviceId: String): LocationDto {
        val dev = deviceRepository.findById(deviceId).orElse(null)
            ?: throw BizException(ParentErr.DEVICE_NOT_FOUND, "设备不存在", Audience.PARENT)
        val latest = locationTrackRepository
            .findByDeviceIdOrderByTsDesc(deviceId, PageRequest.of(0, 1)).firstOrNull()
        val lat = latest?.lat ?: dev.latitude ?: 0.0
        val lng = latest?.lng ?: dev.longitude ?: 0.0
        return LocationDto(
            latitude = lat, longitude = lng, accuracy = latest?.accuracy?.toFloat(),
            timestamp = latest?.ts ?: System.currentTimeMillis()
        )
    }

    fun trackHistory(deviceId: String, limit: Int): List<TrackPointDto> =
        locationTrackRepository
            .findByDeviceIdOrderByTsDesc(deviceId, PageRequest.of(0, limit.coerceAtLeast(1)))
            .map {
                TrackPointDto(
                    latitude = it.lat ?: 0.0, longitude = it.lng ?: 0.0,
                    accuracy = it.accuracy?.toFloat(), timestamp = it.ts ?: 0
                )
            }

    fun getGeofence(deviceId: String): GeofenceDto {
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

    fun updateGeofence(deviceId: String, req: GeofenceDto): GeofenceDto {
        val g = geofenceRepository.findById(deviceId).orElse(Geofence(deviceId = deviceId))
        g.enabled = req.enabled
        g.name = req.name
        g.centerLat = req.centerLatitude
        g.centerLng = req.centerLongitude
        g.radiusMeters = req.radiusMeters
        g.alertOnExit = req.alertOnExit
        geofenceRepository.save(g)
        return getGeofence(deviceId)
    }

    /** 收到定位点时调用：入库 + 围栏越界检测（离开 -> 进 -> 离开 时才告警，避免刷屏） */
    fun ingestPoint(deviceId: String, lat: Double?, lng: Double?, accuracy: Double?, provider: String?, ts: Long?) {
        val point = LocationTrack(
            id = UUID.randomUUID().toString(), deviceId = deviceId, lat = lat, lng = lng,
            accuracy = accuracy, provider = provider, ts = ts, receivedAt = System.currentTimeMillis()
        )
        locationTrackRepository.save(point)

        val g = geofenceRepository.findById(deviceId).orElse(null)
        if (g != null && g.enabled && lat != null && lng != null &&
            g.centerLat != null && g.centerLng != null && g.radiusMeters != null
        ) {
            val outside = haversine(lat, lng, g.centerLat!!, g.centerLng!!) > g.radiusMeters!!
            if (outside && g.alertOnExit) {
                val prev = locationTrackRepository
                    .findByDeviceIdOrderByTsDesc(deviceId, PageRequest.of(0, 2))
                    .getOrNull(1)
                val prevInside = prev?.lat != null && prev.lng != null &&
                    haversine(prev.lat!!, prev.lng!!, g.centerLat!!, g.centerLng!!) <= g.radiusMeters!!
                if (prevInside) {
                    alertService.create(
                        deviceId, "WARNING", "离开电子围栏",
                        "设备已离开设定安全区域", "GEOFENCE_EXIT"
                    )
                }
            }
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
