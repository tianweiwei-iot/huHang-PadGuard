package com.padguard.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.domain.model.Device
import com.padguard.domain.model.DeviceOnlineStatus
import com.padguard.domain.model.SceneType
import com.padguard.domain.model.UsageStats
import com.padguard.domain.repository.DeviceRepository
import com.padguard.domain.repository.PolicyRepository
import com.padguard.domain.repository.StatisticsRepository
import com.padguard.domain.repository.UnlockTicket
import com.padguard.presentation.util.TimeFormat
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
    /** 孩子端提交、等待家长处理的解锁申请（取最早一条展示，避免首页被刷屏） */
    val pendingUnlockTicket: UnlockTicket? = null,
    val unlockActionMessage: String? = null,
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
        _uiState.value = _uiState.value.copy(
            selectedDeviceId = deviceId,
            selectedDevice = device,
            pendingUnlockTicket = null
        )
        loadTodayUsage(deviceId)
        loadDailyLimit(deviceId)
        loadUnlockTickets(deviceId)
    }

    /** 忽略某条解锁申请：关闭工单，不下发任何消息给孩子 */
    fun ignoreUnlockTicket(ticketId: String) {
        val deviceId = _uiState.value.selectedDeviceId ?: return
        viewModelScope.launch {
            policyRepository.ignoreUnlockTicket(deviceId, ticketId)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        pendingUnlockTicket = null,
                        unlockActionMessage = "已忽略该申请"
                    )
                    loadUnlockTickets(deviceId)
                }
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun clearUnlockMessage() {
        _uiState.value = _uiState.value.copy(unlockActionMessage = null)
    }

    /**
     * 拉取待处理的解锁申请。
     * 首页只展示最早的一条：孩子可能连着提交好几次，全列出来既刷屏又容易让家长误点。
     */
    private fun loadUnlockTickets(deviceId: String) {
        viewModelScope.launch {
            policyRepository.getUnlockTickets(deviceId)
                .onSuccess { tickets ->
                    _uiState.value = _uiState.value.copy(
                        pendingUnlockTicket = tickets.filter { it.isPending }.minByOrNull { it.createdAt }
                    )
                }
                .onFailure { /* 申请列表是增值信息，失败不阻断首页渲染 */ }
        }
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
                        // 同一台平板反复绑定会在服务端留下多条设备记录，
                        // 其中往往只有一条真正在线。若默认选第一条（很可能是废弃记录），
                        // 家长下发的所有指令都会发给一个离线设备 ——
                        // 现场表现是"什么管控功能都没反应，就消息发送看似成功"。
                        // 因此首次选中优先选在线设备，没有在线设备才回落到第一条。
                        devices.firstOrNull { it.onlineStatus == DeviceOnlineStatus.ONLINE }?.id
                            ?: devices.firstOrNull()?.id
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
                        loadUnlockTickets(it)
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
                    val topApp = stats.appUsages.firstOrNull()
                    _uiState.value = _uiState.value.copy(
                        todayUsage = stats,
                        latestActivity = topApp?.let {
                            "使用了 ${it.appName} ${TimeFormat.formatDurationFromMinutes(it.usageMinutes)}"
                        }
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
