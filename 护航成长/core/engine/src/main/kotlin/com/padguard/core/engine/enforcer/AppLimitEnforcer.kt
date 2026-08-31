package com.padguard.core.engine.enforcer

import com.padguard.core.common.Logger
import com.padguard.core.common.TimeProvider
import com.padguard.core.data.model.policy.AppLimitPolicy
import com.padguard.core.data.model.policy.AppLimitRule
import com.padguard.core.data.model.policy.DayType
import com.padguard.core.data.repository.UsageRepository
import com.padguard.core.engine.schedule.ScheduleEvaluator
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用时长与次数限额。
 *
 * ## 计时基准：为什么一行墙钟都不能用
 * 限额管控是被绕过最频繁的功能，孩子的第一反应就是改系统时间。
 * 所以这里的规则是**绝对的**：
 *
 * | 用途 | 时钟 | 原因 |
 * |---|---|---|
 * | 累计时长（本类核心） | `elapsedRealtime` 差值 | 单调递增，改时间无效，且含深度睡眠 |
 * | 日切 dayKey | [TimeProvider.now]（服务端校准墙钟） | "今天"是墙钟概念，但取校准值而非本地值 |
 * | 单次会话时长 | `elapsedRealtime` 差值 | 同上 |
 *
 * 注意日切仍依赖墙钟 —— 这是无法回避的（"每日 2 小时"本身就是墙钟语义）。
 * 缓解手段有三层：① 用服务端校准时间；② [com.padguard.core.engine.enforcer.SystemLockEnforcer]
 * 强制自动对时并禁止手改；③ [com.padguard.core.engine.guard.TamperDetector] 检测到跳变即告警。
 * 即便日切被改，**已累计的时长也不会因此清零**（数据按 dayKey 分行存储，改回来照样能查到）。
 *
 * ## 采样而非事件驱动
 * Android 没有可靠的"应用前后台切换"公开广播（UsageStats 有延迟、无障碍事件会被杀）。
 * 因此采用**定时采样**：由前台服务每 [SAMPLE_INTERVAL_MS] 调一次 [track]，
 * 把「上次采样到本次采样之间的时间」记到当时的前台应用头上。
 *
 * 采样有个必须处理的边界：息屏、Doze、进程被杀重启都会让两次采样间隔远超预期。
 * 若直接累加就会出现"睡了一夜，微信被记了 8 小时"。所以这里对单次增量做上限截断
 * （[MAX_DELTA_MS]），并且息屏期间不计时。
 */
