package com.padguard.child.time

import android.content.Context
import com.padguard.core.common.Logger

/**
 * 时钟偏移的跨进程/跨重启持久化。
 *
 * ## 为什么需要它
 * [com.padguard.core.common.TimeProvider] 的 offset 是进程内变量，进程一死就归零。
 * 归零后 `now()` 退化为本地墙钟，而平板的墙钟本身可能有几百毫秒到数秒的偏差 ——
 * 于是每次冷启动后的头一两次时段判定（尤其卡在 22:00 宵禁边界）会走本地不准的时间，
 * 表现为"刚开机时锁机时快时慢"。
 *
 * 记一份到磁盘，开机先 [load] 注入再等第一个网络样本修正，把这段窗口压到最短。
 * 它只是**初值**，永远不会覆盖真实样本（见 TimeProvider.seedOffset 的守卫）。
 *
 * ## 为什么用 SharedPreferences
 * 只有一个 Long，且读写点在守护服务内（可能跨进程调用），
 * DataStore 的 Flow/事务能力用不上，SP 的 `apply()` 异步落盘对这条路径最轻。
 */
object TimeOffsetPrefs {

    private const val PREFS = "padguard_time"
    private const val KEY_OFFSET = "server_offset_ms"

    fun load(context: Context): Long =
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_OFFSET, 0L)
        }.onFailure {
            Logger.w("TimeOffsetPrefs") { "load failed: ${it.message}" }
        }.getOrDefault(0L)

    /**
     * 落盘。
     *
     * 只在 offset **确实变了**时才写：心跳 30s 一次，SP 的 apply() 每次都会触发一次
     * 磁盘提交，无脑写会让守护服务在平板待机时持续产生不必要的 I/O。
     */
    fun saveIfChanged(context: Context, offsetMs: Long) {
        if (offsetMs == 0L) return
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val previous = prefs.getLong(KEY_OFFSET, 0L)
            // 1 秒内的小幅抖动不值得写盘
            if (previous != 0L && kotlin.math.abs(previous - offsetMs) < 1_000L) return
            prefs.edit().putLong(KEY_OFFSET, offsetMs).apply()
        }.onFailure {
            Logger.w("TimeOffsetPrefs") { "save failed: ${it.message}" }
        }
    }
}
