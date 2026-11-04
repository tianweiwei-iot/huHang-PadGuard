package com.padguard.parent.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.parent.domain.model.ReportPeriod
import com.padguard.parent.domain.model.StatisticsReport
import com.padguard.parent.domain.model.UsageStats
import com.padguard.parent.domain.repository.AgeBand
import com.padguard.parent.domain.repository.AgeBandDefault
import com.padguard.parent.domain.repository.MinorMode
import com.padguard.parent.domain.repository.PolicyRepository
import com.padguard.parent.domain.repository.StatisticsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * 平板使用详情 ViewModel
 *
 * 周期窗口与服务端 [com.padguard.server.service.StatisticsService] 严格一致：
 * **均以"今天"为终点往前推**，不是自然周 / 自然月。
 * - daily：今天 1 天
 * - weekly：最近 7 天（含今天）
 * - monthly：最近 30 天（含今天）
 *
 * 限额参考线复用首页看板同一条口径 [computeEffectiveDailyLimitMinutes]：
 * - 「时间管控」总开关关闭且未成年人模式未开启 → 不限时，界面不展示"剩余/超额"；
 * - 周末按 `weekendLimitMinutes`、工作日按 `weekdayLimitMinutes`；
 * - 多天周期把窗口内每一天各自的生效额度累加，而不是简单"每日额度 × 天数"
 *   （周末 240 / 工作日 60 时，一周是 5×60 + 2×240，不是 7×60）。
 */
data class TabletUsageDetailUiState(
    val period: ReportPeriod = ReportPeriod.DAILY,
    val todayStats: UsageStats? = null,
    val report: StatisticsReport? = null,
    /** 「时间管控」总开关（settings.timeControlEnabled） */
    val limitEnabled: Boolean = true,
    /** 「时间管控」页设置的工作日额度（分钟） */
    val weekdayLimitMinutes: Int = 60,
    /** 「时间管控」页设置的周末额度（分钟） */
    val weekendLimitMinutes: Int = 60,
    /** 未成年人模式配置（开启时作为合规兜底，不夹逼家长已设额度） */
    val minorMode: MinorMode? = null,
    val ageBandDefaults: Map<AgeBand, AgeBandDefault> = emptyMap(),
    /**
     * 限额配置是否读取成功。
     * false 时禁止展示"剩余 / 已超额" —— 拿默认值顶上会给出孩子端并不存在的额度。
     */
    val limitKnown: Boolean = true,
    val isLoading: Boolean = false,
    val error: String? = null
) {

    /** 周期天数，与服务端 `days` 口径一致 */
    val periodDays: Int
        get() = when (period) {
            ReportPeriod.DAILY -> 1
            ReportPeriod.WEEKLY -> 7
            ReportPeriod.MONTHLY -> 30
        }

    /** 当前周期内的总使用秒数。 */
    val totalUsageSeconds: Int
        get() {
            val minutes = when (period) {
                ReportPeriod.DAILY -> todayStats?.totalUsageMinutes ?: 0
                else -> report?.totalUsageMinutes ?: 0
            }
            return minutes * 60
        }

    /**
     * 周期内第 [daysAgo] 天（0 = 今天）实际生效的每日上限（分钟）。
     * 周末判定用 Asia/Shanghai 自然日，与孩子端 / 服务端一致。
     */
    fun dailyLimitMinutesAt(daysAgo: Int): Int {
        val date = LocalDate.now(WEEKDAY_ZONE).minusDays(daysAgo.toLong())
        val isWeekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
        return computeEffectiveDailyLimitMinutes(
            enabled = limitEnabled,
            weekdayLimitMinutes = weekdayLimitMinutes,
            weekendLimitMinutes = weekendLimitMinutes,
            minorMode = minorMode,
            ageBandDefaults = ageBandDefaults,
            isWeekend = isWeekend
        )
    }

    /** 当前周期对应的"周期限额"（秒）：窗口内每一天的生效额度逐日累加。 */
    val periodLimitSeconds: Int
        get() {
            if (!limitKnown) return 0
            var minutes = 0
            for (daysAgo in 0 until periodDays) minutes += dailyLimitMinutesAt(daysAgo)
            return minutes * 60
        }

    /** 是否真的存在限额；false 表示不限时（总开关关闭且未成年人模式未开启）或限额读取失败。 */
    val limitApplies: Boolean
        get() = limitKnown && periodLimitSeconds > 0

    /** 剩余秒数（>=0）；不限时恒为 0。 */
    val remainingSeconds: Int
        get() = if (!limitApplies) 0 else (periodLimitSeconds - totalUsageSeconds).coerceAtLeast(0)

    /** 已超出秒数（>0 时表示超额）；不限时恒为 0。 */
    val overLimitSeconds: Int
        get() = if (!limitApplies) 0 else (totalUsageSeconds - periodLimitSeconds).coerceAtLeast(0)

    val overLimit: Boolean
        get() = limitApplies && totalUsageSeconds > periodLimitSeconds

    private companion object {
        val WEEKDAY_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    }
}

