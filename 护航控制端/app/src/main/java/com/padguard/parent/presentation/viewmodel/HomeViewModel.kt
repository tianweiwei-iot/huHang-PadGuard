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
import kotlinx.coroutines.flow.collect
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
    /** 孩子真实姓名（与服务端 Device.childName 一致，做到孩子端/服务端/家长端三端一致） */
    val childName: String = "",
    /** 孩子昵称（可选，用于家长端展示补充） */
    val childNickname: String = "",
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
        // 订阅共享设备流：解绑 / 接入 / 重命名后首页即时反映，无需手动下拉刷新。
        // 否则解绑成功后首页仍显示旧设备，家长会误以为「解绑无效 / 无法解绑」。
        viewModelScope.launch {
            deviceRepository.observeDeviceList().collect { applyDeviceList(it) }
        }
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
                .onSuccess { applyDeviceList(it) }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(error = e.message, isRefreshing = false)
                }
        }
    }

    /**
     * 统一把设备列表应用到 UI 状态并维护选中态。
     * 初载 / 下拉刷新与共享设备流 [deviceRepository.observeDeviceList] 的观察者共用此路径，
     * 因此设备解绑（由 [com.padguard.parent.data.remote.ApiDataSource] 刷新该流）后首页即时反映，
     * 无需手动下拉刷新 —— 避免「解绑成功了但首页还显示旧设备」的误判。
     */
    private fun applyDeviceList(devices: List<Device>) {
        val current = _uiState.value
        val currentSel = current.selectedDeviceId
        val sel = if (currentSel != null && devices.any { it.id == currentSel }) {
            currentSel
        } else {
            // 同一台平板反复绑定会在服务端留下多条设备记录，其中往往只有一条真正在线。
            // 优先选在线设备，没有在线设备才回落到第一条。
            devices.firstOrNull { it.onlineStatus == DeviceOnlineStatus.ONLINE }?.id
                ?: devices.firstOrNull()?.id
        }
        val selectionChanged = sel != currentSel
        val selectedDevice = devices.firstOrNull { it.id == sel }
        _uiState.value = current.copy(
            devices = devices,
            selectedDeviceId = sel,
            selectedDevice = selectedDevice,
            childName = selectedDevice?.childName?.takeIf { it.isNotBlank() }.orEmpty(),
            childNickname = selectedDevice?.childNickname?.takeIf { it.isNotBlank() }.orEmpty(),
            isRefreshing = false,
            // 选中设备被解绑/已无可选项时，清空其用量数据：
            // 否则首页会残留一台已解绑设备的今日统计，看起来像"还在管控里"。
            todayUsage = if (sel == null) null else current.todayUsage,
            latestActivity = if (sel == null) null else current.latestActivity,
            pendingUnlockTicket = if (sel == null) null else current.pendingUnlockTicket
        )
        if (selectionChanged) {
            sel?.let {
                loadTodayUsage(it)
                loadDailyLimit(it)
                loadUnlockTickets(it)
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
