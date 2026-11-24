package com.padguard.core.transport

import com.padguard.core.common.Logger
import java.io.File

/**
 * JSON-Lines 离线队列的**原子落盘底座**（`AckOutbox` / `EyeCareRestOutbox` / `LocationOutbox` 共用）。
 *
 * ## 为什么必须收口成一个类（新增缺陷 J）
 * 三处队列原先各写一套「读行 + 临时文件 + rename」，实现却互相分叉：
 *
 * - `LocationOutbox` 在 rename 前先 `file.delete()`。Android/Linux 的 POSIX `rename(2)`
 *   本就能**原子覆盖已存在的目标**，这一次 delete 纯属多余，却把「原子替换」拆成了
 *   「删除旧文件 + 创建新文件」两步。进程若在两步之间被系统回收（后台进程随时可能被杀），
 *   `file` 不存在、只留下 `*.tmp`，而读路径只看 `file` → 离线期间攒下的整队数据一次性全丢。
 * - `AckOutbox` / `EyeCareRestOutbox` 不 delete，方向是对的；但 rename 失败的降级分支是
 *   **非原子整文件覆写**，中途被杀会留下半行脏数据，且降级失败后 `*.tmp` 里那份
 *   完整的最新快照无人认领。
 *
 * 本类把提交语义统一为下面这一条，任何队列都不再自己碰文件：
 *
 * 1. 先把完整内容写入 `*.tmp` —— 此时 `file` **仍是上一次的完整数据**，一步都没动过；
 * 2. 直接 `renameTo(file)` 原子覆盖（**绝不预先 delete**）：POSIX 上成功即完成原子替换；
 * 3. 仅在 rename 失败时（个别 ROM，或 Windows 语义下目标已存在）退化为非原子覆写；
 * 4. 提交成功清理 tmp；**提交失败保留 tmp**，供下一次 [read] 恢复。
 *
 * 因此最坏情况回退到注释一直承诺的「最多丢最后一次写」，而不是「整队丢失」。
 *
 * ## tmp 残留恢复（提交完成标记）
 * 仅凭 `*.tmp` 存在**不能**断定内容完整：Android 后台进程随时可能被 SIGKILL，
 * `tmp.writeText` 写一半被杀、或磁盘满时部分写入后抛异常，留下的都是半行 tmp；
 * 若无条件用它覆盖完好的 `file`，整队离线数据会被一次清空（新增缺陷 K）。
 *
 * 因此 tmp 写入**完整返回后**才创建兄弟标记 `*.tmp.ok`：
 * - `tmp + ok` → 内容完整、只是提交未完成，用它恢复 `file`（同样直接 rename，不预先 delete）；
 * - `tmp` 无 `ok` → 半行/损坏，丢弃 tmp，保住上一次的完整 `file`（最坏只丢本次入队）；
 * - `ok` 无 `tmp` → 提交已完成但标记未清的孤儿，清掉即可。
 *
 * 正常路径提交成功即同时删 tmp 与标记，不会产生误恢复。
 *
 * ## 如实记录的边界
 * - `write` 返回 [Boolean]：磁盘满 / IO 彻底失败时仍会丢**最后一次入队**，调用方无从凭空
 *   恢复内存态；这里只保证失败可见（warn + 返回值），不虚构兜底。
 * - Android 上的 POSIX 原子 rename 路径在 JVM 单测里测不到（桌面 JVM 的 `renameTo` 到
 *   已存在目标会失败，恰好只覆盖降级与恢复路径），端到端仍需真机。
 */
class JsonLinesFile(private val file: File) {

    /** 与 `file` 同目录的临时文件；`parentFile` 为 null 时退化为相对路径。 */
    private val tmp: File = file.parentFile?.let { File(it, file.name + ".tmp") }
        ?: File(file.name + ".tmp")

    /**
     * tmp 的「内容已完整写入」标记。只有它存在时 [recoverUncommitted] 才会用 tmp 恢复，
     * 否则丢弃可能是半行的 tmp（见类注释「提交完成标记」）。
     */
    private val tmpOk: File = File(tmp.path + ".ok")

