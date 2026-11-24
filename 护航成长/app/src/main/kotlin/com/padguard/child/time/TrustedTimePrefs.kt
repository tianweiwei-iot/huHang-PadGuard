package com.padguard.child.time

import android.content.Context
import com.padguard.core.common.Logger
import com.padguard.core.common.TrustedBaseline

/**
 * 受信时间护栏的跨重启持久化（离线时间篡改护栏的冷启动兜底，对应 R-P0-1）。
 *
 * ## 与 [TimeOffsetPrefs] 的区别
 * [TimeOffsetPrefs] 只持久化 offset（墙钟校正量），它会在首个网络样本到达后被真实对时覆盖；
 * 而本类持久化的是护栏的**锚点快照** —— [com.padguard.core.common.TimeProvider]
 * 进程重启后锚点归零、首帧会退化为裸墙钟，于是孩子改系统时间即可在冷启动窗口绕过锁机。
 * 把锚点持久化到磁盘，开机即恢复，离线时间管控无需服务器对时即可生效，
 * 满足"离线必须本地独立生效"红线。
 *
 * ## 为什么是三元组而不是单个 trustedMs
 * 只存"上次受信时刻"、恢复时原样设回锚点，会把时间**冻结在关机那一刻**：
 * 昨晚 23:00 关机（宵禁中）、今早 08:00 开机，护栏仍认为现在是 23:00，
 * 白天继续锁机，日切 / 每日额度 / 时段边界全部按错误时刻判定，且联网前不会自愈。
 * 因此同时记下落盘瞬间的单调时钟与裸墙钟，由 TimeProvider 的
 * `resumedTrustedMs` 纯函数还原真实流逝。
 * 三个字段必须在**同一个 editor** 里一次提交，否则读到半新半旧的组合会算出错误流逝量。
 *
 * ## 旧盘兼容
 * 旧版本只写了 [KEY_TRUSTED]。此时 [TrustedBaseline.hasResumeContext] 为 false，
 * 退化为"只恢复锚点"的旧行为；本地首个 tick 写出三元组后自动升级，无需迁移。
 *
 * ## 复用同一份 SharedPreferences
 * 与 [TimeOffsetPrefs] 共用 `padguard_time` 文件（键不同），读写点同在守护服务内，
 * 不引入新的 I/O 路径。
 */
object TrustedTimePrefs {

    private const val PREFS = "padguard_time"
    private const val KEY_TRUSTED = "trusted_anchor_ms"
    private const val KEY_WALL = "trusted_wall_ms"
    private const val KEY_MONO = "trusted_mono_ms"

    fun load(context: Context): TrustedBaseline =
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            TrustedBaseline(
                trustedMs = prefs.getLong(KEY_TRUSTED, 0L),
                wallMs = prefs.getLong(KEY_WALL, 0L),
                monoMs = prefs.getLong(KEY_MONO, -1L),
            )
        }.onFailure {
            Logger.w("TrustedTimePrefs") { "load failed: ${it.message}" }
        }.getOrDefault(TrustedBaseline(trustedMs = 0L))

    /**
     * 落盘一份完整快照。
     *
     * 只在受信时间**确实推进了**时才写：主循环每分钟探一次，1 秒内的小幅抖动直接跳过，
     * 避免守护服务在平板待机时持续产生不必要的 I/O。
     * 快照无效（锚点尚未建立 / 旧格式）时不写，避免用脏值覆盖盘上的好基线。
     *
     * ## 为什么用 commit() 而非 apply()
     * 这份三元组是**离线时间管控防篡改的冷启动锚点**（对应 R-P0-1）：孩子改系统时间后重启，
     * 受信钟要先读它才能恢复"真实流逝"，否则冷启动窗口里时段锁 / 宵禁 / 日切会被绕开，
     * 违反「离线时间管控必须本地独立生效」红线。状态转移类落盘按项目红线一律用 commit()，
     * 用 apply() 异步提交时若进程在落盘前被强杀，最近一分钟的锚点会丢、恢复出的受信时间
     * 最多偏旧约 1 分钟（虽被单调增量补偿兜底，仍属不应发生的退化）。本方法在守护主循环
     * （IO 协程，非主线程）调用，commit() 的同步阻塞不会触发 ANR。
     */
    fun saveIfChanged(context: Context, baseline: TrustedBaseline) {
        if (!baseline.hasResumeContext()) return
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val previous = prefs.getLong(KEY_TRUSTED, 0L)
            // 1 秒内的小幅抖动不值得写盘
            if (previous != 0L && kotlin.math.abs(previous - baseline.trustedMs) < 1_000L) return
            prefs.edit()
                .putLong(KEY_TRUSTED, baseline.trustedMs)
                .putLong(KEY_WALL, baseline.wallMs)
                .putLong(KEY_MONO, baseline.monoMs)
                .commit()
        }.onFailure {
            Logger.w("TrustedTimePrefs") { "save failed: ${it.message}" }
        }
    }
}
