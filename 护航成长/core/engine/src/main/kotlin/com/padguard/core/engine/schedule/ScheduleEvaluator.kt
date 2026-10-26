package com.padguard.core.engine.schedule

import com.padguard.core.common.Logger
import com.padguard.core.common.TimeProvider
import com.padguard.core.data.model.policy.DayType
import com.padguard.core.data.model.policy.ScheduleAction
import com.padguard.core.data.model.policy.SchedulePolicy
import com.padguard.core.data.model.policy.ScheduleRule
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 时段锁机判定。
 *
 * ## 时间基准的选择（这是整个模块最关键的决定）
 * 时段判定**必须**用墙钟时间（"晚上 9 点半"是墙钟概念），不能用单调时钟。
 * 但墙钟可被用户修改 —— 把时间调到中午即可绕过就寝锁。
 *
 * 解法是三层：
 * 1. 用 [TimeProvider.now]（服务端校准后的墙钟）而非 `System.currentTimeMillis()`；
 * 2. 由 [com.padguard.core.engine.enforcer.SystemLockEnforcer] 强制自动对时并禁止手改时间；
 * 3. 一旦检测到时钟漂移异常，[com.padguard.core.engine.guard.TamperDetector] 上报高危事件，
 *    且**按最严格策略执行**（即倾向于锁定），而不是放行。
 *
 * ## 跨零点区间
 * `21:30 → 06:30` 这类就寝时段的 end 小于 start，必须拆成两段判断：
 * `[21:30, 24:00)` 落在当日，`[00:00, 06:30)` 落在次日。
 * 这里还有个细节：次日那一半应该用**次日的 dayType** 来匹配还是当日的？
 * 本实现用「区间起始日的 dayType」—— 即周五 21:30 开始的锁定会延续到周六早上，
 * 符合"周五晚上该睡觉"的直觉；若用次日 dayType，周六规则会让锁定意外提前解除。
 */
@Singleton
class ScheduleEvaluator @Inject constructor(
    private val timeProvider: TimeProvider,
    private val holidayProvider: HolidayProvider
) {

    /**
     * 判定当前是否处于锁定时段。
     *
     * @return 命中的锁定规则；null 表示当前不需要因时段而锁屏
     */
    fun evaluate(policy: SchedulePolicy, atMillis: Long = timeProvider.now()): ScheduleVerdict {
        // 家长端「启用时间管控」总开关关闭时，不按时段锁机：
        // 否则关掉开关后孩子端仍会在"可用时段"之外锁屏，与开关语义冲突。
        if (!policy.enabled) return ScheduleVerdict.Unlocked
        if (policy.rules.isEmpty()) return ScheduleVerdict.Unlocked

        val zone = runCatching { ZoneId.of(policy.timezone) }.getOrElse {
            Logger.w(TAG) { "invalid timezone ${policy.timezone}, fallback to system default" }
            ZoneId.systemDefault()
        }
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(atMillis), zone)
        val nowMinutes = now.hour * 60 + now.minute

        // UNLOCK 规则优先级高于 LOCK：用于"周末下午 2-4 点允许使用"这类白名单开窗
        val unlockHit = policy.rules
            .filter { it.action == ScheduleAction.UNLOCK }
            .firstOrNull { matches(it, now.toLocalDate(), nowMinutes) }
        if (unlockHit != null) {
            return ScheduleVerdict.Unlocked
        }

        val lockHit = policy.rules
            .filter { it.action == ScheduleAction.LOCK }
            .firstOrNull { matches(it, now.toLocalDate(), nowMinutes) }
            ?: return ScheduleVerdict.Unlocked

        val endsAt = nextBoundary(lockHit, now, zone)
        Logger.d(TAG) { "schedule LOCK hit: ${lockHit.start}-${lockHit.end}, until $endsAt" }
        return ScheduleVerdict.Locked(rule = lockHit, untilMillis = endsAt)
    }

    /**
     * 规则是否命中当前时刻。
     *
     * 跨零点规则会被检查两次：
     * - 作为"今天开始的区间"，看当前时刻是否 >= start；
     * - 作为"昨天开始、延续到今天的区间"，看当前时刻是否 < end 且**昨天**的 dayType 匹配。
     */
    private fun matches(rule: ScheduleRule, today: LocalDate, nowMinutes: Int): Boolean {
        val start = rule.startMinutes()
        val end = rule.endMinutes()

        if (!rule.crossesMidnight()) {
            return dayTypeMatches(rule, today) && nowMinutes >= start && nowMinutes < end
        }

        // 跨零点：今晚这一半
        if (dayTypeMatches(rule, today) && nowMinutes >= start) return true
        // 跨零点：昨晚延续到今天凌晨这一半，用昨天的 dayType 判断
        if (dayTypeMatches(rule, today.minusDays(1)) && nowMinutes < end) return true
        return false
    }

    private fun dayTypeMatches(rule: ScheduleRule, date: LocalDate): Boolean {
        val actual = dayTypeOf(date)
        if (actual in rule.dayTypes) return true
        // 节假日若未单独配置规则，退化按周末处理（符合"放假就是休息日"的常识）
        if (actual == DayType.HOLIDAY && DayType.WEEKEND in rule.dayTypes) return true
        return false
    }

    fun dayTypeOf(date: LocalDate): DayType = when {
        holidayProvider.isHoliday(date) -> DayType.HOLIDAY
        // 调休上班日：虽然是周六，但按工作日规则管控
        holidayProvider.isWorkdayOverride(date) -> DayType.WEEKDAY
        date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY -> DayType.WEEKEND
        else -> DayType.WEEKDAY
    }

    /**
     * 计算锁定结束的绝对时间戳。
     *
     * 上层用它设置一次精确闹钟，避免为了"到点解锁"而每秒轮询 ——
     * 秒级轮询在平板上是明显的耗电项，且息屏后会被 Doze 拖慢，反而不准。
     */
    private fun nextBoundary(rule: ScheduleRule, now: LocalDateTime, zone: ZoneId): Long {
        val end = rule.endMinutes()
        val nowMinutes = now.hour * 60 + now.minute
        val targetDate = if (rule.crossesMidnight() && nowMinutes >= rule.startMinutes()) {
            now.toLocalDate().plusDays(1)
        } else {
            now.toLocalDate()
        }
        val boundary = targetDate.atStartOfDay(zone).plusMinutes(end.toLong())
        return boundary.toInstant().toEpochMilli()
    }

    companion object {
        private const val TAG = "ScheduleEvaluator"
    }
}

