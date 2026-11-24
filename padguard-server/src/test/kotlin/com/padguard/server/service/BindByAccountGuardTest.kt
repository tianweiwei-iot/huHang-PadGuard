package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ChildErr
import com.padguard.server.domain.User
import com.padguard.server.dto.BindByAccountRequest
import com.padguard.server.dto.BindRequest
import com.padguard.server.mqtt.MqttGateway
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.PolicyRepository
import com.padguard.server.repository.UserRepository
import com.padguard.server.security.DeviceOwnershipGuard
import com.padguard.server.security.Passwords
import com.padguard.server.stream.ScreenStreamHub
import com.padguard.server.ws.WebSocketPushService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * 「账号密码绑定设备」入口的账号级闸门回归（新增缺陷 M）。
 *
 * 旧实现只做 `findByPhone + Passwords.matches`，绕开了登录侧已修好的
 * 锁定 / 失败计数 / 冻结状态三道闸门。这里只测**在 bindChildWithUser 之前就该被挡住**的分支，
 * 因此重型协作者全部以 mock 占位（成功绑定路径另由既有集成测试覆盖）。
 */
class BindByAccountGuardTest {

    private val users = Mockito.mock(UserRepository::class.java)
    private val devices = Mockito.mock(DeviceRepository::class.java)

    // Mockito 的 eq() 对 Kotlin 非空参数会返回 null 占位（NPE），且抛错后 matcher 栈会污染后续用例。
    // 因此这里用 any*() 打桩 + 记录实参来断言，不用 verify + matcher。
    private val failureCalls = mutableListOf<Triple<String, Int, Long>>()
    private val clearCalls = mutableListOf<String>()

    init {
        Mockito.`when`(users.registerLoginFailure(Mockito.anyString(), Mockito.anyInt(), Mockito.anyLong()))
            .thenAnswer { inv ->
                failureCalls += Triple(
                    inv.arguments[0] as String,
                    inv.arguments[1] as Int,
                    inv.arguments[2] as Long
                )
                1
            }
        Mockito.`when`(users.clearLoginFailure(Mockito.anyString())).thenAnswer { inv ->
            clearCalls += inv.arguments[0] as String
            1
        }
    }

    private val service: DeviceService by lazy {
        DeviceService(
            devices,
            DeviceOwnershipGuard(devices),
            Mockito.mock(EnrollmentService::class.java),
            Mockito.mock(PolicyService::class.java),
            Mockito.mock(WebSocketPushService::class.java),
            Mockito.mock(MqttGateway::class.java),
            users,
            Mockito.mock(PolicyRepository::class.java),
            Mockito.mock(AuditService::class.java),
            30L,
            "",
            "",
            Mockito.mock(BillingService::class.java),
            Mockito.mock(DeviceDataPurgeService::class.java),
            DevicePresenceRegistry(),
            ScreenStreamHub(),
            TransactionTemplate(Mockito.mock(PlatformTransactionManager::class.java))
        )
    }

    private fun request(password: String) =
        BindByAccountRequest(phone = PHONE, password = password, req = BindRequest(bindCode = "unused"))

    private fun stubUser(user: User) {
        Mockito.`when`(users.findByPhone(PHONE)).thenReturn(user)
    }

    private fun user(status: String = "active", lockUntil: Long? = null, failCount: Int? = 0) =
        User(
            id = "U1", phone = PHONE, passwordHash = HASH, nickname = "家长",
            status = status, loginFailCount = failCount, loginLockedUntil = lockUntil
        )

    @Test
    fun `locked account cannot bind even with the correct password`() {
        stubUser(user(lockUntil = System.currentTimeMillis() + 10 * 60_000L))

        val ex = assertThrows(BizException::class.java) { service.bindChildByAccount(request(CORRECT)) }
        assertEquals(ChildErr.TOO_MANY_REQUESTS, ex.code)
        assertEquals(Audience.CHILD, ex.audience)
    }

    @Test
    fun `suspended account cannot bind even with the correct password`() {
        stubUser(user(status = "suspended"))

        val ex = assertThrows(BizException::class.java) { service.bindChildByAccount(request(CORRECT)) }
        assertEquals(ChildErr.TOKEN_INVALID, ex.code)
    }

    @Test
    fun `pending account cannot bind`() {
        stubUser(user(status = "pending"))

        val ex = assertThrows(BizException::class.java) { service.bindChildByAccount(request(CORRECT)) }
        assertEquals(ChildErr.TOKEN_INVALID, ex.code)
    }

    @Test
    fun `wrong password feeds the shared login failure counter`() {
        stubUser(user())

        val ex = assertThrows(BizException::class.java) { service.bindChildByAccount(request("wrong")) }
        assertEquals(ChildErr.TOKEN_INVALID, ex.code)
        // 关键：绑定通道的失败必须计入 users 表，才能在低速字典下触发账号级锁定。
        assertEquals(1, failureCalls.size)
        assertEquals(PHONE, failureCalls[0].first)
        assertEquals(5, failureCalls[0].second)
        org.junit.jupiter.api.Assertions.assertTrue(failureCalls[0].third > System.currentTimeMillis())
    }

    @Test
    fun `wrong password does not clear residual failure state`() {
        stubUser(user(failCount = 3))

        assertThrows(BizException::class.java) { service.bindChildByAccount(request("wrong")) }
        org.junit.jupiter.api.Assertions.assertTrue(clearCalls.isEmpty())
    }

    companion object {
        private const val PHONE = "13800000000"
        private const val CORRECT = "correct-password"
        private val HASH: String = Passwords.encode(CORRECT)
    }
}
