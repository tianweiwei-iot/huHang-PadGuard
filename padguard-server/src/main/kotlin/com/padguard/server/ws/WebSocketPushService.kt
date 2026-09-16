package com.padguard.server.ws

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import java.util.concurrent.ConcurrentHashMap

/** 家长端实时推送：按 userId 维护 WS 会话 */
@Component
class WebSocketPushService(
    private val objectMapper: ObjectMapper
) {
    private val sessions = ConcurrentHashMap<String, MutableSet<WebSocketSession>>()

    fun register(userId: String, session: WebSocketSession) {
        sessions.computeIfAbsent(userId) { ConcurrentHashMap.newKeySet() }.add(session)
    }

    fun unregister(userId: String, session: WebSocketSession) {
        sessions[userId]?.remove(session)
    }

    fun pushToUser(userId: String?, payload: Map<String, Any?>) {
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
