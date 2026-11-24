package com.padguard.server

import com.padguard.server.domain.User
import com.padguard.server.repository.UserRepository
import com.padguard.server.security.JwtTokenProvider
import com.padguard.server.ws.DeviceWebSocketHandler
import com.padguard.server.ws.WebSocketPushService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.mockito.Mockito.verify
import org.springframework.http.HttpHeaders
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.WebSocketSession
import java.net.URI
import java.util.Optional

/**
 * 家长端 WebSocket 握手鉴权单测（第 4 轮 · 其三）。
 *
 * 直接驱动 [DeviceWebSocketHandler.afterConnectionEstablished]，覆盖令牌类型隔离、
 * 版本吊销、账号冻结等"冻结/登出后推送通道必须立即失效"的隐藏面。
 * 用 Mockito 构造可控的 WebSocketSession，无需真实 WS 容器。
 */
class DeviceWebSocketHandlerAuthTest {

    private lateinit var jwt: JwtTokenProvider
    private lateinit var users: UserRepository
    private lateinit var push: WebSocketPushService
    private lateinit var handler: DeviceWebSocketHandler

    private val secret = "integration-test-secret-key-32-bytes-minimum-ok"
    private val validId = "U-ws-valid"
    private val frozenId = "U-ws-frozen"
    private val otherSecret = "another-secret-key-which-is-also-32-bytes-ok"

    @BeforeEach
    fun setUp() {
        jwt = JwtTokenProvider(secret, 120, 30)
        users = Mockito.mock(UserRepository::class.java)
        push = Mockito.mock(WebSocketPushService::class.java)
        handler = DeviceWebSocketHandler(jwt, users, push)

        Mockito.`when`(users.findById(validId))
            .thenReturn(Optional.of(User(id = validId, phone = "13900005001", passwordHash = "x", status = "active", tokenVersion = 0)))
        Mockito.`when`(users.findById(frozenId))
            .thenReturn(Optional.of(User(id = frozenId, phone = "13900005002", passwordHash = "x", status = "frozen", tokenVersion = 0)))
    }

    private fun session(authHeader: String?, uri: URI? = null): WebSocketSession {
        val s = Mockito.mock(WebSocketSession::class.java)
        val headers = HttpHeaders()
        if (authHeader != null) headers.set("Authorization", authHeader)
        Mockito.`when`(s.handshakeHeaders).thenReturn(headers)
        Mockito.`when`(s.uri).thenReturn(uri)
        val attrs = mutableMapOf<String, Any>()
        Mockito.`when`(s.attributes).thenReturn(attrs)
        return s
    }

    private fun establish(session: WebSocketSession) {
        handler.afterConnectionEstablished(session)
    }

    /** 验证 close 从未被调用（用 captor 避开 Kotlin 非空参数上 any() 的空安全冲突） */
    private fun verifyNeverClosed(s: WebSocketSession) {
        val captor = ArgumentCaptor.forClass(CloseStatus::class.java)
        verify(s, Mockito.never()).close(captor.capture())
    }

    @Test
    fun `no token closes with NOT_ACCEPTABLE and does not register`() {
        val s = session(null)
        establish(s)
        verify(s).close(CloseStatus.NOT_ACCEPTABLE)
        assertFalse(s.attributes.containsKey("userId"), "无 token 不得建立会话")
        Mockito.verifyNoInteractions(push)
    }

    @Test
    fun `garbage token closes with NOT_ACCEPTABLE`() {
        val s = session("Bearer not-a-real-jwt")
        establish(s)
        verify(s).close(CloseStatus.NOT_ACCEPTABLE)
        assertFalse(s.attributes.containsKey("userId"))
    }

    @Test
    fun `token signed by other secret is rejected`() {
        val otherJwt = JwtTokenProvider(otherSecret, 120, 30)
        val bad = otherJwt.createAccessToken(validId, 0L)
        val s = session("Bearer $bad")
        establish(s)
        verify(s).close(CloseStatus.NOT_ACCEPTABLE)
        assertFalse(s.attributes.containsKey("userId"))
    }

    @Test
    fun `refresh token presented as access is rejected (token type isolation)`() {
        val refresh = jwt.createRefreshToken(validId, 0)
        val s = session("Bearer $refresh")
        establish(s)
        verify(s).close(CloseStatus.NOT_ACCEPTABLE)
        assertFalse(s.attributes.containsKey("userId"), "refresh token 不得建立家长推送通道")
    }

