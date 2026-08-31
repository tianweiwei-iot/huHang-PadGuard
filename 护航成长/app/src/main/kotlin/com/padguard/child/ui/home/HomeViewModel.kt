package com.padguard.child.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.core.data.repository.AuthRepository
import com.padguard.core.data.repository.PolicyRepository
import com.padguard.core.data.repository.LogRepository
import com.padguard.core.data.repository.UsageRepository
import com.padguard.core.data.model.policy.AppLimitRule
import com.padguard.core.data.model.policy.AppLimitPolicy
import com.padguard.core.data.model.policy.SchedulePolicy
import com.padguard.core.data.model.policy.ScheduleRule
import com.padguard.core.data.model.policy.ScheduleAction
import com.padguard.core.engine.PolicyEngine
import com.padguard.core.common.TimeProvider
import com.padguard.core.common.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

/**
 * 首页 ViewModel。
 *
 * 关键点：
 * - 今日使用时长 = max(策略下发的限额, 真实 UsageStats)，前者是上限，后者是实况。
 *   当 UsageStats 不可用时降级为策略下发值，避免首页空白。
 * - 计划时间轴 = 来自 SchedulePolicy.rules，按起止排序，
 *   标注当前指针（用服务端校准后的 now）。
 * - 最近拦截 = LogRepository 的拦截类日志（type 包含"APP_BLOCKED"/"URL_BLOCKED"），
 *   取最近 5 条。
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val policyRepository: PolicyRepository,
    private val usageRepository: UsageRepository,
    private val logRepository: LogRepository,
    private val policyEngine: PolicyEngine,
    private val timeProvider: TimeProvider
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                authRepository.studentName,
                policyRepository.observePolicy(),
                usageRepository.observeTodayUsageMinutes(),
                logRepository.observeRecentBlocks(limit = 5)
            ) { studentName, policy, usedMinutes, blocks ->
                val dailyQuota = policy.appLimit.dailyTotalMinutes
                val remaining = (dailyQuota - usedMinutes).coerceAtLeast(0)
                val nowMillis = timeProvider.now()
                HomeUiState(
                    studentName = studentName,
                    statusLabel = "在线",
                    isOnline = true,
                    usedMinutes = usedMinutes,
                    quotaMinutes = dailyQuota,
                    remainingMinutes = remaining,
                    nowLabel = formatNow(nowMillis, ZoneId.systemDefault()),
                    scheduleItems = buildScheduleSlots(policy.schedule, nowMillis),
                    recentBlocks = blocks.map {
                        RecentBlockUi(
                            appName = it.summary,
                            timeLabel = formatRelative(it.timestamp)
                        )
                    }
                )
            }
                .flowOn(Dispatchers.Default)
                .collect { _state.value = it }
        }
    }

    private fun buildScheduleSlots(schedule: SchedulePolicy, nowMillis: Long): List<ScheduleSlotUi> {
        if (schedule.rules.isEmpty()) return emptyList()
        val zone = runCatching { ZoneId.of(schedule.timezone) }
            .getOrElse {
                Logger.w("HomeViewModel") { "invalid timezone ${schedule.timezone}, fallback" }
                ZoneId.systemDefault()
            }
        val nowMinutes = LocalTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
            .toSecondOfDay() / 60
        return schedule.rules
            .sortedBy { it.startMinutes() }
            .map { rule ->
                val isLocked = rule.action == ScheduleAction.LOCK
                ScheduleSlotUi(
                    timeRange = "${rule.start} – ${rule.end}",
                    label = if (isLocked) "休息" else "可用",
                    isLocked = isLocked,
                    isCurrent = isCurrentSlot(rule, nowMinutes)
                )
            }
    }

    private fun isCurrentSlot(rule: ScheduleRule, nowMinutes: Int): Boolean {
        val start = rule.startMinutes()
        val end = rule.endMinutes()
        return if (rule.crossesMidnight()) {
            nowMinutes >= start || nowMinutes < end
        } else {
            nowMinutes in start until end
        }
    }

    private fun formatNow(millis: Long, zone: ZoneId): String {
        val time = LocalTime.ofInstant(Instant.ofEpochMilli(millis), zone)
        return "%02d:%02d".format(time.hour, time.minute)
    }

    private fun formatRelative(millis: Long): String {
        val diffMin = ((timeProvider.now() - millis) / 60_000L).coerceAtLeast(0)
        return when {
            diffMin < 1 -> "刚刚"
            diffMin < 60 -> "${diffMin} 分钟前"
            else -> "${diffMin / 60} 小时前"
        }
    }
}

/**
 * 首页 UI 状态。
 *
 * 全部是不可变数据，由 ViewModel 一次性组装好后下发到 Compose，
 * 避免 Composable 里再做重计算（影响首帧）。
 */
data class HomeUiState(
    val studentName: String = "",
    val statusLabel: String = "在线",
    val isOnline: Boolean = true,
    val usedMinutes: Int = 0,
    val quotaMinutes: Int = 0,
    val remainingMinutes: Int = 0,
    val nowLabel: String = "--:--",
    val scheduleItems: List<ScheduleSlotUi> = emptyList(),
    val recentBlocks: List<RecentBlockUi> = emptyList()
)

data class ScheduleSlotUi(
    val timeRange: String,
    val label: String,
    val isLocked: Boolean,
    val isCurrent: Boolean
)

data class RecentBlockUi(
    val appName: String,
    val timeLabel: String
)
