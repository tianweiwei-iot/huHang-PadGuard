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
import com.padguard.server.dto.AdminUpdateParentRequest
import com.padguard.server.dto.AdminUpdateAdminRequest
import com.padguard.server.mqtt.MqttGateway
import com.padguard.server.repository.*
import com.padguard.server.security.AdminPermissionService
import com.padguard.server.security.JwtTokenProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Pageable
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.LinkedHashMap

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
    }

    private class FakeDeviceRepo : FakeJpaRepository<Device, String>(), DeviceRepository {
        override val table = LinkedHashMap<String, Device>()
        override fun idOf(e: Device) = e.id
        override fun findByUserId(userId: String) = table.values.filter { it.userId == userId }
        override fun countByUserId(userId: String) = table.values.count { it.userId == userId }.toLong()
        override fun countByOnlineStatus(onlineStatus: String) = table.values.count { it.onlineStatus == onlineStatus }.toLong()
        override fun findByDeviceSn(deviceSn: String) = table.values.filter { it.deviceSn == deviceSn }
        override fun findByLastActiveAtGreaterThan(since: Long) =
            table.values.filter { (it.lastActiveAt ?: 0L) > since }
    }

    private class FakeSubRepo : FakeJpaRepository<Subscription, String>(), SubscriptionRepository {
        override val table = LinkedHashMap<String, Subscription>()
        override fun idOf(e: Subscription) = e.id
        override fun findByUserId(userId: String): Subscription? = table.values.firstOrNull { it.userId == userId }
        override fun countByStatus(status: String) = table.values.count { it.status == status }.toLong()
        override fun findAllByOrderByExpiredAtDesc() = table.values.sortedByDescending { it.expiredAt }
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
    }

    private class FakeAdminRepo : FakeJpaRepository<Admin, String>(), AdminRepository {
        override val table = LinkedHashMap<String, Admin>()
        override fun idOf(e: Admin) = e.id
        override fun findByUsername(username: String): Admin? = table.values.firstOrNull { it.username == username }
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
    }

    private class FakeSystemSettingRepo : FakeJpaRepository<SystemSetting, String>(), SystemSettingRepository {
        override val table = LinkedHashMap<String, SystemSetting>()
        override fun idOf(e: SystemSetting) = e.key
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
        val overrides: FakeOverrideRepo
    )

    private fun fixture(): Fixture {
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

        val objectMapper = ObjectMapper()
        // 计费/指令等服务仅用于满足 AdminService 构造；本测试不触发它们的业务路径。
        // 计费验签器用示例支付放行模式（mockPayEnabled=true），避免影响 refund/manualActivate 等用例。
        val billing = BillingService(subs, orders, devices, 7L, 3, 3800, 9800, 29800, 3, 3, 5, 30L, true, com.padguard.server.security.ConfigurablePaymentVerifier(true, listOf()), FakeSystemSettingRepo(), ObjectMapper())
        val auditService = AuditService(auditLogs)
        // 真实 RBAC 闸门，与 AdminService 共享同一份 admin 仓储 —— 权限事实一致。
        val adminPerm = AdminPermissionService(admins, overrides, objectMapper)
        val mqtt = MqttGateway(null, objectMapper, "default")
        val sweeper = ExpiredCommandSweeper(commands, screenshots, objectMapper)
        val commandService = CommandService(
            devices, commands, mqtt, objectMapper, sweeper, auditService,
            com.padguard.server.security.DeviceOwnershipGuard(devices)
        )
        // 密钥仅用于构造；createAdmin 不签发令牌，不会真正用到。
        val jwt = JwtTokenProvider("padguard-unit-test-secret-0123456789abcdef", 120, 30)
        // 仅 dbUp() 用到；该方法以 runCatching 包裹，getConnection 抛错时回落 false，不影响写操作测试。
        val dataSource = DriverManagerDataSource()

        val svc = AdminService(
            users, devices, subs, orders,
            admins, overrides, alerts, auditLogs, settings,
            billing, commandService, jwt, adminPerm, auditService, objectMapper, dataSource
        )

        // 种入管理员：超管 / 运营 / 审计 / 财务 / 已停用超管
        admins.save(Admin("A-super", "super", "h", "超级管理员", null, "super", "active", false, null, 0))
        admins.save(Admin("A-ops", "ops", "h", "运营", null, "ops", "active", false, null, 0))
        admins.save(Admin("A-audit", "audit", "h", "审计", null, "audit", "active", false, null, 0))
        admins.save(Admin("A-finance", "finance", "h", "财务", null, "finance", "active", false, null, 0))
        admins.save(Admin("A-disabled", "disabled", "h", "停用超管", null, "super", "disabled", false, null, 0))

        return Fixture(svc, users, admins, orders, subs, devices, alerts, overrides)
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
        f.admins.save(com.padguard.server.domain.Admin("A-super2", "super2", "h", "二号超管", null, "super", "active", false, null, 0))
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
}
