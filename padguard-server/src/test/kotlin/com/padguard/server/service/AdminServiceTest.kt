package com.padguard.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.*
import com.padguard.server.dto.AdminCreateParentRequest
import com.padguard.server.dto.AdminCreateRequest
import com.padguard.server.dto.AdminManualSubscriptionRequest
import com.padguard.server.dto.AdminRolePrivilegesDto
import com.padguard.server.dto.AdminSettingsSaveDto
import com.padguard.server.dto.AdminUpdateParentRequest
import com.padguard.server.dto.AdminUpdateAdminRequest
import com.padguard.server.mqtt.MqttGateway
import com.padguard.server.repository.*
import com.padguard.server.security.AdminPermissionService
import com.padguard.server.security.DeviceOwnershipGuard
import com.padguard.server.security.JwtTokenProvider
import com.padguard.server.ws.WebSocketPushService
import org.mockito.Mockito
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Pageable
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.mail.javamail.JavaMailSenderImpl
import com.padguard.server.observability.RequestMetrics
import com.padguard.server.observability.RuntimeLogRing
import java.util.LinkedHashMap
import org.springframework.data.domain.Page

/**
 * 管控后台写操作 + RBAC 越权拦截的单元测试。
 *
 * 这是上一阶段"管理后台打通 server 端"之后最关键的把关：写操作（冻结/激活家长、新建家长/管理员、
 * 变更角色）必须经过服务端的 RBAC 闸门，越权请求不能落到业务层、更不能落库。
 *
 * 设计要点：
 * 1. 闸门用**真实**的 [AdminPermissionService]（与 HTTP 链路同一份代码），而非 mock —— 这样测到的
 *    就是线上真正生效的权限判定；admin 仓储在 [AdminPermissionService] 与 [AdminService] 间共享，
 *    保证"某个管理员有什么角色"这一事实在闸门与业务层一致。
 * 2. 其余仓储一律用内存假实现（沿用 [FakeJpaRepository]），不启动服务端、不连库，避免环境审批与 flaky。
 * 3. 断言除了"抛 FORBIDDEN"，还要断言"越权请求没有产生任何落库副作用"——只验异常不够，
 *    必须证明坏请求既被拦住又没留下残骸。
 */
class AdminServiceTest {

    // ----------------------------- 内存假仓储 -----------------------------

    private class FakeUserRepo : FakeJpaRepository<User, String>(), UserRepository {
        override val table = LinkedHashMap<String, User>()
        override fun idOf(e: User) = e.id
        override fun existsByPhone(phone: String) = table.values.any { it.phone == phone }
        override fun findByPhone(phone: String): User? = table.values.firstOrNull { it.phone == phone }
        override fun countByStatus(status: String) = table.values.count { (it.status ?: "active") == status }.toLong()
        override fun findByIdForUpdate(id: String): User? = table[id]

        // ---- 登录失败计数：语义必须与真实仓储的 SQL 一致，
        // 否则「测试通过但线上是错的」比不补更危险 ----
        override fun registerLoginFailure(phone: String, maxAttempts: Int, lockUntil: Long): Int {
            val u = table.values.firstOrNull { it.phone == phone } ?: return 0
            val next = (u.loginFailCount ?: 0) + 1
            if (next >= maxAttempts) {
                // 与 SQL 的 CASE 一致：达阈值时计数清零并写入锁定时间
                u.loginFailCount = 0
                u.loginLockedUntil = lockUntil
            } else {
                u.loginFailCount = next
            }
            return 1
        }

        override fun clearLoginFailure(phone: String): Int {
            val u = table.values.firstOrNull { it.phone == phone } ?: return 0
            u.loginFailCount = 0
            u.loginLockedUntil = null
            return 1
        }

        override fun touchLastLoginAt(id: String, ts: Long): Int {
            val u = table[id] ?: return 0
            u.lastLoginAt = ts
            return 1
        }
        // 内存版复刻 SQL 的过滤/分页语义：listParents 的筛选与翻页已下推数据库，
        // 假仓储若不跟进，翻页/筛选用例测的就不再是真实行为。
        override fun searchParents(
            status: String,
            keyword: String,
            kw: String,
            planMode: String,
            pageable: org.springframework.data.domain.Pageable
        ): org.springframework.data.domain.Page<User> {
            var all = table.values.filter { status.isEmpty() || (it.status ?: "active") == status }
            if (keyword.isNotEmpty()) {
                all = all.filter {
                    it.phone.lowercase().contains(keyword) ||
                        (it.nickname ?: "").lowercase().contains(keyword) ||
                        it.id.lowercase().contains(keyword)
                }
            }
            // 假仓储不持有订阅表，套餐维度按"全部免费"处理（用例未覆盖付费筛选）
            if (planMode.isNotEmpty() && planMode != "free") {
                return org.springframework.data.domain.PageImpl(emptyList(), pageable, 0)
            }
            val sorted = all.sortedByDescending { it.createdAt }
            val from = pageable.offset.toInt()
            val to = minOf(from + pageable.pageSize, sorted.size)
            val content = if (from >= sorted.size) emptyList<User>() else sorted.subList(from, to)
            return org.springframework.data.domain.PageImpl(content, pageable, sorted.size.toLong())
        }
    
        override fun countByStatusOrNullAsActive(status: String): Long =
            table.values.count { (it.status ?: "active") == status }.toLong()

        override fun countGroupedByRegion(): List<Array<Any?>> =
            table.values.groupBy { it.region }
                .map { arrayOf(it.key, it.value.size.toLong()) as Array<Any?> }

        override fun findCreatedAtSince(since: Long): List<Long> =
            table.values.map { it.createdAt }.filter { it >= since }
}

    private class FakeDeviceRepo : FakeJpaRepository<Device, String>(), DeviceRepository {
        override val table = LinkedHashMap<String, Device>()
        override fun idOf(e: Device) = e.id
        override fun countGroupByUserIds(userIds: Collection<String>): List<Array<Any>> =
            table.values.filter { it.userId in userIds }.groupBy { it.userId }
                .map { arrayOf(it.key, it.value.size.toLong()) as Array<Any> }
        override fun findByIdForUpdate(id: String): Device? = table[id]
        override fun searchDevices(keyword: String, kw: String, onlineMode: String, staleCut: Long, pageable: org.springframework.data.domain.Pageable): org.springframework.data.domain.Page<Device> {
            var all = table.values.toList()
            if (onlineMode == "1") all = all.filter { it.onlineStatus == "ONLINE" && (it.lastOnlineAt ?: 0) >= staleCut }
            if (onlineMode == "0") all = all.filter { !(it.onlineStatus == "ONLINE" && (it.lastOnlineAt ?: 0) >= staleCut) }
            val sorted = all.sortedByDescending { it.createdAt }
            val from = pageable.offset.toInt()
            val to = minOf(from + pageable.pageSize, sorted.size)
            val content = if (from >= sorted.size) emptyList<Device>() else sorted.subList(from, to)
            return org.springframework.data.domain.PageImpl(content, pageable, sorted.size.toLong())
        }
        override fun findByUserId(userId: String) = table.values.filter { it.userId == userId }
        override fun countByUserId(userId: String) = table.values.count { it.userId == userId }.toLong()
        override fun countByOnlineStatus(onlineStatus: String) = table.values.count { it.onlineStatus == onlineStatus }.toLong()
        override fun findByOnlineStatus(onlineStatus: String) =
            table.values.filter { it.onlineStatus == onlineStatus }
        override fun findByOnlineStatusAndLastOnlineAtLessThan(onlineStatus: String, ts: Long) =
            table.values.filter { it.onlineStatus == onlineStatus && (it.lastOnlineAt ?: Long.MAX_VALUE) < ts }
        override fun findByDeviceSn(deviceSn: String) = table.values.filter { it.deviceSn == deviceSn }
        override fun findByLastActiveAtGreaterThan(since: Long) =
            table.values.filter { (it.lastActiveAt ?: 0L) > since }
    
