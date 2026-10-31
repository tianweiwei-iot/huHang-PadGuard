package com.padguard.parent.data.auth

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.padguard.core.common.crypto.KeystoreManager
import com.padguard.parent.domain.model.SceneType
import com.padguard.parent.domain.model.User
import com.padguard.parent.domain.model.UserRole
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.authDataStore: DataStore<Preferences> by preferencesDataStore(name = "auth_tokens")

/**
 * 登录态持久化（DataStore）。
 *
 * 职责：
 * - 保存/读取 JWT accessToken、refreshToken；
 * - 保存当前用户基础字段，供 [currentUserFlow] 与脱机态展示使用；
 * - 退出登录时清空。
 *
 * 安全加固：accessToken / refreshToken 不再以明文落盘，改用 Android Keystore(AES/GCM)
 * 加密存储（加密值以 `ENC:` 前缀标记）。读取时若带 `ENC:` 前缀则解密，否则按旧版明文
 * 兼容返回，实现无缝升级迁移；Keystore 不可用时降级为明文并告警。
 *
 * 与 Mock 实现 LocalDataSource 的偏好存储解耦，使用独立的 DataStore 文件，避免相互污染。
 */
@Singleton
class TokenManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keystore: KeystoreManager
) {

    private object Keys {
        val ACCESS_TOKEN = stringPreferencesKey("access_token")
        val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        val USER_ID = stringPreferencesKey("user_id")
        val USER_PHONE = stringPreferencesKey("user_phone")
        val USER_NICKNAME = stringPreferencesKey("user_nickname")
        val USER_AVATAR = stringPreferencesKey("user_avatar")
        val USER_ROLE = stringPreferencesKey("user_role")
        val SCENE_TYPE = stringPreferencesKey("scene_type")
    }

    suspend fun saveLogin(
        userId: String,
        phone: String,
        nickname: String?,
        avatar: String?,
        role: String,
        sceneType: String,
        accessToken: String,
        refreshToken: String
    ) {
        context.authDataStore.edit { prefs ->
            prefs[Keys.ACCESS_TOKEN] = enc(accessToken)
            prefs[Keys.REFRESH_TOKEN] = enc(refreshToken)
            prefs[Keys.USER_ID] = userId
            prefs[Keys.USER_PHONE] = phone
            prefs[Keys.USER_NICKNAME] = nickname ?: ""
            prefs[Keys.USER_AVATAR] = avatar ?: ""
            prefs[Keys.USER_ROLE] = role
            prefs[Keys.SCENE_TYPE] = sceneType
        }
    }

    /** 仅刷新令牌（token 续期后调用） */
    suspend fun saveTokens(accessToken: String, refreshToken: String) {
        context.authDataStore.edit { prefs ->
            prefs[Keys.ACCESS_TOKEN] = enc(accessToken)
            prefs[Keys.REFRESH_TOKEN] = enc(refreshToken)
        }
    }

    suspend fun getAccessToken(): String? =
        dec(context.authDataStore.data.first()[Keys.ACCESS_TOKEN])

    suspend fun getRefreshToken(): String? =
        dec(context.authDataStore.data.first()[Keys.REFRESH_TOKEN])

    suspend fun clear() {
        context.authDataStore.edit { it.clear() }
    }

    suspend fun isLoggedIn(): Boolean = getAccessToken() != null

    /** 登录态响应式流：存在 accessToken 即视为已登录；会话被 clear 时立即发出 false */
    fun isLoggedInFlow(): Flow<Boolean> =
        context.authDataStore.data.map { it[Keys.ACCESS_TOKEN] != null }

    fun currentUserFlow(): Flow<User?> = context.authDataStore.data.map { prefs ->
        val token = dec(prefs[Keys.ACCESS_TOKEN]) ?: return@map null
        User(
            id = prefs[Keys.USER_ID] ?: "",
            phone = prefs[Keys.USER_PHONE] ?: "",
            nickname = prefs[Keys.USER_NICKNAME]?.takeIf { it.isNotBlank() },
            avatar = prefs[Keys.USER_AVATAR]?.takeIf { it.isNotBlank() },
            role = runCatching { UserRole.valueOf(prefs[Keys.USER_ROLE] ?: "PARENT") }
                .getOrDefault(UserRole.PARENT),
            sceneType = runCatching { SceneType.valueOf(prefs[Keys.SCENE_TYPE] ?: "FAMILY") }
                .getOrDefault(SceneType.FAMILY),
            token = token
        )
    }

    /**
     * 加密令牌：成功返回 `ENC:` + Base64(密文)；Keystore 不可用（极少数老旧/不支持设备）
     * 时降级为明文并告警，保证登录功能不中断。
     */
    private fun enc(value: String): String = try {
        "ENC:" + keystore.encryptToBase64(value)
    } catch (e: Exception) {
        Log.w(TAG, "Keystore 加密失败，降级明文存储", e)
        value
    }

    /**
     * 解密令牌：null 直接返回 null；`ENC:` 前缀按密文解密；其余按旧版明文兼容返回。
     * 解密失败（密钥轮换/数据损坏）返回 null，由上层触发重新登录，避免泄漏损坏数据。
     */
    private fun dec(value: String?): String? {
        if (value == null) return null
        if (value.startsWith(ENC_PREFIX)) {
            return try {
                keystore.decryptFromBase64(value.removePrefix(ENC_PREFIX))
            } catch (e: Exception) {
                Log.w(TAG, "令牌解密失败，返回 null", e)
                null
            }
        }
        return value
    }

    companion object {
        private const val TAG = "TokenManager"
        private const val ENC_PREFIX = "ENC:"
    }
}