@Singleton
class AppLimitEnforcer @Inject constructor(
    private val usageRepository: UsageRepository,
    private val timeProvider: TimeProvider,
    private val scheduleEvaluator: ScheduleEvaluator
) {

    /** 上次采样的单调时钟点；-1 表示尚未开始采样 */
    private var lastSampleElapsed: Long = -1L

    /** 上次采样时的前台包名，用于识别应用切换（进而累计启动次数） */
    private var lastForegroundPackage: String? = null

    /** 当前会话（连续前台）的起始单调时钟点 */
    private var sessionStartElapsed: Long = 0L

    /** 当前日期键，用于跨日重置会话与提醒去重 */
    private var currentDayKey: String = ""

    /** 已提醒过的阈值，避免每次采样都弹提醒。key = "$pkg|$remainingMinutes" */
    private val notifiedReminders = mutableSetOf<String>()

    /**
     * 采样一次并给出限额判定。
     *
     * @param policy 限额策略
     * @param foregroundPackage 当前前台应用包名；null 表示未知（此时只累计全局时长）
     * @param screenOn 屏幕是否点亮 —— 息屏不计时，否则平板放着不动也会耗尽额度
     */
    suspend fun track(
        policy: AppLimitPolicy,
        foregroundPackage: String?,
        screenOn: Boolean
    ): LimitVerdict {
        val nowElapsed = timeProvider.elapsedRealtime()
        val nowWall = timeProvider.now()
        val dayKey = usageRepository.dayKey(nowWall)

        if (dayKey != currentDayKey) {
            onDayRollover(dayKey)
        }

        val delta = computeDelta(nowElapsed)
        lastSampleElapsed = nowElapsed

        if (!screenOn) {
            // 息屏：结束当前会话计时，但保留当日累计值
            lastForegroundPackage = null
            sessionStartElapsed = 0L
            return LimitVerdict.Allowed
        }

        // 应用切换：为新应用累计一次启动，并重置单次会话计时
        if (foregroundPackage != null && foregroundPackage != lastForegroundPackage) {
            usageRepository.addAppLaunch(foregroundPackage, nowWall)
            sessionStartElapsed = nowElapsed
            Logger.v(TAG) { "foreground switched to $foregroundPackage" }
        }
        lastForegroundPackage = foregroundPackage

        if (delta > 0) {
            usageRepository.addTotalUsage(delta, nowWall)
            foregroundPackage?.let { usageRepository.addAppUsage(it, delta, nowWall) }
        }

        return evaluate(policy, foregroundPackage, dayKey, nowElapsed)
    }

    /**
     * 只判定不计时，供锁屏页倒计时刷新、UI 展示等只读场景使用。
     */
    suspend fun evaluate(
        policy: AppLimitPolicy,
        foregroundPackage: String?,
        dayKey: String = usageRepository.dayKey(timeProvider.now()),
        nowElapsed: Long = timeProvider.elapsedRealtime()
    ): LimitVerdict {
        // 每日总时长优先级最高：总额耗尽后任何应用都不该继续使用
        if (policy.dailyTotalMinutes > 0) {
            val usedMs = usageRepository.getTotalUsage(dayKey)
            val quotaMs = policy.dailyTotalMinutes * 60_000L
            if (usedMs >= quotaMs) {
                return LimitVerdict.Blocked(
                    packageName = "",
                    reason = LimitReason.DAILY_TOTAL,
                    usedMinutes = (usedMs / 60_000L).toInt(),
                    quotaMinutes = policy.dailyTotalMinutes
                )
            }
            reminderFor(policy, "", quotaMs - usedMs)?.let { return it }
        }

        val pkg = foregroundPackage ?: return LimitVerdict.Allowed
        val rule = policy.rules.firstOrNull { it.packageName == pkg } ?: return LimitVerdict.Allowed
        if (!ruleAppliesToday(rule)) return LimitVerdict.Allowed

        val stat = usageRepository.getAppUsage(pkg, dayKey)
        val usedMs = stat?.usedMs ?: 0L
        val launchCount = stat?.launchCount ?: 0

        // 当日累计限额
        if (rule.dailyMinutes > 0) {
            val quotaMs = rule.dailyMinutes * 60_000L
            if (usedMs >= quotaMs) {
                return LimitVerdict.Blocked(
                    packageName = pkg,
                    reason = LimitReason.APP_DAILY,
                    usedMinutes = (usedMs / 60_000L).toInt(),
                    quotaMinutes = rule.dailyMinutes
                )
            }
            reminderFor(policy, pkg, quotaMs - usedMs)?.let { return it }
        }

        // 单次连续使用限额（如"每次最多玩 30 分钟"）
        if (rule.singleMinutes > 0 && sessionStartElapsed > 0) {
            val sessionMs = nowElapsed - sessionStartElapsed
            if (sessionMs >= rule.singleMinutes * 60_000L) {
                return LimitVerdict.Blocked(
                    packageName = pkg,
                    reason = LimitReason.APP_SINGLE,
                    usedMinutes = (sessionMs / 60_000L).toInt(),
                    quotaMinutes = rule.singleMinutes
                )
            }
        }

        // 当日启动次数限额
        if (rule.dailyLaunchCount > 0 && launchCount > rule.dailyLaunchCount) {
            return LimitVerdict.Blocked(
                packageName = pkg,
                reason = LimitReason.APP_LAUNCH_COUNT,
                usedMinutes = launchCount,
                quotaMinutes = rule.dailyLaunchCount
            )
        }

        return LimitVerdict.Allowed
    }

    /**
     * 剩余额度提醒。
     *
     * 每个阈值每天每应用只提醒一次 —— 采样是每 15 秒一次，不去重会变成疯狂弹窗。
     * 提醒本身不阻断使用，让孩子有"收尾"的机会（正在做的题至少能写完），
     * 这是产品体验上的重要取舍：直接黑屏会引发强烈对抗情绪。
     */
    private fun reminderFor(policy: AppLimitPolicy, pkg: String, remainingMs: Long): LimitVerdict? {
        val remainingMinutes = (remainingMs / 60_000L).toInt()
        val threshold = policy.reminderMinutes
            .filter { it > 0 }
            .sorted()
            .firstOrNull { remainingMinutes <= it }
            ?: return null

        val key = "$pkg|$threshold"
        if (!notifiedReminders.add(key)) return null
        Logger.i(TAG) { "limit reminder: pkg=$pkg, remaining=${remainingMinutes}min" }
        return LimitVerdict.Warning(pkg, remainingMinutes.coerceAtLeast(0))
    }

    /**
     * 规则今天是否适用。
     *
     * 复用 [ScheduleEvaluator.dayTypeOf] 而不是自己判周末：节假日/调休的判定逻辑
     * 必须与时段锁机完全一致，否则会出现"时段锁按节假日放行、限额却按工作日计算"的错乱。
     */
    private fun ruleAppliesToday(rule: AppLimitRule): Boolean {
        val today = Instant.ofEpochMilli(timeProvider.now()).atZone(ZoneId.systemDefault()).toLocalDate()
        val dayType = scheduleEvaluator.dayTypeOf(today)
        if (dayType in rule.dayTypes) return true
        // 与 ScheduleEvaluator 保持一致：未单独配置节假日时退化按周末处理
        return dayType == DayType.HOLIDAY && DayType.WEEKEND in rule.dayTypes
    }

    /**
     * 计算本次采样的有效增量。
     *
     * 三种异常必须挡住：
     * - 首次采样（lastSampleElapsed < 0）：无基准，本次不计时；
     * - 设备重启（nowElapsed < lastSampleElapsed）：单调时钟归零，重新起算；
     * - 长间隔（Doze / 进程被杀 / 息屏）：截断到 [MAX_DELTA_MS]，避免把休眠时间算成使用时间。
     */
    private fun computeDelta(nowElapsed: Long): Long {
        val last = lastSampleElapsed
        if (last < 0) return 0L
        val delta = nowElapsed - last
        if (delta < 0) {
            Logger.w(TAG) { "monotonic clock went backwards (reboot?), resetting baseline" }
            return 0L
        }
        return delta.coerceAtMost(MAX_DELTA_MS)
    }

    private fun onDayRollover(newDayKey: String) {
        Logger.i(TAG) { "day rollover: $currentDayKey -> $newDayKey" }
        currentDayKey = newDayKey
        notifiedReminders.clear()
        sessionStartElapsed = timeProvider.elapsedRealtime()
    }

    /** 重启或权限重置后调用，丢弃采样基准，防止把关机时间算进使用时长 */
    fun resetSampling() {
        lastSampleElapsed = -1L
        lastForegroundPackage = null
        sessionStartElapsed = 0L
    }

    /** 家长手动"重置今日额度"时调用（需服务端指令授权），仅清提醒去重标记 */
    fun clearReminderCache() = notifiedReminders.clear()

    companion object {
        private const val TAG = "AppLimitEnforcer"

        /** 建议采样间隔：15 秒。再密耗电明显，再疏则限额误差过大 */
        const val SAMPLE_INTERVAL_MS = 15_000L

        /**
         * 单次采样最大计入时长：60 秒。
         * 超出部分视为休眠/进程重启的空档而丢弃 —— 宁可少计，不可多计。
         */
        const val MAX_DELTA_MS = 60_000L
    }
}

/** 限额判定结果 */
sealed interface LimitVerdict {
    data object Allowed : LimitVerdict

    /** 额度即将耗尽的提醒，不阻断使用 */
    data class Warning(val packageName: String, val remainingMinutes: Int) : LimitVerdict

    data class Blocked(
        val packageName: String,
        val reason: LimitReason,
        val usedMinutes: Int,
        val quotaMinutes: Int
    ) : LimitVerdict {
        /** 展示给孩子的文案，避免 UI 层散落 when 分支 */
        fun message(): String = when (reason) {
            LimitReason.DAILY_TOTAL -> "今日总使用时长已达上限 ${quotaMinutes} 分钟"
            LimitReason.APP_DAILY -> "该应用今日可用时长已用完（${quotaMinutes} 分钟）"
            LimitReason.APP_SINGLE -> "单次连续使用已达 ${quotaMinutes} 分钟，先休息一下"
            LimitReason.APP_LAUNCH_COUNT -> "该应用今日打开次数已达上限（${quotaMinutes} 次）"
        }
    }
}

enum class LimitReason { DAILY_TOTAL, APP_DAILY, APP_SINGLE, APP_LAUNCH_COUNT }
