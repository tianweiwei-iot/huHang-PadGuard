package com.padguard.parent.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.parent.data.remote.ParentPushClient
import com.padguard.parent.data.remote.ParentPushMessage
import com.padguard.parent.domain.model.AgreementStatus
import com.padguard.parent.domain.model.AppUploadMeta
import com.padguard.parent.domain.model.Device
import com.padguard.parent.domain.model.DeviceFunctionConfig
import com.padguard.parent.domain.model.DevicePermissionConfig
import com.padguard.parent.domain.model.MediaSource
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
import kotlinx.coroutines.Job
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
    /**
     * 最近一次成功读到设备详情的时刻（墙钟毫秒）。
     *
     * 详情页里"守护进程 / 无障碍 / 链路"都是**设备当下的状态**。刷新失败时这些 chip 仍按旧值渲染，
     * 家长看到"一切正常"可能是一小时前的事实——而这页恰恰是判断"管控还有没有效"的入口。
     */
    val lastSuccessAt: Long? = null,
    /**
     * 使用授权协议签署凭证（说明书 §5.4）。
     *
     * null = 本次尚未查询到结果（请求失败或还在加载）；非 null 时 `signed=false`
     * 只代表**服务端没有凭证**，不表示孩子拒绝同意。
     * 刻意不并入 [error]：这是次要合规信息，查询失败不该把整页挂成错误横幅。
     */
    val agreement: AgreementStatus? = null,
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
    val locating: Boolean = false,
    // === 整机管控 暂停/恢复（跨页面共享开关态） ===
    /** 当前设备整机管控是否生效：true=管控中，false=已释放（设备完全可用）。默认 true */
    val managementActive: Boolean = true,
    /** 切换整机管控开关时的防连点 / 加载态 */
    val managementBusy: Boolean = false
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
    private val parentPushClient: ParentPushClient,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    /** 未成年人模式配置是否成功读取过一次（失败时禁止写入，防默认值覆盖服务端配置） */
    private var minorModeLoaded = false

    /**
     * 上一次从设备列表轮询看到的「低频实时字段」指纹。
     * 详情页原本只在 init 拉一次，孩子端改资料 / 守护进程掉线 / 操作密码补设后，
     * 家长停在详情页看到的全是几分钟前甚至更旧的快照。10 秒列表轮询本来就有这些字段，
     * 这里靠指纹比对把它们变成重拉触发条件，而不是每轮都发 4 个请求。
     */
    private var lastDetailFingerprint: String? = null

    /** 串行化详情重拉：WS 快路径与列表兜底可能同一拍到达，只保留最后一次。 */
    private var detailRefreshJob: Job? = null

    private val deviceId: String = savedStateHandle.get<String>("deviceId").orEmpty()

    private val _uiState = MutableStateFlow(DeviceDetailUiState())
    val uiState: StateFlow<DeviceDetailUiState> = _uiState.asStateFlow()

    init {
        loadDeviceDetail()
        loadMinorMode()
        // 在线态跟随设备列表实时翻转：只就地改上下线，不触发整页重拉。
        // 其余实时字段（孩子资料 / 守护健康度 / 操作密码 / 防护等级 / 电量）靠指纹比对：
        // 10 秒列表轮询本来就会带回这些值，变化时才静默重拉详情（含用量/位置/合规凭证），
        // 既不每轮发 4 个请求，也不会让家长停在详情页时一直看旧快照。
        viewModelScope.launch {
            deviceRepository.observeDeviceList().collect { list ->
                val fresh = list.firstOrNull { it.id == deviceId } ?: return@collect
                val fingerprint = fresh.detailFingerprint()

                val current = _uiState.value.device
                if (current != null &&
                    (current.onlineStatus != fresh.onlineStatus || current.lastOnlineTime != fresh.lastOnlineTime)
                ) {
                    _uiState.value = _uiState.value.copy(
                        device = current.copy(
                            onlineStatus = fresh.onlineStatus,
                            lastOnlineTime = fresh.lastOnlineTime
                        )
                    )
                }

                if (lastDetailFingerprint == null) {
                    // 首帧只记基线：init 发起的详情请求已在途，不该再叠一次
                    lastDetailFingerprint = fingerprint
                    return@collect
                }
                if (fingerprint == lastDetailFingerprint) return@collect
                lastDetailFingerprint = fingerprint
                loadDeviceDetail(silent = true)
            }
        }
        // 孩子端改姓名/昵称/头像的秒级快路径：服务端写完库即推 device.profile.updated。
        // 此前该 type 无人消费，首页 ≤10s 变新、详情页仍停在进页面那一刻，同 App 内自相矛盾。
        // 列表指纹是推送丢失时的兜底，两条路径都会走同一个静默重拉（串行去重）。
        viewModelScope.launch {
            parentPushClient.messages.collect { msg ->
                if (msg is ParentPushMessage.DeviceProfileUpdated && msg.deviceId == deviceId) {
                    loadDeviceDetail(silent = true)
                }
            }
        }
        // 订阅整机管控开关态（跨页面共享）：详情页与首页卡片读同一份，保证两处一致。
        // deviceId 来自 SavedStateHandle 固定不变，流默认 true（与孩子端默认行为一致）。
        viewModelScope.launch {
            policyRepository.observeManagementActive(deviceId).collect { active ->
                _uiState.value = _uiState.value.copy(managementActive = active)
            }
        }
    }

    /**
     * @param silent 后台静默重拉（WS 快路径 / 列表指纹兜底）：不闪整页加载态、不因一次
     *               抖动把整页挂成「刷新失败」，失败时保留上一次成功的数据与 chip 状态。
     *               手动点「重试」仍走非静默，需要看到明确的错误横幅。
     */
    fun loadDeviceDetail(silent: Boolean = false) {
        if (deviceId.isBlank()) return
        // 串行：WS 与列表轮询可能同一拍到达；手动重试也要能取消在途的静默请求。
        detailRefreshJob?.cancel()
        detailRefreshJob = viewModelScope.launch {
            try {
                if (!silent) _uiState.value = _uiState.value.copy(isLoading = true, error = null)
                deviceRepository.getDeviceDetail(deviceId)
                    .onSuccess { device ->
                        _uiState.value = _uiState.value.copy(
                            device = device,
                            // 以服务端派生值为准：冷启动时也能正确反映「设备操作密码是否已设」，
                            // 不再依赖 setGuardPassword 的会话内乐观标记。
                            guardPasswordSet = device.guardPasswordSet,
                            // 设备详情是本页一切 chip / 面板的时间基准：读到了才更新"哪一刻的数据"。
                            lastSuccessAt = System.currentTimeMillis()
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
                        // 合规凭证是独立数据源：失败只让这一行不显示，不影响设备详情主流程，
                        // 也不写进页面级 error（否则一次次要接口抖动会把整页挂成"刷新失败"）。
                        launch {
                            deviceRepository.getAgreementStatus(deviceId)
                                .onSuccess { _uiState.value = _uiState.value.copy(agreement = it) }
                                .onFailure { _uiState.value = _uiState.value.copy(agreement = null) }
                        }
                    }
                    .onFailure { if (!silent) _uiState.value = _uiState.value.copy(error = it.message) }
            } finally {
                // 无论哪种模式都收尾：若首次非静默加载被后续的静默重拉取消，
                // 这里保证 isLoading 不会永久卡在 true。
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
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
     * 远程揭示管理控制台。
     *
     * 孩子端图标隐藏后家长进不去管理台时的远程通道：下发 REVEAL_MANAGEMENT_UI，
     * 孩子端点亮图标并拉起管理台（配置结束自动重新隐藏）。与悬浮卡密码闸互为备份。
     */
    fun revealConsole() {
        issueTracked("揭示管理台", "显示管理台") { policyRepository.revealConsole(deviceId) }
    }

    /**
     * 整机管控 暂停 / 恢复（家长端「释放 / 恢复对平板的全部管控」主开关）。
     *
     * 家长一键把平板整体交还（active=false）或收回（active=true）。
     * 走真实指令链路：POST /v1/policies/{id}/management-active → SET_MANAGEMENT_ACTIVE 指令 →
     * 孩子端 [com.padguard.core.engine.guard.GuardMasterSwitch] 统一释放 / 重铺锁屏浮层、
     * 设置 / 应用拦截、DO 权限与 Kiosk。本地开关态由 repository 乐观更新（两处 UI 同步），
     * 这里复用 [issueTracked] 追踪设备执行结果并给出可读提示。
     */
    fun setManagementActive(active: Boolean) {
        // 防连点：整机管控是高风险开关（暂停会释放全部管控），UI 卡片已按 managementBusy
        // 禁用按钮，但此前该字段从未置位，等于防连点形同虚设——快速双击会下发两条
        // SET_MANAGEMENT_ACTIVE。与水印/操作密码开关同款：入口判重 + 置位，两路复位。
        if (_uiState.value.managementBusy) return
        _uiState.value = _uiState.value.copy(managementBusy = true)
        issueTracked(
            actionName = if (active) "恢复管控" else "暂停管控",
            doneWord = if (active) "恢复管控" else "释放整机",
            onSettled = { result ->
                // 注意：managementActive 的权威值由 repository 的 managementActiveFlow 驱动，
                // 这里不按 result.executed 覆写开关（HTTP 失败时 repository 已回滚并经 Flow 推回；
                // 设备执行失败时保留家长意图的乐观态，靠 toast 如实提示），只负责复位 busy。
                _uiState.value.copy(managementBusy = false)
            }
        ) { policyRepository.setManagementActive(deviceId, active) }
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
                // 失败路径也必须走 onSettled：它负责把开关类 busy 标志复位（如 watermarkBusy）。
                // 只写 toast 不回调的话，一次网络失败就会让水印开关/保存按钮永久禁用，重进页面才能恢复。
                _uiState.value = onSettled(CommandResult(status = "FAILED")).copy(
                    toast = "${actionName}指令下发失败：${it.message}"
                )
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
        // 与 LocationViewModel.locateNow 同口径：只有真的拿到**更新的**坐标才说"已更新"。
        // 原来无论回拉成功与否都提示"位置已更新"，平板离线时家长会拿着一个小时前的点当实时位置。
        val beforeTs = _uiState.value.location?.timestamp ?: 0L
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
                    val after = _uiState.value.location
                    val gotNew = after != null && (after.timestamp > beforeTs || after.fresh == true)
                    _uiState.value = _uiState.value.copy(
                        locating = false,
                        toast = if (gotNew) {
                            "位置已更新"
                        } else {
                            "平板未上报新位置（可能离线或定位权限被关闭），当前显示的是上次记录"
                        }
                    )
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

    /**
     * 上传 APK 到应用库。
     *
     * [apk] 是流式来源（已落盘），本方法负责在结束（成功/失败/协程取消）时
     * `close()` 删除 cache 副本 —— 早期传的是整份 `ByteArray`，
     * 200MB 安装包在堆里就是一次 OOM 事故。
     */
    fun uploadApp(apk: MediaSource, meta: AppUploadMeta) {
        _uiState.value = _uiState.value.copy(uploading = true)
        viewModelScope.launch {
            try {
                deviceRepository.uploadAppCatalog(deviceId, apk, meta)
                    .onSuccess {
                        _uiState.value = _uiState.value.copy(uploading = false, toast = "已上传到应用库：${meta.appName}")
                        loadDistributableApps()
                    }
                    .onFailure {
                        _uiState.value = _uiState.value.copy(uploading = false)
                        showToast(it.message ?: "上传失败")
                    }
            } finally {
                runCatching { apk.close() }
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
            val minorRes = async { policyRepository.getMinorMode(deviceId) }
            val tablet = async { policyRepository.getTabletUsageSettings(deviceId).getOrNull() }
            val ageBandDefaults = defaults.await()
            val minorResult = minorRes.await()
            val minorMode = minorResult.getOrNull()
            val settings = tablet.await()
            // 读取失败不等于"未配置"：若放行后续写入，一键开启会用默认档位与 0 时长
            // 覆盖服务端已有的分龄/宵禁配置并下发（家长没做的变更被执行）。
            minorModeLoaded = minorResult.isSuccess
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
        if (!guardMinorModeLoaded()) return
        val current = _uiState.value.minorMode ?: MinorMode(deviceId = deviceId)
        applyMinorMode(current.copy(enabled = enabled))
    }

    /** 切换分龄档位；档位一变，时长与宵禁随即按新档默认值生效 */
    fun selectAgeBand(band: AgeBand) {
        if (!guardMinorModeLoaded()) return
        val current = _uiState.value.minorMode ?: MinorMode(deviceId = deviceId)
        // 切档时清掉旧的自定义时长：留着会让"8–12岁"却沿用"16–18岁"的 2 小时额度
        applyMinorMode(current.copy(ageBand = band, dailyLimitMinutes = 0, weekendLimitMinutes = 0))
    }

    /**
     * 未成年人模式配置未成功读取时，拒绝任何写操作。
     *
     * 空实现 → 会拿 `MinorMode(deviceId)` 默认值整包覆盖服务端配置并下发，
     * 属于"家长没做的管控变更被静默执行"，比操作无反应严重得多。
     */
    private fun guardMinorModeLoaded(): Boolean {
        if (minorModeLoaded) return true
        showToast("未成年人模式配置未加载成功，请稍后重试")
        return false
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

/**
 * 设备详情页关心的「低频实时字段」指纹（纯函数，便于 JVM 单测钉死字段集合）。
 *
 * 刻意排除三类字段，否则会退化成每 10 秒重拉一次详情：
 * - `onlineStatus` / `lastOnlineTime`：后者每次心跳都刷新，10 秒列表轮询每轮都会变；
 *   在线态由 collect 就地同步，本就不需要走重拉。
 * - `latitude` / `longitude`：由独立的实时位置接口负责，列表里常年为 null。
 * - `id` / `deviceId`：主键恒定，变化意味着换设备而非资料更新。
 *
 * 保留电量：详情页展示电量，孩子端上报粒度较粗（百分比），变化频率远低于 10 秒。
 * 分隔符用控制字符而非 `|`，避免孩子把昵称写成 `a|b` 这种内容时产生指纹碰撞。
 */
internal fun Device.detailFingerprint(): String = listOf(
    name, model, osVersion, appVersion,
    childName, childNickname, childAvatar,
    groupId, groupName, sceneType,
    remoteLocked, guardPasswordSet, sceneMode, controlMode, protectionLevel,
    daemonAlive, a11yAlive, daemonLost, daemonOnlineState, lastRestartReason, permissionVersion,
    batteryLevel
).joinToString("\u0001")
