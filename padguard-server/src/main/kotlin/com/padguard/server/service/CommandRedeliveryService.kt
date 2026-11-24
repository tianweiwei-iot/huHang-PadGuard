package com.padguard.server.service

import com.padguard.server.domain.Command
import com.padguard.server.repository.CommandRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service

/**
 * 一条抢到补发权的指令。
 *
 * [refreshedExpiresAt] 是本次刷新后的新有效期，必须用它构造下发包：
 * `tryRefreshForRedelivery` 是 `@Modifying(clearAutomatically = true)` 的批量 UPDATE，
 * 会清空一级缓存，内存里的 [Command] 快照仍带着**旧的、已过期的** expiresAt。
 * 若直接拿 `cmd.expiresAt` 下发，终端 CommandGate 第一关就会判 expired 拒收，
 * 表现是「补发了却依然不生效」，且服务端日志一切正常，极难排查。
 */
data class RedeliveredCommand(val command: Command, val refreshedExpiresAt: Long)

/**
 * W2：离线指令补发（设备上线重放）。
 *
 * ## 要解决的问题
 * 指令 `expires_at = createdAt + 5min`。设备长期离线时，家长下发的管控指令
 * 既没被 MQTT 送达（QoS1 不为离线设备留存、无 retained），也没被 HTTP 长轮询取走，
 * 5 分钟后被 [ExpiredCommandSweeper] 收敛成 EXPIRED 终态。设备上线后没有任何
 * 「补发离线期间指令」的入口 —— 这批管控变更永久丢失，且家长端只看到"已过期"。
 *
 * ## 为什么把补发点放在轮询，而不是新增「设备上线」事件钩子
 * 孩子端上线后必然重新发起长轮询/心跳，[com.padguard.server.service.CommandService.pendingForPolling]
 * 是**所有通道的公共必经点**（HTTP 长轮询、notifyWaiters 唤醒、MQTT 上线后补拉都走它）。
 * 挂在这里不需要动在线状态机，也不会与 MQTT connectComplete（那是**服务端连 broker**
 * 的事件，不是设备上线）混淆，改动面最小且天然覆盖双通道。
 *
 * ## 三条硬约束
 * 1. **白名单制（fail-closed）**：只补发「状态型、可重复生效」的指令。一次性解锁、
 *    采集类、霸屏消息一律不补发 —— 设备离线 8 小时后补发一条"临时解锁 30 分钟"
 *    等于白送时长；补发一条"拍照"属于超出当时情境的未成年人信息采集。
 *    新增指令类型未显式登记 = 不补发。
 * 2. **契约零漂移**：补发只刷新 `expiresAt` / `deliveryCount` / `lastDeliveryAt`，
 *    **绝不改 msgId、type、timestamp(createdAt)、payload、signature**。
 *    终端 `CommandGate` 验签用 `msgId|type|timestamp|canonicalPayload`（不含 expiresAt），
 *    且 `tryClaim(msgId)` 按 msgId 幂等，因此重放既能通过验签，重复投递也只会回 DUPLICATED。
 * 3. **并发只抢一次**：用数据库条件 UPDATE 抢占补发权（见 `tryRefreshForRedelivery`），
 *    跨实例成立，不引入又一把进程内锁；MQTT 与 HTTP 双通道同时唤醒也只算一次补发。
 *
 * ## 业务口径尚未拍板（PRD ⟦TBD-5⟧）
 * 保留期、最大次数、白名单范围都是**保守默认假设**，全部做成配置项
 * （`padguard.command.*`），产品拍板后改配置即可，不需要改代码。
 */
