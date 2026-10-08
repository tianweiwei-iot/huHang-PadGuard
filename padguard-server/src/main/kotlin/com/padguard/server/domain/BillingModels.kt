package com.padguard.server.domain

import jakarta.persistence.*

/**
 * 套餐档位。
 *
 * 只定义"是什么"，**不定义价格** —— 价格与设备上限由配置项决定（见 BillingConfig），
 * 运营调价不该改代码重新发版。
 */
enum class Plan(val key: String, val label: String, val months: Int) {
    /** 新用户免费试用：不算付费套餐，但复用同一套状态机，保证"没付费也能进系统看看" */
    TRIAL("TRIAL", "免费试用", 0),
    MONTHLY("MONTHLY", "月卡", 1),
    QUARTERLY("QUARTERLY", "季卡", 3),
    YEARLY("YEARLY", "年卡", 12),
    /**
     * 永久会员：一次性开通、永久有效。
     *
     * 不作为可购买套餐（[purchasable] 已排除），也不参与收银台下单与金额计算，
     * 只经由后台种子 / 运营手动开通写入。状态机对它特殊处理：
     * [com.padguard.server.service.BillingService.refreshStatus] 永不把它置为过期，
     * 它是"付费即全功能"的永久形态。
     */
    PERMANENT("PERMANENT", "永久会员", 0);

    companion object {
        fun of(key: String): Plan? = entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
        /** 可购买的付费套餐（不含试用与永久会员） */
        fun purchasable(): List<Plan> = entries.filter { it != TRIAL && it != PERMANENT }
    }
}

/** 订阅状态 */
enum class SubStatus {
    /** 试用期内 */
    TRIAL,
    /** 已付费且在有效期内 */
    ACTIVE,
    /** 试用结束或付费到期 */
    EXPIRED,
    /** 退款或封禁 */
    REVOKED
}

/** 订单状态 */
enum class OrderStatus {
    /** 已创建，等待支付 */
    PENDING,
    /** 已支付 */
    PAID,
    /** 支付失败 */
    FAILED,
    /** 超时未支付已关闭 */
    CLOSED,
    /** 已退款 */
    REFUNDED
}

/**
 * 支付渠道。
 *
 * 目前**全部走示例支付**（[PayChannelDto.mock] = true）：收银台的渠道选择与下单、
 * 支付、开通全链路已经跑通，等拿到商户资质后，只需在 `BillingService.createOrder`
 * 里按渠道换成真实的 `payParams`（微信 prepay_id / 支付宝 orderString / 云闪付 tn），
 * 并把回调验签补进 `BillingController.notify`，前端不需要改。
 *
 * 因此渠道枚举必须**现在就与真实渠道一一对应**，不能只留一个笼统的 "ONLINE"：
 * 否则将来接入时前端展示、订单表里的 channel 字符串、对账口径全都要返工。
 */
enum class PayChannel(val label: String) {
    /** 支付宝 */
    ALIPAY("支付宝"),

    /** 微信支付 */
    WECHAT("微信支付"),

    /** 银联云闪付 */
    UNIONPAY("云闪付"),

    /** 银行卡（借记卡 / 信用卡，走银联或第三方聚合通道） */
    BANKCARD("银行卡"),

    /**
     * 后台手动开通。
     *
     * 不是偷懒：接入真实渠道需要商户号、证书与异步回调验签，
     * 在没有商户资质前它是唯一的通路；同时它也是客服处理"支付成功但没到账"的兜底手段。
     * 正式运营后应限制为仅管理员可调用。
     */
    MANUAL("客服代开通");

    companion object {
        fun of(key: String): PayChannel? = entries.firstOrNull { it.name.equals(key, ignoreCase = true) }

        /** 收银台展示的渠道（不含后台手动开通） */
        fun checkoutChannels(): List<PayChannel> = entries.filter { it != MANUAL }
    }
}

/**
 * 家长账号的订阅。
 *
 * 一个家长**最多一条**当前订阅（按 user_id 唯一），续费是在同一条上延长 expired_at，
 * 而不是新增记录 —— 否则"当前到底有没有效"要按时间排序取最新，
 * 一旦出现重叠区间（比如续费后又退款）就会判错。
 */
@Entity
@Table(
    name = "subscriptions",
    indexes = [Index(name = "idx_subscriptions_user", columnList = "user_id")]
)
class Subscription(
    @Id var id: String = "",
    @Column(name = "user_id") var userId: String = "",
    @Column(name = "plan") var plan: String = Plan.TRIAL.key,
    @Column(name = "status") var status: String = SubStatus.TRIAL.name,
    @Column(name = "device_limit") var deviceLimit: Int = 3,
    @Column(name = "started_at") var startedAt: Long = 0,
    @Column(name = "expired_at") var expiredAt: Long = 0,
    @Column(name = "auto_renew") var autoRenew: Boolean = false,
    @Column(name = "created_at") var createdAt: Long = 0,
    @Column(name = "updated_at") var updatedAt: Long = 0
)

/**
 * 支付订单。
 *
 * 金额一律用**分**存整数：浮点存钱迟早出对不齐账的问题。
 */
@Entity
@Table(
    name = "payment_orders",
    indexes = [
        Index(name = "idx_orders_user", columnList = "user_id, created_at"),
        Index(name = "idx_orders_no", columnList = "order_no")
    ]
)
class PaymentOrder(
    @Id var id: String = "",
    @Column(name = "order_no", unique = true) var orderNo: String = "",
    @Column(name = "user_id") var userId: String = "",
    @Column(name = "plan") var plan: String = "",
    /** 购买时长（月），与 Plan.months 一致，冗余存下来避免套餐定义变动后订单对不上账 */
    @Column(name = "months") var months: Int = 0,
    /** 金额（分） */
    @Column(name = "amount_fen") var amountFen: Int = 0,
    @Column(name = "channel") var channel: String = PayChannel.WECHAT.name,
    @Column(name = "status") var status: String = OrderStatus.PENDING.name,
    /** 第三方支付流水号，用于对账与回调去重 */
    @Column(name = "transaction_id") var transactionId: String? = null,
    @Column(name = "created_at") var createdAt: Long = 0,
    /** 支付超时时间：超过后订单关闭，避免用户拿着老订单重复支付 */
    @Column(name = "pay_deadline") var payDeadline: Long = 0,
    @Column(name = "paid_at") var paidAt: Long? = null
)
