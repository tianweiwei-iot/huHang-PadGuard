package com.padguard.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.domain.model.AppPolicy
import com.padguard.domain.model.ControlMode
import com.padguard.domain.model.Device
import com.padguard.domain.repository.PolicyTemplate
import com.padguard.domain.model.SceneType
import com.padguard.domain.model.TimeRestriction
import com.padguard.domain.model.WebPolicy
import com.padguard.domain.repository.DeviceRepository
import com.padguard.domain.repository.PolicyRepository
import com.padguard.presentation.util.TimeFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 管控策略 ViewModel
 * 覆盖设计文档"全维度管控"四栏：使用时长 / 应用管控 / 上网安全 / 系统与安全
 * 纯家庭场景，模板不含校园（考试）模式
 */
data class ControlPolicyUiState(
    val device: Device? = null,
    val dailyLimitMinutes: Int = 120,
    val timeRestrictions: List<TimeRestriction> = emptyList(),
    val installedApps: List<AppPolicy> = emptyList(),
    val webPolicy: WebPolicy? = null,
    val templates: List<PolicyTemplate> = emptyList(),
    val eyeProtectionEnabled: Boolean = false,
    val eyeProtectionLevel: Int = 2,
    val isLoading: Boolean = false,
    val toast: String? = null,
    val error: String? = null
)

/**
 * 家庭预设模板（上学日 / 周末 / 假期 / 夜间锁机）
 *
 * 预设的「名称 / 管控模式 / 每日限额」在此集中定义，避免 ViewModel 分支里散落硬编码
 * （新增预设只需加一个枚举项，无需改动 [ControlPolicyViewModel.applyPreset]）。
 */
enum class FamilyPreset(
    /** 预设名称，用于提示文案。 */
    val displayName: String,
    /** 应用后切换到的管控模式。 */
    val controlMode: ControlMode,
    /** 每日总时长限制（分钟）；null 表示不改动现有限额（如夜间锁机）。 */
    val dailyLimitMinutes: Int?
) {
    SCHOOL_DAY("上学日模式", ControlMode.LEARNING, 120),
    WEEKEND("周末模式", ControlMode.NORMAL, 180),
    HOLIDAY("假期模式", ControlMode.NORMAL, 240),
    NIGHT_LOCK("夜间锁机", ControlMode.FOCUS, null)
}

