package com.padguard.child.guard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无障碍存活判定的分支覆盖。
 *
 * 重点回归 2026-11-05 的线上误判：服务连接满 [GuardA11y] 的 2 分钟容忍窗口后，
 * 心跳因从未续约而过期，导致"服务明明在跑、全端却一致认为它掉线"。
 * 现在只要进程内是 CONNECTED，就不允许因为心跳陈旧而判死。
 */
class GuardA11yTest {

    private val grace = 2 * 60 * 1000L
    private val now = 1_800_000_000_000L

    @Test
    fun `开关关闭时即便进程内已连接也判死`() {
        // 用户/ROM 把服务从无障碍列表移除 —— 这才是唯一能确定"人为关闭"的信号，
        // 必须优先于一切进程内状态。
        assertFalse(
            GuardA11y.decideAlive(
                enabled = false,
                link = GuardA11y.Link.CONNECTED,
                lastAliveAt = now,
                now = now,
                graceMs = grace
            )
        )
    }

    @Test
    fun `已连接时心跳再陈旧也算活着`() {
        // 回归项：修复前 isAlive 只看"开关 + 心跳新鲜度"，
        // 服务连续运行超过 grace 后必然翻转为 false。
        assertTrue(
            GuardA11y.decideAlive(
                enabled = true,
                link = GuardA11y.Link.CONNECTED,
                lastAliveAt = now - 10 * 60 * 1000L,
                now = now,
                graceMs = grace
            )
        )
    }

    @Test
    fun `服务已销毁时心跳再新鲜也判死`() {
        // onDestroy 之后，持久心跳仍可能在容忍窗口内 —— 不能让它掩盖"已经断开"这一事实。
        assertFalse(
            GuardA11y.decideAlive(
                enabled = true,
                link = GuardA11y.Link.DISCONNECTED,
                lastAliveAt = now,
                now = now,
                graceMs = grace
            )
        )
    }

    @Test
    fun `刚重启且从未有心跳时相信开关`() {
        // 首次装机 / 刚清过数据：还没有任何心跳记录，此时只能以开关为准，
        // 否则会在服务首次连接前就把正常状态误报成掉线。
        assertTrue(
            GuardA11y.decideAlive(
                enabled = true,
                link = GuardA11y.Link.UNKNOWN,
                lastAliveAt = 0L,
                now = now,
                graceMs = grace
            )
        )
    }

    @Test
    fun `未知状态下心跳新鲜则算活着`() {
        // 进程刚重启、系统尚未重绑：给系统一个短暂的重绑窗口。
        assertTrue(
            GuardA11y.decideAlive(
                enabled = true,
                link = GuardA11y.Link.UNKNOWN,
                lastAliveAt = now - grace + 1,
                now = now,
                graceMs = grace
            )
        )
    }

    @Test
    fun `未知状态下心跳时刻在未来时判死而不是复活`() {
        // 回拨系统时间 / 旧版墙钟值落到单调域 / 重启后单调归零：
        // 负差值曾让 UNKNOWN 分支恒为 true，服务已死却不再提示补授权。
        assertFalse(
            GuardA11y.decideAlive(
                enabled = true,
                link = GuardA11y.Link.UNKNOWN,
                lastAliveAt = now + 10 * 60 * 1000L,
                now = now,
                graceMs = grace
            )
        )
    }

    @Test
    fun `未知状态下心跳过期则判死`() {
        assertFalse(
            GuardA11y.decideAlive(
                enabled = true,
                link = GuardA11y.Link.UNKNOWN,
                lastAliveAt = now - grace - 1,
                now = now,
                graceMs = grace
            )
        )
    }

    // ==================== matchesEnabledSetting（双口径主判断的纯函数） ====================

    private val flat = "com.padguard.child/com.padguard.child.guard.OperationGuardAccessibilityService"

    @Test
    fun `设置串精确包含本服务组件即判定启用`() {
        assertTrue(GuardA11y.matchesEnabledSetting(flat, flat))
    }

    @Test
    fun `设置串含多个服务时仍能命中本服务`() {
        val others = "com.other.app/com.other.app.SomeService"
        assertTrue(GuardA11y.matchesEnabledSetting(flat, "$others:$flat"))
        assertTrue(GuardA11y.matchesEnabledSetting(flat, "$flat:$others"))
    }

    @Test
    fun `设置串大小写不一致也能命中（部分 ROM 小写落盘）`() {
        assertTrue(GuardA11y.matchesEnabledSetting(flat, flat.lowercase()))
    }

    @Test
    fun `设置串不含本服务时判定未启用`() {
        val others = "com.other.app/com.other.app.SomeService"
        assertFalse(GuardA11y.matchesEnabledSetting(flat, others))
    }

    @Test
    fun `设置串为空或 null 时判定未启用`() {
        assertFalse(GuardA11y.matchesEnabledSetting(flat, ""))
        assertFalse(GuardA11y.matchesEnabledSetting(flat, null))
    }

    @Test
    fun `空白组件名永不命中`() {
        assertFalse(GuardA11y.matchesEnabledSetting("   ", flat))
    }
}
