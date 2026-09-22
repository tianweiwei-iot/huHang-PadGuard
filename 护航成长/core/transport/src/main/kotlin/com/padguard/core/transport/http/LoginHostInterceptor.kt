package com.padguard.core.transport.http

import com.padguard.core.transport.TransportSettings
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 家庭账号登录专用 Host 改写拦截器。
 *
 * 与 [HostSelectionInterceptor] 的区别：后者把相对路径**追加**到 [TransportSettings.baseUrl]
 * 的 `/api/v1` 前缀之下，适用于子端设备接口；而登录接口在家长端的 `/v1/auth/...` 下，
 * 不能走子端前缀。本拦截器只取配置地址的 scheme/host/port，**原样保留请求自身的相对路径**，
 * 从而得到 `http://<host>:8090/v1/auth/login/password`。
 *
 * 登录请求不带设备鉴权头（此时尚未绑定），由独立 Retrofit 装配时显式排除 [AuthInterceptor]。
 */
@Singleton
class LoginHostInterceptor @Inject constructor(
    private val settings: TransportSettings
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val target = settings.baseUrl.value.toHttpUrlOrNull() ?: return chain.proceed(request)

        val rewritten = request.url.newBuilder()
            .scheme(target.scheme)
            .host(target.host)
            .port(target.port)
            .build()

        return chain.proceed(request.newBuilder().url(rewritten).build())
    }
}