@Service
class CommandRedeliveryService(
    private val commandRepository: CommandRepository,
    @Value("\${padguard.command.redelivery-enabled:true}") private val enabled: Boolean = true,
    @Value("\${padguard.command.ttl-ms:300000}") private val ttlMs: Long = 300_000L,
    @Value("\${padguard.command.redelivery-retention-ms:86400000}") private val retentionMs: Long = 86_400_000L,
    @Value("\${padguard.command.redelivery-max-attempts:3}") private val maxAttempts: Int = 3,
    @Value("\${padguard.command.redelivery-max-batch:10}") private val maxBatch: Int = 10,
    @Value("\${padguard.command.redelivery-types:}") private val typesCsv: String = ""
) {
    /** 可补发白名单。配置为空时回落到内置默认值，保证「不配也不会全量放行」。 */
    val redeliveryTypes: Set<String> by lazy {
        val csv = typesCsv.ifBlank { DEFAULT_REDELIVERY_TYPES }
        csv.split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
    }

    fun ttlMs(): Long = ttlMs

    /**
     * 指令落库时计算补发截止时刻。返回 null = 该指令永不参与补发。
     *
     * 存量数据（迁移加列时未回填）同样是 null，因此功能上线不会重放历史积压指令。
     */
    fun deadlineFor(type: String, createdAt: Long): Long? =
        if (enabled && retentionMs > 0 && type.uppercase() in redeliveryTypes) createdAt + retentionMs
        else null

    /** 过期清扫器判定：这条过期指令是否还要留着等补发（而非立刻收敛成 EXPIRED 终态）。 */
    fun isEligible(cmd: Command, now: Long): Boolean {
        if (!enabled || maxAttempts <= 0) return false
        // 清扫器的查询已限定 PENDING，这里再兜一层：已终态的指令绝不能被"复活"回补发窗口。
        if (cmd.status != STATUS_PENDING) return false
        val deadline = cmd.redeliveryDeadlineAt ?: return false
        return now < deadline && cmd.deliveryCount < maxAttempts && cmd.type.uppercase() in redeliveryTypes
    }

    /**
     * 取出并刷新「已过有效期、仍在补发保留期内」的指令，供本次轮询一并下发。
     *
     * 只有拿到原子 UPDATE 返回 1 的指令才返回：并发下另一个轮询请求已经抢先刷新时，
     * 本请求返回空，但那条指令此刻已带着新 expiresAt 变成正常 PENDING，
     * 下一轮（乃至本次 [CommandService.pendingForPolling] 的常规查询）自会取到它，不会丢。
     *
     * @return 按 createdAt 升序（FIFO），最多 maxBatch 条
     */
    fun collectRedeliverable(deviceId: String, now: Long): List<RedeliveredCommand> {
        if (!enabled || maxBatch <= 0 || maxAttempts <= 0) return emptyList()
        val types = redeliveryTypes
        // 保护：JPQL `in :types` 遇到空集合会在部分方言生成非法 SQL，必须由调用方挡住。
        if (types.isEmpty()) return emptyList()

        val candidates = commandRepository.findRedeliverablePending(
            deviceId = deviceId,
            status = STATUS_PENDING,
            now = now,
            maxAttempts = maxAttempts,
            types = types,
            pageable = PageRequest.of(0, maxBatch)
        )
        if (candidates.isEmpty()) return emptyList()

        val newExpiresAt = now + ttlMs
        val won = candidates.mapNotNull { cmd ->
            val updated = commandRepository.tryRefreshForRedelivery(
                id = cmd.id, status = STATUS_PENDING, now = now, newExpiresAt = newExpiresAt
            )
            if (updated == 1) RedeliveredCommand(cmd, newExpiresAt) else null
        }
        if (won.isNotEmpty()) {
            log.info(
                "command redelivery: device={} count={} msgIds={}",
                deviceId, won.size, won.map { it.command.msgId }
            )
        }
        return won
    }

    private companion object {
        const val STATUS_PENDING = "PENDING"

        /**
         * 内置默认白名单（与 application.yml 保持一致）。
         * 只登记状态型/可重复生效指令；LOCK_SCREEN、UNLOCK、采集类、消息类刻意不在内。
         */
        const val DEFAULT_REDELIVERY_TYPES =
            "REFRESH_POLICY,UPDATE_CONFIG,SET_APP_SUSPENDED,SET_APP_HIDDEN," +
                "SET_RUNTIME_PERMISSION,SET_FEATURE_POLICY,SET_WATERMARK,SET_MANAGEMENT_ACTIVE"

        private val log = LoggerFactory.getLogger(CommandRedeliveryService::class.java)
    }
}
