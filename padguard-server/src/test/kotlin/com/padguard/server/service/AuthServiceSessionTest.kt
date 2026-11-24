package com.padguard.server.service

import com.padguard.server.common.BizException
import com.padguard.server.domain.User
import com.padguard.server.domain.UserSession
import com.padguard.server.dto.LoginPasswordRequest
import com.padguard.server.dto.UserSessionView
import com.padguard.server.repository.UserRepository
import com.padguard.server.repository.UserSessionRepository
import com.padguard.server.security.JwtTokenProvider
import com.padguard.server.security.Passwords
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import java.util.LinkedHashMap

/**
 * F-01 单会话令牌吊销 / refresh jti 轮换 的单元测试。
 *
 * 覆盖：登录即建会话、refresh 轮换 jti 且旧令牌立即失效（防重放）、
 * 单设备 revokeSession 只废那一台、整账号 logout 废全部会话、
 * listSessions 只回未吊销且不含敏感字段。
 */
@ExtendWith(MockitoExtension::class)
class AuthServiceSessionTest {

    private val jwt = JwtTokenProvider("test-secret-0123456789abcdef0123456789ab", 120, 30)
    private val userRepo = FakeUserRepoSession()
    private val sessionRepo = FakeUserSessionRepo()
    private val billingService = Mockito.mock(BillingService::class.java)
    private val env = Mockito.mock(org.springframework.core.env.Environment::class.java)

    private fun makeAuth(): AuthService =
        AuthService(userRepo, jwt, billingService, false, env, sessionRepo)

    private fun seedUser(phone: String = "13800000001"): User {
        val u = User(
            id = "U1",
            phone = phone,
            passwordHash = Passwords.encode("secret123"),
            nickname = "P",
            role = "",
            sceneType = "",
            status = "active"
        )
        userRepo.save(u)
        return u
    }

    @Test
    fun `login creates a session and refresh rotates jti`() {
        val user = seedUser()
        val auth = makeAuth()

        val resp = auth.loginWithPassword(LoginPasswordRequest(phone = user.phone, password = "secret123"))
        // 登录后库里恰好一条未吊销会话
        assertEquals(1, sessionRepo.table.size)
        val oldJti = jwt.parseUserToken(resp.refreshToken, "refresh").jti!!
        assertNotNull(sessionRepo.findByRefreshJtiAndRevokedFalse(oldJti))

        // refresh：应轮换 jti，且旧 jti 在表中不再对应未吊销会话
        val newTokens = auth.refresh(resp.refreshToken)
        val newJti = jwt.parseUserToken(newTokens.refreshToken, "refresh").jti!!
        assertNotEquals(oldJti, newJti)
        assertNull(sessionRepo.findByRefreshJtiAndRevokedFalse(oldJti), "旧 jti 应已失效")
        assertNotNull(sessionRepo.findByRefreshJtiAndRevokedFalse(newJti), "新 jti 应可续期")
        assertEquals(1, sessionRepo.table.size, "轮换不应新增会话行")
    }

    @Test
    fun `replaying a rotated refresh token is rejected`() {
        val user = seedUser()
        val auth = makeAuth()
        val resp = auth.loginWithPassword(LoginPasswordRequest(phone = user.phone, password = "secret123"))
        // 先用一次，把 jti 轮换掉
        auth.refresh(resp.refreshToken)
        // 再用同一个旧 refreshToken：jti 已不在未吊销表里 → 拒绝
        assertThrows(BizException::class.java) { auth.refresh(resp.refreshToken) }
    }

