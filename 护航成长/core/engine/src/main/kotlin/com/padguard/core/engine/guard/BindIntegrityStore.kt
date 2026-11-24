package com.padguard.core.engine.guard

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.padguard.core.common.Logger
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 设备「曾成功绑定」的持久标记，用于检测「清除应用数据」式脱管（P0-2）。
 *
 * ## 关键点
 * 本标记存于 **AndroidKeyStore**（系统密钥库），而「清除应用数据」只清 `/data/data` 下的
 * 数据库 / DataStore / 文件，**不删除密钥库条目**（API 24+）。
 * 因此清数据后 hmacSecret 等凭据消失、设备回到"未绑定"态，但本标记仍在——
 * 据此可区分「初次装机」与「曾被管控后失管」，把"沉默逃逸"变成可见的 CONTROL_LOST 事件。
 *
 * 卸载会删除密钥库条目（属预期：设备已交回，不应再报"曾绑定"）。
 */
@Singleton
class BindIntegrityStore @Inject constructor() {
    private val alias = "padguard_bind_integrity"

    /** 是否曾成功绑定（密钥库中存在该标记密钥）。 */
    fun wasEverBound(): Boolean = runCatching {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null, null) }.containsAlias(alias)
    }.getOrDefault(false)

    /** 标记设备已绑定（若标记密钥不存在则生成一个；仅作哨兵，不持有任何敏感数据）。 */
    fun markBound() {
        runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null, null) }
            if (ks.containsAlias(alias)) return
            val gen = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE
            )
            gen.init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            gen.generateKey()
            Logger.i(TAG) { "bind integrity marker set" }
        }.onFailure { Logger.w(TAG) { "markBound failed: ${it.message}" } }
    }

    companion object {
        private const val TAG = "BindIntegrityStore"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
