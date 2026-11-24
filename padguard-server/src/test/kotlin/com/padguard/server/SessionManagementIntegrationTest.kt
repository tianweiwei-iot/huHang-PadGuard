package com.padguard.server

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

/**
 * 第三步（安全）F-01 的控制器层闭环：单设备会话管理端点 + IDOR 越权防护。
 *
 * 覆盖：
 * 1. 登录即建会话，GET /v1/auth/sessions 能列出；
 * 2. 多端登录产生多条会话，可单独 DELETE 其中一条，且不影响其他；
 * 3. 越权防护（F-05）：账号 B 拿自己的令牌去删 A 的会话，必须被拒（返回 400「会话不存在」），
 *    服务端按 `userId`（取自 JWT）过滤，绝不允许用请求体里的 id 跨账号操作。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("local")
@TestPropertySource(properties = [
    "padguard.storage.dir=build/test-uploads",
    "padguard.ratelimit.register=200",
    "padguard.ratelimit.revoke_session=200",
    "spring.datasource.url=jdbc:h2:mem:sessions;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "padguard.jwt.secret=session-mgmt-test-secret-key-32-byt",
    "padguard.demo-accounts-enabled=false",
    "padguard.initial-password-file=target/sessions-admin-pwd.txt",
    "padguard.parent-initial-password-file=target/sessions-parent-pwd.txt",
    "padguard.base-url=http://localhost:8080"
])
class SessionManagementIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private data class Session(val phone: String, val access: String, val refresh: String)

    companion object {
        private val phoneSeq = AtomicInteger(7000)
        private const val PASSWORD = "Test@12345"
    }

    private fun registerParent(prefix: String = "138"): Session {
        val phone = prefix + (60000000 + phoneSeq.incrementAndGet())
        val body = objectMapper.writeValueAsString(mapOf("phone" to phone, "password" to PASSWORD))
        val res = mockMvc.perform(
            MockMvcRequestBuilders.post("/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .accept(MediaType.APPLICATION_JSON)
        ).andExpect(status().isOk).andReturn()
        val json = objectMapper.readTree(res.response.getContentAsString(StandardCharsets.UTF_8))
        return Session(phone, json.at("/data/token").asText(), json.at("/data/refreshToken").asText())
    }

    private fun login(phone: String): Session {
        val body = objectMapper.writeValueAsString(mapOf("phone" to phone, "password" to PASSWORD))
        val res = mockMvc.perform(
            MockMvcRequestBuilders.post("/v1/auth/login/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .accept(MediaType.APPLICATION_JSON)
        ).andExpect(status().isOk).andReturn()
        val json = objectMapper.readTree(res.response.getContentAsString(StandardCharsets.UTF_8))
        return Session(phone, json.at("/data/token").asText(), json.at("/data/refreshToken").asText())
    }

    private fun listSessions(access: String): List<JsonNode> {
        val res = mockMvc.perform(
            MockMvcRequestBuilders.get("/v1/auth/sessions")
                .header("Authorization", "Bearer $access")
                .accept(MediaType.APPLICATION_JSON)
        ).andExpect(status().isOk).andReturn()
        val node = objectMapper.readTree(res.response.getContentAsString(StandardCharsets.UTF_8)).at("/data")
        return if (node.isArray) node.toList() else emptyList()
    }

    private fun revokeSession(access: String, sessionId: String) = mockMvc.perform(
        MockMvcRequestBuilders.delete("/v1/auth/sessions/$sessionId")
            .header("Authorization", "Bearer $access")
            .accept(MediaType.APPLICATION_JSON)
    )

    @Test
    fun `登录后列出会话，且可单独吊销其中一台不影响其他`() {
        val s = registerParent()

        // 基线：首次登录后恰有一条活跃会话
        var sessions = listSessions(s.access)
        assertEquals(1, sessions.size, "首次登录应建立一条会话")
        val firstId = sessions[0].at("/id").asText()

        // 第二台「设备」登录，制造第二条会话
        val s2 = login(s.phone)
        sessions = listSessions(s.access)
        assertEquals(2, sessions.size, "第二台设备登录应新增一条会话")

        // 只吊销第一台
        revokeSession(s.access, firstId).andExpect(status().isOk)
        sessions = listSessions(s.access)
        assertEquals(1, sessions.size, "吊销后应只剩一台会话")
        assertEquals(false, sessions.any { it.at("/id").asText() == firstId }, "被吊销的会话不应再出现在列表")

        // 第二台仍能正常 refresh（单设备退出不影响其他设备）
        mockMvc.perform(
            MockMvcRequestBuilders.post("/v1/auth/refresh")
                .header("Refresh-Token", s2.refresh)
                .accept(MediaType.APPLICATION_JSON)
        ).andExpect(status().isOk)
    }

    @Test
    fun `吊销需要登录态，匿名请求被拒`() {
        val s = registerParent()
        val id = listSessions(s.access)[0]["id"].toString()
        // 不带令牌：拦截器直接 401，不能让匿名用户删别人的会话
        revokeSession("", id).andExpect(status().isUnauthorized)
    }

    @Test
    fun `账号B不能吊销账号A的会话（IDOR防护）`() {
        val a = registerParent("137")
        val b = registerParent("139")
        val aSessions = listSessions(a.access)
        assertEquals(1, aSessions.size)
        val aSessionId = aSessions[0]["id"].toString()

        // B 拿自己的令牌去删 A 的会话：服务端按 B 的 userId 过滤，查不到 A 的会话 → 拒绝
        revokeSession(b.access, aSessionId)
            .andExpect(status().isBadRequest)

        // A 的会话依然健在
        assertEquals(1, listSessions(a.access).size, "A 的会话不应被 B 越权吊销")
    }
}
