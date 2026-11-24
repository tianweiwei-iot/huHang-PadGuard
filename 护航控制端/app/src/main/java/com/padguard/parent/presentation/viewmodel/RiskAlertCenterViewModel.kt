package com.padguard.parent.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.parent.domain.model.Alert
import com.padguard.parent.domain.model.AlertLevel
import com.padguard.parent.domain.model.AlertStatus
import com.padguard.parent.domain.repository.AlertRepository
import com.padguard.parent.data.remote.ParentPushClient
import com.padguard.parent.data.remote.ParentPushMessage
import com.padguard.parent.notification.NotificationHelper
import com.padguard.parent.presentation.util.RefreshGate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 风险告警消费面。
 *
 * ## 这个 ViewModel 补的是什么缺口
 * 孩子端的 TamperDetector 一直在上报高危事件（权限被撤销 / 已 Root / 时间被篡改 /
 * 无障碍被关闭 / 尝试卸载），服务端也建了告警、开了接口 —— 但家长端早期**零消费**：
 * 没有列表、没有红点、没有通知，"检测到"却说给谁听。本类把最后一环接上：
 * 轮询告警列表 → 消息中心展示 → 铃铛红点（ACTIVE 且非 INFO）→ 后台新 CRITICAL 本地通知。
 *
 * ## 为什么复用轮询而不是接 WS
 * 服务端有 `alert.new` 的 WebSocket 推送，但家长端没有长连接基础设施（MQTT 只覆盖
 * 孩子端下行指令），为一类低频告警自建长连接是另一个量级的工程。15 秒轮询的代价
 * 远小于那条链路的复杂度与故障面，且与解锁工单（[UnlockRequestCenterViewModel]）
 * 完全同构 —— 两套轮询并存，语义各自独立。
 *
 * ## 状态口径
 * - 红点：`Alert.needsAttention`（ACTIVE 且级别高于 INFO）—— INFO 是例行流水，
 *   不该让红点常驻；家长点"确认收到"后红点即消。
 * - 通知：仅 CRITICAL 且 App 在后台（前台家长正看着消息中心，双响是噪音）。
 */
data class RiskAlertUiState(
    /** 全量告警（含已确认/已关闭），按触发时间倒序 —— 消息中心要能翻历史 */
    val alerts: List<Alert> = emptyList(),
    /** 未确认且非 INFO 的告警数，驱动铃铛红点 */
    val unreadCount: Int = 0,
    /** 最近一次操作的反馈文案（如"已确认"） */
    val actionMessage: String? = null,
    /** 确认中：按钮置灰，避免连点重复请求 */
    val processing: Boolean = false,
    /**
     * 最近一次拉取告警失败的原因；null = 最近一次成功。
     *
     * 此前失败路径是 `?: return@launch` —— 状态一点都不动，页面把"接口挂了"
     * 原样渲染成"暂无告警"。家长看到"没有风险"就放心了，而真相可能是根本没拉到。
     * 消息中心这种"没有消息 = 好消息"的页面，静默失败是最伤人的一种。
     */
    val error: String? = null,
    /** 最近一次成功拉到告警的时刻（墙钟毫秒），用于说明"这份列表是哪一刻的" */
    val lastSuccessAt: Long? = null
)

