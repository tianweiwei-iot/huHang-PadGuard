package com.padguard.server.config

import com.padguard.server.ws.DeviceWebSocketHandler
import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry

@Configuration
@EnableWebSocket
class WebSocketConfig(
    private val deviceWebSocketHandler: DeviceWebSocketHandler
) : WebSocketConfigurer {
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
        // 鉴权安全性：WS 握手由 DeviceWebSocketHandler 自行校验 query token
        // （jwt.parseUserId 失败即 close(NOT_ACCEPTABLE)），不依赖 MVC 拦截器；
        // 因此 WebConfig 已把 /v1/ws 排除出 ParentAuth / Subscription 两个拦截器 ——
        // 这两个拦截器只认 Authorization 头，对 WS 的 query token 本就不适用，
        // 若不过滤会把合法的 WS 握手误判成未授权。
        registry.addHandler(deviceWebSocketHandler, "/ws", "/v1/ws")
            .setAllowedOrigins("*")
    }
}
