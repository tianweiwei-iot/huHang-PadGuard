package com.padguard.parent.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.parent.data.model.OrderDto
import com.padguard.parent.data.model.PayChannelDto
import com.padguard.parent.data.model.PlanDto
import com.padguard.parent.data.model.SubscriptionDto
import com.padguard.parent.domain.repository.BillingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SubscriptionUiState(
    val loading: Boolean = true,
    val plans: List<PlanDto> = emptyList(),
    val subscription: SubscriptionDto? = null,
    val orders: List<OrderDto> = emptyList(),
    /** 正在下单的套餐 key */
    val orderingPlan: String? = null,
    /** 最近一次下单结果：展示订单号给家长，便于联系客服核对 */
    val lastOrderNo: String? = null,
    /** 结算页状态：非空时弹出收银台 */
    val checkout: CheckoutState? = null,
    val message: String? = null,
    val error: String? = null
)

/**
 * 收银台结算状态。
 *
 * 把"下单 → 选渠道 → 支付 → 开通"收敛成一段状态机，[SubscriptionScreen] 只负责渲染，
 * 不持有任何业务判断。这样即便将来把示例支付换成真实收银台 SDK，
 * 也只改 [confirmPay] 这一个方法，UI 一句不用动。
 */
data class CheckoutState(
    /** 要购买的套餐（金额、时长来源） */
    val plan: PlanDto,
    /** 可选支付渠道；enabled=false 的渠道在 UI 上置灰 */
    val channels: List<PayChannelDto>,
    /** 当前选中的渠道 key；为空时"确认支付"按钮禁用 */
    val selectedChannel: String? = null,
    /** 支付中（下单 + 模拟支付的往返） */
    val paying: Boolean = false,
    /** 结算页级别的失败提示（与整页 error 区分，只盖住收银台） */
    val error: String? = null
)

/**
 * 订阅与续费。
 *
 * ## 结算页是这次新增的核心
 * 之前是"点订阅 → 直接生成订单 → 展示订单号联系客服"，没有收银台，家长付不了款。
 * 现在：点订阅 → 弹出收银台（金额 + 多种支付渠道：支付宝 / 微信 / 云闪付 / 银行卡）
 * → 选渠道 → 确认支付 → 服务端示例支付完成后自动开通订阅并刷新。
 *
 * 渠道全部走**示例支付**（`[PayChannelDto.mock]=true`）：不产生真实扣款，只为打通全链路；
 * 接入真实商户资质后，仅由服务端把渠道置为真实可用、用支付回调替代这里的 `mockPay`，
 * 前端无需改动 —— 这是刻意保持的前后向兼容设计。
 */
@HiltViewModel
class SubscriptionViewModel @Inject constructor(
    private val billingRepository: BillingRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SubscriptionUiState())
    val uiState: StateFlow<SubscriptionUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            val sub = billingRepository.getSubscription()
            val plans = billingRepository.getPlans()
            val orders = billingRepository.getOrders(20)

            // 订阅状态失败要如实报出：它决定家长"还能不能用"，静默失败会让家长以为自己仍在有效期内
            _uiState.value = _uiState.value.copy(
                loading = false,
                subscription = sub.getOrNull(),
                plans = plans.getOrDefault(emptyList()),
                orders = orders.getOrDefault(emptyList()),
                error = sub.exceptionOrNull()?.message
            )
        }
    }

    /**
     * 从套餐卡片点击"订阅"进入收银台。
     *
     * 只拉一次渠道列表（失败则用内置兜底，避免服务端未实现时整个购买链路卡死），
     * 之后弹窗里选渠道、确认支付都不再发网络请求，体验顺滑。
     */
    fun startCheckout(plan: PlanDto) {
        // 已经有一个收银台时直接返回，防止重复叠加
        if (_uiState.value.checkout != null) return
        viewModelScope.launch {
            val channels = billingRepository.getChannels()
                .getOrDefault(fallbackChannels())
                .filter { it.enabled }
            val safeChannels = if (channels.isEmpty()) fallbackChannels() else channels
            _uiState.value = _uiState.value.copy(
                checkout = CheckoutState(plan = plan, channels = safeChannels)
            )
        }
    }

    /** 收银台内选择支付渠道 */
    fun selectChannel(key: String) {
        val c = _uiState.value.checkout ?: return
        _uiState.value = _uiState.value.copy(
            checkout = c.copy(selectedChannel = key, error = null)
        )
    }

    /** 关闭收银台（取消/支付完成/失败后的退出） */
    fun cancelCheckout() {
        _uiState.value = _uiState.value.copy(checkout = null, orderingPlan = null)
    }

    /**
     * 确认支付。
     *
     * 下单与示例支付是两步：先 `createOrder` 拿到订单号，再 `mockPay` 模拟付款并开通订阅。
     * 两步都成功才算支付完成；任何一步失败都如实回显到收银台（不甩到整页 error，
     * 否则家长看到的是一片空白、以为购买功能坏了）。
     */
    fun confirmPay() {
        val c = _uiState.value.checkout ?: return
        val channel = c.selectedChannel ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                checkout = c.copy(paying = true, error = null),
                orderingPlan = c.plan.key
            )
            // 1) 下单
            val order = billingRepository.createOrder(c.plan.key, channel)
            if (order.isFailure) {
                _uiState.value = _uiState.value.copy(
                    checkout = c.copy(paying = false, error = order.exceptionOrNull()?.message ?: "下单失败"),
                    orderingPlan = null
                )
                return@launch
            }
            // 2) 示例支付（真实渠道接入后这里换成"拉起收银台 SDK + 等回调"）
            val paid = billingRepository.mockPay(order.getOrThrow().orderNo)
            if (paid.isFailure) {
                _uiState.value = _uiState.value.copy(
                    checkout = c.copy(paying = false, error = paid.exceptionOrNull()?.message ?: "支付失败"),
                    orderingPlan = null
                )
                return@launch
            }
            // 3) 支付成功：关弹窗、刷新订阅与套餐、提示
            val sub = paid.getOrNull()
            _uiState.value = _uiState.value.copy(
                checkout = null,
                orderingPlan = null,
                subscription = sub ?: _uiState.value.subscription,
                lastOrderNo = order.getOrThrow().orderNo,
                message = "支付成功，订阅已开通/续费（示例支付，未产生真实扣款）"
            )
            refresh()
        }
    }

    fun dismissMessage() {
        _uiState.value = _uiState.value.copy(message = null, error = null)
    }

    /**
     * 服务端未返回渠道，或网络失败时的兜底。
     * 保证"买不到"不再是死路 —— 至少能走示例支付把全链路验证完。
     * 注意：这是本地写死的四渠道，仅在服务端不可达时启用；接入真实渠道后应以服务端为准。
     */
    private fun fallbackChannels(): List<PayChannelDto> = listOf(
        PayChannelDto("ALIPAY", "支付宝", enabled = true, mock = true, note = "示例支付"),
        PayChannelDto("WECHAT", "微信支付", enabled = true, mock = true, note = "示例支付"),
        PayChannelDto("UNIONPAY", "云闪付", enabled = true, mock = true, note = "示例支付"),
        PayChannelDto("BANKCARD", "银行卡", enabled = true, mock = true, note = "示例支付")
    )
}
