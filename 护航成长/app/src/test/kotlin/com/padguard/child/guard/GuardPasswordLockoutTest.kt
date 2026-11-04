package com.padguard.child.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 操作密码「错 N 次锁定」的判定口径。
 *
 * ## 为什么值得单独测
 * 这道锁是家长密码唯一防爆破机制（4~6 位数字，离线枚举成本极低）。它同时依赖两个时钟，
 * 而两者的失效方向相反：
 * - 墙钟可被往前调 → 若"两个时钟都未过期才算锁定"（原实现用 `&&`），一拨时间锁定立刻解除；
 * - 单调时钟在设备重启后归零 → 若只看它，重启会把"还剩 2 分钟"放大成"锁一整段开机时长"。
 *
 * 正确口径是：**各自算剩余、取较大值、再夹到一个锁定时长以内**。
 * 本测试把这几条语义逐条钉住 —— 只要有人把它改回 `&&` 或去掉上界，立刻会红。
 *
 * 时间全部由参数注入，不触碰 `SystemClock`（本地单测里 android.jar 是"未实现"桩）。
 */
class GuardPasswordLockoutTest {

    private val lockout = GuardPasswordManager.LOCKOUT_MS
    private val nowElapsed = 1_000_000L
    private val nowWall = 1_700_000_000_000L

    /** 正常锁定中：两个时钟都还剩 4 分钟 */
    @Test
    fun `both clocks still counting - stays locked`() {
        val remaining = GuardPasswordManager.lockedRemainingMs(
            untilElapsed = nowElapsed + 4 * 60_000L,
            untilWall = nowWall + 4 * 60_000L,
            nowElapsed = nowElapsed,
            nowWall = nowWall
        )
        assertEquals(4 * 60_000L, remaining)
    }

    /**
     * 孩子把系统时间往前拨 10 分钟：墙钟已"过期"，单调时钟还剩 4 分钟。
     *
     * 这正是原实现（`elapsed < 锁到 && now < 锁到`）的可利用点 —— 拨时间即可无限重试。
     * 期望：仍然锁定，且剩余时间以单调时钟为准。
     */
    @Test
    fun `wall clock moved forward - still locked by monotonic clock`() {
        val remaining = GuardPasswordManager.lockedRemainingMs(
            untilElapsed = nowElapsed + 4 * 60_000L,
            untilWall = nowWall + 4 * 60_000L,
            // 墙钟被拨快 10 分钟 → 按墙钟算已过期
            nowElapsed = nowElapsed,
            nowWall = nowWall + 10 * 60_000L
        )
        assertEquals(4 * 60_000L, remaining)
    }

    /**
     * 设备重启：elapsedRealtime 归零，而锁定时刻是上一轮开机的巨大数值。
     *
     * 若只看单调时钟，会"锁到 83 分钟后"。期望：夹到一个完整锁定时长，
     * 最多多锁 5 分钟（fail-closed，但不会把家长挡在门外一小时）。
     */
    @Test
    fun `after reboot stale elapsed deadline is capped to one lockout`() {
        val remaining = GuardPasswordManager.lockedRemainingMs(
            untilElapsed = 5_000_000L, // 上一轮开机时的时刻
            untilWall = nowWall + 2 * 60_000L, // 墙钟还剩 2 分钟
            nowElapsed = 3_000L, // 刚重启，开机 3 秒
            nowWall = nowWall
        )
        assertEquals(lockout, remaining)
    }

    /** 两个时钟都真的过期了：立刻放行，不能因为"曾经锁过"而继续锁 */
    @Test
    fun `both clocks expired - unlocks`() {
        val remaining = GuardPasswordManager.lockedRemainingMs(
            untilElapsed = nowElapsed - 1L,
            untilWall = nowWall - 1L,
            nowElapsed = nowElapsed,
            nowWall = nowWall
        )
        assertEquals(0L, remaining)
    }

    /**
     * 系统时间被往回拨 30 天：按墙钟算"距离解锁还有 30 天"，剩余必须被夹到一个锁定时长。
     *
     * 场景建模：锁定时刻是真实时间 + 5 分钟；之后墙钟被调慢 30 天，
     * 于是 `锁到 - 现在` 变成 30 天零 5 分钟 —— 不夹上界就会把孩子挡在门外一个月。
     */
    @Test
    fun `wall clock moved backward - capped`() {
        val remaining = GuardPasswordManager.lockedRemainingMs(
            untilElapsed = nowElapsed + 60_000L,
            untilWall = nowWall + 5 * 60_000L,
            nowElapsed = nowElapsed,
            nowWall = nowWall - 30L * 86_400_000L // 墙钟被拨慢了 30 天
        )
        assertEquals(lockout, remaining)
    }

    /** 从未锁定（两项都是 0）：不能因为"elapsed 很小"就误判为锁定 */
    @Test
    fun `never locked - no lockout`() {
        assertEquals(
            0L,
            GuardPasswordManager.lockedRemainingMs(0L, 0L, nowElapsed, nowWall)
        )
    }

    /** 锁定剩余不得为负：调用方用 `> 0` 判定，负数会被漏判 */
    @Test
    fun `remaining is never negative`() {
        assertTrue(
            GuardPasswordManager.lockedRemainingMs(
                untilElapsed = 1L,
                untilWall = 1L,
                nowElapsed = nowElapsed,
                nowWall = nowWall
            ) >= 0L
        )
    }
}
