package com.padguard.server.common

import jakarta.servlet.http.HttpServletResponse

/**
 * 安全响应头的唯一来源。
 *
 * 抽成对象是为了让过滤器（[com.padguard.server.config.ResponseHeaderFilter]）与
 * 全局异常兜底（[GlobalExceptionHandler]）共用同一份常量：
 * 错误兜底在写 JSON 前会 `response.reset()`（清空状态码与全部头，见该处注释），
 * 那一下会把过滤器早先设好的头一起抹掉 —— 也就是说**最需要 nosniff 的错误响应
 * 恰恰是唯一没有它的响应**。所以 reset 之后必须按同一份定义补回来。
 *
 * 三层响应头的职责（nginx / 这里 / 各 controller）：
 * - nginx 的 `add_header` 只在 docker + TLS 反代形态生效；
 * - 家庭部署走 `http://<内网IP>:8090` 直连，不过 nginx，只能靠这里；
 * - `FilesController` 给自己的下载响应单独设 `nosniff`，是第三层冗余，保留不动。
 */
object SecurityHeaders {

    const val CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"
    const val FRAME_OPTIONS = "X-Frame-Options"
    const val REFERRER_POLICY = "Referrer-Policy"

    /**
     * 幂等地写入三个头。
     *
     * 用 `containsHeader` 判存在、`setHeader` 而非 `addHeader`：与 nginx 的 `add_header`
     * 语义对齐（同名只保留一份），也避免同值头重复出现。
     * 响应已提交时 `setHeader` 按 Servlet 规范被忽略，不会抛异常。
     */
    fun apply(response: HttpServletResponse) {
        if (!response.containsHeader(CONTENT_TYPE_OPTIONS)) {
            response.setHeader(CONTENT_TYPE_OPTIONS, "nosniff")
        }
        if (!response.containsHeader(FRAME_OPTIONS)) {
            response.setHeader(FRAME_OPTIONS, "SAMEORIGIN")
        }
        if (!response.containsHeader(REFERRER_POLICY)) {
            response.setHeader(REFERRER_POLICY, "no-referrer")
        }
    }
}
