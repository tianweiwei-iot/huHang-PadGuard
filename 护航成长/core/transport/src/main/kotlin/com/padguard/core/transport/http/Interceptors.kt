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

    private fun rewrite(original: HttpUrl, target: HttpUrl): HttpUrl = rewriteUrl(original, target)

    companion object {
        /** 与 [com.padguard.core.transport.di.TransportModule] 里的占位 baseUrl 路径保持一致 */
        const val PLACEHOLDER_PREFIX = "/placeholder"

        /**
         * 服务端文件接口前缀。
         *
         * 文件接口挂在服务端**根路径**（`/v1/files/{id}`），而孩子端 baseUrl 形如
         * `http://host:8090/api/v1/`——两者路径前缀完全不同，绝不能按 baseUrl 拼接。
         */
        private const val SERVER_FILE_PREFIX = "/v1/files/"

        /**
         * 地址重写（纯函数，便于单测直接覆盖）。
         *
         * ## 三类请求，三种处理
         * 1. **Retrofit 占位地址**（`http://localhost/placeholder/...`）：接口相对路径拼到当前
         *    baseUrl 下，这是本拦截器的原始职责（运行时切接入点）。
         * 2. **服务端文件接口**（`/v1/files/{id}`）：只对齐 origin（scheme/host/port），
         *    **path 与签名 query 原样保留**。
         * 3. **其它绝对地址**（对象存储 / CDN）：不归本服务端管，原样放行，绝不改写——
         *    否则会把外部素材地址改写成自家域名，必然 404。
         *
         * ## 为什么必须区分 1 和 2（真实故障）
         * 旧实现把所有请求都按 baseUrl 拼前缀：baseUrl 带 `/api/v1` 时，媒体下载地址会被改写成
         * `/api/v1/v1/files/{id}` → 404 → 孩子端「视频加载失败」「图片加载失败」。
         * 同源故障此前只在 APK 下载链路上被绕过（`AppInstaller` 另建专用 client 规避），
         * 而 `MediaDownloader` 用的是共享 client，于是家长发布的视频/图片必挂。
         */
        internal fun rewriteUrl(original: HttpUrl, target: HttpUrl): HttpUrl {
            // ① Retrofit 占位路径 → 拼到当前 baseUrl
            if (original.encodedPath.startsWith(PLACEHOLDER_PREFIX)) {
                // 剥掉占位前缀，只保留接口自身的相对路径
                val relative = original.encodedPath.removePrefix(PLACEHOLDER_PREFIX).trimStart('/')
                return target.newBuilder()
                    .encodedPath(target.encodedPath.trimEnd('/') + "/" + relative)
                    .encodedQuery(original.encodedQuery)
                    .build()
            }
            // ③ 非本服务端文件接口的绝对地址（CDN / 对象存储）→ 放行
            if (!original.encodedPath.startsWith(SERVER_FILE_PREFIX)) return original
            // ② 文件接口 → 只换 origin，path 与 exp/sig 查询串必须一字不改
            //    （剥掉签名会让开启匿名强制签名的部署直接 403）
            if (original.host == target.host &&
                original.port == target.port &&
                original.scheme == target.scheme
            ) {
                return original
            }
            return original.newBuilder()
                .scheme(target.scheme)
                .host(target.host)
                .port(target.port)
                .build()
        }
    }
}

/**
 * 弱网重试拦截器。
 *
 * 校园 WiFi 常见"连上但不通"的半死状态，OkHttp 默认只对连接失败做一次重试。
 * 这里对 IOException（请求未到达服务端的连接级失败）做有限次指数退避，
 * 把偶发抖动挡在业务层之外，避免心跳因一次抖动就被判为离线。
 *
 * 重要（防双发红线）：**只在连接级失败（IOException）上重试**。
 * 收到响应后若服务端返回 5xx，仅对读请求（GET/HEAD，天然幂等）重试；
 * 写请求（POST/PUT/DELETE/PATCH）一律原样返回，绝不自动重发——
 * 部分上行接口（bind / requestDelayUsageSession / uploadMedia / startUsageSession）
 * 服务端并非幂等，重发会重复落单。写请求的可靠补偿由磁盘级 at-least-once
 * 重试（AckOutbox / 日志批量上传）按真实幂等键负责，不会重复落单。
 */
@Singleton
class RetryInterceptor @Inject constructor() : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // 写请求（POST/PUT/DELETE/PATCH）绝不在 5xx 上自动重发：服务端未必幂等
        // （bind / requestDelayUsageSession / uploadMedia / startUsageSession 等会重复落单），
        // 重发即双发。读请求（GET/HEAD）5xx 可安全重试。
        // 写请求的可靠补偿交给磁盘级 at-least-once 重试（AckOutbox / 日志批量），
        // 它们带真正的幂等键，不会重复落单。
        val isSafeRead = request.method == "GET" || request.method == "HEAD"
        var lastError: java.io.IOException? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val response = chain.proceed(request)
                // 仅连接级失败才安全重试；5xx 仅在读请求上重试，写请求原样返回交由上层处理
                if (response.code < 500 || isSafeRead || attempt == MAX_ATTEMPTS - 1) return response
                response.close()
            } catch (e: java.io.IOException) {
                // IOException = 请求未到达服务端（连接失败/超时），重发安全
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
