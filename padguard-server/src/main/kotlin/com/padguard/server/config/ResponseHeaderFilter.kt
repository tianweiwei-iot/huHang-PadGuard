package com.padguard.server.config

import com.padguard.server.common.SecurityHeaders
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 全局安全响应头（应用层兜底）。
 *
 * ## 为什么 nginx 已经加了还要在应用里再加一遍
 * `nginx/nginx.conf` 的 `add_header` 只在 **docker + TLS 反代** 这一种部署形态下生效。
 * 本项目更常见的形态是家长端直接把地址填成 `http://<内网IP>:8090`（`start-server.bat`），
 * 请求**根本不过 nginx**，于是：
 * - `X-Content-Type-Options` 缺失 → 浏览器/WebView 可能把上传目录里的
 *   `xxx.png` 按内容嗅探成 HTML/JS 执行（存储型 XSS 的经典入口；
 *   `FilesController` 只给自己的下载响应单独加了 nosniff，其余响应没有）；
 * - `X-Frame-Options` 缺失 → 后台 `/admin/` 可被任意站点 iframe 嵌套并做点击劫持；
 * - `Referrer-Policy` 缺失 → 从后台点外链会把内网地址带进第三方 Referer。
 *
 * 两层并不冲突：nginx 与这里设的是同名同值头，Servlet 容器不会重复输出，
 * 哪一层在链路上就以哪一层为准。
 *
 * ## 为什么用 Filter 而不是 HandlerInterceptor
 * 拦截器管不到 404、静态资源（`/admin/` 下）与被鉴权排除的路径，
 * 而这三类恰恰是上面三条风险的主要载体。Filter 覆盖所有进入容器的请求。
 *
 * ## 为什么**不**在这里加 `Cache-Control: no-store`
 * `/v1/` 下混着两类语义完全不同的响应：JSON 业务数据（应禁缓存，防中间代理留存家长隐私）
 * 与 `/v1/files/`、`/v1/media/` 的图片/录像（应允许客户端磁盘缓存，否则家长端每次
 * 翻看相册都要重新下载整张图）。Filter 在 `chain.doFilter` 之前无从得知响应类型，
 * 而之后再加头对已提交的响应无效 —— 一刀切只会把相册变成流量黑洞。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class ResponseHeaderFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain
    ) {
        SecurityHeaders.apply(response)
        chain.doFilter(request, response)
    }

    /**
     * 异步请求（Spring MVC 的 `Callable`/`DeferredResult`，看屏相关接口在用）在**二次派发**时
     * 还要再过一遍：第一次派发只返回了"未完成"，真正的数据是在异步派发里写的。
     * [SecurityHeaders.apply] 幂等（已存在同名头就不覆盖），重复执行无副作用。
     */
    override fun shouldNotFilterAsyncDispatch(): Boolean = false
}
