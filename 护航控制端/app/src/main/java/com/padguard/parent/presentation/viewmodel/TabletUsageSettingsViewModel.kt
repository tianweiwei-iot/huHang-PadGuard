package com.padguard.parent.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.parent.domain.model.TabletUsageSettings
import com.padguard.parent.domain.model.TimeRange
import com.padguard.parent.domain.model.UsageSession
import com.padguard.parent.domain.repository.PolicyRepository
import com.padguard.parent.domain.repository.UsageDelayTicket
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
    val error: String? = null,
    /**
     * 设置是否成功从服务端读取过一次。
     *
     * 读取失败时页面仍会用默认值（60 分钟 / 开启）渲染，此时保存等于把
     * "开启 + 工作日 60 分钟 + 无时段"整包写回并立即下发，一次网络抖动就能
     * 改写孩子端真实策略。保存前必须校验本标记。
     */
    val loaded: Boolean = false,
    /** 平板使用计时会话（三端一致的剩余时长来源）；null 表示尚未拉取 */
    val usageSession: UsageSession? = null,
    val sessionError: String? = null,
    /** 该设备的延时审批工单（含已处理）；空表示尚未拉取或没有 */
    val delayTickets: List<UsageDelayTicket> = emptyList(),
    /** 延时工单处理中：按钮置灰，避免连点重复下发 */
    val delayProcessing: Boolean = false
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
                // 必须用 copy 增量更新，不能整体重建 state：
                // 本页进入时 load() 与 loadSession() 是并行发起的，且 usageSession 还有独立轮询在刷新。
                // 整体重建会把先到的会话数据（以及延时工单列表）一把清空，
                // "今日剩余时长"因此卡回"正在读取计时状态…"，最长要等下一个轮询周期才恢复；
                // save() 成功后同样会把 delayTickets 清掉，延时审批区瞬间变空。
                .onSuccess { settings ->
                    _uiState.value = _uiState.value.copy(settings = settings, isLoading = false, loaded = true)
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        settings = TabletUsageSettings(deviceId = deviceId),
                        isLoading = false,
                        loaded = false,
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

    /**
     * 拉取平板使用计时会话（含服务端锚定的剩余时长）。
     * 与 [load] 解耦：设置页进入时二者并行拉取，会话每隔一段时间由界面轮询刷新。
     */
    fun loadSession(deviceId: String) {
        if (deviceId.isEmpty()) return
        viewModelScope.launch {
            policyRepository.getUsageSession(deviceId)
                .onSuccess { session ->
                    _uiState.value = _uiState.value.copy(usageSession = session, sessionError = null)
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(sessionError = e.message)
                }
        }
    }

    /**
     * 以本地墙钟 [now] 平滑插值，得到与服务器/孩子端一致的实时剩余毫秒。
     * 界面每秒刷新调用，避免三端各自按本地时钟累加导致数字对不上。
     */
    fun liveRemainingMs(now: Long): Long =
        _uiState.value.usageSession?.liveRemainingMs(now) ?: 0L

    /**
     * 拉取该设备的延时审批工单（含已处理），与 [loadSession] 解耦、可被 15 秒轮询刷新。
     * 孩子端在计时倒计时页点「延时申请」后，家长在此页即可见待处理工单并处理。
     */
    fun loadDelayTickets(deviceId: String) {
        if (deviceId.isEmpty()) return
        viewModelScope.launch {
            policyRepository.getUsageDelayTickets(deviceId)
                .onSuccess { tickets ->
                    _uiState.value = _uiState.value.copy(delayTickets = tickets)
                }
                .onFailure { /* 非阻断：时间管控页仍可用，工单区留空即可 */ }
        }
    }

    /** 同意延时：服务端按工单分钟数累加额度，孩子端下一次会话刷新即生效 */
    fun approveDelay(ticket: UsageDelayTicket) {
        actDelay(ticket, successMessage = "已同意延长 ${ticket.minutes} 分钟") {
            policyRepository.approveUsageDelayTicket(ticket.deviceId, ticket.id)
        }
    }

    /** 拒绝延时：关闭工单并给孩子下发「申请未通过」 */
    fun rejectDelay(ticket: UsageDelayTicket) {
        actDelay(ticket, successMessage = "已拒绝该申请") {
            policyRepository.rejectUsageDelayTicket(ticket.deviceId, ticket.id, null)
        }
    }

    /** 忽略延时：归档进消息中心，不下发消息给孩子，工单仍可被批准 */
    fun dismissDelay(ticket: UsageDelayTicket) {
        actDelay(ticket, successMessage = "已忽略，可在消息中心继续处理") {
            policyRepository.dismissUsageDelayTicket(ticket.deviceId, ticket.id)
        }
    }

    /**
     * 延时工单统一处理入口：置处理中 → 调仓库 → 无论成败都刷新工单列表。
     * 失败也刷新：网络抖动不应让家长被同一张卡反复卡住，列表里仍可见该工单。
     */
    private fun actDelay(
        ticket: UsageDelayTicket,
        successMessage: String,
        block: suspend () -> Result<Unit>
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(delayProcessing = true)
            val outcome = try {
                block()
            } catch (t: Throwable) {
                Result.failure(t)
            }
            val message = outcome.fold(
                onSuccess = { successMessage },
                onFailure = { e -> "操作失败：${e.message ?: "请检查网络后重试"}" }
            )
            loadDelayTickets(ticket.deviceId)
            _uiState.value = _uiState.value.copy(
                delayProcessing = false,
                sessionError = message.takeIf { outcome.isFailure }
            )
        }
    }

    /** 错误提示展示后清空（见 Screen 的 LaunchedEffect 注释） */
    fun clearError() {
        if (_uiState.value.error != null) _uiState.value = _uiState.value.copy(error = null)
    }

    fun save() {        viewModelScope.launch {
            // 没读到真实设置就保存 = 用默认值覆盖服务端配置并立即下发（见 loaded 注释）
            if (!_uiState.value.loaded) {
                _uiState.value = _uiState.value.copy(error = "设置未加载成功，请重新进入页面后再保存")
                return@launch
            }
            _uiState.value = _uiState.value.copy(isLoading = true, saved = false, error = null)
            // 保存必须立即下发到设备。
            // syncToDevice=false 时服务端只落库、不重建策略包，孩子端要等下一次自然重建才生效，
            // 家长看到的就是"保存成功但平板毫无反应"，被当成"时间管控设置不了"。
            policyRepository.updateTabletUsageSettings(
                _uiState.value.settings.copy(syncToDevice = true)
            )
                .onSuccess { synced ->
                    // 同 load()：增量更新，保留 usageSession / delayTickets 等已加载数据
                    _uiState.value = _uiState.value.copy(
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
