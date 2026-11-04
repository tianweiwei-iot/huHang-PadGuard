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
import org.springframework.data.domain.PageRequest
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
    private val paymentOrderRepository: com.padguard.server.repository.PaymentOrderRepository,
    private val auditService: AuditService,
    private val systemSettingRepository: SystemSettingRepository,
    private val objectMapper: ObjectMapper,
    private val usageSessionService: UsageSessionService
) {
    private val log = LoggerFactory.getLogger(AdminParentService::class.java)

    /**
     * 家长列表（分页 + 关键字 + 状态 + 套餐）。
     *
     * 改造前：`findAll()` 全表 → 内存过滤 → 内存分页，且每行都单独查订阅与设备数。
     * 家长数上千后，翻一页 = 读全表 + 2N 次查询；运营每点一次「下一页」都在给数据库加压。
     * 现在过滤与分页交给数据库，只有当前页的 20 行需要补订阅与设备数，且都是批量查询。
     *
     * 排序：按注册时间倒序（与后台默认心智一致：最新注册的在最前）。
     * 改造前 `findAll()` 的顺序由数据库决定 —— 同一次筛选在不同时间点可能给出不同页面内容，
     * 翻页时还会出现「第 2 页重复第 1 页的某条」。稳定排序是分页正确性的前提，不是美化。
     */
    fun listParents(adminId: String, keyword: String?, status: String?, plan: String?, page: Int = 1, size: Int = 20): PageResult<ParentRow> {
        requireRead(adminPerm, adminId, "parents")
        val p = if (page < 1) 1 else page
        val s = when {
            size < 1 -> 20
            size > 200 -> 200
            else -> size
        }
        val k = keyword?.trim()?.lowercase() ?: ""
        // 套餐分类在 SQL 里按 plan 原值匹配（映射见 planCategory），未知值一律算 free，
        // 故 free 分支用「不在付费集合内」表达，才能覆盖历史脏数据（plan 为空/拼错）。
        val planMode = when (plan?.trim()?.lowercase()) {
            "free", "monthly", "yearly", "permanent" -> plan.trim().lowercase()
            else -> ""
        }
        val statusFilter = status?.trim()?.takeIf { it.isNotBlank() } ?: ""
        val result = userRepository.searchParents(
            status = statusFilter,
            keyword = k,
            kw = "%$k%",
            planMode = planMode,
            pageable = PageRequest.of(p - 1, s)
        )

        val users = result.content
        // 只为当前页补数据：订阅一次批量、设备数一次分组统计
        val userIds = users.map { it.id }
        val subs = if (userIds.isEmpty()) emptyMap()
        else subscriptionRepository.findByUserIdIn(userIds).associateBy { it.userId }
        val deviceCounts = if (userIds.isEmpty()) emptyMap()
        else deviceRepository.countGroupByUserIds(userIds)
            .associate { (it[0] as String) to (it[1] as Number).toInt() }

        val rows = users.map { toParentRow(it, subs[it.id], deviceCounts[it.id] ?: 0) }
        val total = result.totalElements.toInt()
        val totalPages = if (total == 0) 1 else ((total + s - 1) / s)
        return PageResult(rows, p, s, total, totalPages)
    }

    fun parentDetail(adminId: String, id: String): ParentDetail? {
        requireRead(adminPerm, adminId, "parents")
        val u = userRepository.findById(id).orElse(null) ?: return null
        val sub = subscriptionRepository.findByUserId(id)
        // 与设备列表页同源取今日用量；取不到为 null（今日尚未开始计时），前端显示"—"
        val owned = deviceRepository.findByUserId(id)
        val usedMinutes = usageSessionService.usedMinutesBatch(owned.map { it.id })
        val devices = owned.map {
            DeviceMini(
                name = it.name, deviceId = it.id, controlMode = it.controlMode,
                // 同 AdminDeviceService：null 才是"今日未开始计时"，不能填 0
                online = online(it), todayUsageMinutes = usedMinutes[it.id]
            )
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
        // 行锁读：`findById` 返回的是脱管快照，随后的 save 是整行 merge，
        // 会把并发提交的 token_version 写回旧值 —— 家长刚好点了一次"退出登录"，
        // 已被吊销的旧令牌就会重新生效。后台所有"只改一两个字段"的写操作都走行锁读。
        val u = userRepository.findByIdForUpdate(id) ?: throw BizException(ParentErr.PARAM_ERROR, "家长不存在", Audience.PARENT)
        u.status = "suspended"
        userRepository.save(u)
        auditServiceRecord(adminId, AdminAudit.PARENTS_SUSPEND, "parent", id, "冻结家长 ${u.phone}")
        return mapOf("ok" to true)
    }

    @Transactional
    fun activateParent(adminId: String, id: String): Map<String, Any> {
        // 解冻必须与冻结用**同一个**权限点（parents.suspend 的语义本来就是"冻结/启用"，
        // 见 AdminModels.AREA_DEFS）。此前这里只要 parents:write，
        // 结果是"只有编辑权的角色"能解冻别人冻结的账号，而它自己无权冻结 —— 权限倒挂，
        // 一道已经生效的处置可以被低权限角色单方面撤销。
        adminPerm.require(adminId, "parents", "suspend")
        val u = userRepository.findByIdForUpdate(id) ?: throw BizException(ParentErr.PARAM_ERROR, "家长不存在", Audience.PARENT)
        u.status = "active"
        userRepository.save(u)
        auditServiceRecord(adminId, AdminAudit.PARENTS_ACTIVATE, "parent", id, "激活家长 ${u.phone}")
        return mapOf("ok" to true)
    }

    @Transactional
    fun resetParentPassword(adminId: String, id: String): Map<String, Any> {
        adminPerm.require(adminId, "parents", "reset_pwd")
        // 行锁读：下面要同时改密码与令牌版本，避免与家长自己点登出并发时互相覆盖。
        val u = userRepository.findByIdForUpdate(id)
            ?: throw BizException(ParentErr.PARAM_ERROR, "家长不存在", Audience.PARENT)
        val newPwd = genPassword(systemSettingRepository, objectMapper)
        u.passwordHash = Passwords.encode(newPwd)
        // 重置密码的语义是「这个账号可能已经泄漏」：必须把既有会话全部踢下线，
        // 否则拿到旧令牌的人照样能用满 120 分钟、并靠 refresh 一直续下去 ——
        // 客服重置了密码，攻击者却毫发无伤，这是最容易在应急响应里被忽略的一环。
        u.tokenVersion += 1
        userRepository.save(u)
        auditServiceRecord(adminId, AdminAudit.PARENTS_RESET_PWD, "parent", id, "重置密码 ${u.phone}（已强制全部会话下线）")
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
        val u = userRepository.findByIdForUpdate(id) ?: throw BizException(ParentErr.PARAM_ERROR, "家长不存在", Audience.PARENT)
        // 部分更新语义：只改**请求里出现过**的字段。下面三行原来写的是
        // `u.email = req.email?.takeIf { ... }` —— `?.` 之后仍然会把 null 赋进去，
        // 于是"只改备注"的请求会把 email / region 一起清空。
        // email 被清空后，重置密码的邮件通知永远发不出去（MailService 直接返回 false），
        // 而这个损坏是静默的：页面提示"保存成功"。与上面 name 的写法对齐后才是真正的部分更新。
        req.name?.takeIf { it.isNotBlank() }?.let { u.nickname = it }
        req.email?.takeIf { it.isNotBlank() }?.let { u.email = it }
        req.region?.takeIf { it.isNotBlank() }?.let { u.region = it }
        req.remark?.takeIf { it.isNotBlank() }?.let { u.remark = it }
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
        // 资金护栏：存在已支付未退款的订单时禁止删除——否则"钱收了、权益没了、
        // 记录对不上人"，对账时只剩一串幽灵 userId。订单行本身要留作财务流水，
        // 正确路径是先退款（AdminBillingService.refund）再删号。
        val paidOrders = paymentOrderRepository.findByUserIdAndStatus(id, OrderStatus.PAID.name)
        if (paidOrders.isNotEmpty()) {
            throw BizException(
                ParentErr.PARAM_ERROR,
                "该账号存在 ${paidOrders.size} 笔已支付未退款的订单，请先处理退款后再删除",
                Audience.PARENT
            )
        }
        // 关联订阅一并清理
        subscriptionRepository.findByUserId(id)?.let { subscriptionRepository.delete(it) }
        userRepository.delete(u)
        auditServiceRecord(adminId, AdminAudit.PARENTS_DELETE, "parent", id, "删除家长 ${u.phone}")
        return mapOf("ok" to true)
    }

    /**
     * 单行 DTO 组装。
     *
     * [sub] / [deviceCount] 允许由调用方批量预取后传入：列表页一页 20 行，
     * 逐行去查就是 40 次往返（N+1）。默认参数保留单行调用形态（详情页只有一行，无需批量）。
     */
    private fun toParentRow(
        u: User,
        sub: Subscription? = subscriptionRepository.findByUserId(u.id),
        deviceCount: Int = deviceRepository.countByUserId(u.id).toInt()
    ): ParentRow {
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
