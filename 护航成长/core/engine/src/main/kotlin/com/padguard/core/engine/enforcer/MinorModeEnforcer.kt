package com.padguard.core.engine.enforcer

import com.padguard.core.common.Logger
import com.padguard.core.common.TimeProvider
import com.padguard.core.data.model.policy.DayType
import com.padguard.core.data.model.policy.MinorModePolicy
import com.padguard.core.data.model.policy.PolicyPackage
import com.padguard.core.data.model.policy.ScheduleAction
import com.padguard.core.data.model.policy.ScheduleRule
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 未成年人模式执行器（P1 合规底座）。
 *
 * ## 它做三件事
 * 1. **派生基线**：把分龄默认值压到 [PolicyPackage.appLimit] / [PolicyPackage.eyeCare] 上，
 *    让"一键开启"不需要家长再去几个页面各配一遍（见 [deriveBaseline]）。
 * 2. **夜间宵禁判定**：每日 22:00–06:00（按档位可能更早）不提供服务（见 [evaluate]）。
 * 3. **家长豁免**：家长在控制端临时放行时，宵禁让路但**每日总时长不让路** ——
 *    时长是合规硬约束，豁免只解决"今晚特殊情况"，不能突破额度。
 *
 * ## 为什么宵禁不复用 [com.padguard.core.engine.schedule.ScheduleEvaluator]
 * 时段锁是家长**自定义**规则，宵禁是分龄**派生**的强制规则。若把宵禁塞进
 * `schedule.rules` 交给时段执行器判定，会出现一个真实故障：
 * 家长在控制端关掉「时段管控」总开关后该执行器直接返回 Unlocked，
 * 宵禁被一起关掉 —— 合规项被一个家长开关静默解除。
 * 因此宵禁由本类独立判定，与家长时段规则互不干扰；
 * 需要展示时由 [curfewRuleOf] 现算一条规则，不落进策略包。
 */
@Singleton
class MinorModeEnforcer @Inject constructor(
    private val timeProvider: TimeProvider
) {

    /**
     * 判定当前是否处于宵禁。
     *
     * @param atMillis 判定时刻，默认取服务端校准后的墙钟（与"晚上 10 点"语义一致）
     */
    fun evaluate(minorMode: MinorModePolicy, atMillis: Long = timeProvider.now()): MinorModeVerdict {
        if (!minorMode.enabled) return MinorModeVerdict.Off
        if (!minorMode.curfewEnabled) return MinorModeVerdict.Active

        // 家长豁免优先于一切自动判定：豁免期内的宵禁与护眼都不再生效，
        // 但每日总时长由 appLimit 独立计算，不受这里影响（有意而为）。
        if (minorMode.isExempt(atMillis)) {
            Logger.i(TAG) { "curfew suppressed by parent exemption until ${minorMode.parentExemptUntil}" }
            return MinorModeVerdict.Active
        }

        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(atMillis), ZoneId.systemDefault())
        val nowMinutes = now.hour * 60 + now.minute
        val start = ScheduleRule.toMinutes(minorMode.effectiveCurfewStart())
        val end = ScheduleRule.toMinutes(minorMode.effectiveCurfewEnd())

        val inCurfew = if (start == end) {
            // 起止相同视为全天宵禁（异常配置，按最严格处理）
            true
        } else if (end <= start) {
            // 跨零点：22:00–06:00 → 今夜段 [22:00,24:00) 或 凌晨段 [00:00,06:00)
            nowMinutes >= start || nowMinutes < end
        } else {
            // 同一天内的区间（如 01:00–05:00），按普通区间处理
            nowMinutes >= start && nowMinutes < end
        }

        if (!inCurfew) return MinorModeVerdict.Active

        val endDateTime = if (end <= start && nowMinutes >= start) {
            now.toLocalDate().plusDays(1).atTime(end / 60, end % 60)
        } else {
            now.toLocalDate().atTime(end / 60, end % 60)
        }
        val untilMillis = endDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        return MinorModeVerdict.Curfew(
            start = minorMode.effectiveCurfewStart(),
            end = minorMode.effectiveCurfewEnd(),
            ageBand = minorMode.ageBand,
            untilMillis = untilMillis
        )
    }

    /**
     * 派生分龄基线后的策略包。
     *
     * 只在 [MinorModePolicy.enabled] 为真时改写；关闭时原样返回，
     * 保证"退出未成年人模式"能干净地回到家长自定义配置，不留残留限制。
     */
    fun deriveBaseline(policy: PolicyPackage): PolicyPackage {
        val minor = policy.minorMode
        if (!minor.enabled) return policy

        val appLimit = minor.coerceAppLimit(policy.appLimit)
        val eyeCare = minor.coerceEyeCare(policy.eyeCare)

        Logger.i(TAG) {
            "minor mode ON: band=${minor.ageBand.label}, " +
                "daily=${appLimit.quotaFor(false)}min, weekend=${appLimit.quotaFor(true)}min, " +
                "continuous=${eyeCare.continuousMinutes}min/rest=${eyeCare.restMinutes}min, " +
                "curfew=${minor.effectiveCurfewStart()}-${minor.effectiveCurfewEnd()}"
        }
        return policy.copy(appLimit = appLimit, eyeCare = eyeCare)
    }

    /**
     * 把当前档位的宵禁表达成一条 [ScheduleRule]，供端上 UI 展示。
     *
     * **不写进 [PolicyPackage.schedule].rules**：那条链表由家长在控制端维护，
     * 往里塞派生项会导致家长下次保存时被原样回传、越积越多，
     * 而且家长删掉它后台又会重新长出来，表现为"删不掉的时段"。
     * 宵禁的执行完全走 [evaluate]，与家长时段规则互不干扰。
     */
    fun curfewRuleOf(minor: MinorModePolicy): ScheduleRule? {
        if (!minor.enabled || !minor.curfewEnabled) return null
        return ScheduleRule(
            dayTypes = listOf(DayType.WEEKDAY, DayType.WEEKEND, DayType.HOLIDAY),
            start = minor.effectiveCurfewStart(),
            end = minor.effectiveCurfewEnd(),
            action = ScheduleAction.LOCK
        )
    }

    companion object {
        private const val TAG = "MinorModeEnforcer"
    }
}

/**
 * 宵禁判定结果。
 *
 * [Active] 表示"未成年人模式开启但当前不在宵禁时段"，
 * 与 [Off]（模式未开启）区分开，便于 UI 展示"已开启"状态而不误判为锁定。
 */
sealed interface MinorModeVerdict {
    /** 未成年人模式未开启 */
    data object Off : MinorModeVerdict

    /** 未成年人模式开启，当前不在宵禁时段（或已获家长豁免） */
    data object Active : MinorModeVerdict

    /** 当前处于宵禁时段 */
    data class Curfew(
        val start: String,
        val end: String,
        val ageBand: com.padguard.core.data.model.policy.AgeBand,
        val untilMillis: Long
    ) : MinorModeVerdict {
        val text: String
            get() = "夜间休息时段 $start–$end 不提供服务（${ageBand.label}）"
    }
}
