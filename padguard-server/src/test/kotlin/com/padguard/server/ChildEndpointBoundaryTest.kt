package com.padguard.server

import com.padguard.server.domain.User
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.UserRepository
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
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * 孩子端（被管控端）端点边界 & 异常测试（Step 3/4 服务端深挖）。
 *
 * 覆盖四个最易被"隐藏 bug"击穿的入口：
 *  1. 账号绑定 `/api/v1/device/bind-by-account`（匿名可爆破面，含订阅/设备数闸门、频控、错误密码语义）
 *  2. 设备改名 `/api/v1/device/name`（设备名长度/空串校验）
 *  3. 心跳 `/api/v1/device/heartbeat`（畸形/越界字段的容错与数据完整性）
 *  4. 指令轮询 `/api/v1/device/commands` 与告警自愈 `/api/v1/device/alerts/resolve`（游标/白名单边界）
 *
 * 全部经真实 Spring 上下文（local profile + 独立 H2 内存库 `boundary`，与应用其它用例隔离），
 * 不打穿外部中间件（MQTT 在 local 关闭）。为避免共用内存频控桶污染其它用例，本类把四类入口频控阈值
 * 统一调高到 1000，仅验证"业务/协议异常"语义而非频控数值（频控数值由 [security.RateLimitTest] 覆盖）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("local")
