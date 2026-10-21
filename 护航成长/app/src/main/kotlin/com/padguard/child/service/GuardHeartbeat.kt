package com.padguard.child.service

import android.content.Context
import android.os.SystemClock
import com.padguard.core.common.Logger
import java.io.File

/**
 * 守护保活的**文件心跳与状态原语**（跨进程安全）。
 *
 * ## 为什么不用 SharedPreferences
 * SharedPreferences 实例按进程缓存：A 进程写入后，B 进程持有的同一名字 SP 实例
 * **不会收到更新**，跨进程读到的永远是陈旧值 —— 用它做跨进程心跳会系统性失效。
 * 这里改用「写临时文件 → rename 原子替换」：任一进程读到的必是最新值，
 * 且不存在读到写一半的中间态（rename 在同一文件系统内是原子的）。
 *
 * ## 三个状态文件（均位于 filesDir）
 * | 文件 | 写者 | 读者 | 语义 |
 * |---|---|---|---|
 * | `guard_alive` | GuardService（每 15s 主循环 + 启动时） | daemon / 看门狗 / 闹钟 | 主进程守护存活心跳 |
 * | `daemon_alive` | GuardDaemonService（每 5s） | GuardService / 看门狗 / 闹钟 | 守护进程存活心跳 |
 * | `guard_state` | GuardService（绑定态变化时） | daemon | 是否已绑定（未绑定则不起服务） |
 * | `live_view_state` | GuardService（看屏开始/停止） | GuardService（重启后自愈） | 家长正在看屏的档位与截止时间 |
 *
 * ## 为什么用 elapsedRealtime 判定心跳新旧
 * 单调时钟，不受孩子改系统时间影响；设备重启后 elapsedRealtime 归零，
 * 此时「新值 < 旧值」即判陈旧 —— 语义正好是"重启后必须重新拉起"。
 * 墙钟只用于 live_view 截止时间这类需要跨重启延续的语义。
 */
object GuardHeartbeat {

    private const val TAG = "GuardHeartbeat"

    private const val FILE_GUARD_ALIVE = "guard_alive"
    private const val FILE_DAEMON_ALIVE = "daemon_alive"
    private const val FILE_GUARD_STATE = "guard_state"
    private const val FILE_LIVE_VIEW = "live_view_state"

    /** 主进程心跳陈旧阈值：主循环 15s 一跳，留 25s 余量，最坏 ~40s 发现 */
    const val GUARD_STALE_MS = 40_000L

    /** 守护进程心跳陈旧阈值：daemon 5s 一跳，最坏 ~30s 发现 */
    const val DAEMON_STALE_MS = 30_000L

    /** 看屏会话恢复窗口：略大于服务端会话 TTL(180s)，覆盖重发冷却间隙 */
    const val LIVE_VIEW_TTL_MS = 200_000L

    /** 看屏恢复状态（进程重启后据此主动恢复推流） */
    data class LiveViewState(val highDefinition: Boolean, val deadlineWallMs: Long)

    // ==================== 主进程心跳 ====================

    /** GuardService 调用：写一条存活心跳（幂等，可高频） */
    fun writeGuardAlive(context: Context) {
        atomicWrite(guardAliveFile(context), SystemClock.elapsedRealtime().toString())
    }

    /**
     * 主进程心跳年龄（毫秒）。
     * @return null = 从未写过（新装/被清数据）；Long.MAX_VALUE = 设备已重启过，必陈旧
     */
    fun readGuardAliveAgeMs(context: Context): Long? = readAliveAgeMs(guardAliveFile(context))

    // ==================== 守护进程心跳 ====================

    /** GuardDaemonService 调用：写一条存活心跳 */
    fun writeDaemonAlive(context: Context) {
        atomicWrite(daemonAliveFile(context), SystemClock.elapsedRealtime().toString())
    }

    /** 守护进程心跳年龄（毫秒）；语义同 [readGuardAliveAgeMs] */
    fun readDaemonAliveAgeMs(context: Context): Long? = readAliveAgeMs(daemonAliveFile(context))

    // ==================== 绑定态 ====================

    /**
     * GuardService 调用：记录绑定态。
     * 守护进程只在「已绑定」时才拉起主进程 —— 没有家长就没有管控依据，
     * 与 GuardService 的 `boundNow` 语义保持一致，防止解绑后反复重启打扰。
     */
    fun writeGuardState(context: Context, bound: Boolean) {
        atomicWrite(guardStateFile(context), if (bound) "bound" else "unbound")
    }

    /** 读取绑定态；无记录按未绑定处理（保守：不起服务） */
    fun readGuardState(context: Context): Boolean =
        runCatching { guardStateFile(context).readText().trim() }.getOrNull() == "bound"

    // ==================== 看屏会话 ====================

    /** GuardService 收到 LIVE_VIEW_START 时写：进程被杀后据此主动恢复推流 */
    fun writeLiveView(context: Context, highDefinition: Boolean, deadlineWallMs: Long) {
        atomicWrite(
            liveViewFile(context),
            "hd=${if (highDefinition) 1 else 0}\ndeadline=$deadlineWallMs"
        )
    }

    /** 家长停止观看 / 解绑 / 窗口过期时清理 */
    fun clearLiveView(context: Context) {
        runCatching { liveViewFile(context).delete() }
            .onFailure { Logger.w(TAG, it) { "clear live_view_state failed" } }
    }

    /** 读取仍在有效期内的看屏状态；过期/无记录返回 null（并顺带清理过期文件） */
    fun readLiveView(context: Context): LiveViewState? {
        val file = liveViewFile(context)
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        var hd = false
        var deadline = 0L
        text.lineSequence().forEach { line ->
            when {
                line.startsWith("hd=") -> hd = line.removePrefix("hd=").trim() == "1"
                line.startsWith("deadline=") ->
                    deadline = line.removePrefix("deadline=").trim().toLongOrNull() ?: 0L
            }
        }
        if (deadline <= 0L) return null
        if (deadline < System.currentTimeMillis()) {
            runCatching { file.delete() }
            return null
        }
        return LiveViewState(hd, deadline)
    }

    // ==================== 内部 ====================

    private fun guardAliveFile(context: Context) = File(context.filesDir, FILE_GUARD_ALIVE)
    private fun daemonAliveFile(context: Context) = File(context.filesDir, FILE_DAEMON_ALIVE)
    private fun guardStateFile(context: Context) = File(context.filesDir, FILE_GUARD_STATE)
    private fun liveViewFile(context: Context) = File(context.filesDir, FILE_LIVE_VIEW)

    /**
     * 原子写：先写同目录临时文件再 rename。跨进程读者永远看到完整的新值或完整的旧值。
     * rename 失败（极少数文件系统）退化为直接覆写，功能不中断。
     */
    private fun atomicWrite(target: File, content: String) {
        runCatching {
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(content)
            if (!tmp.renameTo(target)) {
                target.writeText(content)
                tmp.delete()
            }
        }.onFailure { Logger.w(TAG, it) { "atomic write failed: ${target.name}" } }
    }

    /**
     * 从心跳文件算年龄。文件不存在 → null（从未运行过）；
     * 设备重启过（now < stored）→ Long.MAX_VALUE（必须重新拉起）。
     */
    private fun readAliveAgeMs(file: File): Long? {
        val stored = runCatching { file.readText().trim().toLongOrNull() }.getOrNull() ?: return null
        val now = SystemClock.elapsedRealtime()
        if (now < stored) return Long.MAX_VALUE
        return now - stored
    }
}
