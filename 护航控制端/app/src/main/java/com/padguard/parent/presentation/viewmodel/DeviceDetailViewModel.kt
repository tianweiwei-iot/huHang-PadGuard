package com.padguard.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.domain.model.AppDispatchAction
import com.padguard.domain.model.Device
import com.padguard.domain.model.DeviceFunctionConfig
import com.padguard.domain.model.DevicePermissionConfig
import com.padguard.domain.model.DeviceUpgradeInfo
import com.padguard.domain.model.DistributableApp
import com.padguard.domain.model.LocationInfo
import com.padguard.domain.model.ReportPeriod
import com.padguard.domain.model.StatisticsReport
import com.padguard.domain.model.UsageStats
import com.padguard.domain.repository.DeviceRepository
import com.padguard.domain.repository.LocationRepository
import com.padguard.domain.repository.PolicyRepository
import com.padguard.domain.repository.StatisticsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 设备详情页内嵌的管理面板（对标 MDM 设备管理能力）
 */
enum class DeviceAdminSheet(val label: String) {
    PERMISSIONS("权限管理"),
    FUNCTIONS("功能管理"),
    APP_DISPATCH("应用分发"),
    UPGRADE("远程升级"),
    STATS("数据统计")
}

/**
 * 设备详情 ViewModel
 * 聚合设备信息、今日使用统计、实时位置、即时远程指令，
 * 以及设备管理五面板：权限管理 / 功能管理 / 应用分发 / 远程升级 / 数据统计
 */
data class DeviceDetailUiState(
    val device: Device? = null,
    val todayUsage: UsageStats? = null,
    val location: LocationInfo? = null,
    val isLoading: Boolean = false,
    val toast: String? = null,
    val error: String? = null,
    // === 设备管理面板 ===
    val adminSheet: DeviceAdminSheet? = null,
    val permissions: DevicePermissionConfig? = null,
    val functionConfig: DeviceFunctionConfig? = null,
    val distributableApps: List<DistributableApp> = emptyList(),
    val upgradeInfo: DeviceUpgradeInfo? = null,
    val isUpgrading: Boolean = false,
    val monthlyReport: StatisticsReport? = null
)

@HiltViewModel
class DeviceDetailViewModel @Inject constructor(
    private val deviceRepository: DeviceRepository,
    private val locationRepository: LocationRepository,
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
                        locationRepository.getDeviceLocation(deviceId)
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

    // ==================== 设备管理面板 ====================

    fun openAdminSheet(sheet: DeviceAdminSheet) {
        _uiState.value = _uiState.value.copy(adminSheet = sheet)
        when (sheet) {
            DeviceAdminSheet.PERMISSIONS -> loadPermissions()
            DeviceAdminSheet.FUNCTIONS -> loadFunctionConfig()
            DeviceAdminSheet.APP_DISPATCH -> loadDistributableApps()
            DeviceAdminSheet.UPGRADE -> loadUpgradeInfo()
            DeviceAdminSheet.STATS -> loadMonthlyReport()
        }
    }

    fun closeAdminSheet() {
        _uiState.value = _uiState.value.copy(adminSheet = null)
    }

    private fun loadPermissions() {
        viewModelScope.launch {
            deviceRepository.getDevicePermissions(deviceId)
                .onSuccess { _uiState.value = _uiState.value.copy(permissions = it) }
        }
    }

    fun updatePermissions(config: DevicePermissionConfig) {
        viewModelScope.launch {
            deviceRepository.updateDevicePermissions(config)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(permissions = it, toast = "权限配置已下发")
                }
                .onFailure { showToast(it.message ?: "权限配置下发失败") }
        }
    }

    private fun loadFunctionConfig() {
        viewModelScope.launch {
            deviceRepository.getDeviceFunctionConfig(deviceId)
                .onSuccess { _uiState.value = _uiState.value.copy(functionConfig = it) }
        }
    }

    fun updateFunctionConfig(config: DeviceFunctionConfig) {
        viewModelScope.launch {
            deviceRepository.updateDeviceFunctionConfig(config)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(functionConfig = it, toast = "功能配置已下发")
                }
                .onFailure { showToast(it.message ?: "功能配置下发失败") }
        }
    }

    private fun loadDistributableApps() {
        viewModelScope.launch {
            deviceRepository.getDistributableApps(deviceId)
                .onSuccess { _uiState.value = _uiState.value.copy(distributableApps = it) }
        }
    }

    fun dispatchApp(packageName: String, action: AppDispatchAction) {
        viewModelScope.launch {
            deviceRepository.dispatchApp(deviceId, packageName, action)
                .onSuccess { showToast("已${action.label}：$packageName") }
                .onFailure { showToast(it.message ?: "应用分发失败") }
        }
    }

    private fun loadUpgradeInfo() {
        viewModelScope.launch {
            deviceRepository.getDeviceUpgradeInfo(deviceId)
                .onSuccess { _uiState.value = _uiState.value.copy(upgradeInfo = it) }
        }
    }

    fun startUpgrade() {
        if (_uiState.value.isUpgrading) return
        _uiState.value = _uiState.value.copy(isUpgrading = true)
        viewModelScope.launch {
            // Mock：模拟下发与平板端安装耗时
            kotlinx.coroutines.delay(1500)
            deviceRepository.upgradeDevice(deviceId)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(isUpgrading = false, toast = "升级完成")
                    loadDeviceDetail()
                    loadUpgradeInfo()
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(isUpgrading = false)
                    showToast(it.message ?: "升级失败")
                }
        }
    }

    private fun loadMonthlyReport() {
        if (_uiState.value.monthlyReport != null) return
        viewModelScope.launch {
            statisticsRepository.getUsageStats(deviceId, ReportPeriod.MONTHLY)
                .onSuccess { _uiState.value = _uiState.value.copy(monthlyReport = it) }
        }
    }

    fun clearToast() {
        _uiState.value = _uiState.value.copy(toast = null)
    }

    private fun showToast(message: String) {
        _uiState.value = _uiState.value.copy(toast = message)
    }
}
