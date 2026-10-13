package com.padguard.core.common

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 防时间篡改的计时基准。
 *
 * 设计要点（对应说明书「防时间篡改落地逻辑」）：
 * 1. 所有时长统计一律基于 [android.os.SystemClock.elapsedRealtime] —— 该时钟单调递增，
 *    包含深度睡眠时间，且**无法通过修改系统时间伪造**，是本地计时的唯一可信来源。
 * 2. [android.os.SystemClock.uptimeMillis] 不含深度睡眠，用于「屏幕亮起时长」类统计。
 * 3. 系统墙钟时间（System.currentTimeMillis）仅用于日志展示与上报，
 *    通过 [serverOffsetMs] 与服务端标准时间对齐；一旦检测到用户改时间，
 *    墙钟会跳变，但 [elapsedRealtime] 不受影响，因此时长管控无法被绕过。
 */
@Singleton
class TimeProvider @Inject constructor() {

    private val _serverOffsetMs = MutableStateFlow(0L)
    /** 服务端标准时间 = 本地墙钟 + offset */
    val serverOffsetMs: StateFlow<Long> = _serverOffsetMs.asStateFlow()

    /** 单调递增时钟（含休眠），时长统计与时段判定的唯一基准 */
    fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()

    /** 单调递增时钟（不含深度休眠），用于亮屏时长统计 */
    fun uptimeMillis(): Long = SystemClock.uptimeMillis()

    /** 设备本次开机至今的运行时长 */
    fun sinceBootMs(): Long = SystemClock.elapsedRealtime()

    // ----------------------------------------------------------------------
    // 对时样本窗口（加固）
    //
    // 早期实现是"每个响应直接覆盖 offset"。HTTP 响应到达时刻受网络排队、
    // 长轮询挂起、GC 停顿影响，单次样本本身就带 ±几百毫秒噪声；
    // 更糟的是一次偶发的长延迟（如长轮询被服务端唤醒得晚）会让 offset 瞬间跳变
    // 几百毫秒到数秒，直接落在宵禁 / 时段管控的判定边界上，
    // 表现为"刚好 22:00 时锁机时快时慢"。
    //
    // 因此改为：样本进窗口 → 取中位数抗离群 → 双速收敛写入。
    // ----------------------------------------------------------------------

    private val lock = Any()
    private val samples = ArrayDeque<Long>(SAMPLE_WINDOW)
    private var lastJitterMs = 0L
    @Volatile private var lastSyncNanos = -1L
    @Volatile private var sampleCount = 0L
    @Volatile private var rejectedCount = 0L

    /** 最近一个窗口内样本的最大离散度（ms），用于可观测性与自诊断 */
    fun jitterMs(): Long = synchronized(lock) { lastJitterMs }

    /**
     * 距离上次成功对时经过的**单调**时长（ms）。
     *
     * 用 [System.nanoTime] 而不是 [SystemClock.elapsedRealtime]：本类跑在纯 JVM 单元测试里，
     * `SystemClock` 是 android.jar 的 stub，一调用就抛 "Stub!"；
     * 而 nanoTime 是标准 JDK，在 Android 与 JVM 上都是单调递增且不受改系统时间影响，
     * 对"多久没对上时"这种相对度量完全够用。
     */
    fun sinceLastSyncMs(): Long {
        val base = lastSyncNanos
        if (base < 0) return Long.MAX_VALUE
        return (System.nanoTime() - base) / 1_000_000L
    }

    /** 对时是否已陈旧（超过 [STALE_AFTER_MS] 未拿到新样本） */
    fun isStale(): Boolean = sinceLastSyncMs() > STALE_AFTER_MS

    /** 已接受 / 已拒收的样本计数，排查"为什么 offset 不动"时直接看这个 */
    fun stats(): String = "samples=$sampleCount rejected=$rejectedCount jitter=${jitterMs()}ms age=${if (lastSyncNanos < 0) "never" else "${sinceLastSyncMs()}ms"}"

    /**
     * 冷启动时注入上一次持久化的 offset，让首帧就能拿到接近正确的服务端时间。
     *
     * 不注入也能工作（首个样本到达时会走"快速收敛"全量覆盖），
     * 但那样开机后的头一两次时段判定会落在本地墙钟上 —— 若平板时钟本身不准，
     * 这一两秒的窗口里宵禁判定可能出错。注入只是把窗口缩到最短。
     */
    fun seedOffset(offsetMs: Long) {
        if (offsetMs == 0L) return
        // 持久化的 offset 来自「同一台设备上一次会话」，可信范围应远小于实时样本阈值：
        // 限制到 ±2h，避免设备被改到离谱时间后把上次的偏移原样注入，进而把日切 / 周末判定
        // 整体带偏一整天（表现为「学生端日用量与家长端对不上」）。
        // 真正的 ±24h 脏样本拒收仍由 [syncServerTime] 兜底。
        if (offsetMs !in -MAX_SEED_OFFSET_MS..MAX_SEED_OFFSET_MS) return
        synchronized(lock) {
            if (sampleCount > 0) return   // 已有真实样本，持久值不再覆盖
            _serverOffsetMs.value = offsetMs
        }
        Logger.d("TimeProvider") { "offset seeded from disk: ${offsetMs}ms" }
    }

