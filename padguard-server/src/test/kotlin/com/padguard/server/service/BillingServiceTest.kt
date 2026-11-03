package com.padguard.server.service

import com.padguard.server.common.BizException
import com.padguard.server.common.ChildErr
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.Device
import com.padguard.server.domain.OrderStatus
import com.padguard.server.domain.PayChannel
import com.padguard.server.domain.PaymentOrder
import com.padguard.server.domain.Plan
import com.padguard.server.domain.SubStatus
import com.padguard.server.domain.Subscription
import com.padguard.server.domain.User
import com.padguard.server.dto.CreateOrderRequest
import com.padguard.server.dto.PayRequest
import com.padguard.server.repository.UserRepository
import com.padguard.server.security.ConfigurablePaymentVerifier
import com.padguard.server.security.PaymentVerifier
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.PaymentOrderRepository
import com.padguard.server.repository.SubscriptionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Pageable
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 订阅与计费的单元测试。
 *
 * 这是"付费才能用"这条商业规则的唯一把关处，必须先测住：
 * 漏判一处就等于开了一个永久免费后门，而且不报错、只能靠对账发现；
 * 反过来误判（把已付费用户拦住）会立刻引发投诉。两个方向的代价都很高。
 */
class BillingServiceTest {

    /** 同事为 BillingService 增加账号状态闸门后的用户仓储内存 fake */
    private class FakeUserRepo : FakeJpaRepository<User, String>(), UserRepository {
        override val table = LinkedHashMap<String, User>()
        override fun idOf(e: User) = e.id
        override fun existsByPhone(phone: String) = table.values.any { it.phone == phone }
        override fun findByPhone(phone: String): User? = table.values.firstOrNull { it.phone == phone }
        override fun countByStatus(status: String) = table.values.count { (it.status ?: "active") == status }.toLong()
        override fun findByIdForUpdate(id: String): User? = table[id]
        override fun searchParents(
            status: String,
            keyword: String,
            kw: String,
            planMode: String,
            pageable: org.springframework.data.domain.Pageable
        ): org.springframework.data.domain.Page<User> =
            org.springframework.data.domain.PageImpl(emptyList(), pageable, 0)
    }

    private class FakeSubscriptionRepo : FakeJpaRepository<Subscription, String>(), SubscriptionRepository {
        override val table = LinkedHashMap<String, Subscription>()
        override fun idOf(entity: Subscription) = entity.id
        override fun findByUserId(userId: String): Subscription? =
            table.values.firstOrNull { it.userId == userId }
        override fun countByStatus(status: String): Long = 0L
        override fun findAllByOrderByExpiredAtDesc(): List<Subscription> = emptyList()
        override fun findByUserIdForUpdate(userId: String): Subscription? = findByUserId(userId)
        override fun findByUserIdIn(userIds: Collection<String>): List<Subscription> =
            table.values.filter { it.userId in userIds }
    }

    private class FakeOrderRepo : FakeJpaRepository<PaymentOrder, String>(), PaymentOrderRepository {
        override val table = LinkedHashMap<String, PaymentOrder>()
        override fun idOf(entity: PaymentOrder) = entity.id
        override fun findByOrderNo(orderNo: String): PaymentOrder? =
            table.values.firstOrNull { it.orderNo == orderNo }

        override fun findByUserIdOrderByCreatedAtDesc(userId: String, pageable: Pageable): List<PaymentOrder> =
            table.values.filter { it.userId == userId }
                .sortedByDescending { it.createdAt }
                .take(pageable.pageSize)

