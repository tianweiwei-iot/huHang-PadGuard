package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.security.AdminPermissionService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

/**
 * [StorageMaintenanceService] —— 后台「能删磁盘」的危险动作闸门。
 *
 * 服务刻意与只读的 AdminServerMonitorService 分开：给同事开「查看监控」不应该顺手
 * 给了删除能力。测试重点是 server:write 闸门、宽限期钳制、审计留痕，
 * 以及 dbReadFailed / truncated 如实透出（只看 deleted=0 会把故障当成「没有垃圾」）。
 */
class StorageMaintenanceServiceTest {

    private lateinit var perm: AdminPermissionService
    private lateinit var cleanup: RetentionCleanup
    private lateinit var audit: AuditService
    private lateinit var svc: StorageMaintenanceService

    private fun result(
        scanned: Int = 12, deleted: Int = 3, bytesFreed: Long = 4096L,
        samples: List<String> = listOf("shell.php.png"), truncated: Boolean = false,
        dbReadFailed: Boolean = false
    ) = FileStorageService.OrphanSweepResult(scanned, deleted, bytesFreed, samples, truncated, dbReadFailed)

    @BeforeEach
    fun setUp() {
        perm = mock(AdminPermissionService::class.java)
        cleanup = mock(RetentionCleanup::class.java)
        audit = mock(AuditService::class.java)
        `when`(cleanup.sweepOrphanFiles(anyLong(), anyBoolean())).thenReturn(result())
        svc = StorageMaintenanceService(perm, cleanup, audit)
    }

    /** record 有默认参数：JVM 上是 8 参，verify 必须给满 8 个 matcher。 */
    private fun captureAuditDetail(): String {
        val captor = ArgumentCaptor.forClass(String::class.java)
        verify(audit).record(
            eq("a1") ?: "a1",
            isNull(),
            eq(AdminAudit.STORAGE_SWEEP) ?: AdminAudit.STORAGE_SWEEP,
            eq("storage") ?: "storage",
            eq("uploads") ?: "uploads",
            captor.capture(),
            anyString(),
            isNull()
        )
        return captor.value
    }

    // ----------------------------- RBAC 闸门 -----------------------------

    @Test
    fun `无 server write 权限被拒且绝不触发磁盘清扫`() {
        doThrow(BizException(ParentErr.FORBIDDEN, "无权限", Audience.PARENT))
            .`when`(perm).require("a1", "server", "write")

        val ex = assertThrows(BizException::class.java) { svc.sweepOrphanFiles("a1", 7, false) }

        assertEquals(ParentErr.FORBIDDEN, ex.code)
        verify(cleanup, never()).sweepOrphanFiles(anyLong(), anyBoolean())
    }

    @Test
    fun `有权限时按 server write 闸门放行`() {
        svc.sweepOrphanFiles("a1", 7, true)
        verify(perm).require("a1", "server", "write")
    }

    // ----------------------------- 宽限期钳制 -----------------------------

    @Test
    fun `graceDays 为空取默认 7 天`() {
        svc.sweepOrphanFiles("a1", null, true)
        verify(cleanup).sweepOrphanFiles(eq(RetentionCleanup.ORPHAN_GRACE_DAYS) ?: 7L, eq(true) ?: true)
    }

    @Test
    fun `graceDays 下界钳到 1 天 - 不接受立刻删全部`() {
        svc.sweepOrphanFiles("a1", 0, true)
        svc.sweepOrphanFiles("a1", -10, true)
        verify(cleanup, org.mockito.Mockito.times(2)).sweepOrphanFiles(eq(1L) ?: 1L, eq(true) ?: true)
    }

    @Test
    fun `graceDays 上界钳到 365 天`() {
        svc.sweepOrphanFiles("a1", 9999, true)
        verify(cleanup).sweepOrphanFiles(eq(365L) ?: 365L, eq(true) ?: true)
    }

    @Test
    fun `graceDays 合法区间原样透出到返回值`() {
        val r = svc.sweepOrphanFiles("a1", 30, true)
        assertEquals(30L, r["graceDays"])
        verify(cleanup).sweepOrphanFiles(eq(30L) ?: 30L, eq(true) ?: true)
    }

    // ----------------------------- 审计留痕 -----------------------------

    @Test
    fun `预演与执行都落审计且文案区分`() {
        svc.sweepOrphanFiles("a1", 7, dryRun = true)
        val preview = captureAuditDetail()
        assertTrue(preview.contains("预演"))
        assertTrue(preview.contains("待删"))

        org.mockito.Mockito.reset(audit)
        svc.sweepOrphanFiles("a1", 7, dryRun = false)
        val executed = captureAuditDetail()
        assertTrue(executed.contains("执行"))
        assertTrue(executed.contains("删除"))
    }

    @Test
    fun `读库失败必须写进审计文案`() {
        `when`(cleanup.sweepOrphanFiles(anyLong(), anyBoolean())).thenReturn(result(deleted = 0, dbReadFailed = true))

        svc.sweepOrphanFiles("a1", 7, false)

        assertTrue(captureAuditDetail().contains("读库失败未删除"))
    }

    @Test
    fun `达到单次上限必须写进审计文案`() {
        `when`(cleanup.sweepOrphanFiles(anyLong(), anyBoolean())).thenReturn(result(truncated = true))

        svc.sweepOrphanFiles("a1", 7, false)

        assertTrue(captureAuditDetail().contains("已达单次上限"))
    }

    @Test
    fun `审计动作与目标固定为 storage uploads`() {
        svc.sweepOrphanFiles("a1", 7, true)
        // captureAuditDetail 内部已按 STORAGE_SWEEP / storage / uploads 做了精确 verify
        assertTrue(captureAuditDetail().contains("宽限 7 天"))
    }

    // ----------------------------- 结果透出 -----------------------------

    @Test
    fun `dbReadFailed 与 truncated 如实透出供前端区分故障与无垃圾`() {
        `when`(cleanup.sweepOrphanFiles(anyLong(), anyBoolean()))
            .thenReturn(result(deleted = 0, dbReadFailed = true, truncated = true))

        val r = svc.sweepOrphanFiles("a1", 7, true)

        assertEquals(true, r["dbReadFailed"])
        assertEquals(true, r["truncated"])
        assertEquals(0, r["deleted"])
    }

    @Test
    fun `扫描数删除数字节数与样例原样透出`() {
        `when`(cleanup.sweepOrphanFiles(anyLong(), anyBoolean()))
            .thenReturn(result(scanned = 42, deleted = 5, bytesFreed = 8192L, samples = listOf("a.png", "b.png")))

        val r = svc.sweepOrphanFiles("a1", 7, false)

        assertFalse(r["dryRun"] as Boolean)
        assertEquals(42, r["scanned"])
        assertEquals(5, r["deleted"])
        assertEquals(8192L, r["bytesFreed"])
        assertEquals(listOf("a.png", "b.png"), r["samples"])
        assertFalse(r["truncated"] as Boolean)
    }
}
