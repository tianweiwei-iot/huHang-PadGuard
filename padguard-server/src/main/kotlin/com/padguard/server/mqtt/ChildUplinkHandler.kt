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
import org.eclipse.paho.mqttv5.client.MqttMessage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.util.UUID

/** 接收被管控端上行：ack / heartbeat / event / status，并回写状态、推 WS */
@Component
class ChildUplinkHandler(
    private val mqttClient: MqttClient,
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
        mqttClient.setCallback(this)
        mqttClient.subscribe("pg/v1/+/up/+/ack", 1)
        mqttClient.subscribe("pg/v1/+/up/+/heartbeat", 1)
        mqttClient.subscribe("pg/v1/+/up/+/event", 1)
        mqttClient.subscribe("pg/v1/+/up/+/status", 1)
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

    private fun handleAck(deviceId: String, json: String) {
        val ack = objectMapper.readValue(json, AckPacket::class.java)
        val cmd = commandRepository.findByMsgId(ack.msgId) ?: return
        cmd.status = ack.status
        cmd.executedAt = ack.executedAt
        commandRepository.save(cmd)
        deviceService.getUserId(deviceId)?.let { userId ->
            webSocketPush.pushToUser(userId, mapOf(
                "type" to "command.result", "deviceId" to deviceId,
                "msgId" to ack.msgId, "status" to ack.status
            ))
        }
    }

    private fun handleHeartbeat(deviceId: String, json: String) {
        val hb = objectMapper.readValue(json, HeartbeatDto::class.java)
        deviceService.applyHeartbeat(deviceId, hb)
    }

    private fun handleEvent(deviceId: String, json: String) {
        val ev = objectMapper.readValue(json, EventPacket::class.java)
        if (!deviceEventRepository.existsByEventId(ev.eventId)) {
            deviceEventRepository.save(
                DeviceEvent(
                    id = UUID.randomUUID().toString(), deviceId = deviceId, eventId = ev.eventId,
                    type = ev.type, level = ev.level, timestamp = ev.timestamp,
                    detailJson = objectMapper.writeValueAsString(ev.detail ?: emptyMap<String, Any?>())
                )
            )
        }
        // 高危事件落告警并推 WS
        alertService.createFromEvent(deviceId, ev.type, ev.level, ev.detail)
        deviceService.getUserId(deviceId)?.let { userId ->
            webSocketPush.pushToUser(userId, mapOf(
                "type" to "alert.new", "deviceId" to deviceId,
                "eventType" to ev.type, "level" to ev.level
            ))
        }
    }

    private fun handleStatus(deviceId: String, json: String) {
        val online = (objectMapper.readValue(json, Map::class.java)["online"] as? Boolean) ?: return
        if (online) {
            deviceService.applyHeartbeatOnline(deviceId)
        } else {
            deviceService.setOffline(deviceId)
        }
    }

    override fun connectionLost(cause: Throwable?) {
        log.warn("MQTT connection lost: ${cause?.message}")
    }

    override fun deliveryComplete(token: IMqttToken?) {}

    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
        log.info("MQTT connectComplete reconnect=$reconnect uri=$serverURI")
    }
}