@HiltViewModel
class TabletUsageDetailViewModel @Inject constructor(
    private val statisticsRepository: StatisticsRepository,
    private val policyRepository: PolicyRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TabletUsageDetailUiState())
    val uiState: StateFlow<TabletUsageDetailUiState> = _uiState.asStateFlow()

    private var currentDeviceId: String = ""

    fun load(deviceId: String) {
        currentDeviceId = deviceId
        reload()
    }

    fun retry() {
        reload()
    }

    fun setPeriod(period: ReportPeriod) {
        if (_uiState.value.period == period) return
        _uiState.value = _uiState.value.copy(period = period)
        reload()
    }

    private fun reload() {
        val deviceId = currentDeviceId
        if (deviceId.isEmpty()) return
        val period = _uiState.value.period

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            // 限额参考线改用「时间管控」页设置（权威来源），与首页看板 / 孩子端锁机一致；
            // 同时取未成年人模式与分龄默认值，保证"周末额度""管控开关""合规兜底"三项口径都不丢。
            val limitDeferred = async { policyRepository.getTabletUsageSettings(deviceId) }
            val minorDeferred = async { policyRepository.getMinorMode(deviceId) }
            val defaultsDeferred = async { policyRepository.getAgeBandDefaults() }

            val todayDeferred = async {
                if (period == ReportPeriod.DAILY) statisticsRepository.getTodayUsage(deviceId) else null
            }
            val reportDeferred = async {
                if (period != ReportPeriod.DAILY) statisticsRepository.getUsageStats(deviceId, period) else null
            }

            val limitResult = limitDeferred.await()
            val minorResult = minorDeferred.await()
            val defaultsResult = defaultsDeferred.await()
            val todayResult = todayDeferred.await()
            val reportResult = reportDeferred.await()

            // 乱序防线：A→B 快速切换时，A 的响应会覆盖 B 的页面
            if (currentDeviceId != deviceId) return@launch

            var error: String? = null
            todayResult?.onFailure { error = it.message }
            reportResult?.onFailure { error = error ?: it.message }
            val limit = limitResult.getOrNull()
            if (limit == null && limitResult.isFailure) {
                error = error ?: "限额配置读取失败，请下拉重试"
            }

            _uiState.value = _uiState.value.copy(
                todayStats = todayResult?.getOrNull(),
                report = reportResult?.getOrNull(),
                limitEnabled = limit?.enabled ?: true,
                weekdayLimitMinutes = limit?.weekdayLimitMinutes ?: 60,
                weekendLimitMinutes = limit?.weekendLimitMinutes ?: 60,
                minorMode = minorResult.getOrNull(),
                ageBandDefaults = defaultsResult.getOrNull().orEmpty(),
                limitKnown = limit != null,
                isLoading = false,
                error = error
            )
        }
    }
}
