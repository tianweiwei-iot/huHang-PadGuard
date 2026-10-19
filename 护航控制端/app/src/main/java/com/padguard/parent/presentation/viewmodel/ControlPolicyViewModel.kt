package com.padguard.parent.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.parent.domain.model.AppPolicy
import com.padguard.parent.domain.model.ControlMode
import com.padguard.parent.domain.model.TabletUsageSettings
import com.padguard.parent.domain.model.Device
import com.padguard.parent.domain.repository.PolicyTemplate
import com.padguard.parent.domain.model.SceneType
import com.padguard.parent.domain.model.TimeRestriction
import com.padguard.parent.domain.model.WebPolicy
import com.padguard.parent.domain.repository.AgeBand
import com.padguard.parent.domain.repository.AgeBandDefault
import com.padguard.parent.domain.repository.DeviceRepository
import com.padguard.parent.domain.repository.MinorMode
import com.padguard.parent.domain.repository.PolicyRepository
import com.padguard.parent.presentation.util.TimeFormat
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
    val minorMode: MinorMode? = null,
    val ageBandDefaults: Map<AgeBand, AgeBandDefault> = emptyMap(),
    /** 孩子端实际生效的每日时长上限（未成年人模式开启时被分龄上限覆盖，可能小于 [dailyLimitMinutes]） */
    val effectiveDailyLimitMinutes: Int = 120,
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
                // 只回写上网策略本身。
                //
                // 这里曾经同时在 copy 里写死 eyeProtectionEnabled = false / eyeProtectionLevel = 2：
                // 护眼状态属于独立的 device_settings，不在 getWebPolicy 的返回里，
                // 于是每次进页面都会把家长已经开启的护眼"显示"回退成关闭，
                // 家长再点一次保存又把这个 false 写回服务端 —— 开关反复失效且无从察觉。
                //
                // 根治要服务端补出 GET /policies/{deviceId}/eye-protection；
                // 在那之前先停止用常量覆盖，保留本会话内 UI 侧的真实操作结果。
                _uiState.value = _uiState.value.copy(webPolicy = wp)
            }
            // 家长端「时间管控」页设置（平板使用时间，权威来源：weekday/weekend 额度）
            val tablet = policyRepository.getTabletUsageSettings(deviceId).getOrNull()
            // 未成年人模式：仅作合规兜底，不再夹逼家长已设额度；计算孩子端实际生效额度
            val minor = policyRepository.getMinorMode(deviceId).getOrNull()
            val defaults = policyRepository.getAgeBandDefaults().getOrNull().orEmpty()
            val effective = computeEffectiveDailyLimitMinutes(
                enabled = tablet?.enabled ?: true,
                weekdayLimitMinutes = tablet?.weekdayLimitMinutes ?: 60,
                weekendLimitMinutes = tablet?.weekendLimitMinutes ?: 60,
                minorMode = minor,
                ageBandDefaults = defaults,
                isWeekend = isWeekendToday()
            )
            _uiState.value = _uiState.value.copy(
                dailyLimitMinutes = tablet?.weekdayLimitMinutes ?: 60,
                minorMode = minor,
                ageBandDefaults = defaults,
                effectiveDailyLimitMinutes = effective
            )
            // 仅取家庭场景模板（过滤校园考试模式）
            policyRepository.getPolicyTemplates(SceneType.FAMILY).onSuccess { list ->
                _uiState.value = _uiState.value.copy(
                    templates = list.filter { it.sceneType == SceneType.FAMILY }
                )
            }
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    /** 今天是否周末（与服务端默认 Asia/Shanghai 时区、孩子端时段判定一致） */
    private fun isWeekendToday(): Boolean {
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val dow = java.time.LocalDate.now(zone).dayOfWeek
        return dow == java.time.DayOfWeek.SATURDAY || dow == java.time.DayOfWeek.SUNDAY
    }

    // ===== 时长 =====

    fun setDailyLimit(minutes: Int) {
        viewModelScope.launch {
            // 改走「时间管控」权威来源（tablet-usage-settings），与服务端 buildPolicyPackage 下发的 appLimit 一致；
            // 旧 setGlobalDailyLimit 写入的 DeviceSetting.dailyLimitMinutes 服务端根本不读，是无效写入。
            val current = policyRepository.getTabletUsageSettings(deviceId).getOrNull()
                ?: TabletUsageSettings(deviceId = deviceId)
            policyRepository.updateTabletUsageSettings(
                current.copy(
                    weekdayLimitMinutes = minutes,
                    weekendLimitMinutes = minutes,
                    syncToDevice = true
                )
            ).onSuccess {
                _uiState.value = _uiState.value.copy(dailyLimitMinutes = minutes, toast = "已保存每日时长限制")
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(error = e.message)
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
                // 同样走「时间管控」权威来源，避免写入服务端不读的旧字段
                val current = policyRepository.getTabletUsageSettings(deviceId).getOrNull()
                    ?: TabletUsageSettings(deviceId = deviceId)
                policyRepository.updateTabletUsageSettings(
                    current.copy(
                        weekdayLimitMinutes = limitMinutes,
                        weekendLimitMinutes = limitMinutes,
                        syncToDevice = true
                    )
                )
            }
            val limitLabel = limitMinutes
                ?.let { " · ${TimeFormat.formatDurationFromMinutes(it)}" }
                .orEmpty()
            _uiState.value = _uiState.value.copy(
                dailyLimitMinutes = limitMinutes ?: _uiState.value.dailyLimitMinutes,
                device = _uiState.value.device?.copy(sceneMode = preset.controlMode),
                toast = "已应用：${preset.displayName}（${preset.controlMode.label()}$limitLabel）"
            )
        }
    }

    // ===== 应用 =====

    fun toggleAppBlock(app: AppPolicy, blocked: Boolean) {
        viewModelScope.launch {
            val updated = app.copy(isBlocked = blocked)
            // 应用黑名单开关必须走 updateAppBlacklist（按整包名单一次下发），
            // 之前误走 setAppTimeLimit 会写成"全局每日总时长"端点（dailyLimitMinutes=null → 全局限额清零），
            // 且根本不下发任何黑名单 —— 界面说已禁用、孩子端照常能开。
            val blockedPackages = _uiState.value.installedApps.mapNotNull { a ->
                val isBlocked = if (a.packageName == app.packageName) blocked else a.isBlocked
                if (isBlocked) a.packageName else null
            }
            policyRepository.updateAppBlacklist(deviceId, blockedPackages)
            _uiState.value = _uiState.value.copy(
                installedApps = _uiState.value.installedApps.map {
                    if (it.packageName == app.packageName) updated else it
                },
                toast = if (blocked) "已禁用：${app.appName}" else "已解禁：${app.appName}"
            )
        }
    }

    /**
     * 一键管控 / 一键放行全部应用。
     *
     * 走批量接口 [com.padguard.parent.domain.repository.PolicyRepository.updateAppBlacklist]，
     * 而不是循环调用 [toggleAppBlock]：逐个调用会给服务端打 N 次请求、
     * 触发 N 次策略重建，既慢，又会让终端在半途停在"部分生效"的中间态。
     * 批量接口一次下发、一次重建，状态始终一致。
     */
    fun setAllAppsBlocked(blocked: Boolean) {
        viewModelScope.launch {
            val apps = _uiState.value.installedApps
            if (apps.isEmpty()) return@launch
            val packages = if (blocked) apps.map { it.packageName } else emptyList()
            policyRepository.updateAppBlacklist(deviceId, packages)
            _uiState.value = _uiState.value.copy(
                installedApps = apps.map { it.copy(isBlocked = blocked) },
                toast = if (blocked) "已管控全部 ${apps.size} 个应用，孩子端将不再显示"
                else "已放行全部应用"
            )
        }
    }

    fun setAppTimeLimit(app: AppPolicy, minutes: Int?) {
        viewModelScope.launch {
            val updated = app.copy(dailyLimitMinutes = minutes)
            // 单应用限时改走 setAppLimit（per-app 端点），
            // 之前误走 setAppTimeLimit 会写入"全局每日总时长"端点，造成单应用限额与全局限额互相覆盖、单应用限额实际不生效。
            policyRepository.setAppLimit(deviceId, app.packageName, minutes ?: 0)
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

    /**
     * 定时锁机（夜间锁机）。
     *
     * ## 历史缺陷
     * 此前这里只改内存 `_uiState` 并弹出"已开启定时锁机"，**没有任何持久化动作**。
     * 后果是三重的：服务端策略包里始终没有这两个字段、孩子端永远不执行、
     * 家长离开页面再回来开关就复位了 —— 一条提示成功的 toast 换来一个完全不生效的功能。
     *
     * ## 现在的语义
     * 先写服务端，写成功才更新 UI。写失败时保留原状态并如实报错，
     * 绝不先给用户成功的假象再悄悄回退。
     */
    fun setSmartShutdown(enabled: Boolean, start: String?, end: String?) {
        viewModelScope.launch {
            policyRepository.setSmartShutdown(deviceId, enabled, start, end)
                .onSuccess {
                    val current = _uiState.value.webPolicy ?: WebPolicy(deviceId = deviceId)
                    val normalizedStart = start?.takeIf { it.isNotBlank() && enabled }
                    val normalizedEnd = end?.takeIf { it.isNotBlank() && enabled }
                    _uiState.value = _uiState.value.copy(
                        webPolicy = current.copy(
                            smartShutdownEnabled = enabled,
                            smartShutdownStartTime = normalizedStart,
                            smartShutdownEndTime = normalizedEnd
                        ),
                        toast = if (enabled) {
                            val s = normalizedStart?.let { TimeFormat.formatClock(it) } ?: "--:--"
                            val e = normalizedEnd?.let { TimeFormat.formatClock(it) } ?: "--:--"
                            "已开启定时锁机（$s ~ $e）"
                        } else "已关闭定时锁机"
                    )
                }
                .onFailure { t ->
                    _uiState.value = _uiState.value.copy(
                        toast = "定时锁机保存失败：${t.message ?: "请重试"}"
                    )
                }
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
     * 服务端早期只有"锁"没有"开"，锁屏一旦下发就只能等策略自然失效 —— 单向闸门。
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
                    device = _uiState.value.device?.copy(sceneMode = mode),
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
