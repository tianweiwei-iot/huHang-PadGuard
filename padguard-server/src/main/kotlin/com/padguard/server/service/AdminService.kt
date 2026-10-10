package com.padguard.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.*
import com.padguard.server.dto.*
import com.padguard.server.repository.*
import com.padguard.server.security.AdminPermissionService
import com.padguard.server.security.JwtTokenProvider
import com.padguard.server.security.Passwords
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.lang.management.ManagementFactory
import java.security.SecureRandom
import java.sql.Connection
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import javax.sql.DataSource

/**
 * 管控后台（admin）业务聚合层。
 *
 * 职责：把分散的仓储 / [BillingService] / [CommandService] / [AuditService] 组装成对前端友好的真实管理接口，
 * 覆盖家长账号、设备、订阅计费、RBAC、服务端监控、审计与系统设置。
 *
 * 约定：
 * 1. 所有**写操作**在方法内首行调用 [AdminPermissionService.require] 做服务端闸门（前端只做 UI 隐藏，越权必拦）；
 * 2. 所有写操作落审计（[AuditService.record]，独立事务、失败不影响主流程）；
 * 3. 输出 DTO 字段严格对齐前端 admin 视图（小写状态串、金额用分、parentPhone/planName 等），保证 Live 模式可直接渲染。
 */
