package com.padguard.server.service

import com.padguard.server.security.AdminPermissionService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 存储维护（后台「运维操作」里的危险动作）。
 *
 * 单独成一个服务而不是塞进 [AdminServerMonitorService]：那个服务是**只读**的监控视图
 * （指标/日志），把"删文件"混进去会让"看监控"的权限语义与"能删磁盘"的动作同域，
 * 运维给同事开个"查看监控"就顺手给了删除能力。这里显式要求 `server/write` 并留审计。
 */
@Service
class StorageMaintenanceService(
    private val adminPerm: AdminPermissionService,
    private val retentionCleanup: RetentionCleanup,
    private val auditService: AuditService
) {
    private val log = LoggerFactory.getLogger(StorageMaintenanceService::class.java)

    /**
     * 手动触发 `uploads/` 磁盘孤儿清扫（盘上有、`uploaded_files` 里没有任何记录的文件）。
     *
     * 这类文件保留期清理**永远扫不到** —— [RetentionCleanup] 的每一步都以 DB 记录为起点。
     * 而发布脚本会把整个 `uploads/` 复制进安装包，测试样本（`shell.php.png` 等）
     * 会随包分发给家庭用户。
     *
     * 安全口径：
     * - 默认 `dryRun = true`，先看"有多少、都是些什么"，确认后再真删；
     * - 宽限期夹在 [1,365] 天，服务端不接受"0 天立刻删全部"；
     * - 预演与执行都落审计，写清扫描数/删除数/是否截断/读库是否失败。
     */
    fun sweepOrphanFiles(adminId: String, graceDays: Long?, dryRun: Boolean): Map<String, Any> {
        adminPerm.require(adminId, "server", "write")
        val g = (graceDays ?: RetentionCleanup.ORPHAN_GRACE_DAYS).coerceIn(1L, 365L)
        val r = retentionCleanup.sweepOrphanFiles(g, dryRun)
        auditService.record(
            adminId, null, AdminAudit.STORAGE_SWEEP,
            targetType = "storage", targetId = "uploads",
            detail = "${if (dryRun) "预演" else "执行"}孤儿文件清扫（宽限 $g 天）：" +
                "扫描 ${r.scanned} 个，${if (dryRun) "待删" else "删除"} ${r.deleted} 个" +
                "（${r.bytesFreed / 1024} KB）" +
                (if (r.truncated) "，已达单次上限" else "") +
                (if (r.dbReadFailed) "，读库失败未删除" else "")
        )
        if (r.deleted > 0 && !dryRun) {
            log.info("后台手动孤儿清扫：adminId={} 删除 {} 个文件（{} KB）", adminId, r.deleted, r.bytesFreed / 1024)
        }
        return mapOf(
            "dryRun" to dryRun,
            "graceDays" to g,
            "scanned" to r.scanned,
            "deleted" to r.deleted,
            "bytesFreed" to r.bytesFreed,
            "samples" to r.samples,
            "truncated" to r.truncated,
            // 前端据此区分"没有垃圾" / "读库失败" —— 只看 deleted=0 会把故障当成正常
            "dbReadFailed" to r.dbReadFailed
        )
    }
}