    /** 当前 offset 原值，供上层持久化 */
    fun currentOffsetMs(): Long = _serverOffsetMs.value

    /**
     * 对齐服务端标准时间。
     *
     * @param serverTimeMs 服务端下发的时间戳（UTC 毫秒）
     * @param roundTripMs  **仅在 serverTimeMs 是「请求到达时刻」加盖时**才传往返耗时做折半补偿。
     *
     * ## 为什么调用方通常必须传 0（重要）
     * 本项目的 serverTime 由服务端在**响应写出那一刻**加盖（长轮询更是要等服务端挂起结束才写响应），
     * 它与"本地收到响应的时刻"只差一个单向网络时延。此时若再补 `roundTripMs / 2`，
     * 长轮询会把 20 秒挂起时长的一半（约 10 秒）凭空加到 offset 上，
     * 导致终端时钟系统性偏快、每轮抖动 —— 表现为上报时间对不上、指令 since 落到未来被过滤。
     * 因此 [com.padguard.core.transport.http.ApiCaller] 统一以 **0** 调用本方法。
     */
    fun syncServerTime(serverTimeMs: Long, roundTripMs: Long = 0L) {
        if (serverTimeMs <= 0) return
        val localNow = System.currentTimeMillis()
        val sample = serverTimeMs + roundTripMs / 2 - localNow

        // 脏样本拒收：本地墙钟与服务端相差超过 24h，只有两种可能 ——
        // 服务端下发了 0/占位值，或本地时钟被改到了离谱的时间点。
        // 无论哪种，直接采纳都会把后续所有时段判定带偏，宁可丢弃等下一个样本。
        if (sample !in -MAX_PLAUSIBLE_OFFSET_MS..MAX_PLAUSIBLE_OFFSET_MS) {
            rejectedCount++
            Logger.w("TimeProvider") { "implausible offset sample ${sample}ms rejected" }
            return
        }

        val next = synchronized(lock) {
            if (samples.size >= SAMPLE_WINDOW) samples.removeFirst()
            samples.addLast(sample)
            sampleCount++
            lastSyncNanos = System.nanoTime()

            val sorted = samples.sorted()
            lastJitterMs = sorted.last() - sorted.first()
            val median = sorted[sorted.size / 2]
            val current = _serverOffsetMs.value

            // 双速收敛：
            // - 首次对时 / 偏差超过阈值 → 全量采纳。这是真实的时钟跳变
            //   （用户改了系统时间、设备跨时区、冷启动首个样本），
            //   平滑反而会让 offset 花上十几秒才爬到正确值，期间判定全错。
            // - 否则 → 一阶低通。α=0.25 意味着约 4 个样本（≈12 秒）追平 96%，
            //   足以吃掉单次网络排队带来的百毫秒级噪声。
            val target = if (current == 0L || kotlin.math.abs(median - current) > FAST_CONVERGE_MS) {
                median
            } else {
                current + (median - current) / SMOOTHING_DIVISOR
            }
            _serverOffsetMs.value = target
            target
        }
        Logger.d("TimeProvider") { "server time synced, offset=${next}ms (${stats()})" }
    }

    /** 校准后的当前时间（UTC 毫秒）；未同步前退化为本地墙钟 */
    fun now(): Long = System.currentTimeMillis() + _serverOffsetMs.value

    /**
     * 检测本地墙钟是否被篡改。
     *
     * 判定依据：上次同步后落地的偏移量在短时间内出现显著漂移。
     * 一旦命中，调用方应强制重新拉取服务端时间并上报高危告警。
     */
    fun detectClockTampering(previousOffset: Long, toleranceMs: Long = DRIFT_TOLERANCE_MS): Boolean {
        val drift = kotlin.math.abs(_serverOffsetMs.value - previousOffset)
        if (drift > toleranceMs) {
            Logger.w("TimeProvider") { "clock tampering detected, drift=${drift}ms" }
            return true
        }
        return false
    }

    companion object {
        /** 允许的自然时钟漂移阈值：15 分钟 */
        const val DRIFT_TOLERANCE_MS = 15 * 60 * 1000L

        /** 中位数滤波的样本窗口大小 */
        private const val SAMPLE_WINDOW = 5

        /** 偏差超过该值即判定为真实时钟跳变，全量收敛而非平滑 */
        private const val FAST_CONVERGE_MS = 3_000L

        /** 一阶低通除数：4 → α=0.25 */
        private const val SMOOTHING_DIVISOR = 4L

        /** offset 的可信上限：实时对时样本的脏样本拒收阈值（超过 24h 视为无效） */
        private const val MAX_PLAUSIBLE_OFFSET_MS = 24 * 60 * 60 * 1000L

        /** 持久化 offset 的注入上限：同一设备上次会话的偏移，限制到 ±2h 足以覆盖时区/夏令时，
         *  又不至于把「设备被改到离谱时间」的偏移原样带回来污染日切判定 */
        private const val MAX_SEED_OFFSET_MS = 2 * 60 * 60 * 1000L

        /** 超过该时长没拿到新样本即认为对时已陈旧 */
        private const val STALE_AFTER_MS = 30 * 60 * 1000L
    }
}
