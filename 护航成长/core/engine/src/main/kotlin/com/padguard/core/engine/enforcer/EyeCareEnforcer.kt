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

    /** 休息开始时的墙钟毫秒（服务端校准）；0 表示未在休息中。用于核算实际休息时长 */
    private var restStartedWallEpoch: Long = 0L

    /** 本次休息的唯一标识（= 休息开始墙钟），随上报送达服务端做幂等去重 */
    private var restId: String? = null

    /** 本次休息的时长上限（毫秒）= restMinutes * 60000，核算实际休息时长时做上钳制 */
    private var restCapMs: Long = 0L

    /**
     * 休息结束后待上报的 (restId, 实际休息毫秒)。
     * 由 [PolicyEngine.tick] 取出经 [EngineTick] 上抛，[GuardService] 负责异步上报服务端，
     * 服务端据此从每日总时长中扣减——这是"休息不计入每日总时长"在权威口径上的落地。
     * 取走即清空，避免重复上报。
     */
    private var pendingRestReport: Pair<String, Long>? = null

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
                // 自然结束：核算实际休息时长并暂存为待上报事件（供服务端从每日总额度扣减）
                finalizeRestIfActive()
                Logger.i(TAG) { "rest finished, counters cleared" }
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
            // 记录休息开始墙钟、唯一标识与时长上限，供结束时核算实际休息并上报服务端。
            // restId 用墙钟时间戳，天然唯一且可幂等去重；restCapMs 用于上钳制防异常值。
            restStartedWallEpoch = timeProvider.now()
            restId = restStartedWallEpoch.toString()
            restCapMs = policy.restMinutes * 60_000L
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
        // 提前结束也算一次真实休息，核算实际休息时长并暂存上报事件，再清零
        finalizeRestIfActive()
        Logger.i(TAG) { "rest skipped by authorization" }
    }

    fun reset() {
        // 策略被关闭/重载导致的清零：此时本就无日总时长管控，静默清零即可（不上报，避免噪音）
        continuousUsedMs = 0L
        screenOffAccumulatedMs = 0L
        restUntilElapsed = 0L
        restStartedWallEpoch = 0L
        restId = null
        restCapMs = 0L
        pendingRestReport = null
        lastSampleElapsed = -1L
    }

    /**
     * 休息结束收尾：若正处于强制休息，核算「实际休息毫秒」（墙钟差值，钳制到 [0, restCapMs]），
     * 暂存为待上报事件；随后清零所有休息相关状态。
     *
     * 实际休息时长以墙钟差值核算（而非单调时钟），因为休息窗口要映射到服务端权威会话的
     * 墙钟计时轴上才能被正确扣减；单调时钟受重启归零影响，不能直接换算成"已被计入总额度的墙钟时长"。
     */
    private fun finalizeRestIfActive() {
        if (restUntilElapsed > 0L && restId != null) {
            val actualMs = (timeProvider.now() - restStartedWallEpoch)
                .coerceAtLeast(0L)
                .coerceAtMost(restCapMs)
            if (actualMs > 0L) {
                pendingRestReport = (restId!! to actualMs)
            }
        }
        restUntilElapsed = 0L
        continuousUsedMs = 0L
        screenOffAccumulatedMs = 0L
        restStartedWallEpoch = 0L
        restId = null
        restCapMs = 0L
    }

    /** 取出并清空待上报的休息事件（restId, 实际休息毫秒）；无则返回 null */
    fun consumePendingRestReport(): Pair<String, Long>? {
        val r = pendingRestReport
        pendingRestReport = null
        return r
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
