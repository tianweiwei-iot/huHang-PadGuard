package com.padguard.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.domain.repository.DeviceRepository
import com.padguard.domain.repository.PolicyRepository
import com.padguard.domain.repository.UnlockTicket
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
 * 解锁申请消息中心（**全局**，与页面无关）。
 *
 * ## 为什么必须是全局的而不是放在首页
 * 原来的实现把「解锁申请」卡片塞进首页的 LazyColumn，只有家长恰好停在首页才看得见。
 * 而真实场景是：家长正在看统计、正在调策略、正在看实时画面——
 * 孩子那边在锁屏页干等，家长这边完全无感。锁屏申请是有时效的交互，晚几分钟就失去意义。
 * 因此这里把它提升为**根导航层的全局状态**：无论当前处于哪个界面都能弹出，
 * 处理后的记录统一沉淀到右上角「小铃铛」里。
 *
 * ## 为什么用轮询而不是推送
 * 服务端目前没有面向家长端的长连接（MQTT 只覆盖孩子端下行指令）。
 * 在家长端自建推送通道是另一个量级的工程，而解锁申请的出现频率极低（一天几次），
 * 10 秒轮询的代价远小于引入推送链路的复杂度与故障面。
 * 轮询在会话内常驻、页面销毁即停，不会在后台偷跑。
 *
 * ## 状态机
 * - `PENDING`：需要弹窗打扰家长；
 * - `IGNORED`：家长点了「忽略」，归档进消息中心，不再弹窗，但**仍可批准**；
 * - `APPROVED` / `REJECTED`：终态，只在消息中心回看。
 *
 * 「忽略」不是「拒绝」——这是本模块最重要的一个区分，理由见 [dismiss]。
 */
data class UnlockTicketEntry(
    val ticket: UnlockTicket,
    val deviceName: String
) {
    /** 弹窗/列表是否还提供处理入口：终态工单只允许回看 */
    val actionable: Boolean get() = ticket.isActionable
}

data class UnlockCenterUiState(
    /** 当前应当弹出的申请（已按"最早提交"排序，且未被本次会话处理过） */
    val popup: UnlockTicketEntry? = null,
    /** 消息中心全量记录（含已忽略 / 已同意 / 已拒绝） */
    val records: List<UnlockTicketEntry> = emptyList(),
    /** 未读（从未打扰过家长的待处理申请）数量，驱动铃铛红点 */
    val unreadCount: Int = 0,
    /** 最近一次操作的反馈文案（如"已忽略，可在消息中心继续处理"） */
    val actionMessage: String? = null,
    /** 处理中：按钮置灰，避免家长连点导致重复下发指令 */
    val processing: Boolean = false
)