        override fun findByUserIdAndStatus(userId: String, status: String): List<PaymentOrder> =
            table.values.filter { it.userId == userId && it.status == status }
        override fun findAllByOrderByCreatedAtDesc(): List<PaymentOrder> = emptyList()
        // 模拟条件 UPDATE 的原子抢占语义：仅 PENDING → PAID 才命中 1 行
        override fun markRefundedIfPaid(orderNo: String, refundedStatus: String, paidStatus: String): Int {
            val o = table.values.firstOrNull { it.orderNo == orderNo } ?: return 0
            if (o.status != paidStatus) return 0
            o.status = refundedStatus
            return 1
        }
        override fun claimTimeoutClose(orderNo: String, now: Long): Int {
            val o = table.values.firstOrNull { it.orderNo == orderNo } ?: return 0
            if (o.status != "PENDING" || o.payDeadline >= now) return 0
            o.status = "CLOSED"
            return 1
        }
        override fun claimPendingOrder(
            orderNo: String, paidStatus: String, pendingStatus: String, paidAt: Long, txId: String?
        ): Int {
            val order = findByOrderNo(orderNo) ?: return 0
            if (order.status != pendingStatus) return 0
            order.status = paidStatus
            order.paidAt = paidAt
            order.transactionId = txId
            return 1
        }
    }

    /** 设备台账：BillingService 只关心数量，无需构造完整 Device 实体。 */
    private class FakeDeviceRepo : FakeJpaRepository<Device, String>(), DeviceRepository {
        override val table = LinkedHashMap<String, Device>()
        override fun idOf(entity: Device) = entity.id
        override fun countGroupByUserIds(userIds: Collection<String>): List<Array<Any>> =
            table.values.filter { it.userId in userIds }.groupBy { it.userId }
                .map { arrayOf(it.key, it.value.size.toLong()) as Array<Any> }
        override fun findByIdForUpdate(id: String): Device? = table[id]
        override fun searchDevices(keyword: String, kw: String, onlineMode: String, staleCut: Long, pageable: org.springframework.data.domain.Pageable): org.springframework.data.domain.Page<Device> {
            var all = table.values.toList()
            if (onlineMode == "1") all = all.filter { it.onlineStatus == "ONLINE" && (it.lastOnlineAt ?: 0) >= staleCut }
            if (onlineMode == "0") all = all.filter { !(it.onlineStatus == "ONLINE" && (it.lastOnlineAt ?: 0) >= staleCut) }
            val sorted = all.sortedByDescending { it.createdAt }
            val from = pageable.offset.toInt()
            val to = minOf(from + pageable.pageSize, sorted.size)
            val content = if (from >= sorted.size) emptyList<Device>() else sorted.subList(from, to)
            return org.springframework.data.domain.PageImpl(content, pageable, sorted.size.toLong())
        }
        val counts = HashMap<String, Int>()
        override fun countByUserId(userId: String): Long = (counts[userId] ?: 0).toLong()
        override fun findByUserId(userId: String): List<Device> = emptyList()
        override fun findByDeviceSn(deviceSn: String): List<Device> = emptyList()
        override fun countByOnlineStatus(onlineStatus: String): Long = 0L
        override fun findByOnlineStatus(onlineStatus: String): List<Device> = emptyList()
        override fun findByOnlineStatusAndLastOnlineAtLessThan(onlineStatus: String, ts: Long): List<Device> = emptyList()
        override fun findByLastActiveAtGreaterThan(since: Long): List<Device> = emptyList()
    }

    /** 一套内存依赖，各用例独立构造。 */
    private class Fixture(
        val svc: BillingService,
        val subs: FakeSubscriptionRepo,
        val orders: FakeOrderRepo,
        val devices: FakeDeviceRepo
    ) {
        /** 给指定家长登记 N 台已绑定设备 */
        fun withDevices(n: Int, userId: String = "parent-1") = apply { devices.counts[userId] = n }

        /** 把该家长的订阅改成已过期 */
        fun expire(userId: String = "parent-1") = apply {
            subs.table.values.first { it.userId == userId }.expiredAt = System.currentTimeMillis() - 1
        }

        /** 该家长的订阅实体 */
        fun sub(userId: String = "parent-1"): Subscription =
            subs.table.values.first { it.userId == userId }
    }

