package com.padguard.parent.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.parent.data.remote.ParentPushClient
import com.padguard.parent.data.remote.ParentPushMessage
import com.padguard.parent.domain.repository.DeviceRepository
import com.padguard.parent.domain.repository.PolicyRepository
import com.padguard.parent.domain.repository.UsageDelayTicket
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
 * 延时申请消息中心（**全局**，与页面无关）。
 *
 * ## 与 [UnlockRequestCenterViewModel] 同层、同职责
 * 孩子在计时倒计时页点「延时申请」→ 服务端建 PENDING 工单（**不直接延长时长**）→
 * 本中心轮询拉取 → 弹出审批入口 / 沉淀到消息中心。这是本次修复的关键一环：
 * 旧实现里孩子端「申请延时」直接生效，既没有家长审批，家长端也同步不到延时后的时长。
 *
 * ## 为什么用轮询 + 推送双通道
 * 轮询（10 秒）是兜底，保证推送丢失时仍能看到申请；推送（[ParentPushMessage.UsageDelayRequested]
 * / [ParentPushMessage.UsageDelayUpdated]）是快路径，让孩子刚提交、家长刚处理时即时刷新，
 * 不必等最长 10 秒——审批是有时效的交互。
 *
 * ## 状态机（与解锁工单一致）
 * - `PENDING`：需要弹窗打扰家长；
 * - `IGNORED`：家长点了「忽略」，归档进消息中心，不再弹窗，但**仍可批准**；
 * - `APPROVED` / `REJECTED`：终态，只在消息中心回看。
 *
 * 「忽略」不是「拒绝」——这是本模块最重要的一个区分，理由见 [dismiss]。
 */
data class UsageDelayTicketEntry(
    val ticket: UsageDelayTicket,
    val deviceName: String
) {
    /** 弹窗/列表是否还提供处理入口：终态工单只允许回看 */
    val actionable: Boolean get() = ticket.isActionable
}

data class UsageDelayCenterUiState(
    /** 当前应当弹出的申请（已按"最早提交"排序，且未被本次会话处理过） */
    val popup: UsageDelayTicketEntry? = null,
    /** 消息中心全量记录（含已忽略 / 已同意 / 已拒绝） */
    val records: List<UsageDelayTicketEntry> = emptyList(),
    /** 未读（待家长处理）数量，驱动铃铛红点 */
    val unreadCount: Int = 0,
    /** 最近一次操作的反馈文案 */
    val actionMessage: String? = null,
    /** 处理中：按钮置灰，避免家长连点导致重复下发指令 */
    val processing: Boolean = false
)

