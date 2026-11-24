package com.padguard.child.guard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GuardGateSuspension] 豁免窗口的时钟域测试（新增缺陷 I）。
 *
 * 窗口一旦被裸墙钟计时，孩子在设置里回拨系统时间就能让 `now < suspendedUntil`
 * 长期成立：`OperationGuardAccessibilityService` 在豁免期放行的不只是普通设置页，
 * 还包括设备管理员停用页与防卸载自检分支，整条应用管控链路可被拆掉。
 *
 * 这里注入单调读数，钉住"窗口只按单调流逝自愈、完全不碰墙钟"。
 */
class GuardGateSuspensionTest {

    @Test
    fun `window opens and self-heals by monotonic elapsed time`() {
        val gate = GuardGateSuspension()
        var clock = 10_000L
        gate.elapsedNow = { clock }
        assertFalse(gate.isActive())

        gate.suspendFor(60_000L)
        assertTrue(gate.isActive())

        clock += 59_999L
        assertTrue(gate.isActive())
        clock += 1L
        assertFalse("到点必须自动恢复拦截，不能依赖向导调用 clear()", gate.isActive())
    }

    /**
     * 核心回归：注入一个**远小于真实墙钟**的读数（等价于把墙钟前拨/回拨到任意值），
     * 窗口判定仍只按注入的单调轴走。旧实现用 `System.currentTimeMillis()`，
     * 这里的读数与墙钟相差数年却仍能自愈，直接暴露两个时钟域的分裂。
     */
    @Test
    fun `evaluation is independent of the wall clock`() {
        val gate = GuardGateSuspension()
        var clock = 1_000L // 与 System.currentTimeMillis() 相差数十年
        gate.elapsedNow = { clock }
        gate.suspendFor(30_000L)

        clock = 1_000L + 30_001L
        assertFalse("墙钟怎么改都不应让豁免窗口继续敞开", gate.isActive())
    }

    /** 回拨读数（现实中单调钟不会，但可证伪"用差值判窗口"的错误实现）不会复活窗口 */
    @Test
    fun `rolling the clock backwards never reopens an expired window`() {
        val gate = GuardGateSuspension()
        var clock = 100_000L
        gate.elapsedNow = { clock }
        gate.suspendFor(10_000L)
        clock += 10_001L
        assertFalse(gate.isActive())

        clock = 1L
        assertFalse("已过期的窗口不得因读数回拨而复活", gate.isActive())
    }

    @Test
    fun `clear closes the window immediately`() {
        val gate = GuardGateSuspension()
        var clock = 5_000L
        gate.elapsedNow = { clock }
        gate.suspendFor(60_000L)
        assertTrue(gate.isActive())

        gate.clear()
        assertFalse(gate.isActive())
        clock += 1L
        assertFalse(gate.isActive())
    }

    @Test
    fun `renew extends the window from the latest call`() {
        val gate = GuardGateSuspension()
        var clock = 0L
        gate.elapsedNow = { clock }
        gate.suspendFor(10_000L)
        clock = 9_000L
        gate.suspendFor(10_000L) // 续期：19_000
        clock = 15_000L
        assertTrue(gate.isActive())
        clock = 19_000L
        assertFalse(gate.isActive())
    }

    @Test
    fun `non-positive duration never opens a window`() {
        val gate = GuardGateSuspension()
        var clock = 1_000L
        gate.elapsedNow = { clock }
        gate.suspendFor(0L)
        assertFalse(gate.isActive())
        gate.suspendFor(-5_000L)
        assertFalse(gate.isActive())
    }
}
