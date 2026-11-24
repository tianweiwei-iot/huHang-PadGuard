package com.padguard.core.transport

import com.padguard.core.common.Logger
import com.padguard.core.data.model.CommandAck
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 指令回执磁盘队列（仿 [com.padguard.child.monitor.LocationCollector] 的 LocationOutbox）。
 *
 * ## 为什么需要（R-P1-5 / 离线红线）
 * 旧实现 [RealRemoteDataSource.ackRetryQueue] 是 `ConcurrentLinkedQueue` 内存态：进程被杀后，
 * 那些"已执行但回执未上报成功"的指令回执全部丢失，服务端 `commands.status` 永久停在
 * PENDING/"执行中"，家长端看不到设备其实已执行 —— 指令闭环断裂。
 * 本队列把回执落盘，进程重启后首轮 flush 即可补发，闭合离线/崩溃回路。
 *
 * ## 设计（与 LocationOutbox / EyeCareRestOutbox 共用 [JsonLinesFile]）
 * - JSON-Lines：每行一个回执；整文件重写，代价可忽略（指令量受服务端下发频率约束，天然有界）。
 * - 原子提交由 [JsonLinesFile] 统一负责：tmp 写完整 → rename 覆盖；**绝不预先 delete 旧文件**，
 *   降级失败保留 tmp 供下次 read 恢复（见新增缺陷 J）。
 * - 上限保护：超过 [maxLines] 丢弃最旧（防御极端积压），正常不会触及。
 * - [drain] 对无法解析的行**就地剔除**：这类行永远发不出去，若留在队首会占满 batch 名额，
 *   使后续正常回执被永久饿死（见新增缺陷 J）。
 * - 所有读写在 [lock] 内串行，避免与 flush 的 drain/remove 交错损坏文件。
 */
class AckOutbox(
    file: File,
    private val json: Json,
    private val maxLines: Int = 500
) {
    private val lock = Any()
    private val lines = JsonLinesFile(file)

    /** 入队一条回执（持久化到磁盘）。 */
    fun enqueue(ack: CommandAck) {
        synchronized(lock) {
            // 读失败时绝不写盘：否则会用"只含这一条"的新文件覆盖掉盘上整队旧回执。
            val all = lines.readOrNull()?.toMutableList() ?: run {
                Logger.w(TAG) { "ack ${ack.msgId} dropped: outbox unreadable, kept existing data" }
                return
            }
            all.add(json.encodeToString(CommandAck.serializer(), ack))
            if (all.size > maxLines) all.subList(0, all.size - maxLines).clear()
            if (!lines.write(all)) {
                Logger.w(TAG) { "ack ${ack.msgId} not committed, will be lost if process dies" }
            }
        }
    }

    /**
     * 取出最多 [batch] 条回执用于发送（不删除，成功后再调 [remove]）。
     *
     * 先**全量解析并剔除坏行**，再 `take(batch)`：旧实现是 `lines.take(batch).mapNotNull{...}`，
     * 队首只要堆够 batch 条坏行（schema 演进缺字段 / 降级覆写留下的半行 / 文件损坏），
     * 每轮 drain 都返回空 → `flushAckRetryQueue` 直接 return → 补发通道永久停摆。
     */
    fun drain(batch: Int): List<CommandAck> = synchronized(lock) {
        val raw = lines.read()
        if (raw.isEmpty()) return emptyList()
        val parsed = raw.map { line ->
            line to runCatching {
                json.decodeFromString(CommandAck.serializer(), line)
            }.getOrNull()
        }
        dropCorrupt(parsed)
        parsed.mapNotNull { it.second }.take(batch)
    }

    /** 发送成功后删除指定回执（按 msgId 匹配）；顺带清掉无法解析的坏行。 */
    fun remove(acks: List<CommandAck>) {
        synchronized(lock) {
            if (acks.isEmpty()) return
            // 读失败必须与"队列为空"区分开：否则下面会把队列写成空文件，整队离线回执一次清空。
            val raw = lines.readOrNull() ?: run {
                Logger.w(TAG) { "remove skipped: outbox unreadable, kept on-disk data" }
                return
            }
            val ids = acks.map { it.msgId }.toSet()
            val kept = raw.filter { line ->
                val msgId = runCatching {
                    json.decodeFromString(CommandAck.serializer(), line).msgId
                }.getOrNull()
                // null = 坏行，无法发送也无需保留（否则会占满上限挤掉新回执）
                msgId != null && msgId !in ids
            }
            lines.write(kept)
        }
    }

    /** 把无法解析的行从磁盘剔除；写回失败也不影响本轮返回（下一轮会再试）。 */
    private fun dropCorrupt(parsed: List<Pair<String, CommandAck?>>) {
        val corrupt = parsed.count { it.second == null }
        if (corrupt == 0) return
        lines.write(parsed.mapNotNull { if (it.second != null) it.first else null })
        Logger.w(TAG) { "dropped $corrupt corrupt ack line(s)" }
    }

    private companion object {
        const val TAG = "AckOutbox"
    }
}
