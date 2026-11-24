package com.padguard.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.repository.CommandRepository
import com.padguard.server.repository.ScreenshotRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * 过期指令回收：把已超时却仍未被终端取走执行的指令标记为 EXPIRED，
 * 并联动把关联的截图记录收敛到终态。
 *
 * W2 例外：符合补发条件（白名单 + 保留期内 + 次数未达上限）的指令**不收敛**，
 * 保持 PENDING 由 [CommandRedeliveryService] 在设备上线后重放；一旦超保留期或超次数，
 * 本清扫器仍会把它收敛成 EXPIRED，不会出现永久挂起的僵尸请求。
 *
 * 背景：指令带 5 分钟有效期，但若终端一直未取走（例如终端停留在 MQTT 模式、App 未运行、
 * 或 Bind 后从未轮询），指令会连同其关联记录（如 REMOTE 截图的 PENDING 记录）
 * 永久停留在中间态，家长端看到的是“永远转圈”的僵尸请求。这里让状态有确定终点。
 */
@Component
class ExpiredCommandSweeper(
    private val commandRepository: CommandRepository,
    private val screenshotRepository: ScreenshotRepository,
    private val objectMapper: ObjectMapper,
    /** W2：判定过期指令是否还要留在补发窗口内，而不是立刻收敛成终态 */
    private val redelivery: CommandRedeliveryService
) {
    private val log = LoggerFactory.getLogger(ExpiredCommandSweeper::class.java)

    /** deviceId -> 上一次真正执行扫描的时刻，用于高频路径节流 */
    private val lastSweepAt = ConcurrentHashMap<String, Long>()

    /**
     * 回收指定设备下所有已过期的待执行指令。
     * @param deviceId 设备 ID
     * @param now 基准时间（默认服务端当前时间；注入便于测试与批处理）
     */
    fun sweep(deviceId: String, now: Long = System.currentTimeMillis()) {
        // 走 SQL 条件查询而不是"捞出该设备全部指令再内存 filter"：
        // 指令表只增不删，而这里是**每次指令轮询都会触发**的路径，
        // 全量捞取会让单次轮询成本随历史指令数无限增长。
        val expired = commandRepository.findExpiredPending(deviceId, STATUS_PENDING, now)
        if (expired.isEmpty()) return

        // W2：过期不再等于终态。仍在补发保留期、次数未达上限、且在白名单内的指令
        // 必须保持 PENDING，等设备上线后由 CommandRedeliveryService 刷新窗口重放。
        // 这里若仍一律改写 status 再 saveAll，扫描器会把自己的补发队列清空 ——
        // 补发功能静默失效，且因为 30s 节流的存在，现象会比"完全没发"更难复现。
        val (waitingRedelivery, terminal) = expired.partition { redelivery.isEligible(it, now) }
        if (waitingRedelivery.isNotEmpty()) {
            log.debug(
                "保留待补发指令 {} 条不收敛终态: deviceId={}, msgIds={}",
                waitingRedelivery.size, deviceId, waitingRedelivery.map { it.msgId }
            )
        }
        if (terminal.isEmpty()) return

        // F01：状态只能由带 WHERE status='PENDING' 的条件 UPDATE 决定。
        // 旧写法改内存快照再 saveAll：两次查询之间若回执（MQTT / HTTP 双通道）已把指令
        // 置为 SUCCESS，旧快照会把它覆盖回 EXPIRED，家长端看到「执行成功又变过期」。
        // 逐条 CAS 而不是一条批量 UPDATE，是为了只对真正由本轮收敛的指令联动截图记录。
        val converged = terminal.filter { cmd ->
            commandRepository.markExpiredIfPending(cmd.id, STATUS_PENDING, STATUS_EXPIRED) > 0
        }
        converged.forEach { cmd -> convergeRelatedRecords(deviceId, cmd.type, cmd.payloadJson) }
        if (converged.isEmpty()) return
        log.info(
            "回收过期指令 {} 条: deviceId={}, msgIds={}",
            converged.size, deviceId, converged.map { it.msgId }
        )
    }

    /**
     * 节流版回收，供**高频轮询路径**调用。
     *
     * 背景：过期回收原本在每次 `commands` 轮询时都执行一遍。孩子端 3 秒一轮，
     * 等于每 3 秒就对该设备做一次"查 + 可能写"的维护动作。而指令有效期是 5 分钟，
     * 晚几十秒收敛成 EXPIRED 对业务没有任何影响 —— 家长端看到的只是
     * 「僵尸请求」晚一点消失，而这本来就是异常分支。
     *
     * 因此按设备做时间窗节流：窗口内直接跳过，把高频路径上的数据库压力降一个数量级。
     * 用 ConcurrentHashMap.compute 保证并发下同一时刻只有一个线程真正执行扫描。
     */
    fun sweepIfDue(deviceId: String, now: Long = System.currentTimeMillis()) {
        var due = false
        lastSweepAt.compute(deviceId) { _, last ->
            if (last == null || now - last >= SWEEP_INTERVAL_MS) {
                due = true
                now
            } else {
                last
            }
        }
        if (due) sweep(deviceId, now)
    }

    /** SCREENSHOT 指令会连带生成一条 PENDING 截图记录，需一并收敛，否则永久悬挂 */
    private fun convergeRelatedRecords(deviceId: String, type: String, payloadJson: String?) {
        if (type != TYPE_SCREENSHOT) return
        val shotId = readPayload(payloadJson)[KEY_SHOT_ID]
            ?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: return
        // 与上面的主指令同一个坑（F01）：不能「读实体 → 判 PENDING → 改内存快照 → save 整行」。
        // 查询之后孩子端可能刚好上传成功，把记录推到 READY 并填好 fileUrl；旧快照整行 merge
        // 会把状态打回 EXPIRED、把 fileUrl 抹成 null，图片从此再也点不开。
        // 交给带 WHERE status='PENDING' 的条件 UPDATE 决定，影响 0 行即说明已被别人收敛。
        screenshotRepository.markExpiredIfPending(deviceId, shotId, STATUS_PENDING, STATUS_EXPIRED)
    }

    private fun readPayload(payloadJson: String?): Map<*, *> =
        payloadJson?.let { runCatching { objectMapper.readValue(it, Map::class.java) }.getOrNull() }
            ?: emptyMap<String, Any?>()

    private companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_EXPIRED = "EXPIRED"
        const val TYPE_SCREENSHOT = "SCREENSHOT"
        const val KEY_SHOT_ID = "shotId"

        /**
         * 同一设备的过期回收最小间隔。
         * 指令有效期 5 分钟，30 秒扫一次有充足余量（最坏情况下僵尸请求晚 30 秒收敛），
         * 却把高频轮询路径上的维护成本降到 1/10。
         */
        const val SWEEP_INTERVAL_MS = 30_000L
    }
}
