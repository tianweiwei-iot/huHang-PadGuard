package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ChildErr
import com.padguard.server.common.HashUtil
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.Device
import com.padguard.server.dto.BindRequest
import com.padguard.server.dto.BindResult
import com.padguard.server.dto.DeviceDto
import com.padguard.server.dto.HeartbeatDto
import com.padguard.server.mqtt.MqttGateway
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.UserRepository
import com.padguard.server.security.Passwords
import com.padguard.server.ws.WebSocketPushService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class DeviceService(
    private val deviceRepository: DeviceRepository,
    private val bindCodeService: BindCodeService,
    private val policyService: PolicyService,
    private val webSocketPush: WebSocketPushService,
    private val mqttGateway: MqttGateway,
    private val userRepository: UserRepository,
    @Value("\${padguard.device.token-ttl-days:30}") private val tokenTtlDays: Long
) {

    private val log = LoggerFactory.getLogger(DeviceService::class.java)
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
        return bindChildWithUser(req, userId)
    }

    /** 绑定主流程：确认归属家长后创建/复用设备并下发凭据（配对码与账号密码两种方式共用）。 */
    fun bindChildWithUser(req: BindRequest, userId: String): BindResult {
        val deviceId = UUID.randomUUID().toString()
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
     * 「账号密码绑定」：孩子端直接拿家长的手机号 + 密码绑定，无需配对码 / 扫码。
     *
     * 与 [bindChild] 的唯一区别是归属家长的确认方式（账号密码 vs 一次性绑定码），
     * 设备台账、令牌下发、默认策略等后续逻辑完全复用，避免两套实现漂移。
     */
    fun bindChildByAccount(req: com.padguard.server.dto.BindByAccountRequest): BindResult {
        val user = userRepository.findByPhone(req.phone.trim())
            ?: throw BizException(ChildErr.TOKEN_INVALID, "家长账号不存在", Audience.CHILD)
        if (!Passwords.matches(req.password, user.passwordHash)) {
            throw BizException(ChildErr.TOKEN_INVALID, "家长账号或密码错误", Audience.CHILD)
        }
        // 复用既有绑定实现：把「已确认的 userId」当作绑定码消费的结果
        return bindChildWithUser(req.req, user.id)
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
        // 关键：必须通知被控端。否则平板本地仍持有旧令牌、继续上报心跳并处于受控态，
        // 家长端看着"解绑了"，但孩子端其实还挂在管控里 —— 这就是"解绑无效"的体感来源。
        // 复用下行 config 通道下发 unbind=1，孩子端收到后清除本地凭据并回到绑定页。
        pushUnbind(deviceId)
    }

    /** 下发解绑通知给孩子端（尽力而为：平板离线时会在其下次上线后由重连逻辑兜底）。 */
    private fun pushUnbind(deviceId: String) {
        runCatching { mqttGateway.publishConfig(deviceId, mapOf("unbind" to "1")) }
            .onFailure { log.warn("push unbind config failed: $it") }
    }

    /** 家长修改设备别名 */
    fun rename(userId: String, deviceId: String, name: String) {
        val dev = requireOwned(userId, deviceId)
        dev.name = name
        deviceRepository.save(dev)
        // 实时同步到被控端：通过下行 config 通道把自定义设备名推下去，
        // 孩子端「我的」页立即显示新名称，无需重连或重启。
        pushDeviceName(deviceId, name)
    }

    /** 孩子端自定义设备名（凭设备令牌鉴权，不依赖家长 userId） */
    fun updateNameByDevice(deviceId: String, name: String) {
        deviceRepository.findById(deviceId).ifPresent { dev ->
            dev.name = name
            deviceRepository.save(dev)
        }
        // 同步回被控端：确保各端（家长看板 / 孩子端本地）最终一致
        pushDeviceName(deviceId, name)
    }

    /** 把设备名通过 MQTT 下发行推给被控端 */
    private fun pushDeviceName(deviceId: String, name: String) {
        runCatching { mqttGateway.publishConfig(deviceId, mapOf("deviceName" to name)) }
            .onFailure { log.warn("push deviceName config failed: $it") }
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
