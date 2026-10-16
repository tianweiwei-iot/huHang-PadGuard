package com.padguard.child.hidden

import com.padguard.child.usagesession.UsageSessionManager
import com.padguard.core.common.TimeProvider
import com.padguard.core.data.repository.PolicyRepository
import com.padguard.core.data.repository.UsageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 悬浮状态卡的数据源。
 *
 * 聚合「策略 + 真实用量 + 服务端权威会话」算出孩子最关心的两条基本信息：
 * **今日已用** 与 **剩余可用**。算法与 [com.padguard.child.ui.home.HomeViewModel] 保持同源，
 * 保证悬浮卡上的倒计时与锁机判定、家长端头条三者一致，避免"卡上还剩 10 分钟、设备却已锁"的矛盾。
 *
 * - 服务端会话激活时：剩余由会话基线（startEpoch / serverNow）派生，本地只做平滑；
 * - 会话未激活（离线 / 孩子尚未确认计时）：回退到本地策略额度 - 真实 UsageStats。
 */
data class UsageStatus(
    val usedMinutes: Int = 0,
    val quotaMinutes: Int = 0,
    val remainingMinutes: Int = 0
)

@Singleton
class UsageStatusProvider @Inject constructor(
    private val policyRepository: PolicyRepository,
    private val usageRepository: UsageRepository,
    private val usageSessionManager: UsageSessionManager,
    private val timeProvider: TimeProvider
) {
    val status: Flow<UsageStatus> = combine(
        policyRepository.observePolicy(),
        usageRepository.observeTodayUsageMinutes(),
        usageSessionManager.state
    ) { rawPolicy, usedMinutes, session ->
        val nowMillis = timeProvider.now()
        val zone = runCatching { ZoneId.of(rawPolicy.schedule.timezone) }
            .getOrElse { ZoneId.systemDefault() }
        val isWeekend = Instant.ofEpochMilli(nowMillis)
            .atZone(zone).toLocalDate().dayOfWeek.let {
                it == DayOfWeek.SATURDAY || it == DayOfWeek.SUNDAY
            }
        // 未成年人模式开启时额度走分龄派生值，与锁机判定同源。
        val policy = rawPolicy.copy(
            appLimit = rawPolicy.minorMode.coerceAppLimit(rawPolicy.appLimit)
        )

        val dailyQuota: Int
        val remaining: Int
        val used: Int
        if (session.active) {
            // 权威口径：剩余由服务端锚点派生，本地仅用单调时钟平滑插值。
            val liveRemaining = usageSessionManager.liveRemainingMs(nowMillis)
            dailyQuota = session.quotaMinutes
            remaining = (liveRemaining / 60_000L).toInt().coerceAtLeast(0)
            used = (dailyQuota - remaining).coerceAtLeast(0)
        } else {
            dailyQuota = policy.appLimit.quotaFor(isWeekend)
            remaining = (dailyQuota - usedMinutes).coerceAtLeast(0)
            used = usedMinutes
        }
        UsageStatus(usedMinutes = used, quotaMinutes = dailyQuota, remainingMinutes = remaining)
    }
        .flowOn(Dispatchers.Default)
        .distinctUntilChanged()
}
