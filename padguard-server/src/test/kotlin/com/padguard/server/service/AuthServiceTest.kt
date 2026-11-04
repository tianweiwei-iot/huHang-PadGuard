package com.padguard.server.service

import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.*
import com.padguard.server.dto.LoginPasswordRequest
import com.padguard.server.dto.RegisterRequest
import com.padguard.server.repository.*
import com.padguard.server.security.ConfigurablePaymentVerifier
import com.padguard.server.security.JwtTokenProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Pageable
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.LinkedHashMap
import org.springframework.data.domain.Page

/**
 * 家长端手机号自助注册的单元测试。
 *
 * 注册链路是「建账号 + 设密码（哈希）+ 注册即送试用 + 自动登录」的组合，
 * 必须测住：① 账号与试用订阅同生、② 重复手机号不建第二份、③ 手机号/密码格式校验、
 * ④ 注册后凭密码能登录、错密码不能登录。这些任一点漏掉都会变成"注册了却进不去"或"试用没送"。
 */
class AuthServiceTest {

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

    private class FakeSubRepo : FakeJpaRepository<Subscription, String>(), SubscriptionRepository {
        override val table = LinkedHashMap<String, Subscription>()
        override fun idOf(e: Subscription) = e.id
        override fun findByUserId(userId: String): Subscription? = table.values.firstOrNull { it.userId == userId }
        override fun countByStatus(status: String) = table.values.count { it.status == status }.toLong()
        override fun findAllByOrderByExpiredAtDesc() = table.values.sortedByDescending { it.expiredAt }
        override fun findByUserIdForUpdate(userId: String): Subscription? = findByUserId(userId)

        /** 与真实 SQL 同语义：仅"仍是可用态且确实过期"才命中 1 行 */
        override fun markExpiredIfStale(userId: String, expiredStatus: String, now: Long): Int {
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
        override fun idOf(e: PaymentOrder) = e.id
        override fun findByOrderNo(orderNo: String): PaymentOrder? = table.values.firstOrNull { it.orderNo == orderNo }
        override fun findByUserIdOrderByCreatedAtDesc(userId: String, pageable: Pageable) =
            table.values.filter { it.userId == userId }.sortedByDescending { it.createdAt }
        override fun findByUserIdAndStatus(userId: String, status: String) =
            table.values.filter { it.userId == userId && it.status == status }
        override fun findAllByOrderByCreatedAtDesc() = table.values.sortedByDescending { it.createdAt }
        override fun markRefundedIfPaid(orderNo: String, refundedStatus: String, paidStatus: String): Int {
            val o = table.values.firstOrNull { it.orderNo == orderNo } ?: return 0
            if (o.status != paidStatus) return 0
            o.status = refundedStatus
            return 1
        }
        override fun claimPendingOrder(
            orderNo: String, paidStatus: String, pendingStatus: String, paidAt: Long, txId: String?
        ): Int {
            val order = findByOrderNo(orderNo) ?: return 0
            if (order.status != pendingStatus) return 0
            order.status = paidStatus; order.paidAt = paidAt; order.transactionId = txId
            return 1
        }
        override fun claimTimeoutClose(orderNo: String, now: Long): Int {
            val o = table.values.firstOrNull { it.orderNo == orderNo } ?: return 0
            if (o.status != "PENDING" || o.payDeadline >= now) return 0
            o.status = "CLOSED"
            return 1
        }

        /** 与真实 SQL 同语义：只关闭仍为 PENDING 的订单 */
        override fun closeIfPending(orderNo: String, closedStatus: String, pendingStatus: String): Int {
            val o = table.values.firstOrNull { it.orderNo == orderNo } ?: return 0
            if (o.status != pendingStatus) return 0
            o.status = closedStatus
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
}

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
        override fun idOf(e: Device) = e.id
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
        override fun findByUserId(userId: String) = table.values.filter { it.userId == userId }
        override fun countByUserId(userId: String) = table.values.count { it.userId == userId }.toLong()
        override fun countByOnlineStatus(onlineStatus: String) = table.values.count { it.onlineStatus == onlineStatus }.toLong()
        override fun findByOnlineStatus(onlineStatus: String) =
            table.values.filter { it.onlineStatus == onlineStatus }
        override fun findByOnlineStatusAndLastOnlineAtLessThan(onlineStatus: String, ts: Long) =
            table.values.filter { it.onlineStatus == onlineStatus && (it.lastOnlineAt ?: Long.MAX_VALUE) < ts }
        override fun findByDeviceSn(deviceSn: String) = table.values.filter { it.deviceSn == deviceSn }
        override fun findByLastActiveAtGreaterThan(since: Long) =
            table.values.filter { (it.lastActiveAt ?: 0L) > since }
    
        override fun countOnlineSince(staleCut: Long): Long = table.values.count {
            it.onlineStatus == "ONLINE" && it.lastOnlineAt != null && it.lastOnlineAt!! >= staleCut
        }.toLong()

        override fun findCreatedAtSince(since: Long): List<Long> =
            table.values.map { it.createdAt }.filter { it >= since }
}

