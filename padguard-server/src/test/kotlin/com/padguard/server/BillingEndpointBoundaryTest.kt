package com.padguard.server

import com.padguard.server.domain.Subscription
import com.padguard.server.domain.SubStatus
import com.padguard.server.domain.User
import com.padguard.server.repository.PaymentOrderRepository
import com.padguard.server.repository.SubscriptionRepository
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
 * 计费 / 订阅入口边界 & 异常测试（第 3 轮 · 其一半：示例支付开启）。
 *
 * 隔离 H2 库（billingtest），不与 CriticalLinkIntegrationTest 的 itest 库交叉；
 * 频控阈值沿用生产默认，本类不触发匿名频控（下单/支付走已登录家长端）。
 *
 * 覆盖：套餐查询、下单正向、金额正确性、mockPay 全链路开通、幂等（防重复续费）、
 * 订单归属越权、非法/越权套餐（PERMANENT/TRIAL 白嫖后门）、缺参、未知套餐、
 * 订阅过期闸门（SUBSCRIPTION_REQUIRED 必须落到 403 而非 200）。
 *
 * 注意：本类开启示例支付（mock-pay-enabled=true），因此公开回调 /notify 在设计上放行所有报文，
 * 不再在此类验证"未验签回调被拒"（见 BillingSecurityBoundaryTest）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("local")
