package com.padguard.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.domain.model.AppUsageHistoryEntry
import com.padguard.domain.model.ReportPeriod
import com.padguard.domain.repository.StatisticsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 应用使用记录子页 ViewModel
 *
 * 展示某应用在指定周期（按天 / 按周 / 按月）内每次"打开 → 关闭"的使用记录。
 */
data class AppUsageHistoryUiState(
    val appName: String = "",
    val period: ReportPeriod = ReportPeriod.DAILY,
    val entries: List<AppUsageHistoryEntry> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
) {

    /** 周期内总使用秒数（用于顶部摘要卡片）。 */
    val totalSeconds: Int
        get() = entries.sumOf { it.usageSeconds }
}

@HiltViewModel
class AppUsageHistoryViewModel @Inject constructor(
    private val statisticsRepository: StatisticsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppUsageHistoryUiState())
    val uiState: StateFlow<AppUsageHistoryUiState> = _uiState.asStateFlow()

    private var loadedKey: String = ""

    fun load(deviceId: String, packageName: String, appName: String, period: ReportPeriod) {
        val key = "$deviceId|$packageName|${period.name}"
        if (loadedKey == key && _uiState.value.entries.isNotEmpty()) {
            // 切换周期时只更新 period 与 appName
            _uiState.value = _uiState.value.copy(appName = appName, period = period)
            return
        }
        loadedKey = key
        _uiState.value = _uiState.value.copy(
            appName = appName,
            period = period,
            isLoading = true,
            error = null
        )
        viewModelScope.launch {
            val result = statisticsRepository.getAppUsageHistory(deviceId, packageName, period)
            _uiState.value = if (result.isSuccess) {
                _uiState.value.copy(
                    entries = result.getOrDefault(emptyList()),
                    isLoading = false
                )
            } else {
                _uiState.value.copy(
                    isLoading = false,
                    error = result.exceptionOrNull()?.message ?: "加载失败"
                )
            }
        }
    }
}
