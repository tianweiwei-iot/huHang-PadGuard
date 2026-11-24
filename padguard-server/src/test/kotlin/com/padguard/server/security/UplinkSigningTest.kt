package com.padguard.server.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [UplinkSigning] 与终端逐字节对齐的 HMAC-SHA256 验签契约。
 *
 * 向量由独立实现（Python hmac/hashlib）预先算出后硬编码，避免「用被测实现自己
 * 生成期望值」这种自证式断言。上行报文一旦验签失败一律拒收（fail-closed）。
 */
class UplinkSigningTest {

    private companion object {
        /** HMAC-SHA256(secret="secret", data="hello") 的 Base64 */
        const val VECTOR_HELLO = "iKqz7ejTrflNJquQ07r9SiCDBww7zOnAFO4EpEOEfAs="
        /** HMAC-SHA256(secret="每设备密钥", data="中文报文{\"a\":1}") 的 Base64 */
        const val VECTOR_UTF8 = "3O+dF71FxOrJU2aoz11Ee1bHRLFgXWz6VLeP6HQ4zig="
        /** HMAC-SHA256(secret="secret", data="") 的 Base64 */
        const val VECTOR_EMPTY_DATA = "+eZuF5tnR65UEI+C+K3os8Jddv0wr95sOVgixTAZYWk="
    }

    @Test
    fun `hmac 与独立实现的标准向量一致`() {
        assertEquals(VECTOR_HELLO, UplinkSigning.hmac("secret", "hello"))
    }

    @Test
    fun `hmac 对同一输入输出稳定`() {
        val a = UplinkSigning.hmac("device-secret", "{\"k\":1}")
        val b = UplinkSigning.hmac("device-secret", "{\"k\":1}")
        assertEquals(a, b)
    }

    @Test
    fun `正确签名验签通过`() {
        assertTrue(UplinkSigning.verify("secret", "hello", VECTOR_HELLO))
    }

    @Test
    fun `报文被改一个字节后验签失败`() {
        assertFalse(UplinkSigning.verify("secret", "hellp", VECTOR_HELLO))
    }

    @Test
    fun `密钥不同验签失败`() {
        assertFalse(UplinkSigning.verify("other-secret", "hello", VECTOR_HELLO))
    }

    @Test
    fun `签名长度不同直接拒绝`() {
        assertFalse(UplinkSigning.verify("secret", "hello", "abc"))
        assertFalse(UplinkSigning.verify("secret", "hello", ""))
    }

    @Test
    fun `等长但有一位不同的签名被拒绝`() {
        val tampered = VECTOR_HELLO.dropLast(1) + if (VECTOR_HELLO.last() == 'A') 'B' else 'A'
        assertEquals(VECTOR_HELLO.length, tampered.length)
        assertFalse(UplinkSigning.verify("secret", "hello", tampered))
    }

    @Test
    fun `UTF-8 中文密钥与 JSON 报文验签通过`() {
        val data = "中文报文{\"a\":1}"
        assertEquals(VECTOR_UTF8, UplinkSigning.hmac("每设备密钥", data))
        assertTrue(UplinkSigning.verify("每设备密钥", data, VECTOR_UTF8))
    }

    @Test
    fun `空报文仍按标准 HMAC 计算且可验签`() {
        assertEquals(VECTOR_EMPTY_DATA, UplinkSigning.hmac("secret", ""))
        assertTrue(UplinkSigning.verify("secret", "", VECTOR_EMPTY_DATA))
    }

    @Test
    fun `空密钥被 fail closed 拒绝 不静默生成弱签名`() {
        assertThrows(IllegalArgumentException::class.java) { UplinkSigning.hmac("", "hello") }
    }

    @Test
    fun `签名大小写敏感`() {
        assertFalse(UplinkSigning.verify("secret", "hello", VECTOR_HELLO.lowercase()))
    }
}
