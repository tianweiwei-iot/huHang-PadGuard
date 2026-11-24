package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.common.SERVER_ZONE_ID
import com.padguard.server.domain.OrderStatus
import com.padguard.server.domain.PayChannel
import com.padguard.server.domain.PaymentOrder
import com.padguard.server.domain.Plan
import com.padguard.server.domain.SubStatus
import com.padguard.server.domain.Subscription
import com.padguard.server.domain.User
import com.padguard.server.dto.AdminManualSubscriptionRequest
import com.padguard.server.dto.PlanDto
import com.padguard.server.repository.PaymentOrderRepository
import com.padguard.server.repository.SubscriptionRepository
import com.padguard.server.repository.UserRepository
import com.padguard.server.security.AdminPermissionService
import com.padguard.server.security.WeChatRefundClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import java.time.LocalDate

/**
 * [AdminBillingService] 的钱/权限相关不变量。
 *
 * `AdminServiceTest` 用 fake 覆盖了「退款能标记」「手动开通能生效」这类主路径，
 * 这里补的是它**没有断言**的部分，也是最容易在重构中被无声改坏的部分：
 *
 * - 每个读操作首行必须是 `billing:read`，写操作必须是 `billing:manual` / `billing:refund`；
 * - 列表过滤与分页必须下推 DB、只按当前页批量查家长（否则运营翻页就把两张表读进堆）；
 * - 手动开通必须走悲观行锁，且续期基准是 `max(旧到期, now)`，年卡按自然月而非 360 天；
 * - 退款必须「先微信受理 → 再条件 UPDATE 标记」，顺序反了就是钱没退却显示已退的单边账；
 * - 标记失败 / 并发重复退款必须幂等收敛，且只有真正标记成功才审计与回收权益。
 *
 * 全部 mock 依赖，不加载 Spring 上下文。
 */
class AdminBillingServiceTest {

    private lateinit var subs: SubscriptionRepository
    private lateinit var orders: PaymentOrderRepository
    private lateinit var users: UserRepository
    private lateinit var billing: BillingService
    private lateinit var perm: AdminPermissionService
    private lateinit var audit: AuditService
    private lateinit var refundClient: WeChatRefundClient
    private lateinit var svc: AdminBillingService

    @BeforeEach
    fun setUp() {
        subs = mock(SubscriptionRepository::class.java)
        orders = mock(PaymentOrderRepository::class.java)
        users = mock(UserRepository::class.java)
        billing = mock(BillingService::class.java)
        perm = mock(AdminPermissionService::class.java)
        audit = mock(AuditService::class.java)
        refundClient = mock(WeChatRefundClient::class.java)
        svc = AdminBillingService(subs, orders, users, billing, perm, audit, refundClient)
    }

    private fun parent(id: String = "U1", phone: String = "13800000001") = User(
        id = id, phone = phone, passwordHash = "hash", nickname = "小张"
    )

    private fun subscription(
        userId: String = "U1",
        expiredAt: Long = 0L,
        plan: String = Plan.TRIAL.key,
        status: String = SubStatus.TRIAL.name
    ) = Subscription(
        id = "S1", userId = userId, plan = plan, status = status,
        startedAt = 1L, expiredAt = expiredAt, createdAt = 1L, updatedAt = 1L
    )

    private fun order(
        status: String = OrderStatus.PAID.name,
        channel: String = PayChannel.MANUAL.name,
        amountFen: Int = 3800
    ) = PaymentOrder(
        id = "P1", orderNo = "O1", userId = "U1", plan = Plan.MONTHLY.key,
        amountFen = amountFen, channel = channel, status = status, createdAt = 1L
    )

    /** 只从 mock 的调用记录里取审计动作与详情，避开 8 位默认参数 matcher 的坑。 */
    private fun auditRecords() = mockingDetails(audit).invocations.filter { it.method.name == "record" }

    /** 从调用记录里取下推到 DB 的状态过滤与分页参数，避免 eq/captor 在 Kotlin 非空参数上踩坑。 */
    private fun queryCalls(repo: Any, method: String) =
        mockingDetails(repo).invocations.filter { it.method.name == method }

    // ==================== 读权限闸门 ====================

    @Test
    fun `listPlans 先过 billing read 再取套餐`() {
        `when`(billing.plans()).thenReturn(
            listOf(PlanDto("MONTHLY", "月卡", 1, 3800, "¥38", 3))
        )
        val rows = svc.listPlans("A1")
        assertEquals(1, rows.size)
        assertEquals("MONTHLY", rows[0].id)
        assertEquals(3800, rows[0].priceFen)
        assertEquals(PLAN_FEATURES["MONTHLY"], rows[0].features)
        val io = inOrder(perm, billing)
        io.verify(perm).require("A1", "billing", "read")
        io.verify(billing).plans()
    }