    private fun service(
        trialDays: Long = 1,
        trialDevices: Int = 3,
        mockPayEnabled: Boolean = true,
        payTimeoutMinutes: Long = 30,
        verifier: PaymentVerifier = ConfigurablePaymentVerifier(true, FakeSystemSettingRepo(), ObjectMapper(), listOf()),
        settings: FakeSystemSettingRepo = FakeSystemSettingRepo()
    ): Fixture {
        val subRepo = FakeSubscriptionRepo()
        val orderRepo = FakeOrderRepo()
        val deviceRepo = FakeDeviceRepo()
        // 同事为 BillingService 增加了账号状态闸门（requireUsableAccount），构造需要用户仓储；
        // 预置一个 active 的 parent-1，保证 statusOf/ensureActive 等路径不受影响。
        val userRepo = FakeUserRepo()
        userRepo.save(User(id = "parent-1", phone = "13800000001", passwordHash = "h", role = "parent", sceneType = "family", status = "active"))
        val svc = BillingService(
            subRepo, orderRepo, deviceRepo, userRepo,
            trialDays, trialDevices,
            3800, 9800, 29800,   // 月 / 季 / 年（分）
            3, 3, 5,             // 各档设备上限
            payTimeoutMinutes, mockPayEnabled, verifier, settings, ObjectMapper(),
            orderCloser = OrderTimeoutCloser(orderRepo)  // 超时关单走真实闭合器（断言 CLOSED 落库）
        )
        return Fixture(svc, subRepo, orderRepo, deviceRepo)
    }

    private fun Fixture.order(plan: String = "MONTHLY"): String =
        svc.createOrder("parent-1", CreateOrderRequest(plan = plan, channel = "MANUAL")).orderNo

    /** 用指定收银台渠道下单（MANUAL 之外，需 mockPayEnabled=true 才允许） */
    private fun Fixture.orderVia(channel: String, plan: String = "MONTHLY"): String =
        svc.createOrder("parent-1", CreateOrderRequest(plan = plan, channel = channel)).orderNo

    // ------------------------------------------------------------ 试用与查询

    @Test
    fun `first statusOf call auto-grants a trial instead of locking the user out`() {
        val f = service()
        val dto = f.svc.statusOf("parent-1")

        assertEquals(SubStatus.TRIAL.name, dto.status)
        assertTrue(dto.active, "试用期内必须可用，否则新用户一进来就被挡在门外")
        assertFalse(dto.paid, "试用不算已付费")
        assertEquals(1, dto.remainDays, "当前默认试用 1 天，开通后应显示剩余 1 天")
        assertEquals(1, f.subs.table.size, "应落库一条订阅记录")
    }

    @Test
    fun `legacy accounts also get a trial - otherwise an upgrade locks them out`() {
        val f = service()
        // 存量账号（上线前注册的）没有订阅记录。若 statusOf 不开通试用，
        // 它们会永远处于"无订阅"，一升级就彻底用不了。
        assertTrue(f.svc.statusOf("legacy-parent").active)
    }

    @Test
    fun `statusOf refreshes expired state so the app stops showing active`() {
        val f = service()
        f.svc.statusOf("parent-1")
        f.expire()

        val dto = f.svc.statusOf("parent-1")
        assertEquals(SubStatus.EXPIRED.name, dto.status)
        assertFalse(dto.active, "过期后必须显示不可用，否则家长端会一直显示生效中")
        assertEquals(0, dto.remainDays)
    }

    @Test
    fun `permanent member never expires and is reported as paid with no remaining days`() {
        val f = service()
        val now = System.currentTimeMillis()
        // 直接落一条 PERMANENT 订阅；即便 expiredAt 已"过去"，也不应被判过期
        f.subs.table["perm-1"] = Subscription(
            id = "perm-1", userId = "perm-parent",
            plan = Plan.PERMANENT.key, status = SubStatus.ACTIVE.name,
            deviceLimit = 10, startedAt = now,
            expiredAt = now - 86_400_000L, autoRenew = false,
            createdAt = now, updatedAt = now
        )

        val dto = f.svc.statusOf("perm-parent")
        assertEquals(SubStatus.ACTIVE.name, dto.status)
        assertTrue(dto.active, "永久会员必须始终可用")
        assertTrue(dto.paid, "永久会员视为已付费用户")
        assertEquals("永久会员", dto.planLabel)
        assertEquals(-1, dto.remainDays, "永久会员用 -1 哨兵表示无到期")
        // 二次读取仍应为 ACTIVE：refreshStatus 对 PERMANENT 直接跳过过期分支
        assertEquals(SubStatus.ACTIVE.name, f.svc.statusOf("perm-parent").status)
    }

