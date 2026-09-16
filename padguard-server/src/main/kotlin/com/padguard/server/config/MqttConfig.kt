package com.padguard.server.config

import org.eclipse.paho.mqttv5.client.MqttClient
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions
import org.eclipse.paho.mqttv5.client.persist.MqttDefaultFilePersistence
import org.springframework.beans.factory.annotation.Value
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

    @Bean
    fun mqttClient(): MqttClient {
        val clientId = "padguard-server-" + UUID.randomUUID().toString().substring(0, 8)
        val persistence = MqttDefaultFilePersistence(System.getProperty("java.io.tmpdir") + "/padguard-mqtt")
        val client = MqttClient(brokerUrl, clientId, persistence)

        val opts = MqttConnectionOptions().apply {
            cleanStart = false
            keepAliveInterval = keepAlive
            automaticReconnect = true
            if (username.isNotBlank()) {
                this.userName = username
                this.password = password.toByteArray(Charsets.UTF_8)
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
        if (!connected) throw IllegalStateException("无法连接 MQTT Broker: $brokerUrl")
        return client
    }
}
