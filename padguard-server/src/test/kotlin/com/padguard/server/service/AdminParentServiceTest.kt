package com.padguard.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.Device
import com.padguard.server.domain.OrderStatus
import com.padguard.server.domain.PaymentOrder
import com.padguard.server.domain.Plan
import com.padguard.server.domain.SystemSetting
import com.padguard.server.domain.User
import com.padguard.server.dto.AdminCreateParentRequest
import com.padguard.server.dto.AdminManualSubscriptionRequest
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.ParentErasureDao
import com.padguard.server.repository.PaymentOrderRepository
import com.padguard.server.repository.SubscriptionRepository
import com.padguard.server.repository.SystemSettingRepository
import com.padguard.server.repository.UserRepository
import com.padguard.server.security.AdminPermissionService
import com.padguard.server.security.Passwords
import com.padguard.server.ws.WebSocketPushService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.Optional

/**
 * [AdminParentService] 的高风险路径。
 *
 * `AdminServiceTest` 用 fake 覆盖了「冻结/新建/改资料/删无设备家长」的主路径，
 * 这里只补它没有断言、且出错代价最大的不变量：
 *
 * - 冻结与解冻必须共用 `parents:suspend`（否则低权限角色能撤销高权限处置）；
 * - 重置密码必须 `token_version + 1` 并踢 WS，邮件只在事务提交后发；
 * - 普通删除有「设备 / 已支付订单 / 历史外键引用」三道护栏；
 * - 注销是双闸（delete + super + 手机号二次确认），且**先 purge 每台设备历史、再 dao.erase**，
 *   purge 三次全败必须整体失败，绝不返回「已注销」却留下儿童隐私；
 * - 创建并开通不能静默吞掉 plan，且必须过 `billing:manual`。
 *
 * 三个 `@Autowired(required=false)` 字段用反射注入，避免改动 12 参构造波及其它测试装配。
 */
class AdminParentServiceTest {

    private lateinit var users: UserRepository
    private lateinit var subs: SubscriptionRepository
    private lateinit var devices: DeviceRepository
    private lateinit var perm: AdminPermissionService
    private lateinit var billing: BillingService
    private lateinit var mail: MailService
    private lateinit var adminBilling: AdminBillingService
    private lateinit var orders: PaymentOrderRepository
    private lateinit var audit: AuditService
    private lateinit var settings: SystemSettingRepository
    private lateinit var usageSessions: UsageSessionService
    private lateinit var ws: WebSocketPushService
    private lateinit var dao: ParentErasureDao
    private lateinit var purge: DeviceDataPurgeService
    private lateinit var svc: AdminParentService

    private fun clearSync() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    @BeforeEach
    fun setUp() {
        clearSync()
        users = mock(UserRepository::class.java)
        subs = mock(SubscriptionRepository::class.java)
        devices = mock(DeviceRepository::class.java)
        perm = mock(AdminPermissionService::class.java)
        billing = mock(BillingService::class.java)
        mail = mock(MailService::class.java)
        adminBilling = mock(AdminBillingService::class.java)
        orders = mock(PaymentOrderRepository::class.java)
        audit = mock(AuditService::class.java)
        settings = mock(SystemSettingRepository::class.java)
        usageSessions = mock(UsageSessionService::class.java)
        ws = mock(WebSocketPushService::class.java)
        dao = mock(ParentErasureDao::class.java)
        purge = mock(DeviceDataPurgeService::class.java)
        svc = AdminParentService(
            users, subs, devices, perm, billing, mail, adminBilling,
            orders, audit, settings, ObjectMapper(), usageSessions
        )
        `when`(settings.findById(anyString())).thenReturn(Optional.empty<SystemSetting>())
    }

    @AfterEach
    fun tearDown() {
        clearSync()
    }

    private fun inject(name: String, value: Any?) {
        val f = AdminParentService::class.java.getDeclaredField(name)
        f.isAccessible = true
        f.set(svc, value)
    }

    private fun parent(
        id: String = "U1",
        phone: String = "13800000001",
        email: String? = "a@b.com"
    ) = User(
        id = id, phone = phone, passwordHash = "old-hash", nickname = "小张",
        email = email, region = "上海", remark = "VIP", status = "active", createdAt = 1L
    )

