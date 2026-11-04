package com.padguard.parent.presentation.viewmodel

import com.padguard.parent.domain.model.ReportPeriod
import com.padguard.parent.domain.model.UsageStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 平板使用详情页「剩余 / 超额」口径测试。
 *
 * 复现的是第十六轮遗留的问题：页面把限额固定取 `weekdayLimitMinutes`，
 * 且完全忽略「时间管控」总开关 ——
 * - 管控关着还显示"剩余 59 分钟"；
 * - 周末额度设 240、工作日 60 时，一周仍按 7×60 算。
 *
 * 这些断言都不依赖"今天星期几"，因此在 CI 上每天都成立：
 * 任意连续 7 天恰好包含 5 个工作日 + 2 个周末。
 */
class TabletUsageDetailLimitTest {

    private fun state(
        period: ReportPeriod = ReportPeriod.DAILY,
        enabled: Boolean = true,
        weekday: Int = 60,
        weekend: Int = 60,
        usedMinutes: Int = 0,
        limitKnown: Boolean = true
    ) = TabletUsageDetailUiState(
        period = period,
        todayStats = UsageStats(deviceId = "d1", date = "2026-11-04", totalUsageMinutes = usedMinutes),
        limitEnabled = enabled,
        weekdayLimitMinutes = weekday,
        weekendLimitMinutes = weekend,
        limitKnown = limitKnown
    )

    @Test
    fun `管控关闭时不展示剩余与超额`() {
        val s = state(enabled = false, usedMinutes = 500)
        assertFalse("总开关关闭 → 不存在限额", s.limitApplies)
        assertEquals(0, s.remainingSeconds)
        assertEquals(0, s.overLimitSeconds)
        assertFalse("不限时不能用'已超额'吓人", s.overLimit)
    }

    @Test
    fun `周末额度参与周窗口累加，而不是每日额度乘以天数`() {
        // 工作日 60 × 5 + 周末 240 × 2 = 780 分钟
        val s = state(period = ReportPeriod.WEEKLY, weekday = 60, weekend = 240)
        assertEquals(780 * 60, s.periodLimitSeconds)
    }

    @Test
    fun `月窗口按 30 天逐日累加，落在上下界之间`() {
        // 30 个连续自然日含 8~10 个周末日
        val s = state(period = ReportPeriod.MONTHLY, weekday = 60, weekend = 240)
        val minutes = s.periodLimitSeconds / 60
        assertTrue("实际 $minutes 分钟，应 >= 20×60+8×240", minutes >= 20 * 60 + 8 * 240)
        assertTrue("实际 $minutes 分钟，应 <= 20×60+10×240", minutes <= 20 * 60 + 10 * 240)
    }

    @Test
    fun `今日用量超过每日限额时给出超额秒数与标记`() {
        val s = state(enabled = true, weekday = 60, weekend = 60, usedMinutes = 100)
        assertTrue(s.limitApplies)
        assertEquals(60 * 60, s.periodLimitSeconds)
        assertTrue(s.overLimit)
        assertEquals((100 - 60) * 60, s.overLimitSeconds)
        assertEquals(0, s.remainingSeconds)
    }

    @Test
    fun `未超额时给出剩余秒数`() {
        val s = state(enabled = true, weekday = 90, weekend = 90, usedMinutes = 30)
        assertFalse(s.overLimit)
        assertEquals((90 - 30) * 60, s.remainingSeconds)
        assertEquals(0, s.overLimitSeconds)
    }

    @Test
    fun `限额读取失败时不得拿默认值当作真实额度`() {
        // 读取失败 → limitKnown=false：既不能说"剩余 60 分钟"，也不能说"已超额"
        val s = state(enabled = true, weekday = 60, weekend = 60, usedMinutes = 500, limitKnown = false)
        assertFalse(s.limitApplies)
        assertEquals(0, s.periodLimitSeconds)
        assertFalse(s.overLimit)
        assertEquals(0, s.overLimitSeconds)
    }
}
