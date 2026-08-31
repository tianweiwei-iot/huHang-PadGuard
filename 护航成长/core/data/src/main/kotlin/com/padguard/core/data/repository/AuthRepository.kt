package com.padguard.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
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
        val STUDENT_NAME = stringPreferencesKey("student_name")
        val TENANT_ID = stringPreferencesKey("tenant_id")
    }

    val deviceId: Flow<String> = store.data.catch { emit(emptyPreferences()) }
        .map { it[Keys.DEVICE_ID].orEmpty() }

    val isBound: Flow<Boolean> = deviceId.map { it.isNotBlank() }

    val studentName: Flow<String> = store.data.catch { emit(emptyPreferences()) }
        .map { it[Keys.STUDENT_NAME].orEmpty() }

    val deviceSn: Flow<String> = store.data.catch { emit(emptyPreferences()) }
        .map { it[Keys.DEVICE_SN].orEmpty() }

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
}
