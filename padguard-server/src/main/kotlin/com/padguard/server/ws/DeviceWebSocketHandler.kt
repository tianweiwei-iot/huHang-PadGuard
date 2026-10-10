package com.padguard.server.ws

import com.padguard.server.domain.UserStatus
import com.padguard.server.repository.UserRepository
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
    private val users: UserRepository,
    private val webSocketPush: WebSocketPushService
) : WebSocketHandler {

    private val log = LoggerFactory.getLogger(DeviceWebSocketHandler::class.java)

    /**
     * 握手鉴权：只验签是不够的。
     *
     * WebSocket 握手被 [com.padguard.server.config.WebConfig] 从家长端拦截器里排除
     * （token 走 query 参数而非 Authorization 头），因此这里必须自己把拦截器做的事补齐：
     * 校验账号存在 + 账号状态可用。否则后台冻结家长后，
     * 该家长的长连接仍能收到告警、上下线、解锁申请等推送 —— 冻结出现一条绕过口子。
     */
    override fun afterConnectionEstablished(session: WebSocketSession) {
        val token = session.uri?.query?.let { parseToken(it) }
        // 必须是 access token（refresh/管理员令牌不得建立家长推送通道），
        // 且账号必须真实存在 —— 与 ParentAuthInterceptor 的"幽灵账号"防护同口径。
        val userId = if (token != null) {
            try {
                val parsed = jwt.parseAccessToken(token)
                if (users.existsById(parsed)) parsed else null
            } catch (e: Exception) { null }
        } else null

        val user = userId?.let { runCatching { users.findById(it).orElse(null) }.getOrNull() }
        if (user == null || !UserStatus.isUsable(user.status)) {
            log.info("WS rejected: userId={}, reason=unknown-or-not-usable", userId)
            session.close(CloseStatus.NOT_ACCEPTABLE)
            return
        }
        session.attributes["userId"] = user.id
        webSocketPush.register(user.id, session)
        log.info("WS connected: user=${user.id}")
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
