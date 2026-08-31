package com.padguard.core.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.padguard.core.common.Crypto
import java.security.KeyStore
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地数据加密密钥管理。
 *
 * 密钥存放于 Android Keystore（硬件 TEE/StrongBox -backed，若设备支持），
 * 具备以下性质：
 * - 私钥材料永不进入应用进程空间，无法被 dump 导出
 * - 与 APK 签名/设备绑定，换机或重装后不可用（管控数据自然失效，符合安全预期）
 *
 * 用于加密：策略包缓存、行为日志、告警事件（对应说明书「本地数据存储规范」）。
 */
@Singleton
class KeystoreManager @Inject constructor() {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    /** 获取（或首次创建）数据加密主密钥 */
    fun getDataKey(): SecretKey {
        keyStore.getEntry(ALIAS_DATA_KEY, null)?.let { entry ->
            if (entry is KeyStore.SecretKeyEntry) return entry.secretKey
        }
        return createDataKey()
    }

    private fun createDataKey(): SecretKey {
        val generator = javax.crypto.KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            ALIAS_DATA_KEY,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    /** 加密字符串为 Base64（用于策略包等文本数据落库） */
    fun encryptToBase64(plain: String): String {
        val bytes = Crypto.encrypt(plain.toByteArray(Charsets.UTF_8), getDataKey())
        return Crypto.base64(bytes)
    }

    @Throws(javax.crypto.AEADBadTagException::class)
    fun decryptFromBase64(cipherText: String): String {
        val bytes = Crypto.decrypt(Crypto.fromBase64(cipherText), getDataKey())
        return String(bytes, Charsets.UTF_8)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS_DATA_KEY = "padguard_data_key_v1"
    }
}