    @Test
    fun `admin token presented as access is rejected (scope isolation)`() {
        val admin = jwt.createAdminToken("A-ws", 0)
        val s = session("Bearer $admin")
        establish(s)
        verify(s).close(CloseStatus.NOT_ACCEPTABLE)
        assertFalse(s.attributes.containsKey("userId"), "admin token 不得建立家长推送通道")
    }

    @Test
    fun `stale token version after logout is rejected (revocation)`() {
        // 用户 tokenVersion 已升到 5（登出/重置密码），旧令牌版本 2 应失效
        Mockito.`when`(users.findById(validId))
            .thenReturn(Optional.of(User(id = validId, phone = "13900005001", passwordHash = "x", status = "active", tokenVersion = 5)))
        val stale = jwt.createAccessToken(validId, 2)
        val s = session("Bearer $stale")
        establish(s)
        verify(s).close(CloseStatus.NOT_ACCEPTABLE)
        assertFalse(s.attributes.containsKey("userId"), "版本落后的令牌应被吊销")
    }

    @Test
    fun `frozen user token is rejected`() {
        val tok = jwt.createAccessToken(frozenId, 0)
        val s = session("Bearer $tok")
        establish(s)
        verify(s).close(CloseStatus.NOT_ACCEPTABLE)
        assertFalse(s.attributes.containsKey("userId"), "冻结账号不得建立推送通道")
    }

    @Test
    fun `query string token works only when legacy compat is explicitly enabled`() {
        // 默认值已收口为 false（JWT 进 URL query 会被 accesslog 明文留存）。
        // 本用例验证"显式开启兼容开关"后旧客户端仍能连，不是验证默认行为。
        val f = DeviceWebSocketHandler::class.java.getDeclaredField("queryTokenEnabled")
        f.isAccessible = true
        f.set(handler, true)
        val tok = jwt.createAccessToken(validId, 0)
        val s = session(null, URI("ws://localhost/ws?token=$tok"))
        establish(s)
        verifyNeverClosed(s)
        assertEquals(validId, s.attributes["userId"], "query token 应建立会话")
        verify(push).register(validId, s)
    }

    @Test
    fun `query token is ignored when legacy compat is switched off`() {
        // 生产收口路径：客户端全部升级到 Authorization 头后，
        // 运营置 padguard.ws.query-token-enabled=false 即可关掉 URL 存证面。
        val tok = jwt.createAccessToken(validId, 0)
        val f = DeviceWebSocketHandler::class.java.getDeclaredField("queryTokenEnabled")
        f.isAccessible = true
        f.set(handler, false)
        val s = session(null, URI("ws://localhost/ws?token=$tok"))
        establish(s)
        verify(s).close(CloseStatus.NOT_ACCEPTABLE)
        assertFalse(s.attributes.containsKey("userId"), "关闭兼容后 query token 不得建立会话")
        Mockito.verifyNoInteractions(push)
    }

    @Test
    fun `header token still works when legacy compat is switched off`() {
        val tok = jwt.createAccessToken(validId, 0)
        val f = DeviceWebSocketHandler::class.java.getDeclaredField("queryTokenEnabled")
        f.isAccessible = true
        f.set(handler, false)
        val s = session("Bearer $tok", URI("ws://localhost/ws?token=should-be-ignored"))
        establish(s)
        verifyNeverClosed(s)
        assertEquals(validId, s.attributes["userId"], "关闭兼容后 Authorization 头仍应正常工作")
        verify(push).register(validId, s)
    }

    @Test
    fun `token version ahead of database is rejected (rollback-detection parity)`() {
        // 与 ParentAuthInterceptor/AdminAuthInterceptor 的 != 口径对齐：
        // 令牌版本超前于库（库被回滚/备份恢复）同样属异常态，必须拒绝。
        val tok = jwt.createAccessToken(validId, 5)
        val s = session("Bearer $tok")
        establish(s)
        verify(s).close(CloseStatus.NOT_ACCEPTABLE)
        assertFalse(s.attributes.containsKey("userId"), "ver 超前的令牌不得建立推送通道")
    }

    @Test
    fun `valid token establishes session and registers for push`() {
        val tok = jwt.createAccessToken(validId, 0)
        val s = session("Bearer $tok")
        establish(s)
        verifyNeverClosed(s)
        assertEquals(validId, s.attributes["userId"], "合法令牌应写入 userId")
        verify(push).register(validId, s)
    }
}