    @Test
    fun `permanent plan is not purchasable and not shown in storefront`() {
        assertFalse(Plan.purchasable().any { it == Plan.PERMANENT }, "永久会员不应进入可购买清单")
        val f = service()
        assertFalse(f.svc.plans().any { it.key == Plan.PERMANENT.key }, "收银台套餐列表不应包含永久会员")
    }

    @Test
    fun `createOrder rejects non-purchasable plans such as PERMANENT to prevent free permanent membership`() {
        val f = service()
        // 回归：createOrder 此前只拦截 TRIAL，PERMANENT 仍可被下单成 ¥0 订单，
        // 经 mockPay/markPaid 即开通永久会员 —— 绕过收费后门。修复后应一律拒绝。
        val ex = assertThrows(BizException::class.java) {
            f.svc.createOrder("parent-1", CreateOrderRequest(plan = Plan.PERMANENT.key, channel = "MANUAL"))
        }
        assertEquals(ParentErr.PARAM_ERROR, ex.code)

        // 正向对照：可购买套餐仍应能正常下单
        val ok = f.svc.createOrder("parent-1", CreateOrderRequest(plan = "MONTHLY", channel = "MANUAL"))
        assertEquals("MONTHLY", f.orders.table.values.first().plan, "订单应记录所购套餐")
        assertEquals(3800, ok.amountFen)
    }

    // ------------------------------------------------------------ 付费闸门

    @Test
    fun `ensureActive passes during trial and blocks after expiry`() {
        val f = service()
        f.svc.ensureActive("parent-1")   // 试用期内不抛

        f.expire()
        val ex = assertThrows(BizException::class.java) { f.svc.ensureActive("parent-1") }
        assertEquals(ParentErr.SUBSCRIPTION_REQUIRED, ex.code)
    }

    @Test
    fun `ensureActive reports a distinct trial-ended message for an expired trial`() {
        val f = service()
        f.svc.statusOf("parent-1")                 // 试用订阅（plan=TRIAL）
        f.sub().expiredAt = System.currentTimeMillis() - 1

        val ex = assertThrows(BizException::class.java) { f.svc.ensureActive("parent-1") }
        assertEquals(ParentErr.SUBSCRIPTION_REQUIRED, ex.code)
        assertTrue(ex.message.contains("试用"), "试用到期应提示「试用」而非笼统的订阅到期")
    }

    @Test
    fun `ensureActive reports a subscription-expired message for an expired paid plan`() {
        val f = service()
        f.svc.markPaid(f.order("MONTHLY"), "TX-1") // 付费订阅（plan=MONTHLY, status=ACTIVE）
        f.sub().expiredAt = System.currentTimeMillis() - 1

        val ex = assertThrows(BizException::class.java) { f.svc.ensureActive("parent-1") }
        assertEquals(ParentErr.SUBSCRIPTION_REQUIRED, ex.code)
        assertTrue(ex.message.contains("续费"), "付费到期应提示续费，而非与试用文案混淆")
    }

    @Test
    fun `ensureActive blocks revoked subscriptions even when not expired`() {
        val f = service()
        f.svc.statusOf("parent-1")
        f.sub().status = SubStatus.REVOKED.name
        f.sub().expiredAt = System.currentTimeMillis() + 86_400_000

        // 退款/封禁场景下 expiredAt 可能还没到，只判时间会漏掉这一类
        assertThrows(BizException::class.java) { f.svc.ensureActive("parent-1") }
    }

