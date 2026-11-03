package com.padguard.server.config

import com.padguard.server.ws.DeviceWebSocketHandler
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.server.HandshakeInterceptor

@Configuration
@EnableWebSocket
class WebSocketConfig(
    private val deviceWebSocketHandler: DeviceWebSocketHandler
) : WebSocketConfigurer {

    /**
     * 浏览器侧允许握手的来源白名单（逗号分隔，如 `https://admin.example.com`）。
     *
     * 默认**空**：只放行不带 `Origin` 头的客户端 —— 家长端是原生 Android（OkHttp 不发
     * Origin），因此默认配置对现有客户端零影响，同时彻底关掉"任何网页都能连"这一面。
     * 将来若做 Web 家长端，把其域名配进 `PADGUARD_WS_ALLOWED_ORIGINS` 即可。
     *
     * 用字段注入而不是构造参数：本类是 @Configuration（会被 CGLIB 代理），构造参数上挂
     * @Value 会让 Spring 在"多构造器候选"下判定不出注入点，直接报 No default constructor found，
     * 整个上下文起不来（已被集成测试捕获）。字段注入不改变构造器签名，最稳。
     */
    @Value("\${padguard.ws.allowed-origins:}")
    private var allowedOriginsRaw: String = ""

    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        // 同时注册两个路径（同一 handler）：
        // - "/ws"     ：当前标准路径，家长端源码 UsagePushClient 连此路径
        // - "/v1/ws"  ：兼容存量旧客户端 APK
        //
        // 为什么必须兼容 "/v1/ws"（2026-10-06 线上事故复盘）：
        // 旧版家长端 APK 连的是 /v1/ws，而早期只注册了 /ws —— 旧包每次握手都打到
        // **不存在的端点**，服务端抛 NoResourceFoundException 并被 GlobalExceptionHandler
        // 兜成 500（{"code":5001,"message":"No static resource v1/ws."}）。
        // 客户端握手失败后会**每秒重试**，单日实测产生 35890 次 500，把 Tomcat 线程
        // 长时间占满，表现为整个服务"起不来 / 假死"，而 /health 与普通 HTTP 接口仍 200，
        // 极易误判为"服务端挂了"。注册该别名后旧包也能正常握手，雪崩立刻收敛。
        //
        // 鉴权安全性：WS 握手由 DeviceWebSocketHandler 自行校验 token
        // （优先 Authorization 头，兼容 query），不依赖 MVC 拦截器；
        // 因此 WebConfig 已把 /v1/ws 排除出 ParentAuth / Subscription 两个拦截器 ——
        // 这两个拦截器只认 Authorization 头，对 WS 的 query token 本就不适用，
        // 若不过滤会把合法的 WS 握手误判成未授权。
        //
        // 来源校验：**刻意不调用 setAllowedOrigins**。
        // Spring 在 allowedOrigins 为空时不校验、为 "*" 时全放行，两种都表达不了
        // "只允许原生客户端（无 Origin）"这条规则，所以改由 [OriginAllowlistInterceptor]
        // 显式判定：规则可读、可配、拒绝时有日志。
        registry.addHandler(deviceWebSocketHandler, "/ws", "/v1/ws")
            .addInterceptors(OriginAllowlistInterceptor(parseOrigins(allowedOriginsRaw)))
    }

    private fun parseOrigins(raw: String): Set<String> =
        raw.split(',')
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotEmpty() }
            .toSet()

    /**
     * 拒绝浏览器跨站握手的拦截器（CSWSH 防护）。
     *
     * 只做一件事：带 `Origin` 头的握手必须命中白名单，否则 403；不带 Origin 的放行。
     *
     * 为什么"无 Origin 就放行"是安全的：`Origin` 由浏览器强制加上、页面 JS 无法改写，
     * 跨站 WebSocket 劫持只能由浏览器发起，攻击者无法让自己的页面省略该头。
     * 反过来，原生客户端不发 `Origin` 是常态，一律拒掉会直接打死家长端推送通道。
     *
     * 顺带收掉一个隐蔽面：此前 `setAllowedOrigins("*")` 允许任意网页建立到 `/ws` 的连接。
     * 虽然 token 仍需有效，但这给"token 泄露后从浏览器侧利用"开了一条零成本通道，
     * 也让端点对外暴露成"任何人可连"。
     */
    private class OriginAllowlistInterceptor(
        private val allowed: Set<String>
    ) : HandshakeInterceptor {

        private val log = LoggerFactory.getLogger(OriginAllowlistInterceptor::class.java)

        override fun beforeHandshake(
            request: ServerHttpRequest,
            response: ServerHttpResponse,
            wsHandler: WebSocketHandler,
            attributes: MutableMap<String, Any>
        ): Boolean {
            val origin = request.headers.origin?.trim()?.trimEnd('/')
            // 非浏览器客户端（Android OkHttp / 服务端脚本）不发送 Origin
            if (origin.isNullOrEmpty()) return true
            if (allowed.contains(origin)) return true
            log.warn("ws handshake rejected by origin policy: origin={} path={}", origin, request.uri.path)
            response.setStatusCode(HttpStatus.FORBIDDEN)
            return false
        }

        override fun afterHandshake(
            request: ServerHttpRequest,
            response: ServerHttpResponse,
            wsHandler: WebSocketHandler,
            exception: Exception?
        ) { /* 握手成败已由 beforeHandshake 决定，这里无需处理 */ }
    }
}
