package com.padguard.child.guard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BlockCounter] 纯逻辑单元测试。
 *
 * 验证连点计数器的核心契约：滑动窗口、阈值触发、过期重置、按包独立、显式复位。
 * 使用注入的 `now` 参数驱动时间，避免依赖真实时钟导致的不稳定。
 */
class BlockCounterTest {

    @Test
    fun `below threshold returns false and reaching threshold returns true`() {
        val counter = BlockCounter(windowMs = 3000L, threshold = 3)
        val t0 = 1000L
        assertFalse(counter.record("com.a", now = t0)) // 1
        assertFalse(counter.record("com.a", now = t0 + 100)) // 2
        assertTrue(counter.record("com.a", now = t0 + 200)) // 3 -> 阈值
    }

    @Test
    fun `count resets after window expires (lazy)`() {
        val counter = BlockCounter(windowMs = 3000L, threshold = 3)
        val t0 = 1000L
        counter.record("com.a", now = t0) // 1
        counter.record("com.a", now = t0 + 100) // 2
        // 超过窗口（>3000ms）：下次命中视为新一轮，计数从 1 重新开始，不应触发阈值。
        assertFalse(counter.record("com.a", now = t0 + 3001)) // 新一轮 1
        assertFalse(counter.record("com.a", now = t0 + 3100)) // 2
        assertTrue(counter.record("com.a", now = t0 + 3200)) // 3 -> 阈值
    }

    @Test
    fun `reset clears a package and count restarts`() {
        val counter = BlockCounter(windowMs = 3000L, threshold = 2)
        val t0 = 5000L
        assertFalse(counter.record("com.a", now = t0)) // 1
        assertTrue(counter.record("com.a", now = t0 + 50)) // 2 -> 阈值(2)
        counter.reset("com.a", now = t0)
        // 复位后重新计数，不应带着之前的累计。
        assertFalse(counter.record("com.a", now = t0 + 100)) // 1
        assertTrue(counter.record("com.a", now = t0 + 200)) // 2 -> 阈值
    }

    @Test
    fun `different packages are counted independently`() {
        val counter = BlockCounter(windowMs = 3000L, threshold = 2)
        val t0 = 9000L
        assertFalse(counter.record("com.a", now = t0)) // 1
        assertTrue(counter.record("com.a", now = t0 + 50)) // 2 -> com.a 达到阈值
        // com.b 独立计数，不应被 com.a 的命中影响。
        assertFalse(counter.record("com.b", now = t0 + 100)) // 1
        assertTrue(counter.record("com.b", now = t0 + 150)) // 2 -> com.b 阈值
    }

    @Test
    fun `threshold of one triggers on first hit`() {
        val counter = BlockCounter(windowMs = 1000L, threshold = 1)
        assertTrue(counter.record("com.x", now = 0L))
    }

    @Test
    fun `multiple hits within window do not exceed threshold semantics`() {
        val counter = BlockCounter(windowMs = 5000L, threshold = 3)
        val t0 = 0L
        assertFalse(counter.record("com.a", now = t0))
        assertFalse(counter.record("com.a", now = t0 + 10))
        assertTrue(counter.record("com.a", now = t0 + 20))
        // 已达阈值后继续命中仍返回 true（调用方应 reset，这里只验证不崩、语义稳定）。
        assertTrue(counter.record("com.a", now = t0 + 30))
    }
}