    @Test
    fun `child side check reports a specific reason instead of a generic failure`() {
        val f = service()
        f.svc.ensureActiveForChild("parent-1")   // 试用期内允许绑定

        f.expire()
        val ex = assertThrows(BizException::class.java) { f.svc.ensureActiveForChild("parent-1") }
        assertEquals(ChildErr.SUBSCRIPTION_REQUIRED, ex.code, "孩子端要走 ChildErr，提示语才能直接展示")
    }

    @Test
    fun `device limit is checked only after subscription - wrong order misleads unpaid users`() {
        // 已占满设备数、但仍在试用期 → 报"设备数超限"
        val full = service(trialDevices = 1).withDevices(1)
        val ex1 = assertThrows(BizException::class.java) { full.svc.ensureCanBind("parent-1") }
        assertEquals(ParentErr.DEVICE_LIMIT_EXCEEDED, ex1.code)

        // 订阅过期且设备数也已满 → 必须先报"未订阅"。
        // 反过来的话家长会以为买更多设备额度就能解决，而不是去续费。
        val expired = service(trialDevices = 1).withDevices(1)
        expired.svc.statusOf("parent-1")
        expired.expire()
        val ex2 = assertThrows(BizException::class.java) { expired.svc.ensureCanBind("parent-1") }
        assertEquals(ParentErr.SUBSCRIPTION_REQUIRED, ex2.code)
        assertNotEquals(ParentErr.DEVICE_LIMIT_EXCEEDED, ex2.code)
    }

    // ------------------------------------------------------------ 下单

    @Test
    fun `createOrder accepts manual channel and records amount in fen`() {
        val f = service()
        val resp = f.svc.createOrder("parent-1", CreateOrderRequest(plan = "YEARLY", channel = "MANUAL"))

        assertEquals(29800, resp.amountFen)
        assertEquals(PayChannel.MANUAL.name, resp.channel)
        assertEquals(1, f.orders.table.size)
        assertEquals(OrderStatus.PENDING.name, f.orders.table.values.first().status)
    }

    @Test
    fun `createOrder rejects channels that are not integrated yet`() {
        // 生产模式（示例支付关闭）下，真实渠道尚未接入：在此放行会让家长"付了钱却收不到回调"。
        // 示例支付开启（mockPayEnabled=true）时，收银台渠道作为演示支付可用，不在此列。
        val f = service(mockPayEnabled = false)
        // 微信/支付宝尚未接入：在这里放行会让家长付了钱却收不到回调，
        // 比直接拒绝"暂不支持"严重得多。
        assertThrows(BizException::class.java) {
            f.svc.createOrder("parent-1", CreateOrderRequest(plan = "MONTHLY", channel = "WECHAT"))
        }
    }

    @Test
    fun `createOrder rejects trial and unknown plans`() {
        val f = service()
        assertThrows(BizException::class.java) {
            f.svc.createOrder("parent-1", CreateOrderRequest(plan = "TRIAL", channel = "MANUAL"))
        }
        assertThrows(BizException::class.java) {
            f.svc.createOrder("parent-1", CreateOrderRequest(plan = "LIFETIME", channel = "MANUAL"))
        }
    }

    // ------------------------------------------------------------ 支付入账

    @Test
    fun `markPaid activates the subscription and extends the expiry`() {
        val f = service()
        val before = f.svc.statusOf("parent-1").expiredAt
        val after = f.svc.markPaid(f.order("MONTHLY"), "TX-1")

        assertEquals(SubStatus.ACTIVE.name, after.status)
        assertTrue(after.paid)
        assertTrue(after.expiredAt > before, "付费后有效期必须延长")
    }

    @Test
    fun `renewal before expiry stacks on top of the current expiry`() {
        val f = service()
        f.svc.statusOf("parent-1")
        val expiryBefore = f.sub().expiredAt

        f.svc.markPaid(f.order("MONTHLY"), "TX-1")

        assertTrue(
            f.sub().expiredAt > expiryBefore,
            "未过期续费必须从原到期日顺延，否则家长会损失已购买的剩余天数"
        )
    }

