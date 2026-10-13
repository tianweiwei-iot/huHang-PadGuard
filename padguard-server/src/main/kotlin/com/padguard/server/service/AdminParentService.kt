package com.padguard.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.*
import com.padguard.server.dto.*
import com.padguard.server.repository.*
import com.padguard.server.security.AdminPermissionService
import com.padguard.server.security.Passwords
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 家长账号管理（列表 / 详情 / 冻结 / 激活 / 重置密码 / 新建 / 修改 / 删除）。
 * 
 */
@Service
class AdminParentService(
    private val userRepository: UserRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val deviceRepository: DeviceRepository,
    private val adminPerm: AdminPermissionService,
    private val billingService: BillingService,
    private val mailService: MailService,
    private val adminBillingService: AdminBillingService,
    private val auditService: AuditService,
    private val systemSettingRepository: SystemSettingRepository,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(AdminParentService::class.java)

    fun listParents(adminId: String, keyword: String?, status: String?, plan: String?): List<ParentRow> {
        requireRead(adminPerm, adminId, "parents")
        var users = userRepository.findAll()
        if (!status.isNullOrBlank()) users = users.filter { (it.status ?: "active") == status }
        if (!plan.isNullOrBlank()) {
            val want = plan.lowercase()
            users = users.filter { u -> planCategory(subscriptionRepository.findByUserId(u.id)?.plan) == want }
        }
        if (!keyword.isNullOrBlank()) {
            val k = keyword.lowercase()
            users = users.filter {
                it.phone.contains(k) ||
                (it.nickname?.lowercase()?.contains(k) == true) ||
                it.id.lowercase().contains(k)
            }
        }
        return users.map { toParentRow(it) }
    }

    fun parentDetail(adminId: String, id: String): ParentDetail? {
        requireRead(adminPerm, adminId, "parents")
        val u = userRepository.findById(id).orElse(null) ?: return null
        val sub = subscriptionRepository.findByUserId(id)
        val devices = deviceRepository.findByUserId(id).map {
            DeviceMini(name = it.name, deviceId = it.id, controlMode = it.controlMode, online = online(it), todayUsageMinutes = 0)
        }
        val subscription = sub?.let {
            SubMini(planName = planLabel(it.plan), status = subStatusLower(it.status), autoRenew = it.autoRenew)
        }
        val base = toParentRow(u)
        return ParentDetail(
            id = base.id, phone = base.phone, email = base.email, name = base.name, status = base.status,
            plan = base.plan, planName = base.planName, expiredAt = base.expiredAt, region = base.region,
            registeredAt = base.registeredAt, lastLoginAt = base.lastLoginAt, deviceCount = base.deviceCount,
            devices = devices, subscription = subscription
        )
    }

    @Transactional
    fun suspendParent(adminId: String, id: String): Map<String, Any> {
        adminPerm.require(adminId, "parents", "suspend")
        val u = userRepository.findById(id).orElse(null) ?: throw BizException(ParentErr.PARAM_ERROR, "家长不存在", Audience.PARENT)
        u.status = "suspended"
        userRepository.save(u)
        auditServiceRecord(adminId, AdminAudit.PARENTS_SUSPEND, "parent", id, "冻结家长 ${u.phone}")
        return mapOf("ok" to true)
    }

    @Transactional
    fun activateParent(adminId: String, id: String): Map<String, Any> {
        adminPerm.require(adminId, "parents", "write")
        val u = userRepository.findById(id).orElse(null) ?: throw BizException(ParentErr.PARAM_ERROR, "家长不存在", Audience.PARENT)
        u.status = "active"
        userRepository.save(u)
        auditServiceRecord(adminId, AdminAudit.PARENTS_ACTIVATE, "parent", id, "激活家长 ${u.phone}")
        return mapOf("ok" to true)
    }

    @Transactional
    fun resetParentPassword(adminId: String, id: String): Map<String, Any> {
        adminPerm.require(adminId, "parents", "reset_pwd")
        val u = userRepository.findById(id).orElse(null) ?: throw BizException(ParentErr.PARAM_ERROR, "家长不存在", Audience.PARENT)
        val newPwd = genPassword(systemSettingRepository, objectMapper)
        u.passwordHash = Passwords.encode(newPwd)
        userRepository.save(u)
        auditServiceRecord(adminId, AdminAudit.PARENTS_RESET_PWD, "parent", id, "重置密码 ${u.phone}")
        // 重置生效后立即邮件通知对方（若已登记邮箱且已配置 SMTP）；否则退回后台弹窗明文转告
        val emailed = mailService.sendPasswordReset(u.email, u.phone, "家长账号", newPwd)
        return mapOf("ok" to true, "newPwd" to newPwd, "emailed" to emailed)
    }

    @Transactional
    fun createParent(adminId: String, req: AdminCreateParentRequest): Map<String, Any> {
        adminPerm.require(adminId, "parents", "create")
        if (userRepository.existsByPhone(req.phone)) {
            throw BizException(ParentErr.PARAM_ERROR, "手机号已注册", Audience.PARENT)
        }
        val pwd = genPassword(systemSettingRepository, objectMapper)
        val now = System.currentTimeMillis()
        val u = User(
            id = "U" + UUID.randomUUID().toString().replace("-", "").take(20),
            phone = req.phone, passwordHash = Passwords.encode(pwd),
            nickname = req.name?.takeIf { it.isNotBlank() } ?: ("家长" + req.phone.takeLast(4)),
            email = req.email?.takeIf { it.isNotBlank() },
            region = req.region?.takeIf { it.isNotBlank() },
            remark = req.remark?.takeIf { it.isNotBlank() },
            role = "", sceneType = "", status = "active", createdAt = now
        )
        userRepository.save(u)
        auditServiceRecord(adminId, AdminAudit.PARENTS_CREATE, "parent", u.id, "新建家长 ${u.phone}")

        // 「创建并开通」必须真的开通：早期前端传的 plan 被服务端完全忽略。
        // 现在按所选套餐直接落一条 ACTIVE 订阅；永久会员按 100 年计（不做过期处理）。
        val planKey = req.plan?.takeIf { it.isNotBlank() }
        val activated = if (planKey != null && planKey.uppercase() !in setOf("FREE", "TRIAL")) {
            adminPerm.require(adminId, "billing", "manual")
            val planEnum = Plan.of(planKey)
                ?: throw BizException(ParentErr.PARAM_ERROR, "套餐不存在：$planKey", Audience.PARENT)
            val days = if (planEnum == Plan.PERMANENT) 36_500 else planEnum.months * 30
            adminBillingService.manualActivate(adminId, AdminManualSubscriptionRequest(parentPhone = u.phone, plan = planEnum.key, days = days))
            planEnum.label
        } else null

        return if (activated != null) {
            mapOf("ok" to true, "id" to u.id, "password" to pwd, "plan" to activated)
        } else {
            mapOf("ok" to true, "id" to u.id, "password" to pwd)
        }
    }

    @Transactional
    fun updateParent(adminId: String, id: String, req: AdminUpdateParentRequest): Map<String, Any> {
        adminPerm.require(adminId, "parents", "write")
        val u = userRepository.findById(id).orElse(null) ?: throw BizException(ParentErr.PARAM_ERROR, "家长不存在", Audience.PARENT)
        req.name?.takeIf { it.isNotBlank() }?.let { u.nickname = it }
        u.email = req.email?.takeIf { it.isNotBlank() }
        u.region = req.region?.takeIf { it.isNotBlank() }
        u.remark = req.remark?.takeIf { it.isNotBlank() }
        userRepository.save(u)
        auditServiceRecord(adminId, AdminAudit.PARENTS_UPDATE, "parent", id, "修改家长资料 ${u.phone}")
        return mapOf("ok" to true)
    }

    @Transactional
    fun deleteParent(adminId: String, id: String): Map<String, Any> {
        adminPerm.require(adminId, "parents", "delete")
        val u = userRepository.findById(id).orElse(null) ?: throw BizException(ParentErr.PARAM_ERROR, "家长不存在", Audience.PARENT)
        // 安全护栏：账号下仍有绑定设备时禁止删除，避免产生孤儿设备。
        val deviceCount = deviceRepository.countByUserId(id)
        if (deviceCount > 0) {
            throw BizException(ParentErr.PARAM_ERROR, "该账号下仍有 ${deviceCount} 台设备，请先解绑或转交设备后再删除", Audience.PARENT)
        }
        // 关联订阅一并清理
        subscriptionRepository.findByUserId(id)?.let { subscriptionRepository.delete(it) }
        userRepository.delete(u)
        auditServiceRecord(adminId, AdminAudit.PARENTS_DELETE, "parent", id, "删除家长 ${u.phone}")
        return mapOf("ok" to true)
    }

    private fun toParentRow(u: User): ParentRow {
        val sub = subscriptionRepository.findByUserId(u.id)
        val deviceCount = deviceRepository.countByUserId(u.id).toInt()
        val name = u.nickname ?: ("家长" + u.phone.takeLast(4))
        return ParentRow(
            id = u.id, phone = u.phone, name = name, email = u.email,
            status = (u.status ?: "active"),
            registeredAt = fmt(u.createdAt), lastLoginAt = fmt(u.lastLoginAt),
            plan = planCategory(sub?.plan), planName = planLabel(sub?.plan),
            expiredAt = fmt(sub?.expiredAt),
            deviceCount = deviceCount, childrenCount = deviceCount,
            region = u.region ?: "", channel = "", remark = u.remark ?: "", tags = emptyList()
        )
    }

    /** 审计落库（独立事务，失败不影响主流程）。各写操作共用。 */
    private fun auditServiceRecord(adminId: String, action: String, targetType: String, targetId: String, detail: String) {
        auditService.record(adminId, null, action, targetType = targetType, targetId = targetId, detail = detail)
    }
}
