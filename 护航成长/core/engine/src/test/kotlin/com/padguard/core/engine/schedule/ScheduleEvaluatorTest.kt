package com.padguard.core.engine.schedule

import com.padguard.core.common.Logger
import com.padguard.core.common.TimeProvider
import com.padguard.core.data.model.policy.DayType
import com.padguard.core.data.model.policy.ScheduleAction
import com.padguard.core.data.model.policy.SchedulePolicy
import com.padguard.core.data.model.policy.ScheduleRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * [ScheduleEvaluator] 纯逻辑测试。
 *
 * 关键点：
 * 1. 直接把待判定时刻作为 [ScheduleEvaluator.evaluate] 的 `atMillis` 参数传入，
 *    不依赖 [TimeProvider.now]，彻底绕开 `android.os.SystemClock` 桩。
 * 2. 用 [FakeHolidayProvider] 注入可控的节假日/调休数据，覆盖 dayType 分支。
 * 3. 统一用 UTC 时区构造 policy 与时刻，避免 Asia/Shanghai 的时区/DST 干扰。
 * 4. [Logger.enabled] 在测试中关闭，避免 `android.util.Log` 桩抛 "Stub!"。
 */
class ScheduleEvaluatorTest {

    private val timeProvider = TimeProvider()
    private lateinit var evaluator: ScheduleEvaluator
    private var originalLoggerEnabled = true

    @Before
    fun setUp() {
        originalLoggerEnabled = Logger.enabled
        Logger.enabled = false
        evaluator = ScheduleEvaluator(timeProvider, FakeHolidayProvider())
    }

    @After
    fun tearDown() {
        Logger.enabled = originalLoggerEnabled
    }

    /** 周一 2026-08-31，周二 2026-09-01，周六 2026-09-05，周日 2026-09-06 */
    private fun at(date: LocalDate, hour: Int, minute: Int): Long =
        date.atTime(hour, minute).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()

    @Test
    fun emptyRulesReturnsUnlocked() {
        val policy = SchedulePolicy(timezone = "UTC", rules = emptyList())
        val verdict = evaluator.evaluate(policy, at(LocalDate.of(2026, 8, 31), 21, 30))
        assertTrue(verdict is ScheduleVerdict.Unlocked)
    }

    @Test
    fun weekdayLockRuleHitsAtNight() {
        val rule = ScheduleRule(
            dayTypes = listOf(DayType.WEEKDAY),
            start = "21:00", end = "22:00",
            action = ScheduleAction.LOCK
        )
        val policy = SchedulePolicy(timezone = "UTC", rules = listOf(rule))
        val monday = LocalDate.of(2026, 8, 31)
        assertTrue(evaluator.evaluate(policy, at(monday, 21, 30)) is ScheduleVerdict.Locked)
    }

    @Test
    fun weekdayLockRuleMissesDuringDay() {
        val rule = ScheduleRule(
            dayTypes = listOf(DayType.WEEKDAY),
            start = "21:00", end = "22:00",
            action = ScheduleAction.LOCK
        )
        val policy = SchedulePolicy(timezone = "UTC", rules = listOf(rule))
        val monday = LocalDate.of(2026, 8, 31)
        assertTrue(evaluator.evaluate(policy, at(monday, 20, 0)) is ScheduleVerdict.Unlocked)
    }

    @Test
    fun unlockRuleTakesPriorityOverLock() {
        val lockAllDay = ScheduleRule(
            dayTypes = listOf(DayType.WEEKDAY),
            start = "00:00", end = "23:59",
            action = ScheduleAction.LOCK
        )
        val unlockWindow = ScheduleRule(
            dayTypes = listOf(DayType.WEEKDAY),
            start = "14:00", end = "16:00",
            action = ScheduleAction.UNLOCK
        )
        val policy = SchedulePolicy(timezone = "UTC", rules = listOf(lockAllDay, unlockWindow))
        val monday = LocalDate.of(2026, 8, 31)
        // 落在 UNLOCK 窗口内 -> 放行
        assertTrue(evaluator.evaluate(policy, at(monday, 15, 0)) is ScheduleVerdict.Unlocked)
        // 窗口外 -> LOCK 生效
        assertTrue(evaluator.evaluate(policy, at(monday, 10, 0)) is ScheduleVerdict.Locked)
    }