@Service
class AdminService(
    private val userRepository: UserRepository,
    private val deviceRepository: DeviceRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val paymentOrderRepository: PaymentOrderRepository,
    private val adminRepository: AdminRepository,
    private val adminRoleOverrideRepository: AdminRoleOverrideRepository,
    private val alertRepository: AlertRepository,
    private val auditLogRepository: AuditLogRepository,
    private val systemSettingRepository: SystemSettingRepository,
    private val billingService: BillingService,
    private val commandService: CommandService,
    private val jwt: JwtTokenProvider,
    private val adminPerm: AdminPermissionService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val dataSource: DataSource
) {
    private val log = LoggerFactory.getLogger(AdminService::class.java)

    /** 监控指标用：进程启动时刻（近似应用启动） */
    private val startedAt = System.currentTimeMillis()

    companion object {
        /** 在线态惰性超时阈值（与 DeviceService 一致，单位毫秒） */
        private const val ONLINE_STALE_MS = 120_000L

        private val FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        /** 套餐权益描述（运营展示用，不进数据库） */
        private val PLAN_FEATURES: Map<String, List<String>> = mapOf(
            "MONTHLY" to listOf("3 台设备", "远程锁屏/解锁", "分龄合规", "风险预警", "历史报表"),
            "QUARTERLY" to listOf("3 台设备", "全部月度权益", "季度更省"),
            "YEARLY" to listOf("5 台设备", "全部功能", "实时监控看屏", "电子围栏", "优先客服")
        )

        /** 后台管理操作审计动作常量（与 audit 视图 area 分类对应） */
        object AdminAudit {
            const val PARENTS_SUSPEND = "PARENTS_SUSPEND"
            const val PARENTS_ACTIVATE = "PARENTS_ACTIVATE"
            const val PARENTS_RESET_PWD = "PARENTS_RESET_PWD"
            const val PARENTS_CREATE = "PARENTS_CREATE"
            const val PARENTS_UPDATE = "PARENTS_UPDATE"
            const val PARENTS_DELETE = "PARENTS_DELETE"
            const val DEVICES_LOCK = "DEVICES_LOCK"
            const val DEVICES_UNLOCK = "DEVICES_UNLOCK"
            const val BILLING_MANUAL = "BILLING_MANUAL"
            const val BILLING_REFUND = "BILLING_REFUND"
            const val RBAC_ASSIGN = "RBAC_ASSIGN"
            const val RBAC_CREATE = "RBAC_CREATE"
            const val RBAC_UPDATE = "RBAC_UPDATE"
            const val RBAC_DELETE = "RBAC_DELETE"
            const val RBAC_RESET_PWD = "RBAC_RESET_PWD"
            const val SETTINGS_WRITE = "SETTINGS_WRITE"
        }
    }

    // ============================ 工具 ============================

    private fun fmt(ms: Long?): String? {
        if (ms == null || ms == 0L) return null
        return FMT.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
    }

    private fun online(dev: Device): Boolean {
        if (dev.onlineStatus != "ONLINE") return false
        val last = dev.lastOnlineAt ?: return false
        return System.currentTimeMillis() - last <= ONLINE_STALE_MS
    }

    /** 把套餐键归一到前端下拉值（free/monthly/yearly） */
    private fun planCategory(planKey: String?): String = when ((planKey ?: "").uppercase()) {
        "TRIAL" -> "free"
        "MONTHLY", "QUARTERLY" -> "monthly"
        "YEARLY" -> "yearly"
        else -> "free"
    }

    private fun subStatusLower(status: String): String = status.lowercase()

    private fun orderStatusLower(status: String): String = when (status.uppercase()) {
        "PAID" -> "paid"
        "REFUNDED" -> "refunded"
        "PENDING" -> "unpaid"
        "CLOSED" -> "closed"
        "FAILED" -> "failed"
        else -> status.lowercase()
    }

    private fun planLabel(planKey: String?): String =
        if (planKey.isNullOrBlank()) "免费版" else (Plan.of(planKey)?.label ?: "免费版")

    private fun toAdminDto(a: Admin): AdminDto = AdminDto(
        id = a.id, username = a.username, name = a.displayName, roleId = a.role,
        roleName = AdminRoles.roleName(a.role), email = a.email, status = a.status,
        mfa = a.mfaEnabled, createdAt = a.createdAt, lastLoginAt = a.lastLoginAt
    )

    private fun genPassword(len: Int = 12): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
        val rnd = SecureRandom()
        return (1..len).joinToString("") { chars[rnd.nextInt(chars.length)].toString() } + "!"
    }

    /**
     * 防止越权提升为超级管理员：超级管理员是系统最后的破窗账号，
     * 只有 super 角色本身可以授予或变更 super 角色。即使某运营角色经 override 拿到了 rbac:write，
     * 也无法借此把自己或他人提升为 super（否则后台权限模型会被轻易击穿）。
     */
    private fun ensureNotEscalatingToSuper(actorId: String, roleId: String) {
        if (roleId == "super" && adminRepository.findById(actorId).orElse(null)?.role != "super") {
            throw BizException(ParentErr.FORBIDDEN, "只有超级管理员可授予或变更超级管理员角色", Audience.PARENT)
        }
    }

    // ============================ 登录 ============================

    fun login(username: String, password: String): AdminLoginResponse {
        val admin = adminRepository.findByUsername(username)
            ?: throw BizException(ParentErr.UNAUTHORIZED, "管理员不存在", Audience.PARENT)
        if (admin.status != "active") {
            throw BizException(ParentErr.UNAUTHORIZED, "管理员账号已停用", Audience.PARENT)
        }
        if (!Passwords.matches(password, admin.passwordHash)) {
            throw BizException(ParentErr.UNAUTHORIZED, "密码错误", Audience.PARENT)
        }
        admin.lastLoginAt = System.currentTimeMillis()
        adminRepository.save(admin)
        val token = jwt.createAdminToken(admin.id)
        return AdminLoginResponse(token, toAdminDto(admin))
    }

    // ============================ 仪表盘 ============================

    fun dashboard(adminId: String): DashboardResponse {
        // S13：仪表盘是跨领域聚合（含服务器状态），统一挂 server:read。
        // 默认五个内置角色的权限矩阵均含 server:read，不受影响；自定义角色需在 RBAC 矩阵中显式勾选。
        adminPerm.require(adminId, "server", "read")
        val users = userRepository.findAll()
        val parentsTotal = users.size.toLong()
        val parentsActive = users.count { (it.status ?: "active") == "active" }.toLong()
        val parentsSuspended = users.count { (it.status ?: "active") == "suspended" }.toLong()

        val devicesTotal = deviceRepository.count()
        val devicesOnline = deviceRepository.findAll().count { online(it) }.toLong()

        val subs = subscriptionRepository.findAll()
        val subsActive = subs.count { it.status == SubStatus.ACTIVE.name }.toLong()
        val subsTrial = subs.count { it.status == SubStatus.TRIAL.name }.toLong()

        val monthRevenue = monthPaidSum()
        val serverStatus = if (dbUp()) "UP" else "DOWN"

        val revenueSeries = lastMonthsRevenue(6).map { it.amount }
        val planDist = subs.groupBy { planCategory(it.plan) }
            .map { (cat, list) -> NameCount(planLabel(if (cat == "free") "TRIAL" else cat.uppercase()), list.size) }
        val regionDist = emptyList<NameCount>() // 家长无 region 字段，预留

        val alertsOpen = alertRepository.countByStatus("ACTIVE")

        val recent = auditInternal(null).take(6)

        return DashboardResponse(
            kpis = Kpis(
                parentsTotal, parentsActive, parentsSuspended, devicesTotal, devicesOnline,
                subsActive, subsTrial, monthRevenue, serverStatus
            ),
            revenueSeries = revenueSeries,
            planDist = planDist,
            regionDist = regionDist,
            alertsOpen = alertsOpen,
            recent = recent
        )
    }

    private fun monthPaidSum(): Int {
        val cal = Calendar.getInstance()
        val y = cal.get(Calendar.YEAR); val m = cal.get(Calendar.MONTH)
        return paymentOrderRepository.findAllByOrderByCreatedAtDesc()
            .filter { it.status == OrderStatus.PAID.name && inMonth(it.createdAt, y, m) }
            .sumOf { it.amountFen }
    }

    private fun inMonth(ts: Long, y: Int, m: Int): Boolean {
        val cal = Calendar.getInstance().apply { timeInMillis = ts }
        return cal.get(Calendar.YEAR) == y && cal.get(Calendar.MONTH) == m
    }

    private fun lastMonthsRevenue(n: Int): List<RevenueMonth> {
        val orders = paymentOrderRepository.findAllByOrderByCreatedAtDesc().filter { it.status == OrderStatus.PAID.name }
        val buckets = TreeMap<String, RevenueMonthAccum>()
        val now = Calendar.getInstance()
        for (i in (n - 1) downTo 0) {
            val c = now.clone() as Calendar
            c.add(Calendar.MONTH, -i)
            val key = "%04d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
            buckets[key] = RevenueMonthAccum(key)
        }
        for (o in orders) {
            val c = Calendar.getInstance().apply { timeInMillis = o.createdAt }
            val key = "%04d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
            buckets[key]?.let { acc ->
                acc.amount += o.amountFen
                acc.newSubs += 1
            }
        }
        return buckets.values.map { RevenueMonth(it.month, it.amount, it.newSubs, 0) }
    }

    private data class RevenueMonthAccum(var month: String, var amount: Int = 0, var newSubs: Int = 0)

    private fun dbUp(): Boolean = runCatching {
        dataSource.connection.use { conn: Connection ->
            conn.createStatement().use { st -> st.executeQuery("SELECT 1").use { rs -> rs.next() } }
        }
    }.getOrDefault(false)

    // ============================ 家长账号 ============================

    fun listParents(adminId: String, keyword: String?, status: String?, plan: String?): List<ParentRow> {
        // S13：读操作同样过 RBAC 闸门 —— 列表里含家长手机号等敏感数据，只读角色之外不应可见
        adminPerm.require(adminId, "parents", "read")
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

    fun parentDetail(adminId: String, id: String): ParentDetail? {
        adminPerm.require(adminId, "parents", "read")
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
        auditService.record(adminId, null, AdminAudit.PARENTS_SUSPEND, targetType = "parent", targetId = id, detail = "冻结家长 ${u.phone}")
        return mapOf("ok" to true)
    }

    @Transactional
    fun activateParent(adminId: String, id: String): Map<String, Any> {
        adminPerm.require(adminId, "parents", "write")
        val u = userRepository.findById(id).orElse(null) ?: throw BizException(ParentErr.PARAM_ERROR, "家长不存在", Audience.PARENT)
        u.status = "active"
        userRepository.save(u)
        auditService.record(adminId, null, AdminAudit.PARENTS_ACTIVATE, targetType = "parent", targetId = id, detail = "激活家长 ${u.phone}")
        return mapOf("ok" to true)
    }

    @Transactional
    fun resetParentPassword(adminId: String, id: String): Map<String, Any> {
        adminPerm.require(adminId, "parents", "reset_pwd")
        val u = userRepository.findById(id).orElse(null) ?: throw BizException(ParentErr.PARAM_ERROR, "家长不存在", Audience.PARENT)
        val newPwd = genPassword(10)
        u.passwordHash = Passwords.encode(newPwd)
        userRepository.save(u)
        auditService.record(adminId, null, AdminAudit.PARENTS_RESET_PWD, targetType = "parent", targetId = id, detail = "重置密码 ${u.phone}")
        return mapOf("ok" to true, "newPwd" to newPwd)
    }

    @Transactional
    fun createParent(adminId: String, req: AdminCreateParentRequest): Map<String, Any> {
        adminPerm.require(adminId, "parents", "create")
        if (userRepository.existsByPhone(req.phone)) {
            throw BizException(ParentErr.PARAM_ERROR, "手机号已注册", Audience.PARENT)
        }
        val pwd = genPassword(10)
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
        auditService.record(adminId, null, AdminAudit.PARENTS_CREATE, targetType = "parent", targetId = u.id, detail = "新建家长 ${u.phone}")
        return mapOf("ok" to true, "id" to u.id, "password" to pwd)
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
        auditService.record(adminId, null, AdminAudit.PARENTS_UPDATE, targetType = "parent", targetId = id, detail = "修改家长资料 ${u.phone}")
        return mapOf("ok" to true)
    }

    @Transactional
    fun deleteParent(adminId: String, id: String): Map<String, Any> {
        adminPerm.require(adminId, "parents", "delete")
        val u = userRepository.findById(id).orElse(null) ?: throw BizException(ParentErr.PARAM_ERROR, "家长不存在", Audience.PARENT)
        // 安全护栏：账号下仍有绑定设备时禁止删除，避免产生孤儿设备（设备失去归属家长后策略/解锁链路失效）。
        val deviceCount = deviceRepository.countByUserId(id)
        if (deviceCount > 0) {
            throw BizException(ParentErr.PARAM_ERROR, "该账号下仍有 ${deviceCount} 台设备，请先解绑或转交设备后再删除", Audience.PARENT)
        }
        // 关联订阅一并清理（订阅绑定 userId，账号删除后无存在意义）
        subscriptionRepository.findByUserId(id)?.let { subscriptionRepository.delete(it) }
        userRepository.delete(u)
        auditService.record(adminId, null, AdminAudit.PARENTS_DELETE, targetType = "parent", targetId = id, detail = "删除家长 ${u.phone}")
        return mapOf("ok" to true)
    }

    // ============================ 设备 ============================

    fun listDevices(adminId: String, keyword: String?, online: String?): List<DeviceRow> {
        adminPerm.require(adminId, "devices", "read")
        val users = userRepository.findAll()
        val userMap = users.associateBy { it.id }
        var devs = deviceRepository.findAll()
        if (!keyword.isNullOrBlank()) {
            val k = keyword.lowercase()
            devs = devs.filter { d ->
                d.id.lowercase().contains(k) ||
                (userMap[d.userId]?.phone?.contains(k) == true) ||
                (userMap[d.userId]?.nickname?.lowercase()?.contains(k) == true)
            }
        }
        if (online == "1") devs = devs.filter { online(it) }
        else if (online == "0") devs = devs.filter { !online(it) }
        return devs.map { d ->
            val owner = userMap[d.userId]
            DeviceRow(
                deviceId = d.id, deviceSn = d.deviceSn, name = d.name,
                parentName = owner?.nickname ?: owner?.phone?.let { "家长" + it.takeLast(4) },
                parentPhone = owner?.phone,
                model = d.model, osVersion = d.osVersion, controlMode = d.controlMode,
                online = online(d), lastOnlineAt = fmt(d.lastOnlineAt),
                remoteLocked = d.remoteLocked ?: false, minorMode = d.ageBand != null,
                todayUsageMinutes = 0, region = "", boundAt = fmt(d.createdAt)
            )
        }
    }

    @Transactional
    fun lockDevice(adminId: String, deviceId: String): Map<String, Any> {
        adminPerm.require(adminId, "devices", "lock")
        val dev = deviceRepository.findById(deviceId).orElse(null)
            ?: throw BizException(ParentErr.DEVICE_NOT_FOUND, "设备不存在", Audience.PARENT)
        dev.remoteLocked = true
        runCatching { deviceRepository.save(dev) }.onFailure { log.warn("persist remoteLocked failed: ${it.message}") }
        commandService.issueCommand(deviceId, CommandType.LOCK_SCREEN, mapOf(CommandKey.REASON to "管理员远程锁屏"))
        auditService.record(adminId, deviceId, AdminAudit.DEVICES_LOCK, targetType = "device", targetId = deviceId)
        return mapOf("ok" to true)
    }

    @Transactional
    fun unlockDevice(adminId: String, deviceId: String): Map<String, Any> {
        adminPerm.require(adminId, "devices", "unlock")
        val dev = deviceRepository.findById(deviceId).orElse(null)
            ?: throw BizException(ParentErr.DEVICE_NOT_FOUND, "设备不存在", Audience.PARENT)
        dev.remoteLocked = false
        runCatching { deviceRepository.save(dev) }.onFailure { log.warn("persist remoteLocked failed: ${it.message}") }
        commandService.issueCommand(deviceId, CommandType.UNLOCK, mapOf(CommandKey.REASON to "管理员远程解锁"))
        auditService.record(adminId, deviceId, AdminAudit.DEVICES_UNLOCK, targetType = "device", targetId = deviceId)
        return mapOf("ok" to true)
    }

    // ============================ 订阅计费 ============================

    fun listPlans(adminId: String): List<AdminPlanDto> {
        adminPerm.require(adminId, "billing", "read")
        return billingService.plans().map { p ->
            AdminPlanDto(
                id = p.key, name = p.label, priceFen = p.amountFen, deviceLimit = p.deviceLimit,
                features = PLAN_FEATURES[p.key] ?: emptyList()
            )
        }
    }

    fun listSubscriptions(adminId: String, status: String?): List<SubRow> {
        adminPerm.require(adminId, "billing", "read")
        var subs = subscriptionRepository.findAllByOrderByExpiredAtDesc()
        if (!status.isNullOrBlank()) subs = subs.filter { subStatusLower(it.status) == status.lowercase() }
        val userMap = userRepository.findAll().associateBy { it.id }
        return subs.map { s ->
            val owner = userMap[s.userId]
            SubRow(
                parentPhone = owner?.phone ?: s.userId, parentName = owner?.nickname ?: (owner?.phone?.let { "家长" + it.takeLast(4) } ?: ""),
                plan = planCategory(s.plan), planName = planLabel(s.plan),
                status = subStatusLower(s.status), startedAt = fmt(s.startedAt), expiredAt = fmt(s.expiredAt),
                autoRenew = s.autoRenew, channel = ""
            )
        }
    }

    fun listOrders(adminId: String, status: String?): List<OrderRow> {
        adminPerm.require(adminId, "billing", "read")
        var orders = paymentOrderRepository.findAllByOrderByCreatedAtDesc()
        if (!status.isNullOrBlank()) orders = orders.filter { orderStatusLower(it.status) == status.lowercase() }
        val userMap = userRepository.findAll().associateBy { it.id }
        return orders.map { o ->
            val owner = userMap[o.userId]
            OrderRow(
                orderNo = o.orderNo, parentPhone = owner?.phone ?: o.userId,
                parentName = owner?.nickname ?: (owner?.phone?.let { "家长" + it.takeLast(4) } ?: ""),
                plan = planCategory(o.plan), planName = planLabel(o.plan),
                amountFen = o.amountFen, status = orderStatusLower(o.status), channel = o.channel,
                createdAt = fmt(o.createdAt)
            )
        }
    }

    @Transactional
    fun manualActivate(adminId: String, req: AdminManualSubscriptionRequest): Map<String, Any> {
        adminPerm.require(adminId, "billing", "manual")
        val user = userRepository.findByPhone(req.parentPhone)
            ?: throw BizException(ParentErr.PARAM_ERROR, "家长账号不存在", Audience.PARENT)
        val planEnum = Plan.of(req.plan)
            ?: throw BizException(ParentErr.PARAM_ERROR, "套餐不存在", Audience.PARENT)
        if (req.days <= 0) {
            throw BizException(ParentErr.PARAM_ERROR, "开通天数必须为正整数", Audience.PARENT)
        }
        val now = System.currentTimeMillis()
        val sub = subscriptionRepository.findByUserId(user.id)
            ?: Subscription(
                id = "S" + UUID.randomUUID().toString().replace("-", ""), userId = user.id,
                plan = Plan.TRIAL.key, status = SubStatus.TRIAL.name,
                deviceLimit = billingService.deviceLimitOf(Plan.TRIAL),
                startedAt = now, expiredAt = now, createdAt = now, updatedAt = now
            )
        val base = maxOf(sub.expiredAt, now)
        sub.plan = planEnum.key
        sub.status = SubStatus.ACTIVE.name
        sub.deviceLimit = billingService.deviceLimitOf(planEnum)
        sub.expiredAt = base + req.days * 86_400_000L
        sub.updatedAt = now
        subscriptionRepository.save(sub)
        auditService.record(adminId, user.id, AdminAudit.BILLING_MANUAL, targetType = "subscription", targetId = sub.id, detail = "手动开通 ${planEnum.label} ${req.days}天 -> ${user.phone}")
        return mapOf("ok" to true, "status" to subStatusLower(sub.status), "expiredAt" to (fmt(sub.expiredAt) ?: ""))
    }

    @Transactional
    fun refund(adminId: String, orderNo: String): Map<String, Any> {
        adminPerm.require(adminId, "billing", "refund")
        // 设计约定（显性化，消除隐性约定）：退款仅变更订单状态，不联动回收订阅权益；
        // 订阅是否停用由运营按退款原因另行处理。营收统计基于订单 PAID 状态，退款后该订单
        // 自动不计入营收，因此计费口径自洽。
        val order = paymentOrderRepository.findByOrderNo(orderNo)
            ?: throw BizException(ParentErr.ORDER_NOT_FOUND, "订单不存在", Audience.PARENT)
        if (order.status == OrderStatus.REFUNDED.name) {
            return mapOf("ok" to true, "already" to true)
        }
        // 仅允许对「已支付」订单退款：对未支付(PENDING)/失败(FAILED)/关闭(CLOSED)订单退款，
        // 只会把订单状态改成 REFUNDED 却没有任何资金退回动作，形成无效的「假退款」脏数据。
        if (order.status != OrderStatus.PAID.name) {
            throw BizException(ParentErr.PARAM_ERROR, "仅已支付订单可退款（当前状态：${order.status}）", Audience.PARENT)
        }
        order.status = OrderStatus.REFUNDED.name
        paymentOrderRepository.save(order)
        auditService.record(adminId, order.userId, AdminAudit.BILLING_REFUND, targetType = "order", targetId = orderNo, detail = "退款订单 $orderNo")
        return mapOf("ok" to true)
    }

    fun revenue(adminId: String): List<RevenueMonth> {
        adminPerm.require(adminId, "billing", "read")
        return lastMonthsRevenue(12)
    }

    // ============================ RBAC ============================

    fun listRoles(adminId: String): List<AdminRoles.RoleDef> {
        adminPerm.require(adminId, "rbac", "read")
        return adminPerm.listRoles()
    }

    @Transactional
    fun saveRole(adminId: String, role: AdminRolePrivilegesDto): Map<String, Any> {
        adminPerm.require(adminId, "rbac", "write")
        adminPerm.saveRolePrivileges(role.id, role.privileges)
        auditService.record(adminId, null, "RBAC_ROLE_SAVE", targetType = "role", targetId = role.id, detail = "保存角色权限 ${role.id}")
        return mapOf("ok" to true)
    }

    fun listAdmins(adminId: String): List<AdminDto> {
        adminPerm.require(adminId, "rbac", "read")
        return adminRepository.findAll().map { toAdminDto(it) }
    }

    @Transactional
    fun createAdmin(adminId: String, req: AdminCreateRequest): Map<String, Any> {
        adminPerm.require(adminId, "rbac", "write")
        ensureNotEscalatingToSuper(adminId, req.roleId)
        if (adminRepository.existsByUsername(req.username)) {
            throw BizException(ParentErr.PARAM_ERROR, "登录账号已存在", Audience.PARENT)
        }
        if (!AdminRoles.ROLES.any { it.id == req.roleId }) {
            throw BizException(ParentErr.PARAM_ERROR, "角色不存在", Audience.PARENT)
        }
        val pwd = genPassword(12)
        val admin = Admin(
            id = "A" + UUID.randomUUID().toString().replace("-", ""),
            username = req.username, passwordHash = Passwords.encode(pwd),
            displayName = req.name?.takeIf { it.isNotBlank() } ?: req.username,
            email = req.email, role = req.roleId, status = "active",
            createdAt = System.currentTimeMillis()
        )
        adminRepository.save(admin)
        auditService.record(adminId, admin.id, AdminAudit.RBAC_CREATE, targetType = "admin", targetId = admin.id, detail = "新建管理员 ${admin.username} 角色 ${req.roleId}")
        return mapOf("ok" to true, "admin" to toAdminDto(admin), "password" to pwd)
    }

    @Transactional
    fun assignRole(adminId: String, targetAdminId: String, roleId: String): Map<String, Any> {
        adminPerm.require(adminId, "rbac", "assign")
        ensureNotEscalatingToSuper(adminId, roleId)
        if (!AdminRoles.ROLES.any { it.id == roleId }) {
            throw BizException(ParentErr.PARAM_ERROR, "角色不存在", Audience.PARENT)
        }
        val target = adminRepository.findById(targetAdminId).orElse(null)
            ?: throw BizException(ParentErr.PARAM_ERROR, "管理员不存在", Audience.PARENT)
        val old = target.role
        target.role = roleId
        adminRepository.save(target)
        auditService.record(adminId, target.id, AdminAudit.RBAC_ASSIGN, targetType = "admin", targetId = target.id, detail = "变更角色 $old -> $roleId")
        return mapOf("ok" to true)
    }

    @Transactional
    fun updateAdmin(adminId: String, targetAdminId: String, req: AdminUpdateAdminRequest): Map<String, Any> {
        adminPerm.require(adminId, "rbac", "write")
        val target = adminRepository.findById(targetAdminId).orElse(null)
            ?: throw BizException(ParentErr.PARAM_ERROR, "管理员不存在", Audience.PARENT)
        req.name?.takeIf { it.isNotBlank() }?.let { target.displayName = it }
        target.email = req.email?.takeIf { it.isNotBlank() }
        val newStatus = req.status?.takeIf { it.isNotBlank() }
        if (newStatus != null) {
            if (newStatus != "active" && newStatus != "disabled") {
                throw BizException(ParentErr.PARAM_ERROR, "状态仅支持 active / disabled", Audience.PARENT)
            }
            // 安全护栏：禁用最后一个超级管理员会锁死后台
            if (newStatus == "disabled" && target.role == "super" && countActiveSuperAdmins() <= 1) {
                throw BizException(ParentErr.FORBIDDEN, "不能停用最后一个超级管理员（将锁死后台）", Audience.PARENT)
            }
            target.status = newStatus
        }
        adminRepository.save(target)
        auditService.record(adminId, target.id, AdminAudit.RBAC_UPDATE, targetType = "admin", targetId = target.id, detail = "修改管理员 ${target.username}")
        return mapOf("ok" to true)
    }

    @Transactional
    fun deleteAdmin(adminId: String, targetAdminId: String): Map<String, Any> {
        adminPerm.require(adminId, "rbac", "delete")
        if (targetAdminId == adminId) {
            throw BizException(ParentErr.PARAM_ERROR, "不能删除当前登录的管理员账号", Audience.PARENT)
        }
        val target = adminRepository.findById(targetAdminId).orElse(null)
            ?: throw BizException(ParentErr.PARAM_ERROR, "管理员不存在", Audience.PARENT)
        // 安全护栏：删除最后一个超级管理员会锁死后台
        if (target.role == "super" && countActiveSuperAdmins() <= 1) {
            throw BizException(ParentErr.FORBIDDEN, "不能删除最后一个超级管理员（将锁死后台）", Audience.PARENT)
        }
        adminRepository.delete(target)
        auditService.record(adminId, target.id, AdminAudit.RBAC_DELETE, targetType = "admin", targetId = target.id, detail = "删除管理员 ${target.username}")
        return mapOf("ok" to true)
    }

    @Transactional
    fun resetAdminPassword(adminId: String, targetAdminId: String): Map<String, Any> {
        adminPerm.require(adminId, "rbac", "reset_pwd")
        val target = adminRepository.findById(targetAdminId).orElse(null)
            ?: throw BizException(ParentErr.PARAM_ERROR, "管理员不存在", Audience.PARENT)
        val newPwd = genPassword(12)
        target.passwordHash = Passwords.encode(newPwd)
        adminRepository.save(target)
        auditService.record(adminId, target.id, AdminAudit.RBAC_RESET_PWD, targetType = "admin", targetId = target.id, detail = "重置管理员密码 ${target.username}")
        return mapOf("ok" to true, "newPwd" to newPwd)
    }

    /** 统计处于启用状态的超级管理员数量（用于防锁死护栏） */
    private fun countActiveSuperAdmins(): Int =
        adminRepository.findAll().count { it.role == "super" && it.status == "active" }

    // ============================ 服务端监控 ============================

    fun serverMetrics(adminId: String): ServerMetrics {
        adminPerm.require(adminId, "server", "read")
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        val maxMb = rt.maxMemory() / (1024 * 1024)
        val up = dbUp()
        return ServerMetrics(
            status = if (up) "UP" else "DOWN",
            db = if (up) "UP" else "DOWN",
            uptimeMs = System.currentTimeMillis() - startedAt,
            startedAt = fmt(startedAt) ?: "",
            jvmHeapUsedMb = usedMb.toInt(),
            jvmHeapMaxMb = maxMb.toInt(),
            threads = ManagementFactory.getThreadMXBean().threadCount,
            requestsPerMin = 0,
            errorRate = "0.00",
            dbConnections = "—",
            instances = listOf(
                ServerMetrics.Instance(name = "padguard-server", host = "192.168.1.10:8090", status = if (up) "UP" else "DOWN", cpu = "—", mem = "—")
            ),
            logs = emptyList()
        )
    }

    // ============================ 审计 / 告警 ============================

    /** 对外审计查询：过 audit:read 闸门后委托内部实现（S13 读闸门） */
    fun audit(adminId: String, area: String?): List<AuditItem> {
        adminPerm.require(adminId, "audit", "read")
        return auditInternal(area)
    }

    private fun auditInternal(area: String?): List<AuditItem> {
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

    /** 审计动作 → （领域, 中文名），覆盖后台操作与常见家长端动作 */
    private fun resolveAuditAction(action: String): Pair<String, String> = when (action) {
        AdminAudit.PARENTS_SUSPEND -> "parents" to "冻结家长账号"
        AdminAudit.PARENTS_ACTIVATE -> "parents" to "激活家长账号"
        AdminAudit.PARENTS_RESET_PWD -> "parents" to "重置家长密码"
        AdminAudit.PARENTS_CREATE -> "parents" to "新建家长账号"
        AdminAudit.PARENTS_UPDATE -> "parents" to "修改家长资料"
        AdminAudit.PARENTS_DELETE -> "parents" to "删除家长账号"
        AdminAudit.DEVICES_LOCK -> "devices" to "远程锁屏"
        AdminAudit.DEVICES_UNLOCK -> "devices" to "远程解锁"
        AdminAudit.BILLING_MANUAL -> "billing" to "手动开通订阅"
        AdminAudit.BILLING_REFUND -> "billing" to "订单退款"
        AdminAudit.RBAC_ASSIGN -> "rbac" to "变更管理员角色"
        AdminAudit.RBAC_CREATE -> "rbac" to "新建管理员"
        AdminAudit.RBAC_UPDATE -> "rbac" to "修改管理员"
        AdminAudit.RBAC_DELETE -> "rbac" to "删除管理员"
        AdminAudit.RBAC_RESET_PWD -> "rbac" to "重置管理员密码"
        "RBAC_ROLE_SAVE" -> "rbac" to "保存角色权限"
        AdminAudit.SETTINGS_WRITE -> "settings" to "修改系统配置"
        "DEVICE_BIND" -> "devices" to "设备绑定"
        "DEVICE_UNBIND" -> "devices" to "设备解绑"
        "POLICY_WEB_UPDATE" -> "parents" to "策略变更(网页)"
        "POLICY_APP_UPDATE" -> "parents" to "策略变更(应用)"
        "UNLOCK_APPROVE" -> "devices" to "解锁审批通过"
        "UNLOCK_REJECT" -> "devices" to "解锁审批拒绝"
        "COMMAND_ISSUE" -> "devices" to "指令下发"
        else -> "" to action
    }

    fun alerts(adminId: String): List<AlertItem> {
        // 告警归属 audit 领域（与 closeAlert 的 audit:write 同域），读侧只需 audit:read
        adminPerm.require(adminId, "audit", "read")
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
        // 处理告警是写操作，必须经 RBAC 闸门：仅 audit:write（审计角色 / 超级管理员）可调，
        // 杜绝运营 / 客服 / 财务越权关单、掩盖风险。
        adminPerm.require(adminId, "audit", "write")
        val alert = alertRepository.findById(id).orElse(null)
            ?: throw BizException(ParentErr.PARAM_ERROR, "告警不存在", Audience.PARENT)
        alert.status = "RESOLVED"
        alert.resolvedAt = System.currentTimeMillis()
        alertRepository.save(alert)
        auditService.record(adminId, null, "ALERT_CLOSE", targetType = "alert", targetId = id, detail = "处理告警 ${alert.title}")
        return mapOf("ok" to true)
    }

    // ============================ 系统设置 ============================

    fun settings(adminId: String): AdminSettingsDto {
        adminPerm.require(adminId, "settings", "read")
        val store = loadSettingsStore()
        val plans = billingService.plans().map { p ->
            val price = store.planPrices[p.key] ?: p.amountFen
            AdminPlanDto(id = p.key, name = p.label, priceFen = price, deviceLimit = p.deviceLimit, features = PLAN_FEATURES[p.key] ?: emptyList())
        }
        return AdminSettingsDto(
            brand = store.brand, currency = store.currency, trialDays = store.trialDays,
            passwordPolicy = store.passwordPolicy, mfaRequired = store.mfaRequired,
            mockPay = store.mockPay, plans = plans
        )
    }

    @Transactional
    fun saveSettings(adminId: String, req: AdminSettingsSaveDto): AdminSettingsDto {
        adminPerm.require(adminId, "settings", "write")
        val cur = loadSettingsStore()
        val next = SettingsStore(
            brand = req.brand ?: cur.brand,
            currency = req.currency ?: cur.currency,
            trialDays = req.trialDays ?: cur.trialDays,
            passwordPolicy = req.passwordPolicy ?: cur.passwordPolicy,
            mfaRequired = req.mfaRequired ?: cur.mfaRequired,
            mockPay = req.mockPay ?: cur.mockPay,
            planPrices = cur.planPrices.toMutableMap().apply {
                req.plans?.forEach { put(it.id.uppercase(), it.priceFen) }
            }
        )
        systemSettingRepository.save(SystemSetting("admin.settings", objectMapper.writeValueAsString(next)))
        auditService.record(adminId, null, AdminAudit.SETTINGS_WRITE, targetType = "settings", targetId = "admin.settings", detail = "修改系统设置")
        return settings(adminId)
    }

    private data class SettingsStore(
        val brand: String = "PadGuard",
        val currency: String = "CNY",
        /**
         * 新用户注册后赠送的免费试用天数。
         *
         * 默认值必须与 `padguard.billing.trial-days` 一致（当前同为 1 天）：
         * 这里是**运营可见并可改**的唯一入口，[BillingService] 创建试用时会优先读它，
         * 读不到才回退配置文件。两处默认值若不一致，会出现"后台显示 7 天、实际只给 1 天"。
         */
        val trialDays: Int = 1,
        val passwordPolicy: String = "medium",
        val mfaRequired: Boolean = false,
        val mockPay: Boolean = false,
        val planPrices: Map<String, Int> = emptyMap()
    )

    private fun loadSettingsStore(): SettingsStore =
        runCatching {
            systemSettingRepository.findById("admin.settings").orElse(null)?.valueJson
                ?.let { objectMapper.readValue(it, SettingsStore::class.java) }
        }.getOrNull() ?: SettingsStore()
}