    private fun audits() = mockingDetails(audit).invocations.filter { it.method.name == "record" }

    private fun paidOrder() = PaymentOrder(
        id = "P1", orderNo = "O1", userId = "U1", plan = Plan.MONTHLY.key,
        amountFen = 3800, status = OrderStatus.PAID.name, createdAt = 1L
    )

    // ==================== 权限闸门 ====================

    @Test
    fun `listParents 需要 parents read 且拒绝时不得查库`() {
        doThrow(BizException(ParentErr.FORBIDDEN, "无权限", Audience.PARENT))
            .`when`(perm).require("A1", "parents", "read")
        assertThrows(BizException::class.java) { svc.listParents("A1", null, null, null) }
        verifyNoInteractions(users)
    }

    @Test
    fun `parentDetail 需要 parents read`() {
        `when`(users.findById("U1")).thenReturn(Optional.empty<User>())
        assertNull(svc.parentDetail("A1", "U1"))
        verify(perm).require("A1", "parents", "read")
    }

    @Test
    fun `冻结与解冻共用 parents suspend 权限点 不出现权限倒挂`() {
        `when`(users.findByIdForUpdate("U1")).thenReturn(parent())
        svc.suspendParent("A1", "U1")
        svc.activateParent("A1", "U1")
        val calls = mockingDetails(perm).invocations.filter { it.method.name == "require" }
        assertEquals(2, calls.size)
        assertTrue(calls.all { it.arguments[1] == "parents" && it.arguments[2] == "suspend" })
    }

    @Test
    fun `suspendParent 必须主动踢 WS 长连接且不动 token version`() {
        val u = parent().also { it.tokenVersion = 7L }
        `when`(users.findByIdForUpdate("U1")).thenReturn(u)
        inject("webSocketPush", ws)
        svc.suspendParent("A1", "U1")
        assertEquals("suspended", u.status)
        assertEquals(7L, u.tokenVersion)
        verify(ws).revokeUser("U1")
        assertEquals(AdminAudit.PARENTS_SUSPEND, audits()[0].arguments[2])
    }

    @Test
    fun `resetParentPassword 需要 reset_pwd 权限`() {
        `when`(users.findByIdForUpdate("U1")).thenReturn(parent())
        svc.resetParentPassword("A1", "U1")
        verify(perm).require("A1", "parents", "reset_pwd")
    }

    @Test
    fun `createParent 需要 parents create 权限`() {
        svc.createParent("A1", AdminCreateParentRequest(phone = "13900000001"))
        verify(perm).require("A1", "parents", "create")
    }

    @Test
    fun `updateParent 需要 parents write 权限`() {
        `when`(users.findByIdForUpdate("U1")).thenReturn(parent())
        svc.updateParent("A1", "U1", com.padguard.server.dto.AdminUpdateParentRequest(remark = "新备注"))
        verify(perm).require("A1", "parents", "write")
    }

    @Test
    fun `deleteParent 需要 parents delete 权限`() {
        `when`(users.findById("U1")).thenReturn(Optional.of(parent()))
        svc.deleteParent("A1", "U1")
        verify(perm).require("A1", "parents", "delete")
    }

    // ==================== 重置密码：吊销 / 踢线 / 邮件时机 ====================

    @Test
    fun `resetParentPassword 递增 token version 换密码并强制全部会话下线`() {
        val u = parent().also { it.tokenVersion = 3L }
        `when`(users.findByIdForUpdate("U1")).thenReturn(u)
        `when`(mail.sendPasswordReset(anyString(), anyString(), anyString(), anyString())).thenReturn(true)
        inject("webSocketPush", ws)
        val r = svc.resetParentPassword("A1", "U1")
        assertEquals(4L, u.tokenVersion)
        val newPwd = r["newPwd"] as String
        assertTrue(Passwords.matches(newPwd, u.passwordHash))
        assertFalse(u.passwordHash == "old-hash")
        verify(ws).revokeUser("U1")
        assertTrue((audits()[0].arguments[5] as String).contains("已强制全部会话下线"))
    }