    @Test
    fun `listPlans 权限被拒时不得读取套餐数据`() {
        doThrow(BizException(ParentErr.FORBIDDEN, "无权限", Audience.PARENT))
            .`when`(perm).require("A1", "billing", "read")
        assertThrows(BizException::class.java) { svc.listPlans("A1") }
        verifyNoInteractions(billing)
    }

    @Test
    fun `listSubscriptions 权限被拒时不得查询数据库`() {
        doThrow(BizException(ParentErr.FORBIDDEN, "无权限", Audience.PARENT))
            .`when`(perm).require("A1", "billing", "read")
        assertThrows(BizException::class.java) { svc.listSubscriptions("A1", null) }
        verifyNoInteractions(subs, users)
    }

    @Test
    fun `listOrders 权限被拒时不得查询数据库`() {
        doThrow(BizException(ParentErr.FORBIDDEN, "无权限", Audience.PARENT))
            .`when`(perm).require("A1", "billing", "read")
        assertThrows(BizException::class.java) { svc.listOrders("A1", null) }
        verifyNoInteractions(orders, users)
    }

    @Test
    fun `revenue 需要 billing read 且固定返回 12 个月桶`() {
        `when`(orders.findPaidAmountsSince(anyString(), anyLong())).thenReturn(emptyList())
        val result = svc.revenue("A1")
        assertEquals(12, result.size)
        verify(perm).require("A1", "billing", "read")
    }

    // ==================== 过滤 / 分页下推 ====================

    @Test
    fun `listSubscriptions 归一化状态并把 page 与 size 下推数据库`() {
        `when`(subs.searchSubscriptions(anyString(), any(Pageable::class.java) ?: PageRequest.of(0, 1)))
            .thenReturn(PageImpl(emptyList<Subscription>(), PageRequest.of(2, 10), 25))
        val r = svc.listSubscriptions("A1", "  ACTIVE  ", page = 3, size = 10)
        assertEquals(3, r.page)
        assertEquals(10, r.size)
        assertEquals(25, r.total)
        assertEquals(3, r.totalPages)
        val calls = queryCalls(subs, "searchSubscriptions")
        assertEquals(1, calls.size)
        assertEquals("active", calls[0].arguments[0])
        val pageable = calls[0].arguments[1] as Pageable
        assertEquals(2, pageable.pageNumber)
        assertEquals(10, pageable.pageSize)
    }

    @Test
    fun `listSubscriptions 非法分页被钳制且空状态归一为空串`() {
        `when`(subs.searchSubscriptions(anyString(), any(Pageable::class.java) ?: PageRequest.of(0, 1)))
            .thenReturn(PageImpl(emptyList<Subscription>(), PageRequest.of(0, 200), 0))
        val r = svc.listSubscriptions("A1", "   ", page = -5, size = 9999)
        assertEquals(1, r.page)
        assertEquals(200, r.size)
        assertEquals(1, r.totalPages)
        val calls = queryCalls(subs, "searchSubscriptions")
        assertEquals("", calls[0].arguments[0])
        val pageable = calls[0].arguments[1] as Pageable
        assertEquals(0, pageable.pageNumber)
        assertEquals(200, pageable.pageSize)
        verifyNoInteractions(users)
    }

    @Test
    fun `listSubscriptions size 为 0 时回落 20`() {
        `when`(subs.searchSubscriptions(anyString(), any(Pageable::class.java) ?: PageRequest.of(0, 1)))
            .thenReturn(PageImpl(emptyList<Subscription>(), PageRequest.of(0, 20), 0))
        val r = svc.listSubscriptions("A1", null, page = 1, size = 0)
        assertEquals(20, r.size)
    }

    @Test
    fun `listSubscriptions 只为当前页 userId 批量查家长`() {
        val a = subscription(userId = "U1")
        val b = subscription(userId = "U2")
        `when`(subs.searchSubscriptions(anyString(), any(Pageable::class.java) ?: PageRequest.of(0, 1)))
            .thenReturn(PageImpl(listOf(a, b), PageRequest.of(0, 20), 2))
        `when`(users.findAllById(listOf("U1", "U2"))).thenReturn(listOf(parent("U1")))
        val r = svc.listSubscriptions("A1", null)
        assertEquals("13800000001", r.items[0].parentPhone)
        // U2 查不到家长时必须回退展示 userId，而不是空白或抛错
        assertEquals("U2", r.items[1].parentPhone)
        assertEquals("小张", r.items[0].parentName)
        val calls = mockingDetails(users).invocations.filter { it.method.name == "findAllById" }
        assertEquals(1, calls.size)
        assertEquals(listOf("U1", "U2"), calls[0].arguments[0])
    }