@HiltViewModel
class RiskAlertCenterViewModel @Inject constructor(
    private val alertRepository: AlertRepository,
    private val notificationHelper: NotificationHelper,
    private val parentPushClient: ParentPushClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(RiskAlertUiState())
    val uiState: StateFlow<RiskAlertUiState> = _uiState.asStateFlow()

    /**
     * 已发过本地通知的告警 ID。
     * 轮询每轮都会拿到同一条未确认告警，不去重就会 15 秒一条通知刷屏。
     * 与"已确认"是两回事：确认会取消通知并移出红点，这里只防重复发送。
     */
    private val notifiedAlertIds = mutableSetOf<String>()

    private var pollJob: Job? = null

    /**
     * 刷新闸门（单飞 + 补一次，见 [RefreshGate]）。
     *
     * 除了"请求堆积 + 旧响应覆盖新数据"之外，这里还有一个更具体的风险：
     * [notifiedAlertIds] 是普通 `mutableSetOf`。两个并发的刷新协程同时做
     * "查 in → 通知 → add"，会**各发一条通知**（集合还没写入第二次的判断），
     * 家长同一秒收到两条一模一样的"设备异常"提醒。单飞后同一时刻只有一个刷新在跑，
     * 这个竞态自然消失，不必为此上锁。
     */
    private val refreshGate = RefreshGate()

    init {
        startPolling()
        observeAlertPush()
    }

    /** 账号切换时清空 A 的告警记录/未读/去重集合，防止残留到 B 的会话（详见 Unlock VM 同名方法）。 */
    fun resetForSessionChange() {
        notifiedAlertIds.clear()
        refreshGate.reset()
        _uiState.value = RiskAlertUiState()
    }

    /**
     * 告警推送快路径：服务端建警（`alert.new`）或自愈批量关单（`alert.resolved`）时即刻全量重拉。
     *
     * 此前本页只有 15s 轮询，告警产生与"修好了"两个方向都要等下一拍——孩子端权限自愈后
     * 服务端已关单并推 `alert.resolved`，家长端铃铛却最长红 15 秒，正是"明明开了还显示未开"的
     * 最后一公里。推送丢失时 15s 轮询仍是兜底（[startPolling]），二者职责清晰。
     * 收敛靠 [refreshGate] 单飞：告警风暴下多条推送叠加也只会串行刷新，不会并发堆积或旧响应覆盖新数据。
     */
    private fun observeAlertPush() {
        viewModelScope.launch {
            parentPushClient.messages.collect { msg ->
                if (msg is ParentPushMessage.AlertsChanged) refresh()
            }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                refresh()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * App 是否处于前台。由导航根的生命周期观测驱动（ON_START / ON_STOP），
     * 与解锁工单同一套门控 —— 前台时消息中心自己会展示，通知再响是噪音。
     */
    private var appInForeground = true

    fun setAppForeground(foreground: Boolean) {
        appInForeground = foreground
    }

    fun refresh() {
        // 单飞：见 [refreshGate]。15s 的轮询节拍挡不住 30s 的网络超时。
        if (!refreshGate.tryEnter()) return
        viewModelScope.launch {
            try {
                // status 传 null = 全量（ACTIVE / ACKNOWLEDGED / CLOSED 都要）：
                // 消息中心要能翻看历史，不能只给未处理的。
                val result = alertRepository.getAlerts(deviceId = null, status = null)
                val fetched = result.getOrNull()
                if (fetched == null) {
                    // 失败必须留痕：静默返回会让"接口挂了"看起来像"孩子一切正常"。
                    // 已有的 alerts 不清空（旧列表仍可翻看），只挂出"这份数据没更新"的信号。
                    _uiState.value = _uiState.value.copy(
                        error = result.exceptionOrNull()?.message ?: "告警拉取失败，请稍后重试"
                    )
                    return@launch
                }
                val alerts = fetched.sortedByDescending { it.triggeredAt }

                // 后台 + 新出现的 CRITICAL 告警 → 本地通知触达家长。
                // 只挑 CRITICAL：WARNING/INFO 是"留意"级，进消息中心即可，
                // 每条都推送会把"真的出事"淹在噪音里。
                if (!appInForeground) {
                    alerts
                        .filter { it.level == AlertLevel.CRITICAL && it.status == AlertStatus.ACTIVE && it.id !in notifiedAlertIds }
                        .forEach { alert ->
                            notificationHelper.notifyRiskAlert(alert.id, alert.title, alert.message)
                            notifiedAlertIds.add(alert.id)
                        }
                }

                _uiState.value = _uiState.value.copy(
                    alerts = alerts,
                    unreadCount = alerts.count { it.needsAttention },
                    error = null,
                    lastSuccessAt = System.currentTimeMillis()
                )
            } finally {
                // 期间被拒的刷新（下拉刷新 / 确认告警后的刷新）不能丢，放行一次
                if (refreshGate.exit()) refresh()
            }
        }
    }

    /**
     * 家长确认收到：ACTIVE → ACKNOWLEDGED。
     *
     * 不提供"关闭/解决"入口：端上无法验证"问题真的解决了"（孩子可能还在继续绕），
     * 写一个关不掉真相的"已解决"按钮只会制造虚假的安全感。确认的语义是
     * "我知道了，别再红点提醒" —— 记录仍在列表里可查。
     */
    fun acknowledge(alert: Alert) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(processing = true)
            val outcome = try {
                alertRepository.acknowledgeAlert(alert.id)
            } catch (t: Throwable) {
                // 退出页面/切账号的取消不是操作失败：放行后不写错误态、不误取消通知。
                if (t is CancellationException) throw t
                Result.failure(t)
            }
            // 无论成败都取消通知：失败时下轮轮询会因 notifiedAlertIds 已记录而不再补发，
            // 若留着通知，家长点开来发现确认没生效，比没有通知更糟。
            notificationHelper.cancelRiskAlert(alert.id)
            _uiState.value = _uiState.value.copy(
                actionMessage = outcome.fold(
                    onSuccess = { "已确认，红点已消除" },
                    onFailure = { e -> "确认失败：${e.message ?: "请检查网络后重试"}" }
                ),
                processing = false
            )
            refresh()
        }
    }

    fun clearActionMessage() {
        _uiState.value = _uiState.value.copy(actionMessage = null)
    }

    private companion object {
        /** 告警是低频事件，比解锁工单（10s）放宽一档，省一点电与流量 */
        const val POLL_INTERVAL_MS = 15_000L
    }
}