    @Test
    fun `resetParentPassword 在事务内不发邮件 提交后才发`() {
        `when`(users.findByIdForUpdate("U1")).thenReturn(parent())
        `when`(mail.sendPasswordReset(anyString(), anyString(), anyString(), anyString())).thenReturn(true)
        TransactionSynchronizationManager.initSynchronization()
        val r = svc.resetParentPassword("A1", "U1")
        assertEquals(false, r["emailed"])
        verify(mail, never()).sendPasswordReset(anyString(), anyString(), anyString(), anyString())
        val syncs = TransactionSynchronizationManager.getSynchronizations()
        assertEquals(1, syncs.size)
        syncs.forEach { it.afterCommit() }
        verify(mail).sendPasswordReset(anyString(), anyString(), anyString(), anyString())
    }

    @Test
    fun `resetParentPassword 脱离事务上下文时退回内联发送`() {
        `when`(users.findByIdForUpdate("U1")).thenReturn(parent())
        `when`(mail.sendPasswordReset(anyString(), anyString(), anyString(), anyString())).thenReturn(true)
        val r = svc.resetParentPassword("A1", "U1")
        assertEquals(true, r["emailed"])
        verify(mail).sendPasswordReset(anyString(), anyString(), anyString(), anyString())
    }

    @Test
    fun `resetParentPassword 家长不存在时报错且不发邮件`() {
        assertThrows(BizException::class.java) { svc.resetParentPassword("A1", "NOPE") }
        verifyNoInteractions(mail)
    }

    // ==================== 普通删除护栏 ====================

    @Test
    fun `deleteParent 存在已支付未退款订单时拒绝删除`() {
        `when`(users.findById("U1")).thenReturn(Optional.of(parent()))
        `when`(orders.findByUserIdAndStatus("U1", OrderStatus.PAID.name)).thenReturn(listOf(paidOrder()))
        val ex = assertThrows(BizException::class.java) { svc.deleteParent("A1", "U1") }
        assertTrue(ex.message.contains("1 笔已支付未退款"))
        verify(users, never()).delete(any(User::class.java) ?: User())
        assertEquals(0, audits().size)
    }

    @Test
    fun `deleteParent 存在历史外键引用时引导走注销入口`() {
        `when`(users.findById("U1")).thenReturn(Optional.of(parent()))
        `when`(orders.findByUserIdAndStatus("U1", OrderStatus.PAID.name)).thenReturn(emptyList())
        `when`(dao.residualReferences("U1")).thenReturn(listOf("alerts", "unlock_tickets"))
        inject("erasureDao", dao)
        val ex = assertThrows(BizException::class.java) { svc.deleteParent("A1", "U1") }
        assertTrue(ex.message.contains("注销账号"))
        verify(users, never()).delete(any(User::class.java) ?: User())
    }

    @Test
    fun `deleteParent 三道护栏全清时删除订阅与用户并留痕`() {
        val u = parent()
        `when`(users.findById("U1")).thenReturn(Optional.of(u))
        `when`(orders.findByUserIdAndStatus("U1", OrderStatus.PAID.name)).thenReturn(emptyList())
        inject("erasureDao", dao)
        `when`(dao.residualReferences("U1")).thenReturn(emptyList())
        val r = svc.deleteParent("A1", "U1")
        assertEquals(true, r["ok"])
        verify(users).delete(u)
        assertEquals(AdminAudit.PARENTS_DELETE, audits()[0].arguments[2])
    }

    // ==================== 注销：双闸 / 清理顺序 / 不假成功 ====================

    private fun stubEraseHappyPath() {
        inject("erasureDao", dao)
        inject("deviceDataPurge", purge)
        inject("webSocketPush", ws)
        `when`(users.findByIdForUpdate("U1")).thenReturn(parent())
        `when`(dao.findDeviceIds("U1")).thenReturn(listOf("D1", "D2"))
        `when`(purge.purge(anyString(), anyString())).thenReturn(mapOf("screenshots" to 5))
        `when`(dao.erase(anyString(), anyString(), any() ?: emptyList<String>()))
            .thenReturn(
                com.padguard.server.repository.ErasureOutcome(
                    counts = mapOf("devices" to 2, "payment_orders" to 3),
                    deviceIds = listOf("D1", "D2")
                )
            )
    }

