package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.AdminRoles
import com.padguard.server.domain.Alert
import com.padguard.server.domain.AuditLog
import com.padguard.server.domain.User
import com.padguard.server.repository.AdminRepository
import com.padguard.server.repository.AlertRepository
import com.padguard.server.repository.AuditLogRepository
import com.padguard.server.repository.UserRepository
import com.padguard.server.security.AdminPermissionService
import com.padguard.server.ws.WebSocketPushService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import java.util.Optional

/**
 * [AdminAuditService] 的读闸门、截断语义与告警状态映射。
 *
 * 与 `AdminServiceTest` 的分工：那边通过 AdminService 门面覆盖了 closeAlert 的成功/越权；
 * 本类覆盖 auditPage / auditAreas / alerts 这些同样信任关键、但零覆盖的读路径 ——
 * 尤其是「未知领域必须返回空，而不是退化成不过滤」和「截断必须如实回报」。
 */
class AdminAuditServiceTest {

    private lateinit var perm: AdminPermissionService
    private lateinit var admins: AdminRepository
    private lateinit var users: UserRepository
    private lateinit var auditLogs: AuditLogRepository
    private lateinit var alerts: AlertRepository
    private lateinit var auditService: AuditService
    private lateinit var webSocketPush: WebSocketPushService
    private lateinit var svc: AdminAuditService

    @BeforeEach
    fun setUp() {
        perm = mock(AdminPermissionService::class.java)
        admins = mock(AdminRepository::class.java)
        users = mock(UserRepository::class.java)
        auditLogs = mock(AuditLogRepository::class.java)
        alerts = mock(AlertRepository::class.java)
        auditService = mock(AuditService::class.java)
        webSocketPush = mock(WebSocketPushService::class.java)
        `when`(admins.findAllById(any())).thenReturn(emptyList())
        `when`(users.findAllById(any())).thenReturn(emptyList())
        svc = AdminAuditService(perm, admins, users, auditLogs, alerts, auditService, webSocketPush)
    }

    private fun row(
        id: String = "al1", action: String = AdminAudit.PARENTS_SUSPEND,
        userId: String? = null, result: String = "SUCCESS"
    ) = AuditLog(
        id = id, userId = userId, action = action, targetId = "T-1",
        result = result, ip = "127.0.0.1", createdAt = 1700000000000L
    )

    private fun stubPage(content: List<AuditLog>, total: Long, pageSize: Int = 200) {
        // PageImpl 在 offset+pageSize > total 时会把 total 修正成 content.size，
        // 所以桩的 pageable 必须与被测 limit 同口径，否则 total 断言失真。
        val pageable = PageRequest.of(0, pageSize)
        `when`(auditLogs.findAllByOrderByCreatedAtDesc(any(Pageable::class.java) ?: PageRequest.of(0, 1)))
            .thenReturn(PageImpl(content, pageable, total))
        `when`(
            auditLogs.findByActionInOrderByCreatedAtDesc(
                (any(Collection::class.java) as? Collection<String>) ?: emptyList(),
                any(Pageable::class.java) ?: PageRequest.of(0, 1)
            )
        ).thenReturn(PageImpl(content, pageable, total))
    }

    // ----------------------------- auditAreas -----------------------------

    @Test
    fun `auditAreas 由 AREA_DEFS 单向派生且包含 audit 领域`() {
        val areas = svc.auditAreas("a1")

        assertEquals(AdminRoles.AREA_DEFS.size, areas.size)
        assertEquals(AdminRoles.AREA_DEFS.map { it.key }, areas.map { it["key"] })
        assertEquals(AdminRoles.AREA_DEFS.map { it.name }, areas.map { it["name"] })
        assertTrue(areas.any { it["key"] == "audit" }, "早期前端硬编码漏了 audit，运营根本选不到这一项")
        verify(perm).require("a1", "audit", "read")
    }

    @Test
    fun `auditAreas 无 audit read 权限被拒`() {
        doThrow(BizException(ParentErr.FORBIDDEN, "无权限", Audience.PARENT))
            .`when`(perm).require("a1", "audit", "read")

        val ex = assertThrows(BizException::class.java) { svc.auditAreas("a1") }
        assertEquals(ParentErr.FORBIDDEN, ex.code)
    }

    // ----------------------------- auditPage -----------------------------

    @Test
    fun `auditPage 默认上限 200 并下推数据库分页`() {
        stubPage(listOf(row()), 1)
        val captor = ArgumentCaptor.forClass(Pageable::class.java)

        val page = svc.auditPage("a1", null)

        assertEquals(200, page.maxRows)
        verify(auditLogs).findAllByOrderByCreatedAtDesc(captor.capture() ?: PageRequest.of(0, 1))
        assertEquals(200, captor.value.pageSize, "分页必须下推 DB，不能 findAll 整表再 take")
        verify(perm).require("a1", "audit", "read")
    }

    @Test
    fun `auditPage limit 钳制在 1 到 5000 闭区间`() {
        stubPage(listOf(row()), 1)

        assertEquals(5000, svc.auditPage("a1", null, 99_999).maxRows)
        assertEquals(1, svc.auditPage("a1", null, 0).maxRows)
        assertEquals(1, svc.auditPage("a1", null, -7).maxRows)
        assertEquals(50, svc.auditPage("a1", null, 50).maxRows)
    }