        override fun countOnlineSince(staleCut: Long): Long = table.values.count {
            it.onlineStatus == "ONLINE" && it.lastOnlineAt != null && it.lastOnlineAt!! >= staleCut
        }.toLong()

        override fun findCreatedAtSince(since: Long): List<Long> =
            table.values.map { it.createdAt }.filter { it >= since }
}

    private class FakeSubRepo : FakeJpaRepository<Subscription, String>(), SubscriptionRepository {
        override val table = LinkedHashMap<String, Subscription>()
        override fun idOf(e: Subscription) = e.id
        override fun findByUserId(userId: String): Subscription? = table.values.firstOrNull { it.userId == userId }
        override fun findByUserIdForUpdate(userId: String): Subscription? = findByUserId(userId)

        /** 与真实 SQL 同语义：仅"仍是可用态且确实过期"才命中 1 行 */
        override fun markExpiredIfStale(userId: String, expiredStatus: String, now: Long): Int {
            val s = table.values.firstOrNull { it.userId == userId } ?: return 0
            if (s.status != "TRIAL" && s.status != "ACTIVE") return 0
            if (s.expiredAt >= now) return 0
            s.status = expiredStatus
            s.updatedAt = now
            return 1
        }
        override fun findByUserIdIn(userIds: Collection<String>): List<Subscription> =
            table.values.filter { it.userId in userIds }
        override fun countByStatus(status: String) = table.values.count { it.status == status }.toLong()
        override fun findAllByOrderByExpiredAtDesc() = table.values.sortedByDescending { it.expiredAt }
    
        override fun countGroupedByPlan(): List<Array<Any?>> =
            table.values.groupBy { it.plan }
                .map { arrayOf(it.key, it.value.size.toLong()) as Array<Any?> }

        override fun findCreatedAtSince(since: Long): List<Long> =
            table.values.map { it.createdAt }.filter { it >= since }

        override fun searchSubscriptions(status: String, pageable: Pageable): Page<Subscription> {
            val matched = table.values
                .filter { status.isBlank() || it.status.lowercase() == status }
                .sortedByDescending { it.expiredAt }
            return pageOf(matched, pageable)
        }
}

    private class FakeOrderRepo : FakeJpaRepository<PaymentOrder, String>(), PaymentOrderRepository {
        override val table = LinkedHashMap<String, PaymentOrder>()
        override fun idOf(e: PaymentOrder) = e.id
        override fun findByOrderNo(orderNo: String): PaymentOrder? = table.values.firstOrNull { it.orderNo == orderNo }
        override fun findByUserIdOrderByCreatedAtDesc(userId: String, pageable: Pageable) =
            table.values.filter { it.userId == userId }.sortedByDescending { it.createdAt }
        override fun findByUserIdAndStatus(userId: String, status: String) =
            table.values.filter { it.userId == userId && it.status == status }
        override fun findAllByOrderByCreatedAtDesc() = table.values.sortedByDescending { it.createdAt }
        override fun markRefundedIfPaid(orderNo: String, refundedStatus: String, paidStatus: String): Int {
            val o = table.values.firstOrNull { it.orderNo == orderNo } ?: return 0
            if (o.status != paidStatus) return 0
            o.status = refundedStatus
            return 1
        }
        override fun claimTimeoutClose(orderNo: String, now: Long): Int {
            val o = table.values.firstOrNull { it.orderNo == orderNo } ?: return 0
            if (o.status != "PENDING" || o.payDeadline >= now) return 0
            o.status = "CLOSED"
            return 1
        }
        override fun claimPendingOrder(
            orderNo: String, paidStatus: String, pendingStatus: String, paidAt: Long, txId: String?
        ): Int {
            val order = findByOrderNo(orderNo) ?: return 0
            if (order.status != pendingStatus) return 0
            order.status = paidStatus; order.paidAt = paidAt; order.transactionId = txId
            return 1
        }
    
        override fun searchOrders(status: String, pageable: Pageable): Page<PaymentOrder> {
            val matched = table.values
                .filter { status.isBlank() || it.status.lowercase() == status }
                .sortedByDescending { it.createdAt }
            return pageOf(matched, pageable)
        }

        override fun findPaidAmountsSince(paidStatus: String, since: Long): List<Array<Any?>> =
            table.values.filter { it.status == paidStatus && it.createdAt >= since }
                .map { arrayOf(it.createdAt, it.amountFen) as Array<Any?> }

        /** 与真实 SQL 同语义：只关闭仍为 PENDING 的订单 */
        override fun closeIfPending(orderNo: String, closedStatus: String, pendingStatus: String): Int {
            val o = table.values.firstOrNull { it.orderNo == orderNo } ?: return 0
            if (o.status != pendingStatus) return 0
            o.status = closedStatus
            return 1
        }
}

    private class FakeAdminRepo : FakeJpaRepository<Admin, String>(), AdminRepository {
        override val table = LinkedHashMap<String, Admin>()
        override fun idOf(e: Admin) = e.id
        override fun findByUsername(username: String): Admin? = table.values.firstOrNull { it.username == username }
        override fun findByIdForUpdate(id: String): Admin? = table[id]
        override fun existsByUsername(username: String) = table.values.any { it.username == username }
        override fun countByStatus(status: String) = table.values.count { it.status == status }.toLong()
    }

    private class FakeOverrideRepo : FakeJpaRepository<AdminRoleOverride, String>(), AdminRoleOverrideRepository {
        override val table = LinkedHashMap<String, AdminRoleOverride>()
        override fun idOf(e: AdminRoleOverride) = e.id
        override fun findByRoleKey(roleKey: String): AdminRoleOverride? = table.values.firstOrNull { it.roleKey == roleKey }
    }

    private class FakeAlertRepo : FakeJpaRepository<Alert, String>(), AlertRepository {
        override val table = LinkedHashMap<String, Alert>()
        override fun idOf(e: Alert) = e.id
        override fun findByUserIdAndStatus(userId: String, status: String, pageable: Pageable) = emptyList<Alert>()
        override fun findByUserId(userId: String, pageable: Pageable) = emptyList<Alert>()
        override fun countByUserIdAndStatus(userId: String, status: String) = 0L
        override fun countByStatus(status: String) = table.values.count { it.status == status }.toLong()
        override fun findByDeviceIdOrderByTriggeredAtDesc(deviceId: String) = emptyList<Alert>()
        override fun findTop500ByOrderByTriggeredAtDesc(): List<Alert> =
            table.values.sortedByDescending { it.triggeredAt }.take(500)
        override fun findByUserIdAndDeviceIdAndStatus(userId: String, deviceId: String, status: String, pageable: Pageable) = emptyList<Alert>()
        override fun findByUserIdAndDeviceId(userId: String, deviceId: String, pageable: Pageable) = emptyList<Alert>()
        override fun countByUserIdAndStatusAndDeviceId(userId: String, status: String, deviceId: String) = 0L
        override fun deleteByDeviceId(deviceId: String): Int {
            val before = table.size
            table.values.removeIf { it.deviceId == deviceId }
            return before - table.size
        }
    }

