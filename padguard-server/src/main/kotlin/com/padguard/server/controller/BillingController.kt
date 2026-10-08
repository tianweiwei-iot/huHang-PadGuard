package com.padguard.server.controller

import com.padguard.server.common.ApiResponse
import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.dto.CreateOrderRequest
import com.padguard.server.dto.PayRequest
import com.padguard.server.service.BillingService
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import java.io.ByteArrayOutputStream
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 计费 / 订阅接口（SaaS 模式）。
 *
 * 权限说明：这里**全部**都要登录（@RequestAttribute("userId") 由鉴权拦截器注入），
 * 即使是"查询套餐列表"这种看起来无害的接口也一样 ——
 * 未登录就能查到价格体系与设备额度，等于把商业信息免费公开。
 */
@RestController
@RequestMapping("/v1/billing")
class BillingController(
    private val billingService: BillingService,
    private val objectMapper: ObjectMapper
) {

    /** 可购买套餐（价格来自配置，运营可调） */
    @GetMapping("/plans")
    fun plans(@RequestAttribute("userId") userId: String) =
        ApiResponse.ok(billingService.plans())

    /**
     * 收银台可选的支付渠道。
     *
     * 当前全部为**示例支付**（mock=true），不产生真实扣款，仅打通"下单→选渠道→支付→开通"全链路；
     * 接入真实渠道后按渠道返回可用性即可，前端无需改动。
     */
    @GetMapping("/channels")
    fun channels(@RequestAttribute("userId") userId: String) =
        ApiResponse.ok(billingService.channels())

    /** 当前订阅状态；家长端用它决定"能不能用 / 还剩几天 / 要不要续费" */
    @GetMapping("/subscription")
    fun subscription(@RequestAttribute("userId") userId: String) =
        ApiResponse.ok(billingService.statusOf(userId))

    /** 下单 */
    @PostMapping("/orders")
    fun createOrder(
        @RequestAttribute("userId") userId: String,
        @RequestBody body: CreateOrderRequest
    ) = ApiResponse.ok(billingService.createOrder(userId, body))

    /** 我的订单 */
    @GetMapping("/orders")
    fun orders(
        @RequestAttribute("userId") userId: String,
        @RequestParam(defaultValue = "20") limit: Int
    ) = ApiResponse.ok(billingService.orders(userId, limit.coerceIn(1, 50)))

    /**
     * 模拟支付（仅 `padguard.billing.mock-pay-enabled=true` 时可用）。
     *
     * 存在理由：真实渠道未接入前，没有它整个付费链路无法端到端验证，
     * 只能靠改数据库造数据，那样"续费到底有没有延长订阅"根本没被测过。
     * 生产环境该开关必须是 false，否则任何人都能免费续费。
     */
    @PostMapping("/orders/{orderNo}/mock-pay")
    fun mockPay(
        @RequestAttribute("userId") userId: String,
        @PathVariable orderNo: String
    ) = ApiResponse.ok(billingService.mockPay(orderNo))

    /**
     * 支付回调统一入口（无鉴权：支付网关的异步回调带不来家长 JWT，验签是它的唯一信任依据）。
     *
     * 这里必须拿到**原始报文**：网关的签名是对原始字节算的，解析后再序列化字节可能不一致，
     * 所以直接从 `HttpServletRequest` 读原文字节流透传给验签器，而不是只传解析后的 [PayRequest]。
     * 验签 + 渠道校验 + 防白嫖都在 [com.padguard.server.service.BillingService.handleNotify] 完成，
     * 验签通过才调用 markPaid 入账。
     */
    @PostMapping("/notify")
    fun notify(request: HttpServletRequest): ApiResponse<*> {
        // 限定读取体积：支付回调报文极小（通常 < 1KB），无上限的 readBytes() 可被恶意超大请求
        // 撑爆堆内存（DoS）。这里按 1MB 上限流式读取，超限即拒绝。
        val rawBody = readBoundedBody(request, MAX_NOTIFY_BODY_BYTES)
        val req = try {
            objectMapper.readValue(rawBody, PayRequest::class.java)
        } catch (e: Exception) {
            throw BizException(ParentErr.PARAM_ERROR, "回调报文无法解析", Audience.PARENT)
        }
        val headers = request.headerNames?.toList().orEmpty()
            .associateWith { request.getHeader(it) }
        return ApiResponse.ok(billingService.handleNotify(req, rawBody, headers))
    }

    /** 按上限读取请求体；超出 [maxBytes] 直接拒绝，避免无限读入导致 OOM */
    private fun readBoundedBody(request: HttpServletRequest, maxBytes: Int): String {
        val buf = ByteArray(8192)
        val out = ByteArrayOutputStream()
        var total = 0
        var read: Int
        while (request.inputStream.read(buf).also { read = it } != -1) {
            total += read
            if (total > maxBytes) {
                throw BizException(ParentErr.PARAM_ERROR, "回调报文过大", Audience.PARENT)
            }
            out.write(buf, 0, read)
        }
        return out.toString(Charsets.UTF_8)
    }

    private companion object {
        /** 支付回调报文体积上限（1MB），远超真实回调，纯防滥用 */
        const val MAX_NOTIFY_BODY_BYTES = 1_048_576
    }
}