@TestPropertySource(properties = [
    "padguard.storage.dir=build/test-uploads",
    "spring.datasource.url=jdbc:h2:mem:boundary;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "padguard.jwt.secret=integration-test-secret-key-32-bytes-minimum-ok",
    "padguard.demo-accounts-enabled=false",
    "padguard.initial-password-file=target/boundary-admin-pwd.txt",
    "padguard.parent-initial-password-file=target/boundary-parent-pwd.txt",
    "padguard.base-url=http://localhost:8080",
    "padguard.admin.dashboard-cache-ttl-ms=0",
    "padguard.ratelimit.login=1000",
    "padguard.ratelimit.admin-login=1000",
    "padguard.ratelimit.bind=1000",
    "padguard.ratelimit.bind-by-account=1000"
])
class ChildEndpointBoundaryTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var deviceRepository: DeviceRepository
    @Autowired lateinit var objectMapper: ObjectMapper

    private val parentPhone = "13900000999"
    private val parentPassword = "Test@12345"
    private lateinit var deviceId: String
    private lateinit var deviceToken: String

    @BeforeEach
    fun setUp() {
        if (userRepository.findById("U-boundary-parent").isEmpty) {
            userRepository.save(
                User(
                    id = "U-boundary-parent", phone = parentPhone,
                    passwordHash = Passwords.encode(parentPassword), status = "active"
                )
            )
        }
        // 绑定一次拿到真实 deviceId + deviceToken，供后续鉴权类端点复用
        val bind = doBind(parentPhone, parentPassword, "BOUNDARY_SN_01", "BOUNDARY_CODE_01")
        assertTrue(bind.response.status == 200, "前置绑定应成功，实际=${bind.response.status} ${bind.response.contentAsString}")
        val json = objectMapper.readTree(bind.response.contentAsString)
        deviceId = json.at("/data/deviceId").asText()
        deviceToken = json.at("/data/deviceToken").asText()
        assertNotNull(deviceId, "deviceId 不得为空")
        assertNotNull(deviceToken, "deviceToken 不得为空")
    }

    // ============ 1. 账号绑定 ============

    private fun doBind(phone: String, password: String, deviceSn: String, bindCode: String) =
        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/device/bind-by-account")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "phone" to phone, "password" to password,
                            "req" to mapOf("bindCode" to bindCode, "deviceSn" to deviceSn, "model" to "Pad")
                        )
                    )
                )
                .accept(MediaType.APPLICATION_JSON)
        ).andReturn()

    @Test
    fun `bind-by-account missing phone field returns 400 (deserialization before rate limit)`() {
        // 缺 phone：BindByAccountRequest.phone 无默认值 → JSON 反序列化失败 → 400 + ChildErr.PARAM_ERROR
        val r = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/device/bind-by-account")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("password" to parentPassword, "req" to mapOf("bindCode" to "X"))))
                .accept(MediaType.APPLICATION_JSON)
        ).andReturn()
        assertEquals(400, r.response.status, "缺 phone 应 400，实际=${r.response.status} ${r.response.contentAsString}")
        assertEquals(40001, objectMapper.readTree(r.response.contentAsString).at("/code").asInt(),
            "孩子端参数错误码应为 40001")
    }

    @Test
    fun `bind-by-account missing req field returns 400`() {
        val r = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/device/bind-by-account")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("phone" to parentPhone, "password" to parentPassword)))
                .accept(MediaType.APPLICATION_JSON)
        ).andReturn()
        assertEquals(400, r.response.status, "缺 req 应 400，实际=${r.response.status}")
    }

    @Test
    fun `bind-by-account non-json body returns 400`() {
        val r = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/device/bind-by-account")
                .contentType(MediaType.APPLICATION_JSON)
                .content("this is not json")
                .accept(MediaType.APPLICATION_JSON)
        ).andReturn()
        assertEquals(400, r.response.status, "非法 JSON 应 400，实际=${r.response.status}")
    }

    @Test
    fun `bind-by-account empty phone returns 401 (token invalid, not 500)`() {
        val r = doBind("", parentPassword, "BOUNDARY_SN_EMPTY", "X")
        assertEquals(401, r.response.status, "空手机号应 401，实际=${r.response.status} ${r.response.contentAsString}")
        assertEquals(40101, objectMapper.readTree(r.response.contentAsString).at("/code").asInt(),
            "空手机号错误码应为 TOKEN_INVALID=40101")
    }

    @Test
    fun `bind-by-account nonexistent phone returns 401`() {
        val r = doBind("13900000000", parentPassword, "BOUNDARY_SN_NOUSER", "X")
        assertEquals(401, r.response.status, "不存在的手机号应 401，实际=${r.response.status} ${r.response.contentAsString}")
    }

    @Test
    fun `bind-by-account wrong password returns 401`() {
        val r = doBind(parentPhone, "WrongPassword123", "BOUNDARY_SN_BADPW", "X")
        assertEquals(401, r.response.status, "错误密码应 401，实际=${r.response.status} ${r.response.contentAsString}")
    }

    @Test
    fun `bind-by-account phone with surrounding whitespace is trimmed and succeeds`() {
        // 服务端 bindChildByAccount 对 phone 做了 trim —— 验证该归一化对绑定成功生效
        val r = doBind("  $parentPhone  ", parentPassword, "BOUNDARY_SN_TRIM", "X")
        assertEquals(200, r.response.status, "带前后空格的手机号应归一化后成功，实际=${r.response.status} ${r.response.contentAsString}")
    }

    // ============ 2. 设备改名 ============

    private fun updateName(name: String) =
        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/device/name")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("name" to name)))
                .header("X-Device-Id", deviceId)
                .header("Authorization", "Bearer $deviceToken")
                .accept(MediaType.APPLICATION_JSON)
        ).andReturn()

    @Test
    fun `updateName missing name field returns 400`() {
        val r = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/device/name")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .header("X-Device-Id", deviceId)
                .header("Authorization", "Bearer $deviceToken")
                .accept(MediaType.APPLICATION_JSON)
        ).andReturn()
        assertEquals(400, r.response.status, "缺 name 应 400（反序列化失败），实际=${r.response.status}")
    }

    @Test
    fun `updateName empty returns 400`() {
        val r = updateName("")
        assertEquals(400, r.response.status, "空设备名应 400，实际=${r.response.status} ${r.response.contentAsString}")
        // 孩子端路径统一用 40xxx 码表（与 :112 的绑定参数错误一致）。
        // 旧断言写的家长码 1001 是漏改：sanitizeDeviceName 此前按 audience 抛错却固定用 ParentErr。
        assertEquals(40001, objectMapper.readTree(r.response.contentAsString).at("/code").asInt(),
            "孩子端参数错误码应为 40001，不该是家长端 1001")
    }

    @Test
    fun `updateName whitespace only returns 400`() {
        val r = updateName("     ")
        assertEquals(400, r.response.status, "纯空格设备名应 400，实际=${r.response.status}")
    }

    @Test
    fun `updateName 65 chars returns 400 (exceeds VARCHAR64)`() {
        val r = updateName("a".repeat(65))
        assertEquals(400, r.response.status, "65 字设备名应 400，实际=${r.response.status} ${r.response.contentAsString}")
    }

    @Test
    fun `updateName exactly 64 chars returns 200`() {
        val r = updateName("a".repeat(64))
        assertEquals(200, r.response.status, "64 字设备名应 200，实际=${r.response.status} ${r.response.contentAsString}")
    }

    @Test
    fun `updateName 200 chars returns 400`() {
        val r = updateName("a".repeat(200))
        assertEquals(400, r.response.status, "200 字设备名应 400（已被业务层拦截，不会落到数据库 500），实际=${r.response.status}")
    }

    @Test
    fun `updateName with emoji and special chars returns 200`() {
        val r = updateName("孩子平板📱<>&ok")
        assertEquals(200, r.response.status, "含 emoji/特殊字符的设备名应被接受（仅做长度/空校验），实际=${r.response.status} ${r.response.contentAsString}")
    }

    @Test
    fun `updateName without token returns 401`() {
        val r = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/device/name")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("name" to "x")))
                .accept(MediaType.APPLICATION_JSON)
        ).andReturn()
        assertEquals(401, r.response.status, "缺设备令牌应 401，实际=${r.response.status}")
    }

    // ============ 3. 心跳 ============

    private fun heartbeat(body: Any) =
        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/device/heartbeat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
                .header("X-Device-Id", deviceId)
                .header("Authorization", "Bearer $deviceToken")
                .accept(MediaType.APPLICATION_JSON)
        ).andReturn()

    @Test
    fun `heartbeat empty body returns 200 (resilient to empty payload)`() {
        val r = heartbeat(emptyMap<String, Any>())
        assertEquals(200, r.response.status, "空心跳体应 200（全部字段带默认），实际=${r.response.status} ${r.response.contentAsString}")
    }

    @Test
    fun `heartbeat non-numeric battery level is silently ignored and returns 200`() {
        // battery 是 Map<String,Any?>：非数值不会被 Jackson 拒绝（不会 400），由 as? Number 静默丢弃
        val r = heartbeat(mapOf("battery" to mapOf("level" to "high")))
        assertEquals(200, r.response.status, "非数值电量不应导致 500，应 200（静默忽略），实际=${r.response.status} ${r.response.contentAsString}")
    }

    @Test
    fun `heartbeat out-of-range battery level is clamped to 0-100 (regression B-01)`() {
        // 修复后：负数/超 100 电量应在入口钳到 [0,100]，不再原样落库（曾实测 -5 被写入 battery_level）
        val rLow = heartbeat(mapOf("battery" to mapOf("level" to -5)))
        assertEquals(200, rLow.response.status, "负电量应 200，实际=${rLow.response.status}")
        var dev = deviceRepository.findById(deviceId).orElse(null)
        assertNotNull(dev, "设备应存在")
        assertEquals(0, dev.batteryLevel, "负电量应被钳为 0，实际=${dev.batteryLevel}")

        val rHigh = heartbeat(mapOf("battery" to mapOf("level" to 999)))
        assertEquals(200, rHigh.response.status, "超量程电量应 200，实际=${rHigh.response.status}")
        dev = deviceRepository.findById(deviceId).orElse(null)
        assertEquals(100, dev.batteryLevel, "超量程电量应被钳为 100，实际=${dev.batteryLevel}")
    }

    @Test
    fun `heartbeat out-of-range location is clamped to valid geo bounds (regression B-01)`() {
        val r = heartbeat(mapOf("location" to mapOf("lat" to 999.0, "lng" to -999.0)))
        assertEquals(200, r.response.status, "畸形坐标应 200，实际=${r.response.status}")
        val dev = deviceRepository.findById(deviceId).orElse(null)
        assertNotNull(dev, "设备应存在")
        assertEquals(90.0, dev.latitude ?: Double.NaN, 0.001, "纬度应被钳到 [-90,90]，实际=${dev.latitude}")
        assertEquals(-180.0, dev.longitude ?: Double.NaN, 0.001, "经度应被钳到 [-180,180]，实际=${dev.longitude}")
    }

    // ============ 4. 指令轮询 / 告警自愈 ============

    private fun commands(since: Long) =
        mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/device/commands")
                .param("since", since.toString())
                .header("X-Device-Id", deviceId)
                .header("Authorization", "Bearer $deviceToken")
                .accept(MediaType.APPLICATION_JSON)
        ).andReturn()

    @Test
    fun `commands negative since returns 200 (cursor clamped to 0, no crash)`() {
        val r = commands(-100L)
        assertEquals(200, r.response.status, "负 since 应被钳制为 0 并返回 200，实际=${r.response.status} ${r.response.contentAsString}")
    }

    @Test
    fun `commands huge future since returns 200 (clock skew grace, no crash)`() {
        val r = commands(9_000_000_000_000_000L)
        assertEquals(200, r.response.status, "未来 since 应被容错钳制并返回 200，实际=${r.response.status}")
    }

    @Test
    fun `commands without token returns 401`() {
        val r = mockMvc.perform(
            MockMvcRequestBuilders.get("/api/v1/device/commands")
                .param("since", "0")
                .accept(MediaType.APPLICATION_JSON)
        ).andReturn()
        assertEquals(401, r.response.status, "缺设备令牌应 401，实际=${r.response.status}")
    }

    @Test
    fun `alerts resolve non-whitelist category returns 400`() {
        val r = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/device/alerts/resolve")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("categories" to listOf("ROOT_DETECTED"))))
                .header("X-Device-Id", deviceId)
                .header("Authorization", "Bearer $deviceToken")
                .accept(MediaType.APPLICATION_JSON)
        ).andReturn()
        assertEquals(400, r.response.status, "非白名单告警类别应 400，实际=${r.response.status} ${r.response.contentAsString}")
    }

    @Test
    fun `alerts resolve null category in list returns 400`() {
        val r = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/device/alerts/resolve")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("categories" to listOf<Any?>(null))))
                .header("X-Device-Id", deviceId)
                .header("Authorization", "Bearer $deviceToken")
                .accept(MediaType.APPLICATION_JSON)
        ).andReturn()
        assertEquals(400, r.response.status, "列表中的 null 类别应 400，实际=${r.response.status} ${r.response.contentAsString}")
    }

    @Test
    fun `alerts resolve whitelist category returns 200`() {
        val r = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/device/alerts/resolve")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("categories" to listOf("ACCESSIBILITY_DISABLED"))))
                .header("X-Device-Id", deviceId)
                .header("Authorization", "Bearer $deviceToken")
                .accept(MediaType.APPLICATION_JSON)
        ).andReturn()
        assertEquals(200, r.response.status, "白名单类别应 200，实际=${r.response.status} ${r.response.contentAsString}")
    }

    @Test
    fun `alerts resolve empty list returns 400 (regression B-02)`() {
        val r = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/device/alerts/resolve")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("categories" to emptyList<String>())))
                .header("X-Device-Id", deviceId)
                .header("Authorization", "Bearer $deviceToken")
                .accept(MediaType.APPLICATION_JSON)
        ).andReturn()
        // 修复后：空类别列表被显式拒绝（避免"无意义 200"误导端上），返回 400。
        assertEquals(400, r.response.status, "空类别列表应 400，实际=${r.response.status} ${r.response.contentAsString}")
    }

    // ============ 5. 配对码绑定（另一匿名入口） ============

    @Test
    fun `bind missing bindCode returns 400 (deserialization)`() {
        val r = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/device/bind")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .accept(MediaType.APPLICATION_JSON)
        ).andReturn()
        assertEquals(400, r.response.status, "缺 bindCode 应 400，实际=${r.response.status}")
    }

    @Test
    fun `bind empty bindCode returns 400 with BIND_CODE_EXPIRED (regression B-03)`() {
        // 修复后：无效/过期绑定码应映射到 HTTP 400（而非 200 + body.code），HTTP 语义纠正为"失败"。
        val r = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/device/bind")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("bindCode" to "")))
                .accept(MediaType.APPLICATION_JSON)
        ).andReturn()
        assertEquals(400, r.response.status, "空绑定码应 400（BIND_CODE_EXPIRED 映射），实际=${r.response.status} ${r.response.contentAsString}")
        assertEquals(40302, objectMapper.readTree(r.response.contentAsString).at("/code").asInt(),
            "body.code 应为 BIND_CODE_EXPIRED=40302")
    }

    // ============ 6. 孩子端上传类型闸门 ============

    @Test
    fun `child avatar upload rejects svg content type`() {
        // 孩子端曾完全信任客户端声明的 contentType；SVG 落盘后下载侧 image/* 通配会 inline，
        // 与 /admin 同源执行脚本。这里锁死设备令牌入口也必须走统一黑名单。
        val file = MockMultipartFile(
            "file", "evil.svg", "image/svg+xml",
            "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".toByteArray()
        )
        val r = mockMvc.perform(
            MockMvcRequestBuilders.multipart("/api/v1/device/avatar")
                .file(file)
                .header("X-Device-Id", deviceId)
                .header("Authorization", "Bearer $deviceToken")
                .accept(MediaType.APPLICATION_JSON)
        ).andReturn()
        assertEquals(400, r.response.status, "SVG 头像应 400，实际=${r.response.status} ${r.response.contentAsString}")
        assertEquals(40001, objectMapper.readTree(r.response.contentAsString).at("/code").asInt(),
            "body.code 应为 ChildErr.PARAM_ERROR=40001")
    }
}
