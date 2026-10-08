package com.padguard.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.domain.model.Device
import com.padguard.domain.model.DeviceOnlineStatus
import com.padguard.domain.model.SceneType
import com.padguard.domain.model.UsageStats
import com.padguard.domain.repository.AgeBand
import com.padguard.domain.repository.AgeBandDefault
import com.padguard.domain.repository.DeviceRepository
import com.padguard.domain.repository.MinorMode
import com.padguard.domain.repository.PolicyRepository
import com.padguard.domain.repository.StatisticsRepository
import com.padguard.domain.repository.UnlockTicket
import com.padguard.parent.data.remote.UsagePushClient
import com.padguard.parent.data.remote.UsagePushMessage
import com.padguard.presentation.util.TimeFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    /**
     * 家长端「时间管控」页设置的工作日额度（分钟），即家长配置的原值。
     * 实际生效值见 [effectiveDailyLimitMinutes]（权威来源为家长设置，未成年人模式仅作合规兜底）。
     */
    val dailyLimitMinutes: Int = 60,
    /**
     * 孩子端实际生效的每日时长上限（分钟）。
     *
     * 必须与孩子端锁机判定口径一致：以家长在「时间管控」页设置的时间额度为权威来源，
     * 未成年人模式开启时不再把家长已设额度夹逼到分龄上限，仅当家长完全未设置时回落分龄合规基线。
     * 由 [computeEffectiveDailyLimitMinutes] 统一计算，确保首页看板与孩子端锁机值一致。
     */
    val effectiveDailyLimitMinutes: Int = 60,
    /** 当前未成年人模式配置（用于判断 dailyLimitMinutes 是否被分龄上限覆盖） */
    val minorMode: MinorMode? = null,
    /** 各龄档合规默认值 */
    val ageBandDefaults: Map<AgeBand, AgeBandDefault> = emptyMap(),
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
    private val policyRepository: PolicyRepository,
    private val usagePushClient: UsagePushClient
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
        // 看板用量实时刷新：
        // - 主通道 = WS 推送（孩子端上行用量即推今日快照，毫秒级、零轮询资源），见 observeUsagePush；
        // - 兜底 = 降频轮询（30s），WS 临时断开时仍保证看板不陈旧，用完即弃不长期占资源。
        // 同时拉起家长端 WS 客户端（按登录态/服务器地址自动连接，幂等）。
        usagePushClient.start()
        observeUsagePush()
        startUsageRefreshLoop()
    }

    /**
     * 下拉刷新：复用统一加载路径，仅切换刷新态，避免与 loadHomeData 重复逻辑。
     *
     * 必须**额外**刷新用量与限额：[loadHomeData] 只会拉设备列表，而用量/限额是在
     * [applyDeviceList] 里"选中设备发生变化"时才触发的。若下拉时设备没变，
     * 就只刷了个设备列表、看板数字纹丝不动 —— 与"下拉刷新"的语义不符。
     * （限额已从 10 秒轮询中移除，这里就是它回到最新值的正常通道。）
     */
    fun refreshData() {
        loadHomeData(showRefreshing = true)
        _uiState.value.selectedDeviceId?.let {
            loadTodayUsage(it)
            loadDailyLimit(it)
            loadUnlockTickets(it)
        }
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

    /**
     * 消费 WS 实时推送的今日用量快照，毫秒级刷新"今日平板使用情况"。
     *
     * 推送报文已含 deviceId + 领域模型（图标补成绝对地址），无需再发 HTTP。
     * 只更新"当前选中设备"的看板；非选中设备的推送忽略，避免串台。
     * 推送与轮询兜底写的是同一份 [_uiState.todayUsage]，幂等、互不冲突。
     */
    private fun observeUsagePush() {
        viewModelScope.launch {
            usagePushClient.messages.collect { msg ->
                if (msg is UsagePushMessage.UsageToday) {
                    val sel = _uiState.value.selectedDeviceId ?: return@collect
                    if (msg.deviceId != sel) return@collect
                    val topApp = msg.stats.appUsages.firstOrNull()
                    _uiState.value = _uiState.value.copy(
                        todayUsage = msg.stats,
                        latestActivity = topApp?.let {
                            "使用了 ${it.appName} ${TimeFormat.formatDurationFromMinutes(it.usageMinutes)}"
                        }
                    )
                }
            }
        }
    }

    /**
     * 看板用量 / 额度的近实时刷新循环。
     *
     * ## 用户反馈「看板已使用时间与孩子端实际不一致」的根因与解法
     * 孩子端本地统计是逐秒累计的实时值，家长端看板展示的是**服务端聚合值**，
     * 而服务端聚合值只会在孩子端上行 usage 日志后更新。原先今日用量仅在进入首页 / 切换设备时
     * 拉取**一次**，之后孩子多玩一小时，看板仍停在旧值，差异越积越大。
     * 这里每 [USAGE_REFRESH_INTERVAL_MS] 重新拉取一次今日用量与生效额度，
     * 与孩子端上行节奏（10s 一次）对齐，两端数值基本同步。
     * viewModelScope 在 ViewModel 清除时自动取消，循环不会泄漏。
     */
    private fun startUsageRefreshLoop() {
        viewModelScope.launch {
            while (isActive) {
                delay(USAGE_REFRESH_INTERVAL_MS)
                val sel = _uiState.value.selectedDeviceId ?: continue
                // 只刷新**用量**这类近实时数据。
                //
                // 每日限额是**配置类数据**（只有家长主动改才会变），原先也放在这个 10 秒循环里，
                // 于是每一轮都会多打 3 个请求（平板使用时间 / 未成年人模式 / 分龄默认值），
                // 而其中绝大多数返回的都是与上一轮完全相同的值 —— 这正是首页
                // "同一秒内并发 6~9 个请求"的来源。改为随设备加载 / 下拉刷新 / 修改后刷新。
                loadTodayUsage(sel)
            }
        }
    }

    private fun loadDailyLimit(deviceId: String) {
        viewModelScope.launch {
            // 并行拉取三类数据，算出孩子端实际生效的每日时长上限，避免家长端展示的限额与孩子端锁机判定不一致：
            // - 「时间管控」页设置（平板使用时间，权威来源：weekday/weekend 额度）
            // - 未成年人模式配置（开启时仅作合规兜底，不再夹逼家长已设额度）
            // - 分龄合规默认值
            val tablet = policyRepository.getTabletUsageSettings(deviceId).getOrNull()
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
        }
    }

    /** 今天是否周末（与服务端默认 Asia/Shanghai 时区、孩子端时段判定一致） */
    private fun isWeekendToday(): Boolean {
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val dow = java.time.LocalDate.now(zone).dayOfWeek
        return dow == java.time.DayOfWeek.SATURDAY || dow == java.time.DayOfWeek.SUNDAY
    }

    companion object {
        /**
         * 看板刷新兜底轮询节奏（仅 WS 不可用时生效）。
         * 主通道已改为 WS 实时推送（孩子端上行即推今日快照），故轮询从 10s 降为 30s 仅作兜底，
         * 既保证 WS 临时断开时看板不陈旧，又把常驻网络开销压到最低。
         */
        private const val USAGE_REFRESH_INTERVAL_MS = 30_000L
    }
}

