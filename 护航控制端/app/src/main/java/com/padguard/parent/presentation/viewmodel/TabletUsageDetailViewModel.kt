package com.padguard.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.domain.model.ReportPeriod
import com.padguard.domain.model.StatisticsReport
import com.padguard.domain.model.UsageStats
import com.padguard.domain.repository.PolicyRepository
import com.padguard.domain.repository.StatisticsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 平板使用详情 ViewModel
 *
 * 支持按天/周/月聚合查询：
 * - 按天：今日用量 + 每日限额
 * - 按周：本周 7 天汇总 + 周限额（每日限额 × 7）
 * - 按月：本周 30 天汇总 + 月限额（每日限额 × 30）
 *
 * 详情页内的"应用使用记录"来自 [StatisticsReport.topApps]，
 * 点击应用后会进入 [AppUsageHistoryViewModel] 拉取该应用的逐次使用记录。
 */
data class TabletUsageDetailUiState(
    val period: ReportPeriod = ReportPeriod.DAILY,
    val todayStats: UsageStats? = null,
    val report: StatisticsReport? = null,
    val dailyLimitMinutes: Int = 120,
    val isLoading: Boolean = false,
    val error: String? = null
) {

    /** 当前周期内的总使用秒数。 */
    val totalUsageSeconds: Int
        get() {
            val seconds = when (period) {
                ReportPeriod.DAILY -> (todayStats?.totalUsageMinutes ?: 0) * 60
                else -> (report?.totalUsageMinutes ?: 0) * 60
            }
            return seconds
        }

    /** 当前周期对应的"周期限额"（秒）。 */
    val periodLimitSeconds: Int
        get() = when (period) {
            ReportPeriod.DAILY -> dailyLimitMinutes * 60
            ReportPeriod.WEEKLY -> dailyLimitMinutes * 7 * 60
            ReportPeriod.MONTHLY -> dailyLimitMinutes * 30 * 60
        }

    /** 剩余秒数（>=0）。 */
    val remainingSeconds: Int
        get() = (periodLimitSeconds - totalUsageSeconds).coerceAtLeast(0)

    /** 已超出秒数（>0 时表示超额）。 */
    val overLimitSeconds: Int
        get() = (totalUsageSeconds - periodLimitSeconds).coerceAtLeast(0)

    val overLimit: Boolean
        get() = totalUsageSeconds > periodLimitSeconds
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

            val limitDeferred = async { policyRepository.getGlobalDailyLimit(deviceId) }

            val todayDeferred = async {
                if (period == ReportPeriod.DAILY) statisticsRepository.getTodayUsage(deviceId) else null
            }
            val reportDeferred = async {
                if (period != ReportPeriod.DAILY) statisticsRepository.getUsageStats(deviceId, period) else null
            }

            val limitResult = limitDeferred.await()
            val todayResult = todayDeferred.await()
            val reportResult = reportDeferred.await()

            var error: String? = null
            todayResult?.onFailure { error = it.message }
            reportResult?.onFailure { error = it.message ?: error }

            _uiState.value = _uiState.value.copy(
                todayStats = todayResult?.getOrNull(),
                report = reportResult?.getOrNull(),
                dailyLimitMinutes = limitResult.getOrNull() ?: 120,
                isLoading = false,
                error = error
            )
        }
    }
}