    private class FakeAuditLogRepo : FakeJpaRepository<AuditLog, String>(), AuditLogRepository {
        override val table = LinkedHashMap<String, AuditLog>()
        override fun idOf(e: AuditLog) = e.id
        override fun findByUserIdAndCreatedAtBetween(userId: String, start: Long, end: Long) = emptyList<AuditLog>()
        override fun findByDeviceIdAndCreatedAtBetween(deviceId: String, start: Long, end: Long) = emptyList<AuditLog>()
        override fun findAllByOrderByCreatedAtDesc() = table.values.sortedByDescending { it.createdAt }
        override fun findByDeviceIdOrderByCreatedAtDesc(deviceId: String, pageable: Pageable) = emptyList<AuditLog>()
        override fun findByDeviceIdAndActionOrderByCreatedAtDesc(deviceId: String, action: String, pageable: Pageable) = emptyList<AuditLog>()
        override fun findByUserIdOrderByCreatedAtDesc(userId: String, pageable: Pageable) = emptyList<AuditLog>()
        override fun findByUserIdAndActionOrderByCreatedAtDesc(userId: String, action: String, pageable: Pageable) = emptyList<AuditLog>()
    
        override fun findAllByOrderByCreatedAtDesc(pageable: Pageable): Page<AuditLog> =
            pageOf(table.values.sortedByDescending { it.createdAt }, pageable)

        override fun findByActionInOrderByCreatedAtDesc(
            actions: Collection<String>, pageable: Pageable
        ): Page<AuditLog> =
            pageOf(table.values.filter { it.action in actions }.sortedByDescending { it.createdAt }, pageable)
}

    private class FakeSystemSettingRepo : FakeJpaRepository<SystemSetting, String>(), SystemSettingRepository {
        override val table = LinkedHashMap<String, SystemSetting>()
        override fun idOf(e: SystemSetting) = e.key
    }

    /** 同事 RBAC 自定义角色的内存 fake（AdminPermissionService 构造需要） */
    private class FakeCustomRoleRepo : FakeJpaRepository<CustomAdminRole, String>(), CustomAdminRoleRepository {
        override val table = LinkedHashMap<String, CustomAdminRole>()
        override fun idOf(e: CustomAdminRole) = e.id
        override fun existsByName(name: String) = table.values.any { it.name == name }
    }

    private class FakeCommandRepo : FakeJpaRepository<Command, String>(), CommandRepository {
        override val table = LinkedHashMap<String, Command>()
        override fun idOf(e: Command) = e.id
        override fun findByMsgId(msgId: String): Command? = table.values.firstOrNull { it.msgId == msgId }
        override fun findByMsgIdAndDeviceId(msgId: String, deviceId: String): Command? =
            table.values.firstOrNull { it.msgId == msgId && it.deviceId == deviceId }
        override fun findByDeviceIdOrderByCreatedAtDesc(deviceId: String) =
            table.values.filter { it.deviceId == deviceId }.sortedByDescending { it.createdAt }
        override fun findByDeviceIdOrderByCreatedAtAsc(deviceId: String) =
            table.values.filter { it.deviceId == deviceId }.sortedBy { it.createdAt }
        override fun findPendingForPolling(deviceId: String, status: String, now: Long, cursor: Long, pageable: Pageable) = emptyList<Command>()
        override fun findExpiredPending(deviceId: String, status: String, now: Long) = emptyList<Command>()
        override fun deleteByDeviceId(deviceId: String): Int {
            val before = table.size
            table.values.removeIf { it.deviceId == deviceId }
            return before - table.size
        }
    }

    private class FakeScreenshotRepo : FakeJpaRepository<Screenshot, String>(), ScreenshotRepository {
        override val table = LinkedHashMap<String, Screenshot>()
        override fun idOf(e: Screenshot) = e.id
        override fun findByDeviceIdOrderByCapturedAtDesc(deviceId: String, pageable: Pageable) = emptyList<Screenshot>()
        override fun findFirstByDeviceIdAndShotId(deviceId: String, shotId: String): Screenshot? = null
        override fun deleteByDeviceId(deviceId: String): Int {
            val before = table.size
            table.values.removeIf { it.deviceId == deviceId }
            return before - table.size
        }
        // E11：保留期清理查询
        override fun findReceivedAtBefore(ts: Long): List<Screenshot> =
            table.values.filter { it.receivedAt < ts }
    }

    private class FakeUsageSessionRepo : FakeJpaRepository<UsageSession, String>(), UsageSessionRepository {
        override val table = LinkedHashMap<String, UsageSession>()
        override fun idOf(e: UsageSession) = e.deviceId
        override fun findByDeviceId(deviceId: String): UsageSession? = table.values.firstOrNull { it.deviceId == deviceId }
        override fun deleteByDeviceId(deviceId: String): Int {
            val before = table.size
            table.values.removeIf { it.deviceId == deviceId }
            return before - table.size
        }

    // 对应 UsageSessionRepository.addExtendedMinutes 的条件自增（额度累加不再读改写）。
    // 单日上限也作为写条件：影响 0 行 = 已到顶，语义必须与 SQL 逐字对齐，
    // 否则「并发批准顶穿上限」这条防线在测试里永远是绿的。
    override fun addExtendedMinutes(deviceId: String, minutes: Int, now: Long, maxExtendedMinutes: Int): Int {
        val s = table[deviceId] ?: return 0
        if (s.extendedMinutes + minutes > maxExtendedMinutes) return 0
        s.extendedMinutes += minutes
        s.lastDelayEpoch = now
        s.updatedAt = now
        return 1
    }
    }

    private class FakePolicyRepo : FakeJpaRepository<Policy, String>(), PolicyRepository {
        override val table = LinkedHashMap<String, Policy>()
        override fun idOf(e: Policy) = e.id
        override fun findFirstByDeviceIdOrderByVersionDesc(deviceId: String): Policy? = null
        override fun nextVersion(deviceId: String): Int =
            (table.values.filter { it.deviceId == deviceId }.maxOfOrNull { it.version } ?: 0) + 1
        override fun deleteByDeviceId(deviceId: String): Int = 0
    }

    private class FakeDeviceSettingRepo : FakeJpaRepository<DeviceSetting, String>(), DeviceSettingRepository {
        override val table = LinkedHashMap<String, DeviceSetting>()
        override fun idOf(e: DeviceSetting) = e.deviceId
        override fun deleteByDeviceId(deviceId: String): Int {
            val existed = table.containsKey(deviceId)
            table.remove(deviceId)
            return if (existed) 1 else 0
        }
    }

    // ----------------------------- 测试夹具 -----------------------------

