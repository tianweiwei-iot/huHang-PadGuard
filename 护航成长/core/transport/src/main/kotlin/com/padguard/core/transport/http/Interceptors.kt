package com.padguard.core.transport.http

import com.padguard.core.common.Logger
import com.padguard.core.common.TimeProvider
import com.padguard.core.transport.TransportSettings
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 统一请求头拦截器（契约 §5）。
 *
 * ```
 * Authorization: Bearer <deviceToken>
 * X-Device-Id:   <deviceId>
 * X-Request-Id:  <uuid>
 * X-Timestamp:   <millis>
 * ```
 *
 * [TimeProvider.now] 而非 `System.currentTimeMillis()`：
 * 学生把系统时间改到明天后，未校准的时间戳会让服务端返回 40901，
 * 导致所有上报被拒 —— 反而成了绕过管控的手段。用校准后的时间可规避。
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val credentials: CredentialStore,
    private val timeProvider: TimeProvider
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()
            .header("X-Request-Id", UUID.randomUUID().toString())
            .header("X-Timestamp", timeProvider.now().toString())
            .header("Accept", "application/json")

        // 绑定接口在拿到令牌之前调用，不能带（也没有）Authorization
        val isBindCall = original.url.encodedPath.endsWith("/device/bind")
        if (!isBindCall) {
            credentials.deviceToken.takeIf { it.isNotBlank() }?.let {
                builder.header("Authorization", "Bearer $it")
            }
            credentials.deviceId.takeIf { it.isNotBlank() }?.let {
                builder.header("X-Device-Id", it)
            }
        }
        return chain.proceed(builder.build())
    }
}

/**
 * 动态 BaseUrl 拦截器。
 *
 * Retrofit 的 baseUrl 是构建期固定的，但被管控端有两个必须运行时改地址的场景：
 * 1. 服务端未就绪阶段在 Mock / 联调机 / 预发环境之间来回切；
 * 2. 私有化部署时接入点由绑定响应下发，一台 APK 要能对接不同学校的服务器。
 *
 * 这里采用 Retrofit 官方推荐的 HostSelectionInterceptor 模式重写 scheme/host/port，
 * 保留 Retrofit 注解里声明的相对路径，避免每个接口都写全量 URL。
 */
@Singleton
class HostSelectionInterceptor @Inject constructor(
    private val settings: TransportSettings
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val target = settings.baseUrl.value.toHttpUrlOrNull()
            ?: return chain.proceed(request)

        val rewritten = rewrite(request.url, target)
        return chain.proceed(request.newBuilder().url(rewritten).build())
    }

    private fun rewrite(original: HttpUrl, target: HttpUrl): HttpUrl {
        if (original.host == target.host &&
            original.port == target.port &&
            original.scheme == target.scheme &&
            original.encodedPath.startsWith(target.encodedPath)
        ) {
            return original
        }
        // 剥掉旧 baseUrl 的路径前缀，只保留接口自身的相对路径
        val relative = original.encodedPath.removePrefix(PLACEHOLDER_PREFIX).trimStart('/')
        return target.newBuilder()
            .encodedPath(target.encodedPath.trimEnd('/') + "/" + relative)
            .encodedQuery(original.encodedQuery)
            .build()
    }

    companion object {
        /** 与 [com.padguard.core.transport.di.TransportModule] 里的占位 baseUrl 路径保持一致 */
        const val PLACEHOLDER_PREFIX = "/placeholder"
    }
}

/**
 * 弱网重试拦截器。
 *
 * 校园 WiFi 常见"连上但不通"的半死状态，OkHttp 默认只对连接失败做一次重试。
 * 这里对 IOException 做有限次指数退避，把偶发抖动挡在业务层之外，
 * 避免心跳因一次抖动就被判为离线。
 *
 * 注意：只重试幂等安全的请求 —— 所有上行接口都带 logId/eventId/msgId 幂等键，
 * 服务端按契约 §5.4 去重，因此 POST 重试同样安全。
 */
@Singleton
class RetryInterceptor @Inject constructor() : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var lastError: java.io.IOException? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val response = chain.proceed(chain.request())
                if (response.code < 500 || attempt == MAX_ATTEMPTS - 1) return response
                response.close()
            } catch (e: java.io.IOException) {
                lastError = e
                if (attempt == MAX_ATTEMPTS - 1) throw e
            }
            val backoff = BASE_BACKOFF_MS shl attempt
            Logger.v(TAG) { "retry #${attempt + 1} after ${backoff}ms" }
            try {
                Thread.sleep(backoff)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                throw lastError ?: java.io.IOException("interrupted while retrying", ie)
            }
        }
        throw lastError ?: java.io.IOException("request failed after $MAX_ATTEMPTS attempts")
    }

    companion object {
        private const val TAG = "RetryInterceptor"
        private const val MAX_ATTEMPTS = 3
        private const val BASE_BACKOFF_MS = 800L
    }
}
