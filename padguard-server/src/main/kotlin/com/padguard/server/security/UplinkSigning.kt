package com.padguard.server.security

import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 上行报文应用层验签（R-P0-3：MQTT 上行伪造）。
 *
 * 与终端 [com.padguard.core.transport.UplinkSigner] 逐字节对齐：
 * HMAC-SHA256，Base64 编码，UTF-8 字节输入。密钥为每设备 `hmac_secret`（绑定时下发，
 * 与下行指令共享同一把）。任何验签失败的报文一律拒收并告警（fail-closed），
 * 绝不能"静默丢弃"——伪造上行要么是攻击，要么是端上签名实现不一致，都必须被看到。
 *
 * 信封格式：`{"s":"<base64 HMAC>","p":"<原始 JSON 文本>"}`，验签对象是 `p` 的**原文**
 * （不做任何 JSON 重排），规避两端序列化差异。
 */
object UplinkSigning {

    fun hmac(secret: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return Base64.getEncoder().encodeToString(mac.doFinal(data.toByteArray(StandardCharsets.UTF_8)))
    }

    fun verify(secret: String, data: String, signature: String): Boolean {
        val expected = hmac(secret, data)
        return constantTimeEquals(expected, signature)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }
}
