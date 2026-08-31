package com.padguard.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.domain.model.AppUsage
import com.padguard.domain.model.DailyUsage
import com.padguard.domain.model.ReportPeriod
import com.padguard.domain.repository.StatisticsRepository
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
            _uiState.value = _uiState.value.copy(isLoading = true)
            var todayMin = 0
            var weeklyMin = 0
            var dailies = emptyList<DailyUsage>()
            var tops = emptyList<AppUsage>()

            statisticsRepository.getTodayUsage(deviceId).onSuccess { stats ->
                todayMin = stats.totalUsageMinutes
                tops = stats.appUsages.sortedByDescending { it.usageMinutes }
            }
            statisticsRepository.getUsageStats(deviceId, ReportPeriod.WEEKLY).onSuccess { report ->
                weeklyMin = report.totalUsageMinutes
                dailies = report.dailyUsages
            }

            _uiState.value = _uiState.value.copy(
                todayMinutes = todayMin,
                weeklyMinutes = weeklyMin,
                dailyUsages = dailies,
                topApps = tops,
                isLoading = false
            )
        }
    }
}