    @Test
    fun `renewal after expiry starts from now instead of backdating`() {
        val f = service()
        f.svc.statusOf("parent-1")
        f.sub().expiredAt = System.currentTimeMillis() - 10 * 86_400_000L

        val after = f.svc.markPaid(f.order("MONTHLY"), "TX-1")

        assertTrue(
            after.expiredAt > System.currentTimeMillis(),
            "过期后续费必须从当前时间起算；若仍从已过期的 expiredAt 起算，家长付了钱却还是过期状态"
        )
    }

    @Test
    fun `duplicate payment callbacks do not extend the subscription twice`() {
        val f = service()
        val orderNo = f.order("YEARLY")

        f.svc.markPaid(orderNo, "TX-1")
        val expiryAfterFirst = f.sub().expiredAt
        // 支付平台回调必然重复投递（网络重试、用户重复点击），
        // 不去重的话一次付款能换来两倍时长。
        f.svc.markPaid(orderNo, "TX-1")
        f.svc.markPaid(orderNo, "TX-2")

        assertEquals(expiryAfterFirst, f.sub().expiredAt, "重复回调不得延长订阅")
    }

    @Test
    fun `expired unpaid orders (no transactionId) are closed and rejected`() {
        val f = service()
        val orderNo = f.order("MONTHLY")
        f.orders.table.values.first().payDeadline = System.currentTimeMillis() - 1

        // 超时且无支付凭证 = 未支付，关单让重新下单，避免"幽灵订单"一直挂着。
        assertThrows(BizException::class.java) { f.svc.markPaid(orderNo, null) }
        assertEquals(OrderStatus.CLOSED.name, f.orders.table.values.first().status)
    }

    @Test
    fun `expired but already-paid orders (has transactionId) are still activated`() {
        val f = service()
        val orderNo = f.order("MONTHLY")
        f.orders.table.values.first().payDeadline = System.currentTimeMillis() - 1

        // 异步回调可能晚于超时到达，但渠道已确认收款（transactionId 非空）：
        // 这种单若直接关掉会变成"已付款却不开通"，是家长最难理解的一类投诉。
        val after = f.svc.markPaid(orderNo, "TX-LATE")
        assertEquals(SubStatus.ACTIVE.name, after.status)
        assertEquals("TX-LATE", f.orders.table.values.first().transactionId)
    }

    @Test
    fun `markPaid rejects unknown orders`() {
        val f = service()
        assertThrows(BizException::class.java) { f.svc.markPaid("NO-SUCH-ORDER", "TX-1") }
    }

    // ------------------------------------------------------------ 时长计算

    @Test
    fun `yearly plan follows calendar months instead of 360 days`() {
        val f = service()
        f.svc.statusOf("parent-1")
        // 先让订阅过期，使续费基准变成"当前时间"，这样才能精确断言自然月顺延。
        // （若仍在试用期，基准是试用期到期日，顺延 12 个月后也会多出那几天 ——
        //   那是"续费叠加"的正确结果，不是计算错误。）
        f.sub().expiredAt = System.currentTimeMillis() - 86_400_000L

        f.svc.extend("parent-1", Plan.YEARLY.months, Plan.YEARLY.key)

        val zone = ZoneId.systemDefault()
        val expiryDate = Instant.ofEpochMilli(f.sub().expiredAt).atZone(zone).toLocalDate()

        assertEquals(
            LocalDate.now(zone).plusMonths(12), expiryDate,
            "年卡必须按 12 个自然月顺延；按 30×12=360 天算每年会少 5 天，" +
                "续费几年后家长会发现年卡提前到期"
        )
    }

    @Test
    fun `expiry lands at the end of the last day so that day still counts`() {
        val f = service()
        f.svc.statusOf("parent-1")
        f.svc.extend("parent-1", Plan.MONTHLY.months, Plan.MONTHLY.key)

        val zoned = Instant.ofEpochMilli(f.sub().expiredAt).atZone(ZoneId.systemDefault())
        assertEquals(23, zoned.hour, "到期时刻应为当日 23:59:59.999，家长体感是当天还能用")
        assertEquals(59, zoned.minute)
    }

