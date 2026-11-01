package com.padguard.parent.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.parent.BuildConfig
import com.padguard.parent.data.model.OrderDto
import com.padguard.parent.data.model.PayChannelDto
import com.padguard.parent.data.model.PlanDto
import com.padguard.parent.data.model.SubscriptionDto
import com.padguard.parent.data.remote.WeChatPayLauncher
import com.padguard.parent.domain.repository.BillingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
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
    private val billingRepository: BillingRepository,
    private val weChatPay: WeChatPayLauncher
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
            val remote = billingRepository.getChannels().getOrNull()?.filter { it.enabled }.orEmpty()

            // 正式包不伪造渠道：兜底清单里四个渠道全是 mock=true 的假象，
            // 服务端一不可达就摆出"支付宝/微信都能付"的界面，家长点了才知道付不了。
            // 调试包保留兜底，是为了联调时不被服务端进度卡住 —— 两边语义不同，必须分开。
            val channels = if (remote.isEmpty() && localFallbackAllowed) fallbackChannels() else remote

            if (channels.isEmpty()) {
                _uiState.value = _uiState.value.copy(
                    error = "当前没有可用的支付方式，请稍后再试或联系客服"
                )
                return@launch
            }
            _uiState.value = _uiState.value.copy(
                checkout = CheckoutState(plan = plan, channels = channels)
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
     * 三条路径（按服务端渠道语义分派，UI 无感知）：
     * 1. **示例支付**（`mock=true`，仅联调）：createOrder → mockPay，立即出结果；
     * 2. **真实微信支付**（`mock=false` 且 key=WECHAT）：createOrder 拿 payParams → 拉起微信
     *    → 轮询服务端订单状态直至 PAID/CLOSED；
     * 3. 其他真实渠道：服务端 fail-closed 不会返回可用渠道，选中即提示未接入。
     *
     * 任何失败都如实回显到收银台（不甩到整页 error，否则家长看到的是一片空白、
     * 以为购买功能坏了）。
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
            val created = order.getOrThrow()
            val selected = c.channels.firstOrNull { it.key == channel }

            // 2a) 示例支付：服务端明确标记 mock=true 才允许走
            //（少了这道护栏，字段一变更真实渠道就会静默走成示例支付——订阅开通了钱没收）
            if (selected?.mock == true) {
                val paid = billingRepository.mockPay(created.orderNo)
                if (paid.isFailure) {
                    _uiState.value = _uiState.value.copy(
                        checkout = c.copy(paying = false, error = paid.exceptionOrNull()?.message ?: "支付失败"),
                        orderingPlan = null
                    )
                    return@launch
                }
                _uiState.value = _uiState.value.copy(
                    checkout = null,
                    orderingPlan = null,
                    subscription = paid.getOrNull() ?: _uiState.value.subscription,
                    lastOrderNo = created.orderNo,
                    message = "支付成功，订阅已开通/续费（示例支付，未产生真实扣款）"
                )
                refresh()
                return@launch
            }

            // 2b) 真实微信支付
            if (channel == CHANNEL_WECHAT && selected != null) {
                payWithWeChat(c, created)
                return@launch
            }

            // 2c) 其他真实渠道：服务端只会下发已配置就绪的渠道（微信），
            // 走到这里说明渠道清单与实现出现漂移，如实告知而不是静默失败
            _uiState.value = _uiState.value.copy(
                checkout = c.copy(
                    paying = false,
                    error = "该渠道尚未接入支付方式，请选择其他渠道或联系客服"
                ),
                orderingPlan = null
            )
        }
    }

    /**
     * 真实微信支付：拉起微信 → 感知用户操作 → 轮询服务端权威状态。
     *
     * 为什么不能信任微信本地回调（errCode=0）：回调可能因进程被杀而丢失，也可能被伪造；
     * "钱付了订阅没到账"是家长最不能容忍的故障，所以开通与否只看服务端订单状态。
     * 用户点"取消"也照样轮询一小段——微信里"取消"前可能已输完密码，本地语义不可靠。
     */
    private suspend fun payWithWeChat(c: CheckoutState, created: com.padguard.parent.data.model.CreateOrderResponse) {
        val payParams = created.payParams
        if (payParams.isNullOrEmpty()) {
            _uiState.value = _uiState.value.copy(
                checkout = c.copy(paying = false, error = "微信下单参数缺失，请稍后重试或联系客服"),
                orderingPlan = null
            )
            return
        }
        if (!weChatPay.weChatInstalled()) {
            _uiState.value = _uiState.value.copy(
                checkout = c.copy(paying = false, error = "未检测到微信客户端，请安装微信后重试或联系客服代开通"),
                orderingPlan = null
            )
            return
        }

        val localResult = weChatPay.pay(payParams)

        // 轮询服务端：PAID 即成功；CLOSED 即订单关闭；超时则给出"确认中"的诚实提示
        val settled = pollOrderUntilSettled(created.orderNo)
        when {
            settled?.status == ORDER_STATUS_PAID -> {
                _uiState.value = _uiState.value.copy(
                    checkout = null,
                    orderingPlan = null,
                    lastOrderNo = created.orderNo,
                    message = "支付成功，订阅已开通/续费"
                )
                refresh()
            }
            settled?.status == ORDER_STATUS_CLOSED -> {
                _uiState.value = _uiState.value.copy(
                    checkout = c.copy(paying = false, error = "订单已超时关闭，如已扣款请联系客服核实退款"),
                    orderingPlan = null
                )
            }
            else -> {
                // 未在窗口内确认到结果：不撒谎说成功也不说失败——钱可能已扣，服务端回调可能还在路上
                val hint = if (localResult == WeChatPayLauncher.PayResult.CANCEL && settled == null)
                    "支付未完成。如已扣款，结果确认后会在订单列表自动更新"
                else
                    "支付结果确认中，如已扣款，开通后会在订单列表自动更新"
                _uiState.value = _uiState.value.copy(
                    checkout = c.copy(paying = false, error = hint),
                    orderingPlan = null
                )
            }
        }
    }

    /** 每隔 [POLL_INTERVAL_MS] 查一次订单状态，最长 [POLL_TOTAL_MS]；查不到（网络抖动）继续重试 */
    private suspend fun pollOrderUntilSettled(orderNo: String): OrderDto? {
        val deadline = System.currentTimeMillis() + POLL_TOTAL_MS
        while (System.currentTimeMillis() < deadline) {
            billingRepository.getOrder(orderNo).getOrNull()?.let { o ->
                if (o.status == ORDER_STATUS_PAID || o.status == ORDER_STATUS_CLOSED) return o
            }
            delay(POLL_INTERVAL_MS)
        }
        return null
    }

    fun dismissMessage() {
        _uiState.value = _uiState.value.copy(message = null, error = null)
    }

    /**
     * 是否允许使用本地兜底渠道清单 —— **仅调试包**。
     *
     * 正式包必须禁用：服务端一不可达就摆出四个"示例支付"渠道，
     * 是典型的"看起来能付、其实付不了"，比直接说"暂无支付方式"更伤信任。
     */
    private val localFallbackAllowed: Boolean get() = BuildConfig.DEBUG

    /**
     * 服务端未返回渠道时的兜底清单，**仅供调试包联调使用**。
     *
     * 保留它的理由：开发阶段服务端还没实现渠道接口时，不至于让整条购买链路卡死。
     * 但它是本地写死的四渠道，与服务端真实配置无关，因此正式包一律不走这里
     * （闸门见 [localFallbackAllowed]）；接入真实渠道后应以服务端返回为准。
     */
    private fun fallbackChannels(): List<PayChannelDto> = listOf(
        PayChannelDto("ALIPAY", "支付宝", enabled = true, mock = true, note = "示例支付"),
        PayChannelDto("WECHAT", "微信支付", enabled = true, mock = true, note = "示例支付"),
        PayChannelDto("UNIONPAY", "云闪付", enabled = true, mock = true, note = "示例支付"),
        PayChannelDto("BANKCARD", "银行卡", enabled = true, mock = true, note = "示例支付")
    )

    private companion object {
        const val CHANNEL_WECHAT = "WECHAT"
        const val ORDER_STATUS_PAID = "PAID"
        const val ORDER_STATUS_CLOSED = "CLOSED"
        /** 轮询间隔：微信回调到达服务端通常在秒级，2 秒足够密且不压服务端 */
        const val POLL_INTERVAL_MS = 2_000L
        /** 总轮询窗口：覆盖用户在微信收银台停留 + 回调传播；超时后给"确认中"提示 */
        const val POLL_TOTAL_MS = 60_000L
    }
}
