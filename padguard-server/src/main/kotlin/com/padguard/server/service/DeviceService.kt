package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.HashUtil
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.Device
import com.padguard.server.dto.BindRequest
import com.padguard.server.dto.BindResult
import com.padguard.server.dto.DeviceDto
import com.padguard.server.dto.HeartbeatDto
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.ws.WebSocketPushService
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class DeviceService(
    private val deviceRepository: DeviceRepository,
    private val bindCodeService: BindCodeService,
    private val policyService: PolicyService,
    private val webSocketPush: WebSocketPushService,
    @Value("\${padguard.device.token-ttl-days:30}") private val tokenTtlDays: Long
) {
    /** 孩子端绑定：消费绑定码 -> 创建设备 -> 下发令牌/MQTT 凭据/HMAC 密钥 -> 建默认策略 */
    fun bindChild(req: BindRequest): BindResult {
        val userId = bindCodeService.consume(req.bindCode)
        val deviceId = UUID.randomUUID().toString()
        val deviceToken = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")
        val hmacSecret = "hs_" + UUID.randomUUID().toString().replace("-", "")
        val mqttPassword = "mk_" + UUID.randomUUID().toString().replace("-", "")
        val now = System.currentTimeMillis()

        val device = Device(
            id = deviceId, userId = userId, name = "孩子平板",
            deviceSn = req.deviceSn, fingerprint = req.fingerprint,
            model = req.model, brand = req.brand, osVersion = req.androidVersion,
            sdkInt = req.sdkInt, appVersion = req.appVersion,
            controlMode = req.controlMode, scene = "FAMILY",
            onlineStatus = "OFFLINE", hmacSecret = hmacSecret,
            deviceTokenHash = HashUtil.sha256(deviceToken),
            mqttPassword = mqttPassword, createdAt = now
        )
        deviceRepository.save(device)
        policyService.createDefault(deviceId)

        return BindResult(
            deviceId = deviceId, deviceToken = deviceToken,
            mqttUsername = deviceId, mqttPassword = mqttPassword,
            hmacSecret = hmacSecret, expiresAt = now + tokenTtlDays * 86_400_000
        )
    }

    fun listDevices(userId: String): List<DeviceDto> =
        deviceRepository.findByUserId(userId).map { it.toDto() }

    fun generateBindCode(userId: String) = bindCodeService.generate(userId)

    fun getDevice(userId: String, deviceId: String): DeviceDto {
        val dev = deviceRepository.findById(deviceId).orElse(null)
            ?: throw BizException(ParentErr.DEVICE_NOT_FOUND, "设备不存在", Audience.PARENT)
        if (dev.userId != userId) {
            throw BizException(ParentErr.DEVICE_NOT_OWNED, "设备不属于当前用户", Audience.PARENT)
        }
        return dev.toDto()
    }

    /** 处理孩子端心跳（MQTT 或 HTTP 降级共用），上线状态变更时推 WS */
    fun applyHeartbeat(deviceId: String, hb: HeartbeatDto) {
        deviceRepository.findById(deviceId).ifPresent { dev ->
            val wasOffline = dev.onlineStatus != "ONLINE"
            dev.onlineStatus = "ONLINE"
            dev.lastOnlineAt = System.currentTimeMillis()
            (hb.battery?.get("level") as? Number)?.toInt()?.let { dev.batteryLevel = it }
            hb.location?.let { loc ->
                (loc["lat"] as? Number)?.toDouble()?.let { dev.latitude = it }
                (loc["lng"] as? Number)?.toDouble()?.let { dev.longitude = it }
            }
            hb.controlMode?.let { dev.controlMode = it }
            deviceRepository.save(dev)
            if (wasOffline) {
                webSocketPush.pushToUser(dev.userId, mapOf(
                    "type" to "device.online", "deviceId" to deviceId,
                    "timestamp" to System.currentTimeMillis()
                ))
            }
        }
    }

    fun setOffline(deviceId: String) {
        deviceRepository.findById(deviceId).ifPresent { dev ->
            if (dev.onlineStatus == "ONLINE") {
                dev.onlineStatus = "OFFLINE"
                deviceRepository.save(dev)
                webSocketPush.pushToUser(dev.userId, mapOf(
                    "type" to "device.offline", "deviceId" to deviceId,
                    "lastSeen" to System.currentTimeMillis()
                ))
            }
        }
    }

    fun getUserId(deviceId: String): String? =
        deviceRepository.findById(deviceId).orElse(null)?.userId

    /** 家长解绑设备：清空归属与令牌，旧 deviceToken 立即失效 */
    fun unbind(userId: String, deviceId: String) {
        val dev = deviceRepository.findById(deviceId).orElse(null)
            ?: throw BizException(ParentErr.DEVICE_NOT_FOUND, "设备不存在", Audience.PARENT)
        if (dev.userId != userId) {
            throw BizException(ParentErr.DEVICE_NOT_OWNED, "设备不属于当前用户", Audience.PARENT)
        }
        dev.userId = null
        dev.onlineStatus = "OFFLINE"
        dev.groupId = null
        dev.deviceTokenHash = "revoked_" + UUID.randomUUID().toString()
        deviceRepository.save(dev)
    }

    /** 家长修改设备别名 */
    fun rename(userId: String, deviceId: String, name: String) {
        val dev = requireOwned(userId, deviceId)
        dev.name = name
        deviceRepository.save(dev)
    }

    /** 家长分配设备到分组 */
    fun assignToGroup(userId: String, deviceId: String, groupId: String) {
        val dev = requireOwned(userId, deviceId)
        dev.groupId = groupId
        deviceRepository.save(dev)
    }

    private fun requireOwned(userId: String, deviceId: String): Device {
        val dev = deviceRepository.findById(deviceId).orElse(null)
            ?: throw BizException(ParentErr.DEVICE_NOT_FOUND, "设备不存在", Audience.PARENT)
        if (dev.userId != userId) {
            throw BizException(ParentErr.DEVICE_NOT_OWNED, "设备不属于当前用户", Audience.PARENT)
        }
        return dev
    }

    fun applyHeartbeatOnline(deviceId: String) {
        deviceRepository.findById(deviceId).ifPresent { dev ->
            val wasOffline = dev.onlineStatus != "ONLINE"
            dev.onlineStatus = "ONLINE"
            dev.lastOnlineAt = System.currentTimeMillis()
            deviceRepository.save(dev)
            if (wasOffline) {
                webSocketPush.pushToUser(dev.userId, mapOf(
                    "type" to "device.online", "deviceId" to deviceId,
                    "timestamp" to System.currentTimeMillis()
                ))
            }
        }
    }

    private fun Device.toDto() = DeviceDto(
        id = id, name = name, deviceId = id, model = model, osVersion = osVersion,
        appVersion = appVersion, onlineStatus = onlineStatus,
        lastOnlineTime = lastOnlineAt, batteryLevel = batteryLevel,
        controlMode = controlMode, sceneMode = sceneMode, groupId = groupId,
        groupName = null, sceneType = scene, latitude = latitude, longitude = longitude
    )
}