    private class Fixture(
        val svc: AdminService,
        val users: FakeUserRepo,
        val admins: FakeAdminRepo,
        val orders: FakeOrderRepo,
        val subs: FakeSubRepo,
        val devices: FakeDeviceRepo,
        val alerts: FakeAlertRepo,
        val overrides: FakeOverrideRepo,
        val settings: FakeSystemSettingRepo,
        val usageSessions: FakeUsageSessionRepo
    )

    private fun fixture(
        activeProfiles: Set<String> = emptySet(),
        /**
         * 微信退款网关替身。
         *
         * 退款流程最关键的约束是"先让微信受理，才允许把订单标记 REFUNDED"，
         * 这条约束只有在"微信返回失败"时才体现出来 —— 用真实 `WeChatPayClient`
         * （未配置 → 直接走 isConfigured=false 分支）永远覆盖不到。
         */
        refundClient: com.padguard.server.security.WeChatRefundClient? = null
    ): Fixture {
        val users = FakeUserRepo()
        val devices = FakeDeviceRepo()
        val subs = FakeSubRepo()
        val orders = FakeOrderRepo()
        val admins = FakeAdminRepo()
        val overrides = FakeOverrideRepo()
        val alerts = FakeAlertRepo()
        val auditLogs = FakeAuditLogRepo()
        val settings = FakeSystemSettingRepo()
        val commands = FakeCommandRepo()
        val screenshots = FakeScreenshotRepo()
        val usageSessions = FakeUsageSessionRepo()

        val objectMapper = ObjectMapper()
        // 计费/指令等服务仅用于满足 AdminService 构造；本测试不触发它们的业务路径。
        // 计费验签器用示例支付放行模式（mockPayEnabled=true），避免影响 refund/manualActivate 等用例。
        val billing = BillingService(subs, orders, devices, users, 7L, 3, 3800, 9800, 29800, 3, 3, 5, 30L, true, com.padguard.server.security.ConfigurablePaymentVerifier(true, settings, objectMapper, listOf()), FakeSystemSettingRepo(), ObjectMapper())
        val auditService = AuditService(auditLogs, com.padguard.server.security.DeviceOwnershipGuard(devices))
        // mqtt 在 DeviceService/CommandService 构造前声明（Kotlin 属性初始化顺序：先声明后用）。
        val mqtt = MqttGateway(null, objectMapper, "default")
        // 真实 DeviceService：被测 AdminDeviceService.unbindDevice 把"清归属/作废令牌/清理私有数据"
        // 委托给它，必须用真实实现（mock 会架空 B7 的断言）。重型协作者以 mock 占位（unbind 路径不触发）。
        val policyRepo = FakePolicyRepo()
        val deviceSettingRepo = FakeDeviceSettingRepo()
        val ws = Mockito.mock(WebSocketPushService::class.java)
        val deviceService = DeviceService(
            devices, DeviceOwnershipGuard(devices),
            Mockito.mock(EnrollmentService::class.java),
            Mockito.mock(PolicyService::class.java),
            ws, mqtt, users, policyRepo, auditService,
            30L, "", "", billing,
            Mockito.mock(DeviceDataPurgeService::class.java),
            DevicePresenceRegistry(),
            com.padguard.server.stream.ScreenStreamHub(),
            // 批量导入的按行事务模板：构造即可，本 fixture 的用例不走导入路径，
            // 真正的导入原子性由 DeviceImportTest 用内存 H2 覆盖。
            org.springframework.transaction.support.TransactionTemplate(
                Mockito.mock(org.springframework.transaction.PlatformTransactionManager::class.java)
            )
        )
        // UsageSessionService 用真实实现驱动 B1/B2 的"今日用量"派生（走假 usageSessionRepo）。
        // usageDelayService 在 list 路径不触发，以 mock 占位。
        val usageSessionService =
            UsageSessionService(usageSessions, deviceSettingRepo, deviceService, Mockito.mock(UsageDelayService::class.java))
        // 真实 RBAC 闸门，与 AdminService 共享同一份 admin 仓储 —— 权限事实一致。
        val adminPerm = AdminPermissionService(admins, overrides, FakeCustomRoleRepo(), objectMapper)
        val sweeper = ExpiredCommandSweeper(commands, screenshots, objectMapper)
        val commandService = CommandService(
            devices, commands, mqtt, objectMapper, sweeper, auditService,
            com.padguard.server.security.DeviceOwnershipGuard(devices)
        )
        // 密钥仅用于构造；createAdmin 不签发令牌，不会真正用到。
        val jwt = JwtTokenProvider("padguard-unit-test-secret-0123456789abcdef", 120, 30)
        // 仅 dbUp() 用到；该方法以 runCatching 包裹，getConnection 抛错时回落 false，不影响写操作测试。
        val dataSource = DriverManagerDataSource()
        // 同事新增依赖：邮件（enabled=false 永不发送）、服务档案、可观测性 —— 仅满足构造，不在本测试触发。
        val mailService = MailService(JavaMailSenderImpl(), false, "test@padguard.local", "")
        val serverProfileService = ServerProfileService(settings, adminPerm, objectMapper)
        val requestMetrics = RequestMetrics()
        val runtimeLogRing = RuntimeLogRing()
        // W22：AdminService 新增 env 依赖（示例支付仅允许开发 profile 开启）。
        // 默认无 active profile，等价于生产口径；activeProfiles 参数供 dev 用例注入。
        val env = org.springframework.mock.env.MockEnvironment().apply {
            setActiveProfiles(*activeProfiles.toTypedArray())
        }

        // W25：AdminService 已拆分为多个领域 @Service，门面仅做委托。测试直接构造各子服务，
        // 公开方法签名与原本一致（f.svc.* 调用无需改动）。
        val authS = AdminAuthService(admins, jwt, adminPerm)
        val auditS = AdminAuditService(adminPerm, admins, users, auditLogs, alerts, auditService)
        val billingS = AdminBillingService(subs, orders, users, billing, adminPerm, auditService, refundClient)
        val rbacS = AdminRbacService(admins, adminPerm, auditService, mailService, settings, objectMapper)
        val settingsS = AdminSettingsService(adminPerm, settings, billing, objectMapper, auditService, env)
        val monitorS = AdminServerMonitorService(adminPerm, requestMetrics, runtimeLogRing, dataSource, serverProfileService)
        val parentS = AdminParentService(users, subs, devices, adminPerm, billing, mailService, billingS, auditService, settings, objectMapper, usageSessionService)
        val deviceS = AdminDeviceService(devices, users, commandService, adminPerm, auditService, usageSessionService, deviceService)
        val dashboardS = AdminDashboardService(users, devices, subs, orders, alerts, adminPerm, auditS, requestMetrics, runtimeLogRing, dataSource, serverProfileService, DashboardCache(0)) // TTL=0 即禁用缓存：单测要的是每次现算

        val svc = AdminService(
            authS, dashboardS, parentS, deviceS, billingS, rbacS, monitorS, auditS, settingsS
        )

        // 种入管理员：超管 / 运营 / 审计 / 财务 / 已停用超管
        admins.save(Admin("A-super", "super", "h", "超级管理员", null, "super", "active", false, 0, null))
        admins.save(Admin("A-ops", "ops", "h", "运营", null, "ops", "active", false, 0, null))
        admins.save(Admin("A-audit", "audit", "h", "审计", null, "audit", "active", false, 0, null))
        admins.save(Admin("A-finance", "finance", "h", "财务", null, "finance", "active", false, 0, null))
        admins.save(Admin("A-disabled", "disabled", "h", "停用超管", null, "super", "disabled", false, 0, null))

        return Fixture(svc, users, admins, orders, subs, devices, alerts, overrides, settings, usageSessions)
    }

