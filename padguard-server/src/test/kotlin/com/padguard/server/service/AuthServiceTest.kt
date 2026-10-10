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
    }

    private class FakeSubRepo : FakeJpaRepository<Subscription, String>(), SubscriptionRepository {
        override val table = LinkedHashMap<String, Subscription>()
        override fun idOf(e: Subscription) = e.id
        override fun findByUserId(userId: String): Subscription? = table.values.firstOrNull { it.userId == userId }
        override fun countByStatus(status: String) = table.values.count { it.status == status }.toLong()
        override fun findAllByOrderByExpiredAtDesc() = table.values.sortedByDescending { it.expiredAt }
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
    }

    private class FakeDeviceRepo : FakeJpaRepository<Device, String>(), DeviceRepository {
        override val table = LinkedHashMap<String, Device>()
        override fun idOf(e: Device) = e.id
        override fun findByUserId(userId: String) = table.values.filter { it.userId == userId }
        override fun countByUserId(userId: String) = table.values.count { it.userId == userId }.toLong()
        override fun countByOnlineStatus(onlineStatus: String) = table.values.count { it.onlineStatus == onlineStatus }.toLong()
        override fun findByDeviceSn(deviceSn: String) = table.values.filter { it.deviceSn == deviceSn }
        override fun findByLastActiveAtGreaterThan(since: Long) =
            table.values.filter { (it.lastActiveAt ?: 0L) > since }
    }

    private fun fixture(): Triple<AuthService, FakeUserRepo, FakeSubRepo> {
        val users = FakeUserRepo()
        val subs = FakeSubRepo()
        val orders = FakeOrderRepo()
        val devices = FakeDeviceRepo()
        // 验签器用示例支付放行模式（与注册链路无关，仅满足 BillingService 构造）
        val billing = BillingService(subs, orders, devices, 1L, 3, 3800, 9800, 29800, 3, 3, 5, 30L, true, ConfigurablePaymentVerifier(true, listOf()), FakeSystemSettingRepo(), ObjectMapper())
        val jwt = JwtTokenProvider("padguard-unit-test-secret-0123456789abcdef", 120, 30)
        val auth = AuthService(users, jwt, billing, false)
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
