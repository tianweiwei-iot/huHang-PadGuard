package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.Command
import com.padguard.server.dto.CommandDto
import com.padguard.server.dto.CommandPacket
import com.padguard.server.mqtt.MqttGateway
import com.padguard.server.repository.CommandRepository
import com.padguard.server.repository.DeviceRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Service
class CommandService(
    private val deviceRepository: DeviceRepository,
    private val commandRepository: CommandRepository,
    private val mqttGateway: MqttGateway,
    private val objectMapper: ObjectMapper
) {
    /** 家长端下发指令：构造指令包(HMAC 签名) -> 落库 -> 经 MQTT 下发 */
    fun issueCommand(
        deviceId: String, type: String,
        payload: Map<String, Any?>?, priority: String = "HIGH"
    ): CommandDto {
        val device = deviceRepository.findById(deviceId).orElse(null)
            ?: throw BizException(ParentErr.DEVICE_NOT_FOUND, "设备不存在", Audience.PARENT)

        val msgId = "cmd_" + UUID.randomUUID().toString().replace("-", "").take(24)
        val now = System.currentTimeMillis()
        val expiresAt = now + 5 * 60_000
        val payloadJson = payload?.let { objectMapper.writeValueAsString(it) }
        val signature = hmac(device!!.hmacSecret, "$msgId|$type|$now|${payloadJson ?: ""}")

        commandRepository.save(
            Command(
                id = UUID.randomUUID().toString(), deviceId = deviceId, msgId = msgId,
                type = type, priority = priority, payloadJson = payloadJson,
                signature = signature, status = "PENDING", expiresAt = expiresAt, createdAt = now
            )
        )

        mqttGateway.publishCommand(
            deviceId,
            CommandPacket(msgId, type, 1, now, expiresAt, priority, payload, signature)
        )
        return CommandDto(msgId, type, "PENDING", payload, now, null)
    }

    fun getCommand(msgId: String): CommandDto {
        val cmd = commandRepository.findByMsgId(msgId)
            ?: throw BizException(ParentErr.PARAM_ERROR, "指令不存在", Audience.PARENT)
        val payloadMap = cmd.payloadJson?.let {
            runCatching { objectMapper.readValue(it, Map::class.java) as Map<String, Any?> }.getOrNull()
        }
        return CommandDto(cmd.msgId, cmd.type, cmd.status, payloadMap, cmd.createdAt, cmd.executedAt)
    }

    /** MQTT 降级轮询通道：返回设备待执行的 PENDING 指令包 */
    fun pendingForPolling(deviceId: String, since: Long): List<CommandPacket> =
        commandRepository.findByDeviceIdOrderByCreatedAtDesc(deviceId)
            .filter { it.status == "PENDING" && it.createdAt >= since }
            .map {
                val payloadMap = it.payloadJson?.let {
                    runCatching { objectMapper.readValue(it, Map::class.java) as Map<String, Any?> }.getOrNull()
                }
                CommandPacket(
                    msgId = it.msgId, type = it.type,
                    version = 1, timestamp = it.createdAt, expiresAt = it.expiresAt,
                    priority = it.priority ?: "NORMAL", payload = payloadMap,
                    signature = it.signature ?: ""
                )
            }

    private fun hmac(secret: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
