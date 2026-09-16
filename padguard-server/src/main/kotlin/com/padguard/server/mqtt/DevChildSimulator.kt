package com.padguard.server.mqtt

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.dto.AckPacket
import com.padguard.server.dto.CommandPacket
import com.padguard.server.service.FileStorageService
import com.padguard.server.service.MonitorService
import jakarta.annotation.PostConstruct
import org.eclipse.paho.mqttv5.client.IMqttToken
import org.eclipse.paho.mqttv5.client.MqttCallback
import org.eclipse.paho.mqttv5.client.MqttClient
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions
import org.eclipse.paho.mqttv5.client.MqttMessage
import org.eclipse.paho.mqttv5.client.persist.MqttDefaultFilePersistence
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

/**
 * 开发用"孩子端模拟器"：无真实被管控端时，自动订阅下行指令并回执 ack，
 * 便于在本地演示"绑定 -> 锁屏 -> ack 回显"主链路闭环。
 * 由 padguard.dev.child-simulator=true 开启；生产环境务必关闭。
 */
@Component
class DevChildSimulator(
    @Value("\${padguard.mqtt.broker-url}") private val brokerUrl: String,
    @Value("\${padguard.mqtt.tenant:default}") private val tenant: String,
    @Value("\${padguard.dev.child-simulator:false}") private val enabled: Boolean,
    private val objectMapper: ObjectMapper,
    private val fileStorageService: FileStorageService,
    private val monitorService: MonitorService
) : MqttCallback {

    private val log = LoggerFactory.getLogger(DevChildSimulator::class.java)
    private var client: MqttClient? = null

    @PostConstruct
    fun start() {
        if (!enabled) return
        val clientId = "padguard-sim-" + UUID.randomUUID().toString().substring(0, 8)
        client = MqttClient(
            brokerUrl, clientId,
            MqttDefaultFilePersistence(System.getProperty("java.io.tmpdir") + "/padguard-sim-mqtt")
        )
        val opts = MqttConnectionOptions().apply {
            cleanStart = true
            keepAliveInterval = 90
            automaticReconnect = true
        }
        try {
            client!!.connect(opts)
            client!!.setCallback(this)
            client!!.subscribe("pg/v1/$tenant/down/+/command", 1)
            log.info("DevChildSimulator started (auto-ack enabled)")
        } catch (e: Exception) {
            log.warn("DevChildSimulator failed to connect (ignored): ${e.message}")
        }
    }

    override fun messageArrived(topic: String?, message: MqttMessage?) {
        if (topic == null || message == null) return
        try {
            val deviceId = topic.split("/")[4]
            val packet = objectMapper.readValue(String(message.payload, StandardCharsets.UTF_8), CommandPacket::class.java)
            // 截图指令：除回执外，再生成一张占位截图上传，便于演示截图闭环
            if (packet.type == "SCREENSHOT") {
                val shotId = (packet.payload?.get("shotId") as? String)?.takeIf { it.isNotBlank() }
                val png = Base64.getDecoder().decode(PLACEHOLDER_PNG_B64)
                val url = fileStorageService.store("image/png", png, "placeholder.png")
                monitorService.storeScreenshot(shotId, deviceId, url, 1, 1, System.currentTimeMillis())
                log.info("Sim uploaded placeholder screenshot for $deviceId")
            }
            val ack = AckPacket(
                msgId = packet.msgId, deviceId = deviceId,
                status = "SUCCESS", executedAt = System.currentTimeMillis()
            )
            val ackMsg = MqttMessage(objectMapper.writeValueAsBytes(ack))
            ackMsg.qos = 1
            client?.publish("pg/v1/$tenant/up/$deviceId/ack", ackMsg)
            log.info("Sim auto-ack command ${packet.msgId} (${packet.type}) for $deviceId")
        } catch (e: Exception) {
            log.warn("sim ack error: ${e.message}")
        }
    }

    override fun connectionLost(cause: Throwable?) {
        log.warn("Sim MQTT connection lost: ${cause?.message}")
    }

    override fun deliveryComplete(token: IMqttToken?) {}

    override fun connectComplete(reconnect: Boolean, serverURI: String?) {}

    companion object {
        // 1x1 红色 PNG，用于模拟器占位截图
        private const val PLACEHOLDER_PNG_B64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    }
}
