package com.padguard.server.security

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.domain.PayChannel
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.File
import java.security.cert.X509Certificate
import kotlin.math.abs

/**
 * 微信支付 v3 回调验签器。
 *
 * 接入后，[com.padguard.server.security.ConfigurablePaymentVerifier] 会在收到 WECHAT 渠道回调时委托本类；
 * 同时 [com.padguard.server.service.BillingService] 也直接调用 [verifyAndParse] 走微信专用回调分支。
 *
 * 核心校验链（任意一步失败 → 拒绝入账，fail closed）：
 *   1. 配置齐备（enabled + APIv3 密钥 + 平台证书）；
 *   2. 时间戳防重放（±5 分钟）；
 *   3. 用微信平台证书公钥验 RSA-SHA256 签名（签名串 = timestamp\nnonce\nbody\n）；
 *   4. 用 APIv3 密钥 AES-256-GCM 解密 resource，得到交易明文；
 *   5. 明文 trade_state == SUCCESS（退款等事件不入账）。
 *
 * 平台证书：优先启动时自动从 /v3/certificates 拉取（需商户私钥+序列号），缺失时回退到
 * platform-cert-dir / platform-certs 静态配置；两者都为空则 fail closed。
 */
@Component
class WeChatPayVerifier(
    private val props: WeChatPayProperties,
    private val objectMapper: ObjectMapper
) : ChannelPaymentVerifier {

    override val channel: PayChannel = PayChannel.WECHAT

    private val log = LoggerFactory.getLogger(WeChatPayVerifier::class.java)

    private val certs = mutableListOf<X509Certificate>()

    private val apiV3Key: ByteArray? by lazy {
        props.apiV3Key?.let { runCatching { WeChatPayCrypto.decodeApiV3Key(it) }.getOrNull() }
    }

    init {
        props.platformCerts?.forEach { addCert(it) }
        props.platformCertDir?.let { dir ->
            val dirFile = File(dir)
            if (dirFile.isDirectory) {
                runCatching { dirFile.listFiles { f -> f.extension.equals("pem", true) } }
                    .getOrNull()
                    ?.forEach { addCert(it.readText()) }
            }
        }
        if (props.certAutoRefresh && props.readyForPay()) {
            val priv = props.resolvePrivateKey()?.let { runCatching { WeChatPayCrypto.loadPrivateKey(it) }.getOrNull() }
            val key = apiV3Key
            if (priv != null && key != null) {
                val downloaded = WeChatPayCrypto.downloadPlatformCertificates(
                    props.baseUrl, props.mchId!!, props.serialNo!!, priv, key
                )
                downloaded.forEach { addCert(it) }
                log.info("微信平台证书自动拉取完成：本次新增 {} 张，共 {} 张", downloaded.size, certs.size)
            }
        }
        log.info(
            "WeChatPayVerifier 初始化：enabled={}, 平台证书={} 张, apiV3Key 就绪={}",
            props.enabled, certs.size, apiV3Key != null
        )
    }

    private fun addCert(pem: String) {
        WeChatPayCrypto.loadCertificates(pem).forEach { c ->
            if (certs.none { it.serialNumber == c.serialNumber }) certs.add(c)
        }
    }

    /**
     * 通用验签入口（接口实现）：仅返回通过/拒绝。供 [ConfigurablePaymentVerifier] 统一调度。
     */
    override fun verify(channel: String, rawBody: String, headers: Map<String, String>): Boolean =
        authenticate(rawBody, headers) != null

    /**
     * 微信专用回调解析：在 [verify] 全部校验通过的基础上，从解密后的交易明文里取出
     * 商户订单号（out_trade_no）与微信支付订单号（transaction_id）。
     * 任一环节失败返回 null（调用方据此拒绝入账）。
     */
    fun verifyAndParse(rawBody: String, headers: Map<String, String>): WeChatNotifyResult? {
        val tx = authenticate(rawBody, headers)?.transaction ?: return null
        val outTradeNo = tx["out_trade_no"]?.asText()
        if (outTradeNo.isNullOrBlank()) {
            log.warn("微信验签通过但缺少 out_trade_no")
            return null
        }
        return WeChatNotifyResult(outTradeNo, tx["transaction_id"]?.asText())
    }

    // ---- 以下为通用校验链，verify / verifyAndParse 共用 ----

    private fun authenticate(rawBody: String, headers: Map<String, String>): Authed? {
        if (!props.readyForVerify() || apiV3Key == null) {
            log.warn("微信验签拒绝：未配置（enabled/apiV3Key 缺失）")
            return null
        }
        if (certs.isEmpty()) {
            log.warn("微信验签拒绝：无任何平台证书")
            return null
        }

        val h = headers.mapKeys { it.key.lowercase() }
        val timestamp = h["wechatpay-timestamp"]
            ?: return null.also { log.warn("微信验签拒绝：缺 Wechatpay-Timestamp") }
        val nonce = h["wechatpay-nonce"]
            ?: return null.also { log.warn("微信验签拒绝：缺 Wechatpay-Nonce") }
        val signature = h["wechatpay-signature"]
            ?: return null.also { log.warn("微信验签拒绝：缺 Wechatpay-Signature") }
        val serial = h["wechatpay-serial"]
            ?: return null.also { log.warn("微信验签拒绝：缺 Wechatpay-Serial") }

        // 2) 防重放：时间戳偏差超过 5 分钟拒绝
        val ts = runCatching { timestamp.toLong() }.getOrNull()
        if (ts == null || abs(System.currentTimeMillis() / 1000 - ts) > 300) {
            log.warn("微信验签拒绝：时间戳偏差过大 ts={}", ts)
            return null
        }

        // 3) 验签
        val message = "$timestamp\n$nonce\n$rawBody\n"
        val cert = WeChatPayCrypto.findCertBySerial(certs, serial)
            ?: return null.also { log.warn("微信验签拒绝：未知平台证书序列号 {}", serial) }
        if (!WeChatPayCrypto.verifySignature(cert.publicKey, message, signature)) {
            log.warn("微信验签拒绝：签名不匹配")
            return null
        }

        // 4) 解密 resource + 5) 校验成功支付
        return try {
            val outer = objectMapper.readTree(rawBody)
            val eventType = outer["event_type"]?.asText()
            if (eventType != null && eventType.startsWith("REFUND")) {
                log.warn("微信验签拒绝：退款类事件 {}", eventType)
                return null
            }
            val res = outer["resource"]
                ?: return null.also { log.warn("微信验签拒绝：无 resource 字段") }
            val alg = res["algorithm"]?.asText() ?: "AEAD_AES_256_GCM"
            if (alg != "AEAD_AES_256_GCM") {
                log.warn("微信验签拒绝：不支持的加密算法 {}", alg)
                return null
            }
            val decrypted = WeChatPayCrypto.decryptResource(
                apiV3Key!!, res["ciphertext"].asText(), res["nonce"].asText(), res["associated_data"]?.asText()
            )
            val tx = objectMapper.readTree(decrypted)
            val tradeState = tx["trade_state"]?.asText()
            if (tradeState != "SUCCESS") {
                log.warn("微信验签拒绝：trade_state={}（仅 SUCCESS 可入账）", tradeState)
                return null
            }
            log.info("微信支付回调验签通过：out_trade_no={}, transaction_id={}", tx["out_trade_no"]?.asText(), tx["transaction_id"]?.asText())
            Authed(tx)
        } catch (e: Exception) {
            log.warn("微信验签拒绝：解密/解析失败", e)
            null
        }
    }

    private data class Authed(val transaction: JsonNode)
}

/** 微信回调解析结果：商户订单号 + 微信支付订单号 */
data class WeChatNotifyResult(val outTradeNo: String, val transactionId: String?)