@HiltViewModel
class UnlockRequestCenterViewModel @Inject constructor(
    private val deviceRepository: DeviceRepository,
    private val policyRepository: PolicyRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(UnlockCenterUiState())
    val uiState: StateFlow<UnlockCenterUiState> = _uiState.asStateFlow()

    /**
     * 本次会话已处理过的工单 ID。
     *
     * 服务端把「忽略」落到 IGNORED 状态后，下一次轮询自然就不会再弹；
     * 但在那一次轮询回来之前（最长 10 秒），本地若不做记忆，弹窗会立刻复活一次——
     * 家长看到的就是"我明明点了忽略，它又弹出来了"。
     */
    private val handledInSession = mutableSetOf<String>()

    private var pollJob: Job? = null

    init {
        startPolling()
    }

    /**
     * 启动轮询。
     *
     * 用 `while (isActive)` 而不是 `delay` 递归：前者在 viewModelScope 取消时立刻退出，
     * 后者可能在作用域已取消后仍多跑一轮，产生"页面关了还在请求"的脏日志。
     */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                refresh()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val devices = deviceRepository.getDeviceList().getOrNull() ?: return@launch
            val nameById = devices.associate { it.id to it.name }

            val entries = devices.mapNotNull { device ->
                val tickets = policyRepository.getUnlockTickets(device.id).getOrNull() ?: return@mapNotNull null
                tickets.map { UnlockTicketEntry(it, nameById[device.id].orEmpty()) }
            }.flatten().sortedByDescending { it.ticket.createdAt }

            // 弹窗只挑"待处理且本次会话没处理过"里最早的一条：
            // 孩子可能连着提好几次，一次只弹一条，处理完再弹下一条，避免堆叠成一摞对话框。
            val next = entries
                .filter { it.ticket.isPending && it.ticket.id !in handledInSession }
                .minByOrNull { it.ticket.createdAt }

            _uiState.value = _uiState.value.copy(
                records = entries,
                unreadCount = entries.count { it.ticket.isPending },
                popup = next
            )
        }
    }

    /**
     * 忽略：关闭弹窗并归档。
     *
     * 不给孩子下发任何消息——孩子此刻正盯着锁屏页，一条"申请未通过"是无谓的打击；
     * 而且家长稍后改变主意时，工单如果已被置成终态就再也批不了。
     */
    fun dismiss(ticket: UnlockTicket) {
        act(ticket) { policyRepository.dismissUnlockTicket(ticket.deviceId, ticket.id) }
            .invokeOnCompletion {
                _uiState.value = _uiState.value.copy(
                    actionMessage = "已忽略，可在消息中心继续处理"
                )
            }
    }

    /** 同意：按申请时长（家长未指定则用孩子申请值，再兜底 30 分钟）下发限时解锁 */
    fun approve(ticket: UnlockTicket, durationMinutes: Int? = null) {
        val minutes = durationMinutes ?: ticket.durationMinutes ?: DEFAULT_UNLOCK_MINUTES
        act(ticket) { policyRepository.approveUnlockTicket(ticket.deviceId, ticket.id, minutes) }
            .invokeOnCompletion {
                _uiState.value = _uiState.value.copy(actionMessage = "已同意放行 $minutes 分钟")
            }
    }

    /** 拒绝：关闭工单并给孩子下发"申请未通过"（与「忽略」的区别见 [dismiss]） */
    fun reject(ticket: UnlockTicket) {
        act(ticket) { policyRepository.ignoreUnlockTicket(ticket.deviceId, ticket.id) }
            .invokeOnCompletion {
                _uiState.value = _uiState.value.copy(actionMessage = "已拒绝该申请")
            }
    }

    fun clearActionMessage() {
        _uiState.value = _uiState.value.copy(actionMessage = null)
    }

    /**
     * 统一的处理入口：置处理中 → 调仓库 → 无论成败都立刻收起弹窗 → 刷新一次。
     *
     * 失败也要收起弹窗：一次网络抖动不该让家长被同一个对话框反复卡住，
     * 记录仍在消息中心里，刷新后还能再处理。
     */
    private fun act(
        ticket: UnlockTicket,
        block: suspend () -> Result<Unit>
    ): Job = viewModelScope.launch {
        _uiState.value = _uiState.value.copy(processing = true)

        // 这里**不能**写成 runCatching { block() }，有两个叠加的坑：
        //
        // 1. runCatching 的 lambda 不是 suspend 的。编译器虽然放行（inline lambda 在
        //    suspend 上下文里允许挂起调用），但生成的调用不会真正驱动协程续体，
        //    于是 block() 一次都没被执行 —— 表现就是"家长点了同意放行，
        //    抓包看一个 POST 请求都没发出去，工单永远停在 PENDING，孩子端毫无反应"。
        //
        // 2. block() 自己就返回 Result，再包一层会变成 Result<Result<Unit>>：
        //    仓库层用 Result.failure 表达的网络失败会被外层当成成功，onFailure 不触发，
        //    错误被静默吞掉，家长看不到任何失败提示。
        //
        // 因此这里用普通的 try/catch 直接调 suspend 函数，并统一收敛成败。
        val outcome = try {
            block()
        } catch (t: Throwable) {
            Result.failure(t)
        }
        outcome.onFailure { e ->
            _uiState.value = _uiState.value.copy(
                actionMessage = "操作失败：${e.message ?: "请检查网络后重试"}"
            )
        }
        // 无论成败都记一笔：避免失败后弹窗立刻复活，家长陷入"忽略-又弹-又忽略"的循环
        handledInSession.add(ticket.id)
        _uiState.value = _uiState.value.copy(popup = null, processing = false)
        refresh()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 10_000L
        const val DEFAULT_UNLOCK_MINUTES = 30
    }
}
