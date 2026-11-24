package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.dto.ServerMetrics
import com.padguard.server.dto.ServerProfileDto
import com.padguard.server.observability.RequestMetrics
import com.padguard.server.observability.RuntimeLogRing
import com.padguard.server.security.AdminPermissionService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement
import javax.sql.DataSource

/**
 * `AdminServerMonitorService` 是后台「服务器监控」页的唯一读入口，此前**零单测**。
 *
 * 信任关键点：两个方法都必须先过 `server:read` 闸门（无权限角色连 JVM 指标 / 实时日志都不该读到）；
 * 五项请求指标取自 [RequestMetrics] 的 60 秒滑动窗口真值（历史曾硬编码假数字）；
 * 主机地址未配置时必须显示「未配置」而不是拼出 `":8090"` 半截地址误导运维。
 *
 * 不加载 Spring 上下文，5 个依赖全部 Mockito 桩。
 */
class AdminServerMonitorServiceTest {

    private lateinit var perm: AdminPermissionService
    private lateinit var metrics: RequestMetrics
    private lateinit var logs: RuntimeLogRing
    private lateinit var dataSource: DataSource
    private lateinit var profile: ServerProfileService
    private lateinit var svc: AdminServerMonitorService

    private val adminId = "A1"
    private val logLines = listOf(
        ServerMetrics.LogLine("2026-01-01 10:00:00", "INFO", "started"),
        ServerMetrics.LogLine("2026-01-01 10:00:01", "WARN", "slow query")
    )

    @BeforeEach
    fun setUp() {
        perm = mock(AdminPermissionService::class.java)
        metrics = mock(RequestMetrics::class.java)
        logs = mock(RuntimeLogRing::class.java)
        dataSource = mock(DataSource::class.java)
        profile = mock(ServerProfileService::class.java)

        `when`(metrics.requestsPerMin()).thenReturn(42)
        `when`(metrics.qpmHistory()).thenReturn(listOf(1, 2, 3))
        `when`(metrics.errorRate()).thenReturn("1.25%")
        `when`(metrics.avgLatencyMs()).thenReturn(88L)
        `when`(logs.recent()).thenReturn(logLines)
        `when`(profile.getServerProfile()).thenReturn(ServerProfileDto(publicHost = "192.168.1.12", port = 8090))

        // dbUp 成功链路：DataSource -> Connection -> Statement -> ResultSet
        val conn = mock(Connection::class.java)
        val st = mock(Statement::class.java)
        val rs = mock(ResultSet::class.java)
        `when`(dataSource.connection).thenReturn(conn)
        `when`(conn.createStatement()).thenReturn(st)
        `when`(st.executeQuery(anyString())).thenReturn(rs)
        `when`(rs.next()).thenReturn(true)

        svc = AdminServerMonitorService(perm, metrics, logs, dataSource, profile)
    }

    private fun deny() {
        doThrow(BizException(ParentErr.FORBIDDEN, "无权限", Audience.PARENT))
            .`when`(perm).require(adminId, "server", "read")
    }

    // ---------- RBAC 闸门 ----------

    @Test
    fun `serverMetrics 闸门拒绝时不读任何指标或日志`() {
        deny()
        val ex = assertThrows(BizException::class.java) { svc.serverMetrics(adminId) }
        assertEquals(ParentErr.FORBIDDEN, ex.code)
        verifyNoInteractions(metrics, logs, dataSource, profile)
    }

    @Test
    fun `serverLogs 闸门拒绝时不读日志环`() {
        deny()
        assertThrows(BizException::class.java) { svc.serverLogs(adminId) }
        verify(logs, never()).recent()
    }

    @Test
    fun `两个入口都要求 server read`() {
        svc.serverMetrics(adminId)
        svc.serverLogs(adminId)
        verify(perm, org.mockito.Mockito.times(2)).require(adminId, "server", "read")
    }

    // ---------- 真值透传 ----------

    @Test
    fun `serverMetrics 五项请求指标取自 RequestMetrics 真值`() {
        val r = svc.serverMetrics(adminId)
        assertEquals(42, r.requestsPerMin)
        assertEquals(listOf(1, 2, 3), r.qpmHistory)
        assertEquals("1.25%", r.errorRate)
        assertEquals(88L, r.avgLatencyMs)
    }

    @Test
    fun `serverMetrics 数据库可用时 status 与 db 均为 UP`() {
        val r = svc.serverMetrics(adminId)
        assertEquals("UP", r.status)
        assertEquals("UP", r.db)
        assertEquals("UP", r.instances.single().status)
    }

    @Test
    fun `serverMetrics 数据库不可用时 status 与 db 均为 DOWN（不抛异常）`() {
        `when`(dataSource.connection).thenThrow(RuntimeException("connection refused"))
        val r = svc.serverMetrics(adminId)
        assertEquals("DOWN", r.status)
        assertEquals("DOWN", r.db)
        assertEquals("DOWN", r.instances.single().status)
    }

    @Test
    fun `serverMetrics 主机地址取自配置且带端口`() {
        val r = svc.serverMetrics(adminId)
        val inst = r.instances.single()
        assertEquals("padguard-server", inst.name)
        assertEquals("192.168.1.12:8090", inst.host)
    }

    @Test
    fun `serverMetrics 未配置主机回落未配置而不是半截地址`() {
        `when`(profile.getServerProfile()).thenReturn(ServerProfileDto(publicHost = ""))
        val r = svc.serverMetrics(adminId)
        assertEquals("未配置", r.instances.single().host)
        assertTrue(!r.instances.single().host.startsWith(":"), "禁止拼出 ':8090' 这类会被误读成解析失败的地址")
    }

    @Test
    fun `serverMetrics 读取配置异常被吞掉并回落未配置`() {
        `when`(profile.getServerProfile()).thenThrow(RuntimeException("settings table missing"))
        val r = svc.serverMetrics(adminId)
        assertEquals("未配置", r.instances.single().host)
    }

    @Test
    fun `serverMetrics 非 Hikari 数据源明确说明未暴露池指标`() {
        val r = svc.serverMetrics(adminId)
        assertTrue(r.dbConnections.contains("未暴露池指标"), "不得用占位符 '—' 伪装成取值成功")
    }

    @Test
    fun `serverMetrics 实时日志透传日志环且 startedAt 非空`() {
        val r = svc.serverMetrics(adminId)
        assertEquals(logLines, r.logs)
        assertTrue(r.startedAt.isNotBlank())
        assertTrue(r.uptimeMs >= 0)
    }

    @Test
    fun `serverLogs 只透传日志不读请求指标`() {
        val r = svc.serverLogs(adminId)
        assertEquals(logLines, r)
        verifyNoInteractions(metrics)
    }
}
