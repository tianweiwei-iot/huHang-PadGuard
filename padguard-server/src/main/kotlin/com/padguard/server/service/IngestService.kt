package com.padguard.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.domain.UsageLog
import com.padguard.server.dto.LocationPoint
import com.padguard.server.dto.LogItem
import com.padguard.server.dto.LogsRequest
import com.padguard.server.dto.LocationsRequest
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.UsageLogRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class IngestService(
    private val usageLogRepository: UsageLogRepository,
    private val deviceRepository: DeviceRepository,
    private val objectMapper: ObjectMapper,
    private val geofenceService: GeofenceService,
    private val unlockService: UnlockService
) {
    /** 孩子端批量上报行为日志：按 logId 幂等去重；UNLOCK_REQUEST 转临时解锁工单 */
    fun saveLogs(req: LogsRequest): Map<String, Int> {
        var accepted = 0
        var duplicated = 0
        for (item in req.logs) {
            if (usageLogRepository.existsByLogId(item.logId)) {
                duplicated++
                continue
            }
            usageLogRepository.save(
                UsageLog(
                    id = UUID.randomUUID().toString(), deviceId = req.deviceId, logId = item.logId,
                    type = item.type, timestamp = item.timestamp,
                    payloadJson = item.payload?.let { objectMapper.writeValueAsString(it) },
                    receivedAt = System.currentTimeMillis()
                )
            )
            if (item.type == "UNLOCK_REQUEST") {
                unlockService.createFromRequest(req.deviceId, item.payload)
            }
            accepted++
        }
        return mapOf("accepted" to accepted, "duplicated" to duplicated)
    }

    /** 孩子端上报定位轨迹：逐点入库 + 围栏检测；同时更新设备最新位置 */
    fun saveLocations(req: LocationsRequest): Int {
        for (p in req.points) {
            geofenceService.ingestPoint(req.deviceId, p.lat, p.lng, p.accuracy, p.provider, p.ts)
        }
        val last = req.points.lastOrNull()
        if (last != null) {
            deviceRepository.findById(req.deviceId).ifPresent { dev ->
                last.lat?.let { dev.latitude = it }
                last.lng?.let { dev.longitude = it }
                deviceRepository.save(dev)
            }
        }
        return req.points.size
    }
}