    @Test
    fun `concurrent refresh with same token only one rotation succeeds (CAS atomic)`() {
        val user = seedUser()
        val auth = makeAuth()
        val resp = auth.loginWithPassword(LoginPasswordRequest(phone = user.phone, password = "secret123"))
        val oldJti = jwt.parseUserToken(resp.refreshToken, "refresh").jti!!

        // 模拟两个并发请求：各自算出新 jti，都基于同一个旧 jti 尝试轮换。
        // 条件 UPDATE 保证只有一个能认领旧 jti（影响 1 行），另一个旧 jti 已失配（影响 0 行）。
        // 旧实现「读改写」会让两者都通过校验、各自签出一对有效令牌——这是本用例要锁死的洞。
        val first = sessionRepo.rotateRefreshJti(oldJti, "rotated-A", user.id, 1000L)
        val second = sessionRepo.rotateRefreshJti(oldJti, "rotated-B", user.id, 2000L)

        assertEquals(1, first, "第一个请求应成功认领旧 jti")
        assertEquals(0, second, "第二个并发请求用同一旧 jti 必须失败——否则 refresh token 可并发重放")
        assertNotNull(sessionRepo.findByRefreshJtiAndRevokedFalse("rotated-A"), "胜出的 jti 应可用")
        assertNull(sessionRepo.findByRefreshJtiAndRevokedFalse("rotated-B"), "落败的 jti 不得进入未吊销表")
        assertEquals(1, sessionRepo.table.size, "轮换不应新增会话行")
    }

    @Test
    fun `rotate refresh jti rejects foreign user id (ownership enforced in WHERE)`() {
        val user = seedUser()
        val auth = makeAuth()
        val resp = auth.loginWithPassword(LoginPasswordRequest(phone = user.phone, password = "secret123"))
        val oldJti = jwt.parseUserToken(resp.refreshToken, "refresh").jti!!

        // 归属校验并入 WHERE：即使 jti 正确，userId 不匹配也必须拒绝
        // （等价于旧实现的 session.userId != user.id 校验，但现在是原子的一步）
        assertEquals(0, sessionRepo.rotateRefreshJti(oldJti, "hijacked", "ANOTHER-USER", 1000L))
        assertNotNull(sessionRepo.findByRefreshJtiAndRevokedFalse(oldJti), "被拒绝的轮换不得改动原会话")
    }

    @Test
    fun `single-device revoke only kills that session and not a new login`() {
        val user = seedUser()
        val auth = makeAuth()
        val resp = auth.loginWithPassword(LoginPasswordRequest(phone = user.phone, password = "secret123"))
        val session = sessionRepo.table.values.first()
        val oldJti = jwt.parseUserToken(resp.refreshToken, "refresh").jti!!

        // 只吊销这一台设备
        auth.revokeSession(user.id, session.id)
        assertTrue(sessionRepo.table[session.id]!!.revoked, "该会话应被置位 revoked")
        assertThrows(BizException::class.java) { auth.refresh(resp.refreshToken) }

        // token_version 没变（单设备退出语义），但被退出的那台已废；新登录另开一条可用会话
        assertEquals(0, user.tokenVersion, "单设备退出不应 bump 全局 token_version")
        val resp2 = auth.loginWithPassword(LoginPasswordRequest(phone = user.phone, password = "secret123"))
        val newJti = jwt.parseUserToken(resp2.refreshToken, "refresh").jti!!
        assertNotNull(sessionRepo.findByRefreshJtiAndRevokedFalse(newJti))
        // 旧 jti 对应的那条已被标记 revoked，新 jti 是另一条
        assertNotEquals(oldJti, newJti)
    }

    @Test
    fun `global logout revokes all sessions`() {
        val user = seedUser()
        val auth = makeAuth()
        val resp = auth.loginWithPassword(LoginPasswordRequest(phone = user.phone, password = "secret123"))
        // 再登一次，制造两台会话
        auth.loginWithPassword(LoginPasswordRequest(phone = user.phone, password = "secret123"))
        assertEquals(2, sessionRepo.table.size)

        auth.logout(user.id)
        assertTrue(sessionRepo.table.values.all { it.revoked }, "logout 应吊销全部会话")
        assertThrows(BizException::class.java) { auth.refresh(resp.refreshToken) }
    }

