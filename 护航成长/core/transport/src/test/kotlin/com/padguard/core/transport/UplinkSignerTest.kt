package com.padguard.core.transport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-P0-3 上行签名器单测：覆盖信封结构、原文还原、签名验签、缺密钥降级。
 * 与服务端 [com.padguard.server.security.UplinkSigning] 共用同一 HMAC 算法
 * （HMAC-SHA256 + Base64 + UTF-8），本测试锁定客户端侧契约。
 */
class UplinkSignerTest {

    private val json = Json.Default
    private val secret = "hs_test-secret-123"
    private val payload = """{"msgId":"m1","status":"SUCCESS","executedAt":123}"""

    @Test
    fun `wrap produces envelope with s and p and round-trips payload`() {
        val env = UplinkSigner.wrap(payload, secret)!!
        val node = json.parseToJsonElement(env) as JsonObject
        assertTrue(node.containsKey("s"))
        assertTrue(node.containsKey("p"))
        // p 是原始报文文本，原文逐字还原（服务端按 p 原文验签，杜绝 JSON 重排差异）
        val restored = (node["p"] as JsonPrimitive).content
        assertEquals(payload, restored)
        assertEquals(payload, UplinkSigner.unwrapPayload(env))
    }

    @Test
    fun `verify succeeds for correct signature and fails for tampered`() {
        val env = UplinkSigner.wrap(payload, secret)!!
        val node = json.parseToJsonElement(env) as JsonObject
        val sig = (node["s"] as JsonPrimitive).content
        val p = (node["p"] as JsonPrimitive).content
        assertTrue("正确签名应验过", UplinkSigner.verify(p, sig, secret))
        assertFalse("篡改签名应失败", UplinkSigner.verify(p, "tampered", secret))
        assertFalse("篡改密钥应失败", UplinkSigner.verify(p, sig, "$secret-x"))
    }

    @Test
    fun `wrap returns null when secret is blank`() {
        assertNull(UplinkSigner.wrap(payload, ""))
        assertNull(UplinkSigner.wrap(payload, "   "))
    }

    @Test
    fun `payload with special chars survives envelope round-trip`() {
        val tricky = """{"note":"a\"b\\c\tend","n":1}"""
        val env = UplinkSigner.wrap(tricky, secret)!!
        assertEquals(tricky, UplinkSigner.unwrapPayload(env))
        val node = json.parseToJsonElement(env) as JsonObject
        assertTrue(UplinkSigner.verify((node["p"] as JsonPrimitive).content, (node["s"] as JsonPrimitive).content, secret))
    }
}