    private fun seedParent(f: Fixture, id: String = "parent-1", status: String = "active") {
        f.users.save(User(id, "13800000001", "hash").also { it.status = status })
    }

    // ----------------------------- 写操作：超管放行 -----------------------------

    @Test
    fun `super can suspend a parent and the status persists`() {
        val f = fixture()
        seedParent(f)
        val r = f.svc.suspendParent("A-super", "parent-1")
        assertEquals(true, r["ok"])
        assertEquals("suspended", f.users.table["parent-1"]!!.status, "冻结必须真实落库到 user 状态")
    }

    @Test
    fun `super can activate a suspended parent`() {
        val f = fixture()
        seedParent(f, status = "suspended")
        f.svc.activateParent("A-super", "parent-1")
        assertEquals("active", f.users.table["parent-1"]!!.status, "激活必须真实落库")
    }

    @Test
    fun `super can create a parent account`() {
        val f = fixture()
        val r = f.svc.createParent("A-super", AdminCreateParentRequest(phone = "13900000001", name = "新家长"))
        assertEquals(true, r["ok"])
        assertTrue(f.users.table.values.any { it.phone == "13900000001" }, "新建家长必须落库")
    }

    @Test
    fun `super can create another admin`() {
        val f = fixture()
        val r = f.svc.createAdmin("A-super", AdminCreateRequest(username = "newadmin", name = "新管理员", roleId = "ops"))
        assertEquals(true, r["ok"])
        assertTrue(f.admins.table.values.any { it.username == "newadmin" }, "新建管理员必须落库")
    }

    @Test
    fun `super can assign a role to another admin`() {
        val f = fixture()
        f.svc.assignRole("A-super", "A-ops", "finance")
        assertEquals("finance", f.admins.table["A-ops"]!!.role, "角色变更必须真实落库")
    }

    // ----------------------------- RBAC 闸门：越权必拦且不落库 -----------------------------

    @Test
    fun `ops is blocked from creating admin (rbac write) with FORBIDDEN and nothing is persisted`() {
        val f = fixture()
        val ex = assertThrows(BizException::class.java) {
            f.svc.createAdmin("A-ops", AdminCreateRequest(username = "evil", name = "越权者", roleId = "ops"))
        }
        assertEquals(ParentErr.FORBIDDEN, ex.code, "缺 rbac:write 必须返回 FORBIDDEN")
        assertEquals(Audience.PARENT, ex.audience)
        // 最关键：越权请求绝不能落到落库
        assertFalse(f.admins.table.values.any { it.username == "evil" }, "越权创建管理员不能留下任何记录")
    }

    @Test
    fun `ops is blocked from assigning roles (rbac assign) with FORBIDDEN`() {
        val f = fixture()
        val ex = assertThrows(BizException::class.java) {
            f.svc.assignRole("A-ops", "A-super", "finance")
        }
        assertEquals(ParentErr.FORBIDDEN, ex.code)
        assertEquals("super", f.admins.table["A-super"]!!.role, "越权改角色不能篡改目标账号")
    }

    @Test
    fun `disabled super is denied even with super privileges`() {
        val f = fixture()
        val ex = assertThrows(BizException::class.java) {
            f.svc.createAdmin("A-disabled", AdminCreateRequest(username = "x", roleId = "ops"))
        }
        assertEquals(ParentErr.FORBIDDEN, ex.code, "停用账号的权限必须整体失效")
    }

    @Test
    fun `unknown admin id is denied by the gate`() {
        val f = fixture()
        val ex = assertThrows(BizException::class.java) {
            f.svc.createAdmin("ghost", AdminCreateRequest(username = "x", roleId = "ops"))
        }
        assertEquals(ParentErr.FORBIDDEN, ex.code, "不存在的管理员 id 一律按无权限处理")
    }

    // ----------------------------- 计费：退款状态机 -----------------------------

    @Test
    fun `refund rejects a non paid order (pending) with PARAM_ERROR`() {
        val f = fixture()
        f.orders.save(PaymentOrder(id = "O1", orderNo = "O1", userId = "u1", status = OrderStatus.PENDING.name, amountFen = 9800))
        val ex = assertThrows(BizException::class.java) { f.svc.refund("A-super", "O1") }
        assertEquals(ParentErr.PARAM_ERROR, ex.code, "未支付订单不允许退款")
        assertEquals(OrderStatus.PENDING.name, f.orders.table["O1"]!!.status, "非 PAID 订单退款后状态不得被改写")
    }

    @Test
    fun `refund marks a paid order as REFUNDED`() {
        val f = fixture()
        f.orders.save(PaymentOrder(id = "O2", orderNo = "O2", userId = "u1", status = OrderStatus.PAID.name, amountFen = 9800))
        val r = f.svc.refund("A-super", "O2")
        assertEquals(true, r["ok"])
        assertEquals(OrderStatus.REFUNDED.name, f.orders.table["O2"]!!.status, "已支付订单退款后必须落入 REFUNDED")
    }

    @Test
    fun `refund on an already refunded order is idempotent`() {
        val f = fixture()
        f.orders.save(PaymentOrder(id = "O3", orderNo = "O3", userId = "u1", status = OrderStatus.REFUNDED.name, amountFen = 9800))
        val r = f.svc.refund("A-super", "O3")
        assertEquals(true, r["ok"])
        assertEquals(true, r["already"])
    }

    /**
     * 微信受理失败时，订单必须**留在 PAID**。
     *
     * 这是"先标记后退款"那种单边账的直接反例：如果实现把 `markRefundedIfPaid`
     * 挪到 HTTP 调用之前，这条断言会立刻红 —— 系统显示已退款、钱却没退。
     */
    @Test
    fun `refund keeps order PAID when wechat rejects the request`() {
        val gateway = object : com.padguard.server.security.WeChatRefundClient {
            override fun isConfigured() = true
            override fun createRefund(
                orderNo: String, outRefundNo: String, refundFen: Int, totalFen: Int, reason: String
            ): Map<String, String>? = null // 网络失败 / 微信拒绝
        }
        val f = fixture(refundClient = gateway)
        f.orders.save(PaymentOrder(id = "O4", orderNo = "O4", userId = "u1", status = OrderStatus.PAID.name, amountFen = 9800))

        assertThrows(BizException::class.java) { f.svc.refund("A-super", "O4") }
        assertEquals(
            OrderStatus.PAID.name, f.orders.table["O4"]!!.status,
            "微信未受理时订单必须保持 PAID，否则就是「系统说已退、钱没退」的单边账"
        )
    }

    /** 微信受理成功：订单置 REFUNDED，并把微信退款单号回传给调用方 */
    @Test
    fun `refund marks REFUNDED and returns wechat refund id when accepted`() {
        val gateway = object : com.padguard.server.security.WeChatRefundClient {
            override fun isConfigured() = true
            override fun createRefund(
                orderNo: String, outRefundNo: String, refundFen: Int, totalFen: Int, reason: String
            ): Map<String, String>? = mapOf("refundId" to "RF-1", "status" to "PROCESSING")
        }
        val f = fixture(refundClient = gateway)
        f.orders.save(PaymentOrder(id = "O5", orderNo = "O5", userId = "u1", status = OrderStatus.PAID.name, amountFen = 9800))

        val r = f.svc.refund("A-super", "O5")
        assertEquals(true, r["ok"])
        assertEquals("RF-1", r["refundId"])
        assertEquals(OrderStatus.REFUNDED.name, f.orders.table["O5"]!!.status)
    }

