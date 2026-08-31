package com.padguard.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.domain.model.Device
import com.padguard.domain.repository.LocationInfo
import com.padguard.domain.model.UsageStats
import com.padguard.domain.repository.DeviceRepository
import com.padguard.domain.repository.MonitorRepository
import com.padguard.domain.repository.PolicyRepository
import com.padguard.domain.repository.StatisticsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 设备详情 ViewModel
 * 聚合设备信息、今日使用统计、实时位置，以及即时远程指令
 */
data class DeviceDetailUiState(
    val device: Device? = null,
    val todayUsage: UsageStats? = null,
    val location: LocationInfo? = null,
    val isLoading: Boolean = false,
    val toast: String? = null,
    val error: String? = null
)

@HiltViewModel
class DeviceDetailViewModel @Inject constructor(
    private val deviceRepository: DeviceRepository,
    private val monitorRepository: MonitorRepository,
    private val statisticsRepository: StatisticsRepository,
    private val policyRepository: PolicyRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val deviceId: String = savedStateHandle.get<String>("deviceId").orEmpty()

    private val _uiState = MutableStateFlow(DeviceDetailUiState())
    val uiState: StateFlow<DeviceDetailUiState> = _uiState.asStateFlow()

    init {
        loadDeviceDetail()
    }

    fun loadDeviceDetail() {
        if (deviceId.isBlank()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            deviceRepository.getDeviceDetail(deviceId)
                .onSuccess { device ->
                    _uiState.value = _uiState.value.copy(device = device)
                    launch {
                        statisticsRepository.getTodayUsage(deviceId)
                            .onSuccess { _uiState.value = _uiState.value.copy(todayUsage = it) }
                    }
                    launch {
                        monitorRepository.getDeviceLocation(deviceId)
                            .onSuccess { _uiState.value = _uiState.value.copy(location = it) }
                            .onFailure { /* 位置获取失败不阻塞详情页 */ }
                    }
                }
                .onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    /** 一键锁屏 */
    fun lockScreen() {
        viewModelScope.launch {
            policyRepository.lockScreen(deviceId)
                .onSuccess { _uiState.value = _uiState.value.copy(toast = "已发送锁屏指令") }
                .onFailure { _uiState.value = _uiState.value.copy(toast = "锁屏指令发送失败") }
        }
    }

    /** 通用即时指令（重启/定位/授权等，Mock 阶段仅提示） */
    fun runCommand(label: String) {
        _uiState.value = _uiState.value.copy(toast = "已发送：$label")
    }

    fun clearToast() {
        _uiState.value = _uiState.value.copy(toast = null)
    }
}
