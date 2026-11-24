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
 * 安全加固：accessToken / refreshToken 优先用 Android Keystore(AES/GCM) 加密落盘
 * （加密值以 `ENC:` 前缀标记）；读取时带 `ENC:` 前缀则解密，否则按旧版明文兼容返回。
 *
 * **登录态可用性红线（2026-11-07「自动退出」修复）**：Keystore 不可用时**绝不能**把令牌
 * 只留在内存 / 写成「内存占位」。此前 `tryEncrypt` 失败会写 `__MEM_ONLY__` 并仅缓存到进程内存，
 * 本次进程内看似正常，一旦进程被杀或冷启动，`currentUserFlow()` 立刻发 null →
 * 导航 `user == null` 整栈退回登录页，且所有请求都不带 Authorization 头（服务端 401 风暴）。
 * 现场证据：当天 250 次家长端 401 全是「缺少有效的登录令牌」，且全天没有一次刷新请求。
 * 因此降级路径改为**明文落盘**（仅在加密确实抛异常时发生）：Keystore 正常时依然是 `ENC:` 密文，
 * 降级时优先保证「重启不丢登录态」，并在日志中显式告警。这是内网私有化部署下的可用性取舍，
 * 不能用「只存内存」这种看似更安全、实为静默登出的方案。
 *
 * 与其它偏好存储解耦，使用独立的 DataStore 文件，避免相互污染。
 * （早期注释提到的 Mock 实现 LocalDataSource 已于 R-P3-4 删除。）
 */
