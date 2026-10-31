package com.padguard.child.guard

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.util.Base64

/**
 * 用 Android Keystore（TEE / StrongBox）的 AES/GCM 密钥，对「操作密码哈希」做二次加密后再落盘。
 *
 * ## 目的
 * 即使 DataStore 文件被 root / `adb backup` / 云备份提取，没有 Keystore 主密钥也解不开哈希，
 * 离线爆破 PIN 的门槛从「算一次 SHA-256」抬到「先破解 TEE」+ 慢哈希。
 * 与 [GuardPasswordManager] 的 PBKDF2 慢哈希叠加，构成双重防护。
 *
 * ## 降级
 * 低端机 Keystore 异常时 [safeEncrypt]/[safeDecrypt] 返回 null，调用方降级为明文存储并告警
 * （与现状一致，但哈希已是加盐慢哈希，离线爆破成本仍高）。
 */
object HashCipher {
    private const val KEY_ALIAS = "padguard_guard_hash_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORM =
        "${KeyProperties.KEY_ALGORITHM_AES}/${KeyProperties.BLOCK_MODE_GCM}/${KeyProperties.ENCRYPTION_PADDING_NONE}"
    private const val GCM_IV_LEN = 12
    private const val GCM_TAG_LEN = 128

    private fun getKey(): SecretKey? = runCatching {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val entry = ks.getEntry(KEY_ALIAS, null)
        if (entry is KeyStore.SecretKeyEntry) {
            entry.secretKey
        } else {
            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build()
            kg.init(spec)
            kg.generateKey()
        }
    }.getOrNull()

    /** 加密；Keystore 不可用时返回 null（调用方降级明文）。输出 `base64(iv):base64(ct)`。 */
    fun safeEncrypt(plain: String): String? = runCatching {
        val key = getKey() ?: return null
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val enc = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(enc)
    }.getOrNull()

    /** 解密；Keystore 不可用或密文损坏时返回 null。 */
    fun safeDecrypt(cipherText: String?): String? {
        if (cipherText.isNullOrBlank()) return null
        return runCatching {
            val key = getKey() ?: return null
            val parts = cipherText.split(":", limit = 2)
            if (parts.size != 2) return null
            val iv = Base64.getDecoder().decode(parts[0])
            val enc = Base64.getDecoder().decode(parts[1])
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LEN, iv))
            String(cipher.doFinal(enc), Charsets.UTF_8)
        }.getOrNull()
    }
}
