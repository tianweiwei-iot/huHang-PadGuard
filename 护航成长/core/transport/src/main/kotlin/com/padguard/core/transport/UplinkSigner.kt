package com.padguard.core.transport

import com.padguard.core.common.Crypto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 上行报文应用层签名（R-P0-3：MQTT 上行伪造）。
 *
 * ## 威胁
 * 家庭部署下 MQTT 凭据是**共享**的（见服务端 DeviceService 注释：Windows 版 Mosquitto
 * 无法热加载 password_file，故家庭包注入同一份 username/password）。这意味着任何能连上
 * broker 的一方都能冒充孩子端伪造上行——谎报指令回执成功（ack）、伪装在线（heartbeat）、
 * 淹没或伪造风险告警（event）。下行指令已有 HMAC 验签，上行却裸奔，是信任链的硬伤。
 *
 * ## 方案
 * 复用与下行同源的 `device.hmacSecret`，对孩子端上行 payload 做 HMAC-SHA256（Base64），
 * 服务端 [com.padguard.server.security.UplinkSigning] 验签、fail-closed 拒收伪造。
 *
 * ## 信封
 * `{"s":"<base64 HMAC>","p":"<原始 JSON 文本>"}`。把**原始 payload 文本**整体作为 HMAC 输入，
 * 服务端取出 `p` 原文按字节验签——绕开"JSON 重排 / 键序 / 转义差异导致 canonical 不一致"的坑，
 * 且签名与正文同走一条 MQTT 报文，无需 MQTTv5 用户属性（客户端用的是 mqttv3）。
 *
 * ## 兼容
 * `secret` 为空（未绑定 / mock 联调）时返回 null，调用方退回明文发送，不破坏既有联调路径。
 * 遗嘱离线（`{"online":false}`）由 broker 代发、无法签名，服务端对"未签名且 online=false"
 * 的 status 单独豁免（详见 ChildUplinkHandler）。
 */
object UplinkSigner {
    private val json = Json.Default

    @Serializable
    data class Envelope(val s: String, val p: String)

    /** 用密钥对 payload 签名并包成信封；secret 为空时返回 null（走明文兼容）。 */
    fun wrap(payload: String, secret: String): String? {
        if (secret.isBlank()) return null
        val sig = Crypto.hmacSha256(payload, secret)
        return json.encodeToString(Envelope.serializer(), Envelope(sig, payload))
    }

    /** 取出信封内的原始 payload；非信封（裸报文）返回 null。仅用于测试与下游解析。 */
    fun unwrapPayload(envelopeJson: String): String? =
        runCatching {
            val node = json.parseToJsonElement(envelopeJson)
            if (node is JsonObject && node.containsKey("s") && node.containsKey("p")) {
                (node["p"] as? JsonPrimitive)?.contentOrNull
            } else null
        }.getOrNull()

    /** 验签（与下行 CommandGate 同算法，便于对称测试）。 */
    fun verify(payload: String, signature: String, secret: String): Boolean =
        Crypto.verifyHmac(payload, signature, secret)
}
