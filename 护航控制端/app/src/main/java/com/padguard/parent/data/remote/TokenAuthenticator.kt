package com.padguard.parent.data.remote

import com.padguard.data.api.AuthApi
import com.padguard.parent.data.auth.TokenManager
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * 令牌自动续期（OkHttp Authenticator）。
 *
 * 当响应返回 401 时，使用 refreshToken 调用 /auth/refresh 获取新令牌并写入 [TokenManager]，
 * 随后用新令牌重试原请求。使用独立的无鉴权客户端（[AuthApi] 来自 base Retrofit）发起刷新，
 * 避免与带鉴权客户端形成循环依赖。若刷新失败或无 refreshToken，则清空登录态并返回 null（放弃重试）。
 */
class TokenAuthenticator(
    private val tokenManager: TokenManager,
    private val authApi: AuthApi
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        val refreshToken = runBlocking { tokenManager.getRefreshToken() } ?: return null

        val refreshResp = runBlocking { authApi.refreshToken(refreshToken) }
        if (!refreshResp.isSuccessful) {
            runBlocking { tokenManager.clear() }
            return null
        }
        val body = refreshResp.body() ?: return null
        if (body.code != 0 || body.data == null) {
            runBlocking { tokenManager.clear() }
            return null
        }

        val newAccess = body.data.token
        val newRefresh = body.data.refreshToken
        runBlocking { tokenManager.saveTokens(newAccess, newRefresh) }

        return response.request.newBuilder()
            .header("Authorization", "Bearer $newAccess")
            .build()
    }
}
