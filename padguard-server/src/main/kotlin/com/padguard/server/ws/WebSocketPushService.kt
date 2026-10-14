package com.padguard.server.ws

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import java.util.concurrent.ConcurrentHashMap

/**
 * 家长端实时推送：按 userId 维护 WS 会话。
 *
 * `open` 不是为继承而设计，而是为了让测试能替换 pushToUser 断言"事件确实被推给了正确的家长"——
 * 推送是接入链路的关键一环（家长端据此结束等待），必须可测。
 */
@Component
open class WebSocketPushService(
    private val objectMapper: ObjectMapper
) {
    private val sessions = ConcurrentHashMap<String, MutableSet<WebSocketSession>>()

    fun register(userId: String, session: WebSocketSession) {
        sessions.computeIfAbsent(userId) { ConcurrentHashMap.newKeySet() }.add(session)
    }

    fun unregister(userId: String, session: WebSocketSession) {
        sessions[userId]?.remove(session)
    }

    open fun pushToUser(userId: String?, payload: Map<String, Any?>) {
        if (userId == null) return
        val json = objectMapper.writeValueAsString(payload)
        sessions[userId]?.forEach { s ->
            if (s.isOpen) {
                try {
                    s.sendMessage(TextMessage(json))
                } catch (e: Exception) {
                    // 单条推送失败不影响其他会话
                }
            }
        }
    }
}
