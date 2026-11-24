package com.padguard.server.service

import com.padguard.server.common.BizException
import com.padguard.server.domain.AuditLog
import com.padguard.server.domain.Device
import com.padguard.server.repository.AuditLogRepository
import com.padguard.server.security.DeviceOwnershipGuard
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.PageRequest

/**
 * `AuditService` 是合规审计的信任关键服务：审计日志独立事务落库（主事务回滚也不丢）、
 * 防篡改哈希、分页查询带 IDOR 归属校验。此前**零单测**。
 *
 * 覆盖：
 * - `record`：落库带防篡改 hash、detail 截断 4000、写库失败绝不外抛；
 * - `query`：deviceId+userId 时先过 [DeviceOwnershipGuard]（防越权读他人审计流水）、
 *   分页 size 钳制 [1,100]、负页码归零、按 action 过滤；
 * - `verifyIntegrity`：检出被篡改的 hash、跳过历史 null hash 行。
 *
 * 不加载 Spring 上下文；`record` 用 REQUIRES_NEW 独立事务，无事务时直接落库，行为可预期。
 */
class AuditServiceTest {
    private val auditLogRepository = mock(AuditLogRepository::class.java)
    private val ownershipGuard = mock(DeviceOwnershipGuard::class.java)

    private fun svc(): AuditService = AuditService(auditLogRepository, ownershipGuard)

    @Test
    fun record_savesAuditLogWithIntegrityHash() {
        var saved: AuditLog? = null
        `when`(auditLogRepository.save(any(AuditLog::class.java))).thenAnswer { args ->
            saved = args.arguments[0] as AuditLog
            args.arguments[0]
        }
        svc().record("u1", "d1", AuditAction.DEVICE_BIND)
        assertNotNull(saved)
        assertTrue(saved!!.id.startsWith("al_"))
        assertNotNull(saved!!.hash)
        assertEquals(64, saved!!.hash!!.length)
        assertEquals("u1", saved!!.userId)
        assertEquals(AuditAction.DEVICE_BIND, saved!!.action)
        assertTrue(saved!!.createdAt > 0)
    }

    @Test
    fun record_detailTruncatedTo4000Chars() {
        var saved: AuditLog? = null
        `when`(auditLogRepository.save(any(AuditLog::class.java))).thenAnswer { args ->
            saved = args.arguments[0] as AuditLog
            args.arguments[0]
        }
        svc().record("u1", "d1", AuditAction.DEVICE_BIND, detail = "x".repeat(5000))
        assertEquals(4000, saved!!.detail!!.length)
    }

    @Test
    fun record_writeFailureDoesNotPropagate() {
        `when`(auditLogRepository.save(any(AuditLog::class.java))).thenThrow(RuntimeException("db down"))
        // 审计写失败绝不可导致主流程失败
        svc().record("u1", "d1", AuditAction.DEVICE_BIND)
    }

    @Test
    fun query_requiresOwnershipWhenDeviceAndUserGiven() {
        `when`(ownershipGuard.requireOwned(eq("u1") ?: "u1", eq("d1") ?: "d1"))
            .thenReturn(Device(id = "d1", userId = "u1"))
        `when`(auditLogRepository.findByDeviceIdOrderByCreatedAtDesc(eq("d1") ?: "d1", any(Pageable::class.java) ?: PageRequest.of(0, 1)))
            .thenReturn(emptyList())
        svc().query("u1", "d1", null, 0, 20)
        verify(ownershipGuard).requireOwned(eq("u1") ?: "u1", eq("d1") ?: "d1")
    }

    @Test
    fun query_noOwnershipCheckWhenUserAbsent() {
        `when`(auditLogRepository.findByDeviceIdOrderByCreatedAtDesc(eq("d1") ?: "d1", any(Pageable::class.java) ?: PageRequest.of(0, 1)))
            .thenReturn(emptyList())
        svc().query(null, "d1", null, 0, 20)
        verify(ownershipGuard, never()).requireOwned(anyString(), anyString())
    }

    @Test
    fun query_pageSizeClampedTo100() {
        var pageable: Pageable? = null
        `when`(auditLogRepository.findByDeviceIdOrderByCreatedAtDesc(eq("d1") ?: "d1", any(Pageable::class.java) ?: PageRequest.of(0, 1)))
            .thenAnswer { args ->
                pageable = args.arguments[1] as Pageable
                emptyList<AuditLog>()
            }
        svc().query(null, "d1", null, 0, 200)
        assertEquals(100, pageable!!.pageSize)
    }

    @Test
    fun query_negativePageCoercedToZero() {
        var pageable: Pageable? = null
        `when`(auditLogRepository.findByDeviceIdOrderByCreatedAtDesc(eq("d1") ?: "d1", any(Pageable::class.java) ?: PageRequest.of(0, 1)))
            .thenAnswer { args ->
                pageable = args.arguments[1] as Pageable
                emptyList<AuditLog>()
            }
        svc().query(null, "d1", null, -5, 20)
        assertEquals(0, pageable!!.pageNumber)
    }

    @Test
    fun query_filtersByActionWhenProvided() {
        `when`(auditLogRepository.findByDeviceIdAndActionOrderByCreatedAtDesc(eq("d1") ?: "d1", eq("FOO") ?: "FOO", any(Pageable::class.java) ?: PageRequest.of(0, 1)))
            .thenReturn(emptyList())
        val result = svc().query(null, "d1", "FOO", 0, 20)
        assertTrue(result.isEmpty())
        verify(auditLogRepository).findByDeviceIdAndActionOrderByCreatedAtDesc(eq("d1") ?: "d1", eq("FOO") ?: "FOO", any(Pageable::class.java) ?: PageRequest.of(0, 1))
    }

    @Test
    fun verifyIntegrity_detectsTamperedAndSkipsNullHash() {
        val rows = listOf(
            AuditLog(id = "al_ok_null", userId = "u", deviceId = "d", action = "A", hash = null, createdAt = 123),
            AuditLog(
                id = "al_bad", userId = "u", deviceId = "d", action = "A",
                hash = "0000000000000000000000000000000000000000000000000000000000000000", createdAt = 456
            )
        )
        // 用具体 PageRequest 值而非 any(Pageable)：Kotlin 下 any(Class) 返回 null 会触发 NPE / matcher 误用。
        // verifyIntegrity 默认 limit=5000，内部构造的正是 PageRequest.of(0, 5000)。
        `when`(auditLogRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 5000)))
            .thenReturn(PageImpl(rows))
        val report = svc().verifyIntegrity()
        assertEquals(2, report.checked)
        assertEquals(1, report.mismatch)
        assertTrue(report.samples.contains("al_bad"))
    }
}
