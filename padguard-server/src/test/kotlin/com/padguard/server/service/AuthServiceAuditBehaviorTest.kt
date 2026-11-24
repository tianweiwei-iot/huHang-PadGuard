package com.padguard.server.service

import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.AuditLog
import com.padguard.server.domain.User
import com.padguard.server.domain.UserSession
import com.padguard.server.dto.ChangePasswordRequest
import com.padguard.server.repository.AuditLogRepository
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.UserRepository
import com.padguard.server.repository.UserSessionRepository
import com.padguard.server.security.DeviceOwnershipGuard
import com.padguard.server.security.JwtTokenProvider
import com.padguard.server.security.Passwords
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.mock.env.MockEnvironment
import java.util.LinkedHashMap

/**
 * R45 审计链路**行为化**回归（2026-11-18 批次六：源码文本锁 → 行为断言）。
 *
 * 原 AuthAuditTrailContractTest 用源码扫描断言"方法体里出现过 auditTrail?.record"，
 * 只能证明"有这行代码"，不能证明审计真的落库、动作类型对不对、detail 是否泄密、
 * 失败路径是否误留痕。本文件直跑三个凭证治理入口，用**真实的 AuditService** +
 * 内存版 AuditLogRepository 断言落库结果：
 * 1. 改密 / 登出 / 吊销会话各落对应动作的审计行；
 * 2. 被拒绝的操作（原密码错）不留痕；
 * 3. detail 绝不包含明文密码；
 * 4. 审计写失败不影响主流程（record 内部 runCatching 的承诺落到行为层）。
 *
 * 为什么不用 Mockito mock AuditService：mockito 当前配置无法拦截 final 方法的参数
 * 匹配（Kotlin 默认参数还会走 record$default 静态桥），verify 恒报
 * "Missing method call / final cannot be verified"。真服务 + fake 仓储完全绕开
 * mock 语义，且顺带验证了防篡改哈希计算路径，比 mock 更真实。
 */
class AuthServiceAuditBehaviorTest {

    private class FakeUserRepo : FakeJpaRepository<User, String>(), UserRepository {
        override val table = LinkedHashMap<String, User>()
        override fun idOf(e: User) = e.id
        override fun existsByPhone(phone: String) = table.values.any { it.phone == phone }
        override fun findByPhone(phone: String): User? = table.values.firstOrNull { it.phone == phone }
        override fun countByStatus(status: String) = table.values.count { (it.status ?: "active") == status }.toLong()
        override fun findByIdForUpdate(id: String): User? = table[id]
        override fun registerLoginFailure(phone: String, maxAttempts: Int, lockUntil: Long): Int {
            val u = table.values.firstOrNull { it.phone == phone } ?: return 0
            val next = (u.loginFailCount ?: 0) + 1
            if (next >= maxAttempts) {
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
            pageable: Pageable
        ): Page<User> = PageImpl(emptyList(), pageable, 0)

        override fun countByStatusOrNullAsActive(status: String): Long =
            table.values.count { (it.status ?: "active") == status }.toLong()

        override fun countGroupedByRegion(): List<Array<Any?>> =
            table.values.groupBy { it.region }
                .map { arrayOf(it.key, it.value.size.toLong()) as Array<Any?> }

        override fun findCreatedAtSince(since: Long): List<Long> =
            table.values.map { it.createdAt }.filter { it >= since }
    }

    private class FakeSessionRepo : FakeJpaRepository<UserSession, String>(), UserSessionRepository {
        override val table = LinkedHashMap<String, UserSession>()
        override fun idOf(e: UserSession) = e.id
        override fun findByUserIdAndRevokedFalse(userId: String) =
            table.values.filter { it.userId == userId && !it.revoked }

        override fun findByRefreshJtiAndRevokedFalse(jti: String): UserSession? =
            table.values.firstOrNull { it.refreshJti == jti && !it.revoked }

        override fun findByUserIdAndIdAndRevokedFalse(userId: String, id: String): UserSession? =
            table.values.firstOrNull { it.userId == userId && it.id == id && !it.revoked }

        override fun countByUserIdAndRevokedFalse(userId: String) =
            table.values.count { it.userId == userId && !it.revoked }.toLong()

        override fun revokeAllForUser(userId: String, at: Long): Int {
            val affected = table.values.filter { it.userId == userId && !it.revoked }
            affected.forEach { it.revoked = true; it.revokedAt = at }
            return affected.size
        }

