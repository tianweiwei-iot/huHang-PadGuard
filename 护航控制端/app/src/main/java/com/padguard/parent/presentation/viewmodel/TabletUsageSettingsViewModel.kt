package com.padguard.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.domain.model.TabletUsageSettings
import com.padguard.domain.model.TimeRange
import com.padguard.domain.repository.PolicyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 平板使用时间设置 ViewModel
 */
data class TabletUsageSettingsUiState(
    val settings: TabletUsageSettings = TabletUsageSettings(deviceId = ""),
    val isLoading: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class TabletUsageSettingsViewModel @Inject constructor(
    private val policyRepository: PolicyRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TabletUsageSettingsUiState())
    val uiState: StateFlow<TabletUsageSettingsUiState> = _uiState.asStateFlow()

    fun load(deviceId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, saved = false, error = null)
            policyRepository.getTabletUsageSettings(deviceId)
                .onSuccess { settings ->
                    _uiState.value = TabletUsageSettingsUiState(settings = settings, isLoading = false)
                }
                .onFailure { e ->
                    _uiState.value = TabletUsageSettingsUiState(
                        settings = TabletUsageSettings(deviceId = deviceId),
                        isLoading = false,
                        error = e.message
                    )
                }
        }
    }

    fun updateEnabled(enabled: Boolean) {
        val current = _uiState.value.settings
        _uiState.value = _uiState.value.copy(settings = current.copy(enabled = enabled))
    }

    fun updateTimeRange(index: Int, startTime: String, endTime: String) {
        val current = _uiState.value.settings
        val ranges = current.enabledTimeRanges.toMutableList()
        if (index in ranges.indices) {
            ranges[index] = TimeRange(startTime, endTime)
        }
        _uiState.value = _uiState.value.copy(settings = current.copy(enabledTimeRanges = ranges))
    }

    fun addTimeRange() {
        val current = _uiState.value.settings
        _uiState.value = _uiState.value.copy(
            settings = current.copy(enabledTimeRanges = current.enabledTimeRanges + TimeRange("08:00", "18:00"))
        )
    }

    fun removeTimeRange(index: Int) {
        val current = _uiState.value.settings
        val ranges = current.enabledTimeRanges.toMutableList()
        if (index in ranges.indices) {
            ranges.removeAt(index)
        }
        _uiState.value = _uiState.value.copy(settings = current.copy(enabledTimeRanges = ranges))
    }

    fun updateWeekdayLimit(minutes: Int) {
        val current = _uiState.value.settings
        _uiState.value = _uiState.value.copy(settings = current.copy(weekdayLimitMinutes = minutes))
    }

    fun updateWeekendLimit(minutes: Int) {
        val current = _uiState.value.settings
        _uiState.value = _uiState.value.copy(settings = current.copy(weekendLimitMinutes = minutes))
    }

    fun updateRestAfter(minutes: Int) {
        val current = _uiState.value.settings
        _uiState.value = _uiState.value.copy(settings = current.copy(restAfterMinutes = minutes))
    }

    fun updateRestDuration(minutes: Int) {
        val current = _uiState.value.settings
        _uiState.value = _uiState.value.copy(settings = current.copy(restDurationMinutes = minutes))
    }

    fun updateTimeUpMessage(message: String) {
        val current = _uiState.value.settings
        _uiState.value = _uiState.value.copy(settings = current.copy(timeUpMessage = message))
    }

    fun save() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, saved = false, error = null)
            policyRepository.updateTabletUsageSettings(_uiState.value.settings)
                .onSuccess { synced ->
                    _uiState.value = TabletUsageSettingsUiState(
                        settings = synced,
                        isLoading = false,
                        saved = true
                    )
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = e.message
                    )
                }
        }
    }
}
