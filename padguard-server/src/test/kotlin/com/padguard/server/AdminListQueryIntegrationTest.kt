package com.padguard.server

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.domain.Admin
import com.padguard.server.repository.AdminRepository
import com.padguard.server.security.JwtTokenProvider
import org.junit.jupiter.api.Assertions.assertTrue
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
 * 后台家长/设备列表「下推数据库」的集成测试。
 *
 * ## 为什么必须有这一条
 * 这两个列表原本是 `findAll()` + 内存过滤，改成 JPQL 之后，
 * **单元测试用假仓储根本碰不到真正的 SQL**：JPQL 语法错误、参数类型推断失败
 * （`? is null` 在 PostgreSQL 上的经典坑）、`coalesce` 与 `like` 的方言差异，
 * 全部只在真实数据库上才暴露。而这类错误一旦上线，后台列表和搜索会整体不可用。
 *
 * H2 用 PostgreSQL 兼容模式跑，能在本地提前暴露绝大部分这类问题。
 * 因此这里打的是真实 HTTP + 真实 JPA，覆盖：
 * 1. 无条件分页（能否返回数据、total 是否正确）；
 * 2. 关键字搜索（大小写、昵称、手机号）；
 * 3. 状态过滤 + 套餐过滤的分支（这些条件在 JPQL 里是多段 or，最容易写错）；
 * 4. 设备列表的在线态过滤分支。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("local")
