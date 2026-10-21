package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.AdminRoles
import com.padguard.server.domain.Alert
import com.padguard.server.domain.AuditLog
import com.padguard.server.dto.AlertItem
import com.padguard.server.dto.AuditItem
import com.padguard.server.dto.AuditPageDto
import com.padguard.server.repository.AdminRepository
import com.padguard.server.repository.AlertRepository
import com.padguard.server.repository.AuditLogRepository
import com.padguard.server.repository.UserRepository
import com.padguard.server.security.AdminPermissionService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 审计日志 / 告警查看与关单。
 */
@Service
class AdminAuditService(
    private val adminPerm: AdminPermissionService,
    private val adminRepository: AdminRepository,
    private val userRepository: UserRepository,
    private val auditLogRepository: AuditLogRepository,
    private val alertRepository: AlertRepository,
    private val auditService: AuditService
) {
    private val log = LoggerFactory.getLogger(AdminAuditService::class.java)

    companion object {
        /**
         * 单次返回的审计条数上限。
         *
         * 这是一个**如实声明的截断**，不是"分页"：审计日志可能积累到几十万条，
         * 一次性灌给浏览器既不现实也无必要（运营真正要看的永远是最近的若干条）。
         * 超出上限时前端须提示"仅展示最近 N 条"，避免让人误以为这就是全部。
         * 真正的深挖走导出/检索，不在本接口职责内。
         */
        const val AUDIT_MAX_ROWS = 200
    }

    /**
     * 审计领域下拉的元数据（key + 中文名）。
     *
     * 由 [AdminRoles.AREA_DEFS] **单向派生**，前端不再自带副本：
     * 早期前端硬编码了 6 项、漏掉 `audit`，运营想筛"审计告警"时根本选不到那一项，
     * 而后端日志里这类记录一直存在 —— 典型的"数据有、入口没有"。
     *
     * 闸门沿用 `audit:read`（与 [audit] 一致），而不是 `rbac:read`：
     * 只被授予审计查看权限的角色同样需要打开这个下拉，
     * 借道 `/rbac/permissions` 会让他们在这一页直接吃到 403。
     */
    fun auditAreas(adminId: String): List<Map<String, String>> {
        requireRead(adminPerm, adminId, "audit")
        return AdminRoles.AREA_DEFS.map { mapOf("key" to it.key, "name" to it.name) }
    }

    /** 列表视图：只取条目，供仪表盘等处二次裁剪（截断信息在此处被有意丢弃）。 */
    fun audit(adminId: String, area: String?): List<AuditItem> = auditPage(adminId, area).items

    /**
     * 审计日志列表（带截断元信息）。
     *
     * **截断顺序（易错点）**：先按领域过滤、再截断，而不是反过来。
     * 旧实现是 `findAll().take(200).filter(area)` —— 取最近 200 条**全领域**记录再筛领域，
     * 于是"只看审计告警"时看到的其实是"最近 200 条里恰好属于告警的那几条"。
     * 运营据此判断"这个领域最近没动静"，实际上只是被其他领域的洪流挤出去了。
     * 现在先过滤再截断，得到的是"该领域最近的 N 条"。
     */
    fun auditPage(adminId: String, area: String?): AuditPageDto {
        requireRead(adminPerm, adminId, "audit")
        val admins = adminRepository.findAll().associateBy { it.id }
        val users = userRepository.findAll().associateBy { it.id }
        val rows = auditLogRepository.findAllByOrderByCreatedAtDesc()
        // 领域派生依赖 action 解析，无法下推到 SQL，只能在内存里判定；
        // 由于 findAll 已按时间倒序，先过滤再 take 得到的仍是"该领域最近的记录"。
        val matched = rows.mapNotNull { log ->
            val meta = resolveAuditAction(log.action)
            if (!area.isNullOrBlank() && meta.first != area) return@mapNotNull null
            val actorName = when {
                log.userId != null && admins.containsKey(log.userId) -> admins[log.userId]!!.username
                log.userId != null && users.containsKey(log.userId) -> users[log.userId]!!.phone
                else -> log.userId ?: "system"
            }
            AuditItem(
                id = log.id, time = fmt(log.createdAt) ?: "", admin = actorName,
                action = log.action, actionName = meta.second, area = meta.first,
                target = log.targetId ?: log.detail?.take(40) ?: "",
                ip = log.ip, result = log.result.lowercase()
            )
        }
        return AuditPageDto(
            items = matched.take(AUDIT_MAX_ROWS),
            truncated = matched.size > AUDIT_MAX_ROWS,
            maxRows = AUDIT_MAX_ROWS,
            total = matched.size
        )
    }

    fun alerts(adminId: String): List<AlertItem> {
        requireRead(adminPerm, adminId, "audit")
        val users = userRepository.findAll().associateBy { it.id }
        return alertRepository.findAll().map { a ->
            val level = when (a.level.uppercase()) {
                "ERROR" -> "high"; "WARN" -> "mid"; else -> "low"
            }
            val status = if (a.status == "RESOLVED") "closed" else "open"
            AlertItem(
                id = a.id, level = level, type = a.title, message = a.message,
                relatedParent = a.userId?.let { users[it]?.phone } ?: "",
                status = status, time = fmt(a.triggeredAt) ?: ""
            )
        }
    }

    @Transactional
    fun closeAlert(adminId: String, id: String): Map<String, Any> {
        // 处理告警是写操作，必须经 RBAC 闸门：仅 audit:write 可调，杜绝运营 / 客服 / 财务越权关单。
        adminPerm.require(adminId, "audit", "write")
        val alert = alertRepository.findById(id).orElse(null)
            ?: throw BizException(ParentErr.PARAM_ERROR, "告警不存在", Audience.PARENT)
        alert.status = "RESOLVED"
        alert.resolvedAt = System.currentTimeMillis()
        alertRepository.save(alert)
        auditService.record(adminId, null, "ALERT_CLOSE", targetType = "alert", targetId = id, detail = "处理告警 ${alert.title}")
        return mapOf("ok" to true)
    }
}
