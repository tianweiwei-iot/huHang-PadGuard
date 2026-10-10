package com.padguard.server.mqtt

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.domain.DeviceEvent
import com.padguard.server.dto.AckPacket
import com.padguard.server.dto.EventPacket
import com.padguard.server.dto.HeartbeatDto
import com.padguard.server.repository.CommandRepository
import com.padguard.server.repository.DeviceEventRepository
import com.padguard.server.service.AlertService
import com.padguard.server.service.DeviceService
import com.padguard.server.ws.WebSocketPushService
import jakarta.annotation.PostConstruct
import org.eclipse.paho.mqttv5.client.IMqttToken
import org.eclipse.paho.mqttv5.client.MqttCallback
import org.eclipse.paho.mqttv5.client.MqttClient
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse
import org.eclipse.paho.mqttv5.common.MqttException
import org.eclipse.paho.mqttv5.common.MqttMessage
import org.eclipse.paho.mqttv5.common.packet.MqttProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.util.UUID

/** 接收被管控端上行：ack / heartbeat / event / status，并回写状态、推 WS */
@Component
class ChildUplinkHandler(
    private val mqttClient: MqttClient?,
    private val objectMapper: ObjectMapper,
    private val commandRepository: CommandRepository,
    private val deviceEventRepository: DeviceEventRepository,
    private val deviceService: DeviceService,
    private val webSocketPush: WebSocketPushService,
    private val alertService: AlertService
) : MqttCallback {

    private val log = LoggerFactory.getLogger(ChildUplinkHandler::class.java)

    @PostConstruct
    fun subscribe() {
        val client = mqttClient ?: run {
            log.info("MQTT disabled (padguard.mqtt.enabled=false): uplink subscriber skipped")
            return
        }
        client.setCallback(this)
        client.subscribe("pg/v1/+/up/+/ack", 1)
        client.subscribe("pg/v1/+/up/+/heartbeat", 1)
        client.subscribe("pg/v1/+/up/+/event", 1)
        client.subscribe("pg/v1/+/up/+/status", 1)
        log.info("ChildUplinkHandler subscribed to uplink topics")
    }

    override fun messageArrived(topic: String?, message: MqttMessage?) {
        if (topic == null || message == null) return
        val segments = topic.split("/")
        // pg/v1/{tenant}/up/{deviceId}/{type}
        if (segments.size < 6) return
        val deviceId = segments[4]
        val type = segments[5]
        val json = String(message.payload, StandardCharsets.UTF_8)
        when (type) {
            "ack" -> handleAck(deviceId, json)
            "heartbeat" -> handleHeartbeat(deviceId, json)
            "event" -> handleEvent(deviceId, json)
            "status" -> handleStatus(deviceId, json)
        }
    }

    /**
     * 处理指令回执：MQTT 上行与 HTTP 降级端点共用。
     *
     * **必须同时兼容三种载荷形状**，否则回执会整体失败（历史故障）：
     * 1. 裸回执 `{msgId, status, ...}`（MQTT 上行）
     * 2. 批量包装 `{deviceId, acks:[{...}, {...}]}`（孩子端 HTTP 上报的实际格式）
     * 3. 数组 `[{...}, {...}]`
     *
     * 之前只认第 1 种，孩子端发的第 2 种被强行按 AckPacket 解析 → msgId 缺失 → 抛异常 →
     * HTTP 500。结果是**所有指令的回执全部石沉大海**：家长端只看到"指令发送失败"，
     * 而孩子端其实早已执行成功（锁屏已生效、截图已上传），形成极难定位的"假失败"。
     */
    fun handleAck(deviceId: String, json: String) {
        val root = runCatching { objectMapper.readTree(json) }.getOrNull() ?: return
        val nodes: List<com.fasterxml.jackson.databind.JsonNode> = when {
            root.isArray -> root.toList()
            root.has("acks") && root.get("acks").isArray -> root.get("acks").toList()
            else -> listOf(root)
        }
        for (node in nodes) {
            val ack = runCatching { objectMapper.treeToValue(node, AckPacket::class.java) }.getOrNull()
                ?: continue
            if (ack.msgId.isBlank()) continue
            applyAck(deviceId, ack)
        }
    }

    /**
     * ack 状态机（S6）。三条硬约束：
     *
     * 1. **双键匹配**：按 msgId + deviceId 查指令。只按 msgId 查的话，任何知道别人 msgId
     *    的设备都能伪造回执（MQTT 上行无来源校验），把别人的指令改成 SUCCESS。
     * 2. **白名单**：只接受孩子端契约 [com.padguard.core.data.model.AckStatus] 定义过的
     *    5 种状态（SUCCESS/FAILED/UNSUPPORTED/DUPLICATED/REJECTED）+ 服务端 EXPIRED 之外的
     *    任意垃圾值一律丢弃，避免脏状态进库后家长端轮询逻辑失明。
     * 3. **单向流转**：终态（SUCCESS/UNSUPPORTED/DUPLICATED/REJECTED/EXPIRED）不可再被覆盖。
     *    EXPIRED 是服务端 sweeper 判定的最终结论，之后才慢悠悠到达的回执不能"复活"指令；
     *    FAILED 不算终态 —— 孩子端 FAILED 会带 retryCount<3 重试，允许 FAILED -> SUCCESS 升级。
     */
    private fun applyAck(deviceId: String, ack: AckPacket) {
        val cmd = commandRepository.findByMsgIdAndDeviceId(ack.msgId, deviceId) ?: run {
            log.warn("ack dropped: msgId={} not found on device {}", ack.msgId, deviceId)
            return
        }
        if (ack.status !in ACK_ALLOWED_STATUSES) {
            log.warn("ack dropped: msgId={} illegal status={}", ack.msgId, ack.status)
            return
        }
        if (cmd.status in ACK_TERMINAL_STATUSES) {
            log.info("ack ignored: msgId={} already terminal status={}", ack.msgId, cmd.status)
            return
        }
        cmd.status = ack.status
        cmd.executedAt = ack.executedAt
        // P0-5：回执里的错误码 / 描述一并落库，家长端才能看到"指令为什么没生效"。
        // 仅当回执确实带错误时才覆盖，避免把历史 SUCCESS 回执的 errorCode 清掉。
        if (!ack.errorCode.isNullOrBlank() || !ack.errorMessage.isNullOrBlank()) {
            cmd.errorCode = ack.errorCode
            cmd.errorMessage = ack.errorMessage
        }
        commandRepository.save(cmd)
        deviceService.getUserId(deviceId)?.let { userId ->
            webSocketPush.pushToUser(userId, mapOf(
                "type" to "command.result", "deviceId" to deviceId,
                "msgId" to ack.msgId, "status" to ack.status
            ))
        }
    }

    companion object {
        /** 孩子端契约 AckStatus 全集（护航成长 core/data Command.kt:156） */
        val ACK_ALLOWED_STATUSES = setOf("SUCCESS", "FAILED", "UNSUPPORTED", "DUPLICATED", "REJECTED")

        /** 终态：一旦到达不可回退。FAILED 可重试所以不在内；EXPIRED 是服务端 sweeper 结论。 */
        val ACK_TERMINAL_STATUSES = setOf("SUCCESS", "UNSUPPORTED", "DUPLICATED", "REJECTED", "EXPIRED")
    }

    private fun handleHeartbeat(deviceId: String, json: String) {
        val hb = objectMapper.readValue(json, HeartbeatDto::class.java)
        deviceService.applyHeartbeat(deviceId, hb)
    }

    private fun handleEvent(deviceId: String, json: String) {
        val ev = objectMapper.readValue(json, EventPacket::class.java)
        handleEventPackets(deviceId, listOf(ev))
    }

    /**
     * 风险事件入库 + 告警 + WS 推送 —— **MQTT 与 HTTP 两条通路共用的唯一实现**。
     *
     * 为什么必须共用而不是各写一份：HTTP 是 MQTT 不可用时的**降级**通道。
     * 两边各写一份的话语义迟早漂移（一边落库一边不落、一边告警一边不告警），
     * 真出问题时根本说不清"告警到底为什么丢"。
     *
     * @return (accepted, duplicated)
     */
    fun handleEventPackets(deviceId: String, events: List<EventPacket>): Pair<Int, Int> {
        if (events.isEmpty()) return 0 to 0

        // 批内先去重，再一次性查库：避免同一条事件在同批里被处理两次、
        // 也避免逐条 exists 造成的上百次 DB 往返。
        val candidates = events.distinctBy { it.eventId }
        val existing = deviceEventRepository.findExistingEventIds(candidates.map { it.eventId })
        val fresh = candidates.filter { it.eventId !in existing }
        if (fresh.isEmpty()) return 0 to events.size

        // S8：批量写入；并发上报同一 eventId 撞 unique 约束时降级为逐行 ON CONFLICT DO NOTHING。
        // 告警与 WS 推送只作用于**真正落库**的事件，保持「去重作用在整条链路」的语义不变。
        val inserted = try {
            deviceEventRepository.saveAll(fresh.map { ev ->
                DeviceEvent(
                    id = UUID.randomUUID().toString(), deviceId = deviceId, eventId = ev.eventId,
                    type = ev.type, level = ev.level, timestamp = ev.timestamp,
                    detailJson = objectMapper.writeValueAsString(ev.detail ?: emptyMap<String, Any?>())
                )
            })
            fresh
        } catch (e: org.springframework.dao.DataIntegrityViolationException) {
            log.warn("device_events 批量写入撞唯一约束（并发上报竞态），降级逐行 upsert: {}", e.message)
            fresh.filter { ev ->
                deviceEventRepository.insertIgnoreConflict(
                    id = UUID.randomUUID().toString(), deviceId = deviceId, eventId = ev.eventId,
                    type = ev.type, level = ev.level,
                    detailJson = objectMapper.writeValueAsString(ev.detail ?: emptyMap<String, Any?>()),
                    timestamp = ev.timestamp
                ) > 0
            }
        }

        // 高危事件落告警并推 WS。
        // 去重作用在**整条链路**上，而不是只挡住落库：MQTT QoS1 与 HTTP 重试都会让同一条
        // 事件重复到达，只挡落库却照常告警的话，一次重传就给家长推一条重复告警。
        inserted.forEach { ev ->
            alertService.createFromEvent(deviceId, ev.type, ev.level, ev.detail)
            deviceService.getUserId(deviceId)?.let { userId ->
                webSocketPush.pushToUser(userId, mapOf(
                    "type" to "alert.new", "deviceId" to deviceId,
                    "eventType" to ev.type, "level" to ev.level
                ))
            }
        }
        return inserted.size to (events.size - inserted.size)
    }

    private fun handleStatus(deviceId: String, json: String) {
        val online = (objectMapper.readValue(json, Map::class.java)["online"] as? Boolean) ?: return
        if (online) {
            deviceService.applyHeartbeatOnline(deviceId)
        } else {
            deviceService.setOffline(deviceId)
        }
    }

    override fun disconnected(disconnectResponse: MqttDisconnectResponse?) {
        log.warn("MQTT disconnected: ${disconnectResponse?.reasonString}")
    }

    override fun mqttErrorOccurred(exception: MqttException?) {
        log.warn("MQTT error occurred: ${exception?.message}")
    }

    override fun authPacketArrived(reasonCode: Int, properties: MqttProperties?) {}

    override fun deliveryComplete(token: IMqttToken?) {}

    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
        log.info("MQTT connectComplete reconnect=$reconnect uri=$serverURI")
    }
}
