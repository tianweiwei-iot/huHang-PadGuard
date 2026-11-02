package com.padguard.parent.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.parent.domain.model.AppUsage
import com.padguard.parent.domain.model.DailyUsage
import com.padguard.parent.domain.model.ReportPeriod
import com.padguard.parent.domain.repository.StatisticsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 使用统计 ViewModel
 * 加载指定设备的今日用量与本周报告
 */
data class StatisticsUiState(
    val todayMinutes: Int = 0,
    val weeklyMinutes: Int = 0,
    val dailyUsages: List<DailyUsage> = emptyList(),
    val topApps: List<AppUsage> = emptyList(),
    val isLoading: Boolean = false,
    /** 是否至少成功取到一份数据：用于区分"真 0 分钟"与"接口失败后的默认 0" */
    val hasData: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class StatisticsViewModel @Inject constructor(
    private val statisticsRepository: StatisticsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(StatisticsUiState())
    val uiState: StateFlow<StatisticsUiState> = _uiState.asStateFlow()

    fun load(deviceId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            var todayMin = 0
            var weeklyMin = 0
            var dailies = emptyList<DailyUsage>()
            var tops = emptyList<AppUsage>()

            // 失败不能静默：此前只写 onSuccess，接口挂了页面显示"今日 0 分钟"——
            // 家长会以为孩子今天没碰平板，是典型的状态谎报。
            val todayResult = statisticsRepository.getTodayUsage(deviceId)
            todayResult.onSuccess { stats ->
                todayMin = stats.totalUsageMinutes
                tops = stats.appUsages.sortedByDescending { it.usageMinutes }
            }
            val weekResult = statisticsRepository.getUsageStats(deviceId, ReportPeriod.WEEKLY)
            weekResult.onSuccess { report ->
                weeklyMin = report.totalUsageMinutes
                dailies = report.dailyUsages
            }

            _uiState.value = _uiState.value.copy(
                todayMinutes = todayMin,
                weeklyMinutes = weeklyMin,
                dailyUsages = dailies,
                topApps = tops,
                isLoading = false,
                hasData = todayResult.isSuccess || weekResult.isSuccess,
                // 两份都失败才是整页失败；只挂一份时保留已取到的数据，仅提示部分失败
                error = when {
                    todayResult.isFailure && weekResult.isFailure ->
                        "统计加载失败：" + (todayResult.exceptionOrNull()?.message
                            ?: weekResult.exceptionOrNull()?.message ?: "网络或服务异常")
                    todayResult.isFailure || weekResult.isFailure -> "部分数据加载失败，显示的内容可能不完整"
                    else -> null
                }
            )
        }
    }
}
