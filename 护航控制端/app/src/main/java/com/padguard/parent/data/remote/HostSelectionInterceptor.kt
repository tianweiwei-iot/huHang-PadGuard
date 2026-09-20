package com.padguard.parent.data.remote

import com.padguard.parent.di.ServerPrefs
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 动态 BaseUrl 拦截器（Retrofit 官方推荐的 HostSelectionInterceptor 模式）。
 *
 * Retrofit 的 baseUrl 是构建期固定的，这里用占位地址 `http://placeholder.padguard/placeholder/`
 * 占位，每次请求时把 scheme/host/port 改写成 [ServerPrefs] 里当前生效的地址，
 * 保留接口注解里声明的相对路径（如 `auth/login/password`、`devices`）。
 *
 * 因为读的是 [ServerPrefs.baseUrl] 这个 StateFlow 的实时值，
 * 用户在登录页改完地址后下一次请求立即生效，无需重启 App。
 */
@Singleton
class HostSelectionInterceptor @Inject constructor(
    private val serverPrefs: ServerPrefs
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val target = serverPrefs.getBaseUrl().toHttpUrlOrNull()
            ?: return chain.proceed(request)

        val rewritten = rewrite(request.url, target)
        return chain.proceed(request.newBuilder().url(rewritten).build())
    }

    private fun rewrite(original: HttpUrl, target: HttpUrl): HttpUrl {
        // 剥掉占位前缀，只保留接口自身的相对路径
        val relative = original.encodedPath.removePrefix(PLACEHOLDER_PREFIX).trimStart('/')
        return target.newBuilder()
            .encodedPath(target.encodedPath.trimEnd('/') + "/" + relative)
            .encodedQuery(original.encodedQuery)
            .build()
    }

    companion object {
        /** 与 NetworkModule 里 Retrofit 的占位 baseUrl 路径保持一致 */
        const val PLACEHOLDER_PREFIX = "/placeholder"
    }
}
