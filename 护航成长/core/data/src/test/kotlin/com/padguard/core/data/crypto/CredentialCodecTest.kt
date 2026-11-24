package com.padguard.core.data.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖「Keystore 密钥销毁后不得把密文当令牌发出去」这条链。
 *
 * 回归背景：2026-11-07 孩子端救砖（完整卸载 → 全新安装 → run-as 恢复数据）后，
 * Android Keystore 密钥随卸载销毁，旧版 `dec()` 把 Base64 密文回退为「原文」，
 * 结果 18:53 起连续 401 TOKEN_INVALID、HMAC 签名全错，家长端看到设备一直离线。
 */
class CredentialCodecTest {

    /** 与 DeviceService 下发格式一致：两段去横线 UUID，纯 hex。 */
    private val deviceTokenPlain = "a".repeat(64)

    private fun packed(payloadBytes: Int, fill: (Int) -> Byte): String =
        java.util.Base64.getEncoder().encodeToString(ByteArray(payloadBytes, fill))

    @Test
    fun `服务端下发的四类明文凭据都不是密文形态`() {
        val samples = listOf(
            deviceTokenPlain,                                   // deviceToken
            "hs_" + "b".repeat(32),                             // hmacSecret
            "mk_" + "c".repeat(32),                             // mqttPassword
            "550e8400-e29b-41d4-a716-446655440000",             // mqttUsername(UUID)
            "p@ss=word+1",                                      // 短共享口令里偶发的 = / +
            "",
        )
        samples.forEach { value ->
            assertEquals("误判为密文: $value", false, CredentialCodec.looksLikeCipherText(value))
        }
    }

    @Test
    fun `deviceToken 密文 92 字节带填充可识别`() {
        val cipher = packed(92) { (it * 37 + 11).toByte() }
        assertTrue("构造样本应带填充", cipher.endsWith("="))
        assertTrue(CredentialCodec.looksLikeCipherText(cipher))
    }

    @Test
    fun `短字段密文 63 字节无填充但含 base64 专有字符也能识别`() {
        val cipher = packed(63) { 0xFB.toByte() }
        assertTrue("构造样本应无填充", cipher.none { it == '=' })
        assertTrue("构造样本应含 + 或 /", cipher.any { it == '+' || it == '/' })
        assertTrue(CredentialCodec.looksLikeCipherText(cipher))
    }

    @Test
    fun `最小合法载荷与非法 base64 边界`() {
        // 40 字符是最短密文长度：解码后 30 字节 > IV(12)+TAG(16)+1
        assertTrue(CredentialCodec.looksLikeCipherText("+".repeat(40)))
        // 含专有字符但根本不是合法 Base64 → 不能当成我们的密文
        assertEquals(false, CredentialCodec.looksLikeCipherText("!!!!!!!!!!=".repeat(4)))
        // 长度不足的带等号文本（共享口令）不被误伤
        assertEquals(false, CredentialCodec.looksLikeCipherText("abcd=".repeat(7) + "abcd"))
    }

    @Test
    fun `resolve 解密成功永远优先于形态判定`() {
        val cipher = packed(92) { (it * 7 + 3).toByte() }
        val result = CredentialCodec.resolve(cipher) { "decrypted-token" }
        assertEquals(CredentialCodec.Resolution.Plain("decrypted-token"), result)
    }

    @Test
    fun `resolve 解密失败且形态为密文判定凭据失效`() {
        val cipher = packed(92) { (it * 13 + 5).toByte() }
        val result = CredentialCodec.resolve(cipher) { throw javax.crypto.AEADBadTagException() }
        assertEquals(CredentialCodec.Resolution.Corrupted, result)
    }

    @Test
    fun `resolve 解密失败但为老版本明文时原样保留以平滑迁移`() {
        val result = CredentialCodec.resolve(deviceTokenPlain) { throw IllegalArgumentException() }
        assertEquals(CredentialCodec.Resolution.Plain(deviceTokenPlain), result)
    }

    @Test
    fun `resolve 空串不触发解密调用`() {
        var called = false
        val result = CredentialCodec.resolve("") { called = true; "unexpected" }
        assertEquals(CredentialCodec.Resolution.Plain(""), result)
        assertEquals(false, called)
    }
}