@HiltViewModel
class UsageDelayCenterViewModel @Inject constructor(
    private val deviceRepository: DeviceRepository,
    private val policyRepository: PolicyRepository,
    private val notificationHelper: NotificationHelper,
    private val parentPushClient: ParentPushClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(UsageDelayCenterUiState())
    val uiState: StateFlow<UsageDelayCenterUiState> = _uiState.asStateFlow()

    /**
     * 本次会话已处理过的工单 ID。
     * 服务端把「忽略」落到 IGNORED 后下一次轮询才回来；在那之前（最长 10 秒）本地若不做记忆，
     * 弹窗会立刻复活一次——家长看到的就是"我明明点了忽略，它又弹出来了"。
     */
    private val handledInSession = mutableSetOf<String>()

    /** 已发过本地通知的工单 ID，避免 10 秒轮询反复堆叠同一条通知 */
    private val notifiedTicketIds = mutableSetOf<String>()

    private var pollJob: Job? = null

    init {
        startPolling()
        observePush()
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
     * 监听服务端推送：孩子刚提交 / 家长刚处理，即时刷新一次。
     * 推送快路径 + 轮询兜底，二者职责清晰；推送丢失时轮询仍能补齐。
     */
    private fun observePush() {
        viewModelScope.launch {
            parentPushClient.messages.collect { msg ->
                if (msg is ParentPushMessage.UsageDelayRequested ||
                    msg is ParentPushMessage.UsageDelayUpdated
                ) {
                    refresh()
                }
            }
        }
    }

    private var appInForeground = true

    /** 由导航根的生命周期观测回调驱动（ON_START=true / ON_STOP=false） */
    fun setAppForeground(foreground: Boolean) {
        appInForeground = foreground
    }

    fun refresh() {
        viewModelScope.launch {
            val devices = deviceRepository.getDeviceList().getOrNull() ?: return@launch
            val nameById = devices.associate { it.id to it.name }

            val entries = devices.mapNotNull { device ->
                val tickets = policyRepository.getUsageDelayTickets(device.id).getOrNull() ?: return@mapNotNull null
                tickets.map { UsageDelayTicketEntry(it, nameById[device.id].orEmpty()) }
            }.flatten().sortedByDescending { it.ticket.createdAt }

            // 弹窗只挑"待处理且本次会话没处理过"里最早的一条
            val next = entries
                .filter { it.ticket.isPending && it.ticket.id !in handledInSession }
                .minByOrNull { it.ticket.createdAt }

            if (!appInForeground) {
                entries
                    .filter { it.ticket.isPending && it.ticket.id !in notifiedTicketIds }
                    .forEach { entry ->
                        notificationHelper.notifyUsageDelayRequest(entry.deviceName, entry.ticket.id)
                        notifiedTicketIds.add(entry.ticket.id)
                    }
            }

            _uiState.value = _uiState.value.copy(
                records = entries,
                unreadCount = entries.count { it.ticket.isPending },
                popup = next
            )
        }
    }

    /** 忽略：关闭弹窗并归档，不下发任何消息给孩子、工单仍可被批准 */
    fun dismiss(ticket: UsageDelayTicket) {
        act(ticket, successMessage = "已忽略，可在消息中心继续处理") {
            policyRepository.dismissUsageDelayTicket(ticket.deviceId, ticket.id)
        }
    }

    /** 同意：服务端按工单申请分钟数累加额度，孩子端下一次会话刷新即生效 */
    fun approve(ticket: UsageDelayTicket) {
        act(ticket, successMessage = "已同意延长 ${ticket.minutes} 分钟") {
            policyRepository.approveUsageDelayTicket(ticket.deviceId, ticket.id)
        }
    }

    /** 拒绝：关闭工单并给孩子下发「申请未通过」 */
    fun reject(ticket: UsageDelayTicket, reason: String? = null) {
        act(ticket, successMessage = "已拒绝该申请") {
            policyRepository.rejectUsageDelayTicket(ticket.deviceId, ticket.id, reason)
        }
    }

    fun clearActionMessage() {
        _uiState.value = _uiState.value.copy(actionMessage = null)
    }

    /**
     * 统一的处理入口：置处理中 → 调仓库 → 无论成败都立刻收起弹窗 → 刷新一次。
     * 失败也要收起弹窗：一次网络抖动不该让家长被同一个对话框反复卡住。
     * 成败文案在此一次性据实落定，避免早期"界面说成功、后端什么都没发生"的假成功形态。
     */
    private fun act(
        ticket: UsageDelayTicket,
        successMessage: String,
        block: suspend () -> Result<Unit>
    ): Job = viewModelScope.launch {
        _uiState.value = _uiState.value.copy(processing = true)

        // 不能用 runCatching { block() }：block 本身是 suspend，且返回 Result，
        // 包两层会变成 Result<Result<Unit>>，仓库层用 Result.failure 表达的网络失败会被外层当成功。
        val outcome = try {
            block()
        } catch (t: Throwable) {
            Result.failure(t)
        }
        val message = outcome.fold(
            onSuccess = { successMessage },
            onFailure = { e -> "操作失败：${e.message ?: "请检查网络后重试"}" }
        )
        handledInSession.add(ticket.id)
        // 处理（含忽略）即取消对应本地通知，避免通知栏长期挂着与事实不符的内容
        notificationHelper.cancelUsageDelayRequest(ticket.id)
        _uiState.value = _uiState.value.copy(
            actionMessage = message,
            popup = null,
            processing = false
        )
        refresh()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 10_000L
    }
}
