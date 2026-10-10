package com.padguard.parent.data.remote

import com.padguard.data.api.AuthApi
import com.padguard.parent.data.auth.TokenManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * 令牌自动续期（OkHttp Authenticator）（P1' 全量重写）。
 *
 * 旧实现的三个问题，重写后逐一收口：
 *
 * 1. **无单飞（最严重）**：首页并发请求（设备列表 + 今日用量 + 消息数…）在 token
 *    过期时会**同时**收到 401，各自触发一次 /auth/refresh。refresh token 若是
 *    一次性的（服务端轮换），先返回的那个会把后返回的作废 —— 用户被随机登出。
 *    现在用 [refreshMutex] 收敛为单飞：第一个进锁的负责刷新，其余等锁后
 *    发现 token 已变，直接拿最新 token 重试，不再重复刷新。
 *
 * 2. **刷新失败一律清登录态**：旧代码只要 `!isSuccessful` 就 clear()，
 *    服务端 5xx 抖动 / 网络闪断也会把用户登出 —— 这是"服务端打了个喷嚏，
 *    客户端把用户扫地出门"。现在只有**凭据本身失效**（HTTP 401/403，
 *    或业务码明确拒绝）才清登录态；5xx / 网络异常只放弃本次重试，
 *    登录态保留，下一个请求到来时会再次尝试刷新。
 *
 * 3. **无重试上限**：authenticate 返回非 null OkHttp 就会用新请求重试，
 *    重试的响应仍可能触发 authenticate。靠 [response.priorResponse] 判断
 *    "这条响应已经是重试的产物" —— 是则直接放弃，杜绝循环。
 */
class TokenAuthenticator(
    private val tokenManager: TokenManager,
    private val authApi: AuthApi
) : Authenticator {

    /** 401 风暴收敛：同一时刻只允许一个线程真正发起 refresh */
    private val refreshMutex = Mutex()

    override fun authenticate(route: Route?, response: Response): Request? {
        // 该响应已经是"用新 token 重试过一次"的产物 → 新 token 也无效，放弃。
        if (response.priorResponse != null) return null

        // 原请求携带的（已过期的）token，用于判断"锁内 token 是否已被别人刷新"
        val staleToken = response.request.header("Authorization")
            ?.removePrefix("Bearer ")
            ?.takeIf { it.isNotBlank() }

        return runBlocking {
            refreshMutex.withLock {
                // 单飞关键段：等锁期间可能已有并发请求完成刷新。
                // tokenManager 里的 token 与本次请求携带的不一致 → 已刷新，直接重试。
                val current = tokenManager.getAccessToken()
                if (!current.isNullOrBlank() && current != staleToken) {
                    return@withLock requestWithToken(response.request, current)
                }

                val refreshToken = tokenManager.getRefreshToken()
                    ?: return@withLock null

                val refreshResp = try {
                    authApi.refreshToken(refreshToken)
                } catch (e: Exception) {
                    // 网络异常 / 反序列化异常 ≠ 凭据失效：保留登录态，放弃本次重试。
                    // 下一个请求再 401 时会重新尝试刷新。
                    null
                } ?: return@withLock null

                if (!refreshResp.isSuccessful) {
                    // 只有 401/403 才说明 refresh token 确认失效（服务端已拒绝凭据本身）；
                    // 5xx 等是服务端暂时性问题，清登录态会造成"抖动即登出"。
                    if (refreshResp.code() == 401 || refreshResp.code() == 403) {
                        tokenManager.clear()
                    }
                    return@withLock null
                }

                val body = refreshResp.body()
                if (body == null || body.code != 0 || body.data == null) {
                    // HTTP 层成功但业务层拒绝：refresh token 已被服务端作废，清登录态。
                    tokenManager.clear()
                    return@withLock null
                }

                tokenManager.saveTokens(body.data.token, body.data.refreshToken)
                requestWithToken(response.request, body.data.token)
            }
        }
    }

    private fun requestWithToken(request: Request, token: String): Request =
        request.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()
}
