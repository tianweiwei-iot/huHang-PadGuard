package com.padguard.child.guard

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GuardTickSignal] 的行为测试。
 *
 * 它承担的是"把 15 秒采样窗口收掉"这件事，所以两个性质都必须钉死：
 * 1. **叫得醒**：请求之后 [GuardTickSignal.awaitWithin] 立刻返回 true（否则限额判定照样晚 15 秒）；
 * 2. **叫不疯**：1 秒内的重复请求合并成一次（否则孩子连点五个图标就触发五次完整采样，
 *    每次都要查 UsageStats、写用量、落盘）。
 *
 * 另外要守住"漏信号只退化、不挂死"：没有请求时 awaitWithin 必须按时超时返回 false。
 */
class GuardTickSignalTest {

    @Test
    fun `request wakes the waiting loop once and repeats inside the window are merged`() = runBlocking {
        val signal = GuardTickSignal()
        var clock = 1_000L
        signal.elapsedNow = { clock }

        // 没人叫：必须按时超时，且不吞掉后续的唤醒能力
        assertFalse("无请求时应超时返回 false", signal.awaitWithin(50))

        signal.request("foreground:com.game")
        assertTrue("请求后应立刻唤醒", signal.awaitWithin(1_000))

        // 1 秒内再来两次（孩子连点图标）：合并，不再唤醒
        signal.request("foreground:com.video")
        clock += 300L
        signal.request("foreground:com.chat")
        assertFalse("节流窗口内的重复请求应被合并", signal.awaitWithin(50))

        // 过了节流窗口：恢复唤醒
        clock += 1_000L
        signal.request("foreground:com.chat")
        assertTrue("节流窗口过后应再次唤醒", signal.awaitWithin(1_000))
    }

    @Test
    fun `throttle clock is monotonic and never blocks forever`() = runBlocking {
        val signal = GuardTickSignal()
        // 时钟被"回拨"（模拟异常读数）：只要差值不足窗口就仍按节流处理，
        // 不会因为负差值永久锁死 —— 差值 >= 窗口（含负数溢出保护）才放行。
        var clock = 10_000L
        signal.elapsedNow = { clock }
        signal.request("a")
        assertTrue(signal.awaitWithin(1_000))

        clock -= 5_000L
        signal.request("b")
        // 回拨后 now - last < 0 < MIN_INTERVAL → 视作节流窗口内，丢弃这一次
        assertFalse("时钟回拨时不得放行（避免高频风暴）", signal.awaitWithin(50))

        clock += 10_000L
        signal.request("c")
        assertTrue("时钟恢复推进后应重新放行", signal.awaitWithin(1_000))
    }
}
