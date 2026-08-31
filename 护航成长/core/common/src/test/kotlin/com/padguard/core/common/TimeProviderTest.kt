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

    @Test
    fun `syncServerTime with zero round-trip aligns now to server time`() {
        val tp = TimeProvider()
        val serverTimeMs = 1_700_000_000_000L
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
        val serverTimeMs = 1_700_000_000_000L
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
        tp.syncServerTime(1_700_000_000_000L)
        val currentOffset = tp.serverOffsetMs.value
        assertFalse(
            "offset 未漂移时不应判定为篡改",
            tp.detectClockTampering(currentOffset)
        )
    }

    @Test
    fun `detectClockTampering is true when drift exceeds tolerance`() {
        val tp = TimeProvider()
        tp.syncServerTime(1_700_000_000_000L)
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
        tp.syncServerTime(1_700_000_000_000L)
        val currentOffset = tp.serverOffsetMs.value
        // 小漂移（1s），大容忍度（1 分钟）
        assertFalse(
            "漂移 1s < 容忍 1 分钟，不应判定为篡改",
            tp.detectClockTampering(currentOffset + 1000L, toleranceMs = 60_000L)
        )
    }
}