    @Test
    fun crossMidnightLockSpansTwoDays() {
        val rule = ScheduleRule(
            dayTypes = listOf(DayType.WEEKDAY),
            start = "21:30", end = "06:30",
            action = ScheduleAction.LOCK
        )
        val policy = SchedulePolicy(timezone = "UTC", rules = listOf(rule))
        val monday = LocalDate.of(2026, 8, 31)
        val tuesday = LocalDate.of(2026, 9, 1)
        // 当晚 22:00（今日这半段）-> 锁定
        assertTrue(evaluator.evaluate(policy, at(monday, 22, 0)) is ScheduleVerdict.Locked)
        // 跨零点延续到次日凌晨 02:00（用昨日的 dayType 判定）-> 锁定
        assertTrue(evaluator.evaluate(policy, at(tuesday, 2, 0)) is ScheduleVerdict.Locked)
        // 次日白天 -> 解锁
        assertTrue(evaluator.evaluate(policy, at(tuesday, 12, 0)) is ScheduleVerdict.Unlocked)
    }

    @Test
    fun dayTypeOfRespectsHolidayAndWorkdayOverride() {
        val monday = LocalDate.of(2026, 8, 31)
        val tuesday = LocalDate.of(2026, 9, 1) // 强制为节假日
        val saturday = LocalDate.of(2026, 9, 5) // 强制为调休上班日
        val sunday = LocalDate.of(2026, 9, 6)
        val ev = ScheduleEvaluator(
            timeProvider,
            FakeHolidayProvider(
                holidays = setOf(tuesday),
                workdayOverrides = setOf(saturday)
            )
        )
        assertEquals(DayType.WEEKDAY, ev.dayTypeOf(monday))
        assertEquals(DayType.HOLIDAY, ev.dayTypeOf(tuesday))
        assertEquals(DayType.WEEKEND, ev.dayTypeOf(sunday))
        // 调休上班日（周六）按工作日判定
        assertEquals(DayType.WEEKDAY, ev.dayTypeOf(saturday))
    }

    @Test
    fun holidayDegeneratesToWeekendRule() {
        val tuesday = LocalDate.of(2026, 9, 1) // 强制节假日
        val ev = ScheduleEvaluator(
            timeProvider,
            FakeHolidayProvider(holidays = setOf(tuesday))
        )
        val weekendRule = ScheduleRule(
            dayTypes = listOf(DayType.WEEKEND),
            start = "10:00", end = "11:00",
            action = ScheduleAction.LOCK
        )
        val weekdayRule = ScheduleRule(
            dayTypes = listOf(DayType.WEEKDAY),
            start = "10:00", end = "11:00",
            action = ScheduleAction.LOCK
        )
        val weekendPolicy = SchedulePolicy(timezone = "UTC", rules = listOf(weekendRule))
        val weekdayPolicy = SchedulePolicy(timezone = "UTC", rules = listOf(weekdayRule))
        // 节假日退化按周末处理 -> WEEKEND 规则命中锁定
        assertTrue(ev.evaluate(weekendPolicy, at(tuesday, 10, 30)) is ScheduleVerdict.Locked)
        // 节假日不是工作日 -> WEEKDAY 规则不命中
        assertTrue(ev.evaluate(weekdayPolicy, at(tuesday, 10, 30)) is ScheduleVerdict.Unlocked)
    }

    @Test
    fun nextBoundaryComputesSameDayEnd() {
        val rule = ScheduleRule(
            dayTypes = listOf(DayType.WEEKDAY),
            start = "21:00", end = "22:00",
            action = ScheduleAction.LOCK
        )
        val policy = SchedulePolicy(timezone = "UTC", rules = listOf(rule))
        val monday = LocalDate.of(2026, 8, 31)
        val verdict = evaluator.evaluate(policy, at(monday, 21, 30))
        assertTrue(verdict is ScheduleVerdict.Locked)
        assertEquals(at(monday, 22, 0), (verdict as ScheduleVerdict.Locked).untilMillis)
    }

    @Test
    fun nextBoundaryCrossMidnightRollsToNextDay() {
        val rule = ScheduleRule(
            dayTypes = listOf(DayType.WEEKDAY),
            start = "21:30", end = "06:30",
            action = ScheduleAction.LOCK
        )
        val policy = SchedulePolicy(timezone = "UTC", rules = listOf(rule))
        val monday = LocalDate.of(2026, 8, 31)
        val tuesday = LocalDate.of(2026, 9, 1)
        val verdict = evaluator.evaluate(policy, at(monday, 22, 0))
        assertTrue(verdict is ScheduleVerdict.Locked)
        assertEquals(at(tuesday, 6, 30), (verdict as ScheduleVerdict.Locked).untilMillis)
    }

    /** 可控的节假日/调休实现，供测试注入特定日期 */
    private class FakeHolidayProvider(
        private val holidays: Set<LocalDate> = emptySet(),
        private val workdayOverrides: Set<LocalDate> = emptySet()
    ) : HolidayProvider {
        override fun isHoliday(date: LocalDate): Boolean = date in holidays
        override fun isWorkdayOverride(date: LocalDate): Boolean = date in workdayOverrides
    }
}
