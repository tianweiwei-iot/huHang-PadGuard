package com.padguard.server.service

import com.padguard.server.domain.AuditLog
import com.padguard.server.repository.AuditLogRepository
import com.padguard.server.security.DeviceOwnershipGuard
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 审计日志服务。
 *
 * ## 设计要点
 * 1. **独立事务（REQUIRES_NEW）**：即使主业务事务回滚，审计记录也照常落库，真实反映"有人尝试过某操作"，
 *    这是合规追溯与责任界定的底线要求。
 * 2. **永不外抛**：任何异常只在内部记日志，绝不向上传播——审计写入失败绝不可导致绑定 / 解锁等主流程失败。
 * 3. **操作语义化**：[action] 采用动词 + 宾语的常量（见 [AuditAction]），便于按动作统计与过滤。
 */
@Service
class AuditService(
    private val auditLogRepository: AuditLogRepository,
    private val ownershipGuard: DeviceOwnershipGuard
) {

    private val log = LoggerFactory.getLogger(AuditService::class.java)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(
        userId: String?,
        deviceId: String?,
        action: String,
        targetType: String? = null,
        targetId: String? = null,
        detail: String? = null,
        result: String = "SUCCESS",
        ip: String? = null
    ) {
        runCatching {
            auditLogRepository.save(
                AuditLog(
                    id = "al_" + UUID.randomUUID().toString().replace("-", ""),
                    userId = userId,
                    deviceId = deviceId,
                    action = action,
                    targetType = targetType,
                    targetId = targetId,
                    detail = detail?.take(4000),
                    result = result,
                    ip = ip,
                    createdAt = System.currentTimeMillis()
                )
            )
        }.onFailure { log.warn("audit log write failed (action={}): {}", action, it.message) }
    }

    /**
     * 分页查询审计日志（家长端 / 校园端）。deviceId 与 action 为可选过滤条件。
     *
     * 排序与分页全部交给数据库（ORDER BY + LIMIT/OFFSET）。
     * 早期是把该设备/用户的**全部**审计日志捞进内存，再 sortedByDescending + drop + take：
     * 审计日志只增不删，这个"分页"的耗时会随数据增长线性上升、内存占用不可控，
     * 是家长端偶发"一点审计就卡住"的成因。
     */
    fun query(userId: String?, deviceId: String?, action: String?, page: Int, size: Int): List<Map<String, Any?>> {
        // 按 deviceId 过滤前必须先校验设备归属，否则任意登录家长可凭他人设备号
        // 越权读取其审计流水（IDOR）。userId 为空只会出现在后台等可信调用方，那里本就不过滤设备。
        if (deviceId != null && userId != null) {
            ownershipGuard.requireOwned(userId, deviceId)
        }
        val pageable = PageRequest.of(
            page.coerceAtLeast(0),
            if (size > 0) size.coerceAtMost(100) else 20
        )
        val rows = when {
            deviceId != null -> if (action.isNullOrBlank())
                auditLogRepository.findByDeviceIdOrderByCreatedAtDesc(deviceId, pageable)
            else auditLogRepository.findByDeviceIdAndActionOrderByCreatedAtDesc(deviceId, action, pageable)

            userId != null -> if (action.isNullOrBlank())
                auditLogRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable)
            else auditLogRepository.findByUserIdAndActionOrderByCreatedAtDesc(userId, action, pageable)

            else -> emptyList()
        }
        return rows.map {
                mapOf(
                    "id" to it.id,
                    "userId" to it.userId,
                    "deviceId" to it.deviceId,
                    "action" to it.action,
                    "targetType" to it.targetType,
                    "targetId" to it.targetId,
                    "detail" to it.detail,
                    "result" to it.result,
                    "ip" to it.ip,
                    "createdAt" to it.createdAt
                )
            }
    }
}

/** 审计动作常量（语义化，便于统计与过滤） */
object AuditAction {
    const val DEVICE_BIND = "DEVICE_BIND"
    const val DEVICE_UNBIND = "DEVICE_UNBIND"
    const val POLICY_WEB_UPDATE = "POLICY_WEB_UPDATE"
    const val POLICY_APP_UPDATE = "POLICY_APP_UPDATE"
    const val POLICY_TABLET_USAGE_UPDATE = "POLICY_TABLET_USAGE_UPDATE"
    const val POLICY_SYSTEM_LOCK_UPDATE = "POLICY_SYSTEM_LOCK_UPDATE"
    const val POLICY_TEMPLATE_APPLY = "POLICY_TEMPLATE_APPLY"
    const val UNLOCK_APPROVE = "UNLOCK_APPROVE"
    const val UNLOCK_REJECT = "UNLOCK_REJECT"
    const val UNLOCK_DISMISS = "UNLOCK_DISMISS"
    /** 延时申请提交（孩子端发起，待家长审批） */
    const val USAGE_DELAY_REQUEST = "USAGE_DELAY_REQUEST"
    /** 延时申请通过（家长批准，额度真正延长） */
    const val USAGE_DELAY_APPROVE = "USAGE_DELAY_APPROVE"
    /** 延时申请拒绝（家长驳回，下发拒因给孩子） */
    const val USAGE_DELAY_REJECT = "USAGE_DELAY_REJECT"
    /** 延时申请忽略（家长归档，非终态、仍可被批准） */
    const val USAGE_DELAY_DISMISS = "USAGE_DELAY_DISMISS"
    const val COMMAND_ISSUE = "COMMAND_ISSUE"
    const val CONTENT_SAFE_SEARCH_UPDATE = "CONTENT_SAFE_SEARCH_UPDATE"
    const val CONTENT_CATEGORY_BLOCK_UPDATE = "CONTENT_CATEGORY_BLOCK_UPDATE"
    /** 护眼模式变更：涉及孩子用眼健康，家长有权事后核对是谁在什么时候改的 */
    const val POLICY_EYE_PROTECTION_UPDATE = "POLICY_EYE_PROTECTION_UPDATE"
}