    @Test
    fun `listSubscriptions 空结果不触发家长批量查询`() {
        `when`(subs.searchSubscriptions(anyString(), any(Pageable::class.java) ?: PageRequest.of(0, 1)))
            .thenReturn(PageImpl(emptyList<Subscription>(), PageRequest.of(0, 20), 0))
        svc.listSubscriptions("A1", null)
        verifyNoInteractions(users)
    }

    @Test
    fun `listOrders 下推分页并保留金额分与渠道`() {
        val o = order(status = OrderStatus.PAID.name, channel = PayChannel.WECHAT.name, amountFen = 9900)
        `when`(orders.searchOrders(anyString(), any(Pageable::class.java) ?: PageRequest.of(0, 1)))
            .thenReturn(PageImpl(listOf(o), PageRequest.of(1, 5), 8))
        val r = svc.listOrders("A1", " PAID ", page = 2, size = 5)
        assertEquals(2, r.totalPages)
        assertEquals(9900, r.items[0].amountFen)
        assertEquals("WECHAT", r.items[0].channel)
        assertEquals("paid", r.items[0].status)
        val calls = queryCalls(orders, "searchOrders")
        assertEquals("paid", calls[0].arguments[0])
        assertEquals(1, (calls[0].arguments[1] as Pageable).pageNumber)
    }

    // ==================== 手动开通 ====================

    @Test
    fun `manualActivate 需要 billing manual 权限`() {
        `when`(users.findByPhone("13800000001")).thenReturn(parent())
        `when`(subs.findByUserIdForUpdate("U1")).thenReturn(subscription())
        svc.manualActivate("A1", AdminManualSubscriptionRequest("13800000001", "MONTHLY", 30))
        verify(perm).require("A1", "billing", "manual")
        assertEquals(1, auditRecords().size)
    }

    @Test
    fun `manualActivate 家长不存在时报错且不写订阅`() {
        assertThrows(BizException::class.java) {
            svc.manualActivate("A1", AdminManualSubscriptionRequest("10000000000", "MONTHLY", 30))
        }
        verify(subs, never()).save(any(Subscription::class.java) ?: Subscription())
        assertEquals(0, auditRecords().size)
    }

    @Test
    fun `manualActivate 非法套餐与负天数均被拒绝`() {
        `when`(users.findByPhone("13800000001")).thenReturn(parent())
        assertThrows(BizException::class.java) {
            svc.manualActivate("A1", AdminManualSubscriptionRequest("13800000001", "NOT_A_PLAN", 30))
        }
        assertThrows(BizException::class.java) {
            svc.manualActivate("A1", AdminManualSubscriptionRequest("13800000001", "MONTHLY", -1))
        }
        verify(subs, never()).save(any(Subscription::class.java) ?: Subscription())
    }

    @Test
    fun `manualActivate 必须走悲观行锁读而不是无锁 findByUserId`() {
        val sub = subscription(expiredAt = 0L)
        `when`(users.findByPhone("13800000001")).thenReturn(parent())
        `when`(subs.findByUserIdForUpdate("U1")).thenReturn(sub)
        `when`(billing.deviceLimitOf(any(Plan::class.java) ?: Plan.TRIAL)).thenReturn(3)
        svc.manualActivate("A1", AdminManualSubscriptionRequest("13800000001", "MONTHLY", 30))
        verify(subs).findByUserIdForUpdate("U1")
        verify(subs, never()).findByUserId("U1")
        assertEquals(Plan.MONTHLY.key, sub.plan)
        assertEquals(SubStatus.ACTIVE.name, sub.status)
        assertEquals(3, sub.deviceLimit)
    }