    // ------------------------------------------------------------ 套餐

    @Test
    fun `plans list exposes three purchasable tiers with prices and device limits`() {
        val plans = service().svc.plans()

        assertEquals(3, plans.size, "只应出现月/季/年三档，试用期不是可购买项")
        assertEquals(listOf("MONTHLY", "QUARTERLY", "YEARLY"), plans.map { it.key })
        assertEquals(3800, plans[0].amountFen)
        assertEquals("¥38", plans[0].priceText)
        assertEquals(5, plans[2].deviceLimit)
    }

    @Test
    fun `mock pay is disabled by default so production cannot be bypassed`() {
        val f = service(mockPayEnabled = false)
        // 生产环境若开着模拟支付，任何人都能免费续费
        assertThrows(BizException::class.java) { f.svc.mockPay("parent-1", f.order("MONTHLY")) }
    }

    @Test
    fun `mock pay rejects orders belonging to another user (s16 ownership)`() {
        val f = service(mockPayEnabled = true)
        val orderNo = f.order("MONTHLY") // 订单归属 parent-1
        val ex = assertThrows(BizException::class.java) { f.svc.mockPay("someone-else", orderNo) }
        assertEquals(com.padguard.server.common.ParentErr.DEVICE_NOT_OWNED, ex.code, "他人订单不得被替付")
        val paid = f.svc.mockPay("parent-1", orderNo)
        assertEquals(true, paid.active, "归属人本人 mock 支付应正常开通")
    }

    // ------------------------------------------------------------ 支付回调验签

    @Test
    fun `handleNotify activates subscription after verification passes`() {
        // mock 模式（示例支付）验签放行，让"下单→支付→开通"可端到端验证
        val f = service(mockPayEnabled = true)
        val orderNo = f.orderVia("WECHAT", "MONTHLY")

        val after = f.svc.handleNotify(PayRequest(orderNo, "TX-1"), "{}", emptyMap())

        assertEquals(SubStatus.ACTIVE.name, after.status, "验签通过必须入账开通")
        assertTrue(after.paid)
    }

    @Test
    fun `handleNotify rejects manual-channel orders from the public callback`() {
        // 后台代开通订单（MANUAL）不应经公开回调入账，否则拿后台单号即可白嫖
        val f = service(mockPayEnabled = true)
        val orderNo = f.order("MONTHLY")

        val ex = assertThrows(BizException::class.java) { f.svc.handleNotify(PayRequest(orderNo, "TX-1"), "{}", emptyMap()) }
        assertEquals(ParentErr.FORBIDDEN, ex.code, "MANUAL 单走公开 notify 必须被拒")
    }

    @Test
    fun `handleNotify rejects when verification fails (fail closed)`() {
        // 生产默认：真实渠道（WECHAT）尚未接入验签器 → 验签一律拒绝，宁可暂不入账也不白嫖
        val f = service(mockPayEnabled = true, verifier = ConfigurablePaymentVerifier(false, FakeSystemSettingRepo(), ObjectMapper(), listOf()))
        val orderNo = f.orderVia("WECHAT", "MONTHLY")

        val ex = assertThrows(BizException::class.java) { f.svc.handleNotify(PayRequest(orderNo, "TX-1"), "{}", emptyMap()) }
        assertEquals(ParentErr.ORDER_STATE_INVALID, ex.code, "验签失败必须拒绝入账")
        // 关键：被拒的订单绝不能落到 PAID，否则就等于开了免验证通道
        assertEquals(OrderStatus.PENDING.name, f.orders.table.values.first().status)
    }

    // ------------------------------------------------------- 退款与单订单查询（微信支付完整接入批）

