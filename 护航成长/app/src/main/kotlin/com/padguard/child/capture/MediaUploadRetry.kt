package com.padguard.child.capture

import android.content.Context
import com.padguard.core.common.Logger
import com.padguard.core.transport.JsonLinesFile
import com.padguard.core.transport.RemoteDataSource
import com.padguard.core.transport.http.ApiResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 媒体上传重试队列。
 *
 * ## 修的问题
 * 早期实现在四条上传路径上「失败即删」：截屏（[com.padguard.child.capture.ScreenCaptureService] doCapture）、
 * 录屏（finalizeRecording）、录音（stopAudioRecord）、拍照（[com.padguard.child.capture.PhotoCapture]）——
 * 上传失败后只记一行日志就把本地文件删掉。于是弱网/服务端重启窗口里，
 * 家长点了「截屏 / 录屏 / 录音 / 拍照」，孩子端明明成功采集了，文件却被悄悄丢弃，
 * 家长端永远等不到结果，且没有任何痕迹可查。
 *
 * ## 做法
 * 上传失败不再删除：文件移入 `cacheDir/media_retry/`，元数据追加进索引文件，
 * 之后在采集服务启动 / 每次录制收尾 / 拍照完成时尝试重传（[drain]），成功才删。
 * 与指令回执重发（RealRemoteDataSource.ackRetryQueue）同一思路：
 * **失败要留下可恢复的痕迹，而不是假装没发生过**。
 *
 * ## 落盘底座（新增缺陷 L-02）
 * 这是全工程第四处离线队列，此前却自己写了一套「readLines + writeText/appendText」：
 * - 读异常被 `.getOrElse { emptyList() }` 折叠成"队列已空"，[drain] 结尾的重读会据此
 *   `writeIndex(emptyList())` → 删掉索引文件，**整队未上传的采集媒体从此永不补传**；
 * - `writeText` 非原子整文件覆写，中途被系统回收会留下半行脏数据；
 * - 队首坏行会让 `mapNotNull` 后的列表缺项但不被剔除，重写时仍能卡住队列。
 * 现统一走 [JsonLinesFile]：读失败返回 null（≠ 空队列）、tmp 写完整后直接 rename 覆盖、
 * 失败保留 tmp 供下次 read 恢复；坏行在解码阶段剔除，下次写盘即自愈。
 *
 * ## 边界
 * - 队列有上限（[MAX_FILES] 条）与存活期（[MAX_AGE_MS]），超限丢最旧 ——
 *   缓存不能无限涨，否则照样把磁盘写满。
 * - 重传仍失败就留在队列里等下一轮，不抛异常打扰调用方。
 */