    init {
        runCatching {
            file.parentFile?.let { if (!it.exists()) it.mkdirs() }
        }.onFailure { Logger.w(TAG, it) { "create outbox dir failed" } }
    }

    /**
     * 读取全部非空行，读失败返回 `null`（**不是空列表**）。
     *
     * 这个区分很关键：磁盘瞬时异常时若把失败折叠成"队列已空"，调用方的 `remove` 会
     * 顺势写一份空文件，把整队离线数据一次清空；`enqueue` 也会只写新行覆盖掉旧队列。
     * 有了 null，调用方就能**跳过本次写盘**，保住盘上原数据，等下轮重试。
     */
    fun readOrNull(): List<String>? {
        recoverUncommitted()
        return runCatching {
            if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()
        }.onFailure { Logger.w(TAG, it) { "read failed: ${file.name}" } }
            .getOrNull()
    }

    /** [readOrNull] 的便利重载，仅供"读失败也无需写盘"的纯读取路径（如 drain）使用。 */
    fun read(): List<String> = readOrNull() ?: emptyList()

    /**
     * 原子写入整份快照，返回是否成功提交。
     *
     * 调用方需在自己的锁内串行调用；本类不持锁（三处队列各有自己的 `lock`）。
     */
    fun write(lines: List<String>): Boolean {
        val payload = lines.joinToString("\n")
        return runCatching {
            // 清掉上轮可能残留的标记，避免本次写到一半被杀时误用旧标记恢复半行 tmp。
            tmpOk.delete()
            // 第一步只动 tmp：file 保持上一次的完整内容，进程此刻被杀也只是丢本次写。
            tmp.writeText(payload)
            // tmp 写完整了才打标记；标记未创建前的 tmp 一律视为半行，恢复路径会丢弃它。
            tmpOk.createNewFile()
            val committed = if (tmp.renameTo(file)) {
                true   // POSIX：原子覆盖已存在的目标，tmp 此刻已被系统移走
            } else {
                // 降级：目标已存在且 rename 失败的 ROM / 非 POSIX 语义。非原子，仅此路径有半行风险，
                // 由带标记的 tmp 恢复兜底（失败时下面的 delete 不会执行）。
                file.writeText(payload)
                true
            }
            if (committed) {
                tmp.delete()
                tmpOk.delete()
            }
            committed
        }.onFailure { Logger.w(TAG, it) { "atomic write failed: ${file.name}, tmp kept for recovery" } }
            .getOrDefault(false)
    }

    /** 把"写完整但没提交成功"的 tmp 快照恢复为正式文件；半行 tmp 直接丢弃。 */
    private fun recoverUncommitted() {
        if (!tmp.exists()) {
            // 提交已完成但标记未清的孤儿：tmp 已不在，标记无意义。
            tmpOk.delete()
            return
        }
        if (!tmpOk.exists()) {
            // 无完成标记 = 写一半被杀 / 磁盘满截断的半行，绝不能覆盖完好的 file。
            runCatching { tmp.delete() }
                .onFailure { Logger.w(TAG, it) { "drop incomplete tmp failed: ${file.name}" } }
            Logger.w(TAG) { "discarded incomplete tmp snapshot: ${file.name}" }
            return
        }
        runCatching {
            if (tmp.renameTo(file)) {
                tmpOk.delete()
                Logger.i(TAG) { "recovered uncommitted outbox snapshot: ${file.name}" }
            } else {
                // 目标已存在且 rename 失败：直接覆写（tmp 内容完整，覆盖是安全的）。
                file.writeText(tmp.readText())
                tmp.delete()
                tmpOk.delete()
                Logger.i(TAG) { "recovered uncommitted outbox snapshot by overwrite: ${file.name}" }
            }
        }.onFailure {
            // 恢复失败就保留 tmp 与标记，下次 read 再试；绝不删它们，否则唯一完整副本就没了。
            Logger.w(TAG, it) { "tmp recovery failed, kept for next read: ${file.name}" }
        }
    }

    private companion object {
        const val TAG = "JsonLinesFile"
    }
}