    @Test
    fun `auditPage 未知领域如实返回空绝不退化成不过滤`() {
        val page = svc.auditPage("a1", "no_such_area")

        assertTrue(page.items.isEmpty())
        assertEquals(0, page.total)
        assertFalse(page.truncated)
        verify(auditLogs, never()).findAllByOrderByCreatedAtDesc(any(Pageable::class.java) ?: PageRequest.of(0, 1))
        verify(auditLogs, never()).findByActionInOrderByCreatedAtDesc(
            (any(Collection::class.java) as? Collection<String>) ?: emptyList(),
            any(Pageable::class.java) ?: PageRequest.of(0, 1)
        )
    }

    @Test
    fun `auditPage 领域过滤走 action 集合且按请求 limit 下推`() {
        stubPage(listOf(row()), 1)
        val captor = ArgumentCaptor.forClass(Pageable::class.java)

        svc.auditPage("a1", "parents", 7)

        verify(auditLogs).findByActionInOrderByCreatedAtDesc(
            (any(Collection::class.java) as? Collection<String>) ?: emptyList(),
            captor.capture() ?: PageRequest.of(0, 1)
        )
        assertEquals(7, captor.value.pageSize, "先按领域过滤再截断，取的是该领域最近 N 条")
        verify(auditLogs, never()).findAllByOrderByCreatedAtDesc(any(Pageable::class.java) ?: PageRequest.of(0, 1))
    }

    @Test
    fun `auditPage 如实回报截断前总数与截断标志`() {
        stubPage(listOf(row()), 10, pageSize = 1)

        val page = svc.auditPage("a1", null, 1)

        assertTrue(page.truncated)
        assertEquals(10, page.total)
        assertEquals(1, page.maxRows)
        assertEquals(1, page.items.size)
    }

    @Test
    fun `auditPage 未登记动作回落空领域且 result 小写化`() {
        stubPage(listOf(row(action = "SOME_NEW_ACTION", result = "FAILED")), 1)

        val item = svc.auditPage("a1", null).items.single()

        assertEquals("", item.area)
        assertEquals("SOME_NEW_ACTION", item.actionName)
        assertEquals("failed", item.result)
    }

    @Test
    fun `auditPage 只为当前页 actor 补显示名不整表读家长`() {
        stubPage(listOf(row(userId = "u1")), 1)
        `when`(users.findAllById(any())).thenReturn(listOf(User("u1", "13800000001", "h")))

        val item = svc.auditPage("a1", null).items.single()

        assertEquals("13800000001", item.admin)
        verify(users).findAllById(eq(listOf("u1")) ?: emptyList())
        verify(users, never()).findAll()
    }

    @Test
    fun `audit 只取条目不泄漏截断元信息之外的数据`() {
        stubPage(listOf(row(), row(id = "al2")), 2)
        assertEquals(2, svc.audit("a1", null).size)
    }

    // ----------------------------- alerts -----------------------------

    @Test
    fun `alerts level 映射 ERROR high WARN mid 其余 low`() {
        `when`(alerts.findTop500ByOrderByTriggeredAtDesc()).thenReturn(
            listOf(
                Alert(id = "a1", level = "ERROR", title = "t1"),
                Alert(id = "a2", level = "WARN", title = "t2"),
                Alert(id = "a3", level = "INFO", title = "t3"),
                Alert(id = "a4", level = "DEBUG", title = "t4")
            )
        )

        val list = svc.alerts("a1")

        assertEquals(listOf("high", "mid", "low", "low"), list.map { it.level })
        verify(perm).require("a1", "audit", "read")
    }

    @Test
    fun `alerts 状态映射覆盖全部终结与中间态`() {
        `when`(alerts.findTop500ByOrderByTriggeredAtDesc()).thenReturn(
            listOf(
                Alert(id = "a1", status = "RESOLVED", title = "t1"),
                Alert(id = "a2", status = "CLOSED", title = "t2"),
                Alert(id = "a3", status = "ACKNOWLEDGED", title = "t3"),
                Alert(id = "a4", status = "ACTIVE", title = "t4")
            )
        )

        val list = svc.alerts("a1")

        assertEquals(listOf("closed", "closed", "acknowledged", "open"), list.map { it.status })
    }

    @Test
    fun `alerts 只补当前批告警涉及的家长`() {
        `when`(alerts.findTop500ByOrderByTriggeredAtDesc()).thenReturn(
            listOf(Alert(id = "a1", userId = "u1", title = "t1"))
        )
        `when`(users.findAllById(any())).thenReturn(listOf(User("u1", "13800000002", "h")))

        val list = svc.alerts("a1")

        assertEquals("13800000002", list.single().relatedParent)
        verify(users, never()).findAll()
    }

    // ----------------------------- closeAlert 补充 -----------------------------

    @Test
    fun `closeAlert 告警不存在抛参数错误且不写审计`() {
        `when`(alerts.findById("missing")).thenReturn(Optional.empty())

        val ex = assertThrows(BizException::class.java) { svc.closeAlert("a1", "missing") }

        assertEquals(ParentErr.PARAM_ERROR, ex.code)
        verify(alerts, never()).save(any(Alert::class.java))
    }
}
