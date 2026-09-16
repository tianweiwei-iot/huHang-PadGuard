package com.padguard.server.ws

import com.padguard.server.security.JwtTokenProvider
import org.slf4j.LoggerFactory
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.WebSocketMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.stereotype.Component

/** 家长端 WebSocket：wss://host/ws?token=<JWT> */
@Component
class DeviceWebSocketHandler(
    private val jwt: JwtTokenProvider,
    private val webSocketPush: WebSocketPushService
) : WebSocketHandler {

    private val log = LoggerFactory.getLogger(DeviceWebSocketHandler::class.java)

    override fun afterConnectionEstablished(session: WebSocketSession) {
        val token = session.uri?.query?.let { parseToken(it) }
        val userId = if (token != null) {
            try { jwt.parseUserId(token) } catch (e: Exception) { null }
        } else null

        if (userId == null) {
            session.close(CloseStatus.NOT_ACCEPTABLE)
            return
        }
        session.attributes["userId"] = userId
        webSocketPush.register(userId, session)
        log.info("WS connected: user=$userId")
    }

    override fun handleMessage(session: WebSocketSession, message: WebSocketMessage<*>) {
        // 家长端为单向接收；客户端上行消息暂不处理
    }

    override fun handleTransportError(session: WebSocketSession, exception: Throwable) {
        log.warn("WS transport error: ${exception.message}")
    }

    override fun afterConnectionClosed(session: WebSocketSession, closeStatus: CloseStatus) {
        val userId = session.attributes["userId"] as? String
        if (userId != null) webSocketPush.unregister(userId, session)
    }

    override fun supportsPartialMessages(): Boolean = false

    private fun parseToken(query: String): String? =
        query.split("&").firstOrNull { it.startsWith("token=") }?.substringAfter("token=")
}
