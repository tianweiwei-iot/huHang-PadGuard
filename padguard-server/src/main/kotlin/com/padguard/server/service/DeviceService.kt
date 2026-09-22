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
    /**
     * 孩子端绑定：消费绑定码 -> **按硬件指纹复用或新建设备** -> 下发令牌/MQTT 凭据/HMAC 密钥 -> 建默认策略
     *
     * ## 为什么必须复用而不是每次新建
     * 早期实现每次绑定都 `UUID.randomUUID()` 造一个新 deviceId。孩子端一旦重装（升级、
     * 排障重装、换机恢复），就会多出一条设备记录：家长端列表里出现两个同名"孩子平板"，
     * 而家长端选中的往往还是那条**已经死掉的旧记录** ——
     * 表现就是"实时画面一直转圈、锁屏/录屏全部 PENDING"，因为指令全发给了离线设备。
     *
     * 以 `deviceSn`（= [android.provider.Settings.Secure.ANDROID_ID]）为硬件指纹：
     * 同一次绑定用户下命中已有设备时，沿用原 deviceId 只轮换凭据，
     * 家长端的历史绑定、策略、截图记录全部保留，重装后立刻可用。
     */
    fun bindChild(req: BindRequest): BindResult {
        val userId = bindCodeService.consume(req.bindCode)
        val deviceToken = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")
        val hmacSecret = "hs_" + UUID.randomUUID().toString().replace("-", "")
        val mqttPassword = "mk_" + UUID.randomUUID().toString().replace("-", "")
        val now = System.currentTimeMillis()

        val existing = matchExistingDevice(userId, req)
        if (existing != null) {
            existing.apply {
                name = name ?: "孩子平板"
                deviceSn = req.deviceSn ?: deviceSn
                fingerprint = req.fingerprint ?: fingerprint
                model = req.model ?: model
                brand = req.brand ?: brand
                osVersion = req.androidVersion ?: osVersion
                sdkInt = req.sdkInt ?: sdkInt
                appVersion = req.appVersion ?: appVersion
                controlMode = req.controlMode ?: controlMode
                this.hmacSecret = hmacSecret
                deviceTokenHash = HashUtil.sha256(deviceToken)
                this.mqttPassword = mqttPassword
            }
            deviceRepository.save(existing)
            return BindResult(
                deviceId = existing.id, deviceToken = deviceToken,
                mqttUsername = existing.id, mqttPassword = mqttPassword,
                hmacSecret = hmacSecret, expiresAt = now + tokenTtlDays * 86_400_000
            )
        }

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

    /**
     * 在同一家长名下按硬件指纹找"同一台平板"的历史记录。
     *
     * 优先 `deviceSn`（ANDROID_ID，重装不变）；退而求其次用 `fingerprint`（ROM 指纹，
     * 同型号同版本的多台平板会撞车，故只在 deviceSn 缺失时兜底）。
     */
    private fun matchExistingDevice(userId: String, req: BindRequest): Device? {
        val mine = deviceRepository.findByUserId(userId)
        val sn = req.deviceSn?.takeIf { it.isNotBlank() }
        if (sn != null) {
            mine.firstOrNull { it.deviceSn == sn }?.let { return it }
        }
        val fp = req.fingerprint?.takeIf { it.isNotBlank() }
        if (fp != null) {
            val byFp = mine.filter { it.fingerprint == fp }
            // 只有唯一命中才敢复用：多条同指纹说明家长有多台同型号平板，认错了就串台
            if (byFp.size == 1) return byFp[0]
        }
        return null
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
