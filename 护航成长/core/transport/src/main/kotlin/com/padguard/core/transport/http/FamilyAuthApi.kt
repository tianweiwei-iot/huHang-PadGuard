package com.padguard.core.transport.http

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * 家庭账号登录接口（被管控端复用家长端账号体系）。
 *
 * 路径落在家长端 `/v1/auth/...` 下，而非子端 `/api/v1/` 前缀，因此由配套的
 * [LoginHostInterceptor] 只改写 scheme/host/port、保留此处声明的相对路径，
 * 最终落到 `http://<host>:8090/v1/auth/login/password`。
 *
 * 响应复用子端统一的 [ApiEnvelope]，与服务端 `{"code":0,"message":"ok","data":{...}}` 结构一致。
 */
interface FamilyAuthApi {

    /** 手机号 + 密码登录，返回家庭账号令牌与基础资料（契约：家长端 `/v1/auth/login/password`）。 */
    @POST("v1/auth/login/password")
    suspend fun login(@Body req: FamilyLoginRequest): Response<ApiEnvelope<FamilyLoginData>>
}

@Serializable
data class FamilyLoginRequest(
    @SerialName("phone") val phone: String,
    @SerialName("password") val password: String
)

@Serializable
data class FamilyLoginData(
    @SerialName("token") val token: String = "",
    @SerialName("refreshToken") val refreshToken: String = "",
    @SerialName("user") val user: FamilyUserDto? = null
)

@Serializable
data class FamilyUserDto(
    @SerialName("id") val id: String = "",
    @SerialName("phone") val phone: String = "",
    @SerialName("nickname") val nickname: String? = null,
    @SerialName("role") val role: String = "",
    @SerialName("sceneType") val sceneType: String = ""
)
