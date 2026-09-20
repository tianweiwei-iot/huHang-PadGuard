package com.padguard.parent.data.remote

import com.padguard.parent.data.auth.TokenManager
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 自动附加鉴权头：将 [TokenManager] 中的 JWT 以 `Authorization: Bearer <token>` 形式注入每个请求。
 *
 * 采用主流 OkHttp 拦截器模式；令牌读取为内存型轻操作，使用 runBlocking 不会阻塞主线程（调用在 IO 线程）。
 */
class AuthInterceptor(
    private val tokenManager: TokenManager
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val token = runBlocking { tokenManager.getAccessToken() }
        val builder = original.newBuilder()
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
        token?.let { builder.header("Authorization", "Bearer $it") }
        return chain.proceed(builder.build())
    }
}