@Singleton
class MediaUploadRetry @Inject constructor(
    @ApplicationContext private val context: Context,
    private val remote: RemoteDataSource
) {

    /** 待重传的上传类型。截图与媒体任务走不同端点，必须分开记。 */
    sealed interface Kind {
        val id: String

        data class Screenshot(
            override val id: String,
            val capturedAt: Long,
            val triggerType: String
        ) : Kind

        data class Media(
            override val id: String,
            val durationSec: Int,
            val mime: String
        ) : Kind
    }

    private data class Entry(
        val kind: Kind,
        val fileName: String,
        val enqueuedAt: Long
    )

    private val dir: File get() = File(context.cacheDir, DIR_NAME)

    /** 与 AckOutbox / EyeCareRestOutbox / LocationOutbox 共用的原子落盘底座。 */
    private val index = JsonLinesFile(File(context.cacheDir, "$DIR_NAME/$INDEX_NAME"))

    /**
     * 索引文件的读写互斥锁。
     *
     * ## 为什么必须有（历史缺陷）
     * 索引是纯文本文件，读写都是"整份快照重写"。同一时刻可能有三条采集链路在动它：
     * 截屏服务在 [enqueue]、录音收尾在 [enqueue]、[drain] 正在重传。
     * 没有锁时 [drain] 开头的快照会被结尾的写回抹掉 drain 期间新入队的条目；
     * [trim] 与 [enqueue] 交错也会丢新行。两条都以"静默丢件"告终。
     */
    private val indexLock = Any()

    /**
     * 上传失败时调用：把已采集好的文件移交重试队列（源文件被移动，不复制留存两份）。
     *
     * 索引读不出来时**跳过本次写盘并保留源文件**：绝不能把读失败当成空队列后写一份
     * 只含新条目的文件，那样盘上原有的整队数据会一次清空。
     *
     * @param source    已采集的媒体文件
     * @param kind      上传类型（截图 / 媒体任务）
     */
    fun enqueue(source: File, kind: Kind) {
        runCatching {
            if (!source.exists() || source.length() <= 0L) {
                Logger.w(TAG) { "retry enqueue skipped, empty file: ${source.name}" }
                return
            }
            dir.mkdirs()
            val dest = File(dir, "${System.currentTimeMillis()}_${kind.id}${extOf(source.name)}")
            var committed = false
            synchronized(indexLock) {
                val current = readIndex()
                if (current != null) {
                    // copyTo 中途失败（磁盘满最易触发，录屏文件数十 MB）：半截 dest **不在索引**，
                    // drain/trim/MAX_AGE 都只处理索引内文件 → 永久泄漏。必须当场删除再抛，
                    // 让外层 runCatching 记日志且源文件保持原样（重试语义不破坏）。
                    try {
                        source.copyTo(dest, overwrite = true)
                    } catch (e: Exception) {
                        runCatching { dest.delete() }
                        throw e
                    }
                    val entry = Entry(kind, dest.name, System.currentTimeMillis())
                    committed = writeIndex(current + entry)
                    if (committed) {
                        source.delete()
                        trim()
                    } else {
                        runCatching { dest.delete() }
                    }
                }
            }
            if (!committed) {
                Logger.w(TAG) { "retry enqueue kept source untouched: index unreadable or write rejected" }
                return
            }
            Logger.w(TAG) { "media upload failed, queued for retry: ${dest.name} (queue=${count()})" }
        }.onFailure { Logger.w(TAG) { "retry enqueue failed: ${it.message}" } }
    }

    /**
     * 尝试重传队列里的全部文件。成功即删；失败保留等下一轮。
     * 任何单个文件的异常都不影响其余条目。
     *
     * 写回索引前会**重新读一次**并剔除本次已处理项，而不是直接用开头那个快照 ——
     * 否则 drain 期间新入队的条目会被这次写回抹掉（见 [indexLock] 的说明）。
     * 重读失败时**整段跳过写回**，宁可下轮重复上传，也不能清空盘上队列。
     */
    suspend fun drain() {
        val entries = readIndex() ?: return
        if (entries.isEmpty()) return
        val now = System.currentTimeMillis()
        // 本次"可以从索引里移除"的文件名：上传成功、已过期、或文件已被系统清理
        val settled = HashSet<String>(entries.size)
        var sent = 0
        for (e in entries) {
            val f = File(dir, e.fileName)
            if (!f.exists()) {
                settled += e.fileName // 文件已被系统清理，条目作废
                continue
            }
            if (now - e.enqueuedAt > MAX_AGE_MS) {
                runCatching { f.delete() }
                settled += e.fileName
                continue
            }
            val ok = runCatching {
                when (val k = e.kind) {
                    // File 版重载：流式读盘上传，不把整张图 readBytes() 进堆（B22 上传全程流式）。
                    is Kind.Screenshot -> remote.uploadScreenshot(k.id, k.capturedAt, k.triggerType, f)
                    is Kind.Media -> remote.uploadMedia(k.id, k.durationSec, k.mime, f)
                }
            }.let { outcome ->
                val r = outcome.getOrNull()
                if (r == null) false else r is ApiResult.Success
            }
            if (ok) {
                runCatching { f.delete() }
                settled += e.fileName
                sent++
                Logger.i(TAG) { "retry upload succeeded: ${e.fileName}" }
            }
        }
        val remainingCount = synchronized(indexLock) {
            val latest = readIndex()
            if (latest == null) {
                null
            } else {
                val kept = latest.filterNot { it.fileName in settled }
                writeIndex(kept)
                kept.size
            }
        }
        if (remainingCount == null) {
            Logger.w(TAG) { "retry drain: index unreadable, skipped rewrite to keep queued files" }
            return
        }
        if (sent > 0 || remainingCount != entries.size) {
            Logger.i(TAG) { "retry drain done: sent=$sent remaining=$remainingCount" }
        }
    }

    /**
     * 解绑即清空整个重试队列（文件 + 索引 + 目录）。
     *
     * 队列里躺着的是尚未上传成功的采集媒体（截屏/录屏/录音），
     * 全部属于"旧家庭在本设备上的管控痕迹"。Room 与 DataStore 清了，
     * 这些文件不清，平板转手后旧家庭的录音录像仍残留在 cacheDir，
     * 隐私承诺不可信。失败不抛异常——解绑主流程不能被打断。
     */
    fun clearAll() {
        runCatching {
            if (!dir.exists()) return
            dir.listFiles()?.forEach { f -> runCatching { f.delete() } }
            dir.delete()
            Logger.w(TAG) { "media retry queue cleared on unbind" }
        }.onFailure { Logger.w(TAG) { "retry queue clear failed: ${it.message}" } }
    }

    /** 当前队列长度（供日志/调试）；读不出来返回 0，绝不因此触发任何写盘。 */
    fun count(): Int = readIndex()?.size ?: 0

    // ==================== 索引序列化（管道分隔，免引入序列化插件） ====================

    private fun encode(e: Entry): String {
        val kind = when (val k = e.kind) {
            is Kind.Screenshot -> "SHOT|${k.id}|${k.capturedAt}|${k.triggerType}"
            is Kind.Media -> "MEDIA|${k.id}|${k.durationSec}|${k.mime}"
        }
        return "$kind|${e.fileName}|${e.enqueuedAt}"
    }

    private fun decode(line: String): Entry? {
        val p = line.split("|")
        if (p.size < 6) return null
        val kind: Kind = when (p[0]) {
            "SHOT" -> Kind.Screenshot(p[1], p[2].toLongOrNull() ?: 0L, p[3])
            "MEDIA" -> Kind.Media(p[1], p[2].toIntOrNull() ?: 0, p[3])
            else -> return null
        }
        return Entry(kind, p[p.size - 2], p[p.size - 1].toLongOrNull() ?: 0L)
    }

    /**
     * 读失败返回 `null`（**不是空列表**），坏行在解码阶段剔除。
     * 调用方凡是要写盘的路径都必须先判 null，否则一次磁盘抖动就能清空整队。
     */
    private fun readIndex(): List<Entry>? = synchronized(indexLock) {
        index.readOrNull()?.mapNotNull { line -> decode(line) }
    }

    /** 整份快照原子提交，返回是否成功；调用方须持有 [indexLock]（此处可重入）。 */
    private fun writeIndex(entries: List<Entry>): Boolean = synchronized(indexLock) {
        index.write(entries.map(::encode))
    }

    /** 超限时丢最旧（索引按入队时间追加，队首即最旧）。读不出来时不动盘。 */
    private fun trim() {
        synchronized(indexLock) {
            val entries = readIndex() ?: return
            if (entries.size <= MAX_FILES) return
            val drop = entries.take(entries.size - MAX_FILES)
            drop.forEach { e -> runCatching { File(dir, e.fileName).delete() } }
            writeIndex(entries.takeLast(MAX_FILES))
            Logger.w(TAG) { "retry queue overflow, dropped ${drop.size} oldest" }
        }
    }

    private fun extOf(name: String): String {
        val i = name.lastIndexOf('.')
        return if (i in 0 until name.length - 1) name.substring(i) else ""
    }

    private companion object {
        const val TAG = "MediaUploadRetry"
        const val DIR_NAME = "media_retry"
        const val INDEX_NAME = "index.txt"

        /** 队列上限：截屏约 300KB / 录屏可达数十 MB，50 条已是很保守的兜底量 */
        const val MAX_FILES = 50

        /** 存活期 24h：超过这个时间家长大概也不需要这张旧截图了 */
        const val MAX_AGE_MS = 24 * 60 * 60 * 1000L
    }
}