    @Test
    fun `manualActivate 续期基准取旧到期与当前时间较大者 不缩短既有有效期`() {
        // 未来到期：base 必须是旧值，不能用 now 覆盖（否则一次补开反而把年卡砍短）
        val future = LocalDate.of(2027, 6, 1).atStartOfDay(SERVER_ZONE_ID).toInstant().toEpochMilli()
        val sub = subscription(expiredAt = future)
        `when`(users.findByPhone("13800000001")).thenReturn(parent())
        `when`(subs.findByUserIdForUpdate("U1")).thenReturn(sub)
        `when`(billing.deviceLimitOf(any(Plan::class.java) ?: Plan.TRIAL)).thenReturn(3)
        svc.manualActivate("A1", AdminManualSubscriptionRequest("13800000001", "MONTHLY", 45))
        assertEquals(future + 45 * 86_400_000L, sub.expiredAt)
    }

    @Test
    fun `manualActivate 年卡 360 天按 12 个自然月推进到月末最后一毫秒`() {
        val base = LocalDate.of(2027, 1, 15).atStartOfDay(SERVER_ZONE_ID).toInstant().toEpochMilli()
        val sub = subscription(expiredAt = base)
        `when`(users.findByPhone("13800000001")).thenReturn(parent())
        `when`(subs.findByUserIdForUpdate("U1")).thenReturn(sub)
        `when`(billing.deviceLimitOf(any(Plan::class.java) ?: Plan.TRIAL)).thenReturn(5)
        svc.manualActivate("A1", AdminManualSubscriptionRequest("13800000001", "YEARLY", 360))
        val expected = LocalDate.of(2028, 1, 16).atStartOfDay(SERVER_ZONE_ID).toInstant().toEpochMilli() - 1
        assertEquals(expected, sub.expiredAt)
        // 与旧口径「360 × 86400000」必须不同，否则说明自然月改造被回退
        assertTrue(sub.expiredAt != base + 360 * 86_400_000L)
    }

    @Test
    fun `manualActivate 非标准天数仍按天推进 保留运营灵活度`() {
        val base = LocalDate.of(2027, 1, 15).atStartOfDay(SERVER_ZONE_ID).toInstant().toEpochMilli()
        val sub = subscription(expiredAt = base)
        `when`(users.findByPhone("13800000001")).thenReturn(parent())
        `when`(subs.findByUserIdForUpdate("U1")).thenReturn(sub)
        svc.manualActivate("A1", AdminManualSubscriptionRequest("13800000001", "YEARLY", 45))
        assertEquals(base + 45 * 86_400_000L, sub.expiredAt)
    }

    @Test
    fun `manualActivate 无既有订阅时新建并写 BILLING_MANUAL 审计`() {
        `when`(users.findByPhone("13800000001")).thenReturn(parent())
        `when`(subs.findByUserIdForUpdate("U1")).thenReturn(null)
        `when`(billing.deviceLimitOf(any(Plan::class.java) ?: Plan.TRIAL)).thenReturn(3)
        val r = svc.manualActivate("A1", AdminManualSubscriptionRequest("13800000001", "MONTHLY", 30))
        assertEquals(true, r["ok"])
        assertEquals("active", r["status"])
        val cap = ArgumentCaptor.forClass(Subscription::class.java)
        verify(subs).save(cap.capture() ?: Subscription())
        assertEquals("U1", cap.value.userId)
        assertEquals(Plan.MONTHLY.key, cap.value.plan)
        assertEquals(SubStatus.ACTIVE.name, cap.value.status)
        assertEquals(1, auditRecords().size)
        assertEquals(AdminAudit.BILLING_MANUAL, auditRecords()[0].arguments[2])
        assertTrue((auditRecords()[0].arguments[5] as String).contains("13800000001"))
    }

    // ==================== 退款：顺序 / 幂等 / 权益回收 ====================

    @Test
    fun `refund 需要 billing refund 权限`() {
        `when`(orders.findByOrderNo("O1")).thenReturn(order(status = OrderStatus.REFUNDED.name))
        assertEquals(true, svc.refund("A1", "O1")["already"])
        verify(perm).require("A1", "billing", "refund")
    }

    @Test
    fun `refund 订单不存在抛 ORDER_NOT_FOUND 且不动微信`() {
        val ex = assertThrows(BizException::class.java) { svc.refund("A1", "NOPE") }
        assertEquals(ParentErr.ORDER_NOT_FOUND, ex.code)
        verifyNoInteractions(refundClient)
    }

    @Test
    fun `refund 非已支付订单被拒且不调微信不标记`() {
        `when`(orders.findByOrderNo("O1")).thenReturn(order(status = OrderStatus.PENDING.name))
        val ex = assertThrows(BizException::class.java) { svc.refund("A1", "O1") }
        assertEquals(ParentErr.PARAM_ERROR, ex.code)
        verifyNoInteractions(refundClient)
        verify(orders, never()).markRefundedIfPaid(anyString(), anyString(), anyString())
    }