    // ----------------------------- 权限：告警关单闸门 -----------------------------

    @Test
    fun `audit role can close an alert`() {
        val f = fixture()
        f.alerts.save(Alert(id = "AL1", title = "风险", status = "ACTIVE"))
        val r = f.svc.closeAlert("A-audit", "AL1")
        assertEquals(true, r["ok"])
        assertEquals("RESOLVED", f.alerts.table["AL1"]!!.status, "审计角色关单必须真实落库")
    }

    @Test
    fun `ops is blocked from closing alerts (audit write) with FORBIDDEN`() {
        val f = fixture()
        f.alerts.save(Alert(id = "AL2", title = "风险", status = "ACTIVE"))
        val ex = assertThrows(BizException::class.java) { f.svc.closeAlert("A-ops", "AL2") }
        assertEquals(ParentErr.FORBIDDEN, ex.code, "缺 audit:write 必须返回 FORBIDDEN")
        assertEquals("ACTIVE", f.alerts.table["AL2"]!!.status, "越权关单不得篡改告警状态")
    }

    // ----------------------------- 计费：手动开通天数校验 -----------------------------

    @Test
    fun `manualActivate rejects non positive days`() {
        val f = fixture()
        f.users.save(User("p-x", "13800000009", "h").also { it.status = "active" })
        val ex = assertThrows(BizException::class.java) {
            f.svc.manualActivate("A-super", AdminManualSubscriptionRequest(parentPhone = "13800000009", plan = "MONTHLY", days = 0))
        }
        assertEquals(ParentErr.PARAM_ERROR, ex.code, "days<=0 必须被拒绝")
    }

    @Test
    fun `super can manually activate a subscription for valid days`() {
        val f = fixture()
        f.users.save(User("p-y", "13800000008", "h").also { it.status = "active" })
        val r = f.svc.manualActivate("A-super", AdminManualSubscriptionRequest(parentPhone = "13800000008", plan = "MONTHLY", days = 30))
        assertEquals(true, r["ok"])
        val sub = f.subs.table.values.first { it.userId == "p-y" }
        assertEquals(SubStatus.ACTIVE.name, sub.status, "手动开通必须置为 ACTIVE")
        assertTrue(sub.expiredAt > System.currentTimeMillis(), "到期时间应顺延到未来")
    }

    // ----------------------------- RBAC：不可编辑 super / 防越权提升 -----------------------------

    @Test
    fun `saving an override for super role is rejected`() {
        val f = fixture()
        val ex = assertThrows(BizException::class.java) {
            f.svc.saveRole("A-super", AdminRolePrivilegesDto(id = "super", privileges = mapOf("parents" to listOf("read"))))
        }
        assertEquals(ParentErr.FORBIDDEN, ex.code, "超级管理员权限不可经 override 收窄，否则会自锁")
    }

    @Test
    fun `a delegated ops with rbac write cannot escalate to super`() {
        val f = fixture()
        // 模拟运营经 override 拿到 rbac:write（super 授权）
        f.overrides.save(
            AdminRoleOverride(
                id = "aro_ops", roleKey = "ops",
                privilegesJson = """{"rbac":["read","write","assign"],"parents":["read","write","create","suspend"],"devices":["read","write","lock","unlock"],"billing":["read"],"server":["read"],"audit":["read"],"settings":["read"]}"""
            )
        )
        // 现在 ops 有 rbac:write/assign，但仍不能把任何人提升为 super
        val ex = assertThrows(BizException::class.java) {
            f.svc.assignRole("A-ops", "A-finance", "super")
        }
        assertEquals(ParentErr.FORBIDDEN, ex.code, "委托获得的 rbac:write 不能突破 super 提升限制")
        assertEquals("finance", f.admins.table["A-finance"]!!.role, "越权提升不得落库")
    }

    // ----------------------------- 家长账号：修改 / 删除 -----------------------------

    @Test
    fun `super can update a parent profile fields`() {
        val f = fixture()
        seedParent(f)
        f.svc.updateParent("A-super", "parent-1", AdminUpdateParentRequest(name = "新名字", email = "a@b.com", region = "上海", remark = "VIP"))
        val u = f.users.table["parent-1"]!!
        assertEquals("新名字", u.nickname)
        assertEquals("a@b.com", u.email)
        assertEquals("上海", u.region)
        assertEquals("VIP", u.remark)
    }

    /**
     * 部分更新语义：请求里没出现的字段必须原样保留。
     *
     * 旧写法 `u.email = req.email?.takeIf { it.isNotBlank() }` —— `?.` 之后仍会把 null 赋进去，
     * 于是"只改备注"的请求会连带清空 email / region。email 被清空后该家长再也收不到
     * 重置密码邮件（MailService 直接返回 false），而页面显示"保存成功"，属于静默数据损坏。
     */
    @Test
    fun `updating only the remark must not wipe email or region`() {
        val f = fixture()
        seedParent(f)
        f.users.table["parent-1"]!!.email = "keep@example.com"
        f.users.table["parent-1"]!!.region = "北京"

        f.svc.updateParent("A-super", "parent-1", AdminUpdateParentRequest(remark = "只改备注"))

        val u = f.users.table["parent-1"]!!
        assertEquals("keep@example.com", u.email, "未传 email 不得清空")
        assertEquals("北京", u.region, "未传 region 不得清空")
        assertEquals("只改备注", u.remark)
    }

    @Test
    fun `super can delete a parent without bound devices`() {
        val f = fixture()
        seedParent(f)
        val r = f.svc.deleteParent("A-super", "parent-1")
        assertEquals(true, r["ok"])
        assertEquals(false, f.users.table.containsKey("parent-1"), "删除后家长必须从库里移除")
    }

    @Test
    fun `deleting a parent that still has devices is rejected and nothing is removed`() {
        val f = fixture()
        seedParent(f)
        // 种入一台归属该家长的设备
        f.devices.save(com.padguard.server.domain.Device("dev-1").also { it.userId = "parent-1" })
        val ex = assertThrows(BizException::class.java) { f.svc.deleteParent("A-super", "parent-1") }
        assertEquals(ParentErr.PARAM_ERROR, ex.code, "有绑定设备必须拒绝删除")
        assertEquals(true, f.users.table.containsKey("parent-1"), "拒绝删除时家长不得被移除")
        assertEquals(true, f.devices.table.containsKey("dev-1"), "拒绝删除时设备不得被连带清除")
    }

    @Test
    fun `ops can delete a parent (parents delete privilege granted)`() {
        val f = fixture()
        seedParent(f)
        val r = f.svc.deleteParent("A-ops", "parent-1")
        assertEquals(true, r["ok"])
        assertEquals(false, f.users.table.containsKey("parent-1"))
    }

