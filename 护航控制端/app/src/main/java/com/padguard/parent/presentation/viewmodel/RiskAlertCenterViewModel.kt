package com.padguard.parent.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.parent.domain.model.Alert
import com.padguard.parent.domain.model.AlertLevel
import com.padguard.parent.domain.model.AlertStatus
import com.padguard.parent.domain.repository.AlertRepository
import com.padguard.parent.notification.NotificationHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val processing: Boolean = false
)

@HiltViewModel
class RiskAlertCenterViewModel @Inject constructor(
    private val alertRepository: AlertRepository,
    private val notificationHelper: NotificationHelper
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

    init {
        startPolling()
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
        viewModelScope.launch {
            // status 传 null = 全量（ACTIVE / ACKNOWLEDGED / CLOSED 都要）：
            // 消息中心要能翻看历史，不能只给未处理的。
            val alerts = alertRepository.getAlerts(deviceId = null, status = null)
                .getOrNull()
                ?.sortedByDescending { it.triggeredAt }
                ?: return@launch

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
                unreadCount = alerts.count { it.needsAttention }
            )
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
