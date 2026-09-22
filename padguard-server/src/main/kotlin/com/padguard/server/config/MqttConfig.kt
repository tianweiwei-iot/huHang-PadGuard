package com.padguard.server.config

import org.eclipse.paho.mqttv5.client.MqttClient
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions
import org.eclipse.paho.mqttv5.client.persist.MqttDefaultFilePersistence
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.UUID

@Configuration
class MqttConfig(
    @Value("\${padguard.mqtt.broker-url}") private val brokerUrl: String,
    @Value("\${padguard.mqtt.username:}") private val username: String,
    @Value("\${padguard.mqtt.password:}") private val password: String,
    @Value("\${padguard.mqtt.keep-alive-seconds:90}") private val keepAlive: Int
) {

    private val log = LoggerFactory.getLogger(MqttConfig::class.java)

    /**
     * 仅当 padguard.mqtt.enabled=true 时创建 MQTT 客户端；本地免 Docker 运行置为 false，避免强连 EMQX 导致启动失败。
     *
     * 返回**可空**：Broker 不可达时返回 null 而非抛异常。理由：
     * - 抛异常会让整个服务端启动失败，导致家长端与被管控端全部不可用；而实际上下行走 HTTP 轮询仍可工作。
     * - “Broker 抖动”不应升级为“服务整体故障”，应降级：实时推送暂不可用，轮询继续兜底。
     * 消费方 [MqttGateway] 与 [com.padguard.server.mqtt.ChildUplinkHandler] 均已按可空处理。
     */
    @Bean
    @ConditionalOnProperty(name = ["padguard.mqtt.enabled"], havingValue = "true", matchIfMissing = true)
    fun mqttClient(): MqttClient? {
        val clientId = "padguard-server-" + UUID.randomUUID().toString().substring(0, 8)
        val persistence = MqttDefaultFilePersistence(System.getProperty("java.io.tmpdir") + "/padguard-mqtt")
        val client = MqttClient(brokerUrl, clientId, persistence)

        val pwBytes = password.toByteArray()
        val opts = MqttConnectionOptions().apply {
            setCleanStart(false)
            keepAliveInterval = keepAlive
            setAutomaticReconnect(true)
            if (username.isNotBlank()) {
                this.userName = username
                setPassword(pwBytes)
            }
        }

        // EMQX 启动需要几秒，反复重试直到连上（开发环境足够）
        var connected = false
        repeat(20) {
            try {
                client.connect(opts)
                connected = true
                return@repeat
            } catch (e: Exception) {
                Thread.sleep(3000)
            }
        }
        if (!connected) {
            log.error(
                "无法连接 MQTT Broker: {}，已降级为 HTTP 轮询下发（实时推送暂不可用）。" +
                    "请检查 Broker 是否已启动、地址是否可达。",
                brokerUrl
            )
            return null
        }
        log.info("MQTT client connected: {}", brokerUrl)
        return client
    }
}