    @Test
    fun `ops is blocked from updating an admin (rbac write) with FORBIDDEN`() {
        val f = fixture()
        val ex = assertThrows(BizException::class.java) {
            f.svc.updateAdmin("A-ops", "A-finance", AdminUpdateAdminRequest(name = "越权改名"))
        }
        assertEquals(ParentErr.FORBIDDEN, ex.code, "缺 rbac:write 必须返回 FORBIDDEN")
        assertEquals("财务", f.admins.table["A-finance"]!!.displayName, "越权修改管理员不得落库")
    }

    // ----------------------------- 管理员账号：修改 / 删除 / 重置密码 -----------------------------

    @Test
    fun `super can update an admin profile and status`() {
        val f = fixture()
        f.svc.updateAdmin("A-super", "A-finance", AdminUpdateAdminRequest(name = "财务小王", email = "w@x.com", status = "disabled"))
        val a = f.admins.table["A-finance"]!!
        assertEquals("财务小王", a.displayName)
        assertEquals("w@x.com", a.email)
        assertEquals("disabled", a.status, "状态必须真实落库")
    }

    @Test
    fun `deleting self is rejected with PARAM_ERROR`() {
        val f = fixture()
        val ex = assertThrows(BizException::class.java) { f.svc.deleteAdmin("A-super", "A-super") }
        assertEquals(ParentErr.PARAM_ERROR, ex.code, "不能删除当前登录账号")
        assertEquals(true, f.admins.table.containsKey("A-super"), "自删必须被拦截")
    }

    @Test
    fun `deleting a non last super admin is allowed`() {
        val f = fixture()
        f.admins.save(com.padguard.server.domain.Admin("A-super2", "super2", "h", "二号超管", null, "super", "active", false, 0, null))
        val r = f.svc.deleteAdmin("A-super", "A-super2")
        assertEquals(true, r["ok"])
        assertEquals(false, f.admins.table.containsKey("A-super2"))
    }

    @Test
    fun `deleting the last active super admin by a delegated operator with rbac delete is rejected`() {
        val f = fixture()
        // 仅 A-super 一个 active super（A-disabled 为停用）；模拟运营经 override 拿到 rbac:delete 但仍非 super
        f.overrides.save(
            com.padguard.server.domain.AdminRoleOverride(
                id = "aro_ops", roleKey = "ops",
                privilegesJson = """{"rbac":["read","write","assign","reset_pwd","delete"],"parents":["read","write","create","suspend","delete"],"devices":["read","write","lock","unlock"],"billing":["read"],"server":["read"],"audit":["read"],"settings":["read"]}"""
            )
        )
        val ex = assertThrows(BizException::class.java) { f.svc.deleteAdmin("A-ops", "A-super") }
        assertEquals(ParentErr.FORBIDDEN, ex.code, "不能删除最后一个超级管理员")
        assertEquals(true, f.admins.table.containsKey("A-super"), "末位超管不得被删除")
    }

    @Test
    fun `non super admin is blocked from deleting admins (rbac delete) with FORBIDDEN`() {
        val f = fixture()
        val ex = assertThrows(BizException::class.java) { f.svc.deleteAdmin("A-ops", "A-finance") }
        assertEquals(ParentErr.FORBIDDEN, ex.code, "缺 rbac:delete 必须返回 FORBIDDEN")
        assertEquals(true, f.admins.table.containsKey("A-finance"), "越权删除不得落库")
    }

    @Test
    fun `super can reset an admin password and the hash changes`() {
        val f = fixture()
        val before = f.admins.table["A-finance"]!!.passwordHash
        val r = f.svc.resetAdminPassword("A-super", "A-finance")
        assertEquals(true, r["ok"])
        val newHash = f.admins.table["A-finance"]!!.passwordHash
        assertEquals(true, (r["newPwd"] as String).isNotBlank(), "必须返回新密码明文")
        assertEquals(false, newHash == before, "密码哈希必须更新")
    }

    // ----------------------------- W22：示例支付开关的环境红线 -----------------------------

    @Test
    fun `saveSettings rejects enabling mockPay outside dev profile and persists nothing`() {
        val f = fixture() // 无 active profile = 生产口径
        val ex = assertThrows(BizException::class.java) {
            f.svc.saveSettings("A-super", AdminSettingsSaveDto(mockPay = true))
        }
        assertEquals(ParentErr.PARAM_ERROR, ex.code, "非开发环境开启示例支付必须被拒（W22 生产红线）")
        assertFalse(
            f.settings.table.containsKey("admin.settings"),
            "拒绝开启时不得留下任何设置落库——落库即等于给计费侧开了免验证通道"
        )
    }

    @Test
    fun `saveSettings allows disabling mockPay outside dev profile`() {
        val f = fixture()
        // 模拟 dev 时期遗留的开启状态：生产环境必须允许（且应当）关掉它
        f.settings.save(SystemSetting("admin.settings", """{"mockPay":true}"""))
        val r = f.svc.saveSettings("A-super", AdminSettingsSaveDto(mockPay = false))
        assertEquals(false, r.mockPay, "关闭示例支付是生产环境的正当操作，不应被拦")
        assertEquals(false, f.svc.settings("A-super").mockPay, "关闭必须真实落库并被回读确认")
    }

    @Test
    fun `saveSettings allows enabling mockPay in dev profile`() {
        val f = fixture(activeProfiles = setOf("dev"))
        val r = f.svc.saveSettings("A-super", AdminSettingsSaveDto(mockPay = true))
        assertEquals(true, r.mockPay, "开发环境允许开启示例支付（本地联调用）")
        assertEquals(true, f.svc.settings("A-super").mockPay, "开启必须真实落库并被回读确认")
    }

    // ----------------------------- B6：登出吊销（token 版本） -----------------------------

    @Test
    fun `logout bumps admin token version so prior tokens are revoked`() {
        val f = fixture()
        assertEquals(0, f.admins.table["A-super"]!!.tokenVersion, "初始令牌版本为 0")
        f.svc.logout("A-super")
        assertEquals(1, f.admins.table["A-super"]!!.tokenVersion, "登出必须自增令牌版本，使所有历史令牌（含本次）失效")
    }

    // ----------------------------- B7：运营后台设备解绑端点 -----------------------------

    @Test
    fun `super can unbind a device and revoke its token`() {
        val f = fixture()
        f.users.save(User("p-b7", "13800000005", "h").also { it.status = "active" })
        f.devices.save(com.padguard.server.domain.Device("dev-b7").also {
            it.userId = "p-b7"; it.deviceTokenHash = "valid-token"
        })
        val r = f.svc.unbindDevice("A-super", "dev-b7")
        assertEquals(true, r["ok"])
        val dev = f.devices.table["dev-b7"]!!
        assertEquals(null, dev.userId, "解绑必须清除设备归属")
        assertTrue(dev.deviceTokenHash.startsWith("revoked_"), "解绑必须作废设备令牌，使旧凭据心跳 401")
    }

    @Test
    fun `ops without devices unbind is blocked from unbinding`() {
        val f = fixture()
        f.users.save(User("p-b7b", "13800000004", "h").also { it.status = "active" })
        f.devices.save(com.padguard.server.domain.Device("dev-b7b").also { it.userId = "p-b7b" })
        // A-ops 默认无 devices:unbind 权限
        val ex = assertThrows(BizException::class.java) { f.svc.unbindDevice("A-ops", "dev-b7b") }
        assertEquals(ParentErr.FORBIDDEN, ex.code, "缺 devices:unbind 必须返回 FORBIDDEN")
        assertEquals("p-b7b", f.devices.table["dev-b7b"]!!.userId, "越权解绑不得清除归属")
    }