@TestPropertySource(properties = [
    "padguard.storage.dir=build/test-uploads",
    // 集成测试会在几秒内为每个用例开一个家长账号；真实注册频控（默认 6 次/分钟/IP）
    // 是给生产拦批量建号的，不该干预用例本身——频控行为由 RateLimitTest 单测覆盖。
    "padguard.ratelimit.register=200",
    "spring.datasource.url=jdbc:h2:mem:adminlist;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "padguard.jwt.secret=admin-list-test-secret-key-32-bytes-min",
    "padguard.demo-accounts-enabled=false",
    "padguard.initial-password-file=target/adminlist-admin-pwd.txt",
    "padguard.parent-initial-password-file=target/adminlist-parent-pwd.txt",
    "padguard.base-url=http://localhost:8080"
])
class AdminListQueryIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var adminRepository: AdminRepository
    @Autowired private lateinit var jwt: JwtTokenProvider

    private lateinit var adminToken: String

    companion object {
        /** 同 JUnit 5 每方法新建实例的坑：计数必须是静态的（详见 TokenRevocationIntegrationTest） */
        private val phoneSeq = AtomicInteger(2000)
    }

    @BeforeEach
    fun setUp() {
        val adminId = "A-list-test"
        if (adminRepository.findById(adminId).isEmpty) {
            adminRepository.save(
                Admin(
                    id = adminId, username = "listtester", passwordHash = "x",
                    displayName = "列表测试", role = "super", status = "active"
                )
            )
        }
        adminToken = jwt.createAdminToken(adminId, adminRepository.findById(adminId).get().tokenVersion)
    }

    /**
     * 按 UTF-8 解码响应体。
     *
     * 不能直接用 `response.contentAsString`：MockMvc 在响应头未显式带 charset 时按 ISO-8859-1 解码，
     * 中文昵称会变成乱码，断言"搜到了某位家长"会误判为失败 —— 排查方向会被带偏到 SQL 上。
     * 真实客户端（OkHttp / 浏览器）对 application/json 一律按 UTF-8 解析，故这里显式指定。
     */
    private fun bodyOf(result: org.springframework.test.web.servlet.MvcResult): String =
        String(result.response.getContentAsByteArray(), Charsets.UTF_8)

    private fun registerParent(nickname: String? = null): String {        val phone = "138" + (60000000 + phoneSeq.incrementAndGet())
        val payload = mutableMapOf<String, Any>("phone" to phone, "password" to "Test@12345")
        nickname?.let { payload["nickname"] = it }
        mockMvc.perform(
            MockMvcRequestBuilders.post("/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload))
                .accept(MediaType.APPLICATION_JSON)
        ).andExpect(status().isOk)
        return phone
    }

    @Test
    fun `parents list supports paging keyword status and plan filters`() {
        val phone = registerParent("搜索目标妈妈")
        registerParent("另一个家长")

        // 无条件分页
        val all = bodyOf(mockMvc.perform(
            MockMvcRequestBuilders.get("/v1/admin/parents")
                .param("page", "1").param("size", "10")
                .header("Authorization", "Bearer $adminToken")
                .accept(MediaType.APPLICATION_JSON)
        ).andExpect(status().isOk).andReturn())
        val allJson = objectMapper.readTree(all)
        assertTrue(allJson.at("/data/total").asInt() >= 2, "分页应返回 total，实际=$all")

        // 关键字（手机号后 6 位）
        val byPhone = bodyOf(mockMvc.perform(
            MockMvcRequestBuilders.get("/v1/admin/parents")
                .param("keyword", phone.takeLast(6))
                .header("Authorization", "Bearer $adminToken")
                .accept(MediaType.APPLICATION_JSON)
        ).andExpect(status().isOk).andReturn())
        assertTrue(byPhone.contains(phone), "按手机号应能搜到该家长，实际=$byPhone")

        // 关键字（中文昵称）—— 覆盖 lower() 与大小写不敏感比较在 H2 上的行为
        val byNick = bodyOf(mockMvc.perform(
            MockMvcRequestBuilders.get("/v1/admin/parents")
                .param("keyword", "搜索目标")
                .header("Authorization", "Bearer $adminToken")
                .accept(MediaType.APPLICATION_JSON)
        ).andExpect(status().isOk).andReturn())
        assertTrue(byNick.contains("搜索目标妈妈"), "按昵称应能搜到该家长，实际=$byNick")

        // 状态 + 套餐分支（每种组合都执行一次，确保 JPQL 的多段 or 不会在某个分支报错）
        listOf(
            "status" to "active",
            "status" to "suspended",
            "plan" to "free",
            "plan" to "monthly",
            "plan" to "yearly",
            "plan" to "permanent"
        ).forEach { (name, value) ->
            mockMvc.perform(
                MockMvcRequestBuilders.get("/v1/admin/parents")
                    .param(name, value)
                    .header("Authorization", "Bearer $adminToken")
                    .accept(MediaType.APPLICATION_JSON)
            ).andExpect(status().isOk)
        }
    }

    @Test
    fun `devices list supports paging online filter and keyword`() {
        // 先造一台设备：注册家长 → 孩子端按账号绑定
        val phone = registerParent()
        val bindBody = mapOf(
            "phone" to phone,
            "password" to "Test@12345",
            "req" to mapOf(
                "bindCode" to "ACCOUNT_BIND", "deviceSn" to "ADMINLIST-SN-1",
                "model" to "Pad", "brand" to "PadGuardTest", "controlMode" to "DO"
            )
        )
        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/device/bind-by-account")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(bindBody))
                .accept(MediaType.APPLICATION_JSON)
        ).andExpect(status().isOk)

        val all = bodyOf(mockMvc.perform(
            MockMvcRequestBuilders.get("/v1/admin/devices")
                .param("page", "1").param("size", "10")
                .header("Authorization", "Bearer $adminToken")
                .accept(MediaType.APPLICATION_JSON)
        ).andExpect(status().isOk).andReturn())
        assertTrue(all.contains("ADMINLIST-SN-1"), "设备列表应包含刚绑定的设备，实际=$all")

        // 在线/离线两个分支都要执行：JPQL 里 0/1 的取反条件最容易写漏 is null
        listOf("1", "0").forEach { mode ->
            mockMvc.perform(
                MockMvcRequestBuilders.get("/v1/admin/devices")
                    .param("online", mode)
                    .header("Authorization", "Bearer $adminToken")
                    .accept(MediaType.APPLICATION_JSON)
            ).andExpect(status().isOk)
        }

        // 关键字：按家长手机号搜设备（exists 子查询分支）
        val byPhone = bodyOf(mockMvc.perform(
            MockMvcRequestBuilders.get("/v1/admin/devices")
                .param("keyword", phone)
                .header("Authorization", "Bearer $adminToken")
                .accept(MediaType.APPLICATION_JSON)
        ).andExpect(status().isOk).andReturn())
        assertTrue(byPhone.contains("ADMINLIST-SN-1"), "按家长手机号应能搜到其设备，实际=$byPhone")
    }
}
