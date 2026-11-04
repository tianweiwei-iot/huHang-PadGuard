package com.padguard.server

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.domain.Admin
import com.padguard.server.domain.AuditLog
import com.padguard.server.domain.OrderStatus
import com.padguard.server.domain.PaymentOrder
import com.padguard.server.domain.Plan
import com.padguard.server.repository.AdminRepository
import com.padguard.server.repository.AuditLogRepository
import com.padguard.server.repository.PaymentOrderRepository
import com.padguard.server.repository.UserRepository
import com.padguard.server.security.JwtTokenProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * 后台「聚合 / 列表」查询下推数据库的集成测试。
 *
 * ## 为什么必须有这一条
 * 本轮把仪表盘、订阅/订单列表、审计列表从"全表读进内存"改成了 `count` / `group by` /
 * `action in (...)` + 分页。**假仓储完全碰不到这些 SQL**：JPQL 语法、`coalesce` 的方言行为、
 * 集合参数 `in (?)` 的展开、`Page` 返回值的 count 查询，全部只在真库上才会暴露。
 *
 * 而这一轮改动里最危险的一处不是性能，是**语义**：
 * 仪表盘原来在内存里写 `(it.status ?: "active")` —— `status` 是后加的列，存量行为 NULL。
 * 若改成派生查询 `countByStatus("active")`，SQL 的 `status = 'active'` **不会命中 NULL**，
 * 于是看板会出现"家长总数 1000、正常 980、冻结 0"，差的 20 个既不算正常也不算冻结。
 * 这种错误只在有历史数据的库上出现，本地新库永远复现不了。
 * 所以这里用一条 JDBC 直改把某位家长置成 NULL，断言它仍然计入"正常"。
 *
 * 覆盖：
 * 1. 仪表盘 KPI 的自洽性 + NULL 状态口径；
 * 2. 审计按领域下推过滤（含未知领域返回空，而不是退化成全量）；
 * 3. 订阅 / 订单列表的 SQL 分页与"只补当前页家长"。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("local")
