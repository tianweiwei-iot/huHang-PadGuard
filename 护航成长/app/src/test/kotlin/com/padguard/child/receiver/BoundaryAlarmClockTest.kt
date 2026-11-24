package com.padguard.child.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 时段边界闹钟时钟域换算的纯 JVM 单测（R-P2-6 第九轮修正）。
 *
 * 核心要钉住的语义：闹钟触发点必须是"单调时钟 + 距边界的时长"，
 * 绝不能把受信时间轴上的绝对 UTC 毫秒直接交给 AlarmManager（那会回到裸墙钟 RTC 的老路）。
 */
class BoundaryAlarmClockTest {

    private val now = 1_700_000_000_000L
    private val elapsed = 5_000_000L

    @Test
    fun `future boundary is converted to elapsedRealtime plus delay`() {
        val trigger = BoundaryAlarmClock.triggerAtElapsed(
            nextBoundaryMs = now + 30 * 60_000L,
            trustedNowMs = now,
            elapsedNowMs = elapsed,
        )
        assertEquals(elapsed + 30 * 60_000L, trigger)
    }

    @Test
    fun `no boundary means no alarm`() {
        assertNull(BoundaryAlarmClock.triggerAtElapsed(null, now, elapsed))
    }

    @Test
    fun `past boundary is not scheduled as an already expired trigger`() {
        // 前拨系统时间的旧实现里，RTC 闹钟拿到"过去时刻"会立即触发并被白白消耗，
        // 而 lastLockBoundaryAt 仍记着该边界不再重排 → 真实到点没有唤醒。
        assertNull(BoundaryAlarmClock.triggerAtElapsed(now - 2 * 3_600_000L, now, elapsed))
    }

    @Test
    fun `boundary exactly at now is not scheduled`() {
        // 主循环本轮 tick 已经按当前时刻判定过，再排一个零延迟闹钟没有意义。
        assertNull(BoundaryAlarmClock.triggerAtElapsed(now, now, elapsed))
    }

    @Test
    fun `dirty absolute timestamps are rejected`() {
        assertNull(BoundaryAlarmClock.triggerAtElapsed(0L, now, elapsed))
        assertNull(BoundaryAlarmClock.triggerAtElapsed(-1L, now, elapsed))
        assertNull(BoundaryAlarmClock.triggerAtElapsed(now + 60_000L, 0L, elapsed))
        assertNull(BoundaryAlarmClock.triggerAtElapsed(now + 60_000L, -1L, elapsed))
    }

    @Test
    fun `implausibly distant boundary is rejected`() {
        val tooFar = BoundaryAlarmClock.MAX_BOUNDARY_DELAY_MS + 1L
        assertNull(BoundaryAlarmClock.triggerAtElapsed(now + tooFar, now, elapsed))
    }

    @Test
    fun `boundary at the lookahead limit is still scheduled`() {
        val delay = BoundaryAlarmClock.MAX_BOUNDARY_DELAY_MS
        assertEquals(
            elapsed + delay,
            BoundaryAlarmClock.triggerAtElapsed(now + delay, now, elapsed)
        )
    }

    @Test
    fun `dirty elapsedRealtime or overflow is rejected`() {
        assertNull(BoundaryAlarmClock.triggerAtElapsed(now + 60_000L, now, -1L))
        assertNull(
            BoundaryAlarmClock.triggerAtElapsed(
                now + 60_000L, now, Long.MAX_VALUE - 1L
            )
        )
    }

    @Test
    fun `only the distance to boundary matters not the absolute instant`() {
        // 防止有人把换算改成"直接拿绝对 UTC 毫秒去排 RTC"：
        // 两对完全不同的绝对时刻，只要差值相同，触发点必须只差 elapsed 的差值。
        val delay = 45 * 60_000L
        val early = BoundaryAlarmClock.triggerAtElapsed(now + delay, now, elapsed)
        val later = BoundaryAlarmClock.triggerAtElapsed(
            now + 400L * 24 * 3_600_000L + delay,
            now + 400L * 24 * 3_600_000L,
            elapsed + 1_000L,
        )
        assertEquals(elapsed + delay, early)
        assertEquals(elapsed + 1_000L + delay, later)
    }
}
