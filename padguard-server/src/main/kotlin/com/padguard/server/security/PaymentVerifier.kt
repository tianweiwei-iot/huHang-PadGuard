package com.padguard.server.security

import com.padguard.server.domain.PayChannel
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * 支付回调验签抽象。
 *
 * 为什么必须有一层验签：支付网关（支付宝/微信/云闪付）的异步回调是无鉴权的公开 HTTP 接口，
 * 任何人都能伪造一个 POST 打到 `/v1/billing/notify`。若直接拿回调里的 orderNo 去开通订阅，
 * 攻击者只要猜到一个有效订单号就能白嫖年卡 —— 这是计费系统最致命的一类"免费后门"，
 * 而且不报错、只能靠对账发现。
 *
 * 验签的输入是**原始报文 + 回调头**：签名是网关对原始字节算的，解析后的 DTO 重新序列化后
 * 字节不一定一致，所以必须把 `HttpServletRequest` 的原文字节流透传给 [verify]，不能只传解析后的对象。
 */
interface PaymentVerifier {
    /**
     * @param channel 订单渠道（[PayChannel] 的 name，如 WECHAT/ALIPAY）
     * @param rawBody 回调原始报文（未改动的字节）
     * @param headers 回调请求头（网关签名通常放在头里，如微信的 Wechatpay-Signature）
     * @return true = 验签通过，可以入账；false = 拒绝入账
     */
    fun verify(channel: String, rawBody: String, headers: Map<String, String>): Boolean
}

/**
 * 单渠道验签器：接入真实渠道时各实现一个（支付宝/微信/云闪付），按 [channel] 注册到 [ConfigurablePaymentVerifier]。
 *
 * 现阶段不实现任何具体渠道（商户号/证书/密钥都还没接入），[ConfigurablePaymentVerifier] 对未注册的
 * 真实渠道一律拒绝，使其"fail closed"——宁可让家长付了钱暂时收不到回调，也不能开一条免验证白嫖通道。
 */
interface ChannelPaymentVerifier : PaymentVerifier {
    /** 本验签器负责的渠道 */
    val channel: PayChannel
}

/**
 * 可配置的验签中枢。
 *
 * 决策顺序（都是 fail closed，只有明确放行才入账）：
 *
 * 1. **示例支付模式**（`padguard.billing.mock-pay-enabled=true`）：放行所有回调。
 *    开发期没有真实商户密钥，全链路要靠它端到端跑通；生产该开关必须为 false。
 * 2. **渠道未注册**：真实渠道（WECHAT/ALIPAY/...）尚未接入验签器 → 拒绝。
 *    这是默认且唯一的生产行为，直到某天补上对应 [ChannelPaymentVerifier]。
 * 3. **渠道已注册**：交给该渠道的验签器判定，它自己负责按网关规则验签。
 *
 * 注意 [PayChannel.MANUAL]（后台代开通）**不**走公开回调：客服代开通由后台 `manualGrant` 直接入账，
 * 公开 `notify` 收到 MANUAL 单号属于异常情况，这一层不专门拦截（交给 BillingService 在业务层判），
 * 但 MANUAL 也不会有对应验签器，所以生产默认下会被第 2 步直接挡掉。
 */
@Component
class ConfigurablePaymentVerifier(
    @Value("\${padguard.billing.mock-pay-enabled:false}") private val mockPayEnabled: Boolean,
    private val channelVerifiers: List<ChannelPaymentVerifier>
) : PaymentVerifier {

    private val log = LoggerFactory.getLogger(ConfigurablePaymentVerifier::class.java)

    private val byChannel = channelVerifiers.associateBy { it.channel }

    override fun verify(channel: String, rawBody: String, headers: Map<String, String>): Boolean {
        // 1) 示例支付（开发）模式：跳过验签，便于端到端验证"下单→支付→开通"链路。
        if (mockPayEnabled) {
            log.debug("payment verify skipped (mock-pay-enabled): channel={}", channel)
            return true
        }

        val ch = PayChannel.of(channel)
            ?: run {
                log.warn("payment verify rejected: unknown channel={}", channel)
                return false
            }

        // 2) 真实渠道未接入验签器 → 拒绝（fail closed）。
        val verifier = byChannel[ch]
            ?: run {
                log.warn("payment verify rejected: channel {} has no verifier integrated", ch)
                return false
            }

        // 3) 委托给该渠道的验签器。
        return verifier.verify(channel, rawBody, headers)
    }
}