@TestPropertySource(properties = [
    "spring.datasource.url=jdbc:h2:mem:adminagg;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "padguard.jwt.secret=admin-agg-test-secret-key-32-bytes-min",
    "padguard.demo-accounts-enabled=false",
    "padguard.initial-password-file=target/adminagg-admin-pwd.txt",
    "padguard.parent-initial-password-file=target/adminagg-parent-pwd.txt",
    "padguard.base-url=http://localhost:8080",
    // 仪表盘缓存禁用：用例会改数据后再断言 KPI 变化，缓存会让第二次读到旧值
    "padguard.admin.dashboard-cache-ttl-ms=0"
])
class AdminAggregateIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var adminRepository: AdminRepository
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var auditLogRepository: AuditLogRepository
    @Autowired private lateinit var paymentOrderRepository: PaymentOrderRepository
    @Autowired private lateinit var jwt: JwtTokenProvider
    @Autowired private lateinit var jdbc: JdbcTemplate

    private lateinit var adminToken: String

    companion object {
        /** JUnit5 每个测试方法新建实例：计数器必须静态，否则三个用例会撞同一手机号 */
        private val phoneSeq = AtomicInteger(3000)
    }

    @BeforeEach
    fun setUp() {
        val adminId = "A-agg-test"
        if (adminRepository.findById(adminId).isEmpty) {
            adminRepository.save(
                Admin(
                    id = adminId, username = "aggtester", passwordHash = "x",
                    displayName = "聚合测试", role = "super", status = "active"
                )
            )
        }
        adminToken = jwt.createAdminToken(adminId, adminRepository.findById(adminId).get().tokenVersion)
    }

    private fun bodyOf(result: org.springframework.test.web.servlet.MvcResult): String =
        String(result.response.getContentAsByteArray(), Charsets.UTF_8)

    private fun get(path: String, vararg params: Pair<String, String>): JsonNode {
        var request = MockMvcRequestBuilders.get(path)
            .header("Authorization", "Bearer $adminToken")
            .accept(MediaType.APPLICATION_JSON)
        params.forEach { (k, v) -> request = request.param(k, v) }
        val body = bodyOf(mockMvc.perform(request).andExpect(status().isOk).andReturn())
        return objectMapper.readTree(body)
    }

    private fun registerParent(nickname: String? = null): String {
        val phone = "137" + (50000000 + phoneSeq.incrementAndGet())
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

    /** KPI 三元组（总数 / 正常 / 冻结） */
    private fun parentKpis(): Triple<Int, Int, Int> {
        val kpis = get("/v1/admin/dashboard").at("/data/kpis")
        return Triple(
            kpis.at("/parentsTotal").asInt(),
            kpis.at("/parentsActive").asInt(),
            kpis.at("/parentsSuspended").asInt()
        )
    }

    @Test
    fun `dashboard counts null status as active and stays self-consistent`() {
        val phone = registerParent("空状态家长")
        registerParent("第二位家长")

        val (totalBefore, activeBefore, suspendedBefore) = parentKpis()
        assertTrue(totalBefore >= 2, "至少应有两个家长，实际=$totalBefore")
        assertEquals(
            totalBefore, activeBefore + suspendedBefore,
            "正常 + 冻结 必须等于总数：status 是后加列，存量行为 NULL，任何一边漏算都会让看板自相矛盾"
        )

        // 把这位家长置成"历史数据"形态：status = NULL
        jdbc.update("update users set status = null where phone = ?", phone)

        val (totalAfter, activeAfter, suspendedAfter) = parentKpis()
        assertEquals(totalBefore, totalAfter, "置空状态不应改变家长总数")
        /*
         * 关键断言：把 status 改成 NULL 之后，"正常"的数量必须**不变**。
         *
         * - 用 coalesce(status,'active')：NULL 仍算正常 → 数量不变（本实现）；
         * - 用派生查询 countByStatus("active")：SQL 的 `status = 'active'` 不命中 NULL
         *   → 正常数 -1，而冻结数不变，于是 总数 ≠ 正常 + 冻结。
         *
         * 这条断言的方向很容易写反（直觉上"改成 NULL 就该少一个"），
         * 但语义是"NULL 是历史存量，按正常处理"，少一个才是 bug。
         */
        assertEquals(
            activeBefore, activeAfter,
            "status 为 NULL 的家长必须仍计入'正常' —— 若这里少 1，说明 SQL 的 = 'active' 漏掉了 NULL"
        )
        assertEquals(suspendedBefore, suspendedAfter, "置空状态不应影响冻结数")
    }

    @Test
    fun `dashboard revenue and plan distribution come from aggregates`() {
        val phone = registerParent("营收家长")
        val user = userRepository.findByPhone(phone)!!

        // 造一个月内的已支付订单 + 一个历史订单，验证窗口查询不会把窗口外的算进来
        val now = System.currentTimeMillis()
        paymentOrderRepository.save(
            PaymentOrder(
                id = UUID.randomUUID().toString(), orderNo = "AGG" + now, userId = user.id,
                plan = Plan.MONTHLY.key, months = 1, amountFen = 2990,
                status = OrderStatus.PAID.name, createdAt = now - 86_400_000L, payDeadline = now
            )
        )
        paymentOrderRepository.save(
            PaymentOrder(
                id = UUID.randomUUID().toString(), orderNo = "AGGOLD" + now, userId = user.id,
                plan = Plan.MONTHLY.key, months = 1, amountFen = 999_999,
                status = OrderStatus.PAID.name, createdAt = now - 400L * 86_400_000L, payDeadline = now
            )
        )

        val data = get("/v1/admin/dashboard").at("/data")
        val series = data.at("/revenueSeries")
        assertEquals(6, series.size(), "营收曲线应固定为近 6 个月")

        // 本月金额 = 窗口内订单之和；历史订单必须被排除
        val monthRevenue = data.at("/kpis/monthRevenue").asInt()
        assertTrue(monthRevenue in 2990..(2990 * 2), "本月营收应包含刚入账的 2990 分，实际=$monthRevenue")
        assertEquals(
            monthRevenue, series.last().asInt(),
            "KPI 的本月营收与曲线最后一点必须同源 —— 改造前两者分两次查全表，中间入账就会对不上"
        )

        // 订阅分组的计数之和应等于订阅总数（本次只造了 1 条手动开通之前的免费试用）
        val planDist = data.at("/planDist")
        assertTrue(planDist.isArray, "planDist 应为数组")

        // 区域分布：region 为 NULL 的家长归入"未填写"，不能整体丢行
        val regionDist = data.at("/regionDist")
        assertTrue(regionDist.isArray && regionDist.size() > 0, "区域分布至少应有'未填写'一项")
        var regionSum = 0
        regionDist.forEach { regionSum += it.at("/count").asInt() }
        assertEquals(
            data.at("/kpis/parentsTotal").asInt(), regionSum,
            "区域分布各桶之和应等于家长总数：group by 把 NULL 分到单独一桶，不能在映射时丢掉"
        )
    }

    @Test
    fun `audit list filters by area in sql and rejects unknown area`() {
        val phone = registerParent("被审计家长")
        val user = userRepository.findByPhone(phone)!!
        val stamp = System.currentTimeMillis()

        // 三个不同领域的动作，各造一条
        listOf(
            "PARENTS_SUSPEND" to "parents",
            "DEVICES_LOCK" to "devices",
            "BILLING_MANUAL" to "billing"
        ).forEachIndexed { i, (action, _) ->
            auditLogRepository.save(
                AuditLog(
                    id = "AGG-AUD-" + i + "-" + stamp, userId = user.id, action = action,
                    targetType = "parent", targetId = user.id, detail = "集成测试 $action",
                    result = "SUCCESS", ip = "127.0.0.1", createdAt = stamp + i
                )
            )
        }

        // 按领域筛：只应拿到该领域，且必须走 SQL 的 action in (...) 分支
        val parentsPage = get("/v1/admin/audit", "area" to "parents").at("/data")
        assertTrue(parentsPage.at("/total").asInt() >= 1, "parents 领域至少应有 1 条")
        parentsPage.at("/items").forEach { item ->
            assertEquals("parents", item.at("/area").asText(), "按领域筛选后不应混入其他领域：$item")
        }
        val actionSet = parentsPage.at("/items").map { it.at("/action").asText() }.toSet()
        assertTrue("PARENTS_SUSPEND" in actionSet, "应包含刚写入的 parents 动作，实际=$actionSet")
        assertTrue("DEVICES_LOCK" !in actionSet, "devices 动作不应出现在 parents 领域，实际=$actionSet")

        // 未知领域：必须返回空，不能退化成"不过滤"
        val unknown = get("/v1/admin/audit", "area" to "no-such-area").at("/data")
        assertEquals(0, unknown.at("/total").asInt(), "未知领域的 total 必须为 0，否则说明过滤被静默忽略")
        assertEquals(0, unknown.at("/items").size(), "未知领域不应返回任何条目")

        // 不筛领域时应能看到全部三类
        val all = get("/v1/admin/audit").at("/data/items").map { it.at("/action").asText() }.toSet()
        assertTrue(
            setOf("PARENTS_SUSPEND", "DEVICES_LOCK", "BILLING_MANUAL").all { it in all },
            "不筛选时三个领域都应出现，实际=$all"
        )
    }

    @Test
    fun `subscription and order lists page in sql and resolve parent info`() {
        val phone = registerParent("订阅家长")

        // 手动开通一条订阅（走真实业务路径，顺带覆盖 billing 分支）
        mockMvc.perform(
            MockMvcRequestBuilders.post("/v1/admin/billing/subscriptions/manual")
                .header("Authorization", "Bearer $adminToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("parentPhone" to phone, "plan" to "MONTHLY", "days" to 30)))
                .accept(MediaType.APPLICATION_JSON)
        ).andExpect(status().isOk)

        val subs = get("/v1/admin/billing/subscriptions", "status" to "active", "page" to "1", "size" to "10").at("/data")
        assertTrue(subs.at("/total").asInt() >= 1, "至少应有一条生效订阅")
        val found = subs.at("/items").any { it.at("/parentPhone").asText() == phone }
        assertTrue(found, "订阅列表应通过批量补数据解析出家长手机号，实际=${subs.at("/items")}")

        // 订单：插一条已支付订单，验证状态过滤 + 分页 + 家长解析
        val user = userRepository.findByPhone(phone)!!
        val now = System.currentTimeMillis()
        paymentOrderRepository.save(
            PaymentOrder(
                id = UUID.randomUUID().toString(), orderNo = "AGGLIST" + now, userId = user.id,
                plan = Plan.MONTHLY.key, months = 1, amountFen = 2990,
                status = OrderStatus.PAID.name, createdAt = now, payDeadline = now
            )
        )
        val orders = get("/v1/admin/billing/orders", "status" to "paid", "page" to "1", "size" to "10").at("/data")
        assertTrue(orders.at("/total").asInt() >= 1, "至少应有一条已支付订单")
        orders.at("/items").forEach { item ->
            assertEquals("paid", item.at("/status").asText(), "按 paid 过滤后不应混入其他状态：$item")
        }
        assertTrue(
            orders.at("/items").any { it.at("/parentPhone").asText() == phone },
            "订单列表应解析出家长手机号，实际=${orders.at("/items")}"
        )

        // 越界页：total 不变、items 为空（与内存分页口径一致）
        val outOfRange = get("/v1/admin/billing/orders", "page" to "999", "size" to "10").at("/data")
        assertEquals(0, outOfRange.at("/items").size(), "越界页应返回空列表")
        assertTrue(outOfRange.at("/total").asInt() >= 1, "越界页的 total 仍应是过滤后的总数")
    }
}
