package com.padguard.server.service

import com.padguard.server.domain.AuditLog
import com.padguard.server.repository.AuditLogRepository
import com.padguard.server.security.DeviceOwnershipGuard
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import java.security.MessageDigest
import java.util.*

class AuditServiceHashTest {
    private val repo = mock(AuditLogRepository::class.java)
    private val guard = mock(DeviceOwnershipGuard::class.java)
    private fun svc() = AuditService(repo, guard)

    private fun sha256(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun record_writesTamperEvidentHash() {
        svc().record("u1", "d1", "POLICY_UPDATE", "DEVICE", "d1", "detail", "SUCCESS", "1.2.3.4")
        val captor = ArgumentCaptor.forClass(AuditLog::class.java)
        verify(repo).save(captor.capture())
        val saved = captor.value
        assertNotNull(saved.hash)
        val canonical = listOf(
            saved.id, saved.userId, saved.deviceId, saved.action, saved.targetType,
            saved.targetId, saved.detail, saved.result, saved.ip, saved.createdAt
        ).joinToString("|")
        assertEquals(sha256(canonical), saved.hash)
    }

    @Test
    fun verifyIntegrity_detectsTamperedRow() {
        val good = AuditLog(
            id = "a1", userId = "u1", action = "X", createdAt = 1,
            hash = sha256(listOf("a1", "u1", null, "X", null, null, null, "SUCCESS", null, 1L).joinToString("|"))
        )
        val tampered = AuditLog(id = "a2", userId = "u2", action = "Y", createdAt = 2, detail = "orig")
        tampered.hash = sha256(listOf("a2", "u2", null, "Y", null, null, "orig", "SUCCESS", null, 2L).joinToString("|"))
        // 模拟落库行被非法篡改
        tampered.detail = "changed"

        `when`(repo.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 5000)))
            .thenReturn(PageImpl(listOf(good, tampered)))
        val report = svc().verifyIntegrity()
        assertEquals(2, report.checked)
        assertEquals(1, report.mismatch)
        assertTrue(report.samples.contains("a2"))
    }

    @Test
    fun verifyIntegrity_ignoresPreMigrationRows() {
        // 迁移前历史行 hash 为 NULL，不参与校验
        val legacy = AuditLog(id = "old", userId = "u1", action = "X", createdAt = 1, hash = null)
        `when`(repo.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 5000)))
            .thenReturn(PageImpl(listOf(legacy)))
        val report = svc().verifyIntegrity()
        assertEquals(1, report.checked)
        assertEquals(0, report.mismatch)
    }
}
