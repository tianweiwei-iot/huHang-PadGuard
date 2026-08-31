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
 * 必须由服务端下发年度日历。P0 阶段给一个空实现（只按自然周末判断），
 * 服务端日历接口就绪后替换实现即可，业务代码零改动。
 */
interface HolidayProvider {
    fun isHoliday(date: LocalDate): Boolean

    /** 调休上班日（如"周六补班"），此时应按工作日规则管控 */
    fun isWorkdayOverride(date: LocalDate): Boolean
}

/** P0 默认实现：不识别法定节假日，仅按自然周末判断 */
@Singleton
class DefaultHolidayProvider @Inject constructor() : HolidayProvider {
    override fun isHoliday(date: LocalDate): Boolean = false
    override fun isWorkdayOverride(date: LocalDate): Boolean = false
}
