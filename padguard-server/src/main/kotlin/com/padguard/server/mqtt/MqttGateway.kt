package com.padguard.server.mqtt

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.dto.CommandPacket
import org.eclipse.paho.mqttv5.client.MqttClient
import org.eclipse.paho.mqttv5.client.MqttMessage
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/** 服务端 -> 被管控端 的 MQTT 下发通道 */
@Component
class MqttGateway(
    private val mqttClient: MqttClient,
    private val objectMapper: ObjectMapper,
    @Value("\${padguard.mqtt.tenant:default}") private val tenant: String
) {
    fun downCommandTopic(deviceId: String) = "pg/v1/$tenant/down/$deviceId/command"
    fun downPolicyTopic(deviceId: String) = "pg/v1/$tenant/down/$deviceId/policy"
    fun downConfigTopic(deviceId: String) = "pg/v1/$tenant/down/$deviceId/config"

    fun publishCommand(deviceId: String, packet: CommandPacket) {
        publish(downCommandTopic(deviceId), packet)
    }

    fun publishPolicyNotify(deviceId: String, version: Int) {
        publish(downPolicyTopic(deviceId), mapOf("version" to version))
    }

    private fun publish(topic: String, payload: Any) {
        val msg = MqttMessage(objectMapper.writeValueAsBytes(payload))
        msg.qos = 1
        mqttClient.publish(topic, msg)
    }
}
