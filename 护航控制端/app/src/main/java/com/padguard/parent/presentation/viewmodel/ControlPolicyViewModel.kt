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
import com.padguard.parent.data.remote.readableActionMessage
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import java.util.UUID

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
    /**
     * 时段列表是否已从服务端读到。
     *
     * 空列表有两种含义："还没读到"和"确实一条都没配"。此前都渲染成空白，
     * 家长既看不到空态提示，也找不到新增入口 —— 新设备的分时段限制等于不存在。
     */
    val timeRestrictionsLoaded: Boolean = false,
    /** 时段列表读取失败的原因；与 [timeRestrictionsLoaded] 一起把"空/失败/加载中"三态分开 */
    val timeRestrictionsError: String? = null,
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

    /**
     * 应用名单提交串行化。
     *
     * `updateAppBlacklist` 是**全量覆盖**语义（PUT 整个 blockedPackages），而请求体在发送瞬间
     * 由 `_uiState.installedApps` 拼装、本地状态要等响应成功才更新。家长在 1 个 RTT 内连续
     * 关掉应用 A、再关掉 B 时，第二次的名单里 A 仍是"未禁用" —— 后一次 PUT 把 A 的改动整体盖掉。
     * 结果是界面显示"A 已禁用"、服务端名单里没有 A、孩子端照常能打开，且家长完全无从察觉。
     * 加锁后第二次提交会等第一次落地并读到最新状态，全量覆盖语义才真正成立。
     */
    private val appListMutex = Mutex()

    init { load() }

    fun load() {
        if (deviceId.isBlank()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            deviceRepository.getDeviceDetail(deviceId).onSuccess { device ->
                _uiState.value = _uiState.value.copy(device = device)
            }
            policyRepository.getTimeRestrictions(deviceId)
                .onSuccess { list ->
                    _uiState.value = _uiState.value.copy(
                        timeRestrictions = list,
                        timeRestrictionsLoaded = true,
                        timeRestrictionsError = null
                    )
                }
                .onFailure { e ->
                    // 读取失败不能渲染成"暂无时段"：家长会以为自己没配过，
                    // 而实际是网络/服务异常，已有的时段仍在孩子端生效。
                    _uiState.value = _uiState.value.copy(
                        timeRestrictionsLoaded = true,
                        timeRestrictionsError = e.message ?: "读取失败"
                    )
                }
            policyRepository.getInstalledApps(deviceId).onSuccess { list ->
                _uiState.value = _uiState.value.copy(installedApps = list)
            }.onFailure { e ->
                // 读取失败必须可见：否则页面显示"没有已安装应用"，家长会以为平板是空的，
                // 随后任何开关操作都会被上面的空列表防线拦下，却不知道原因。
                _uiState.value = _uiState.value.copy(toast = "应用列表读取失败：${e.message ?: "请稍后重试"}")
            }
            policyRepository.getWebPolicy(deviceId).onSuccess { wp ->
                // 只回写上网策略本身。
                //
                // 这里曾经同时在 copy 里写死 eyeProtectionEnabled = false / eyeProtectionLevel = 2：
                // 护眼状态属于独立的 device_settings，不在 getWebPolicy 的返回里，
                // 于是每次进页面都会把家长已经开启的护眼"显示"回退成关闭，
                // 家长再点一次保存又把这个 false 写回服务端 —— 开关反复失效且无从察觉。
                //
                // 服务端已补出 GET /policies/{deviceId}/eye-protection，见下方单独读取。
                _uiState.value = _uiState.value.copy(webPolicy = wp)
            }
            // 护眼状态是独立字段（device_settings），必须单独读：
            // 不读就只能用常量兜底，家长每次重进页面都会看到"已关闭"，
            // 再点一次开关又把这个关闭写回服务端 —— 开关看起来生效、实际每次都被自己关掉。
            policyRepository.getEyeProtection(deviceId).onSuccess { eye ->
                _uiState.value = _uiState.value.copy(
                    eyeProtectionEnabled = eye.enabled,
                    eyeProtectionLevel = eye.level.coerceIn(1, 5)
                )
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
            // ⚠️ 读取失败必须中止写入：拿出厂默认值当"现状"提交，会把服务端真实配置
            // （家长已关闭时间管控 / 自定义时段与额度）整体覆盖成默认值并立即下发到孩子端 ——
            // 这是"家长没做的管控变更被静默执行"，属于管控产品最危险的误操作。
            val current = policyRepository.getTabletUsageSettings(deviceId).getOrNull()
            if (current == null) {
                _uiState.value = _uiState.value.copy(
                    error = "读取当前时间管控设置失败，请检查网络后重试（已放弃本次修改，避免覆盖孩子端现有配置）"
                )
                return@launch
            }
            // 只改家长意图改的字段：顺带把周末额度也改成同值，会覆盖家长为周末单独设置的额度。
            policyRepository.updateTabletUsageSettings(
                current.copy(
                    weekdayLimitMinutes = minutes,
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
            // 必须判 Result：早期版本直接丢弃返回值并立刻弹"已生效"，
            // 弱网/订阅过期/设备解绑时服务端什么都没发生，界面却显示成功 ——
            // 家长以为禁用了某时段，孩子端照常用（"假成功"是管控类产品最不可接受的缺陷）。
            policyRepository.setTimeRestriction(updated)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        timeRestrictions = _uiState.value.timeRestrictions.map {
                            if (it.id == restriction.id) updated else it
                        },
                        toast = if (updated.isEnabled) "已启用该时段限制" else "已停用该时段限制"
                    )
                }
                .onFailure { e -> fail("时段限制保存失败", e) }
        }
    }

    /**
     * 新增或编辑一条时段限制。
     *
     * 服务端 PUT 是 upsert：带 id 且命中则更新，否则新建。
     * 保存后整表回读而不是本地拼装 —— 新建时 id 由服务端生成，
     * 本地塞一个假 id 会让下一次编辑/删除作用不到真实记录。
     *
     * @return 校验失败的文案（由 UI 就地提示）；通过校验则返回 null，写操作异步进行。
     */
    fun saveTimeRestriction(
        id: String?,
        dayOfWeek: Int,
        startTime: String,
        endTime: String,
        maxMinutes: Int?,
        isEnabled: Boolean
    ): String? {
        val start = startTime.trim()
        val end = endTime.trim()
        if (!isValidClock(start) || !isValidClock(end)) return "时间格式应为 HH:mm，例如 21:30"
        if (start == end) return "开始时间与结束时间不能相同"
        if (maxMinutes != null && maxMinutes !in 1..1440) return "该时段限时应在 1~1440 分钟之间"

        val restriction = TimeRestriction(
            id = id ?: UUID.randomUUID().toString(),
            deviceId = deviceId,
            dayOfWeek = dayOfWeek,
            startTime = start,
            endTime = end,
            maxMinutes = maxMinutes,
            isEnabled = isEnabled
        )
        viewModelScope.launch {
            policyRepository.setTimeRestriction(restriction)
                .onSuccess {
                    refreshTimeRestrictions()
                    _uiState.value = _uiState.value.copy(
                        toast = if (id == null) "已新增时段限制" else "已保存时段限制"
                    )
                }
                .onFailure { e -> fail("时段限制保存失败", e) }
        }
        return null
    }

    fun deleteTimeRestriction(restrictionId: String) {
        viewModelScope.launch {
            // deviceId 必须一起带：服务端按 (deviceId, id) 校验归属
            policyRepository.deleteTimeRestriction(deviceId, restrictionId)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        timeRestrictions = _uiState.value.timeRestrictions.filterNot { it.id == restrictionId },
                        toast = "已删除该时段限制"
                    )
                }
                .onFailure { e -> fail("删除时段限制失败", e) }
        }
    }

    private suspend fun refreshTimeRestrictions() {
        policyRepository.getTimeRestrictions(deviceId)
            .onSuccess { list ->
                _uiState.value = _uiState.value.copy(
                    timeRestrictions = list,
                    timeRestrictionsLoaded = true,
                    timeRestrictionsError = null
                )
            }
    }

    private fun isValidClock(v: String): Boolean = Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(v)

    fun applyPreset(preset: FamilyPreset) {
        viewModelScope.launch {
            // 两步都必须判 Result：预设包含"切模式 + 改限额"两个独立写操作，
            // 任一步失败都要如实告诉家长，不能两个都丢掉返回值然后统一弹"已应用"。
            val modeResult = policyRepository.setControlMode(deviceId, preset.controlMode)
            if (modeResult.isFailure) {
                fail("应用预设失败（切换管控模式未成功）", modeResult.exceptionOrNull())
                return@launch
            }
            val limitMinutes = preset.dailyLimitMinutes
            if (limitMinutes != null) {
                // 同样走「时间管控」权威来源，避免写入服务端不读的旧字段。
                // 读取失败必须中止：拿默认值当现状会把服务端真实配置整体覆盖成默认值并下发（同 setDailyLimit）。
                val current = policyRepository.getTabletUsageSettings(deviceId).getOrNull()
                if (current == null) {
                    fail("应用预设失败（读取当前时间管控设置失败）", IllegalStateException("get tablet-usage-settings failed"))
                    return@launch
                }
                val limitResult = policyRepository.updateTabletUsageSettings(
                    current.copy(
                        weekdayLimitMinutes = limitMinutes,
                        syncToDevice = true
                    )
                )
                if (limitResult.isFailure) {
                    fail("应用预设失败（每日时长未保存）", limitResult.exceptionOrNull())
                    return@launch
                }
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
            appListMutex.withLock {
                // 应用列表没加载成功时，installedApps 为空（或只剩部分），
                // 而 updateAppBlacklist 是**全量覆盖**语义 —— 此时提交等于把其余
                // 已管控应用静默解禁，孩子端立刻能打开。宁可拒绝操作并提示重试。
                if (_uiState.value.installedApps.isEmpty()) {
                    _uiState.value = _uiState.value.copy(toast = "应用列表未加载完成，请稍后重试")
                    return@launch
                }
                val updated = app.copy(isBlocked = blocked)
                // 应用黑名单开关必须走 updateAppBlacklist（按整包名单一次下发），
                // 之前误走 setAppTimeLimit 会写成"全局每日总时长"端点（dailyLimitMinutes=null → 全局限额清零），
                // 且根本不下发任何黑名单 —— 界面说已禁用、孩子端照常能开。
                val blockedPackages = _uiState.value.installedApps.mapNotNull { a ->
                    val isBlocked = if (a.packageName == app.packageName) blocked else a.isBlocked
                    if (isBlocked) a.packageName else null
                }
                policyRepository.updateAppBlacklist(deviceId, blockedPackages)
                    .onSuccess {
                        _uiState.value = _uiState.value.copy(
                            installedApps = _uiState.value.installedApps.map {
                                if (it.packageName == app.packageName) updated else it
                            },
                            toast = if (blocked) "已禁用：${app.appName}" else "已解禁：${app.appName}"
                        )
                    }
                    .onFailure { e -> fail(if (blocked) "禁用失败" else "解禁失败", e) }
            }
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
            appListMutex.withLock {
                val apps = _uiState.value.installedApps
                // 空列表时原来直接 return：开关弹回去、家长不知道为什么没反应。
                if (apps.isEmpty()) {
                    _uiState.value = _uiState.value.copy(toast = "应用列表未加载完成，请稍后重试")
                    return@launch
                }
                val packages = if (blocked) apps.map { it.packageName } else emptyList()
                policyRepository.updateAppBlacklist(deviceId, packages)
                    .onSuccess {
                        _uiState.value = _uiState.value.copy(
                            installedApps = apps.map { it.copy(isBlocked = blocked) },
                            toast = if (blocked) "已管控全部 ${apps.size} 个应用，孩子端将不再显示"
                            else "已放行全部应用"
                        )
                    }
                    .onFailure { e -> fail(if (blocked) "一键管控失败" else "一键放行失败", e) }
            }
        }
    }

    fun setAppTimeLimit(app: AppPolicy, minutes: Int?) {
        viewModelScope.launch {
            val updated = app.copy(dailyLimitMinutes = minutes)
            // 单应用限时改走 setAppLimit（per-app 端点），
            // 之前误走 setAppTimeLimit 会写入"全局每日总时长"端点，造成单应用限额与全局限额互相覆盖、单应用限额实际不生效。
            policyRepository.setAppLimit(deviceId, app.packageName, minutes ?: 0)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        installedApps = _uiState.value.installedApps.map {
                            if (it.packageName == app.packageName) updated else it
                        },
                        toast = if (minutes == null) "已取消 ${app.appName} 时长限制"
                        else "已设置 ${app.appName} 限时 ${TimeFormat.formatDurationFromMinutes(minutes)}"
                    )
                }
                .onFailure { e -> fail("保存 ${app.appName} 时长限制失败", e) }
        }
    }

    // ===== 上网 =====

    fun setBrowserDisabled(disabled: Boolean) {
        viewModelScope.launch {
            policyRepository.setBrowserDisabled(deviceId, disabled)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        webPolicy = _uiState.value.webPolicy?.copy(browserDisabled = disabled),
                        toast = if (disabled) "已禁用浏览器" else "已启用浏览器"
                    )
                }
                .onFailure { e -> fail(if (disabled) "禁用浏览器失败" else "启用浏览器失败", e) }
        }
    }

    fun addUrlBlock(url: String) {
        if (url.isBlank()) return
        viewModelScope.launch {
            val current = _uiState.value.webPolicy ?: WebPolicy(deviceId = deviceId)
            val newList = (current.blockedUrls + url).distinct()
            policyRepository.updateUrlBlacklist(deviceId, newList)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        webPolicy = current.copy(blockedUrls = newList),
                        toast = "已拦截：$url"
                    )
                }
                .onFailure { e -> fail("添加拦截网址失败", e) }
        }
    }

    fun removeUrlBlock(url: String) {
        viewModelScope.launch {
            val current = _uiState.value.webPolicy ?: WebPolicy(deviceId = deviceId)
            val newList = current.blockedUrls - url
            policyRepository.updateUrlBlacklist(deviceId, newList)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(webPolicy = current.copy(blockedUrls = newList))
                }
                .onFailure { e -> fail("移除拦截网址失败", e) }
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
                .onFailure { _uiState.value = _uiState.value.copy(toast = "锁屏指令发送失败：" + it.readableActionMessage("请检查网络后重试")) }
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
                .onFailure { _uiState.value = _uiState.value.copy(toast = "解锁失败：" + it.readableActionMessage("请检查网络后重试")) }
        }
    }

    /** 限时放行：到点自动恢复管控，适合"再玩 30 分钟就收"这类场景 */
    fun tempUnlock(minutes: Int) {
        viewModelScope.launch {
            policyRepository.tempUnlock(deviceId, minutes)
                .onSuccess { _uiState.value = _uiState.value.copy(toast = "已限时放行 $minutes 分钟") }
                .onFailure { _uiState.value = _uiState.value.copy(toast = "限时放行失败：" + it.readableActionMessage("请检查网络后重试")) }
        }
    }

    fun setEyeProtection(enabled: Boolean, level: Int) {
        viewModelScope.launch {
            policyRepository.setEyeProtection(deviceId, enabled, level)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        eyeProtectionEnabled = enabled,
                        eyeProtectionLevel = level,
                        toast = if (enabled) "护眼已开启（等级 $level）" else "护眼已关闭"
                    )
                }
                .onFailure { e -> fail(if (enabled) "开启护眼失败" else "关闭护眼失败", e) }
        }
    }

    fun setControlMode(mode: ControlMode) {
        viewModelScope.launch {
            policyRepository.setControlMode(deviceId, mode)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        device = _uiState.value.device?.copy(sceneMode = mode),
                        toast = "已切换为${mode.label()}"
                    )
                }
                .onFailure { e -> fail("切换管控模式失败", e) }
        }
    }

    fun clearToast() {
        _uiState.value = _uiState.value.copy(toast = null)
    }

    /** 错误已通过 Snackbar 展示后清除，避免返回本页时重复弹出同一错误 */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /**
     * 统一的写失败反馈。
     *
     * 管控类写操作一旦静默失败，家长看到的仍是"已生效"，而孩子端完全没收到策略 ——
     * 这是本产品最不能接受的一类缺陷（比直接报错严重得多）。
     * 这里统一落到 `error`（而非 toast）：错误需要显式确认，一闪而过的 toast 会被忽略。
     */
    private fun fail(action: String, cause: Throwable?) {
        // readableActionMessage 会按业务码翻译可执行提示（如 4001 → 引导去续费），
        // 其余沿用原文案 —— 此前 [4001] 这类原始码直接糊在家长脸上，无法行动。
        val reason = cause?.readableActionMessage("网络或服务异常") ?: "网络或服务异常"
        _uiState.value = _uiState.value.copy(error = "$action：$reason")
    }
}

/** 管控模式中文名 */
fun ControlMode.label(): String = when (this) {
    ControlMode.NORMAL -> "正常模式"
    ControlMode.LEARNING -> "学习模式"
    ControlMode.FOCUS -> "专注模式"
}