/**
 * 计算孩子端实际生效的每日时长上限（分钟），必须与
 * [com.padguard.core.engine.enforcer.MinorModeEnforcer] / [com.padguard.core.data.model.policy.AppLimitPolicy.quotaFor]
 * 口径完全一致，否则家长端展示与孩子端锁机会对不上。
 *
 * 口径（与产品要求一致）：
 * 1. 时间管控总开关关闭 **且** 未成年人模式未开启 → 0（不限制）；
 * 2. 优先取家长在「时间管控」页设置的 weekday/weekend 额度（权威，家长可自由设置，含高于分龄上限）；
 * 3. 家长未设置（0）时，未成年人模式开启则回落分龄合规基线（合规兜底），否则沿用服务端默认额度。
 *
 * @param enabled             时间管控总开关（appLimit.enabled / settings.timeControlEnabled）
 * @param weekdayLimitMinutes  家长在「时间管控」页设置的工作日额度（已含服务端默认）
 * @param weekendLimitMinutes 家长在「时间管控」页设置的周末额度（已含服务端默认）
 * @param minorMode           未成年人模式配置；关闭或其字段为 0 时不参与夹逼
 * @param ageBandDefaults     分龄合规默认值
 * @param isWeekend           今天是否周末
 */
fun computeEffectiveDailyLimitMinutes(
    enabled: Boolean,
    weekdayLimitMinutes: Int,
    weekendLimitMinutes: Int,
    minorMode: MinorMode?,
    ageBandDefaults: Map<AgeBand, AgeBandDefault>,
    isWeekend: Boolean
): Int {
    // 时间管控关闭且未成年人模式未开启：完全不限制
    if (!enabled && minorMode?.enabled != true) return 0
    // 优先取家长在「时间管控」页设置的额度（权威）；周末回落到工作日设置
    val parentValue = if (isWeekend) {
        weekendLimitMinutes.takeIf { it > 0 } ?: weekdayLimitMinutes.takeIf { it > 0 }
    } else {
        weekdayLimitMinutes.takeIf { it > 0 }
    }
    if (parentValue != null) return parentValue
    // 家长未设置任何额度：未成年人模式开启则回落分龄合规基线，否则沿用服务端默认额度
    return if (minorMode?.enabled == true) {
        ageBandDefaults[minorMode.ageBand]?.dailyLimitMinutes ?: 60
    } else {
        weekdayLimitMinutes
    }
}