@Singleton
class TokenManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keystore: KeystoreManager
) {
    /** Keystore 不可用时的内存令牌缓存：仅进程存活期有效，重启即失效（R-P1-2）。 */
    @Volatile private var memAccess: String? = null
    @Volatile private var memRefresh: String? = null

    /**
     * 一次性原因提示：登录页展示后调用 [consumeSessionNotice] 清空，
     * 避免家长下次自己点"退出登录"时又看到上次的过期提示。
     *
     * 只放内存不落盘：进程被杀后家长本来就要正常登录，提示也就没有意义；
     * 落盘反而会让它在几天后莫名弹出来。
     *
     * 声明在其它属性之前：Kotlin 的属性初始化按书写顺序执行，
     * 后面的流都依赖它，放最后会在类初始化期拿到 null。
     */
    private val _sessionNotice = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val sessionNotice: Flow<String?> = _sessionNotice

    fun consumeSessionNotice() {
        _sessionNotice.value = null
    }

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
        val encAccess = tryEncrypt(accessToken)
        val encRefresh = tryEncrypt(refreshToken)
        context.authDataStore.edit { prefs ->
            // 兜底只能是明文令牌，绝不能是「无令牌」占位：后者等于静默登出。
            prefs[Keys.ACCESS_TOKEN] = encAccess.first ?: accessToken
            prefs[Keys.REFRESH_TOKEN] = encRefresh.first ?: refreshToken
            prefs[Keys.USER_ID] = userId
            prefs[Keys.USER_PHONE] = phone
            prefs[Keys.USER_NICKNAME] = nickname ?: ""
            prefs[Keys.USER_AVATAR] = avatar ?: ""
            prefs[Keys.USER_ROLE] = role
            prefs[Keys.SCENE_TYPE] = sceneType
        }
        memAccess = encAccess.second
        memRefresh = encRefresh.second
    }

    /** 仅刷新令牌（token 续期后调用；401 自动续期也会走到这里） */
    suspend fun saveTokens(accessToken: String, refreshToken: String) {
        val encAccess = tryEncrypt(accessToken)
        val encRefresh = tryEncrypt(refreshToken)
        context.authDataStore.edit { prefs ->
            // 每约 2 小时 accessToken 过期就会走一次本方法；此处一次瞬时加密失败若写成占位符，
            // 会把原本好好的持久登录态抹掉，且要等下次冷启动才暴露，极难复现。
            prefs[Keys.ACCESS_TOKEN] = encAccess.first ?: accessToken
            prefs[Keys.REFRESH_TOKEN] = encRefresh.first ?: refreshToken
        }
        memAccess = encAccess.second
        memRefresh = encRefresh.second
    }

    suspend fun getAccessToken(): String? =
        memAccess ?: decryptStored(context.authDataStore.data.first()[Keys.ACCESS_TOKEN])

    suspend fun getRefreshToken(): String? =
        memRefresh ?: decryptStored(context.authDataStore.data.first()[Keys.REFRESH_TOKEN])

    suspend fun clear() {
        clearWithReason(null)
    }

    /**
     * 登录态被**非用户主动**清空时，带一句原因回登录页。
     *
     * ## 为什么需要它
     * `clear()` 会让导航层把整栈退回登录页（`user == null`）。此前这条路径完全静默：
     * 家长可能只是另一台手机改了密码（服务端 `token_version+1`，本机 refresh 立刻 401），
     * 一抬头发现自己被扔回登录页，没有任何解释 —— 第一反应是"App 出 bug 了"
     * 或者"账号被盗了"，而不是"我改过密码"。
     *
     * 主动退出登录传 `null`（家长知道自己在干什么，再提示一次是噪音）。
     */
    suspend fun clearWithReason(reason: String?) {
        memAccess = null
        memRefresh = null
        context.authDataStore.edit { it.clear() }
        _sessionNotice.value = reason
    }

    suspend fun isLoggedIn(): Boolean = getAccessToken() != null

    /** 登录态响应式流：存在有效 accessToken 即视为已登录；降级态（仅内存）进程重启后即失效。 */
    fun isLoggedInFlow(): Flow<Boolean> =
        context.authDataStore.data.map { it[Keys.ACCESS_TOKEN] != null && it[Keys.ACCESS_TOKEN] != MEM_ONLY }

    fun currentUserFlow(): Flow<User?> = context.authDataStore.data.map { prefs ->
        val token = memAccess ?: decryptStored(prefs[Keys.ACCESS_TOKEN]) ?: return@map null
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
     * 加密令牌：成功返回 (ENC 密文, null)；Keystore 抛异常时返回 (明文, 明文) ——
     * **明文同时落盘并缓存到内存**，保证进程重启后仍能免登。
     *
     * 绝不能再返回 (null, 明文)：那会让 [saveLogin] / [saveTokens] 把 DataStore 里的有效
     * `ENC:` 密文覆盖成 `__MEM_ONLY__`，本次进程无感、下次冷启动静默登出（本次故障根因）。
     */
    private fun tryEncrypt(value: String): Pair<String?, String?> = try {
        ("ENC:" + keystore.encryptToBase64(value)) to null
    } catch (e: Exception) {
        Log.w(TAG, "Keystore 加密失败，降级明文落盘以保持登录态（仅加密异常时发生）", e)
        value to value
    }

    /**
     * 解密存储值：null / MEM_ONLY 返回 null；`ENC:` 前缀按密文解密；其余按旧版明文兼容返回。
     * 解密失败（密钥轮换/数据损坏）返回 null，由上层触发重新登录，避免泄漏损坏数据。
     */
    private fun decryptStored(value: String?): String? {
        if (value == null || value == MEM_ONLY) return null
        return if (value.startsWith(ENC_PREFIX)) {
            try {
                keystore.decryptFromBase64(value.removePrefix(ENC_PREFIX))
            } catch (e: Exception) {
                Log.w(TAG, "令牌解密失败，返回 null", e)
                null
            }
        } else value
    }

    companion object {
        private const val TAG = "TokenManager"
        private const val ENC_PREFIX = "ENC:"
        /** 降级占位：Keystore 不可用时 DataStore 写此标记而非明文，读取即视为无令牌。 */
        private const val MEM_ONLY = "__MEM_ONLY__"
    }
}
