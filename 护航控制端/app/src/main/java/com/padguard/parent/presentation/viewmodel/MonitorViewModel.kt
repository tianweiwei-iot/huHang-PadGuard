package com.padguard.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.domain.repository.LocationInfo
import com.padguard.domain.model.ScreenshotData
import com.padguard.domain.repository.MonitorRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 实时监控 ViewModel
 * 提供实时截屏（Mock）与设备位置，供"查看屏幕"页使用
 */
data class MonitorUiState(
    val screenshot: ScreenshotData? = null,
    val location: LocationInfo? = null,
    val isLoading: Boolean = false,
    val toast: String? = null,
    val error: String? = null
)

@HiltViewModel
class MonitorViewModel @Inject constructor(
    private val monitorRepository: MonitorRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val deviceId: String = savedStateHandle.get<String>("deviceId").orEmpty()

    private val _uiState = MutableStateFlow(MonitorUiState())
    val uiState: StateFlow<MonitorUiState> = _uiState.asStateFlow()

    init { load() }

    fun load() {
        if (deviceId.isBlank()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            monitorRepository.requestScreenshot(deviceId).onSuccess { shot ->
                _uiState.value = _uiState.value.copy(screenshot = shot)
            }
            monitorRepository.getDeviceLocation(deviceId).onSuccess { loc ->
                _uiState.value = _uiState.value.copy(location = loc)
            }.onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    fun captureScreenshot() {
        viewModelScope.launch {
            monitorRepository.requestScreenshot(deviceId).onSuccess {
                _uiState.value = _uiState.value.copy(screenshot = it, toast = "已截屏")
            }
        }
    }

    fun clearToast() {
        _uiState.value = _uiState.value.copy(toast = null)
    }
}
