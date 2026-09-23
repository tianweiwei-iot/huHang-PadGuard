package com.padguard.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.padguard.core.data.di.AuthDataStore
import com.padguard.core.data.model.BindResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 设备绑定凭据仓库。
 *
 * [hmacSecret] 是指令签名校验的核心密钥，仅存于本地，永不参与任何上行请求。
 */
@Singleton
class AuthRepository @Inject constructor(
    @AuthDataStore private val store: DataStore<Preferences>
) {

    private object Keys {
        val DEVICE_ID = stringPreferencesKey("device_id")
        val DEVICE_TOKEN = stringPreferencesKey("device_token")
        val MQTT_USERNAME = stringPreferencesKey("mqtt_username")
        val MQTT_PASSWORD = stringPreferencesKey("mqtt_password")
        val HMAC_SECRET = stringPreferencesKey("hmac_secret")
        val TOKEN_EXPIRES_AT = longPreferencesKey("token_expires_at")
        val BOUND_AT = longPreferencesKey("bound_at")
        val DEVICE_SN = stringPreferencesKey("device_sn")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val STUDENT_NAME = stringPreferencesKey("student_name")
        val TENANT_ID = stringPreferencesKey("tenant_id")
        val AGREEMENT_VERSION = stringPreferencesKey("agreement_version")

        // === 被管控端登录态（复用家长家庭账号） ===
        val CHILD_PHONE = stringPreferencesKey("child_phone")
        val CHILD_TOKEN = stringPreferencesKey("child_token")
        val CHILD_USER_ID = stringPreferencesKey("child_user_id")
        val CHILD_LOGGED_IN = booleanPreferencesKey("child_logged_in")
        // === 账号密码记录（家长家庭账号，用于免重复输入 / 掉线后自动重登）===
        // 安全说明：与 deviceToken / hmacSecret 同库存放（DataStore，应用私有目录、
        // 非 root 设备不可读）。仅当用户在登录页勾选「记住账号密码」时才会写入。
        val CHILD_PASSWORD = stringPreferencesKey("child_password")
        val CHILD_REMEMBER = booleanPreferencesKey("child_remember")

        // === 权限引导完成态（初次打开一次性授予，后续登录不再重复） ===
        val PERMISSIONS_DONE = booleanPreferencesKey("permissions_done")
    }

    /** 当前生效的授权协议版本（说明书 §5.4 / §4.6）。协议正文迭代时升版即可。 */
    val deviceId: Flow<String> = store.data.catch { emit(emptyPreferences()) }
        .map { it[Keys.DEVICE_ID].orEmpty() }

    val isBound: Flow<Boolean> = deviceId.map { it.isNotBlank() }

    /** 已同意的协议版本；为空表示尚未同意（受限预览模式）。 */
    val agreementVersion: Flow<String> = store.data.catch { emit(emptyPreferences()) }
        .map { it[Keys.AGREEMENT_VERSION].orEmpty() }

    /** 是否已同意当前版本协议 —— 入口门禁据此决定是否弹授权弹窗。 */
    val isAgreementAccepted: Flow<Boolean> =
        agreementVersion.map { it == CURRENT_AGREEMENT_VERSION }

    /** 被管控端是否已用家庭账号登录（驱动入口分流：未登录→登录页）。 */
    val isChildLoggedIn: Flow<Boolean> = store.data.catch { emit(emptyPreferences()) }
        .map { it[Keys.CHILD_LOGGED_IN] == true }

    /** 权限引导是否已完成（初次打开一次性授予，后续登录不再重复）。 */
    val isPermissionsDone: Flow<Boolean> = store.data.catch { emit(emptyPreferences()) }
        .map { it[Keys.PERMISSIONS_DONE] == true }

    /** 已登录的家庭账号手机号（仅用于展示）。 */
    val childPhone: Flow<String> = store.data.catch { emit(emptyPreferences()) }
        .map { it[Keys.CHILD_PHONE].orEmpty() }

    /** 已登录的家庭账号用户ID。 */
    val childUserId: Flow<String> = store.data.catch { emit(emptyPreferences()) }
        .map { it[Keys.CHILD_USER_ID].orEmpty() }

    val studentName: Flow<String> = store.data.catch { emit(emptyPreferences()) }
        .map { it[Keys.STUDENT_NAME].orEmpty() }

    val deviceSn: Flow<String> = store.data.catch { emit(emptyPreferences()) }
        .map { it[Keys.DEVICE_SN].orEmpty() }

    /** 自定义设备名（家长重命名下发 / 孩子端本地修改都会写入此处，驱动「我的」页展示） */
    val deviceName: Flow<String> = store.data.catch { emit(emptyPreferences()) }
        .map { it[Keys.DEVICE_NAME].orEmpty() }

    suspend fun getDeviceId(): String = deviceId.first()

    suspend fun getDeviceToken(): String =
        store.data.catch { emit(emptyPreferences()) }.first()[Keys.DEVICE_TOKEN].orEmpty()

    suspend fun getMqttUsername(): String =
        store.data.catch { emit(emptyPreferences()) }.first()[Keys.MQTT_USERNAME].orEmpty()

    suspend fun getMqttPassword(): String =
        store.data.catch { emit(emptyPreferences()) }.first()[Keys.MQTT_PASSWORD].orEmpty()

    suspend fun getHmacSecret(): String =
        store.data.catch { emit(emptyPreferences()) }.first()[Keys.HMAC_SECRET].orEmpty()

    suspend fun getTenantId(): String =
        store.data.catch { emit(emptyPreferences()) }.first()[Keys.TENANT_ID].orEmpty()

    suspend fun getDeviceSn(): String =
        store.data.catch { emit(emptyPreferences()) }.first()[Keys.DEVICE_SN].orEmpty()

    suspend fun getStudentName(): String =
        store.data.catch { emit(emptyPreferences()) }.first()[Keys.STUDENT_NAME].orEmpty()

    /** 持久化自定义设备名（家长下发或孩子本地修改共用） */
    suspend fun saveDeviceName(name: String) {
        store.edit { it[Keys.DEVICE_NAME] = name }
    }

    /** 保存绑定结果。绑定成功后终端即进入受控态。 */
    suspend fun saveBindResult(result: BindResult, deviceSn: String) {
        store.edit { prefs ->
            prefs[Keys.DEVICE_ID] = result.deviceId
            prefs[Keys.DEVICE_TOKEN] = result.deviceToken
            prefs[Keys.MQTT_USERNAME] = result.mqttUsername
            prefs[Keys.MQTT_PASSWORD] = result.mqttPassword
            prefs[Keys.HMAC_SECRET] = result.hmacSecret
            prefs[Keys.TOKEN_EXPIRES_AT] = result.expiresAt
            prefs[Keys.BOUND_AT] = System.currentTimeMillis()
            prefs[Keys.DEVICE_SN] = deviceSn
        }
    }

    suspend fun saveTenantId(tenantId: String) {
        store.edit { it[Keys.TENANT_ID] = tenantId }
    }

    suspend fun saveStudentName(name: String) {
        store.edit { it[Keys.STUDENT_NAME] = name }
    }

    /** 持久化已同意的协议版本（同意授权后由 [PermissionGranter] 调用）。 */
    suspend fun saveAgreement(version: String = CURRENT_AGREEMENT_VERSION) {
        store.edit { it[Keys.AGREEMENT_VERSION] = version }
    }

    /** 保存家庭账号登录态（账号/令牌/用户ID），置 [Keys.CHILD_LOGGED_IN]=true。 */
    suspend fun saveChildLogin(phone: String, token: String, userId: String) {
        store.edit {
            it[Keys.CHILD_PHONE] = phone
            it[Keys.CHILD_TOKEN] = token
            it[Keys.CHILD_USER_ID] = userId
            it[Keys.CHILD_LOGGED_IN] = true
        }
    }

    /**
     * 记录账号密码（登录页「记住账号密码」勾选时调用）。
     *
     * 安全说明：密码与 deviceToken / hmacSecret 同库存放于应用私有 DataStore
     * （非 root 设备其他应用不可读），且**仅在用户显式勾选后**才写入，
     * 用户随时可在「我的 → 账号与密码」一键清除。
     */
    suspend fun saveChildCredentials(phone: String, password: String) {
        store.edit {
            it[Keys.CHILD_PHONE] = phone
            it[Keys.CHILD_PASSWORD] = password
            it[Keys.CHILD_REMEMBER] = true
        }
    }

    /** 已记录的账号 + 密码（未记录时均为空串）。 */
    val rememberedCredentials: Flow<Pair<String, String>> =
        store.data.catch { emit(emptyPreferences()) }
            .map { it[Keys.CHILD_PHONE].orEmpty() to it[Keys.CHILD_PASSWORD].orEmpty() }

    /** 是否开启了「记住账号密码」。 */
    val isRemembered: Flow<Boolean> = store.data.catch { emit(emptyPreferences()) }
        .map { it[Keys.CHILD_REMEMBER] == true }

    /** 清除已记录的账号密码（只清记录，不退出登录、不影响已绑定设备）。 */
    suspend fun clearRememberedCredentials() {
        store.edit {
            it.remove(Keys.CHILD_PASSWORD)
            it[Keys.CHILD_REMEMBER] = false
        }
    }

    /** 标记权限引导已完成，后续登录不再重复申请。 */
    suspend fun savePermissionsDone() {
        store.edit { it[Keys.PERMISSIONS_DONE] = true }
    }

    /**
     * 退出家庭账号登录。
     * 仅清登录态，保留已绑定设备与权限完成态——下次进入直接登录即可，
     * 不再重复走权限引导（[isPermissionsDone] 独立持久化）。
     */
    suspend fun childLogout() {
        store.edit {
            it.remove(Keys.CHILD_PHONE)
            it.remove(Keys.CHILD_TOKEN)
            it.remove(Keys.CHILD_USER_ID)
            it[Keys.CHILD_LOGGED_IN] = false
        }
    }

    /**
     * 解绑清理。
     * 注意：终端**无自主解绑权限**，本方法仅在收到服务端/管理员授权的解绑指令时调用。
     */
    suspend fun clear() {
        try {
            store.edit { it.clear() }
        } catch (e: IOException) {
            // DataStore 写入失败时重试一次，仍失败则记录日志交由上层处理
            store.edit { it.clear() }
        }
    }

    companion object {
        /** 当前生效的授权协议版本（说明书 §5.4 / §4.6）。协议正文迭代时升版即可。 */
        const val CURRENT_AGREEMENT_VERSION = "1.0.0"
    }
}
