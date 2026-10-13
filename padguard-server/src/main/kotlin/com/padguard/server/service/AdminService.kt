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
import org.springframework.core.env.Environment
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
    private val mailService: MailService,
    private val objectMapper: ObjectMapper,
    private val dataSource: DataSource,
    private val env: Environment,
    private val serverProfileService: ServerProfileService,
    private val requestMetrics: com.padguard.server.observability.RequestMetrics,
    private val runtimeLogRing: com.padguard.server.observability.RuntimeLogRing
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
        return FMT.format(Instant.ofEpochMilli(ms).atZone(ZoneId.of("Asia/Shanghai")))
    }

    private fun online(dev: Device): Boolean {
        if (dev.onlineStatus != "ONLINE") return false
        val last = dev.lastOnlineAt ?: return false
        return System.currentTimeMillis() - last <= ONLINE_STALE_MS
    }

    /** 把套餐键归一到前端下拉值（free/monthly/yearly） */
    /**
     * 套餐归类（用于后台筛选与分布统计）。
     *
     * 修复：PERMANENT（永久会员）此前落到 else 分支被当成 "free"，
     * 后台列表与首页分布图都把它显示成「免费版」——运营会误判为"付了钱没生效"。
     * 现在单独成类，前端筛选与分布统计同步支持。
     */
    private fun planCategory(planKey: String?): String = when ((planKey ?: "").uppercase()) {
        "TRIAL" -> "free"
        "MONTHLY", "QUARTERLY" -> "monthly"
        "YEARLY" -> "yearly"
        "PERMANENT" -> "permanent"
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

    /**
     * 按后台「系统设置 → 密码策略」生成初始密码 / 重置密码。
     *
     * 为什么改：passwordPolicy 此前只被存进 system_settings、没有任何地方读取，
     * 运营在后台把策略调成 high，实际发下去的还是固定 12 位 —— 属于典型的"设了没用"。
     * 现在新建家长、重置家长密码、新建管理员、重置管理员密码四处统一按策略生成。
     * 策略读取失败时回落 medium，绝不因配置损坏导致建号失败。
     */
    private fun genPassword(): String {
        val policy = runCatching { loadSettingsStore().passwordPolicy }.getOrDefault("medium")
        val (len, withSymbol) = when (policy) {
            "low" -> 8 to false
            "high" -> 16 to true
            else -> 12 to true
        }
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
        val rnd = SecureRandom()
        val core = (1..len).joinToString("") { chars[rnd.nextInt(chars.length)].toString() }
        return if (withSymbol) core + "!" else core
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
        // users.region 字段存在（V13 起可维护），此前恒返回空数组导致首页柱状图永远空白。
        // 按真实 region 分组；未填写的归入「未填写」，避免运营看到空图却查不出原因。
        val regionDist = userRepository.findAll()
            .groupBy { it.region?.takeIf { r -> r.isNotBlank() } ?: "未填写" }
            .map { (region, list) -> NameCount(region, list.size) }
            .sortedByDescending { it.count }

        val alertsOpen = alertRepository.countByStatus("ACTIVE")

        // 首页最近操作：必须尊重 audit:read —— 没有审计查看权限的角色不应在首页看到操作流，
        // 否则 adminPerm.require(id,"audit","read") 这道闸门会被仪表盘从旁路掏空。
        val recent = if (adminPerm.can(adminId, "audit", "read")) audit(adminId, null).take(6) else emptyList()

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

    /**
     * 读操作的权限闸门。
     *
     * 此前服务端只对写操作 require，全系统**没有任何一处**校验过 read —— 因此在 RBAC 矩阵里
     * 给某角色勾选或取消「查看」在服务端毫无区别，"只读角色"实际上并不成立：
     * 任何能进后台的管理员都能拉取全部列表数据（含财务营收、家长手机号、审计日志）。
     *
     * 现在所有返回业务列表的 Service 方法都在首行过这道闸门，
     * 矩阵里的「查看」列才真正等价于"能不能打开这个页面"。
     */
    private fun requireRead(adminId: String, area: String) = adminPerm.require(adminId, area, "read")

    // ============================ 家长账号 ============================

    fun listParents(adminId: String, keyword: String?, status: String?, plan: String?): List<ParentRow> {
        requireRead(adminId, "parents")
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
        requireRead(adminId, "parents")
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
        val newPwd = genPassword()
        u.passwordHash = Passwords.encode(newPwd)
        userRepository.save(u)
        auditService.record(adminId, null, AdminAudit.PARENTS_RESET_PWD, targetType = "parent", targetId = id, detail = "重置密码 ${u.phone}")
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
        val pwd = genPassword()
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

        // 「创建并开通」必须真的开通：此前前端传的 plan 被服务端完全忽略，
        // 运营选了「年度会员」点创建，建出来的号仍是免费版 —— 界面承诺与后台行为不一致。
        // 现在按所选套餐直接落一条 ACTIVE 订阅；永久会员按 100 年计（不做过期处理）。
        val planKey = req.plan?.takeIf { it.isNotBlank() }
        val activated = if (planKey != null && planKey.uppercase() !in setOf("FREE", "TRIAL")) {
            adminPerm.require(adminId, "billing", "manual")
            val planEnum = Plan.of(planKey)
                ?: throw BizException(ParentErr.PARAM_ERROR, "套餐不存在：$planKey", Audience.PARENT)
            val days = if (planEnum == Plan.PERMANENT) 36_500 else planEnum.months * 30
            manualActivate(adminId, AdminManualSubscriptionRequest(parentPhone = u.phone, plan = planEnum.key, days = days))
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
        requireRead(adminId, "devices")
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
        requireRead(adminId, "billing")
        return billingService.plans().map { p ->
            AdminPlanDto(
                id = p.key, name = p.label, priceFen = p.amountFen, deviceLimit = p.deviceLimit,
                features = PLAN_FEATURES[p.key] ?: emptyList()
            )
        }
    }

    fun listSubscriptions(adminId: String, status: String?): List<SubRow> {
        requireRead(adminId, "billing")
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
        requireRead(adminId, "billing")
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
        requireRead(adminId, "billing")
        return lastMonthsRevenue(12)
    }

    // ============================ RBAC ============================

    /** 权限元数据（领域/动作中文名 + 危险等级 + 系统锁定角色），供前端渲染矩阵 */
    fun permissionSchema(adminId: String): Map<String, Any> {
        requireRead(adminId, "rbac")
        return adminPerm.permissionSchema()
    }

    fun listRoles(adminId: String): List<com.padguard.server.domain.AdminRoles.RoleDef> {
        requireRead(adminId, "rbac")
        return adminPerm.listRoles()
    }

    /**
     * 新建自定义角色。
     *
     * 此前后台「新建角色」只在前端内存 push 一条，刷新即丢、服务端不存在该角色，
     * 一旦被指派给管理员，权限解析为空、该管理员什么都做不了。现在落库并参与权限判定。
     */
    @Transactional
    fun createRole(adminId: String, req: AdminRoleCreateRequest): Map<String, Any> {
        adminPerm.require(adminId, "rbac", "write")
        adminPerm.createRole(req.id, req.name, req.description, req.privileges)
        auditService.record(adminId, null, "RBAC_ROLE_CREATE", targetType = "role", targetId = req.id, detail = "新建角色 ${req.name}")
        val created = adminPerm.listRoles().firstOrNull { it.id == req.id }
        return if (created != null) mapOf<String, Any>("ok" to true, "role" to created)
        else mapOf<String, Any>("ok" to true)
    }

    @Transactional
    fun deleteRole(adminId: String, roleId: String): Map<String, Any> {
        adminPerm.require(adminId, "rbac", "write")
        adminPerm.deleteRole(roleId)
        auditService.record(adminId, null, "RBAC_ROLE_DELETE", targetType = "role", targetId = roleId, detail = "删除角色 $roleId")
        return mapOf("ok" to true)
    }

    @Transactional
    fun saveRole(adminId: String, role: AdminRolePrivilegesDto): Map<String, Any> {
        adminPerm.require(adminId, "rbac", "write")
        adminPerm.saveRolePrivileges(role.id, role.privileges)
        auditService.record(adminId, null, "RBAC_ROLE_SAVE", targetType = "role", targetId = role.id, detail = "保存角色权限 ${role.id}")
        return mapOf("ok" to true)
    }

    /**
     * 还原角色权限到系统默认值。
     *
     * 存在的意义：内置角色的权限一旦在矩阵里被改乱（典型是误取消某个领域的「查看」，
     * 导致该角色的菜单全打不开），此前没有任何自助恢复手段，只能改代码或直接改数据库。
     */
    @Transactional
    fun resetRolePrivileges(adminId: String, roleId: String): Map<String, Any> {
        adminPerm.require(adminId, "rbac", "write")
        adminPerm.resetRolePrivileges(roleId)
        auditService.record(adminId, null, "RBAC_ROLE_RESET", targetType = "role", targetId = roleId, detail = "还原角色权限 $roleId")
        return mapOf("ok" to true)
    }

    fun listAdmins(adminId: String): List<AdminDto> {
        requireRead(adminId, "rbac")
        return adminRepository.findAll().map { toAdminDto(it) }
    }

    @Transactional
    fun createAdmin(adminId: String, req: AdminCreateRequest): Map<String, Any> {
        adminPerm.require(adminId, "rbac", "write")
        ensureNotEscalatingToSuper(adminId, req.roleId)
        if (adminRepository.existsByUsername(req.username)) {
            throw BizException(ParentErr.PARAM_ERROR, "登录账号已存在", Audience.PARENT)
        }
        // 内置角色 + 运营自建角色（V24 admin_roles）都可被指派
        if (!adminPerm.allRoleKeys().contains(req.roleId)) {
            throw BizException(ParentErr.PARAM_ERROR, "角色不存在", Audience.PARENT)
        }
        val pwd = genPassword()
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
        if (!adminPerm.allRoleKeys().contains(roleId)) {
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
        val newPwd = genPassword()
        target.passwordHash = Passwords.encode(newPwd)
        adminRepository.save(target)
        auditService.record(adminId, target.id, AdminAudit.RBAC_RESET_PWD, targetType = "admin", targetId = target.id, detail = "重置管理员密码 ${target.username}")
        // 重置生效后立即邮件通知对方（若已登记邮箱且已配置 SMTP）；否则退回后台弹窗明文转告
        val emailed = mailService.sendPasswordReset(target.email, target.username, "管理员账号", newPwd)
        return mapOf("ok" to true, "newPwd" to newPwd, "emailed" to emailed)
    }

    /**
     * 管理员自助修改自己的密码。
     *
     * 与「重置他人密码」区分：此处必须校验当前密码（证明是本人），且不涉及 RBAC 权限闸门——
     * 任何已登录的管理员都可修改自己的密码。新密码强制至少 8 位。
     */
    @Transactional
    fun changeOwnPassword(adminId: String, currentPassword: String, newPassword: String): Map<String, Any> {
        if (newPassword.length < 8) {
            throw BizException(ParentErr.PARAM_ERROR, "新密码至少 8 位", Audience.PARENT)
        }
        val admin = adminRepository.findById(adminId).orElse(null)
            ?: throw BizException(ParentErr.UNAUTHORIZED, "管理员不存在", Audience.PARENT)
        if (!Passwords.matches(currentPassword, admin.passwordHash)) {
            throw BizException(ParentErr.UNAUTHORIZED, "当前密码错误", Audience.PARENT)
        }
        admin.passwordHash = Passwords.encode(newPassword)
        adminRepository.save(admin)
        auditService.record(adminId, null, "RBAC_SELF_CHG_PWD", targetType = "admin", targetId = adminId, detail = "修改自身密码")
        return mapOf("ok" to true)
    }

    /** 统计处于启用状态的超级管理员数量（用于防锁死护栏） */
    private fun countActiveSuperAdmins(): Int =
        adminRepository.findAll().count { it.role == "super" && it.status == "active" }

    // ============================ 服务端监控 ============================

    fun serverMetrics(adminId: String): ServerMetrics {
        requireRead(adminId, "server")
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
            // 以下三项此前是硬编码的 0 / "0.00" / "—"，运营看到的是永远不变的假数字。
            // 现在取自 [com.padguard.server.observability.RequestMetrics] 的 60 秒滑动窗口真值。
            requestsPerMin = requestMetrics.requestsPerMin(),
            qpmHistory = requestMetrics.qpmHistory(),
            errorRate = requestMetrics.errorRate(),
            avgLatencyMs = requestMetrics.avgLatencyMs(),
            dbConnections = dbConnections(),
            instances = listOf(
                ServerMetrics.Instance(
                    name = "padguard-server",
                    // 主机地址取自后台可维护的「服务器基本信息」，此前写死 192.168.1.10:8090，
                    // 改了对外地址后台却仍显示旧值，排查时会误导。
                    host = runCatching { serverProfileService.getServerProfile() }
                        .getOrNull()?.let { "${it.publicHost}:${it.port}" } ?: "—",
                    status = if (up) "UP" else "DOWN",
                    cpu = "—",
                    mem = "${usedMb.toInt()} / ${maxMb.toInt()} MB"
                )
            ),
            logs = runtimeLogRing.recent()
        )
    }

    /**
     * 数据库连接池使用情况。
     *
     * 取 HikariCP 的池指标（Spring Boot 默认连接池）；若部署换成其他 DataSource
     * 或 MXBean 不可用，返回明确说明而非占位符 "—"，避免"看不懂是没数据还是没接"。
     */
    private fun dbConnections(): String = runCatching {
        val hikari = dataSource as? com.zaxxer.hikari.HikariDataSource
        val mx = hikari?.hikariPoolMXBean
        if (mx != null) {
            "${mx.activeConnections} 活跃 / ${mx.idleConnections} 空闲 / ${mx.totalConnections} 总数"
        } else {
            "${dataSource.javaClass.simpleName}（未暴露池指标）"
        }
    }.getOrDefault("不可用")

    /** 后台「实时日志」：直接透传进程内日志环形缓冲 */
    fun serverLogs(adminId: String): List<ServerMetrics.LogLine> {
        requireRead(adminId, "server")
        return runtimeLogRing.recent()
    }

    // ============================ 审计 / 告警 ============================

    fun audit(adminId: String, area: String?): List<AuditItem> {
        requireRead(adminId, "audit")
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
        requireRead(adminId, "audit")
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
        requireRead(adminId, "settings")
        return settingsInternal()
    }

    /**
     * 读取设置的内部实现，不带闸门。
     *
     * 供 [saveSettings] 在保存成功后回读最新值：那里已通过更严格的 `settings:write` 校验，
     * 若此处再要求 read，会让"有写无读"的配置（虽然我们已在保存前拦截）把保存接口卡在回读环节。
     */
    private fun settingsInternal(): AdminSettingsDto {
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
        // W22：示例支付是生产环境的安全红线。后台「系统设置」允许运营把 mockPay 打开，
        // 但若是生产/预发等非开发 profile，落库后计费侧（BillingService/PaymentVerifier）会据此
        // 跳过支付验签，等于开一条免验证白嫖通道。故非开发环境一律拒绝开启。
        if (next.mockPay && !isDevProfile()) {
            throw BizException(ParentErr.PARAM_ERROR, "示例支付仅允许在开发环境（local/dev）开启，生产环境禁止开启", Audience.PARENT)
        }
        systemSettingRepository.save(SystemSetting("admin.settings", objectMapper.writeValueAsString(next)))
        auditService.record(adminId, null, AdminAudit.SETTINGS_WRITE, targetType = "settings", targetId = "admin.settings", detail = "修改系统设置")
        return settingsInternal()
    }

    private data class SettingsStore(
        val brand: String = "PadGuard",
        val currency: String = "CNY",
        /**
         * 新用户注册后赠送的免费试用天数。
         *
         * 默认值必须与 `padguard.billing.trial-days` 一致（当前同为 30 天，B3/D2 免费内测延长）：
         * 这里是**运营可见并可改**的唯一入口，[BillingService] 创建试用时会优先读它，
         * 读不到才回退配置文件。两处默认值若不一致，会出现"后台显示 30 天、实际只给 1 天"。
         */
        val trialDays: Int = 30,
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

    /** 当前是否处于开发类 profile（local/dev）。W22：示例支付仅允许在此类环境开启。 */
    private fun isDevProfile(): Boolean =
        env.activeProfiles.any { it.equals("local", true) || it.equals("dev", true) }
}