    @Test
    fun `eraseParent 双闸要求 parents delete 与 super`() {
        stubEraseHappyPath()
        svc.eraseParent("A1", "U1", "13800000001")
        verify(perm).require("A1", "parents", "delete")
        verify(perm).requireSuper("A1")
    }

    @Test
    fun `eraseParent super 校验失败时不得读取用户行`() {
        doThrow(BizException(ParentErr.FORBIDDEN, "非超级管理员", Audience.PARENT))
            .`when`(perm).requireSuper("A1")
        inject("erasureDao", dao)
        assertThrows(BizException::class.java) { svc.eraseParent("A1", "U1", "13800000001") }
        verify(users, never()).findByIdForUpdate(anyString())
    }

    @Test
    fun `eraseParent 确认手机号不一致时拒绝且不做任何清理`() {
        stubEraseHappyPath()
        val ex = assertThrows(BizException::class.java) { svc.eraseParent("A1", "U1", "13900000000") }
        assertTrue(ex.message.contains("确认手机号"))
        verifyNoInteractions(purge)
        assertEquals(0, mockingDetails(dao).invocations.count { it.method.name == "erase" })
        verify(users, never()).delete(any(User::class.java) ?: User())
    }

    @Test
    fun `eraseParent 必须先逐台 purge 再 dao erase 最后删用户`() {
        stubEraseHappyPath()
        val r = svc.eraseParent("A1", "U1", " 13800000001 ")
        val io = inOrder(purge, dao, users, ws)
        io.verify(purge).purge("D1", "ACCOUNT_ERASE")
        io.verify(purge).purge("D2", "ACCOUNT_ERASE")
        io.verify(dao).erase(anyString(), anyString(), any() ?: emptyList<String>())
        io.verify(users).delete(any(User::class.java) ?: User())
        io.verify(ws).revokeUser("U1")
        assertEquals(10, r["purgedDeviceRows"])
        assertEquals(2, r["revokedDevices"])
    }

    @Test
    fun `eraseParent purge 三次全败必须抛错且账号未删`() {
        stubEraseHappyPath()
        `when`(purge.purge(anyString(), anyString())).thenThrow(RuntimeException("db down"))
        val ex = assertThrows(BizException::class.java) { svc.eraseParent("A1", "U1", "13800000001") }
        assertTrue(ex.message.contains("账号未删除"))
        assertEquals(3, mockingDetails(purge).invocations.count { it.method.name == "purge" })
        assertEquals(0, mockingDetails(dao).invocations.count { it.method.name == "erase" })
        verify(users, never()).delete(any(User::class.java) ?: User())
        assertEquals(0, audits().size)
    }

    @Test
    fun `eraseParent 匿名键稳定且审计手机号脱敏`() {
        stubEraseHappyPath()
        val r1 = svc.eraseParent("A1", "U1", "13800000001")
        val key1 = r1["anonKey"] as String
        assertTrue(key1.startsWith("erased_"))
        assertEquals(23, key1.length)
        val r2 = svc.eraseParent("A1", "U1", "13800000001")
        assertEquals(key1, r2["anonKey"])
        val detail = audits().last().arguments[5] as String
        assertFalse(detail.contains("13800000001"))
        assertTrue(detail.contains("138****0001"))
        assertEquals(AdminAudit.PARENTS_ERASE, audits().last().arguments[2])
    }

    @Test
    fun `eraseParent 清理器缺失时明确报错而不是静默跳过`() {
        `when`(users.findByIdForUpdate("U1")).thenReturn(parent())
        assertThrows(BizException::class.java) { svc.eraseParent("A1", "U1", "13800000001") }
        inject("erasureDao", dao)
        `when`(dao.findDeviceIds("U1")).thenReturn(listOf("D1"))
        assertThrows(BizException::class.java) { svc.eraseParent("A1", "U1", "13800000001") }
        verify(users, never()).delete(any(User::class.java) ?: User())
    }