        override fun rotateRefreshJti(oldJti: String, newJti: String, userId: String, at: Long): Int {
            val s = table.values.firstOrNull { it.refreshJti == oldJti && !it.revoked } ?: return 0
            s.refreshJti = newJti
            s.lastRefreshedAt = at
            return 1
        }
    }

    /** 内存版审计仓储：默认正常落库；置 [failWrites] 后 save 抛异常（验证主流程不受影响） */
    private class FakeAuditLogRepo : FakeJpaRepository<AuditLog, String>(), AuditLogRepository {
        override val table = LinkedHashMap<String, AuditLog>()
        override fun idOf(e: AuditLog) = e.id
        var failWrites = false

        override fun <S : AuditLog> save(entity: S): S {
            if (failWrites) throw RuntimeException("audit backend down")
            table[idOf(entity)] = entity
            return entity
        }

        override fun findByUserIdAndCreatedAtBetween(userId: String, start: Long, end: Long) =
            table.values.filter { it.userId == userId && it.createdAt in start..end }

        override fun findByDeviceIdAndCreatedAtBetween(deviceId: String, start: Long, end: Long) =
            table.values.filter { it.deviceId == deviceId && it.createdAt in start..end }

        override fun findAllByOrderByCreatedAtDesc() =
            table.values.sortedByDescending { it.createdAt }

        override fun findAllByOrderByCreatedAtDesc(pageable: Pageable): Page<AuditLog> =
            PageImpl(table.values.sortedByDescending { it.createdAt }, pageable, table.size.toLong())

        override fun findByActionInOrderByCreatedAtDesc(actions: Collection<String>, pageable: Pageable): Page<AuditLog> =
            PageImpl(table.values.filter { it.action in actions }.sortedByDescending { it.createdAt }, pageable, table.size.toLong())

        override fun findByDeviceIdOrderByCreatedAtDesc(deviceId: String, pageable: Pageable) =
            table.values.filter { it.deviceId == deviceId }.sortedByDescending { it.createdAt }

        override fun findByDeviceIdAndActionOrderByCreatedAtDesc(deviceId: String, action: String, pageable: Pageable) =
            table.values.filter { it.deviceId == deviceId && it.action == action }.sortedByDescending { it.createdAt }

        override fun findByUserIdOrderByCreatedAtDesc(userId: String, pageable: Pageable) =
            table.values.filter { it.userId == userId }.sortedByDescending { it.createdAt }

        override fun findByUserIdAndActionOrderByCreatedAtDesc(userId: String, action: String, pageable: Pageable) =
            table.values.filter { it.userId == userId && it.action == action }.sortedByDescending { it.createdAt }
    }

    private fun fixture(failAuditWrites: Boolean = false): Quad {
        val users = FakeUserRepo()
        val sessions = FakeSessionRepo()
        val auditRepo = FakeAuditLogRepo().apply { failWrites = failAuditWrites }
        val audit = AuditService(auditRepo, DeviceOwnershipGuard(Mockito.mock(DeviceRepository::class.java)))
        val auth = AuthService(
            users,
            JwtTokenProvider("padguard-unit-test-secret-0123456789abcdef", 120, 30),
            Mockito.mock(BillingService::class.java),
            false,
            MockEnvironment(),
            sessions
        )
        // auditTrail 是 @Autowired(required=false) 字段注入：JVM 直构时反射补齐
        val f = AuthService::class.java.getDeclaredField("auditTrail")
        f.isAccessible = true
        f.set(auth, audit)
        return Quad(auth, users, sessions, auditRepo)
    }

    /** 四元组小载体：auth / userRepo / sessionRepo / auditRepo */
    private data class Quad(
        val auth: AuthService,
        val users: FakeUserRepo,
        val sessions: FakeSessionRepo,
        val auditRepo: FakeAuditLogRepo
    )

    private fun seededUser(users: FakeUserRepo, id: String, phone: String, password: String): User {
        val u = User(
            id = id, phone = phone,
            passwordHash = Passwords.encode(password),
            role = "PARENT", sceneType = "FAMILY", status = "active",
            createdAt = System.currentTimeMillis()
        )
        users.table[u.id] = u
        return u
    }

    private fun auditRows(repo: FakeAuditLogRepo, action: String) =
        repo.table.values.filter { it.action == action }