    @Test
    fun `orderStatusOf returns own order with authoritative status for client polling`() {
        val f = service()
        val orderNo = f.order("MONTHLY")
        f.svc.markPaid(orderNo, "TX-1")

        val dto = f.svc.orderStatusOf("parent-1", orderNo)
        assertEquals(OrderStatus.PAID.name, dto.status, "轮询端点必须返回服务端权威状态")
        assertEquals("TX-1", f.orders.table.values.first().transactionId)
    }

    @Test
    fun `orderStatusOf rejects polling other users orders`() {
        val f = service()
        val orderNo = f.order("MONTHLY")
        val ex = assertThrows(BizException::class.java) {
            f.svc.orderStatusOf("parent-2", orderNo)
        }
        assertEquals(ParentErr.DEVICE_NOT_OWNED, ex.code, "拿他人订单号探测支付状态属横向越权")
    }

    @Test
    fun `refund only transitions PAID orders and is idempotent`() {
        val f = service()
        val orderNo = f.order("MONTHLY")
        f.svc.markPaid(orderNo, "TX-1")
        assertEquals(OrderStatus.PAID.name, f.orders.table.values.first().status)

        // 管理端标记 + 退款回调并发到达：只有第一次条件 UPDATE 命中
        assertEquals(1, f.orders.markRefundedIfPaid(orderNo, OrderStatus.REFUNDED.name, OrderStatus.PAID.name))
        assertEquals(0, f.orders.markRefundedIfPaid(orderNo, OrderStatus.REFUNDED.name, OrderStatus.PAID.name))
        assertEquals(OrderStatus.REFUNDED.name, f.orders.table.values.first().status)
    }

    @Test
    fun `refund is rejected for unpaid or closed orders`() {
        val f = service()
        val pending = f.order("MONTHLY")
        assertEquals(
            0, f.orders.markRefundedIfPaid(pending, OrderStatus.REFUNDED.name, OrderStatus.PAID.name),
            "PENDING 订单不能被退款标记（未收到钱就退款 = 单边账）"
        )
        assertEquals(OrderStatus.PENDING.name, f.orders.table.values.first().status)
    }

    // ------------------------------------------------------------ 后台改价

    /**
     * 后台「系统设置 → 套餐定价」写入的价格必须真正参与计价。
     *
     * 此前 `amountFenOf` 只读 `@Value` 常量：运营改价看到"保存成功"，
     * 家长下单仍按旧价扣款；而设置页读 DB 价、套餐列表读配置价，
     * 同一套餐在同一后台里显示两个价格。
     */
    @Test
    fun `admin price override wins over config when creating an order`() {
        val settings = FakeSystemSettingRepo()
        settings.save(
            com.padguard.server.domain.SystemSetting(
                key = "admin.settings",
                valueJson = """{"brand":"PadGuard","currency":"CNY","trialDays":30,"passwordPolicy":"medium","mfaRequired":false,"mockPay":false,"planPrices":{"MONTHLY":9900}}"""
            )
        )
        val f = service(settings = settings)

        assertEquals(9900, f.svc.amountFenOf(Plan.MONTHLY), "应优先采用后台改过的价格")

        val orderNo = f.order("MONTHLY")
        val order = f.orders.table.values.first { it.orderNo == orderNo }
        assertEquals(9900, order.amountFen, "下单金额必须与改价后一致，否则就是资损")

        // 未在后台改过的档位仍走配置价，不能被一起清空
        assertEquals(9800, f.svc.amountFenOf(Plan.QUARTERLY))
    }

    @Test
    fun `zero or negative override falls back to the configured price`() {
        // 0 元套餐会被直接下单，宁可回落配置价也不能让"填错 0"变成免费发卡
        val settings = FakeSystemSettingRepo()
        settings.save(
            com.padguard.server.domain.SystemSetting(
                key = "admin.settings",
                valueJson = """{"planPrices":{"MONTHLY":0,"QUARTERLY":-100}}"""
            )
        )
        val f = service(settings = settings)

        assertEquals(3800, f.svc.amountFenOf(Plan.MONTHLY))
        assertEquals(9800, f.svc.amountFenOf(Plan.QUARTERLY))
    }
}