    private fun fixture(): Triple<AuthService, FakeUserRepo, FakeSubRepo> {
        val users = FakeUserRepo()
        val subs = FakeSubRepo()
        val orders = FakeOrderRepo()
        val devices = FakeDeviceRepo()
        // 验签器用示例支付放行模式（与注册链路无关，仅满足 BillingService 构造）
        val billing = BillingService(subs, orders, devices, users, 1L, 3, 3800, 9800, 29800, 3, 3, 5, 30L, true, ConfigurablePaymentVerifier(true, FakeSystemSettingRepo(), ObjectMapper(), listOf()), FakeSystemSettingRepo(), ObjectMapper())
        val jwt = JwtTokenProvider("padguard-unit-test-secret-0123456789abcdef", 120, 30)
        // prod profile 未激活：短信登录走 smsLoginEnabled 配置位（false）即被拒
        val auth = AuthService(users, jwt, billing, false,
            org.springframework.mock.env.MockEnvironment())
        return Triple(auth, users, subs)
    }

    @Test
    fun `register creates a parent account and a one-day trial subscription, then auto-logs in`() {
        val (auth, users, subs) = fixture()
        val resp = auth.register(RegisterRequest(phone = "13900000001", password = "secret123"))

        // 账号落库
        assertEquals(1, users.table.size)
        val user = users.table.values.first()
        assertEquals("13900000001", user.phone)
        assertEquals("active", user.status)
        // 密码必须哈希存储，绝不能明文
        assertTrue(user.passwordHash != "secret123" && user.passwordHash.isNotBlank())

        // 注册即送 1 天试用（订阅与账号同生）
        assertEquals(1, subs.table.size)
        val sub = subs.table.values.first()
        assertEquals(Plan.TRIAL.key, sub.plan)
        assertEquals(SubStatus.TRIAL.name, sub.status)
        assertTrue(sub.expiredAt > System.currentTimeMillis(), "试用必须落到未来")
        assertTrue(sub.expiredAt - System.currentTimeMillis() <= 86_400_000L + 5000, "默认试用为 1 天")

        // 自动登录：返回 token
        assertNotNull(resp.token)
        assertNotNull(resp.refreshToken)
    }

    @Test
    fun `register rejects a phone that already exists`() {
        val (auth, users, _) = fixture()
        auth.register(RegisterRequest(phone = "13900000001", password = "secret123"))
        val ex = assertThrows(BizException::class.java) {
            auth.register(RegisterRequest(phone = "13900000001", password = "secret123"))
        }
        assertEquals(ParentErr.PARAM_ERROR, ex.code, "重复注册必须报参数错误")
        assertEquals(1, users.table.size, "重复注册不得产生第二个账号")
    }

    @Test
    fun `register rejects a malformed phone`() {
        val (auth, _, _) = fixture()
        val ex = assertThrows(BizException::class.java) {
            auth.register(RegisterRequest(phone = "123", password = "secret123"))
        }
        assertEquals(ParentErr.PARAM_ERROR, ex.code, "手机号格式不正确必须被拒")
    }

    @Test
    fun `register rejects a weak password`() {
        val (auth, _, _) = fixture()
        val ex = assertThrows(BizException::class.java) {
            auth.register(RegisterRequest(phone = "13900000001", password = "123"))
        }
        assertEquals(ParentErr.PARAM_ERROR, ex.code, "密码过短必须被拒")
    }

    @Test
    fun `registered user can log in with the password`() {
        val (auth, _, _) = fixture()
        auth.register(RegisterRequest(phone = "13900000001", password = "secret123"))
        val resp = auth.loginWithPassword(LoginPasswordRequest(phone = "13900000001", password = "secret123"))
        assertNotNull(resp.token, "注册后凭密码应能登录")
    }

    @Test
    fun `registered user cannot log in with a wrong password`() {
        val (auth, _, _) = fixture()
        auth.register(RegisterRequest(phone = "13900000001", password = "secret123"))
        val ex = assertThrows(BizException::class.java) {
            auth.loginWithPassword(LoginPasswordRequest(phone = "13900000001", password = "wrongpw"))
        }
        assertEquals(ParentErr.UNAUTHORIZED, ex.code, "错密码必须被拒")
    }
}