    @Test
    fun `listSessions returns only active sessions and excludes sensitive fields`() {
        val user = seedUser()
        val auth = makeAuth()
        auth.loginWithPassword(LoginPasswordRequest(phone = user.phone, password = "secret123"))
        val s1 = sessionRepo.table.values.first()
        // 再建一条并立刻吊销
        auth.loginWithPassword(LoginPasswordRequest(phone = user.phone, password = "secret123"))
        val s2 = sessionRepo.table.values.first { it.id != s1.id }
        s2.revoked = true

        val views: List<UserSessionView> = auth.listSessions(user.id)
        assertEquals(1, views.size, "应只列未吊销会话")
        assertEquals(s1.id, views[0].id)
        // 返回类型确为脱敏后的 DTO，而非实体本身（不携带 refreshJti / userId / revoked）
        assertEquals(UserSessionView::class, views[0]::class, "listSessions 应返回脱敏 DTO 而非实体")
    }

    // ---- 内存假仓储 ----

    class FakeUserRepoSession : FakeJpaRepository<User, String>(), UserRepository {
        override val table = LinkedHashMap<String, User>()
        override fun idOf(e: User): String = e.id
        override fun findByPhone(phone: String): User? = table.values.firstOrNull { it.phone == phone }
        override fun existsByPhone(phone: String): Boolean = table.values.any { it.phone == phone }
        override fun countByStatus(status: String): Long =
            table.values.count { (it.status ?: "active") == status }.toLong()
        override fun findByIdForUpdate(id: String): User? = table[id]
        override fun registerLoginFailure(phone: String, maxAttempts: Int, lockUntil: Long): Int = 0
        override fun clearLoginFailure(phone: String): Int = 0
        override fun touchLastLoginAt(id: String, ts: Long): Int {
            table[id]?.lastLoginAt = ts
            return if (table.containsKey(id)) 1 else 0
        }
        override fun searchParents(
            status: String, keyword: String, kw: String, planMode: String, pageable: Pageable
        ) = PageImpl<User>(emptyList(), pageable, 0)
        override fun countByStatusOrNullAsActive(status: String): Long = countByStatus(status)
        override fun countGroupedByRegion(): List<Array<Any?>> = emptyList()
        override fun findCreatedAtSince(since: Long): List<Long> = emptyList()
    }

    class FakeUserSessionRepo : FakeJpaRepository<UserSession, String>(), UserSessionRepository {
        override val table = LinkedHashMap<String, UserSession>()
        override fun idOf(e: UserSession): String = e.id

        override fun findByUserIdAndRevokedFalse(userId: String): List<UserSession> =
            table.values.filter { it.userId == userId && !it.revoked }

        override fun findByRefreshJtiAndRevokedFalse(jti: String): UserSession? =
            table.values.firstOrNull { it.refreshJti == jti && !it.revoked }

        override fun findByUserIdAndIdAndRevokedFalse(userId: String, id: String): UserSession? =
            table[id]?.takeIf { it.userId == userId && !it.revoked }

        override fun countByUserIdAndRevokedFalse(userId: String): Long =
            table.values.count { it.userId == userId && !it.revoked }.toLong()

        override fun revokeAllForUser(userId: String, at: Long): Int {
            var n = 0
            table.values.forEach {
                if (it.userId == userId && !it.revoked) {
                    it.revoked = true
                    it.revokedAt = at
                    n++
                }
            }
            return n
        }

        /** 原子轮换的 fake：按 oldJti + userId + 未吊销精确认领，一次成功后旧 jti 即失配。 */
        override fun rotateRefreshJti(oldJti: String, newJti: String, userId: String, at: Long): Int {
            val target = table.values.firstOrNull {
                it.refreshJti == oldJti && it.userId == userId && !it.revoked
            } ?: return 0
            target.refreshJti = newJti
            target.lastRefreshedAt = at
            return 1
        }
    }
}
