package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.Alert
import com.padguard.server.domain.AuditLog
import com.padguard.server.dto.AlertItem
import com.padguard.server.dto.AuditItem
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

    fun audit(adminId: String, area: String?): List<AuditItem> {
        requireRead(adminPerm, adminId, "audit")
        val admins = adminRepository.findAll().associateBy { it.id }
        val users = userRepository.findAll().associateBy { it.id }
        val rows = auditLogRepository.findAllByOrderByCreatedAtDesc().take(200)
        return rows.mapNotNull { log ->
            val actorName = when {
                log.userId != null && admins.containsKey(log.userId) -> admins[log.userId]!!.username
                log.userId != null && users.containsKey(log.userId) -> users[log.userId]!!.phone
                else -> log.userId ?: "system"
            }
            val meta = resolveAuditAction(log.action)
            if (!area.isNullOrBlank() && meta.first != area) return@mapNotNull null
            AuditItem(
                id = log.id, time = fmt(log.createdAt) ?: "", admin = actorName,
                action = log.action, actionName = meta.second, area = meta.first,
                target = log.targetId ?: log.detail?.take(40) ?: "",
                ip = log.ip, result = log.result.lowercase()
            )
        }
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
