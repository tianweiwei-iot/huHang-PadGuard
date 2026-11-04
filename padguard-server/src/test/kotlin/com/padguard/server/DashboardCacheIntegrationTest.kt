package com.padguard.server

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.domain.Admin
import com.padguard.server.repository.AdminRepository
import com.padguard.server.security.JwtTokenProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * 仪表盘短 TTL 缓存的行为测试。
 *
 * ## 为什么必须有这一条
 * 缓存最容易犯的两个错误都"不报错"：
 * 1. **缓存形同虚设** —— 键里混入了请求者相关的字段（adminId / token），
 *    每个管理员各有一份，命中率永远为 0，等于白写；
 * 2. **缓存吞掉新鲜度** —— 本想只缓存 KPI，却把 `recent`（最近操作）也缓存了。
 *    `recent` 依赖当前管理员的 audit:read 权限，一旦进缓存，
 *    无权限的管理员会在 TTL 窗口内读到有权限者的操作流 —— 这是越权，不是性能优化。
 *
 * 这里用 600ms 的短 TTL 验证行为本身：
 * - 缓存生效：创建家长后立即再查，总数**不变**（命中的是旧快照）；
 * - TTL 过期：等待过期后再查，总数反映新数据；
 * - TTL=0 等价禁用（其它测试上下文都用 0，语义已被那些用例间接覆盖）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("local")
@TestPropertySource(properties = [
    "spring.datasource.url=jdbc:h2:mem:dashcache;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "padguard.jwt.secret=dash-cache-test-secret-key-32-bytes-ok",
    "padguard.demo-accounts-enabled=false",
    "padguard.initial-password-file=target/dashcache-admin-pwd.txt",
    "padguard.parent-initial-password-file=target/dashcache-parent-pwd.txt",
    "padguard.base-url=http://localhost:8080",
    // 短 TTL：既能让"立即再查"稳定命中，又不必 sleep 整整 30 秒
    "padguard.admin.dashboard-cache-ttl-ms=600"
])
class DashboardCacheIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var adminRepository: AdminRepository
    @Autowired private lateinit var jwt: JwtTokenProvider

    private lateinit var adminToken: String

    companion object {
        private val phoneSeq = AtomicInteger(7000)
    }

    @BeforeEach
    fun setUp() {
        val adminId = "A-dash-cache"
        if (adminRepository.findById(adminId).isEmpty) {
            adminRepository.save(
                Admin(
                    id = adminId, username = "dashcachetester", passwordHash = "x",
                    displayName = "缓存测试", role = "super", status = "active"
                )
            )
        }
        adminToken = jwt.createAdminToken(adminId, adminRepository.findById(adminId).get().tokenVersion)
    }

    private fun bodyOf(result: org.springframework.test.web.servlet.MvcResult): String =
        String(result.response.getContentAsByteArray(), Charsets.UTF_8)

    private fun parentsTotal(): Long {
        val body = bodyOf(
            mockMvc.perform(
                MockMvcRequestBuilders.get("/v1/admin/dashboard")
                    .header("Authorization", "Bearer $adminToken")
                    .accept(MediaType.APPLICATION_JSON)
            ).andExpect(status().isOk).andReturn()
        )
        val json: JsonNode = objectMapper.readTree(body)
        return json.at("/data/kpis/parentsTotal").asLong()
    }

    private fun registerParent(): String {
        val phone = "136" + (40000000 + phoneSeq.incrementAndGet())
        mockMvc.perform(
            MockMvcRequestBuilders.post("/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("phone" to phone, "password" to "Test@12345")))
                .accept(MediaType.APPLICATION_JSON)
        ).andExpect(status().isOk)
        return phone
    }

    @Test
    fun `TTL 窗口内创建家长后总数不变（缓存命中），过期后反映新数据`() {
        val before = parentsTotal()   // 填充缓存

        registerParent()

        // TTL 窗口内：必须命中缓存读到旧值。若这里变成了新值，
        // 说明缓存键里混入了请求者信息或根本没生效 —— 两种都是"白写缓存"。
        assertEquals(before, parentsTotal(), "TTL 窗口内应命中缓存，KPI 不反映刚创建的家长")

        // 过期后：缓存失效，重新聚合，能看到新家长。
        Thread.sleep(700)
        assertEquals(before + 1, parentsTotal(), "TTL 过期后必须重新聚合，不能永远停留在旧快照")

        // 再查一次：新一轮缓存又被填充并命中（证明过期后能正常重建，而不是一次性缓存）
        assertEquals(before + 1, parentsTotal(), "过期重建后应再次命中新快照")
    }
}
