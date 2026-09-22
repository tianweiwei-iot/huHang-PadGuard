package com.padguard.core.common

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 本地数据加密工具（对应说明书「本地数据存储规范」：AES 加密存储）。
 *
 * 采用 AES-256-GCM：既加密又做完整性校验，可检测出本地数据被篡改的情况。
 */
object Crypto {

    private const val AES_ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val IV_LENGTH_BYTES = 12

    fun generateKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(AES_ALGORITHM)
        keyGenerator.init(256)
        return keyGenerator.generateKey()
    }

    fun keyFromBytes(raw: ByteArray): SecretKey = SecretKeySpec(raw, AES_ALGORITHM)

    /**
     * AES-GCM 加密。输出结构：[12 字节 IV][密文+16 字节 GCM Tag]
     *
     * IV 由 Cipher 对象随机生成，而不是调用方自造：
     * Android Keystore 密钥（setRandomizedEncryptionRequired=true，默认开启）
     * 禁止调用方传入 IV，自造 IV 会直接抛 InvalidAlgorithmParameterException。
     */
    fun encrypt(plain: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv ?: error("cipher did not generate an IV")
        val cipherText = cipher.doFinal(plain)
        return iv + cipherText
    }

    /**
     * AES-GCM 解密。数据被篡改时会抛出 AEADBadTagException。
     */
    fun decrypt(packed: ByteArray, key: SecretKey): ByteArray {
        require(packed.size > IV_LENGTH_BYTES) { "cipher text too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = packed.copyOfRange(0, IV_LENGTH_BYTES)
        val cipherText = packed.copyOfRange(IV_LENGTH_BYTES, packed.size)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher.doFinal(cipherText)
    }

    fun sha256(input: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input)

    fun sha256Hex(input: String): String =
        sha256(input.toByteArray()).joinToString("") { "%02x".format(it) }

    fun base64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    fun fromBase64(text: String): ByteArray =
        Base64.decode(text, Base64.NO_WRAP)

    /**
     * 指令签名校验（对应说明书「数据传输安全规范」：指令签名校验通过后方可执行）。
     *
     * 签名算法：HMAC-SHA256，秘钥由服务端与终端共享（绑定时下发）。
     * 采用固定时间比较，避免时序侧信道。
     */
    fun verifyHmac(payload: String, signature: String, secret: String): Boolean {
        val expected = hmacSha256(payload, secret)
        return constantTimeEquals(expected, signature)
    }

    fun hmacSha256(payload: String, secret: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return base64(mac.doFinal(payload.toByteArray()))
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
