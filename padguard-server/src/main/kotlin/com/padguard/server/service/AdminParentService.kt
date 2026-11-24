package com.padguard.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.common.SensitiveData
import com.padguard.server.domain.*
import com.padguard.server.dto.*
import com.padguard.server.repository.*
import com.padguard.server.security.AdminPermissionService
import com.padguard.server.security.Passwords
import com.padguard.server.ws.WebSocketPushService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.security.MessageDigest
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

    /** 冻结 / 重置密码时同步掐断该家长已建立的 WS 长连接（新增缺陷 K，理由见 AuthService）。 */
    @Autowired(required = false)
    private var webSocketPush: WebSocketPushService? = null

    /**
     * 账号注销的集中清理器。刻意用可选字段注入而不是加构造参数：
     * AdminParentService 在 AdminServiceTest 里是用 12 个位置参数直接 new 的，
     * 加构造参数会波及一整套 fake 装配；该字段只在 eraseParent 路径使用。
     * 生产上下文必有这个 @Repository bean，缺失时 eraseParent 会明确报错而不是静默跳过。
     */
    @Autowired(required = false)
    private var erasureDao: ParentErasureDao? = null

    /**
     * 设备私有数据清理器（解绑/易主共用的唯一入口）。注销时也必须逐台调用：
     * 设备台账保留是为了硬件指纹复用，但其下的截图/轨迹/用量/指令/告警
     * 属于旧家庭的儿童隐私，绝不能留给下一任家长。同样用可选字段注入，
     * 避免改动 12 参构造波及 AdminServiceTest 的整套 fake 装配。
     */
    @Autowired(required = false)
    private var deviceDataPurge: DeviceDataPurgeService? = null

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
        // 冻结只改 status、不动 token_version：HTTP 拦截器每次请求都会拒绝，
        // 但 WS 长连接握手后不再校验，必须主动踢线，否则冻结形同"下次重连才生效"。
        webSocketPush?.revokeUser(id)
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
        // 先把要在事务提交后发送邮件所需的快照拷出来（事务内对象后续可能被分离，不能依赖其状态）。
        val phone = u.phone
        val email = u.email
        // 邮件发送必须放在事务「提交之后」：SMTP 是网络调用（已设 5s 超时仍属慢路径），
        // 若仍在 @Transactional 内、持着 users 行锁时去等邮件往返，该账号的登录/登出/其它后台操作
        // 会在锁上排队；并发几次重置就能把连接池耗干。afterCommit 里连接已归还，仅占请求线程。
        // 用 isSynchronizationActive 兜底：若脱离事务上下文被调用（如纯单元测试），退回内联发送，
        // 避免 TransactionSynchronizationManager 抛 IllegalStateException 而中断整个重置流程。
        val emailedHolder = booleanArrayOf(false)
        val doSend = { emailedHolder[0] = mailService.sendPasswordReset(email, phone, "家长账号", newPwd) }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() = doSend()
            })
        } else {
            doSend()
        }
        webSocketPush?.revokeUser(id)
        auditServiceRecord(adminId, AdminAudit.PARENTS_RESET_PWD, "parent", id, "重置密码 $phone（已强制全部会话下线）")
        return mapOf("ok" to true, "newPwd" to newPwd, "emailed" to emailedHolder[0])
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
        // 生产一致性护栏：Flyway/PG 下 alerts、enrollment_sessions、unlock_tickets、
        // device_groups、bind_codes 都带 REFERENCES users(id)。即使设备已解绑、订单已退款，
        // 这些历史行仍会让 DELETE users 直接违反外键约束报 500；而 H2/ddl-auto 无外键，
        // 会"删成功"并留下悬空 user_id。两边都不该继续，统一引导到能清干净的注销入口。
        val residual = erasureDao?.residualReferences(id).orEmpty()
        if (residual.isNotEmpty()) {
            throw BizException(
                ParentErr.PARAM_ERROR,
                "该账号仍有历史关联数据（${residual.joinToString("、")}），普通删除会留下孤儿记录或失败；" +
                    "请使用「注销账号」一次性清理",
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
     * 注销家长账号（不可逆，仅超级管理员）。
     *
     * 与 [deleteParent] 的区别：普通删除遇到"有绑定设备 / 有已支付订单"只能拒绝，
     * 运营得跨好几个页面手工处理，实际就是删不掉；而账号注销是个人信息保护法意义上的
     * 删除权，不该被业务资产永久阻断。这里在**同一个事务**里按 [ParentErasurePlan] 的
     * 顺序解除所有指向 users 的引用：设备解绑但保留、作废旧设备凭据、订单归属换成匿名键
     * 但流水保留、私有配置与工单删除、订阅删除，最后才删用户行。生产 Flyway 外键因此不会再拦，
     * H2 的 ddl-auto 形态也不会留下悬空 user_id。
     *
     * 设备台账保留不等于设备数据保留：`ChildAuthInterceptor` 只认 deviceTokenHash，
     * 所以 [ParentErasurePlan] 会轮换凭据挡住旧平板上行；这里再逐台调用
     * [DeviceDataPurgeService.purge] 清掉截图/轨迹/用量/指令/告警/已装应用等 24 张表
     * 与磁盘文件，避免旧家庭的孩子隐私被下一任家长继承。
     *
     * 双闸：`parents:delete` 权限 + 硬编码的 super 角色；再要求提交完整手机号做二次确认，
     * 避免"点一下确定"就触发物理删除与财务脱敏。清空用户后主动踢 WS 长连接
     * （HTTP 侧因账号已不存在自然失效，WS 握手后不再查库）。
     */
    @Transactional
    fun eraseParent(adminId: String, id: String, confirmPhone: String): Map<String, Any> {
        adminPerm.require(adminId, "parents", "delete")
        adminPerm.requireSuper(adminId)
        val dao = erasureDao
            ?: throw BizException(ParentErr.PARAM_ERROR, "注销能力未就绪，请联系管理员", Audience.PARENT)
        val u = userRepository.findByIdForUpdate(id)
            ?: throw BizException(ParentErr.PARAM_ERROR, "家长不存在", Audience.PARENT)
        if (confirmPhone.trim() != u.phone) {
            throw BizException(ParentErr.PARAM_ERROR, "确认手机号与目标账号不一致", Audience.PARENT)
        }
        // 每个被注销用户一个稳定的匿名键：订单流水仍能按"同一来源"聚合，但反推不回原 userId。
        val anonKey = "erased_" + MessageDigest.getInstance("SHA-256")
            .digest(id.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            .take(16)
        // 台账留下了，历史数据不能留：逐台复用解绑/易主的唯一清理入口。
        // 顺序是硬约束：purge 走 REQUIRES_NEW 独立事务，必须**先于** dao.erase 的批量
        // DELETE 跑完并提交。两边都会删 alerts / 工单等同一批行，若外层先删并持锁，
        // 独立事务就得等外层释放，而外层又在等它返回 —— PG 上是死锁，H2 上是锁超时。
        val deviceIds = dao.findDeviceIds(id)
        val purge = deviceDataPurge
            ?: throw BizException(ParentErr.PARAM_ERROR, "注销能力未就绪（设备数据清理器缺失），请联系管理员", Audience.PARENT)
        // 失败可重试；三次全败就抛错让整个注销回滚（此时外层还没写任何业务行），
        // 绝不返回"注销成功"却残留隐私数据（早期版本吞异常只写日志就是这类假成功）。
        val purgedRows = deviceIds.sumOf { deviceId -> purgeDeviceHistory(purge, deviceId) }
        // dao.erase 的所有语句都按 userId 条件执行，因此即便两段之间并发新绑了一台设备，
        // 它的归属与凭据同样会被解除，不会漏掉解绑；极端情况下仅该新设备的历史数据
        // 未纳入本轮 purge（新绑设备本就无历史），重绑前由孤儿扫盘/人工确认兜底。
        val outcome = dao.erase(id, anonKey, deviceIds)
        val counts = outcome.counts
        // 用户行最后删：此时所有引用方已解除，PG 的 Flyway 外键不会拦；H2 也不会留孤儿。
        userRepository.delete(u)
        webSocketPush?.revokeUser(id)
        auditServiceRecord(
            adminId, AdminAudit.PARENTS_ERASE, "parent", id,
            "注销家长 ${SensitiveData.maskPhone(u.phone)}：解绑并作废旧凭据 ${counts["devices"] ?: 0} 台设备、" +
                "清除设备历史数据 ${purgedRows} 行、" +
                "脱敏 ${counts["payment_orders"] ?: 0} 笔订单（流水保留，归属=${anonKey.take(12)}…）"
        )
        return mapOf(
            "ok" to true, "anonKey" to anonKey, "erased" to counts,
            "revokedDevices" to outcome.deviceIds.size, "purgedDeviceRows" to purgedRows
        )
    }

    /**
     * 清理单台设备的历史数据，最多重试 3 次（独立事务，重试是真重试）。
     * 全部失败则上抛，让外层注销事务整体回滚——账号还在、凭据未作废，
     * 管理员重试即可；不能吞异常只记日志，那会给出"已注销"的假承诺而隐私仍在。
     */
    private fun purgeDeviceHistory(purge: DeviceDataPurgeService, deviceId: String): Int {
        repeat(3) { attempt ->
            try {
                return purge.purge(deviceId, "ACCOUNT_ERASE").values.sum()
            } catch (e: Exception) {
                log.warn("注销清理设备 {} 历史数据失败（第 {} 次）：{}", deviceId, attempt + 1, e.message)
            }
        }
        throw BizException(
            ParentErr.PARAM_ERROR,
            "注销失败：设备历史数据清理未完成，账号未删除，请重试", Audience.PARENT
        )
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
