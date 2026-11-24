package com.padguard.server

import com.padguard.server.domain.Device
import com.padguard.server.domain.User
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.UserRepository
import com.padguard.server.security.JwtTokenProvider
import com.padguard.server.security.Passwords
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
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
 * 家长端受保护接口 —— 设备归属越权（IDOR）边界 & 异常测试（第 4 轮 · 其一）。
 *
 * 隔离 H2 库（parentownertest）。
 *
 * 历史背景：GeofenceService 曾"漏抄"归属校验，任意登录家长可读写他人设备的实时位置、
 * 轨迹、电子围栏（[com.padguard.server.security.DeviceOwnershipGuard] 注释）。
 * 现已收敛到统一的 requireOwned。本类用"另一名家长 token 访问 A 的设备"的矩阵，
 * 实证所有 {deviceId} 类接口都返回 403(DEVICE_NOT_OWNED)，把这道致命越权面锁成回归基线。
 *
 * 覆盖：
 * - 缺鉴权 → 401；
 * - 跨用户访问设备详情/解绑/改名/锁屏/定位/截屏/围栏/轨迹/统计/工单 → 一律 403(2003)；
 * - 设备归属者本人访问 → 200（正向对照）；
 * - 归属者访问不存在的设备 → 404(2001)；
 * - 空 deviceId 路径 → 404。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("local")
