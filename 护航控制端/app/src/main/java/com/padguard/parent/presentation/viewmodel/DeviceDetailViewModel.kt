package com.padguard.parent.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.parent.domain.model.AppUploadMeta
import com.padguard.parent.domain.model.Device
import com.padguard.parent.domain.model.DeviceFunctionConfig
import com.padguard.parent.domain.model.DevicePermissionConfig
import com.padguard.parent.domain.model.AppUpgradeItem
import com.padguard.parent.domain.model.DistributableApp
import com.padguard.parent.domain.model.LocationInfo
import com.padguard.parent.domain.model.ReportPeriod
import com.padguard.parent.domain.model.StatisticsReport
import com.padguard.parent.domain.model.UsageStats
import com.padguard.parent.domain.repository.AgeBand
import com.padguard.parent.domain.repository.AgeBandDefault
import com.padguard.parent.domain.repository.DeviceRepository
import com.padguard.parent.domain.repository.LocationRepository
import com.padguard.parent.domain.repository.CommandResult
import com.padguard.parent.domain.repository.MinorMode
import com.padguard.parent.domain.repository.PolicyRepository
import com.padguard.parent.domain.repository.StatisticsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
    val uploading: Boolean = false,
    val upgradeItems: List<AppUpgradeItem> = emptyList(),
    val isUpgrading: Boolean = false,
    val monthlyReport: StatisticsReport? = null,
    // === 未成年人模式（合规底座） ===
    val minorMode: MinorMode? = null,
    /** 各龄档合规默认值，用于在下发前就把后果展示给家长 */
    val ageBandDefaults: Map<AgeBand, AgeBandDefault> = emptyMap(),
    /**
     * 孩子端实际生效的每日时长上限（分钟），与首页看板 / 孩子端锁机同一口径：
     * 以「时间管控」页设置为准，未成年人模式仅作合规兜底。供未成年人模式卡片展示。
     */
    val effectiveDailyLimitMinutes: Int = 60,
    val minorModeBusy: Boolean = false,
    /** 最近一条指令的执行结果，供界面展示"到底生效了没有" */
    val lastCommand: CommandResult? = null,
    // === 受管控水印 ===
    val watermarkEnabled: Boolean = false,
    /** 水印文案；空串表示用设备端默认文案 */
    val watermarkContent: String = "",
    val watermarkBusy: Boolean = false,
    // === 操作密码闸门（C 端软拦截方案） ===
    /** 是否已设置操作密码（仅表示家长端下发的意图，设备端是否真正生效以孩子端为准） */
    val guardPasswordSet: Boolean = false,
    val guardPasswordBusy: Boolean = false,
    /** 正在执行「立即定位」指令链（防连点） */
    val locating: Boolean = false
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
                    _uiState.value = _uiState.value.copy(
                        device = device,
                        // 以服务端派生值为准：冷启动时也能正确反映「设备操作密码是否已设」，
                        // 不再依赖 setGuardPassword 的会话内乐观标记。
                        guardPasswordSet = device.guardPasswordSet
                    )
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

    /**
     * 一键锁屏。
     *
     * 下发后**继续追踪执行结果**：只显示"已发送"的话，设备没反应时家长无从判断
     * 是设备离线、指令没送达、还是执行失败 —— 而这三种情况的处理方式完全不同。
     * 这里把最终状态写进 toast，让"为什么没生效"在界面上可见。
     */
    fun lockScreen() {
        issueTracked("锁屏", "锁定") { policyRepository.lockScreen(deviceId) }
    }

    /** 与 [lockScreen] 对称，同样追踪执行结果 */
    fun unlockScreen() {
        issueTracked("解锁", "解除锁定") { policyRepository.unlockScreen(deviceId) }
    }

    /**
     * 受管控水印开关。
     *
     * @param enabled 打开还是收起水印
     * @param content 自定义文案，留空则设备端用默认"受管控设备"
     *
     * 为什么水印开关要做成一个独立能力，而不是藏在设置里：
     * 家长提这个需求时，真实意图往往是"录屏时别显示红点"。
     * 而那个红点是系统在 MediaProjection 期间绘制的隐私指示器，关不掉。
     * 把水印做成一个显眼、可自定义、可随时关闭的开关，
     * 是把"做不到的事"换成"能做到且同样满足诉求的事"。
     */
    fun setWatermark(enabled: Boolean, content: String = "") {
        val trimmed = content.trim().take(30)
        _uiState.value = _uiState.value.copy(watermarkBusy = true)
        issueTracked(
            actionName = if (enabled) "水印" else "关闭水印",
            doneWord = if (enabled) "显示水印" else "收起水印",
            onSettled = { result ->
                _uiState.value.copy(
                    watermarkBusy = false,
                    // 只有真的执行成功才把开关切过去：
                    // 提前切会让家长以为生效了，实际设备端还是旧状态。
                    watermarkEnabled = if (result.executed) enabled else _uiState.value.watermarkEnabled,
                    watermarkContent = if (result.executed) trimmed else _uiState.value.watermarkContent
                )
            }
        ) { policyRepository.setWatermark(deviceId, enabled, trimmed.ifBlank { null }) }
    }

    /**
     * 操作密码闸门：家长端设置 / 清除「设备操作密码」（C 端软拦截方案）。
     *
     * 密码由服务端按 SHA-256(明文) 的 Base64 哈希落库后随策略包下发，
     * 孩子端只存哈希、明文永不出机；开启后，孩子要进入系统设置 / 卸载 / 安装未知来源应用
     * 都必须输入此密码（由孩子端无障碍服务拦截）。
     *
     * 这里只做一次性同步下发（不像锁屏那样需要轮询指令结果），
     * 下发成功即认为已同步，并据 [clear] 切换「是否已设置」的本地标记用于界面展示。
     */
    fun setGuardPassword(password: String, clear: Boolean = false) {
        _uiState.value = _uiState.value.copy(guardPasswordBusy = true)
        viewModelScope.launch {
            policyRepository.setGuardPassword(deviceId, if (clear) null else password, clear)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        guardPasswordBusy = false,
                        guardPasswordSet = !clear && password.isNotBlank(),
                        toast = if (clear) "操作密码已清除" else "操作密码已设置，已同步到孩子端"
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        guardPasswordBusy = false,
                        toast = "操作密码设置失败：${it.message}"
                    )
                }
        }
    }

    /**
     * 下发指令并轮询结果。
     *
     * @param actionName 操作名，用于文案
     * @param doneWord 成功时的动词（"锁定"/"解除锁定"）
     */
    // 注意参数顺序：[issue] 必须留在最后。
    // 调用方普遍写成尾随 lambda（`issueTracked("锁屏", "锁定") { ... }`），
    // 而尾随 lambda 只匹配最后一个参数 —— 把带默认值的 [onSettled] 放在它之后，
    // 会让既有调用全部匹配错位并报"No value passed for parameter 'issue'"。
    private fun issueTracked(
        actionName: String,
        doneWord: String,
        /** 结果落定后的额外状态更新（例如开关类指令要把按钮切到新状态） */
        onSettled: (CommandResult) -> DeviceDetailUiState = { _uiState.value },
        issue: suspend () -> Result<CommandResult>
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(toast = "正在下发${actionName}指令…")
            issue().onSuccess { cmd ->
                _uiState.value = _uiState.value.copy(
                    toast = "${actionName}指令已下发，等待设备执行…",
                    lastCommand = cmd
                )
                val finalResult = awaitCommandResult(cmd.msgId)
                // 先套用 onSettled 的增量，再写 toast —— 顺序颠倒会被 toast 覆盖掉
                _uiState.value = onSettled(finalResult).copy(
                    lastCommand = finalResult,
                    toast = describeCommandResult(actionName, doneWord, finalResult)
                )
            }.onFailure {
                _uiState.value = _uiState.value.copy(toast = "${actionName}指令下发失败：${it.message}")
            }
        }
    }

    /**
     * 轮询指令结果直到落定。
     *
     * 服务端不向家长端推送指令结果，只能主动查。轮询上限取 15 秒：
     * 实测指令闭环 P95 在 1.3 秒左右，15 秒足够覆盖正常与弱网情形，
     * 再长就会让界面长时间停在"等待执行"，反而像卡住。
     */
    private suspend fun awaitCommandResult(msgId: String, timeoutMs: Long = 15_000L): CommandResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = CommandResult(msgId = msgId, status = "PENDING")
        while (System.currentTimeMillis() < deadline) {
            val r = policyRepository.getCommand(msgId)
            if (r.isFailure) return last
            last = r.getOrThrow()
            if (!last.pending) return last
            kotlinx.coroutines.delay(800)
        }
        return last
    }

    /** 把指令状态翻译成家长看得懂、且知道下一步该做什么的文案 */
    private fun describeCommandResult(actionName: String, doneWord: String, r: CommandResult): String = when {
        r.executed -> "设备已$doneWord"
        r.failed -> buildString {
            append("${actionName}指令已送达，但设备执行失败")
            val reason = r.failureReason
            if (!reason.isNullOrBlank()) append("：$reason")
            else append(" —— 请检查设备是否处于管控中")
        }
        r.expired -> "${actionName}指令超时未执行 —— 设备可能离线或网络不通"
        else -> "${actionName}指令已下发，设备尚未响应 —— 设备可能离线，稍后可再试一次"
    }

    /**
     * 立即定位（原「定位」按钮只弹"已发送"假 toast，一个请求都不发）。
     *
     * 走真实指令链路：POST /v1/monitor/{deviceId}/locate → 服务端下发 LOCATE 指令 →
     * 孩子端当场补采一次位置并上报。定位是异步的，下发成功后延迟两次拉取最新坐标
     * （延迟档位与 LocationViewModel.locateNow 一致：3s / 5s，覆盖端上采集+上报时延）。
     */
    fun locateNow() {
        if (deviceId.isBlank()) return
        if (_uiState.value.locating) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(locating = true)
            locationRepository.requestLocate(deviceId)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(toast = "定位指令已下发，正在获取最新位置…")
                    delay(3_000)
                    locationRepository.getDeviceLocation(deviceId)
                        .onSuccess { _uiState.value = _uiState.value.copy(location = it) }
                    delay(5_000)
                    locationRepository.getDeviceLocation(deviceId)
                        .onSuccess { _uiState.value = _uiState.value.copy(location = it) }
                    _uiState.value = _uiState.value.copy(locating = false, toast = "位置已更新")
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        locating = false,
                        toast = "定位指令下发失败：${it.message}"
                    )
                }
        }
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

    /**
     * 一键分发安装。
     *
     * @param force false（默认）：设备已装同包名且版本不低于目录版本时，服务端直接拒绝并提示"无需分发"，
     *              避免重复下发；true：无条件下发 —— 这是"应用被孩子弄坏 / 装残了要重装"的唯一途径，
     *              没有它，家长只能先卸载再分发，中间若卸载失败就彻底卡住。
     */
    fun dispatchApp(catalogId: String, force: Boolean = false) {
        viewModelScope.launch {
            deviceRepository.dispatchApp(deviceId, catalogId, force)
                .onSuccess {
                    showToast(if (force) "已下发强制重装：$catalogId" else "已下发分发安装：$catalogId")
                }
                .onFailure { showToast(it.message ?: "应用分发失败") }
        }
    }

    fun uploadApp(bytes: ByteArray, meta: AppUploadMeta, fileName: String) {
        _uiState.value = _uiState.value.copy(uploading = true)
        viewModelScope.launch {
            deviceRepository.uploadAppCatalog(deviceId, bytes, meta, fileName)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(uploading = false, toast = "已上传到应用库：${meta.appName}")
                    loadDistributableApps()
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(uploading = false)
                    showToast(it.message ?: "上传失败")
                }
        }
    }

    fun deleteApp(catalogId: String) {
        viewModelScope.launch {
            deviceRepository.deleteAppCatalog(deviceId, catalogId)
                .onSuccess {
                    showToast("已删除：$catalogId")
                    loadDistributableApps()
                }
                .onFailure { showToast(it.message ?: "删除失败") }
        }
    }

    private fun loadUpgradeInfo() {
        viewModelScope.launch {
            deviceRepository.getDeviceUpgradeInfo(deviceId)
                .onSuccess { _uiState.value = _uiState.value.copy(upgradeItems = it) }
        }
    }

    fun startUpgrade(catalogId: String) {
        if (_uiState.value.isUpgrading) return
        _uiState.value = _uiState.value.copy(isUpgrading = true)
        viewModelScope.launch {
            deviceRepository.upgradeDevice(deviceId, catalogId)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(isUpgrading = false, toast = "升级指令已下发，平板正在安装…")
                    loadUpgradeInfo()
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(isUpgrading = false)
                    showToast(it.message ?: "升级失败")
                }
        }
    }

    /**
     * 从被控平板卸载应用（对应应用分发面板的「卸载」）。
     *
     * 与 [deleteApp] 的边界必须分清，这是最容易误操作的地方：
     * - [deleteApp]：删除**应用库**（服务端目录库）条目，平板上的应用原样保留；
     * - 本方法：下发 UNINSTALL_APP 指令，真正把应用从平板移除。
     *
     * 服务端另有一道自我保护（拒绝卸载管控端自身，packageName 以 .child 结尾），
     * 故这里只需挡住空包名，不必重复实现那套判定。
     */
    fun uninstallDistributedApp(packageName: String) {
        if (packageName.isBlank()) return
        viewModelScope.launch {
            policyRepository.uninstallApp(deviceId, packageName)
                .onSuccess { showToast("已下发卸载指令：$packageName") }
                .onFailure { showToast(it.message ?: "卸载失败") }
        }
    }

    // ==================== 未成年人模式 ====================

    private fun loadMinorMode() {
        viewModelScope.launch {
            // 档位默认值、未成年人模式配置、「时间管控」页设置（平板使用时间，权威来源）并发拉取，
            // 统一计算孩子端实际生效的每日时长上限，避免详情页与首页看板 / 孩子端锁机值对不上。
            val defaults = async { policyRepository.getAgeBandDefaults().getOrNull().orEmpty() }
            val minor = async { policyRepository.getMinorMode(deviceId).getOrNull() }
            val tablet = async { policyRepository.getTabletUsageSettings(deviceId).getOrNull() }
            val ageBandDefaults = defaults.await()
            val minorMode = minor.await()
            val settings = tablet.await()
            val effective = computeEffectiveDailyLimitMinutes(
                enabled = settings?.enabled ?: true,
                weekdayLimitMinutes = settings?.weekdayLimitMinutes ?: 60,
                weekendLimitMinutes = settings?.weekendLimitMinutes ?: 60,
                minorMode = minorMode,
                ageBandDefaults = ageBandDefaults,
                isWeekend = isWeekendToday()
            )
            _uiState.value = _uiState.value.copy(
                ageBandDefaults = ageBandDefaults,
                minorMode = minorMode,
                effectiveDailyLimitMinutes = effective
            )
        }
    }

    /** 今天是否周末（与服务端默认 Asia/Shanghai 时区、孩子端时段判定一致） */
    private fun isWeekendToday(): Boolean {
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val dow = java.time.LocalDate.now(zone).dayOfWeek
        return dow == java.time.DayOfWeek.SATURDAY || dow == java.time.DayOfWeek.SUNDAY
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