    // ----------------------------- B8：登录/列表返回有效权限（前端据此隐藏按钮） -----------------------------

    @Test
    fun `admin dto carries effective privileges derived from the role`() {
        val f = fixture()
        val list = f.svc.listAdmins("A-super")
        val superDto = list.first { it.id == "A-super" }
        assertTrue(superDto.privileges.isNotEmpty(), "管理员 DTO 必须携带有效权限，供前端隐藏按钮")
        assertEquals(true, superDto.privileges["parents"]?.contains("delete"), "超管应拥有 parents:delete")
        // 运营角色无财务退款权限：前端据此隐藏「退款」按钮
        val opsDto = list.first { it.id == "A-ops" }
        assertEquals(false, opsDto.privileges["billing"]?.contains("refund") == true, "运营不应拥有 billing:refund")
    }

    // ----------------------------- B1：设备列表真实数据（今日用量 + 区域） -----------------------------

    @Test
    fun `device list shows real region and today usage derived from usage session`() {
        val f = fixture()
        f.users.save(User("p-b1", "13800000007", "h").also { it.status = "active"; it.region = "上海"; it.nickname = "张家长" })
        f.devices.save(com.padguard.server.domain.Device("dev-b1").also { it.userId = "p-b1" })
        // 今日计时会话：起点 30 分钟前、额度 120 分钟、无休息扣除
        val now = System.currentTimeMillis()
        f.usageSessions.save(
            com.padguard.server.domain.UsageSession(
                "dev-b1", todayKey(now), startEpoch = now - 30 * 60_000L,
                quotaMinutes = 120, extendedMinutes = 0, restAccumulatedMs = 0
            )
        )
        val rows = f.svc.listDevices("A-super", null, null).items
        val row = rows.first { it.deviceId == "dev-b1" }
        assertEquals("上海", row.region, "设备区域应来自归属家长 region，而非恒为空串")
        assertTrue(row.todayUsageMinutes in 29..31, "今日用量应来自真实会话（约 30 分钟），而非恒为 0")
    }

    @Test
    fun `device list falls back to empty region and null usage when no session today`() {
        val f = fixture()
        f.users.save(User("p-b2", "13800000006", "h").also { it.status = "active" })
        f.devices.save(com.padguard.server.domain.Device("dev-b2").also { it.userId = "p-b2" })
        val rows = f.svc.listDevices("A-super", null, null).items
        val row = rows.first { it.deviceId == "dev-b2" }
        assertEquals("", row.region, "无区域时回退空串（前端对空串显示 —）")
        // null 而不是 0：DTO 明确约定 null = 今日尚未开始计时，前端据此显示「—」。
        // 填 0 会把"没有数据"显示成"今天一分钟都没用"，运营据此判断活跃度必然失真。
        assertNull(row.todayUsageMinutes, "无今日会话时应为 null（前端显示 —），不能是 0")
    }

    // ----------------------------- B3/B4：列表分页与带参筛选 -----------------------------

    @Test
    fun `parents list is paginated with correct total and totalPages`() {
        val f = fixture()
        repeat(25) { i -> f.users.save(User("p-%02d".format(i), "1380000%03d".format(i), "h").also { it.status = "active" }) }
        val p1 = f.svc.listParents("A-super", null, null, null, 1, 10)
        assertEquals(25, p1.total, "total 应为已种入的家长总数")
        assertEquals(3, p1.totalPages, "25 条 / 每页 10 → 共 3 页")
        assertEquals(1, p1.page)
        assertEquals(10, p1.size)
        assertEquals(10, p1.items.size, "第 1 页应返回 10 条")

        val p2 = f.svc.listParents("A-super", null, null, null, 2, 10)
        assertEquals(2, p2.page)
        assertEquals(10, p2.items.size, "第 2 页应返回 10 条")

        val p3 = f.svc.listParents("A-super", null, null, null, 3, 10)
        assertEquals(3, p3.page)
        assertEquals(5, p3.items.size, "第 3 页（末页）应返回剩余 5 条")

        // 跨页不重复：三页 id 并集 = 25 且彼此无交集，分页切片不能丢数据也不能重复
        val ids = (p1.items + p2.items + p3.items).map { it.id }.toSet()
        assertEquals(25, ids.size, "三页去重后应为 25 个不同家长")
    }

    @Test
    fun `parents list filters by status and paginates the filtered subset`() {
        val f = fixture()
        repeat(25) { i ->
            val status = if (i < 10) "suspended" else "active"
            f.users.save(User("p-%02d".format(i), "1380000%03d".format(i), "h").also { it.status = status })
        }
        // 筛选已冻结（10 条），每页 4 条 → 3 页（4+4+2）
        val p1 = f.svc.listParents("A-super", null, "suspended", null, 1, 4)
        assertEquals(10, p1.total, "按 status=suspended 筛选后应只剩 10 条")
        assertEquals(3, p1.totalPages, "10 / 4 → 3 页")
        assertEquals(4, p1.items.size, "首页 4 条")

        val p3 = f.svc.listParents("A-super", null, "suspended", null, 3, 4)
        assertEquals(2, p3.items.size, "末页 2 条")
    }

    @Test
    fun `parents list keyword matches id or phone and returns single page subset`() {
        val f = fixture()
        f.users.save(User("unique-target", "13900008888", "h").also { it.status = "active" })
        f.users.save(User("other-1", "13800000001", "h").also { it.status = "active" })
        val byId = f.svc.listParents("A-super", "unique-target", null, null, 1, 20)
        assertEquals(1, byId.total, "按 id 关键字应精确命中 1 条")
        assertEquals("unique-target", byId.items.first().id)

        val byPhone = f.svc.listParents("A-super", "13900008888", null, null, 1, 20)
        assertEquals(1, byPhone.total, "按手机号关键字应精确命中 1 条")
        assertEquals("unique-target", byPhone.items.first().id)
    }

    @Test
    fun `paginate helper clamps page and caps size at 200`() {
        val all = (1..500).map { "i-$it" }
        // size 超过 200 应被压回 200
        val capped = paginate(all, 1, 500)
        assertEquals(200, capped.size, "单页上限 200")
        assertEquals(200, capped.items.size, "被截断到 200 条")
        assertEquals(3, capped.totalPages, "500 / 200 → 3 页")
        // page 越界（大于总页数）→ 返回空页但保留 total 信息
        val over = paginate(all, 99, 20)
        assertEquals(500, over.total, "越界页仍上报真实总数")
        assertEquals(99, over.page)
        assertEquals(0, over.items.size, "越界页内容为空")
        // page < 1 被钳回 1
        val neg = paginate(all, 0, 20)
        assertEquals(1, neg.page, "page<1 钳回 1")
        assertEquals(20, neg.items.size, "钳回后仍正常切片")
        // 空集合：totalPages 为 1 而非 0，避免前端除零
        val empty = paginate(emptyList<String>(), 1, 20)
        assertEquals(0, empty.total)
        assertEquals(1, empty.totalPages, "空集合 totalPages 应为 1")
    }
}
