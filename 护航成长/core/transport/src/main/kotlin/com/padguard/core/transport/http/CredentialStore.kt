package com.padguard.core.transport.http

import com.padguard.core.data.repository.AuthRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 凭据内存快照。
 *
 * 为什么需要这层：OkHttp 拦截器是同步的，而凭据存在 DataStore（挂起 API）里。
 * 若在拦截器里 `runBlocking` 读 DataStore，会在弱网重试叠加时把 OkHttp 的
 * dispatcher 线程逐个堵死，最终表现为"心跳不发了但服务没死"，
 * 这是最难排查的一类假在线。
 *
 * 因此改为：每次发起请求前由挂起函数刷新快照，拦截器只读 volatile 字段。
 *
 * [hmacSecret] 只用于本地校验指令签名，**不会写入任何请求头或请求体**。
 */
@Singleton
class CredentialStore @Inject constructor(
    private val authRepository: AuthRepository
) {

    @Volatile
    var deviceId: String = ""
        private set

    @Volatile
    var deviceToken: String = ""
        private set

    @Volatile
    var hmacSecret: String = ""
        private set

    @Volatile
    var mqttUsername: String = ""
        private set

    @Volatile
    var mqttPassword: String = ""
        private set

    @Volatile
    var tenantId: String = DEFAULT_TENANT
        private set

    val isBound: Boolean get() = deviceId.isNotBlank() && deviceToken.isNotBlank()

    /** 从持久层刷新快照。绑定成功、开机、令牌刷新后都应调用。 */
    suspend fun refresh() {
        deviceId = authRepository.getDeviceId()
        deviceToken = authRepository.getDeviceToken()
        hmacSecret = authRepository.getHmacSecret()
        mqttUsername = authRepository.getMqttUsername()
        mqttPassword = authRepository.getMqttPassword()
        tenantId = authRepository.getTenantId().ifBlank { DEFAULT_TENANT }
    }

    fun clear() {
        deviceId = ""
        deviceToken = ""
        hmacSecret = ""
        mqttUsername = ""
        mqttPassword = ""
        tenantId = DEFAULT_TENANT
    }

    companion object {
        /** 契约 §9.2 待服务端确认租户隔离方案，单租户部署时用此占位 */
        const val DEFAULT_TENANT = "default"
    }
}