@HiltViewModel
class ControlPolicyViewModel @Inject constructor(
    private val deviceRepository: DeviceRepository,
    private val policyRepository: PolicyRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val deviceId: String = savedStateHandle.get<String>("deviceId").orEmpty()

    private val _uiState = MutableStateFlow(ControlPolicyUiState())
    val uiState: StateFlow<ControlPolicyUiState> = _uiState.asStateFlow()

    init { load() }

    fun load() {
        if (deviceId.isBlank()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            deviceRepository.getDeviceDetail(deviceId).onSuccess { device ->
                _uiState.value = _uiState.value.copy(device = device)
            }
            policyRepository.getTimeRestrictions(deviceId).onSuccess { list ->
                _uiState.value = _uiState.value.copy(timeRestrictions = list)
            }
            policyRepository.getInstalledApps(deviceId).onSuccess { list ->
                _uiState.value = _uiState.value.copy(installedApps = list)
            }
            policyRepository.getWebPolicy(deviceId).onSuccess { wp ->
                _uiState.value = _uiState.value.copy(
                    webPolicy = wp,
                    eyeProtectionEnabled = false,
                    eyeProtectionLevel = 2
                )
            }
            // 仅取家庭场景模板（过滤校园考试模式）
            policyRepository.getPolicyTemplates(SceneType.FAMILY).onSuccess { list ->
                _uiState.value = _uiState.value.copy(
                    templates = list.filter { it.sceneType == SceneType.FAMILY }
                )
            }
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    // ===== 时长 =====

    fun setDailyLimit(minutes: Int) {
        viewModelScope.launch {
            policyRepository.setGlobalDailyLimit(deviceId, minutes).onSuccess {
                _uiState.value = _uiState.value.copy(dailyLimitMinutes = minutes, toast = "已保存每日时长限制")
            }
        }
    }

    fun toggleTimeRestriction(restriction: TimeRestriction) {
        viewModelScope.launch {
            val updated = restriction.copy(isEnabled = !restriction.isEnabled)
            policyRepository.setTimeRestriction(updated)
            _uiState.value = _uiState.value.copy(
                timeRestrictions = _uiState.value.timeRestrictions.map {
                    if (it.id == restriction.id) updated else it
                }
            )
        }
    }

    fun applyPreset(preset: FamilyPreset) {
        viewModelScope.launch {
            policyRepository.setControlMode(deviceId, preset.controlMode)
            val limitMinutes = preset.dailyLimitMinutes
            if (limitMinutes != null) {
                policyRepository.setGlobalDailyLimit(deviceId, limitMinutes)
            }
            val limitLabel = limitMinutes
                ?.let { " · ${TimeFormat.formatDurationFromMinutes(it)}" }
                .orEmpty()
            _uiState.value = _uiState.value.copy(
                dailyLimitMinutes = limitMinutes ?: _uiState.value.dailyLimitMinutes,
                device = _uiState.value.device?.copy(controlMode = preset.controlMode),
                toast = "已应用：${preset.displayName}（${preset.controlMode.label()}$limitLabel）"
            )
        }
    }

    // ===== 应用 =====

    fun toggleAppBlock(app: AppPolicy, blocked: Boolean) {
        viewModelScope.launch {
            val updated = app.copy(isBlocked = blocked)
            policyRepository.setAppTimeLimit(updated)
            _uiState.value = _uiState.value.copy(
                installedApps = _uiState.value.installedApps.map {
                    if (it.packageName == app.packageName) updated else it
                },
                toast = if (blocked) "已禁用：${app.appName}" else "已解禁：${app.appName}"
            )
        }
    }

    fun setAppTimeLimit(app: AppPolicy, minutes: Int?) {
        viewModelScope.launch {
            val updated = app.copy(dailyLimitMinutes = minutes)
            policyRepository.setAppTimeLimit(updated)
            _uiState.value = _uiState.value.copy(
                installedApps = _uiState.value.installedApps.map {
                    if (it.packageName == app.packageName) updated else it
                },
                toast = if (minutes == null) "已取消 ${app.appName} 时长限制" else "已设置 ${app.appName} 限时 ${TimeFormat.formatDurationFromMinutes(minutes)}"
            )
        }
    }

    // ===== 上网 =====

    fun setBrowserDisabled(disabled: Boolean) {
        viewModelScope.launch {
            policyRepository.setBrowserDisabled(deviceId, disabled)
            _uiState.value = _uiState.value.copy(
                webPolicy = _uiState.value.webPolicy?.copy(browserDisabled = disabled),
                toast = if (disabled) "已禁用浏览器" else "已启用浏览器"
            )
        }
    }

    fun addUrlBlock(url: String) {
        if (url.isBlank()) return
        viewModelScope.launch {
            val current = _uiState.value.webPolicy ?: WebPolicy(deviceId = deviceId)
            val newList = (current.blockedUrls + url).distinct()
            policyRepository.updateUrlBlacklist(deviceId, newList)
            _uiState.value = _uiState.value.copy(
                webPolicy = current.copy(blockedUrls = newList),
                toast = "已拦截：$url"
            )
        }
    }

    fun removeUrlBlock(url: String) {
        viewModelScope.launch {
            val current = _uiState.value.webPolicy ?: WebPolicy(deviceId = deviceId)
            val newList = current.blockedUrls - url
            policyRepository.updateUrlBlacklist(deviceId, newList)
            _uiState.value = _uiState.value.copy(webPolicy = current.copy(blockedUrls = newList))
        }
    }

    fun setSmartShutdown(enabled: Boolean, start: String?, end: String?) {
        viewModelScope.launch {
            val current = _uiState.value.webPolicy ?: WebPolicy(deviceId = deviceId)
            _uiState.value = _uiState.value.copy(
                webPolicy = current.copy(
                    smartShutdownEnabled = enabled,
                    smartShutdownStartTime = start,
                    smartShutdownEndTime = end
                ),
                toast = if (enabled) {
                    "已开启定时锁机（${start?.takeIf { it.isNotBlank() }?.let { TimeFormat.formatClock(it) } ?: "--:--"} ~ ${end?.takeIf { it.isNotBlank() }?.let { TimeFormat.formatClock(it) } ?: "--:--"}）"
                } else "已关闭定时锁机"
            )
        }
    }

    // ===== 系统 =====

    fun lockScreen() {
        viewModelScope.launch {
            policyRepository.lockScreen(deviceId)
                .onSuccess { _uiState.value = _uiState.value.copy(toast = "已发送锁屏指令") }
                .onFailure { _uiState.value = _uiState.value.copy(toast = "锁屏指令发送失败") }
        }
    }

    /**
     * 一键解锁。
     *
     * 服务端此前只有"锁"没有"开"，锁屏一旦下发就只能等策略自然失效 —— 单向闸门。
     * 这里补上对称的解锁通道，家长点了「去处理」才能真正放行。
     */
    fun unlockNow() {
        viewModelScope.launch {
            policyRepository.unlock(deviceId)
                .onSuccess { _uiState.value = _uiState.value.copy(toast = "已解锁设备") }
                .onFailure { _uiState.value = _uiState.value.copy(toast = "解锁失败：${it.message}") }
        }
    }

    /** 限时放行：到点自动恢复管控，适合"再玩 30 分钟就收"这类场景 */
    fun tempUnlock(minutes: Int) {
        viewModelScope.launch {
            policyRepository.tempUnlock(deviceId, minutes)
                .onSuccess { _uiState.value = _uiState.value.copy(toast = "已限时放行 $minutes 分钟") }
                .onFailure { _uiState.value = _uiState.value.copy(toast = "限时放行失败：${it.message}") }
        }
    }

    fun setEyeProtection(enabled: Boolean, level: Int) {
        viewModelScope.launch {
            policyRepository.setEyeProtection(deviceId, enabled, level)
            _uiState.value = _uiState.value.copy(
                eyeProtectionEnabled = enabled,
                eyeProtectionLevel = level,
                toast = if (enabled) "护眼已开启（等级 $level）" else "护眼已关闭"
            )
        }
    }

    fun setControlMode(mode: ControlMode) {
        viewModelScope.launch {
            policyRepository.setControlMode(deviceId, mode).onSuccess {
                _uiState.value = _uiState.value.copy(
                    device = _uiState.value.device?.copy(controlMode = mode),
                    toast = "已切换为${mode.label()}"
                )
            }
        }
    }

    fun clearToast() {
        _uiState.value = _uiState.value.copy(toast = null)
    }
}

/** 管控模式中文名 */
fun ControlMode.label(): String = when (this) {
    ControlMode.NORMAL -> "正常模式"
    ControlMode.LEARNING -> "学习模式"
    ControlMode.FOCUS -> "专注模式"
}
