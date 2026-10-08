package com.padguard.core.common

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [TimeProvider] 时钟校准逻辑的单元测试。
 *
 * 注意：运行在本地 JVM（非 Robolectric）上，因此任何会调到 [android.util.Log]
 * 的路径都必须规避 —— 这里在 @Before 里关掉 [Logger.enabled]，
 * 否则 Logger 会触发 Android 框架的 Stub 抛异常。
 * elapsedRealtime / uptimeMillis 等依赖 SystemClock 的方法不在本测试范围内。
 */
class TimeProviderTest {

    private var originalLoggerEnabled = true

    @Before
    fun setUp() {
        originalLoggerEnabled = Logger.enabled
        Logger.enabled = false
    }

    @After
    fun tearDown() {
        Logger.enabled = originalLoggerEnabled
    }

    @Test
    fun `now returns local wall clock before any sync`() {
        val tp = TimeProvider()
        val before = System.currentTimeMillis()
        val now = tp.now()
        val after = System.currentTimeMillis()
        assertTrue("now should fall within [before, after]", now in before..after)
    }

    // 服务端时间一律基于"当前真实时间 + 一个小偏移"构造。
    //
    // 不能硬编码某个历史时间戳（例如 1_700_000_000_000L = 2023-11）：
    // 随着真实时间推移，它与本地墙钟的差值会越来越大，
    // 一旦超过 implausible 阈值（24h）就会被当成脏样本拒收，
    // 于是测试在某一天突然开始失败 —— 这类"时间炸弹"正是要避免的。
    private fun serverTimeAheadBy(offsetMs: Long): Long = System.currentTimeMillis() + offsetMs

    @Test
    fun `syncServerTime with zero round-trip aligns now to server time`() {
        val tp = TimeProvider()
        val serverTimeMs = serverTimeAheadBy(2_500)
        tp.syncServerTime(serverTimeMs)

        val now = tp.now()
        // offset = serverTimeMs - localNow，故 now() = localNow' + offset ≈ serverTimeMs
        assertTrue(
            "now=$now 应约等于 serverTime=$serverTimeMs",
            kotlin.math.abs(now - serverTimeMs) < 5000
        )
    }

    @Test
    fun `syncServerTime compensates half of the round-trip latency`() {
        val tp = TimeProvider()
        val serverTimeMs = serverTimeAheadBy(2_500)
        val roundTripMs = 2000L
        tp.syncServerTime(serverTimeMs, roundTripMs)

        val now = tp.now()
        // 网络延迟折半补偿：now() ≈ serverTimeMs + roundTripMs / 2
        val expected = serverTimeMs + roundTripMs / 2
        assertTrue(
            "now=$now 应约等于 serverTime+RTT/2=$expected",
            kotlin.math.abs(now - expected) < 5000
        )
    }

    @Test
    fun `detectClockTampering is false when offset is unchanged`() {
        val tp = TimeProvider()
        tp.syncServerTime(serverTimeAheadBy(2_500))
        val currentOffset = tp.serverOffsetMs.value
        assertFalse(
            "offset 未漂移时不应判定为篡改",
            tp.detectClockTampering(currentOffset)
        )
    }

    @Test
    fun `detectClockTampering is true when drift exceeds tolerance`() {
        val tp = TimeProvider()
        tp.syncServerTime(serverTimeAheadBy(2_500))
        // 假设上次记录的 offset 与当前相差 1 小时，容忍度仅 1 分钟
        val previousOffset = tp.serverOffsetMs.value - 60 * 60 * 1000L
        assertTrue(
            "漂移 1 小时 > 容忍 1 分钟，应判定为篡改",
            tp.detectClockTampering(previousOffset, toleranceMs = 60_000L)
        )
    }

    @Test
    fun `detectClockTampering is false when drift within tolerance`() {
        val tp = TimeProvider()
        tp.syncServerTime(serverTimeAheadBy(2_500))
        val currentOffset = tp.serverOffsetMs.value
        // 小漂移（1s），大容忍度（1 分钟）
        assertFalse(
            "漂移 1s < 容忍 1 分钟，不应判定为篡改",
            tp.detectClockTampering(currentOffset + 1000L, toleranceMs = 60_000L)
        )
    }

    // ==================== P0-4：样本滤波与双速收敛 ====================

    @Test
    fun `implausible sample is rejected and does not move offset`() {
        val tp = TimeProvider()
        tp.syncServerTime(System.currentTimeMillis() + 5_000)   // 先建立一个正常 offset
        val settled = tp.serverOffsetMs.value

        // 本地墙钟被改到三天后 —— 这种样本必须丢弃，否则时段判定全错
        tp.syncServerTime(System.currentTimeMillis() + 3 * 24 * 3600 * 1000L)
        assertTrue(
            "离谱样本不应改变 offset（settled=$settled, now=${tp.serverOffsetMs.value}）",
            kotlin.math.abs(tp.serverOffsetMs.value - settled) < 3_000
        )
    }

    @Test
    fun `single noisy sample does not yank the offset`() {
        val tp = TimeProvider()
        val base = System.currentTimeMillis() + 500
        repeat(4) { tp.syncServerTime(base) }        // 收敛到 +500 附近
        val settled = tp.serverOffsetMs.value

        // 一次网络排队造成的 2s 离群样本（仍在 3s 快速收敛阈值内 → 走平滑）
        tp.syncServerTime(base + 2_000)
        assertTrue(
            "单次离群样本不应把 offset 直接拽走 2s（settled=$settled, now=${tp.serverOffsetMs.value}）",
            kotlin.math.abs(tp.serverOffsetMs.value - settled) < 1_000
        )
    }

    @Test
    fun `real clock jump converges immediately`() {
        val tp = TimeProvider()
        val base = System.currentTimeMillis() + 500
        repeat(4) { tp.syncServerTime(base) }

        // 用户把系统时间拨快 1 小时 —— 这是真实跳变，必须一轮就跟上，
        // 平滑收敛会让 offset 爬十几秒，期间所有时段判定都是错的。
        val jumped = System.currentTimeMillis() + 3600_000
        repeat(3) { tp.syncServerTime(jumped) }
        assertTrue(
            "真实跳变应快速收敛（offset=${tp.serverOffsetMs.value}）",
            kotlin.math.abs(tp.serverOffsetMs.value - 3600_000) < 5_000
        )
    }

    @Test
    fun `seedOffset only applies before any real sample`() {
        val tp = TimeProvider()
        tp.seedOffset(1234L)
        assertTrue("冷启动应接受持久值", tp.serverOffsetMs.value == 1234L)

        val base = System.currentTimeMillis() + 7_000
        repeat(3) { tp.syncServerTime(base) }
        // 已有真实样本后，迟到的持久值不得覆盖
        tp.seedOffset(999_999L)
        assertTrue(
            "已有真实样本后持久值不得覆盖（offset=${tp.serverOffsetMs.value}）",
            tp.serverOffsetMs.value != 999_999L
        )
    }

    @Test
    fun `zero or negative serverTime is ignored`() {
        val tp = TimeProvider()
        tp.syncServerTime(0)
        tp.syncServerTime(-1)
        assertTrue("占位/非法时间戳不应产生 offset", tp.serverOffsetMs.value == 0L)
    }
}
