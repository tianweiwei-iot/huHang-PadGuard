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
import org.springframework.data.domain.PageRequest
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

        /**
         * 单次导出的硬上限。
         *
         * 导出必须真的能拿到"比页面更多"的记录，否则前端那句"更早记录需通过导出查看"
         * 就是空话；但也不能无上限：`auditPage` 走 `findAll` + 内存映射，
         * 一次拉十万条会把堆与响应体同时撑爆，而这类请求点一下就会发生。
         */
        const val AUDIT_EXPORT_MAX_ROWS = 5_000
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
     *
     * @param limit 本次最多返回多少条。默认 [AUDIT_MAX_ROWS]（页面展示用）；
     *              导出场景可传更大值，但硬上限 [AUDIT_EXPORT_MAX_ROWS] ——
     *              一次导出十万条会让 `findAll` + 序列化把堆和响应体同时撑爆，
     *              而这类请求只要有一个人点就会发生。
     */
    fun auditPage(adminId: String, area: String?, limit: Int? = null): AuditPageDto {
        requireRead(adminPerm, adminId, "audit")
        val effectiveLimit = (limit ?: AUDIT_MAX_ROWS).coerceIn(1, AUDIT_EXPORT_MAX_ROWS)

        /*
         * 领域过滤、排序、截断全部下推数据库。
         *
         * 改造前是 `findAllByOrderByCreatedAtDesc()` 整表 + 内存过滤 + 内存 take，
         * 外加 `userRepository.findAll()` 把**全部家长**读进内存，只为给这一页里的
         * 最多 200 行补一个显示名。审计表是全项目增长最快的表之一：
         * 这个页面的内存占用会随运营时间线性上升，而它看上去只是"一个列表页"。
         *
         * 领域在库里没有独立列（由 action 派生），所以先把领域反查成 action 集合
         * 再下推 `action in (...)`。这样"先过滤再截断"的语义仍成立：
         * 拿到的是该领域最近的 N 条，而不是"最近 N 条里恰好属于该领域的几条"。
         */
        val areaKey = area?.trim()?.takeIf { it.isNotBlank() }
        val actions = areaKey?.let { auditActionsOf(it) }
        if (actions != null && actions.isEmpty()) {
            // 未知领域：如实返回空，不能退化成"不过滤"
            log.warn("auditPage: unknown area '{}', returning empty page", areaKey)
            return AuditPageDto(emptyList(), truncated = false, maxRows = effectiveLimit, total = 0)
        }

        val pageable = PageRequest.of(0, effectiveLimit)
        val page = if (actions == null) {
            auditLogRepository.findAllByOrderByCreatedAtDesc(pageable)
        } else {
            auditLogRepository.findByActionInOrderByCreatedAtDesc(actions, pageable)
        }
        val total = page.totalElements.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        // 只为这一页涉及的 actor 补显示名：manager 表很小，家长表可能很大，都不该整表读。
        val actorIds = page.content.mapNotNull { it.userId }.distinct()
        val admins = if (actorIds.isEmpty()) emptyMap() else
            adminRepository.findAllById(actorIds).associateBy { it.id }
        val parentIds = actorIds.filter { !admins.containsKey(it) }
        val users = if (parentIds.isEmpty()) emptyMap() else
            userRepository.findAllById(parentIds).associateBy { it.id }

        val items = page.content.map { logRow ->
            val meta = resolveAuditAction(logRow.action)
            val actorName = when {
                logRow.userId != null && admins.containsKey(logRow.userId) -> admins[logRow.userId]!!.username
                logRow.userId != null && users.containsKey(logRow.userId) -> users[logRow.userId]!!.phone
                else -> logRow.userId ?: "system"
            }
            AuditItem(
                id = logRow.id, time = fmt(logRow.createdAt) ?: "", admin = actorName,
                action = logRow.action, actionName = meta.second, area = meta.first,
                target = logRow.targetId ?: logRow.detail?.take(40) ?: "",
                ip = logRow.ip, result = logRow.result.lowercase()
            )
        }
        return AuditPageDto(
            items = items,
            truncated = total > effectiveLimit,
            maxRows = effectiveLimit,
            total = total
        )
    }

    fun alerts(adminId: String): List<AlertItem> {
        requireRead(adminPerm, adminId, "audit")
        // 先取最近的告警，再只为这批告警涉及的家长补手机号。
        // 原来是 findAll() 全表 + findAll() 全量家长：两个全表扫描换一个列表页。
        val recent = alertRepository.findTop500ByOrderByTriggeredAtDesc()
        val ownerIds = recent.mapNotNull { it.userId }.distinct()
        val users = if (ownerIds.isEmpty()) emptyMap() else userRepository.findAllById(ownerIds).associateBy { it.id }
        return recent.map { a ->
            val level = when (a.level.uppercase()) {
                "ERROR" -> "high"; "WARN" -> "mid"; else -> "low"
            }
            // 状态映射须覆盖告警实体的全部终结/中间态（见 AlertService）：
            // CLOSED = 家长端关闭 / 孩子端自愈批量关闭；RESOLVED = 后台处理；这两类都算 closed。
            // ACKNOWLEDGED = 家长已确认（未关闭），单独标 acknowledged，不能再渲染成"待处理"。
            val status = when (a.status.uppercase()) {
                "RESOLVED", "CLOSED" -> "closed"
                "ACKNOWLEDGED" -> "acknowledged"
                else -> "open"
            }
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