    @Test
    fun `refund 已退款订单幂等返回且不做任何后续动作`() {
        `when`(orders.findByOrderNo("O1")).thenReturn(order(status = OrderStatus.REFUNDED.name))
        val r = svc.refund("A1", "O1")
        assertEquals(true, r["already"])
        verifyNoInteractions(refundClient)
        verify(orders, never()).markRefundedIfPaid(anyString(), anyString(), anyString())
        assertEquals(0, auditRecords().size)
        verify(billing, never()).revokeRightsOnRefund(anyString())
    }

    @Test
    fun `refund 微信渠道必须先在微信受理再标记本地订单`() {
        `when`(orders.findByOrderNo("O1")).thenReturn(order(channel = PayChannel.WECHAT.name))
        `when`(refundClient.isConfigured()).thenReturn(true)
        `when`(refundClient.createRefund(anyString(), anyString(), anyInt(), anyInt(), anyString()))
            .thenReturn(mapOf("refundId" to "RF-9", "status" to "PROCESSING"))
        `when`(orders.markRefundedIfPaid(anyString(), anyString(), anyString())).thenReturn(1)
        val r = svc.refund("A1", "O1")
        assertEquals("RF-9", r["refundId"])
        val io = inOrder(refundClient, orders, billing)
        io.verify(refundClient).createRefund("O1", "RO1", 3800, 3800, "用户退款")
        io.verify(orders).markRefundedIfPaid("O1", OrderStatus.REFUNDED.name, OrderStatus.PAID.name)
        io.verify(billing).revokeRightsOnRefund("U1")
        assertEquals(1, auditRecords().size)
        assertEquals(AdminAudit.BILLING_REFUND, auditRecords()[0].arguments[2])
        assertTrue((auditRecords()[0].arguments[5] as String).contains("RF-9"))
    }

    @Test
    fun `refund 微信受理失败时订单保持 PAID 且不审计不回收`() {
        `when`(orders.findByOrderNo("O1")).thenReturn(order(channel = PayChannel.WECHAT.name))
        `when`(refundClient.isConfigured()).thenReturn(true)
        `when`(refundClient.createRefund(anyString(), anyString(), anyInt(), anyInt(), anyString()))
            .thenReturn(null)
        assertThrows(BizException::class.java) { svc.refund("A1", "O1") }
        verify(orders, never()).markRefundedIfPaid(anyString(), anyString(), anyString())
        assertEquals(0, auditRecords().size)
        verify(billing, never()).revokeRightsOnRefund(anyString())
    }

    @Test
    fun `refund 条件 UPDATE 返回 0 表示并发已退款 幂等收敛`() {
        `when`(orders.findByOrderNo("O1")).thenReturn(order(channel = PayChannel.MANUAL.name))
        `when`(orders.markRefundedIfPaid(anyString(), anyString(), anyString())).thenReturn(0)
        val r = svc.refund("A1", "O1")
        assertEquals(true, r["already"])
        assertEquals(0, auditRecords().size)
        verify(billing, never()).revokeRightsOnRefund(anyString())
    }

    @Test
    fun `refund 非微信渠道跳过微信直接标记并回收权益`() {
        `when`(orders.findByOrderNo("O1")).thenReturn(order(channel = PayChannel.MANUAL.name))
        `when`(orders.markRefundedIfPaid(anyString(), anyString(), anyString())).thenReturn(1)
        val r = svc.refund("A1", "O1")
        assertEquals("", r["refundId"])
        verifyNoInteractions(refundClient)
        verify(billing).revokeRightsOnRefund("U1")
        assertEquals(1, auditRecords().size)
        assertFalse((auditRecords()[0].arguments[5] as String).contains("微信退款单"))
    }

    @Test
    fun `refund 微信渠道但未配置客户端时跳过 HTTP 仍可标记`() {
        `when`(orders.findByOrderNo("O1")).thenReturn(order(channel = PayChannel.WECHAT.name))
        `when`(refundClient.isConfigured()).thenReturn(false)
        `when`(orders.markRefundedIfPaid(anyString(), anyString(), anyString())).thenReturn(1)
        svc.refund("A1", "O1")
        verify(refundClient, never()).createRefund(anyString(), anyString(), anyInt(), anyInt(), anyString())
        verify(orders).markRefundedIfPaid("O1", OrderStatus.REFUNDED.name, OrderStatus.PAID.name)
    }
}
