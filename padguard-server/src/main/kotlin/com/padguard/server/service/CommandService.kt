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
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
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
        // MQTT 不可用（家庭/本地部署无 broker）时唤醒正在长轮询的孩子端，
        // 让它不必等下一个轮询周期就能取走这条指令。
        notifyWaiters(deviceId)
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

        // 游标合法性校验（含**时钟偏移容错**）：
        // 终端时钟略快于服务端时，since 会落在「未来」——此前直接判非法归零，
        // 等于每次轮询都下发全部 PENDING 指令，叠加回执延迟就会重复执行
        // （实测录屏每 3 秒被丢弃重启一次）。因此对轻微超前做 clamp 而不是归零。
        //
        // clamp 目标不能是服务端当前时间 now 本身：终端"上一次真正取走指令"的时刻
        // 其实早于 now（两轮轮询之间存在数秒的重新发起间隙），
        // 若钳到 now，`createdAt >= now` 会把这个间隙里刚入库的指令整批判为旧数据丢掉 ——
        // 表现是家长点了「解锁」，指令已入库却迟迟不下发（解锁有延迟）。
        // 这里回退一个 [CLOCK_SKEW_GRACE_MS] 的宽容窗口：
        // 只重放"最近几秒内仍是 PENDING"的指令，已回执的指令早已不是 PENDING，
        // 因此不会退化成重复执行，但能兜住终端时钟偏快导致的漏发。
        val cursor = when {
            since in 1L..now -> since
            since > now -> (now - CLOCK_SKEW_GRACE_MS).coerceAtLeast(0L)
            else -> 0L
        }
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

    // ==================== 长轮询（MQTT 缺失时的低延迟通道） ====================
    //
    // 无 broker 环境下孩子端退化为 HTTPS 定时轮询，而**轮询周期就是指令延迟的下限**：
    // 60s 一轮意味着家长点「锁屏」最坏要等一分钟才生效，实时看屏同样迟迟不出首帧。
    // 长轮询把请求挂在服务端，指令一入库立刻唤醒，延迟从「一个轮询周期」降到「一次 RTT」，
    // 且空闲时只在超时时刻才往返一次，请求数比高频轮询更少。
    //
    // 这里只存回调、不引入 Spring Web 类型，保持 Service 层与传输方式解耦。

    /** deviceId -> 挂起中的长轮询回调 */
    private val waiters = ConcurrentHashMap<String, ConcurrentLinkedQueue<(List<CommandPacket>) -> Unit>>()

    /**
     * 注册一个长轮询等待者。
     *
     * @return true 表示已挂起（调用方应把 DeferredResult 交给 Spring 异步完成）；
     *         false 表示此刻已有待办指令，[onReady] 已同步触发，请求可立即结束
     */
    fun registerWaiter(deviceId: String, since: Long, onReady: (List<CommandPacket>) -> Unit): Boolean {
        val pending = pendingForPolling(deviceId, since)
        if (pending.isNotEmpty()) {
            onReady(pending)
            return false
        }
        waiters.computeIfAbsent(deviceId) { ConcurrentLinkedQueue() }.add(onReady)
        return true
    }

    /** 超时或连接断开时摘除回调，避免队列无限增长 */
    fun removeWaiter(deviceId: String, onReady: (List<CommandPacket>) -> Unit) {
        waiters[deviceId]?.remove(onReady)
    }

    /** 唤醒该设备所有挂起的轮询请求。重复下发由终端 CommandGate 按 msgId 去重，安全。 */
    private fun notifyWaiters(deviceId: String) {
        val queue = waiters[deviceId] ?: return
        while (true) {
            val onReady = queue.poll() ?: break
            runCatching { onReady(pendingForPolling(deviceId, 0)) }
                .onFailure { log.warn("notify long-poll waiter failed: {}", it.message) }
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

    companion object {
        const val STATUS_PENDING = "PENDING"
        /** 单次轮询下发上限：既保证批次可控，也避免异常堆积时一次性拉取过多 */
        const val MAX_POLLING_BATCH = 50

        /**
         * 终端时钟超前时的游标宽容窗口（毫秒）。
         *
         * 终端时钟快于服务端时 since 落在未来，需回退一小段再取指令，
         * 否则两轮轮询间隙里刚入库的指令会被整批漏发（家长点了解锁却迟迟不生效）。
         * 取值只需覆盖"轮询间隙 + 轻微时钟偏差"，不宜过大：
         * 窗口内只重放仍为 PENDING 的指令，已回执的不会被重复下发。
         */
        const val CLOCK_SKEW_GRACE_MS = 15_000L

        /**
         * 长轮询挂起上限。
         *
         * 必须**小于终端 HTTP 读超时（30s）**：一旦服务端挂起时间超过终端读超时，
         * 终端会先掐断连接，家长端表现为指令迟迟不生效，而服务端日志只剩一条断开记录，
         * 极难定位。取 20s 留 10s 余量覆盖网络排队与 GC 停顿。
         */
        const val MAX_WAIT_MS = 20_000L

        private val log = LoggerFactory.getLogger(CommandService::class.java)
    }
}