sealed interface ScheduleVerdict {
    data object Unlocked : ScheduleVerdict

    /** [untilMillis] 为本次锁定的预计结束时间，用于设置精确闹钟与 UI 倒计时 */
    data class Locked(val rule: ScheduleRule, val untilMillis: Long) : ScheduleVerdict
}

/**
 * 节假日与调休判定。
 *
 * 之所以抽成接口：中国的法定节假日与调休安排每年由国务院公布，无法用算法推导，
 * 必须由服务端下发年度日历。早期阶段给一个空实现（只按自然周末判断），
 * 服务端日历接口就绪后替换实现即可，业务代码零改动。
 */
interface HolidayProvider {
    fun isHoliday(date: LocalDate): Boolean

    /** 调休上班日（如"周六补班"），此时应按工作日规则管控 */
    fun isWorkdayOverride(date: LocalDate): Boolean
}

/**
 * 默认实现：内置中国法定节假日表（2024-2026，依据国务院办公厅公开发文）。
 *
 * - 每年由国务院于前一年 11 月左右公布；新年份公布后在此 [BUILTIN] 追加即可。
 * - 未收录的年份按"非节假日"处理（返回 false）。[ScheduleEvaluator.dayTypeOf] 仍会按
 *   自然周末判周末，因此不会误把孩子锁在正常工作日，只是该年份的法定调休不生效。
 * - 服务端日历接口就绪后，用 [loadServerCalendar] 整体覆盖某一年的内置数据，
 *   无需改动业务代码（接口契约不变）。
 */
@Singleton
class DefaultHolidayProvider @Inject constructor() : HolidayProvider {

    override fun isHoliday(date: LocalDate): Boolean {
        val y = date.year
        // 该年有服务端日历则整体采用它（替换而非合并），否则回退内置表。
        overrides[y]?.let { return it.holidays.contains(date) }
        return BUILTIN[y]?.holidays?.contains(date) ?: false
    }