@TestPropertySource(properties = [
    "padguard.storage.dir=build/test-uploads",
    "spring.datasource.url=jdbc:h2:mem:parentownertest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "padguard.jwt.secret=integration-test-secret-key-32-bytes-minimum-ok",
    "padguard.demo-accounts-enabled=false",
    "padguard.initial-password-file=target/powner-itest-admin-pwd.txt",
    "padguard.parent-initial-password-file=target/powner-itest-parent-pwd.txt",
    "padguard.base-url=http://localhost:8080",
    "padguard.admin.dashboard-cache-ttl-ms=0"
])
class ParentEndpointOwnershipBoundaryTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwt: JwtTokenProvider
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var deviceRepository: DeviceRepository
    @Autowired lateinit var objectMapper: ObjectMapper

    private lateinit var ownerId: String
    private lateinit var ownerToken: String
    private lateinit var otherId: String
    private lateinit var otherToken: String
    private lateinit var deviceId: String

    @BeforeEach
    fun setUp() {
        ownerId = "U-owner-A"
        if (userRepository.findById(ownerId).isEmpty) {
            userRepository.save(User(id = ownerId, phone = "13900003001", passwordHash = Passwords.encode("Test@12345"), status = "active"))
        }
        ownerToken = jwt.createAccessToken(ownerId, 0L)

        otherId = "U-owner-B"
        if (userRepository.findById(otherId).isEmpty) {
            userRepository.save(User(id = otherId, phone = "13900003002", passwordHash = Passwords.encode("Test@12345"), status = "active"))
        }
        otherToken = jwt.createAccessToken(otherId, 0L)

        // 直接落一台归属 owner 的设备（绕过绑定流，聚焦归属校验本身）
        deviceId = "DEV-owner-A-001"
        deviceRepository.save(
            Device(id = deviceId, userId = ownerId, name = "孩子的Pad", deviceSn = "SN-A-001", controlMode = "DO", onlineStatus = "OFFLINE")
        )
    }

    private fun auth(token: String) = "Bearer $token"

    @Test
    fun `guarded parent endpoint without token is 401`() {
        val r = mockMvc.perform(
            MockMvcRequestBuilders.get("/v1/devices/$deviceId")
                .accept(MediaType.APPLICATION_JSON)
        ).andReturn()
        assertEquals(401, r.response.status, "缺鉴权应 401，实际=${r.response.status}")
    }

    @Test
    fun `owner can read own device detail 200 (positive control)`() {
        val r = mockMvc.perform(
            MockMvcRequestBuilders.get("/v1/devices/$deviceId")
                .header("Authorization", auth(ownerToken))
                .accept(MediaType.APPLICATION_JSON)
        ).andReturn()
        assertEquals(200, r.response.status, "归属者应能读取自己设备，实际=${r.response.status} ${r.response.contentAsString}")
    }

    @Test
    fun `owner can read own device location 200 (positive control)`() {
        val r = mockMvc.perform(
            MockMvcRequestBuilders.get("/v1/monitor/$deviceId/location")
                .header("Authorization", auth(ownerToken))
                .accept(MediaType.APPLICATION_JSON)
        ).andReturn()
        assertEquals(200, r.response.status, "归属者应能读取自己设备定位，实际=${r.response.status}")
    }

    @Test
    fun `owner accessing non-existent device is 404`() {
        val r = mockMvc.perform(
            MockMvcRequestBuilders.get("/v1/devices/NOSUCHDEVICE")
                .header("Authorization", auth(ownerToken))
                .accept(MediaType.APPLICATION_JSON)
        ).andReturn()
        assertEquals(404, r.response.status, "不存在设备应 404，实际=${r.response.status}")
        assertTrue(
            objectMapper.readTree(r.response.contentAsString).at("/code").asInt() == 2001,
            "应为 DEVICE_NOT_FOUND(2001)，实际=${r.response.contentAsString}"
        )
    }

    @Test
    fun `empty deviceId path is 404`() {
        val r = mockMvc.perform(
            MockMvcRequestBuilders.get("/v1/devices/")
                .header("Authorization", auth(ownerToken))
                .accept(MediaType.APPLICATION_JSON)
        ).andReturn()
        // 空路径无对应映射 → 404（NoResourceFoundException 已映射），不得 500
        assertTrue(r.response.status == 404 || r.response.status == 405, "空 deviceId 路径应 404/405，实际=${r.response.status}")
    }

    // ---------------- 跨用户越权矩阵（核心） ----------------

    data class EndpointSpec(val method: String, val path: String, val body: String? = null)

    private val crossUserSpecs = listOf(
        // ---- 原始 11 端点（第 4 轮基线）----
        EndpointSpec("GET", "/v1/devices/{dev}"),
        EndpointSpec("POST", "/v1/devices/{dev}/unbind"),
        EndpointSpec("PUT", "/v1/devices/{dev}/name", """{"name":"Hacked"}"""),
        EndpointSpec("POST", "/v1/policies/{dev}/lock", "{}"),
        EndpointSpec("GET", "/v1/monitor/{dev}/location"),
        EndpointSpec("GET", "/v1/monitor/{dev}/screenshots"),
        EndpointSpec("POST", "/v1/monitor/{dev}/screenshot"),
        EndpointSpec("GET", "/v1/location/{dev}/current"),
        EndpointSpec("GET", "/v1/location/{dev}/geofences"),
        EndpointSpec("GET", "/v1/statistics/{dev}/today"),
        EndpointSpec("GET", "/v1/devices/{dev}/unlock-tickets"),
        // ---- R40 扩展：覆盖其余 service 的归属链路 ----
        // 策略扩展（PolicyExtensionService）
        EndpointSpec("GET", "/v1/policies/{dev}/apps"),
        EndpointSpec("GET", "/v1/policies/{dev}/apps/list-policy"),
        EndpointSpec("GET", "/v1/policies/{dev}/time-restrictions"),
        EndpointSpec("GET", "/v1/policies/{dev}/eye-protection"),
        EndpointSpec("GET", "/v1/policies/{dev}/daily-limit"),
        EndpointSpec("GET", "/v1/policies/{dev}/web"),
        EndpointSpec("GET", "/v1/policies/{dev}/tablet-usage-settings"),
        EndpointSpec("GET", "/v1/policies/{dev}/system-lock-relaxed"),
        EndpointSpec("GET", "/v1/policies/{dev}/minor-mode"),
        // 设备详情 / 日志 / 分组
        EndpointSpec("GET", "/v1/devices/{dev}/logs?type=NOTIFICATION_COLLECTED"),
        EndpointSpec("GET", "/v1/devices/{dev}/agreement"),
        // 策略指令（PolicyController，body 均 required=false）
        EndpointSpec("POST", "/v1/policies/{dev}/unlock", "{}"),
        EndpointSpec("POST", "/v1/policies/{dev}/management-active", "{}"),
        EndpointSpec("POST", "/v1/policies/{dev}/reveal-console"),
        EndpointSpec("POST", "/v1/policies/{dev}/temp-unlock", """{"durationMinutes":10}"""),
        EndpointSpec("POST", "/v1/policies/{dev}/watermark", "{}"),
        EndpointSpec("POST", "/v1/policies/{dev}/minor-mode", """{"enabled":false}"""),
        // 监控 / 定位 / 消息
        EndpointSpec("GET", "/v1/monitor/{dev}/screen-settings"),
        EndpointSpec("GET", "/v1/location/{dev}/track"),
        EndpointSpec("GET", "/v1/messages/{dev}/history"),
        EndpointSpec("POST", "/v1/messages/{dev}/publish", """{"content":"x"}"""),
        // 统计族
        EndpointSpec("GET", "/v1/statistics/{dev}/usage"),
        EndpointSpec("GET", "/v1/statistics/{dev}/violations"),
        EndpointSpec("GET", "/v1/statistics/{dev}/export"),
        // 工单族（UnlockService / UsageDelayService）
        EndpointSpec("GET", "/v1/devices/{dev}/usage-delay/tickets"),
        EndpointSpec("POST", "/v1/devices/{dev}/usage-delay/tickets/T001/dismiss"),
        EndpointSpec("POST", "/v1/devices/{dev}/unlock-tickets/T001/dismiss"),
        // 使用会话 / 版本
        EndpointSpec("GET", "/v1/policies/{dev}/usage-session"),
        EndpointSpec("GET", "/v1/policies/{dev}/versions")
    )

    @Test
    fun `cross-user access to another parents device is forbidden 403 on every endpoint`() {
        val failures = mutableListOf<String>()
        for (spec in crossUserSpecs) {
            val path = spec.path.replace("{dev}", deviceId)
            val builder = when (spec.method) {
                "GET" -> MockMvcRequestBuilders.get(path)
                "POST" -> MockMvcRequestBuilders.post(path)
                "PUT" -> MockMvcRequestBuilders.put(path)
                else -> throw IllegalStateException("unsupported method ${spec.method}")
            }
            builder.header("Authorization", auth(otherToken)).accept(MediaType.APPLICATION_JSON)
            if (spec.body != null) {
                builder.contentType(MediaType.APPLICATION_JSON).content(spec.body)
            }
            val r = mockMvc.perform(builder).andReturn()
            val status = r.response.status
            val code = runCatching { objectMapper.readTree(r.response.contentAsString).at("/code").asInt() }.getOrDefault(-1)
            if (status != 403 || code != 2003) {
                failures.add("${spec.method} $path -> status=$status code=$code body=${r.response.contentAsString.take(200)}")
            }
        }
        assertTrue(failures.isEmpty(), "存在未做归属校验的接口（IDOR 越权面）：\n" + failures.joinToString("\n"))
    }
}
