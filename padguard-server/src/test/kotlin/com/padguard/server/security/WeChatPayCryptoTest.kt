package com.padguard.server.security

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 微信支付 v3 加解密/验签工具的单测（第八轮评估 P2-10：支付链路零测试缺口）。
 *
 * 覆盖三条高风险路径，全部纯 JVM 无网络：
 * 1. APIv3 密钥三种合法格式解析 + 非法格式拒绝；
 * 2. RSA-SHA256 签名/验签闭环 + 篡改报文验签必须失败（防伪造回调）；
 * 3. AES-256-GCM resource 解密闭环（nonce 按 Base64 解码为 12B IV）+ 错误密钥/坏 IV 必须失败。
 */
class WeChatPayCryptoTest {

    private fun generateRsaKeyPair() = KeyPairGenerator.getInstance("RSA")
        .apply { initialize(2048) }.generateKeyPair()

    @Test
    fun `apiV3 key parses all three supported formats to 32 bytes`() {
        val raw32 = "0123456789abcdef0123456789abcdef"
        assertArrayEquals(raw32.toByteArray(Charsets.ISO_8859_1), WeChatPayCrypto.decodeApiV3Key(raw32))

        // HEX 写法：把 raw32 的字节序列转成 64 位 HEX 字符串，解析后应还原同一字节序列
        val hex = raw32.toByteArray(Charsets.ISO_8859_1).joinToString("") { "%02x".format(it) }
        assertArrayEquals(raw32.toByteArray(Charsets.ISO_8859_1), WeChatPayCrypto.decodeApiV3Key(hex))

        val base64 = Base64.getEncoder().encodeToString(raw32.toByteArray(Charsets.ISO_8859_1))
        assertArrayEquals(raw32.toByteArray(Charsets.ISO_8859_1), WeChatPayCrypto.decodeApiV3Key(base64))
    }

    @Test
    fun `apiV3 key rejects malformed input instead of silently producing a weak key`() {
        assertThrows(IllegalArgumentException::class.java) { WeChatPayCrypto.decodeApiV3Key("short") }
        assertThrows(IllegalArgumentException::class.java) { WeChatPayCrypto.decodeApiV3Key("") }
        // 63 位：既不是 32/44/64，必须拒绝
        assertThrows(IllegalArgumentException::class.java) {
            WeChatPayCrypto.decodeApiV3Key("0123456789abcdef0123456789abcde")
        }
    }

    @Test
    fun `rsa signature round-trip verifies and tampered message fails`() {
        val kp = generateRsaKeyPair()
        val message = "POST\n/v3/pay/transactions/app\n1700000000\nnonce\nbody\n"
        val signature = WeChatPayCrypto.signRSA(kp.private, message)

        assertTrue(WeChatPayCrypto.verifySignature(kp.public, message, signature))
        // 篡改 body（例如把金额改掉）后验签必须失败 —— 防伪造回调的根基
        assertFalse(WeChatPayCrypto.verifySignature(kp.public, message.replace("body", "tampered"), signature))
        // 非法 Base64 签名不抛异常、返回 false（验签失败走业务拒绝路径）
        assertFalse(WeChatPayCrypto.verifySignature(kp.public, message, "not-base64!!!"))
    }

    @Test
    fun `resource decrypt round-trips with base64 nonce as 12-byte iv`() {
        val key = "0123456789abcdef0123456789abcdef".toByteArray(Charsets.ISO_8859_1)
        val iv = ByteArray(12) { it.toByte() }
        val plaintext = """{"out_trade_no":"PG202611010000011234","transaction_id":"wx-1"}"""

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD("transaction".toByteArray(Charsets.UTF_8)) // 与解密侧 AAD 对齐
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val decrypted = WeChatPayCrypto.decryptResource(
            key,
            Base64.getEncoder().encodeToString(ciphertext),
            Base64.getEncoder().encodeToString(iv),
            "transaction"
        )
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun `resource decrypt fails closed on wrong key or bad nonce`() {
        val key = "0123456789abcdef0123456789abcdef".toByteArray(Charsets.ISO_8859_1)
        val iv = ByteArray(12) { 7 }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val ciphertextB64 = Base64.getEncoder().encodeToString(cipher.doFinal("secret".toByteArray()))
        val nonceB64 = Base64.getEncoder().encodeToString(iv)

        // 错误密钥：GCM 校验失败必须抛异常（fail-closed），不能返回乱码当成功
        assertThrows(Exception::class.java) {
            WeChatPayCrypto.decryptResource(ByteArray(32), ciphertextB64, nonceB64, null)
        }
        // nonce 解码后不是 12 字节：必须拒绝（对接坑位回归）
        val shortNonce = Base64.getEncoder().encodeToString(ByteArray(8))
        assertThrows(IllegalArgumentException::class.java) {
            WeChatPayCrypto.decryptResource(key, ciphertextB64, shortNonce, null)
        }
    }
}
