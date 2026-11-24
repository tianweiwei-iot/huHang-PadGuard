package com.padguard.server.ws

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.domain.User
import com.padguard.server.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.web.socket.WebSocketSession
import java.util.Optional

/**
 * WebSocket 长连接的**运行期**账号复核（新增缺陷 K）。
 *
 * 与 [WebSocketPushServiceTest] 的分工：那边测会话数量治理，这边测
 * "连接建立之后账号被登出 / 冻结 / 删号 / 重置密码"这四种状态变化。
 * 这些场景在 HTTP 侧由拦截器逐请求挡住，WS 侧若不复核就会一直漏推送。
 */
class WebSocketSessionRevocationTest {

    private val mapper = ObjectMapper()

    /** 构造一个已建立的会话；version 为握手时写入的令牌版本，null 模拟旧版客户端没写。 */
    private fun session(version: Long? = null): WebSocketSession {
        val s = mock(WebSocketSession::class.java)
        `when`(s.isOpen).thenReturn(true)
        `when`(s.attributes).thenReturn(
            mutableMapOf<String, Any>().apply {
                if (version != null) put(WebSocketPushService.ATTR_TOKEN_VERSION, version)
            }
        )
        return s
    }

    private fun repo(user: User? = null, broken: Boolean = false): UserRepository {
        val r = mock(UserRepository::class.java)
        if (broken) {
            `when`(r.findById(anyString())).thenThrow(RuntimeException("db unavailable"))
        } else {
            `when`(r.findById(anyString())).thenReturn(Optional.ofNullable(user))
        }
        return r
    }

    private fun activeUser(version: Long = 3, status: String = "active") =
        User(id = "u1", status = status, tokenVersion = version)

    private fun service(users: UserRepository) =
        WebSocketPushService(mapper, maxSessionsPerUser = 5, users = users)

    /** 正常状态：令牌版本与库内一致，推送照常到达。 */
    @Test
    fun `current account keeps receiving pushes`() {
        val s = session(version = 3)
        val svc = service(repo(activeUser(version = 3)))
        svc.register("u1", s)

        svc.pushToUser("u1", mapOf("type" to "alert.new"))

        verify(s, times(1)).sendMessage(any())
        verify(s, never()).close(any())
    }

    /** 后台冻结只改 status、不动 token_version：旧连接必须立刻收不到告警。 */
    @Test
    fun `suspended account loses its established ws session`() {
        val s = session(version = 3)
        val svc = service(repo(activeUser(version = 3, status = "suspended")))
        svc.register("u1", s)

        svc.pushToUser("u1", mapOf("type" to "alert.new"))

        verify(s, never()).sendMessage(any())
        verify(s, times(1)).close(any())
        // 会话已整账号清除，之后即使账号恢复也不会向僵尸会话推送
        assertEquals(0, svc.revokeUser("u1"))
    }

    /** 账号被删除：查无此人，fail-closed。 */
    @Test
    fun `deleted account loses its established ws session`() {
        val s = session(version = 3)
        val svc = service(repo(user = null))
        svc.register("u1", s)

        svc.pushToUser("u1", mapOf("type" to "unlock.request"))

        verify(s, never()).sendMessage(any())
        verify(s, times(1)).close(any())
    }

    /** 登出 / 重置密码使 token_version +1：即使漏调 revokeUser，推送前复核也兜住。 */
    @Test
    fun `stale token version revokes session even without explicit revoke call`() {
        val old = session(version = 3)
        val svc = service(repo(activeUser(version = 4)))
        svc.register("u1", old)

        svc.pushToUser("u1", mapOf("type" to "location.update"))

        verify(old, never()).sendMessage(any())
        verify(old, times(1)).close(any())
    }

    /** 多会话混合版本：只要有一条落后，整账号旧会话一并清掉，不给"漏一条"留口子。 */
    @Test
    fun `one stale session revokes the whole account`() {
        val stale = session(version = 3)
        val current = session(version = 4)
        val svc = service(repo(activeUser(version = 4)))
        svc.register("u1", stale)
        svc.register("u1", current)

        svc.pushToUser("u1", mapOf("type" to "ping"))

        verify(stale, never()).sendMessage(any())
        verify(current, never()).sendMessage(any())
        verify(stale, times(1)).close(any())
        verify(current, times(1)).close(any())
    }

    /** 读库抖动不能把全体在线家长踢下线（重连风暴可用性损失 > 短暂维持已认证连接）。 */
    @Test
    fun `db outage keeps established session instead of revoking everyone`() {
        val s = session(version = 3)
        val svc = service(repo(broken = true))
        svc.register("u1", s)

        svc.pushToUser("u1", mapOf("type" to "ping"))

        verify(s, times(1)).sendMessage(any())
        verify(s, never()).close(any())
    }

    /** 老会话没有版本属性（旧版客户端）：只按账号状态复核，不得误踢。 */
    @Test
    fun `legacy session without token version still passes account check`() {
        val s = session(version = null)
        val svc = service(repo(activeUser(version = 9)))
        svc.register("u1", s)

        svc.pushToUser("u1", mapOf("type" to "ping"))

        verify(s, times(1)).sendMessage(any())
    }

    /** 显式吊销点：立即关闭全部会话、幂等，且吊销后不再推送。 */
    @Test
    fun `revokeUser closes all sessions and is idempotent`() {
        val a = session(version = 3)
        val b = session(version = 3)
        val svc = service(repo(activeUser(version = 3)))
        svc.register("u1", a)
        svc.register("u1", b)

        assertEquals(2, svc.revokeUser("u1"))
        assertEquals(0, svc.revokeUser("u1"))
        svc.pushToUser("u1", mapOf("type" to "ping"))

        verify(a, times(1)).close(any())
        verify(b, times(1)).close(any())
        verify(a, never()).sendMessage(any())
        verify(b, never()).sendMessage(any())
    }

    /** 显式 active 仓储替身：会话治理语义不因"强制复核"而改变。 */
    @Test
    fun `permissive repository keeps legacy session governance behavior`() {
        val s = session(version = 0)
        val svc = service(com.padguard.server.support.PushTestUsers.permissive())
        svc.register("u1", s)

        svc.pushToUser("u1", mapOf("type" to "ping"))

        verify(s, times(1)).sendMessage(any())
    }
}
