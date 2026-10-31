package com.padguard.parent.data.remote

import com.padguard.parent.data.auth.TokenManager
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 自动附加鉴权头：将 [TokenManager] 中的 JWT 以 `Authorization: Bearer <token>` 形式注入每个请求。
 *
 * 采用主流 OkHttp 拦截器模式；令牌读取为内存型轻操作，使用 runBlocking 不会阻塞主线程（调用在 IO 线程）。
 *
 * ## Content-Type 只能"补缺"，绝不能覆盖
 *
 * 早期实现无条件 `.header("Content-Type", "application/json")`。`Request.Builder.header()`
 * 的语义是**先删除同名头再添加**，于是 Retrofit `@Multipart` 生成的
 * `Content-Type: multipart/form-data; boundary=xxx` 里的 **boundary 被抹掉**，
 * 请求变成「application/json 头 + multipart 二进制体」。
 * 服务端 `/v1/files/upload`、`/v1/devices/app-catalog` 都声明了
 * `consumes = MULTIPART_FORM_DATA_VALUE`，boundary 丢失后 Spring 无法解析分段，直接返回
 * **415 Unsupported Media Type** —— 信息发布素材、本地 APK 分发、应用库 APK 三条上传链路全废。
 *
 * 正确做法：Retrofit 已经为每种注解生成了正确的 Content-Type，
 * 本拦截器只在请求**没有** body/contentType 时补一个默认值（如 `@GET`/无参 `@POST`）。
 */
class AuthInterceptor(
    private val tokenManager: TokenManager
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val token = runBlocking { tokenManager.getAccessToken() }
        val builder = original.newBuilder()
            .header("Accept", "application/json")
        // 仅在请求确实没有 Content-Type（无 body 或 Retrofit 未生成）时才补默认值，
        // 绝不能覆盖 multipart 的 boundary（见类注释）。
        if (original.body?.contentType() == null && original.header("Content-Type") == null) {
            builder.header("Content-Type", "application/json")
        }
        token?.let { builder.header("Authorization", "Bearer $it") }
        return chain.proceed(builder.build())
    }
}
