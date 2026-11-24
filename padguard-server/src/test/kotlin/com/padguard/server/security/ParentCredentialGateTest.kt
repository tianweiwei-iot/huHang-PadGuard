package com.padguard.server.security

import com.padguard.server.domain.User
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [ParentCredentialGate] 纯函数回归（新增缺陷 M）。
 *
 * 这层是登录与「账号密码绑定设备」两条入口共用的判定真相，
 * 重点钉住：锁定期边界、状态口径、脏配置值不得让锁定失效。
 */
class ParentCredentialGateTest {

    private fun user(status: String? = "active", failCount: Int? = 0, lockUntil: Long? = null) =
        User(
            id = "U1", phone = "13800000000", passwordHash = "hash", nickname = "家长",
            status = status, loginFailCount = failCount, loginLockedUntil = lockUntil
        )

    @Test
    fun `active account is usable`() {
        assertTrue(ParentCredentialGate.isUsable(user()))
        assertTrue(ParentCredentialGate.isUsable(user(status = null))) // 存量行 NULL 按 active 兜底
    }

    @Test
    fun `suspended and pending accounts are not usable`() {
        assertFalse(ParentCredentialGate.isUsable(user(status = "suspended")))
        assertFalse(ParentCredentialGate.isUsable(user(status = "PENDING")))
    }

    @Test
    fun `lock gate only blocks while lockUntil is in the future`() {
        val now = 1_000_000L
        assertFalse(ParentCredentialGate.isLocked(null, now))
        assertFalse(ParentCredentialGate.isLocked(now, now))
        assertFalse(ParentCredentialGate.isLocked(now - 1, now))
        assertTrue(ParentCredentialGate.isLocked(now + 1, now))
    }

    @Test
    fun `remaining minutes never reports zero`() {
        val now = 1_000_000L
        assertEquals(1, ParentCredentialGate.remainingMinutes(now + 1, now))
        assertEquals(15, ParentCredentialGate.remainingMinutes(now + 15 * 60_000L, now))
    }

    @Test
    fun `failure counter is cleared only when there is residue`() {
        assertFalse(ParentCredentialGate.shouldClearFailure(0, null))
        assertFalse(ParentCredentialGate.shouldClearFailure(null, 0L))
        assertTrue(ParentCredentialGate.shouldClearFailure(2, 0L))
        assertTrue(ParentCredentialGate.shouldClearFailure(0, 1L))
    }

    @Test
    fun `dirty config values cannot disable the lock`() {
        assertEquals(1, ParentCredentialGate.maxAttempts(0))
        assertEquals(1, ParentCredentialGate.maxAttempts(-7))
        assertEquals(5, ParentCredentialGate.maxAttempts(5))
        val now = 1_000_000L
        assertTrue(ParentCredentialGate.lockUntilAfter(now, 0) > now)
        assertTrue(ParentCredentialGate.lockUntilAfter(now, -3) > now)
        assertEquals(now + 15 * 60_000L, ParentCredentialGate.lockUntilAfter(now, 15))
    }
}
