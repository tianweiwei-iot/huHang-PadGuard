package com.padguard.server

import com.padguard.server.domain.Admin
import com.padguard.server.repository.AdminRepository
import com.padguard.server.repository.UserRepository
import com.padguard.server.security.JwtTokenProvider
import com.padguard.server.security.Passwords
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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

/**
 * 关键链路集成测试（P1-6）。
 *
 * 启动**真实**的 Spring 上下文（local profile + 内存 H2），经 MockMvc 打穿
 * 「HTTP → 拦截器鉴权 → RBAC 闸门 → Service → JPA」整条链路，覆盖四件最易回归的事：
 *
 * 1. 鉴权越权：后台端点无 token → 401；家长 token 冒充管理员 → 401；
 *    运营/审计角色缺 `billing:refund` → 403；super 通过闸门（非 403）。
 * 2. 绑定闭环：家长注册 → 孩子端按账号绑定 → 返回设备令牌（绑定关系落库）。
 * 3. 策略下发：绑定后的设备凭设备令牌拉取策略，服务端真实返回 PolicyPackage。
 * 4. 指令回执通道：设备长轮询 /commands 返回 policyVersion 与指令列表（HTTP 降级通道可用）。
 *
 * 不依赖任何外部中间件（MQTT 在 local profile 下关闭、Flyway 关闭、JWT 用测试固定密钥）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("local")
@TestPropertySource(properties = [
    "spring.datasource.url=jdbc:h2:mem:itest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "padguard.jwt.secret=integration-test-secret-key-32-bytes-minimum-ok",
    "padguard.demo-accounts-enabled=false",
    "padguard.initial-password-file=target/itest-admin-pwd.txt",
    "padguard.parent-initial-password-file=target/itest-parent-pwd.txt",
    "padguard.base-url=http://localhost:8080"
])
class CriticalLinkIntegrationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwt: JwtTokenProvider
    @Autowired lateinit var adminRepository: AdminRepository
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var objectMapper: ObjectMapper

    private lateinit var superToken: String
    private lateinit var auditToken: String
    private lateinit var parentToken: String
    private lateinit var parentId: String

    @BeforeEach
    fun setUp() {
        parentId = "U-itest-parent"
        // 家长账号（真实落库，供 ParentAuthInterceptor 校验存在性；也用于绑定闭环）
        if (userRepository.findById(parentId).isEmpty) {
            userRepository.save(
                com.padguard.server.domain.User(
                    id = parentId, phone = "13900000001",
                    passwordHash = Passwords.encode("Test@12345"), status = "active"
                )
            )
        }
        parentToken = jwt.createAccessToken(parentId)

        // 超级管理员（全权限）
        val superId = "A-itest-super"
        adminRepository.save(
            Admin(
                id = superId, username = "itsuper", passwordHash = Passwords.encode("Test@12345"),
                role = "super", status = "active", tokenVersion = 0
            )
        )
        superToken = jwt.createAdminToken(superId, 0)

        // 审计角色（缺 billing:refund，用于越权反例）
        val auditId = "A-itest-audit"
        adminRepository.save(
            Admin(
                id = auditId, username = "itaudit", passwordHash = Passwords.encode("Test@12345"),
                role = "audit", status = "active", tokenVersion = 0
            )
        )
        auditToken = jwt.createAdminToken(auditId, 0)
    }

    // ---------- 1. 鉴权越权 ----------

    @Test
    fun `admin endpoint without token is rejected with 401`() {
        mockMvc.perform(
            MockMvcRequestBuilders.get("/v1/admin/dashboard")
                .accept(MediaType.APPLICATION_JSON)
        ).andExpect(status().`is`(401))
    }

    @Test
    fun `parent token cannot access admin endpoint (scope mismatch) is 401`() {
        mockMvc.perform(
            MockMvcRequestBuilders.get("/v1/admin/dashboard")
                .header("Authorization", "Bearer $parentToken")
                .accept(MediaType.APPLICATION_JSON)
        ).andExpect(status().`is`(401))
    }

    @Test
    fun `audit role without billing refund privilege is forbidden 403`() {
        mockMvc.perform(
            MockMvcRequestBuilders.post("/v1/admin/billing/orders/NOSUCH/refund")
                .header("Authorization", "Bearer $auditToken")
                .accept(MediaType.APPLICATION_JSON)
        ).andExpect(status().`is`(403))
    }

    @Test
    fun `super admin passes the rbac gate on refund (not 403)`() {
        val result = mockMvc.perform(
            MockMvcRequestBuilders.post("/v1/admin/billing/orders/NOSUCH/refund")
                .header("Authorization", "Bearer $superToken")
                .accept(MediaType.APPLICATION_JSON)
        ).andReturn()
        // 闸门已通过：非 403（订单不存在会落到业务层返回 404，但 RBAC 不再拦截）
        assertTrue(result.response.status != 403, "super 应越过 RBAC 闸门，实际=${result.response.status}")
    }

    // ---------- 2. 绑定闭环 ----------

    @Test
    fun `full binding loop registers parent then binds device and returns device token`() {
        // 家长注册（公开端点）
        val reg = mockMvc.perform(
            MockMvcRequestBuilders.post("/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("phone" to "13900000002", "password" to "Test@12345")))
                .accept(MediaType.APPLICATION_JSON)
        ).andExpect(status().`isOk`).andReturn()
        assertTrue(reg.response.contentAsString.contains("13900000002") || reg.response.status == 200,
            "家长注册应成功")

        // 孩子端按账号绑定
        val bindBody = mapOf(
            "phone" to "13900000001",
            "password" to "Test@12345",
            "req" to mapOf(
                "bindCode" to "ACCOUNT_BIND",
                "deviceSn" to "ITESTSN001", "model" to "Pad", "brand" to "PadGuardTest",
                "controlMode" to "DO"
            )
        )
        val bind = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/device/bind-by-account")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(bindBody))
                .accept(MediaType.APPLICATION_JSON)
        ).andReturn()
        assertTrue(
            bind.response.status == 200,
            "bind-by-account 期望 200，实际=${bind.response.status}，body=${bind.response.contentAsString}"
        )

        val bindJson = objectMapper.readTree(bind.response.contentAsString)
        val deviceId = bindJson.at("/data/deviceId").asText()
        val deviceToken = bindJson.at("/data/deviceToken").asText()
        assertNotNull(deviceId, "绑定应返回 deviceId")
        assertNotNull(deviceToken, "绑定应返回 deviceToken")
        assertTrue(deviceId.isNotBlank() && deviceToken.isNotBlank(), "deviceId/deviceToken 不得为空")

        // 绑定的设备应真实落库并可被该账号查到
        val list = mockMvc.perform(
            MockMvcRequestBuilders.get("/v1/devices")
                .header("Authorization", "Bearer $parentToken")
                .accept(MediaType.APPLICATION_JSON)
        ).andExpect(status().isOk).andReturn()
        assertTrue(list.response.contentAsString.indexOf(deviceId) >= 0, "绑定后的设备应出现在家长设备列表")

        // ---------- 3. 策略下发 ----------
        val policy = mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/device/policy")
                .param("currentVersion", "0")
                .header("X-Device-Id", deviceId)
                .header("Authorization", "Bearer $deviceToken")
                .accept(MediaType.APPLICATION_JSON)
        ).andExpect(status().`isOk`).andReturn()
        assertTrue(policy.response.contentAsString.isNotBlank(), "设备应拉取到策略包")

        // ---------- 4. 指令回执通道（HTTP 降级长轮询） ----------
        val commands = mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/device/commands")
                .param("since", "0")
                .header("X-Device-Id", deviceId)
                .header("Authorization", "Bearer $deviceToken")
                .accept(MediaType.APPLICATION_JSON)
        ).andExpect(status().`isOk`).andReturn()
        val cmdJson = objectMapper.readTree(commands.response.contentAsString)
        val policyVersion = cmdJson.at("/data/policyVersion").asInt(-1)
        assertTrue(policyVersion >= 0, "指令轮询响应应携带非负 policyVersion（实际=$policyVersion）")
    }
}
