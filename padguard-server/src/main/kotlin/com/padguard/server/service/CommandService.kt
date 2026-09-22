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
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Service
class CommandService(
    private val deviceRepository: DeviceRepository,
    private val commandRepository: CommandRepository,
    private val mqttGateway: MqttGateway,
    private val objectMapper: ObjectMapper,
    private val expiredCommandSweeper: ExpiredCommandSweeper
) {
    /**
     * 家长端下发指令：构造指令包(HMAC 签名) -> 落库 -> 经 MQ/M轮询下发。
     *
     * 签名必须与终端侧 [com.padguard.core.data.model.Command.signingPayload] 逐字符一致，
     * 否则终端 [com.padguard.core.transport.CommandGate] 会把所有指令判为 REJECTED（含锁屏）。
     * 终端侧算法：Base64(HmacSHA256("$msgId|$type|$timestamp|${canonicalJson(payload)}"))，
     * 其中 canonicalJson 为“按键升序、值字符串化”的自定义格式。这里严格复刻。
     */
    fun issueCommand(
        deviceId: String, type: String,
        payload: Map<String, Any?>?, priority: String = "HIGH"
    ): CommandDto {
        val device = deviceRepository.findById(deviceId).orElse(null)
            ?: throw BizException(ParentErr.DEVICE_NOT_FOUND, "设备不存在", Audience.PARENT)

        val msgId = "cmd_" + UUID.randomUUID().toString().replace("-", "").take(24)
        val now = System.currentTimeMillis()
        val expiresAt = now + 5 * 60_000
        // 字符串化 payload：① 让终端 Map<String,String> 反序列化无歧义；② canonicalJson 逐字符一致
        val strPayload: Map<String, String>? = payload?.mapValues { (_, v) -> v?.toString() ?: "null" }
        val canonical = canonicalPayload(strPayload)
        val signature = hmac(device.hmacSecret, "$msgId|$type|$now|$canonical")

        commandRepository.save(
            Command(
                id = UUID.randomUUID().toString(), deviceId = deviceId, msgId = msgId,
                type = type, priority = priority,
                payloadJson = strPayload?.let { objectMapper.writeValueAsString(it) },
                signature = signature, status = "PENDING", expiresAt = expiresAt, createdAt = now
            )
        )

        mqttGateway.publishCommand(
            deviceId,
            CommandPacket(msgId, type, 1, now, expiresAt, priority, strPayload, signature)
        )
        return CommandDto(msgId, type, "PENDING", strPayload, now, null)
    }

    fun getCommand(msgId: String): CommandDto {
        val cmd = commandRepository.findByMsgId(msgId)
            ?: throw BizException(ParentErr.PARAM_ERROR, "指令不存在", Audience.PARENT)
        val payloadMap = cmd.payloadJson?.let {
            runCatching { objectMapper.readValue(it, Map::class.java) as Map<String, Any?> }.getOrNull()
        }
        return CommandDto(cmd.msgId, cmd.type, cmd.status, payloadMap, cmd.createdAt, cmd.executedAt)
    }

    /**
     * MQTT 降级轮询通道：返回设备待执行的 PENDING 指令包。
     *
     * 这里有三处必须保证的语义（历史上都曾导致“控制指令永远不生效”）：
     * 1. **不信任终端时钟**：since 由终端本机时钟生成。若终端时钟快于服务端，
     *    `createdAt >= since` 会把服务端刚入库的指令全部判为“旧数据”过滤掉，
     *    表现为终端一直轮询却永远取不到指令。因此先在服务端侧校验游标合法性，
     *    超出服务端当前时间（或非法）即退化为返回全部待执行指令。
     * 2. **过滤已过期指令**：过期指令不应再被下发执行，同时由 [ExpiredCommandSweeper] 收敛为终态。
     * 3. **按 FIFO 升序下发**：先到的指令先执行，并用批次上限避免异常堆积时单次拉取过多。
     */
    fun pendingForPolling(deviceId: String, since: Long): List<CommandPacket> {
        val now = System.currentTimeMillis()
        expiredCommandSweeper.sweep(deviceId, now)

        val cursor = since.takeIf { it in 1L..now } ?: 0L
        return commandRepository.findByDeviceIdOrderByCreatedAtAsc(deviceId)
            .filter { it.status == STATUS_PENDING && it.expiresAt > now && it.createdAt >= cursor }
            .take(MAX_POLLING_BATCH)
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
    }

    /**
     * 规范化 payload：与服务端约定一致——按键升序、值字符串化、固定格式 `{"k":"v",...}`。
     * 终端 [com.padguard.core.data.model.Command.canonicalJson] 用完全相同的排序与格式，
     * 必须逐字符一致，否则 HMAC 校验失败。
     */
    private fun canonicalPayload(payload: Map<String, String>?): String {
        if (payload.isNullOrEmpty()) return "{}"
        return payload.entries.sortedBy { it.key }
            .joinToString(",", "{", "}") { "\"${it.key}\":\"${it.value}\"" }
    }

    /**
     * HMAC-SHA256，Base64 编码（与终端 [com.padguard.core.common.Crypto.hmacSha256] 一致）。
     * 注意：早期实现误用 hex 编码，与终端 Base64 永远不相等，导致所有指令被拒。
     */
    private fun hmac(secret: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Base64.getEncoder().encodeToString(mac.doFinal(data.toByteArray(Charsets.UTF_8)))
    }

    private companion object {
        const val STATUS_PENDING = "PENDING"
        /** 单次轮询下发上限：既保证批次可控，也避免异常堆积时一次性拉取过多 */
        const val MAX_POLLING_BATCH = 50
    }
}
