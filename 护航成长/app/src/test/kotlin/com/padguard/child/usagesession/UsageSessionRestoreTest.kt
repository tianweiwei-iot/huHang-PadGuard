package com.padguard.child.usagesession

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UsageSessionRestore] 的分支穷举测试。
 *
 * 覆盖的每条分支都对应一个**真实发生过的离线脱管路径**（详见该类注释）：
 * 跨零点重启整天不管控、同日未激活额度信息丢失、跨日重起表不落盘导致"重启即续杯"、
 * 家长关掉总开关后仍被锁。
 *
 * 纯 JVM 可跑：决策层不碰 Context / SharedPreferences / SystemClock，副作用留在
 * [UsageSessionManager] 里。
 */
class UsageSessionRestoreTest {

    private val today = "20261107"
    private val nowElapsed = 1_000_000L
    private val nowWall = 1_700_000_000_000L
    private val minute = 60_000L

    private fun snapshot(
        dayKey: String? = today,
        active: Boolean = true,
        remainingMs: Long = 30 * minute,
        serverNow: Long = nowWall,
        savedElapsed: Long = nowElapsed,
        quotaMinutes: Int = 60,
        extendedMinutes: Int = 0,
        timeControlEnabled: Boolean = true
    ) = UsageSessionRestore.Snapshot(
        dayKey = dayKey,
        active = active,
        remainingMs = remainingMs,
        serverNow = serverNow,
        savedElapsed = savedElapsed,
        quotaMinutes = quotaMinutes,
        extendedMinutes = extendedMinutes,
        timeControlEnabled = timeControlEnabled
    )

    private fun plan(snapshot: UsageSessionRestore.Snapshot?, elapsed: Long = nowElapsed, wall: Long = nowWall) =
        UsageSessionRestore.plan(snapshot, today, elapsed, wall)

    // ==================== 暂停快照（2026-11-18）====================

    @Test
    fun `paused snapshot keeps frozen remaining instead of deducting sleep again`() {
        val p = plan(
            snapshot(savedElapsed = nowElapsed - 5 * minute, serverNow = nowWall - 5 * minute).copy(
                paused = true,
                pauseSource = "MANUAL",
                pauseStartedElapsed = nowElapsed - 1_000L
            )
        )!!

        assertTrue("进程被杀后仍处于暂停，恢复必须由孩子显式继续", p.paused)
        assertEquals("冻结的剩余不能被冷启动流逝再扣一遍", 30 * minute, p.remainingMs)
        assertEquals("MANUAL", p.pauseSource)
        assertEquals(nowElapsed - 1_000L, p.pauseStartedElapsed)
        assertFalse("暂停快照同日无需 restamp", p.restamp)
    }

    @Test
    fun `cross day snapshot never restores yesterday pause`() {
        val p = plan(
            snapshot(dayKey = "20261106").copy(
                paused = true,
                pauseSource = "SCREEN_OFF",
                pauseStartedElapsed = 100L
            )
        )!!
        assertFalse("新的一天不继承昨天的暂停态", p.paused)
        assertNull(p.pauseSource)
        assertEquals(0L, p.pauseStartedElapsed)
        assertTrue(p.restamp)
    }

    // ==================== 无快照 ====================

    @Test
    fun `no snapshot leaves default state untouched`() {
        assertNull(plan(null))
    }

    @Test
    fun `snapshot without a day key is ignored`() {
        assertNull(plan(snapshot(dayKey = null)))
        assertNull(plan(snapshot(dayKey = "")))
        assertNull(plan(snapshot(dayKey = "-")))
    }

    // ==================== 同日续算 ====================

    @Test
    fun `same day active continues countdown from monotonic delta`() {
        // 真实快照里两个时刻取自同一瞬间：单调 5 分钟前落盘 == 墙钟 5 分钟前
        val p = plan(
            snapshot(savedElapsed = nowElapsed - 5 * minute, serverNow = nowWall - 5 * minute)
        )!!

        assertTrue("同日已激活必须续算，不能回到默认态", p.active)
        assertFalse("恢复态不是新的开始，不该弹确认浮层", p.needsStartConfirm)
        assertEquals(25 * minute, p.remainingMs)
        assertEquals("基线要跟着推进，否则本地倒计时会多算 5 分钟", nowWall, p.serverNow)
        assertFalse("同日续算不需要重写落盘快照", p.restamp)
    }

    @Test
    fun `after device reboot monotonic resets so wall clock fills the gap`() {
        // 设备重启：elapsedRealtime 归零，单调差退化为 0，必须靠墙钟补停机时长
        val p = plan(
            snapshot(savedElapsed = 0L, serverNow = nowWall - 10 * minute),
            elapsed = 2_000L,
            wall = nowWall
        )!!

        assertTrue(p.active)
        assertEquals(20 * minute, p.remainingMs)
    }

    @Test
    fun `the larger of monotonic and wall advance wins`() {
        // 单调差 3 分钟、墙钟差 8 分钟（墙钟被前拨或跨重启），取大 → 少算额度
        val p = plan(snapshot(savedElapsed = nowElapsed - 3 * minute, serverNow = nowWall - 8 * minute))!!
        assertEquals(22 * minute, p.remainingMs)
    }

    @Test
    fun `remaining never goes negative`() {
        val p = plan(snapshot(remainingMs = minute, savedElapsed = nowElapsed - 60 * minute))!!
        assertEquals(0L, p.remainingMs)
        assertTrue("额度用尽必须体现为 active 且剩余 0，才能挂 DAILY_LIMIT", p.active)
    }