    override fun isWorkdayOverride(date: LocalDate): Boolean {
        val y = date.year
        overrides[y]?.let { return it.workdays.contains(date) }
        return BUILTIN[y]?.workdays?.contains(date) ?: false
    }

    /** 服务端下发年度日历后调用：整体替换该年的内置数据。 */
    fun loadServerCalendar(year: Int, holidays: Set<LocalDate>, workdays: Set<LocalDate>) {
        val merged = overrides.toMutableMap()
        merged[year] = YearCalendar(holidays, workdays)
        overrides = merged
    }

    private data class YearCalendar(val holidays: Set<LocalDate>, val workdays: Set<LocalDate>)

    @Volatile
    private var overrides: Map<Int, YearCalendar> = emptyMap()

    companion object {
        private val BUILTIN: Map<Int, YearCalendar> = mapOf(
            2024 to YearCalendar(
                holidays = setOf(
                    LocalDate.of(2024, 1, 1), // 元旦
                    *dateRange(2024, 2, 10, 17), // 春节 2/10-2/17
                    *dateRange(2024, 4, 4, 6),   // 清明 4/4-4/6
                    *dateRange(2024, 5, 1, 5),   // 劳动节 5/1-5/5
                    LocalDate.of(2024, 6, 10),   // 端午
                    *dateRange(2024, 9, 15, 17), // 中秋 9/15-9/17
                    *dateRange(2024, 10, 1, 7)   // 国庆 10/1-10/7
                ),
                workdays = setOf(
                    LocalDate.of(2024, 2, 4), LocalDate.of(2024, 2, 18),
                    LocalDate.of(2024, 4, 7),
                    LocalDate.of(2024, 4, 28), LocalDate.of(2024, 5, 11),
                    LocalDate.of(2024, 9, 14),
                    LocalDate.of(2024, 9, 29), LocalDate.of(2024, 10, 12)
                )
            ),
            2025 to YearCalendar(
                holidays = setOf(
                    LocalDate.of(2025, 1, 1),    // 元旦
                    *dateRange(2025, 1, 28, 31), // 春节 1/28-1/31
                    *dateRange(2025, 2, 1, 4),   // 春节 2/1-2/4
                    *dateRange(2025, 4, 4, 6),   // 清明 4/4-4/6
                    *dateRange(2025, 5, 1, 5),   // 劳动节 5/1-5/5
                    LocalDate.of(2025, 5, 31),   // 端午 5/31（连周末 6/1-6/2）
                    LocalDate.of(2025, 6, 1), LocalDate.of(2025, 6, 2),
                    *dateRange(2025, 10, 1, 8)   // 国庆中秋 10/1-10/8
                ),
                workdays = setOf(
                    LocalDate.of(2025, 1, 26), LocalDate.of(2025, 2, 8),
                    LocalDate.of(2025, 4, 27),
                    LocalDate.of(2025, 9, 28), LocalDate.of(2025, 10, 11)
                )
            ),
            2026 to YearCalendar(
                holidays = setOf(
                    *dateRange(2026, 1, 1, 3),   // 元旦 1/1-1/3
                    *dateRange(2026, 2, 15, 23), // 春节 2/15-2/23
                    *dateRange(2026, 4, 4, 6),   // 清明 4/4-4/6
                    *dateRange(2026, 5, 1, 5),   // 劳动节 5/1-5/5
                    *dateRange(2026, 6, 19, 21), // 端午 6/19-6/21
                    *dateRange(2026, 9, 25, 27), // 中秋 9/25-9/27
                    *dateRange(2026, 10, 1, 7)   // 国庆 10/1-10/7
                ),
                workdays = setOf(
                    LocalDate.of(2026, 1, 4),
                    LocalDate.of(2026, 2, 14), LocalDate.of(2026, 2, 28),
                    LocalDate.of(2026, 5, 9),
                    LocalDate.of(2026, 9, 20), LocalDate.of(2026, 10, 10)
                )
            )
        )

        /** 生成 [year]-[month]-[from] 到 [to] 的连续日期数组（含端点），供 spread 展开进 setOf。 */
        private fun dateRange(year: Int, month: Int, from: Int, to: Int): Array<LocalDate> =
            (from..to).map { LocalDate.of(year, month, it) }.toTypedArray()
    }
}