    @Test
    fun `改密成功必须落 PARENT_PASSWORD_CHANGE 审计行`() {
        val fx = fixture()
        seededUser(fx.users, "u-audit-1", "13900009999", "old-pass-123456")

        fx.auth.changePassword("u-audit-1", ChangePasswordRequest("old-pass-123456", "new-pass-654321"))

        val rows = auditRows(fx.auditRepo, AuditAction.PARENT_PASSWORD_CHANGE)
        assertTrue(rows.size == 1, "改密必须恰好落 1 条审计行，实际 ${rows.size}")
        assertEquals("u-audit-1", rows[0].userId)
        assertTrue(!rows[0].detail.isNullOrBlank(), "审计 detail 必须非空")
        assertEquals("SUCCESS", rows[0].result)
    }

    @Test
    fun `原密码错误被拒绝时不得留任何审计`() {
        val fx = fixture()
        seededUser(fx.users, "u-audit-1", "13900009999", "old-pass-123456")

        val ex = assertThrows(BizException::class.java) {
            fx.auth.changePassword("u-audit-1", ChangePasswordRequest("wrong-old-pass", "new-pass-654321"))
        }
        assertEquals(ParentErr.UNAUTHORIZED, ex.code, "原密码错误必须被拒")
        assertTrue(fx.auditRepo.table.isEmpty(), "被拒绝的操作不得留任何审计痕")
    }

    @Test
    fun `审计 detail 不得包含新旧密码明文`() {
        val fx = fixture()
        seededUser(fx.users, "u-audit-1", "13900009999", "secret-old-pass-9")

        fx.auth.changePassword("u-audit-1", ChangePasswordRequest("secret-old-pass-9", "secret-new-pass-7"))

        val rows = auditRows(fx.auditRepo, AuditAction.PARENT_PASSWORD_CHANGE)
        val detail = rows.single().detail ?: ""
        assertTrue(
            "secret-old-pass-9" !in detail && "secret-new-pass-7" !in detail,
            "审计 detail 绝不落明文密码，实际: $detail"
        )
    }

    @Test
    fun `登出必须落 PARENT_LOGOUT 审计行`() {
        val fx = fixture()
        seededUser(fx.users, "u-audit-1", "13900009999", "pwd-not-relevant")

        val result = fx.auth.logout("u-audit-1")

        assertEquals(true, result["ok"])
        val rows = auditRows(fx.auditRepo, AuditAction.PARENT_LOGOUT)
        assertTrue(rows.size == 1, "登出必须恰好落 1 条审计行，实际 ${rows.size}")
        assertEquals("u-audit-1", rows[0].userId)
    }

    @Test
    fun `吊销单设备会话必须落 PARENT_SESSION_REVOKE 且 targetId 等于会话 id`() {
        val fx = fixture()
        seededUser(fx.users, "u-audit-2", "13900008888", "pwd-not-relevant")
        fx.sessions.table["sess-1"] = UserSession(id = "sess-1", userId = "u-audit-2", refreshJti = "jti-1")

        fx.auth.revokeSession("u-audit-2", "sess-1")

        val rows = auditRows(fx.auditRepo, AuditAction.PARENT_SESSION_REVOKE)
        assertTrue(rows.size == 1, "吊销会话必须恰好落 1 条审计行，实际 ${rows.size}")
        assertEquals("u-audit-2", rows[0].userId)
        assertEquals("session", rows[0].targetType)
        assertEquals("sess-1", rows[0].targetId)
    }

    @Test
    fun `审计写入抛异常不得影响改密主流程`() {
        val fx = fixture(failAuditWrites = true)
        seededUser(fx.users, "u-audit-1", "13900009999", "old-pass-abc123")

        // record 内部 runCatching 吞异常（REQUIRES_NEW + 永不外抛语义）——主流程必须照常返回新令牌
        val resp = fx.auth.changePassword("u-audit-1", ChangePasswordRequest("old-pass-abc123", "new-pass-xyz789"))

        assertTrue(resp.token.isNotBlank(), "审计失败绝不能阻断改密")
        assertTrue(fx.users.table["u-audit-1"]!!.tokenVersion > 0, "改密必须推进令牌版本")
        assertTrue(fx.auditRepo.table.isEmpty(), "写失败的审计不得留下半行脏数据")
    }
}
