package com.padguard.core.transport

import com.padguard.core.common.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 护眼强制休息时长上报磁盘队列（仿 [AckOutbox] / LocationOutbox）。
 *
 * ## 为什么需要（R-P2-9 / 离线红线）
 * 旧实现 [com.padguard.child.service.GuardService] 的 `eyeCareRestQueue` 是内存态 `ConcurrentLinkedQueue`：
 * 进程被杀后，那些"休息已结束但上报未成功"的 `(restId, 实际休息毫秒)` 全部丢失，
 * 服务端无法从每日总时长扣减该次休息 → 孩子被多计额度。这违反离线红线——
 * 断网/崩溃不应改变管控判定结果（休息扣减是三端时长一致的关键）。
 * 落盘后，进程重启首轮 flush 即可补报，闭合离线/崩溃回路。
 *
 * ## 设计（同 [AckOutbox]，共用 [JsonLinesFile]）
 * - JSON-Lines：每行一条 `(restId, restMs)`；整文件重写，代价可忽略（休息事件低频，天然有界）。
 * - 原子提交由 [JsonLinesFile] 统一负责，绝不预先 delete 旧文件，降级失败保留 tmp 供恢复。
 * - 上限保护：超过 [maxLines] 丢弃最旧（防御极端积压），正常不会触及。
 * - [drain] 先全量解析并剔除坏行再 `take(batch)`。本队列上限与批次同为 32，坏行虽不至于
 *   让 drain 恒空，却会**占满名额把有效的休息扣减挤出队列** → 孩子被多计额度（新增缺陷 J）。
 */
class EyeCareRestOutbox(
    file: File,
    private val json: Json,
    private val maxLines: Int = 300
) {
    private val lock = Any()
    private val lines = JsonLinesFile(file)

    /** 一次护眼休息上报条目。 */
    @Serializable
    data class Entry(val restId: String, val restMs: Long)

    /** 入队一条护眼休息上报（持久化到磁盘）。 */
    fun enqueue(restId: String, restMs: Long) {
        synchronized(lock) {
            // 读失败时绝不写盘：否则会用"只含这一条"的新文件覆盖掉盘上整队旧上报。
            val all = lines.readOrNull()?.toMutableList() ?: run {
                Logger.w(TAG) { "eye-care rest $restId dropped: outbox unreadable, kept existing data" }
                return
            }
            all.add(json.encodeToString(Entry.serializer(), Entry(restId, restMs)))
            if (all.size > maxLines) all.subList(0, all.size - maxLines).clear()
            if (!lines.write(all)) {
                Logger.w(TAG) { "eye-care rest $restId not committed, will be lost if process dies" }
            }
        }
    }

    /** 取出最多 [batch] 条用于发送（不删除，成功后再调 [remove]）。 */
    fun drain(batch: Int): List<Entry> = synchronized(lock) {
        val raw = lines.read()
        if (raw.isEmpty()) return emptyList()
        val parsed = raw.map { line ->
            line to runCatching {
                json.decodeFromString(Entry.serializer(), line)
            }.getOrNull()
        }
        dropCorrupt(parsed)
        parsed.mapNotNull { it.second }.take(batch)
    }

    /** 发送成功后（含服务端业务拒绝的"无需重试"项）删除指定条目（按 restId 匹配）。 */
    fun remove(entries: List<Entry>) {
        synchronized(lock) {
            if (entries.isEmpty()) return
            // 读失败必须与"队列为空"区分开：否则下面会把队列写成空文件，整队离线上报一次清空。
            val raw = lines.readOrNull() ?: run {
                Logger.w(TAG) { "remove skipped: outbox unreadable, kept on-disk data" }
                return
            }
            val keys = entries.map { it.restId }.toSet()
            val kept = raw.filter { line ->
                val id = runCatching {
                    json.decodeFromString(Entry.serializer(), line).restId
                }.getOrNull()
                // null = 坏行，无法发送也无需保留
                id != null && id !in keys
            }
            lines.write(kept)
        }
    }

    private fun dropCorrupt(parsed: List<Pair<String, Entry?>>) {
        val corrupt = parsed.count { it.second == null }
        if (corrupt == 0) return
        lines.write(parsed.mapNotNull { if (it.second != null) it.first else null })
        Logger.w(TAG) { "dropped $corrupt corrupt eye-care rest line(s)" }
    }

    private companion object {
        const val TAG = "EyeCareRestOutbox"
    }
}
