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
import com.padguard.server.domain.SystemSetting
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
import org.springframework.data.domain.Page

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

        // ---- 登录失败计数：语义必须与真实仓储的 SQL 一致，
        // 否则「测试通过但线上是错的」比不补更危险 ----
        override fun registerLoginFailure(phone: String, maxAttempts: Int, lockUntil: Long): Int {
            val u = table.values.firstOrNull { it.phone == phone } ?: return 0
            val next = (u.loginFailCount ?: 0) + 1
            if (next >= maxAttempts) {
                // 与 SQL 的 CASE 一致：达阈值时计数清零并写入锁定时间
                u.loginFailCount = 0
                u.loginLockedUntil = lockUntil
            } else {
                u.loginFailCount = next
            }
            return 1
        }

        override fun clearLoginFailure(phone: String): Int {
            val u = table.values.firstOrNull { it.phone == phone } ?: return 0
            u.loginFailCount = 0
            u.loginLockedUntil = null
            return 1
        }

        override fun touchLastLoginAt(id: String, ts: Long): Int {
            val u = table[id] ?: return 0
            u.lastLoginAt = ts
            return 1
        }
        override fun searchParents(
            status: String,
            keyword: String,
            kw: String,
            planMode: String,
            pageable: org.springframework.data.domain.Pageable
        ): org.springframework.data.domain.Page<User> =
            org.springframework.data.domain.PageImpl(emptyList(), pageable, 0)
    
        override fun countByStatusOrNullAsActive(status: String): Long =
            table.values.count { (it.status ?: "active") == status }.toLong()

        override fun countGroupedByRegion(): List<Array<Any?>> =
            table.values.groupBy { it.region }
                .map { arrayOf(it.key, it.value.size.toLong()) as Array<Any?> }

        override fun findCreatedAtSince(since: Long): List<Long> =
            table.values.map { it.createdAt }.filter { it >= since }
}

    private class FakeSubscriptionRepo : FakeJpaRepository<Subscription, String>(), SubscriptionRepository {
        override val table = LinkedHashMap<String, Subscription>()

        /**
         * 并发窗口模拟钩子：在"判定过期并写回"这一刻插入别的线程刚提交的动作。
         * 用来复现"读快照 → 别人续费 → 本请求写回"这条时序，单靠顺序代码无法构造。
         */
        var beforeMarkExpired: ((String) -> Unit)? = null
        override fun idOf(entity: Subscription) = entity.id
        override fun findByUserId(userId: String): Subscription? =
            table.values.firstOrNull { it.userId == userId }
        override fun countByStatus(status: String): Long = 0L
        override fun findAllByOrderByExpiredAtDesc(): List<Subscription> = emptyList()
        override fun findByUserIdForUpdate(userId: String): Subscription? = findByUserId(userId)

        /**
         * 与真实 SQL 同语义：只更新 status/updated_at（**绝不写 expiredAt**），
         * 且仅当"仍是可用态 + 此刻确实过期"才命中。
         * 这条语义是本轮修复的核心断言点：不能被 fake 实现"顺手"改宽。
         */
        override fun markExpiredIfStale(userId: String, expiredStatus: String, now: Long): Int {
            beforeMarkExpired?.invoke(userId)
            val s = table.values.firstOrNull { it.userId == userId } ?: return 0
            if (s.status != "TRIAL" && s.status != "ACTIVE") return 0
            if (s.expiredAt >= now) return 0
            s.status = expiredStatus
            s.updatedAt = now
            return 1
        }
        override fun findByUserIdIn(userIds: Collection<String>): List<Subscription> =
            table.values.filter { it.userId in userIds }
    
        override fun countGroupedByPlan(): List<Array<Any?>> =
            table.values.groupBy { it.plan }
                .map { arrayOf(it.key, it.value.size.toLong()) as Array<Any?> }

        override fun findCreatedAtSince(since: Long): List<Long> =
            table.values.map { it.createdAt }.filter { it >= since }

        override fun searchSubscriptions(status: String, pageable: Pageable): Page<Subscription> {
            val matched = table.values
                .filter { status.isBlank() || it.status.lowercase() == status }
                .sortedByDescending { it.expiredAt }
            return pageOf(matched, pageable)
        }
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
    
        override fun searchOrders(status: String, pageable: Pageable): Page<PaymentOrder> {
            val matched = table.values
                .filter { status.isBlank() || it.status.lowercase() == status }
                .sortedByDescending { it.createdAt }
            return pageOf(matched, pageable)
        }

        override fun findPaidAmountsSince(paidStatus: String, since: Long): List<Array<Any?>> =
            table.values.filter { it.status == paidStatus && it.createdAt >= since }
                .map { arrayOf(it.createdAt, it.amountFen) as Array<Any?> }

        /** 与真实 SQL 同语义：只关闭仍为 PENDING 的订单 */
        override fun closeIfPending(orderNo: String, closedStatus: String, pendingStatus: String): Int {
            val o = table.values.firstOrNull { it.orderNo == orderNo } ?: return 0
            if (o.status != pendingStatus) return 0
            o.status = closedStatus
            return 1
        }
}

    /** 设备台账：BillingService 只关心数量，无需构造完整 Device 实体。 */
    private class FakeDeviceRepo : FakeJpaRepository<Device, String>(), DeviceRepository {
    override fun updateEntitlementState(deviceId: String, state: String): Int {
        val d = table[deviceId] ?: return 0
        d.entitlementState = state
        return 1
    }

    override fun clearRemoteLockedIfSet(deviceId: String): Int {
        val d = table[deviceId] ?: return 0
        return if (d.remoteLocked == true) { d.remoteLocked = false; 1 } else 0
    }


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
    
        override fun countOnlineSince(staleCut: Long): Long = table.values.count {
            it.onlineStatus == "ONLINE" && it.lastOnlineAt != null && it.lastOnlineAt!! >= staleCut
        }.toLong()

        override fun findCreatedAtSince(since: Long): List<Long> =
            table.values.map { it.createdAt }.filter { it >= since }
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
        settings: FakeSystemSettingRepo = FakeSystemSettingRepo(),
        /** 传入后替身 BillingService 的 trialInserter；为 null 时走"直接 save"的降级路径 */
        trialInserterFactory: ((FakeSubscriptionRepo) -> TrialSubscriptionInserter)? = null,
        /** 微信验签器替身；为 null 时该渠道置灰（与生产未配置时一致） */
        weChatVerifier: com.padguard.server.security.WeChatPayVerifier? = null
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
            orderCloser = OrderTimeoutCloser(orderRepo),  // 超时关单走真实闭合器（断言 CLOSED 落库）
            trialInserter = trialInserterFactory?.invoke(subRepo),
            weChatVerifier = weChatVerifier
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

    /**
     * 并发窗口回归：本请求已读到"已过期"快照、正要写回状态时，另一事务（微信回调）刚续费成功。
     *
     * 旧实现是 `save(sub)` —— JPA merge 整行 UPDATE，会把快照里的旧 `expiredAt` 一并写回，
     * 把钱已付的家长打回过期；而 refreshStatus 对 EXPIRED 早退，此后**永久锁死**。
     * 现在改成条件 UPDATE + 命中 0 行则重读，等价于"只在自己仍然过期时才写"。
     */
    @Test
    fun `stale expiry refresh must not overwrite a renewal that committed concurrently`() {
        val f = service()
        f.svc.statusOf("parent-1")
        f.expire()

        // 模拟并发：在 markExpiredIfStale 执行前，另一个事务已完成续费并提交
        val renewedUntil = System.currentTimeMillis() + 30L * 86_400_000L
        f.subs.beforeMarkExpired = { userId ->
            val row = f.subs.table.values.first { it.userId == userId }
            row.status = SubStatus.ACTIVE.name
            row.plan = Plan.MONTHLY.key
            row.expiredAt = renewedUntil
        }

        val dto = f.svc.statusOf("parent-1")

        assertEquals(SubStatus.ACTIVE.name, dto.status, "刚续费的家长不能被过期刷新打回过期态")
        assertTrue(dto.active)
        assertEquals(renewedUntil, f.sub("parent-1").expiredAt, "到期时间不能被旧快照写回")
        assertTrue(dto.remainDays > 0)
    }

    /**
     * 试用插入必须走独立事务入口。
     *
     * 理由：调用方（markPaid / extend / manualGrant）都在事务中，就地 save 时唯一约束冲突
     * 要到外层提交才抛，`catch (DataIntegrityViolationException)` 永远进不去，
     * 异常还会把外层事务打成 rollback-only —— 钱收了、订阅没开。
     * 这里断言"确实调用了独立入口"，保证后续重构不会把它改回就地 save。
     */
    @Test
    fun `trial subscription is inserted through the independent transaction entry point`() {
        var calls = 0
        val f = service(trialInserterFactory = { repo ->
            object : TrialSubscriptionInserter {
                override fun insert(sub: Subscription) {
                    calls++
                    repo.save(sub)
                }
            }
        })

        f.svc.statusOf("parent-1")

        assertEquals(1, calls, "试用插入必须走 REQUIRES_NEW 入口，否则并发冲突的 catch 是死代码")
        assertEquals(1, f.subs.table.size)
    }

    /**
     * 插入撞唯一约束时要回退读取赢家那一行，而不是把 500 抛给家长。
     */
    @Test
    fun `trial insert race falls back to the winning row instead of failing the request`() {
        val f = service(trialInserterFactory = { repo ->
            object : TrialSubscriptionInserter {
                override fun insert(sub: Subscription) {
                    // 赢家（另一并发请求）已提交，随后本事务 INSERT 撞约束
                    repo.table[sub.id] = sub
                    throw org.springframework.dao.DataIntegrityViolationException("ux_subscriptions_user_id")
                }
            }
        })

        val dto = f.svc.statusOf("parent-1")

        assertEquals(SubStatus.TRIAL.name, dto.status)
        assertTrue(dto.active)
        assertEquals(1, f.subs.table.size, "不能因为并发而留下两条订阅")
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

    @Test
    fun `renewals chaining from month end keep the month-end anchor`() {
        // 月末续费收缩回归：LocalDate.plusMonths 会把 1/31 夹到 2/28，
        // 若再以 2/28 为基准续费就得到 3/28 —— 每次续费都少几天，年卡用户几年后
        // 会发现"明明按月续费，到期日却从 31 号悄悄漂到 28 号"。
        // 正解是月末锚定：基准日若是所在月最后一天，目标日也取目标月最后一天。
        val f = service()
        f.svc.statusOf("parent-1")
        val zone = ZoneId.systemDefault()
        // 未来月末（相对测试运行时刻）作为已购到期日，模拟"每月底到期"的用户
        f.sub().expiredAt = LocalDate.of(2027, 1, 31).atTime(23, 59, 59, 999_000_000)
            .atZone(zone).toInstant().toEpochMilli()

        // 连续两次各续 1 个月：1/31 → 2/28 → 应到 3/31
        f.svc.extend("parent-1", 1, Plan.MONTHLY.key)
        f.svc.extend("parent-1", 1, Plan.MONTHLY.key)

        val expiryDate = Instant.ofEpochMilli(f.sub().expiredAt).atZone(zone).toLocalDate()
        assertEquals(
            LocalDate.of(2027, 3, 31), expiryDate,
            "月末连续续费必须保持月末锚点：1/31→2/28→3/31；" +
                "旧实现 1/31→2/28→3/28 会让家长每次续费都少几天"
        )
    }

    @Test
    fun `non-month-end renewals advance by the same day of month`() {
        // 反向对照：普通日期续费不受月末锚定影响，必须严格按同一天顺延。
        val f = service()
        f.svc.statusOf("parent-1")
        val zone = ZoneId.systemDefault()
        f.sub().expiredAt = LocalDate.of(2027, 1, 15).atTime(23, 59, 59, 999_000_000)
            .atZone(zone).toInstant().toEpochMilli()

        f.svc.extend("parent-1", 1, Plan.MONTHLY.key)

        val expiryDate = Instant.ofEpochMilli(f.sub().expiredAt).atZone(zone).toLocalDate()
        assertEquals(LocalDate.of(2027, 2, 15), expiryDate, "15 号续费应到 2/15，不能被月末逻辑误伤")
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

    // ------------------------------------------------------------ 退款回收权益（G-01）

    @Test
    fun `revokeRightsOnRefund revokes an active subscription and is idempotent`() {
        val f = service()
        f.svc.statusOf("parent-1") // 自动开通 TRIAL 订阅
        f.svc.revokeRightsOnRefund("parent-1")

        assertEquals(SubStatus.REVOKED.name, f.sub().status, "退款即回收：订阅应被置为 REVOKED")
        assertTrue(f.sub().expiredAt > 0, "退款回收应清掉到期时间")

        // 幂等：再次调用对已回收订阅不应抛异常，且保持 REVOKED
        f.svc.revokeRightsOnRefund("parent-1")
        assertEquals(SubStatus.REVOKED.name, f.sub().status)
    }

    @Test
    fun `revokeRightsOnRefund skips expired and missing subscriptions`() {
        val f = service()
        f.svc.statusOf("parent-1")

        // 已过期（非 ACTIVE/TRIAL）的订阅不应被翻成 REVOKED
        f.sub().status = SubStatus.EXPIRED.name
        f.svc.revokeRightsOnRefund("parent-1")
        assertEquals(SubStatus.EXPIRED.name, f.sub().status, "已过期订阅不应被翻成 REVOKED")

        // 无订阅的用户：安全 no-op，不抛异常
        f.svc.revokeRightsOnRefund("ghost-user")
    }

    // ---------------------------------------- 微信回调金额比对（官方接入规范第 4 步）

    /** 微信回调入口测试用的固定报文与头（stub 端与调用端必须逐字一致，按 equals 精确匹配） */
    private val notifyRawBody = "{}"
    private val notifyHeaders = mapOf("Wechatpay-Signature" to "x")

    /** 订单表以 id 为 key，按 orderNo 查必须走 values（既有用例同口径） */
    private fun Fixture.orderOf(orderNo: String): PaymentOrder =
        orders.table.values.first { it.orderNo == orderNo }

    /** 微信验签器替身：verifyAndParse 恒返回预设结果（验签链路有独立的协议级测试约束） */
    private fun stubVerifier(result: com.padguard.server.security.WeChatNotifyResult?): com.padguard.server.security.WeChatPayVerifier =
        org.mockito.Mockito.mock(com.padguard.server.security.WeChatPayVerifier::class.java).also {
            // 不用 any() matcher：Kotlin 非空参数会把 any() 的 null 拦成 NPE；
            // 用具体参数按 equals 精确匹配（调用端传的就是上面这两个常量）。
            org.mockito.Mockito.doReturn(result).`when`(it).verifyAndParse(notifyRawBody, notifyHeaders)
        }

    @Test
    fun `微信回调金额与订单不符必须拒绝入账且订单保持 PENDING`() {
        val verifier = stubVerifier(null)
        val f = service(weChatVerifier = verifier)
        val realNo = f.orderVia("WECHAT")
        // 用真实单号但金额差 1 分（错账场景：商户号被多系统共用/配串时另一系统的回调撞上本系统单号）
        org.mockito.Mockito.doReturn(
            com.padguard.server.security.WeChatNotifyResult(realNo, "wx-tx-1", totalFen = 1L)
        ).`when`(verifier).verifyAndParse(notifyRawBody, notifyHeaders)

        val ex = assertThrows(BizException::class.java) {
            f.svc.handleWeChatNotify(notifyRawBody, notifyHeaders)
        }
        assertEquals(ParentErr.ORDER_STATE_INVALID, ex.code, "金额不符属于订单状态类拒绝，不能放行")
        assertEquals(
            OrderStatus.PENDING.name, f.orderOf(realNo).status,
            "拒绝入账后订单必须保持 PENDING，等人工对账 —— 不能悄悄关单也不能误入账"
        )
    }

    @Test
    fun `微信回调金额一致正常入账（金额比对不误伤正常回调）`() {
        val verifier = stubVerifier(null)
        val f = service(weChatVerifier = verifier)
        val realNo = f.orderVia("WECHAT")
        val amountFen = f.orderOf(realNo).amountFen
        org.mockito.Mockito.doReturn(
            com.padguard.server.security.WeChatNotifyResult(realNo, "wx-tx-1", totalFen = amountFen.toLong())
        ).`when`(verifier).verifyAndParse(notifyRawBody, notifyHeaders)

        val dto = f.svc.handleWeChatNotify(notifyRawBody, notifyHeaders)
        assertTrue(dto.paid, "金额一致的正常回调必须照常入账，比对逻辑不能误伤")
        assertEquals(OrderStatus.PAID.name, f.orderOf(realNo).status)
    }

    // ---------------------------------------- 退款回调幂等：不得撤销新订阅

    private fun refundVerifier(orderNo: String): com.padguard.server.security.WeChatPayVerifier =
        org.mockito.Mockito.mock(com.padguard.server.security.WeChatPayVerifier::class.java).also {
            org.mockito.Mockito.doReturn(
                com.padguard.server.security.WeChatRefundNotifyResult(orderNo, null, "refund-1")
            ).`when`(it).verifyRefundAndParse(notifyRawBody, notifyHeaders)
        }

    @Test
    fun `重复退款回调不得撤销退款后新购恢复的订阅`() {
        val f = service(weChatVerifier = refundVerifier("order-refunded"))
        f.orders.table["old"] = PaymentOrder(
            id = "old", orderNo = "order-refunded", userId = "parent-1",
            channel = PayChannel.WECHAT.name, status = OrderStatus.REFUNDED.name, amountFen = 3800
        )
        f.subs.table["new"] = Subscription(
            id = "new", userId = "parent-1", plan = Plan.MONTHLY.key,
            status = SubStatus.ACTIVE.name, expiredAt = System.currentTimeMillis() + 86_400_000L
        )

        val res = f.svc.handleWeChatRefundNotify(notifyRawBody, notifyHeaders)

        assertTrue(res["ok"] as Boolean, "已 REFUNDED 的回调仍应幂等确认成功")
        assertEquals(
            SubStatus.ACTIVE.name, f.subs.table["new"]!!.status,
            "旧订单的延迟/重投回调不得把家长后来新购恢复的订阅再次停服"
        )
    }

    @Test
    fun `首次退款回调 PAID 转 REFUNDED 仍必须回收权益`() {
        val f = service(weChatVerifier = refundVerifier("order-paid"))
        f.orders.table["paid"] = PaymentOrder(
            id = "paid", orderNo = "order-paid", userId = "parent-1",
            channel = PayChannel.WECHAT.name, status = OrderStatus.PAID.name, amountFen = 3800
        )
        f.subs.table["sub"] = Subscription(
            id = "sub", userId = "parent-1", plan = Plan.MONTHLY.key,
            status = SubStatus.ACTIVE.name, expiredAt = System.currentTimeMillis() + 86_400_000L
        )

        f.svc.handleWeChatRefundNotify(notifyRawBody, notifyHeaders)

        assertEquals(OrderStatus.REFUNDED.name, f.orderOf("order-paid").status)
        assertEquals(
            SubStatus.REVOKED.name, f.subs.table["sub"]!!.status,
            "真正完成退款的那一次仍必须立即停服，否则既退钱又白用"
        )
    }

    // ------------------------------------------- 生产 mockPay 总闸（P0-1，2026-11-18）

    @Test
    fun `DB cannot enable mock pay when deployment config disables it`() {
        val settings = FakeSystemSettingRepo()
        settings.save(SystemSetting(key = "admin.settings", valueJson = """{"mockPay":true}"""))
        val f = service(mockPayEnabled = false, settings = settings)
        val orderNo = f.order("MONTHLY")

        val ex = assertThrows(BizException::class.java) { f.svc.mockPay("parent-1", orderNo) }
        assertTrue(ex.message.orEmpty().contains("模拟支付未开启"))

        // 验签侧必须同样 fail closed：DB 里的 true 不能越过部署配置
        val verifier = ConfigurablePaymentVerifier(false, settings, ObjectMapper(), emptyList())
        assertFalse(verifier.verify("WECHAT", "{}", emptyMap()))
    }

    @Test
    fun `dev config still allows mock pay and DB can turn it off`() {
        val on = FakeSystemSettingRepo().apply {
            save(SystemSetting(key = "admin.settings", valueJson = """{"mockPay":true}"""))
        }
        assertTrue(ConfigurablePaymentVerifier(true, on, ObjectMapper(), emptyList()).verify("WECHAT", "{}", emptyMap()))
        assertEquals(
            SubStatus.ACTIVE.name,
            service(mockPayEnabled = true, settings = on).let { f ->
                f.svc.mockPay("parent-1", f.order("MONTHLY")).status
            }
        )

        val off = FakeSystemSettingRepo().apply {
            save(SystemSetting(key = "admin.settings", valueJson = """{"mockPay":false}"""))
        }
        assertFalse(ConfigurablePaymentVerifier(true, off, ObjectMapper(), emptyList()).verify("WECHAT", "{}", emptyMap()))
    }
}
