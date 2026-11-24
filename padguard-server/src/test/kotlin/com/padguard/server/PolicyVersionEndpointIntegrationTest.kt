package com.padguard.server

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.domain.Device
import com.padguard.server.domain.Policy
import com.padguard.server.domain.User
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.PolicyRepository
import com.padguard.server.repository.UserRepository
import com.padguard.server.security.JwtTokenProvider
import com.padguard.server.security.Passwords
import com.padguard.server.service.PolicyExtensionService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
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
import java.util.UUID

/**
 * 第二步 W3-1 新增的策略版本端点 —— 真实 Spring 容器 + MockMvc 集成回归。
 *
 * 单测 `PolicyVersionServiceTest` 用窄端口验证服务内部逻辑；本类补的是**只有真实容器才验得到的三件事**：
 * 1. 新端点挂在 `/v1` 下，确实经过 `ParentAuthInterceptor`（缺 token 401）；
 * 2. `DeviceOwnershipGuard` 在每个单设备端点、以及批量端点的**每一台设备**上生效（跨用户 403/2003）；
 * 3. **回滚行不会被 `PolicyExtensionService.ensureRebuilt` 覆盖** —— 这是 W3-1 最关键的架构约束：
 *    孩子端每次拉策略前都会触发子表自愈，若 ROLLBACK 行不跳过，家长端点"回滚成功"、
 *    设备下一次拉策略立刻被重建回当前配置，全程没有异常日志，只能在客诉里发现。
 *
 * 每个用例用独立 deviceId，避免回滚产生的新版本行污染其它用例的版本号断言。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("local")
@TestPropertySource(properties = [
    "padguard.storage.dir=build/test-uploads",
    "spring.datasource.url=jdbc:h2:mem:policyversionitest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=10000",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "padguard.jwt.secret=policy-version-itest-secret-key-32-bytes",
    "padguard.demo-accounts-enabled=false",
    "padguard.initial-password-file=target/pv-itest-admin-pwd.txt",
    "padguard.parent-initial-password-file=target/pv-itest-parent-pwd.txt",
    "padguard.base-url=http://localhost:8080",
    "padguard.admin.dashboard-cache-ttl-ms=0"
])
class PolicyVersionEndpointIntegrationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwt: JwtTokenProvider
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var deviceRepository: DeviceRepository
    @Autowired lateinit var policyRepository: PolicyRepository
    @Autowired lateinit var policyExtensionService: PolicyExtensionService
    @Autowired lateinit var objectMapper: ObjectMapper

    private lateinit var ownerToken: String
    private lateinit var otherToken: String
    private lateinit var deviceA: String
    private lateinit var deviceB: String

    private val ownerId = "U-pv-owner"
    private val otherId = "U-pv-other"

    @BeforeEach
    fun setUp() {
        if (userRepository.findById(ownerId).isEmpty) {
            userRepository.save(User(id = ownerId, phone = "13900007101", passwordHash = Passwords.encode("Test@12345"), status = "active"))
        }
        if (userRepository.findById(otherId).isEmpty) {
            userRepository.save(User(id = otherId, phone = "13900007102", passwordHash = Passwords.encode("Test@12345"), status = "active"))
        }
        ownerToken = "Bearer " + jwt.createAccessToken(ownerId, 0L)
        otherToken = "Bearer " + jwt.createAccessToken(otherId, 0L)

        val suffix = UUID.randomUUID().toString().take(8)
        deviceA = "DEV-PV-A-$suffix"   // 归属 owner
        deviceB = "DEV-PV-B-$suffix"   // 归属 other
        deviceRepository.save(Device(id = deviceA, userId = ownerId, name = "我的平板", deviceSn = "SN-$suffix-A", controlMode = "DO", onlineStatus = "OFFLINE"))
        deviceRepository.save(Device(id = deviceB, userId = otherId, name = "他人平板", deviceSn = "SN-$suffix-B", controlMode = "DO", onlineStatus = "OFFLINE"))

        // 两条可回滚的历史版本（v1 日限额 60、v2 日限额 120）
        savePolicy(deviceA, 1, """{"version":1,"deviceId":"$deviceA","dailyLimit":60}""")
        savePolicy(deviceA, 2, """{"version":2,"deviceId":"$deviceA","dailyLimit":120}""")
    }

    private fun savePolicy(device: String, version: Int, json: String, source: String = "NORMAL", rollbackOf: Int? = null) {
        policyRepository.save(
            Policy(
                id = UUID.randomUUID().toString(), deviceId = device, version = version,
                packageJson = json, scene = "FAMILY", source = source, rollbackOf = rollbackOf,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private fun jsonPost(path: String, token: String?, body: String) =
        MockMvcRequestBuilders.post(path)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)
            .apply { if (token != null) header("Authorization", token) }

    private fun latest(device: String): Policy =
        policyRepository.findFirstByDeviceIdOrderByVersionDesc(device)!!

    // ---------------- 鉴权与归属 ----------------

    @Test
    fun `versions endpoint without token is 401`() {
        val r = mockMvc.perform(MockMvcRequestBuilders.get("/v1/policies/$deviceA/versions")).andReturn()
        assertEquals(401, r.response.status, "策略版本端点必须挂在家长鉴权前缀下，实际=${r.response.status}")
    }

    @Test
    fun `cross user versions list is 403 DEVICE_NOT_OWNED`() {
        val r = mockMvc.perform(
            MockMvcRequestBuilders.get("/v1/policies/$deviceA/versions").header("Authorization", otherToken)
        ).andReturn()
        assertEquals(403, r.response.status, "实际=${r.response.status} ${r.response.contentAsString}")
        assertEquals(2003, objectMapper.readTree(r.response.contentAsString).at("/code").asInt())
    }

    @Test
    fun `cross user rollback is rejected and inserts no version`() {
        val before = policyRepository.findAll().count { it.deviceId == deviceA }
        val r = mockMvc.perform(jsonPost("/v1/policies/$deviceA/rollback", otherToken, """{"targetVersion":1}""")).andReturn()
        assertEquals(403, r.response.status, "实际=${r.response.status} ${r.response.contentAsString}")
        assertEquals(before, policyRepository.findAll().count { it.deviceId == deviceA }, "越权回滚不得写入任何策略行")
    }

    @Test
    fun `cross user schedule is rejected`() {
        val r = mockMvc.perform(
            MockMvcRequestBuilders.put("/v1/policies/$deviceA/schedule")
                .header("Authorization", otherToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"effectiveFrom":1700000000000,"effectiveTo":1700003600000}""")
        ).andReturn()
        assertEquals(403, r.response.status, "实际=${r.response.status} ${r.response.contentAsString}")
    }

    // ---------------- 批量通道的逐设备归属 ----------------

    @Test
    fun `batch publish checks ownership for every single device`() {
        val r = mockMvc.perform(
            jsonPost("/v1/policies/batch/publish", ownerToken, """{"deviceIds":["$deviceA","$deviceB"]}""")
        ).andReturn()
        assertEquals(200, r.response.status, "批量端点本身应返回逐设备结果，实际=${r.response.status} ${r.response.contentAsString}")
        val data = objectMapper.readTree(r.response.contentAsString).at("/data")
        assertEquals(2, data.at("/total").asInt())
        assertEquals(1, data.at("/succeeded").asInt(), "本人设备应成功，实际=${r.response.contentAsString}")
        assertEquals(1, data.at("/failed").asInt(), "他人设备必须单独失败，不能因为列表里有自己的设备就整批放行")
        val failed = data.at("/results").firstOrNull { it.at("/deviceId").asText() == deviceB }
        assertTrue(failed != null && !failed.at("/ok").asBoolean(), "他人设备结果应 ok=false")
    }

    @Test
    fun `batch command refuses non whitelisted high risk type`() {
        val r = mockMvc.perform(
            jsonPost("/v1/commands/batch", ownerToken, """{"deviceIds":["$deviceA"],"type":"SET_MANAGEMENT_ACTIVE"}""")
        ).andReturn()
        assertEquals(400, r.response.status, "高危指令不得进入批量通道，实际=${r.response.status} ${r.response.contentAsString}")
        assertEquals(1001, objectMapper.readTree(r.response.contentAsString).at("/code").asInt())
    }

    // ---------------- 回滚语义（端到端） ----------------

    @Test
    fun `rollback creates a higher version carrying the historical snapshot`() {
        val r = mockMvc.perform(jsonPost("/v1/policies/$deviceA/rollback", ownerToken, """{"targetVersion":1}""")).andReturn()
        assertEquals(200, r.response.status, "实际=${r.response.status} ${r.response.contentAsString}")
        val data = objectMapper.readTree(r.response.contentAsString).at("/data")
        assertTrue(data.at("/version").asInt() > 2, "回滚必须分配更高的新版本号，孩子端只接受版本递增")
        assertEquals(1, data.at("/rolledBackTo").asInt())
        assertTrue(data.at("/rollbackActive").asBoolean())

        val row = latest(deviceA)
        assertEquals("ROLLBACK", row.source)
        assertEquals(1, row.rollbackOf)
        assertEquals(60, objectMapper.readTree(row.packageJson).at("/dailyLimit").asInt(), "包体必须来自 v1 历史快照")
        assertEquals(data.at("/version").asInt(), objectMapper.readTree(row.packageJson).at("/version").asInt(), "包内 version 必须与新版本号一致")

        val list = mockMvc.perform(
            MockMvcRequestBuilders.get("/v1/policies/$deviceA/versions").header("Authorization", ownerToken)
        ).andReturn()
        assertTrue(objectMapper.readTree(list.response.contentAsString).at("/data/rollbackActive").asBoolean())
    }

    @Test
    fun `rollback is idempotent for the same target and window`() {
        val body = """{"targetVersion":1}"""
        val first = mockMvc.perform(jsonPost("/v1/policies/$deviceA/rollback", ownerToken, body)).andReturn()
        val second = mockMvc.perform(jsonPost("/v1/policies/$deviceA/rollback", ownerToken, body)).andReturn()
        val v1 = objectMapper.readTree(first.response.contentAsString).at("/data/version").asInt()
        val v2 = objectMapper.readTree(second.response.contentAsString).at("/data/version").asInt()
        assertEquals(v1, v2, "重复点击不应刷版本号，否则设备端对账会被打乱")
        assertTrue(objectMapper.readTree(second.response.contentAsString).at("/data/idempotent").asBoolean())
    }

    // ---------------- 最关键的架构约束 ----------------

    @Test
    fun `ensureRebuilt does not overwrite a rollback snapshot`() {
        // 造一条与任何子表都明显不一致的回滚快照；若自愈逻辑误触发，包体会被重建并新增版本。
        val snapshot = """{"version":9,"deviceId":"$deviceA","dailyLimit":999,"marker":"rollback-snapshot"}"""
        savePolicy(deviceA, 9, snapshot, source = "ROLLBACK", rollbackOf = 1)
        val rowCountBefore = policyRepository.findAll().count { it.deviceId == deviceA }

        policyExtensionService.ensureRebuilt(deviceA)

        val after = latest(deviceA)
        assertEquals(9, after.version, "回滚态下不得产生新版本")
        assertEquals(snapshot, after.packageJson, "回滚快照必须原样保留；被覆盖就意味着设备下次拉策略回滚失效")
        assertEquals(rowCountBefore, policyRepository.findAll().count { it.deviceId == deviceA }, "不得插入自愈行")
    }

    @Test
    fun `ensureRebuilt still self heals a normal drifted row`() {
        // 正向对照：NORMAL 行的自愈能力没有被「跳过回滚」的分支误伤。
        // 包体故意写成空对象，与子表重建结果必然不同。
        savePolicy(deviceA, 10, """{"version":10}""", source = "NORMAL")
        val before = latest(deviceA).packageJson

        policyExtensionService.ensureRebuilt(deviceA)

        val after = latest(deviceA)
        assertTrue(after.version >= 10, "实际=${after.version}")
        assertNotEquals(before, after.packageJson, "NORMAL 行发生 drift 时仍应触发自愈重建")
        assertEquals("NORMAL", after.source)
    }
}
