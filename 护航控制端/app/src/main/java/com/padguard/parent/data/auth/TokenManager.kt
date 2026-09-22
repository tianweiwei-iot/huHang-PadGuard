package com.padguard.parent.data.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.padguard.domain.model.SceneType
import com.padguard.domain.model.User
import com.padguard.domain.model.UserRole
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
 * 与 Mock 阶段 LocalDataSource 的偏好存储解耦，使用独立的 DataStore 文件，避免相互污染。
 */
@Singleton
class TokenManager @Inject constructor(
    @ApplicationContext private val context: Context
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
            prefs[Keys.ACCESS_TOKEN] = accessToken
            prefs[Keys.REFRESH_TOKEN] = refreshToken
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
            prefs[Keys.ACCESS_TOKEN] = accessToken
            prefs[Keys.REFRESH_TOKEN] = refreshToken
        }
    }

    suspend fun getAccessToken(): String? =
        context.authDataStore.data.first()[Keys.ACCESS_TOKEN]

    suspend fun getRefreshToken(): String? =
        context.authDataStore.data.first()[Keys.REFRESH_TOKEN]

    suspend fun clear() {
        context.authDataStore.edit { it.clear() }
    }

    suspend fun isLoggedIn(): Boolean = getAccessToken() != null

    /** 登录态响应式流：存在 accessToken 即视为已登录；会话被 clear 时立即发出 false */
    fun isLoggedInFlow(): Flow<Boolean> =
        context.authDataStore.data.map { it[Keys.ACCESS_TOKEN] != null }

    fun currentUserFlow(): Flow<User?> = context.authDataStore.data.map { prefs ->
        val token = prefs[Keys.ACCESS_TOKEN] ?: return@map null
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
}