@TestPropertySource(properties = [
    "padguard.storage.dir=build/test-uploads",
    "spring.datasource.url=jdbc:h2:mem:billingtest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "padguard.jwt.secret=integration-test-secret-key-32-bytes-minimum-ok",
    "padguard.demo-accounts-enabled=false",
    "padguard.initial-password-file=target/billing-itest-admin-pwd.txt",
    "padguard.parent-initial-password-file=target/billing-itest-parent-pwd.txt",
    "padguard.base-url=http://localhost:8080",
    "padguard.admin.dashboard-cache-ttl-ms=0",
    "padguard.billing.mock-pay-enabled=true"
])
class BillingEndpointBoundaryTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwt: JwtTokenProvider
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var subscriptionRepository: SubscriptionRepository
    @Autowired lateinit var paymentOrderRepository: PaymentOrderRepository
    @Autowired lateinit var objectMapper: ObjectMapper

    private lateinit var parentId: String
    private lateinit var parentToken: String
    private lateinit var otherParentId: String
    private lateinit var otherParentToken: String

    @BeforeEach
    fun setUp() {
        parentId = "U-bill-parent"
        ensureUser(parentId, "13900001001")
        parentToken = jwt.createAccessToken(parentId, 0L)

        otherParentId = "U-bill-other"
        ensureUser(otherParentId, "13900001002")
        otherParentToken = jwt.createAccessToken(otherParentId, 0L)
    }

    private fun ensureUser(id: String, phone: String) {
        if (userRepository.findById(id).isEmpty) {
            userRepository.save(
                User(id = id, phone = phone, passwordHash = Passwords.encode("Test@12345"), status = "active")
            )
        }
    }

    private fun auth(token: String) = "Bearer $token"

    // ---------------- 正向功能 ----------------

    @Test
    fun `plans endpoint returns purchasable plans with correct price`() {
        val r = mockMvc.perform(
            MockMvcRequestBuilders.get("/v1/billing/plans")
                .header("Authorization", auth(parentToken))
                .accept(MediaType.APPLICATION_JSON)
        ).andExpect(status().isOk).andReturn()
        val body = objectMapper.readTree(r.response.contentAsString)
        val monthly = body.at("/data").firstOrNull { it.at("/key").asText() == "MONTHLY" }
        assertNotNull(monthly, "套餐列表应含 MONTHLY")
        assertEquals(3800, monthly!!.at("/amountFen").asInt(), "MONTHLY 金额应为配置价 3800 分（无 DB 覆盖价）")
    }

    @Test
    fun `subscription before purchase is TRIAL and active`() {
        val r = mockMvc.perform(
            MockMvcRequestBuilders.get("/v1/billing/subscription")
                .header("Authorization", auth(parentToken))
                .accept(MediaType.APPLICATION_JSON)
        ).andExpect(status().isOk).andReturn()
        val sub = objectMapper.readTree(r.response.contentAsString).at("/data")
        assertEquals("TRIAL", sub.at("/status").asText(), "未付费应为试用")
        assertTrue(sub.at("/active").asBoolean(), "试用期内 active 应为 true")
    }

    @Test
    fun `createOrder happy path returns orderNo and correct amount`() {
        val r = performCreateOrder("MONTHLY", "ALIPAY")
        assertEquals(200, r.response.status, "下单应 200，body=${r.response.contentAsString}")
        val data = objectMapper.readTree(r.response.contentAsString).at("/data")
        val orderNo = data.at("/orderNo").asText()
        assertTrue(orderNo.startsWith("PG"), "商户单号应以 PG 开头，实际=$orderNo")
        assertEquals(3800, data.at("/amountFen").asInt(), "金额应为 3800 分")
        assertEquals("ALIPAY", data.at("/channel").asText())
    }

    @Test
    fun `orders list contains the created order`() {
        val orderNo = createOrderAndReturnNo("MONTHLY", "ALIPAY")
        val r = mockMvc.perform(
            MockMvcRequestBuilders.get("/v1/billing/orders")
                .header("Authorization", auth(parentToken))
                .accept(MediaType.APPLICATION_JSON)
        ).andExpect(status().isOk).andReturn()
        assertTrue(
            r.response.contentAsString.contains(orderNo),
            "订单列表应含刚创建的订单 $orderNo"
        )
    }

    @Test
    fun `mockPay full chain activates subscription and marks order PAID`() {
        val orderNo = createOrderAndReturnNo("MONTHLY", "ALIPAY")

        val before = subscriptionRepository.findByUserId(parentId)
        val beforeExpire = before?.expiredAt ?: 0L

        val mp = mockMvc.perform(
            MockMvcRequestBuilders.post("/v1/billing/orders/$orderNo/mock-pay")
                .header("Authorization", auth(parentToken))
                .accept(MediaType.APPLICATION_JSON)
        ).andExpect(status().isOk).andReturn()
        assertEquals(200, mp.response.status, "mockPay 应 200，body=${mp.response.contentAsString}")

        // 订阅变为 ACTIVE / paid
        val subNow = mockMvc.perform(
            MockMvcRequestBuilders.get("/v1/billing/subscription")
                .header("Authorization", auth(parentToken))
                .accept(MediaType.APPLICATION_JSON)
        ).andExpect(status().isOk).andReturn()
        val subJson = objectMapper.readTree(subNow.response.contentAsString).at("/data")
        assertEquals("ACTIVE", subJson.at("/status").asText(), "付费后应 ACTIVE")
        assertTrue(subJson.at("/paid").asBoolean(), "paid 应为 true")
        assertTrue(subJson.at("/expiredAt").asLong() > beforeExpire, "到期时间应被延长")

        // 订单变为 PAID
        val os = mockMvc.perform(
            MockMvcRequestBuilders.get("/v1/billing/orders/$orderNo")
                .header("Authorization", auth(parentToken))
                .accept(MediaType.APPLICATION_JSON)
        ).andExpect(status().isOk).andReturn()
        assertEquals("PAID", objectMapper.readTree(os.response.contentAsString).at("/data/status").asText(), "订单应 PAID")
    }

    @Test
    fun `mockPay is idempotent and does NOT double-extend`() {
        val orderNo = createOrderAndReturnNo("MONTHLY", "ALIPAY")
        mockMvc.perform(
            MockMvcRequestBuilders.post("/v1/billing/orders/$orderNo/mock-pay")
                .header("Authorization", auth(parentToken))
                .accept(MediaType.APPLICATION_JSON)
        ).andExpect(status().isOk)
        val expireAfterFirst = subscriptionRepository.findByUserId(parentId)!!.expiredAt

        // 第二次调用（重复回调）：订单已 PAID，应被幂等忽略，到期时间不变
        mockMvc.perform(
            MockMvcRequestBuilders.post("/v1/billing/orders/$orderNo/mock-pay")
                .header("Authorization", auth(parentToken))
                .accept(MediaType.APPLICATION_JSON)
        ).andExpect(status().isOk)
        val expireAfterSecond = subscriptionRepository.findByUserId(parentId)!!.expiredAt
        assertEquals(expireAfterFirst, expireAfterSecond, "重复 mockPay 不得二次延长订阅（防重复续费资损）")
    }

    // ---------------- 边界 & 异常 ----------------

    @Test
    fun `createOrder missing plan field is rejected with 400`() {
        val r = mockMvc.perform(
            MockMvcRequestBuilders.post("/v1/billing/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}") // 缺 plan（无默认值 → 反序列化失败）
                .header("Authorization", auth(parentToken))
                .accept(MediaType.APPLICATION_JSON)
        ).andReturn()
        assertEquals(400, r.response.status, "缺必需字段应 400（报文不可读），实际=${r.response.status}")
    }

    @Test
    fun `createOrder with unknown plan is rejected with 400`() {
        val r = performCreateOrder("NONEXISTENT", "ALIPAY")
        assertEquals(400, r.response.status, "未知套餐应 400，body=${r.response.contentAsString}")
        assertEquals(1001, objectMapper.readTree(r.response.contentAsString).at("/code").asInt(), "应为 PARAM_ERROR")
    }

    @Test
    fun `createOrder PERMANENT plan is rejected (anti-fraud backdoor)`() {
        // PERMANENT 若可下单 + mockPay 即白嫖永久会员，曾是绕过收费后门，必须拦截
        val r = performCreateOrder("PERMANENT", "ALIPAY")
        assertEquals(400, r.response.status, "永久会员不得经收银台下单，实际=${r.response.status}")
    }

    @Test
    fun `createOrder TRIAL plan is rejected`() {
        val r = performCreateOrder("TRIAL", "ALIPAY")
        assertEquals(400, r.response.status, "试用无需下单，应 400，实际=${r.response.status}")
    }

    @Test
    fun `createOrder null channel is rejected with 400`() {
        val r = mockMvc.perform(
            MockMvcRequestBuilders.post("/v1/billing/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("plan" to "MONTHLY"))) // 缺 channel
                .header("Authorization", auth(parentToken))
                .accept(MediaType.APPLICATION_JSON)
        ).andReturn()
        assertEquals(400, r.response.status, "缺渠道应 400，实际=${r.response.status}")
    }

    @Test
    fun `mockPay non-existent order returns 404`() {
        val r = mockMvc.perform(
            MockMvcRequestBuilders.post("/v1/billing/orders/NOSUCHORDER/mock-pay")
                .header("Authorization", auth(parentToken))
                .accept(MediaType.APPLICATION_JSON)
        ).andReturn()
        assertEquals(404, r.response.status, "订单不存在应 404，实际=${r.response.status}")
        assertEquals(4003, objectMapper.readTree(r.response.contentAsString).at("/code").asInt(), "应为 ORDER_NOT_FOUND")
    }

    @Test
    fun `orderStatusOf non-existent order returns 404`() {
        val r = mockMvc.perform(
            MockMvcRequestBuilders.get("/v1/billing/orders/NOSUCHORDER")
                .header("Authorization", auth(parentToken))
                .accept(MediaType.APPLICATION_JSON)
        ).andReturn()
        assertEquals(404, r.response.status, "订单不存在应 404，实际=${r.response.status}")
    }

    @Test
    fun `mockPay on another users order is forbidden 403 (ownership)`() {
        val orderNo = createOrderAndReturnNo("MONTHLY", "ALIPAY")
        // 用"另一个家长"的 token 去支付属于 parentId 的订单
        val r = mockMvc.perform(
            MockMvcRequestBuilders.post("/v1/billing/orders/$orderNo/mock-pay")
                .header("Authorization", auth(otherParentToken))
                .accept(MediaType.APPLICATION_JSON)
        ).andReturn()
        assertEquals(403, r.response.status, "越权支付他人订单应 403，实际=${r.response.status}")
        assertEquals(2003, objectMapper.readTree(r.response.contentAsString).at("/code").asInt(), "应为 DEVICE_NOT_OWNED")
    }

    @Test
    fun `orderStatusOf on another users order is forbidden 403 (ownership)`() {
        val orderNo = createOrderAndReturnNo("MONTHLY", "ALIPAY")
        val r = mockMvc.perform(
            MockMvcRequestBuilders.get("/v1/billing/orders/$orderNo")
                .header("Authorization", auth(otherParentToken))
                .accept(MediaType.APPLICATION_JSON)
        ).andReturn()
        assertEquals(403, r.response.status, "越权查他人订单应 403，实际=${r.response.status}")
    }

    @Test
    fun `expired subscription blocks guarded parent endpoint with 403 (not 200)`() {
        // 订阅过期闸门 SUBSCRIPTION_REQUIRED(4001) 此前落到 HTTP 200（与已修的 2003 同类 bug）：
        // 家长端 api.js 以 HTTP 2xx 判成败，会把"订阅已过期"误判为操作成功。修复后应返回 403。
        val expUserId = "U-bill-expired"
        ensureUser(expUserId, "13900001003")
        // 直接写入一条已过期订阅（绕过 statusOf 的惰性建试用）
        subscriptionRepository.save(
            Subscription(
                id = "S-bill-expired", userId = expUserId, plan = "TRIAL",
                status = SubStatus.EXPIRED.name, deviceLimit = 3,
                startedAt = 0, expiredAt = 1, createdAt = 0, updatedAt = 0
            )
        )
        val expToken = jwt.createAccessToken(expUserId, 0L)
        val r = mockMvc.perform(
            MockMvcRequestBuilders.get("/v1/devices")
                .header("Authorization", auth(expToken))
                .accept(MediaType.APPLICATION_JSON)
        ).andReturn()
        assertEquals(403, r.response.status, "订阅过期应 403（而非 200 误判成功），实际=${r.response.status}")
        assertEquals(4001, objectMapper.readTree(r.response.contentAsString).at("/code").asInt(), "应为 SUBSCRIPTION_REQUIRED")
    }

    // ---------------- helpers ----------------

    private fun performCreateOrder(plan: String, channel: String?) = mockMvc.perform(
        MockMvcRequestBuilders.post("/v1/billing/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(mapOf("plan" to plan, "channel" to channel)))
            .header("Authorization", auth(parentToken))
            .accept(MediaType.APPLICATION_JSON)
    ).andReturn()

    private fun createOrderAndReturnNo(plan: String, channel: String): String {
        val r = performCreateOrder(plan, channel)
        assertEquals(200, r.response.status, "下单应 200，body=${r.response.contentAsString}")
        return objectMapper.readTree(r.response.contentAsString).at("/data/orderNo").asText()
    }
}
