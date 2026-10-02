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
import com.padguard.domain.repository.AgeBand
import com.padguard.domain.repository.AgeBandDefault
import com.padguard.domain.repository.DeviceRepository
import com.padguard.domain.repository.LocationRepository
import com.padguard.domain.repository.MinorMode
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
    val monthlyReport: StatisticsReport? = null,
    // === 未成年人模式（P1 合规底座） ===
    val minorMode: MinorMode? = null,
    /** 各龄档合规默认值，用于在下发前就把后果展示给家长 */
    val ageBandDefaults: Map<AgeBand, AgeBandDefault> = emptyMap(),
    val minorModeBusy: Boolean = false
) {
    /**
     * 当前档位下实际会生效的每日时长。
     *
     * 服务端用 0 表示"沿用档位默认"，界面直接显示 0 分钟会让家长以为额度被清零，
     * 因此展示层必须用 [ageBandDefaults] 补齐 —— 这是"下发前先看后果"的关键。
     */
    fun effectiveDailyMinutes(): Int {
        val mode = minorMode ?: return 0
        if (mode.dailyLimitMinutes > 0) return mode.dailyLimitMinutes
        return ageBandDefaults[mode.ageBand]?.dailyLimitMinutes ?: 60
    }

    fun effectiveContinuousMinutes(): Int {
        val mode = minorMode ?: return 30
        if (mode.continuousMinutes > 0) return mode.continuousMinutes
        return ageBandDefaults[mode.ageBand]?.continuousMinutes ?: 30
    }
}

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
        loadMinorMode()
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

    // ==================== 未成年人模式 ====================

    private fun loadMinorMode() {
        viewModelScope.launch {
            // 档位默认值与本设备当前配置并发拉取：
            // 默认值是全设备共享的静态数据，失败时用空 Map 兜底，不影响开关本身。
            launch {
                policyRepository.getAgeBandDefaults()
                    .onSuccess { _uiState.value = _uiState.value.copy(ageBandDefaults = it) }
            }
            policyRepository.getMinorMode(deviceId)
                .onSuccess { _uiState.value = _uiState.value.copy(minorMode = it) }
        }
    }

    /**
     * 一键开启 / 关闭未成年人模式。
     *
     * 关闭是"合规敏感操作"：端上会把由分龄派生出的时长、护眼、宵禁限制一并解除，
     * 因此这里不做二次确认弹窗的替代 —— 确认由 UI 层负责，VM 只负责如实下发并回读。
     */
    fun setMinorModeEnabled(enabled: Boolean) {
        val current = _uiState.value.minorMode ?: MinorMode(deviceId = deviceId)
        applyMinorMode(current.copy(enabled = enabled))
    }

    /** 切换分龄档位；档位一变，时长与宵禁随即按新档默认值生效 */
    fun selectAgeBand(band: AgeBand) {
        val current = _uiState.value.minorMode ?: MinorMode(deviceId = deviceId)
        // 切档时清掉旧的自定义时长：留着会让"8–12岁"却沿用"16–18岁"的 2 小时额度
        applyMinorMode(current.copy(ageBand = band, dailyLimitMinutes = 0, weekendLimitMinutes = 0))
    }

    fun setCurfewEnabled(enabled: Boolean) {
        val current = _uiState.value.minorMode ?: return
        applyMinorMode(current.copy(curfewEnabled = enabled))
    }

    /**
     * 家长临时豁免。
     *
     * 只压过宵禁与护眼，**不解除**每日总时长 —— 时长是合规硬约束，
     * 豁免是"今晚特殊情况"，两者混同会让孩子靠反复申请突破额度。
     */
    fun grantExemption(minutes: Int) {
        val current = _uiState.value.minorMode ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(minorModeBusy = true)
            policyRepository.setMinorMode(current.copy(enabled = true), exemptMinutes = minutes)
                .onSuccess {
                    loadMinorMode()
                    showToast("已临时豁免 $minutes 分钟（每日总时长仍然生效）")
                }
                .onFailure { showToast(it.message ?: "豁免失败") }
            _uiState.value = _uiState.value.copy(minorModeBusy = false)
        }
    }

    private fun applyMinorMode(mode: MinorMode) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(minorModeBusy = true)
            policyRepository.setMinorMode(mode)
                .onSuccess {
                    // 立即回读：服务端会补齐省略字段（如档位默认的宵禁时间），
                    // 不回读的话界面显示的仍是本地旧值，家长会以为没生效。
                    loadMinorMode()
                    showToast(if (mode.enabled) "未成年人模式已开启" else "未成年人模式已关闭")
                }
                .onFailure { showToast(it.message ?: "未成年人模式设置失败") }
            _uiState.value = _uiState.value.copy(minorModeBusy = false)
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
