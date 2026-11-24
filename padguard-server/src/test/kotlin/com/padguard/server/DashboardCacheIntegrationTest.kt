package com.padguard.server

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.domain.Admin
import com.padguard.server.domain.Alert
import com.padguard.server.repository.AdminRepository
import com.padguard.server.repository.AlertRepository
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
    "padguard.storage.dir=build/test-uploads",
    // 集成测试会在几秒内为每个用例开一个家长账号；真实注册频控（默认 6 次/分钟/IP）
    // 是给生产拦批量建号的，不该干预用例本身——频控行为由 RateLimitTest 单测覆盖。
    "padguard.ratelimit.register=200",
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
    @Autowired private lateinit var alertRepository: AlertRepository

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

    private fun parentsTotal(): Long = dashboardField("/kpis/parentsTotal")

    private fun alertsOpen(): Long = dashboardField("/alertsOpen")

    private fun dashboardField(path: String): Long {
        val body = bodyOf(
            mockMvc.perform(
                MockMvcRequestBuilders.get("/v1/admin/dashboard")
                    .header("Authorization", "Bearer $adminToken")
                    .accept(MediaType.APPLICATION_JSON)
            ).andExpect(status().isOk).andReturn()
        )
        val json: JsonNode = objectMapper.readTree(body)
        return json.at("/data$path").asLong()
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

    /**
     * alertsOpen 口径回归：待处理 = ACTIVE + ACKNOWLEDGED。
     *
     * 后台同一屏有两个"待处理告警"数字：首页卡片（本接口）与铃铛角标
     * （告警列表接口的非 closed 计数）。原实现只数 ACTIVE，
     * 只要有一条"家长已确认但管理员未关单"的告警，两个数字就对不上；
     * ACKNOWLEDGED 在告警列表里仍提供"标记为已处理"按钮，属于后台待处理范畴，
     * CLOSED / RESOLVED 是终态（家长关闭 / 自愈关闭 / 后台关单），不计入。
     */
    @Test
    fun `alertsOpen 口径与铃铛一致 含 ACKNOWLEDGED 不含 CLOSED 与 RESOLVED`() {
        val now = System.currentTimeMillis()
        alertRepository.saveAll(
            listOf(
                alert("d-dash-a1", "ACTIVE", now),
                alert("d-dash-a2", "ACTIVE", now),
                alert("d-dash-a3", "ACKNOWLEDGED", now),
                alert("d-dash-c1", "CLOSED", now),
                alert("d-dash-c2", "RESOLVED", now)
            )
        )
        // 等 TTL 过期强制重新聚合：直接查会命中插入前的空快照
        Thread.sleep(700)
        assertEquals(3L, alertsOpen(), "待处理应 = ACTIVE(2) + ACKNOWLEDGED(1)，与铃铛角标口径一致")
        // 归还"缓存已过期"的冷启动状态：本用例的查询会填充共享缓存，
        // TTL 用例的时序假设（首查 miss 重建、builtAt 从零起算）依赖进入时缓存必已过期；
        // 否则其 before 命中本用例留下的陈旧时间戳，600ms 窗口被 registerParent 耗尽，
        // 第二次查询误判为缓存失效（本次跑挂过一回）。
        Thread.sleep(700)
    }

    private fun alert(id: String, status: String, now: Long) = Alert(
        id = id,
        deviceId = "d-dash",
        userId = null,
        level = "WARN",
        title = "口径测试告警",
        message = "口径回归用，无真实告警语义",
        category = "TEST",
        status = status,
        triggeredAt = now,
        acknowledgedAt = if (status == "ACKNOWLEDGED") now else null,
        resolvedAt = if (status == "CLOSED" || status == "RESOLVED") now else null
    )
}
