package com.padguard.server

import com.padguard.server.domain.User
import com.padguard.server.repository.UserRepository
import com.padguard.server.security.JwtTokenProvider
import com.padguard.server.security.Passwords
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.WebSocketHttpHeaders
import org.springframework.web.socket.WebSocketMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 真实容器（RANDOM_PORT）边界测试（第 4 轮 · 其四）。
 *
 * 为什么必须真实容器：MockMvc 不经过 Servlet 容器的 multipart 大小限制，
 * 也无法做 WebSocket 握手。这里用真实 Tomcat 验证两件事：
 * 1. CSWSH 防护 —— 原生客户端（不发 Origin）可握手；浏览器跨站 Origin 被 403 拒绝；
 * 2. 超限上传 —— multipart 上限压到 10KB，传 11KB 必须返回 413（而非 500）。
 *
 * 内存 H2（wstest），不与其它测试类交叉。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@TestPropertySource(properties = [
    "padguard.storage.dir=build/test-uploads",
    "spring.datasource.url=jdbc:h2:mem:wstest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "padguard.jwt.secret=integration-test-secret-key-32-bytes-minimum-ok",
    "padguard.demo-accounts-enabled=false",
    "padguard.initial-password-file=target/wshandshake-itest-admin-pwd.txt",
    "padguard.parent-initial-password-file=target/wshandshake-itest-parent-pwd.txt",
    "padguard.base-url=http://localhost:8080",
    "padguard.admin.dashboard-cache-ttl-ms=0",
    "spring.servlet.multipart.max-file-size=10KB",
    "spring.servlet.multipart.max-request-size=10KB"
])
class WebSocketHandshakeBoundaryTest {

    @Autowired lateinit var jwt: JwtTokenProvider
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var restTemplate: TestRestTemplate

    @Value("\${local.server.port}")
    private var port: Int = 0

    private lateinit var validToken: String

    @BeforeEach
    fun setUp() {
        val id = "U-ws-handshake"
        if (userRepository.findById(id).isEmpty) {
            userRepository.save(User(id = id, phone = "13900006001", passwordHash = Passwords.encode("Test@12345"), status = "active", tokenVersion = 0))
        }
        validToken = jwt.createAccessToken(id, 0)
    }

    private class CapturingHandler : WebSocketHandler {
        val sessionRef = AtomicReference<WebSocketSession?>()
        @Volatile var closed = false
        override fun afterConnectionEstablished(session: WebSocketSession) { sessionRef.set(session) }
        override fun handleMessage(session: WebSocketSession, message: WebSocketMessage<*>) {}
        override fun handleTransportError(session: WebSocketSession, exception: Throwable) {}
        override fun afterConnectionClosed(session: WebSocketSession, closeStatus: CloseStatus) { closed = true }
        override fun supportsPartialMessages(): Boolean = false
    }

    @Test
    fun `native client without Origin can establish ws handshake with valid token`() {
        val client = StandardWebSocketClient()
        val uri = URI("ws://localhost:$port/ws")
        val headers = WebSocketHttpHeaders()
        headers.set("Authorization", "Bearer $validToken")
        val handler = CapturingHandler()
        val session = client.execute(handler, headers, uri).get(8, TimeUnit.SECONDS)
        assertNotNull(session, "原生客户端应成功握手")
        try { session.close() } catch (_: Exception) { }
    }

    @Test
    fun `browser cross-site Origin is rejected with 403 handshake`() {
        val client = StandardWebSocketClient()
        val uri = URI("ws://localhost:$port/ws")
        val headers = WebSocketHttpHeaders()
        headers.set("Origin", "http://evil.example.com")
        headers.set("Authorization", "Bearer $validToken")
        val handler = CapturingHandler()
        var failed = false
        var reason = ""
        try {
            client.execute(handler, headers, uri).get(8, TimeUnit.SECONDS)
        } catch (e: Exception) {
            failed = true
            reason = e.message ?: e.javaClass.simpleName
        }
        assertTrue(failed, "带跨站 Origin 的握手应被拒绝（CSWSH 防护），实际竟然建立连接")
        assertTrue(reason.contains("403") || reason.contains("Forbidden") || reason.contains("handshake"),
            "拒绝原因应指向握手 403，实际=$reason")
    }

    // ---------------- 真实容器：超限上传 ----------------

    @Test
    fun `oversized upload beyond real multipart limit is 413 not 500`() {
        val headers = HttpHeaders()
        headers.contentType = MediaType.MULTIPART_FORM_DATA
        headers.set("Authorization", "Bearer $validToken")
        val body = LinkedMultiValueMap<String, Any>()
        body.add("file", object : ByteArrayResource(ByteArray(11 * 1024) { 0x41 }) {
            override fun getFilename(): String = "big.png"
        })
        val resp = restTemplate.postForEntity("/v1/media/upload", HttpEntity(body, headers), String::class.java)
        assertEquals(413, resp.statusCode.value(), "真实容器下超限上传应 413（PAYLOAD_TOO_LARGE），实际=${resp.statusCode.value()} body=${resp.body?.take(200)}")
    }

    @Test
    fun `small upload within real limit still succeeds 200`() {
        val headers = HttpHeaders()
        headers.contentType = MediaType.MULTIPART_FORM_DATA
        headers.set("Authorization", "Bearer $validToken")
        val body = LinkedMultiValueMap<String, Any>()
        body.add("file", object : ByteArrayResource("fakepng".toByteArray()) {
            override fun getFilename(): String = "small.png"
        })
        val resp = restTemplate.postForEntity("/v1/media/upload", HttpEntity(body, headers), String::class.java)
        assertEquals(200, resp.statusCode.value(), "真实容器下小文件应 200，实际=${resp.statusCode.value()} body=${resp.body?.take(200)}")
    }
}
