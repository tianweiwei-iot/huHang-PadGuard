package com.padguard.core.engine.enforcer

import com.padguard.core.common.Logger
import com.padguard.core.common.TimeProvider
import com.padguard.core.data.model.policy.EyeCarePolicy
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 护眼强制休息。
 *
 * ## 什么算"连续使用"
 * 说明书写的是"连续使用 40 分钟强制休息 10 分钟"，但"连续"的边界必须自己定清楚，
 * 否则会出现两种都很糟的实现：
 *
 * - **太严**：息屏 10 秒就清零连续时长 → 孩子学会每隔几分钟按一下电源键，功能形同虚设；
 * - **太松**：只要不重启就一直累加 → 中午睡了两小时起来还是被判定"连续使用"，用户体验崩坏。
 *
 * 本实现的判定：息屏累计达到 [EyeCarePolicy.restMinutes] 才算完成一次有效休息并清零，
 * 短暂息屏（接水、上厕所）只是暂停计时而不清零。这与"眼睛需要真正休息"的医学意图一致，
 * 也堵掉了按电源键刷时长的漏洞。
 *
 * ## 计时基准
 * 全部使用 [TimeProvider.elapsedRealtime]。休息倒计时尤其关键 ——
 * 若用墙钟，把时间往前调 10 分钟就能秒过休息期。
 */
@Singleton
class EyeCareEnforcer @Inject constructor(
    private val timeProvider: TimeProvider
) {

    /** 本轮连续使用累计毫秒（亮屏期间累加） */
    private var continuousUsedMs: Long = 0L

    /** 息屏累计毫秒，达到 restMinutes 即视为完成有效休息 */
    private var screenOffAccumulatedMs: Long = 0L

    /** 强制休息结束的单调时钟点；0 表示不在休息中 */
    private var restUntilElapsed: Long = 0L

    private var lastSampleElapsed: Long = -1L

    /**
     * 采样一次并给出护眼判定。由前台服务与 [AppLimitEnforcer.track] 同频调用。
     */
    fun track(policy: EyeCarePolicy, screenOn: Boolean): EyeCareVerdict {
        if (!policy.enabled) {
            reset()
            return EyeCareVerdict.Idle
        }

        val nowElapsed = timeProvider.elapsedRealtime()
        val delta = computeDelta(nowElapsed)
        lastSampleElapsed = nowElapsed

        // 正在强制休息中
        if (restUntilElapsed > 0) {
            if (nowElapsed >= restUntilElapsed) {
                Logger.i(TAG) { "rest finished, counters cleared" }
                restUntilElapsed = 0L
                continuousUsedMs = 0L
                screenOffAccumulatedMs = 0L
                return EyeCareVerdict.Idle
            }
            val remainingSec = ((restUntilElapsed - nowElapsed) / 1000L).toInt()
            return EyeCareVerdict.Resting(
                remainingSeconds = remainingSec,
                forceLock = policy.forceLockDuringRest,
                untilMillis = timeProvider.now() + (restUntilElapsed - nowElapsed)
            )
        }

        if (!screenOn) {
            screenOffAccumulatedMs += delta
            // 息屏时间足够长 → 认定为有效休息，连续时长清零
            if (screenOffAccumulatedMs >= policy.restMinutes * 60_000L) {
                if (continuousUsedMs > 0) {
                    Logger.i(TAG) { "natural rest detected (${screenOffAccumulatedMs / 60000}min), counters cleared" }
                }
                continuousUsedMs = 0L
                screenOffAccumulatedMs = 0L
            }
            return EyeCareVerdict.Idle
        }

        // 亮屏：息屏累计作废（短暂息屏不算休息），继续累加连续使用
        screenOffAccumulatedMs = 0L
        continuousUsedMs += delta

        val thresholdMs = policy.continuousMinutes * 60_000L
        if (continuousUsedMs >= thresholdMs) {
            restUntilElapsed = nowElapsed + policy.restMinutes * 60_000L
            Logger.i(TAG) {
                "eye care triggered after ${continuousUsedMs / 60000}min, rest ${policy.restMinutes}min"
            }
            return EyeCareVerdict.Resting(
                remainingSeconds = policy.restMinutes * 60,
                forceLock = policy.forceLockDuringRest,
                untilMillis = timeProvider.now() + policy.restMinutes * 60_000L
            )
        }

        val usedMinutes = (continuousUsedMs / 60_000L).toInt()
        val remainingMinutes = policy.continuousMinutes - usedMinutes
        // 剩 5 分钟起给出预告，让孩子有心理准备再收尾，减少对抗
        return if (remainingMinutes in 1..PRE_NOTICE_MINUTES) {
            EyeCareVerdict.Approaching(usedMinutes, remainingMinutes)
        } else {
            EyeCareVerdict.Working(usedMinutes)
        }
    }

    /** 家长确认"已休息"或远程指令提前结束休息时调用 */
    fun skipRest() {
        restUntilElapsed = 0L
        continuousUsedMs = 0L
        screenOffAccumulatedMs = 0L
        Logger.i(TAG) { "rest skipped by authorization" }
    }

    fun reset() {
        continuousUsedMs = 0L
        screenOffAccumulatedMs = 0L
        restUntilElapsed = 0L
        lastSampleElapsed = -1L
    }

    /** 与 [AppLimitEnforcer.computeDelta] 同规则：首采样、重启、超长间隔都要挡住 */
    private fun computeDelta(nowElapsed: Long): Long {
        val last = lastSampleElapsed
        if (last < 0) return 0L
        val delta = nowElapsed - last
        if (delta < 0) return 0L
        return delta.coerceAtMost(AppLimitEnforcer.MAX_DELTA_MS)
    }

    companion object {
        private const val TAG = "EyeCareEnforcer"
        private const val PRE_NOTICE_MINUTES = 5
    }
}

sealed interface EyeCareVerdict {
    /** 未启用、或刚完成休息 */
    data object Idle : EyeCareVerdict

    data class Working(val continuousMinutes: Int) : EyeCareVerdict

    /** 即将触发强制休息的预告 */
    data class Approaching(val continuousMinutes: Int, val remainingMinutes: Int) : EyeCareVerdict

    /** 强制休息中。[forceLock] 为 true 时应锁屏，否则仅弹提示 */
    data class Resting(
        val remainingSeconds: Int,
        val forceLock: Boolean,
        val untilMillis: Long
    ) : EyeCareVerdict
}