    // ==================== 创建并开通 / 列表与详情 ====================

    @Test
    fun `createParent 手机号重复时拒绝`() {
        `when`(users.existsByPhone("13800000001")).thenReturn(true)
        assertThrows(BizException::class.java) {
            svc.createParent("A1", AdminCreateParentRequest(phone = "13800000001"))
        }
        verify(users, never()).save(any(User::class.java) ?: User())
    }

    @Test
    fun `createParent 带套餐时必须过 billing manual 并按套餐月数开通`() {
        val r = svc.createParent("A1", AdminCreateParentRequest(phone = "13900000001", plan = "YEARLY"))
        verify(perm).require("A1", "billing", "manual")
        val calls = mockingDetails(adminBilling).invocations.filter { it.method.name == "manualActivate" }
        assertEquals(1, calls.size)
        val req = calls[0].arguments[1] as AdminManualSubscriptionRequest
        assertEquals("13900000001", req.parentPhone)
        assertEquals("YEARLY", req.plan)
        assertEquals(360, req.days)
        assertTrue(r.containsKey("plan"))
    }

    @Test
    fun `createParent 永久会员按 36500 天开通`() {
        svc.createParent("A1", AdminCreateParentRequest(phone = "13900000001", plan = "PERMANENT"))
        val req = mockingDetails(adminBilling).invocations
            .first { it.method.name == "manualActivate" }.arguments[1] as AdminManualSubscriptionRequest
        assertEquals(36_500, req.days)
    }

    @Test
    fun `createParent 免费与试用套餐不触发手动开通`() {
        svc.createParent("A1", AdminCreateParentRequest(phone = "13900000001", plan = "FREE"))
        svc.createParent("A1", AdminCreateParentRequest(phone = "13900000002", plan = "TRIAL"))
        assertEquals(0, mockingDetails(adminBilling).invocations.count { it.method.name == "manualActivate" })
    }

    @Test
    fun `createParent 非法套餐被拒绝`() {
        val ex = assertThrows(BizException::class.java) {
            svc.createParent("A1", AdminCreateParentRequest(phone = "13900000001", plan = "GOLD"))
        }
        assertTrue(ex.message.contains("套餐不存在"))
    }

    @Test
    fun `listParents 过滤与分页下推数据库并只为当前页批量补数据`() {
        val u1 = parent("U1")
        val u2 = parent("U2", phone = "13800000002")
        `when`(users.searchParents(anyString(), anyString(), anyString(), anyString(),
            any(Pageable::class.java) ?: PageRequest.of(0, 1)))
            .thenReturn(PageImpl(listOf(u1, u2), PageRequest.of(0, 20), 2))
        val r = svc.listParents("A1", "  张 ", " ACTIVE ", " monthly ", page = 0, size = 9999)
        assertEquals(1, r.page)
        assertEquals(200, r.size)
        assertEquals(2, r.total)
        val call = mockingDetails(users).invocations.first { it.method.name == "searchParents" }
        assertEquals("ACTIVE", call.arguments[0])
        assertEquals("张", call.arguments[1])
        assertEquals("%张%", call.arguments[2])
        assertEquals("monthly", call.arguments[3])
        assertEquals(0, (call.arguments[4] as Pageable).pageNumber)
        assertEquals(1, mockingDetails(subs).invocations.count { it.method.name == "findByUserIdIn" })
        assertEquals(1, mockingDetails(devices).invocations.count { it.method.name == "countGroupByUserIds" })
    }

    @Test
    fun `parentDetail 今日未开始计时的设备必须保留 null 而不是填 0`() {
        `when`(users.findById("U1")).thenReturn(Optional.of(parent()))
        `when`(devices.findByUserId("U1")).thenReturn(listOf(Device(id = "D1", name = "平板")))
        `when`(usageSessions.usedMinutesBatch(any() ?: emptyList<String>())).thenReturn(emptyMap())
        val d = svc.parentDetail("A1", "U1")
        assertEquals(1, d!!.devices.size)
        assertNull(d.devices[0].todayUsageMinutes)
        assertFalse(d.devices[0].online)
    }
}
