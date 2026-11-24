package com.padguard.server

import com.padguard.server.domain.OrderStatus
import com.padguard.server.domain.PaymentOrder
import com.padguard.server.domain.User
import com.padguard.server.repository.PaymentOrderRepository
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
 * 计费 / 订阅入口边界 & 异常测试（第 3 轮 · 其二：示例支付关闭 = 生产等价安全态）。
 *
 * 隔离 H2 库（billingsectest）。此类刻意关闭示例支付（mock-pay-enabled=false），
 * 让 ConfigurablePaymentVerifier 进入"fail closed"生产行为：未接入验签器的真实渠道一律拒绝。
 *
 * 覆盖公开回调 /notify 的反欺诈与健壮性：超大报文拒绝（防 OOM DoS）、非法 JSON 拒绝、
 * 未知订单 404、MANUAL 渠道单号拒绝（防拿后台单白嫖）、**未验签回调不得入账**（订单保持 PENDING、
 * 订阅不被开通）、以及示例支付关闭时 mock-pay 端点返回 400。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("local")
@TestPropertySource(properties = [
    "padguard.storage.dir=build/test-uploads",
    "spring.datasource.url=jdbc:h2:mem:billingsectest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "padguard.jwt.secret=integration-test-secret-key-32-bytes-minimum-ok",
    "padguard.demo-accounts-enabled=false",
    "padguard.initial-password-file=target/billsec-itest-admin-pwd.txt",
    "padguard.parent-initial-password-file=target/billsec-itest-parent-pwd.txt",
    "padguard.base-url=http://localhost:8080",
    "padguard.admin.dashboard-cache-ttl-ms=0",
    "padguard.billing.mock-pay-enabled=false"
])
class BillingSecurityBoundaryTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwt: JwtTokenProvider
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var paymentOrderRepository: PaymentOrderRepository
    @Autowired lateinit var objectMapper: ObjectMapper

    private lateinit var parentId: String
    private lateinit var parentToken: String

    @BeforeEach
    fun setUp() {
        parentId = "U-billsec-parent"
        if (userRepository.findById(parentId).isEmpty) {
            userRepository.save(
                User(id = parentId, phone = "13900002001", passwordHash = Passwords.encode("Test@12345"), status = "active")
            )
        }
        parentToken = jwt.createAccessToken(parentId, 0L)
    }

    /** 超大回调报文（>1MB）必须被拒绝，避免无限读入撑爆堆内存（DoS） */
    @Test
    fun `notify oversized body over 1MB is rejected with 400`() {
        val big = ByteArray(1_100_000) // 超过 MAX_NOTIFY_BODY_BYTES(1_048_576)
        val r = mockMvc.perform(
            MockMvcRequestBuilders.post("/v1/billing/notify").content(big)
        ).andReturn()
        assertEquals(400, r.response.status, "超大回调报文应 400，实际=${r.response.status}")
    }

    /** 非法 JSON 报文必须被拒绝（解析失败不得 500） */
    @Test
    fun `notify malformed json is rejected with 400`() {
        val r = mockMvc.perform(
            MockMvcRequestBuilders.post("/v1/billing/notify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("this is not json")
        ).andReturn()
        assertEquals(400, r.response.status, "非法 JSON 应 400，实际=${r.response.status}")
    }

    /** 未知订单号的回调：订单不存在，必须 404 而非静默 200 */
    @Test
    fun `notify for unknown order returns 404`() {
        val r = mockMvc.perform(
            MockMvcRequestBuilders.post("/v1/billing/notify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("orderNo" to "PGUNKNOWN0001")))
        ).andReturn()
        assertEquals(404, r.response.status, "未知订单回调应 404，实际=${r.response.status}")
        assertEquals(4003, objectMapper.readTree(r.response.contentAsString).at("/code").asInt(), "应为 ORDER_NOT_FOUND")
    }

    /** 后台代开通（MANUAL）渠道的单号走公开回调 = 有人拿后台单白嫖，必须 403 */
    @Test
    fun `notify for MANUAL channel order is forbidden 403`() {
        // MANUAL 渠道在 createOrder 中允许（绕过渠道可用性校验），但公开回调必须拒收
        val orderNo = createManualOrder()
        val r = mockMvc.perform(
            MockMvcRequestBuilders.post("/v1/billing/notify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("orderNo" to orderNo)))
        ).andReturn()
        assertEquals(403, r.response.status, "MANUAL 渠道公开回调应 403，实际=${r.response.status}")
    }

    /**
     * 反欺诈核心：示例支付关闭时，未验签的回调（无签名、渠道无验签器）必须被拒，
     * 订单保持 PENDING、订阅不被开通。此前 ORDER_STATE_INVALID(4004) 落到 HTTP 200，
     * 与"已被拒绝"语义矛盾；修复后应 400，且订单/订阅状态不变。
     */
    @Test
    fun `unverified notify on real channel order is rejected and does NOT activate subscription`() {
        val victimUserId = "U-billsec-victim"
        if (userRepository.findById(victimUserId).isEmpty) {
            userRepository.save(
                User(id = victimUserId, phone = "13900002002", passwordHash = Passwords.encode("Test@12345"), status = "active")
            )
        }
        val orderNo = "PGBILLSEC-R${System.nanoTime()}"
        val now = System.currentTimeMillis()
        paymentOrderRepository.save(
            PaymentOrder(
                id = orderNo, orderNo = orderNo, userId = victimUserId,
                plan = "MONTHLY", months = 1, amountFen = 3800,
                channel = "ALIPAY", status = OrderStatus.PENDING.name,
                createdAt = now, payDeadline = now + 30 * 60_000L
            )
        )

        val r = mockMvc.perform(
            MockMvcRequestBuilders.post("/v1/billing/notify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("orderNo" to orderNo)))
        ).andReturn()
        assertEquals(400, r.response.status, "未验签回调应 400（拒绝入账），实际=${r.response.status}")
        assertEquals(4004, objectMapper.readTree(r.response.contentAsString).at("/code").asInt(), "应为 ORDER_STATE_INVALID")

        // 订单仍 PENDING（未被支付），订阅未被开通
        val order = paymentOrderRepository.findByOrderNo(orderNo)
        assertEquals(OrderStatus.PENDING.name, order?.status, "未验签回调不得改变订单状态")
    }

    /** 示例支付关闭时，mock-pay 端点必须返回 400（生产严禁绕过收费） */
    @Test
    fun `mockPay endpoint is disabled when mock-pay is off returns 400`() {
        val orderNo = createManualOrder()
        val r = mockMvc.perform(
            MockMvcRequestBuilders.post("/v1/billing/orders/$orderNo/mock-pay")
                .header("Authorization", "Bearer $parentToken")
                .accept(MediaType.APPLICATION_JSON)
        ).andReturn()
        assertEquals(400, r.response.status, "示例支付关闭时 mock-pay 应 400，实际=${r.response.status}")
        assertEquals(1001, objectMapper.readTree(r.response.contentAsString).at("/code").asInt(), "应为 PARAM_ERROR")
    }

    private fun createManualOrder(): String {
        // MANUAL 渠道绕过渠道可用性校验，可在示例支付关闭时建单
        val r = mockMvc.perform(
            MockMvcRequestBuilders.post("/v1/billing/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("plan" to "MONTHLY", "channel" to "MANUAL")))
                .header("Authorization", "Bearer $parentToken")
                .accept(MediaType.APPLICATION_JSON)
        ).andExpect(status().isOk).andReturn()
        val orderNo = objectMapper.readTree(r.response.contentAsString).at("/data/orderNo").asText()
        assertTrue(orderNo.isNotBlank(), "MANUAL 订单号不得为空")
        return orderNo
    }
}
