package com.padguard.server.security

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

/**
 * 微信支付 v3 下单客户端（APP 支付）。
 *
 * 仅当 [WeChatPayProperties.readyForPay] 满足时才可用；未配置时 [createAppOrder] 返回 null，
 * 调用方据此拒绝下单，避免产生"没人收款"的订单。
 *
 * 流程：POST /v3/pay/transactions/app → 取 prepay_id → 用商户私钥对
 * `appid\ntimestamp\nnoncestr\nprepayid\n` 做 RSA-SHA256 二次签名，返回调起微信 SDK 所需的 payParams。
 * （APP 调起支付的二次签名是 RSA，不是 HMAC/API key，见微信支付 v3 官方文档。）
 */
@Component
class WeChatPayClient(
    private val props: WeChatPayProperties,
    private val objectMapper: ObjectMapper
) {

    private val log = LoggerFactory.getLogger(WeChatPayClient::class.java)
    private val http = HttpClient.newBuilder().build()

    fun isConfigured(): Boolean = props.readyForPay()

    /**
     * 创建一个 APP 支付预订单，返回调起微信 SDK 所需的参数；失败返回 null。
     * @param description 商品描述，如「护航成长-年卡会员」
     */
    fun createAppOrder(orderNo: String, amountFen: Int, description: String): Map<String, String>? {
        if (!props.readyForPay()) {
            log.warn("微信下单失败：未配置真实微信支付")
            return null
        }
        val appId = props.appId!!
        val mchId = props.mchId!!
        val serialNo = props.serialNo!!
        val privateKey = WeChatPayCrypto.loadPrivateKey(props.resolvePrivateKey()!!)
        val notifyUrl = props.notifyUrl ?: run {
            log.warn("微信下单失败：notify-url 未配置")
            return null
        }

        val body = objectMapper.writeValueAsString(
            mapOf(
                "appid" to appId,
                "mchid" to mchId,
                "description" to description,
                "out_trade_no" to orderNo,
                "notify_url" to notifyUrl,
                "amount" to mapOf("total" to amountFen, "currency" to "CNY")
            )
        )
        val path = "/v3/pay/transactions/app"
        val auth = WeChatPayCrypto.buildAuthorization("POST", path, body, mchId, serialNo, privateKey)
        return try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("${props.baseUrl}$path"))
                .header("Authorization", auth)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() != 200) {
                log.warn("微信下单失败：HTTP {} body={}", resp.statusCode(), resp.body())
                return null
            }
            val prepayId = objectMapper.readTree(resp.body())["prepay_id"]?.asText()
                ?: return null.also { log.warn("微信下单失败：响应缺少 prepay_id") }

            val ts = (System.currentTimeMillis() / 1000).toString()
            val nonce = UUID.randomUUID().toString().replace("-", "")
            val signMsg = "$appId\n$ts\n$nonce\n$prepayId\n"
            val sign = WeChatPayCrypto.signRSA(privateKey, signMsg)
            mapOf(
                "appid" to appId,
                "partnerid" to mchId,
                "prepayid" to prepayId,
                "package" to "Sign=WXPay",
                "noncestr" to nonce,
                "timestamp" to ts,
                "sign" to sign
            )
        } catch (e: Exception) {
            log.warn("微信下单异常：{}", e.message, e)
            null
        }
    }

    /**
     * 申请退款（POST /v3/refund/domestic/refunds）。
     *
     * 全额退款场景：refundFen == totalFen。申请成功（HTTP 200）即微信受理，
     * 最终结果以异步退款回调 / 主动查单为准（微信退款受理后失败率极低）。
     *
     * @param outRefundNo 商户退款单号（全局唯一，建议 "R" + 订单号；同一订单重复申请会报错）
     * @return 受理成功返回 map(refundId, status)；未配置/网络失败/微信拒绝返回 null（调用方拒绝退款）
     */
    fun createRefund(
        orderNo: String,
        outRefundNo: String,
        refundFen: Int,
        totalFen: Int,
        reason: String
    ): Map<String, String>? {
        if (!props.readyForPay()) {
            log.warn("微信退款失败：未配置真实微信支付")
            return null
        }
        val mchId = props.mchId!!
        val privateKey = WeChatPayCrypto.loadPrivateKey(props.resolvePrivateKey()!!)
        val body = objectMapper.writeValueAsString(
            buildMap {
                put("out_trade_no", orderNo)
                put("out_refund_no", outRefundNo)
                put("reason", reason)
                props.refundNotifyUrl?.takeIf { it.isNotBlank() }?.let { put("notify_url", it) }
                put("amount", mapOf("refund" to refundFen, "total" to totalFen, "currency" to "CNY"))
            }
        )
        val path = "/v3/refund/domestic/refunds"
        return try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("${props.baseUrl}$path"))
                .header("Authorization", WeChatPayCrypto.buildAuthorization("POST", path, body, mchId, props.serialNo!!, privateKey))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() != 200) {
                log.warn("微信退款失败：HTTP {} body={}", resp.statusCode(), resp.body())
                return null
            }
            val root = objectMapper.readTree(resp.body())
            val refundId = root["refund_id"]?.asText()
            if (refundId.isNullOrBlank()) {
                log.warn("微信退款失败：响应缺少 refund_id")
                return null
            }
            val status = root["status"]?.asText() ?: "PROCESSING"
            log.info("微信退款受理：orderNo={}, refundId={}, status={}", orderNo, refundId, status)
            mapOf("refundId" to refundId, "status" to status)
        } catch (e: Exception) {
            log.warn("微信退款异常：{}", e.message, e)
            null
        }
    }
}