    @Test
    fun `negative deltas from clock weirdness are clamped to zero advance`() {
        val p = plan(snapshot(savedElapsed = nowElapsed + 99_000L, serverNow = nowWall + 99_000L))!!
        assertEquals("推进量不能为负，否则剩余时长会凭空变多", 30 * minute, p.remainingMs)
    }

    // ==================== 同日未激活 ====================

    @Test
    fun `same day inactive does not auto start but keeps quota for display`() {
        val p = plan(snapshot(active = false, remainingMs = 0L, quotaMinutes = 60, extendedMinutes = 15))!!

        assertFalse("未确认开始时不替孩子起表（起表要由服务端锁起点）", p.active)
        assertTrue("同日未激活 = 今日尚未确认，等待页与确认浮层必须工作（进程重启不吞提示）", p.needsStartConfirm)
        assertEquals("额度信息必须回填，否则首页会显示'剩余 0 分钟'却不锁屏", 60, p.quotaMinutes)
        assertEquals(15, p.extendedMinutes)
    }

    // ==================== 跨日 ====================

    @Test
    fun `cross day enters awaiting-confirm instead of auto starting`() {
        // 2026-11-09 口径变更：跨日不再自动按满额起表（那等于"跨零点重启就白拿一天额度"，
        // 且与「未确认不开始计时」冲突）。改为进入待确认：不计时、不消耗额度，
        // 设备由 PolicyEngine 挂 AWAITING_START 等待页，孩子确认后才起表。
        val p = plan(snapshot(dayKey = "20261106", remainingMs = 0L, quotaMinutes = 60))!!

        assertFalse("跨日绝不自动起表（未确认不开始计时）", p.active)
        assertTrue("跨日后必须进入待确认（弹确认浮层 + 等待页）", p.needsStartConfirm)
        assertEquals("不计时就没有剩余可言", 0L, p.remainingMs)
        assertEquals(nowWall, p.serverNow)
        assertTrue("跨日必须立刻落盘，否则每次重启都会再判一次跨日分支", p.restamp)
    }

    @Test
    fun `cross day clears yesterday's extensions`() {
        // 延时额度按天清零：昨天的审批不给今天续命（与服务端 startFreshIfStale 一致）
        val p = plan(snapshot(dayKey = "20261106", quotaMinutes = 60, extendedMinutes = 30))!!
        assertFalse(p.active)
        assertEquals(0, p.extendedMinutes)
        assertEquals(0L, p.remainingMs)
    }

    @Test
    fun `cross day with unknown quota still awaits confirm`() {
        // 额度未知（KEY_QUOTA 缺省 0）不猜数字：同样进入待确认，
        // 起表起点与额度由服务端 start() 决定，本地无需知道额度。
        val p = plan(snapshot(dayKey = "20261106", quotaMinutes = 0, extendedMinutes = 0))!!

        assertFalse(p.active)
        assertTrue("额度未知也要走确认流程（等待页照常工作）", p.needsStartConfirm)
        assertEquals(0L, p.remainingMs)
        assertTrue(p.restamp)
    }

    @Test
    fun `iso formatted day key matches compact local key`() {
        val p = plan(snapshot(dayKey = "2026-11-07", savedElapsed = nowElapsed - 5 * minute))!!
        assertTrue("服务端 ISO 与本地紧凑格式归一化后必须相等，否则每次启动都判成跨日", p.active)
        assertEquals(25 * minute, p.remainingMs)
        assertFalse(p.restamp)
    }

    @Test
    fun `restamped snapshot is not refilled on the next boot`() {
        // 第一次：跨日 → 待确认（active=false, restamp=true），调用方按 first 落盘
        val first = plan(snapshot(dayKey = "20261106", quotaMinutes = 60))!!
        assertFalse(first.active)
        assertEquals(0L, first.remainingMs)
        assertTrue(first.restamp)

        // 模拟调用方按 first 落盘（今天、未激活）后，10 分钟后又重启一次：
        // 同日未激活 → 保持待确认，不因为重启而消失提示
        val next = plan(
            snapshot(
                dayKey = today,
                active = first.active,
                remainingMs = first.remainingMs,
                serverNow = first.serverNow,
                savedElapsed = nowElapsed,
                quotaMinutes = first.quotaMinutes,
                extendedMinutes = first.extendedMinutes
            ),
            elapsed = nowElapsed + 10 * minute,
            wall = first.serverNow + 10 * minute
        )!!
        assertFalse("仍处于待确认（不自动起表）", next.active)
        assertTrue("提示不因重启消失", next.needsStartConfirm)
        assertFalse(next.restamp)
    }

    // ==================== 总开关 ====================

    @Test
    fun `disabled time control never starts a session or prompts`() {
        val p = plan(snapshot(dayKey = "20261106", timeControlEnabled = false))!!

        assertFalse("家长关掉总开关后不得起表，否则'管控关不掉'", p.active)
        assertFalse(p.needsStartConfirm)
        assertEquals(0L, p.remainingMs)
        assertFalse(p.timeControlEnabled)
    }

    // ==================== 归一化 ====================

    @Test
    fun `normalize keeps digits and drops separators`() {
        assertEquals("20261107", UsageSessionRestore.normalizeDayKey("2026-11-07"))
        assertEquals("20261107", UsageSessionRestore.normalizeDayKey("20261107"))
        assertNull(UsageSessionRestore.normalizeDayKey(null))
        assertNull(UsageSessionRestore.normalizeDayKey("----"))
    }
}
