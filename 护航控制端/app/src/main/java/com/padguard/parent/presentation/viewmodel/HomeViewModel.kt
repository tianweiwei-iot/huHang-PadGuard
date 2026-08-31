package com.padguard.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.domain.model.Device
import com.padguard.domain.model.SceneType
import com.padguard.domain.model.UsageStats
import com.padguard.domain.repository.DeviceRepository
import com.padguard.domain.repository.PolicyRepository
import com.padguard.domain.repository.StatisticsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 首页 ViewModel
 * 聚合设备列表、使用统计、最新动态等数据，并支持在多个孩子/设备间切换。
 *
 * 设计约定（单一可信源）：
 * - [HomeUiState.selectedDeviceId] 为当前选中的设备标识；
 * - [HomeUiState.selectedDevice] 由其派生，所有页面统一从此读取，避免在 Home/统计/设备
 *   多处在各自用 firstOrNull 重复解析，导致选中态漂移的隐性回归。
 * - [HomeUiState.dailyLimitMinutes] 来自 PolicyRepository（真实策略来源），取代此前 UI 硬编码的 120。
 */
data class HomeUiState(
    val userName: String = "家长用户",
    val sceneType: SceneType = SceneType.FAMILY,
    val devices: List<Device> = emptyList(),
    val selectedDeviceId: String? = null,
    val selectedDevice: Device? = null,
    val dailyLimitMinutes: Int = 120,
    val todayUsage: UsageStats? = null,
    val latestActivity: String? = null,
    val isRefreshing: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val deviceRepository: DeviceRepository,
    private val statisticsRepository: StatisticsRepository,
    private val policyRepository: PolicyRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadHomeData()
    }

    /** 下拉刷新：复用统一加载路径，仅切换刷新态，避免与 loadHomeData 重复逻辑。 */
    fun refreshData() {
        loadHomeData(showRefreshing = true)
    }

    /** 切换当前选中的孩子/设备，并重新加载其今日用量与每日限额 */
    fun selectDevice(deviceId: String) {
        if (_uiState.value.selectedDeviceId == deviceId) return
        val device = _uiState.value.devices.firstOrNull { it.id == deviceId }
        _uiState.value = _uiState.value.copy(selectedDeviceId = deviceId, selectedDevice = device)
        loadTodayUsage(deviceId)
        loadDailyLimit(deviceId)
    }

    /**
     * 加载首页数据。
     * @param showRefreshing 是否处于下拉刷新（控制 isRefreshing 态）
     */
    private fun loadHomeData(showRefreshing: Boolean = false) {
        viewModelScope.launch {
            if (showRefreshing) _uiState.value = _uiState.value.copy(isRefreshing = true)
            deviceRepository.getDeviceList()
                .onSuccess { devices ->
                    val currentSel = _uiState.value.selectedDeviceId
                    val sel = if (currentSel != null && devices.any { it.id == currentSel }) {
                        currentSel
                    } else {
                        devices.firstOrNull()?.id
                    }
                    val selectedDevice = devices.firstOrNull { it.id == sel }
                    _uiState.value = _uiState.value.copy(
                        devices = devices,
                        selectedDeviceId = sel,
                        selectedDevice = selectedDevice,
                        isRefreshing = false
                    )
                    sel?.let {
                        loadTodayUsage(it)
                        loadDailyLimit(it)
                    }
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(error = e.message, isRefreshing = false)
                }
        }
    }

    private fun loadTodayUsage(deviceId: String) {
        viewModelScope.launch {
            statisticsRepository.getTodayUsage(deviceId)
                .onSuccess { stats ->
                    _uiState.value = _uiState.value.copy(
                        todayUsage = stats,
                        latestActivity = if (stats.appUsages.isNotEmpty())
                            "使用了 ${stats.appUsages.first().appName} ${stats.appUsages.first().usageMinutes} 分钟"
                        else null
                    )
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(error = e.message)
                }
        }
    }

    private fun loadDailyLimit(deviceId: String) {
        viewModelScope.launch {
            policyRepository.getGlobalDailyLimit(deviceId)
                .onSuccess { minutes -> _uiState.value = _uiState.value.copy(dailyLimitMinutes = minutes) }
                .onFailure { /* 保留默认值，不阻断首页渲染 */ }
        }
    }
}
