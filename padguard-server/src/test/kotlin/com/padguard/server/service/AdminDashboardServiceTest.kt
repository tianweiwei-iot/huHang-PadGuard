package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.dto.AuditItem
import com.padguard.server.dto.DashboardTrends
import com.padguard.server.dto.KpiTrend
import com.padguard.server.dto.NameCount
import com.padguard.server.observability.RequestMetrics
import com.padguard.server.observability.RuntimeLogRing
import com.padguard.server.repository.AlertRepository
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.PaymentOrderRepository
import com.padguard.server.repository.SubscriptionRepository
import com.padguard.server.repository.UserRepository
import com.padguard.server.security.AdminPermissionService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import javax.sql.DataSource

/**
 * `AdminDashboardService` 的聚合正确性已由 `AdminAggregateIntegrationTest` /
 * `DashboardCacheIntegrationTest` 覆盖，此处只补**信任关键且零覆盖的权限边界**：
 *
 * - `adminPerm.require(server:read)` 必须先于 `dashboardCache.get`：缓存命中永远不能替代鉴权；
 * - 聚合快照与请求者无关，但 `billing:read` / `audit:read` 必须**按字段裁剪**，
 *   否则「只看服务器监控」的角色在首页能读到全部经营数据（同一数字两套口径 = 权限形同虚设）；
 * - `recent` 依赖当前管理员权限，**绝不进缓存**，必须每请求现算。
 *
 * 用 mock [DashboardCache] 直接返回预置快照，绕开 `buildAggregate` 的十余条聚合查询，
 * 让测试只聚焦权限判定（不加载 Spring 上下文）。
 */
class AdminDashboardServiceTest {

    private lateinit var users: UserRepository
    private lateinit var devices: DeviceRepository
    private lateinit var subs: SubscriptionRepository
    private lateinit var orders: PaymentOrderRepository
    private lateinit var alerts: AlertRepository
    private lateinit var perm: AdminPermissionService
    private lateinit var auditSvc: AdminAuditService
    private lateinit var metrics: RequestMetrics
    private lateinit var logs: RuntimeLogRing
    private lateinit var dataSource: DataSource
    private lateinit var profile: ServerProfileService
    private lateinit var cache: DashboardCache
    private lateinit var svc: AdminDashboardService

    private val adminId = "A1"

    private fun snapshot(): DashboardCache.Snapshot {
        val trend = KpiTrend(series = listOf(1, 2, 3), delta = 6, unit = "count", direction = "up", label = "近 7 天")
        return DashboardCache.Snapshot(
            parentsTotal = 10L, parentsActive = 8L, parentsSuspended = 2L,
            devicesTotal = 5L, devicesOnline = 3L,
            subsActive = 4L, subsTrial = 1L,
            monthRevenue = 12345, serverStatus = "UP",
            revenueSeries = listOf(100, 200, 300),
            planDist = listOf(NameCount("月卡", 3)),
            regionDist = listOf(NameCount("广东", 6)),
            alertsOpen = 2L,
            trends = DashboardTrends(trend, trend, trend, trend),
            builtAt = 0L
        )
    }

    private fun auditItems(n: Int): List<AuditItem> = (1..n).map {
        AuditItem("id$it", "2026-01-01", "管理员", "ACTION", "动作", "parents", "t", null, "SUCCESS")
    }

    @BeforeEach
    fun setUp() {
        users = mock(UserRepository::class.java)
        devices = mock(DeviceRepository::class.java)
        subs = mock(SubscriptionRepository::class.java)
        orders = mock(PaymentOrderRepository::class.java)
        alerts = mock(AlertRepository::class.java)
        perm = mock(AdminPermissionService::class.java)
        auditSvc = mock(AdminAuditService::class.java)
        metrics = mock(RequestMetrics::class.java)
        logs = mock(RuntimeLogRing::class.java)
        dataSource = mock(DataSource::class.java)
        profile = mock(ServerProfileService::class.java)
        cache = mock(DashboardCache::class.java)

        // 缓存命中：buildAggregate 完全不执行
        `when`(cache.get(anyLong())).thenReturn(snapshot())
        `when`(perm.can(adminId, "billing", "read")).thenReturn(true)
        `when`(perm.can(adminId, "audit", "read")).thenReturn(true)
        `when`(auditSvc.audit(adminId, null)).thenReturn(auditItems(8))

        svc = AdminDashboardService(
            users, devices, subs, orders, alerts, perm, auditSvc,
            metrics, logs, dataSource, profile, cache
        )
    }

    // ---------- 鉴权先于缓存 ----------

    @Test
    fun `鉴权必须先于缓存查询（InOrder）`() {
        svc.dashboard(adminId)
        val order = inOrder(perm, cache)
        order.verify(perm).require(adminId, "server", "read")
        order.verify(cache).get(anyLong())
    }

    @Test
    fun `server read 闸门拒绝时不查缓存也不读审计`() {
        doThrow(BizException(ParentErr.FORBIDDEN, "无权限", Audience.PARENT))
            .`when`(perm).require(adminId, "server", "read")
        val ex = assertThrows(BizException::class.java) { svc.dashboard(adminId) }
        assertEquals(ParentErr.FORBIDDEN, ex.code)
        verify(cache, never()).get(anyLong())
        verify(auditSvc, never()).audit(anyString(), any())
    }

    @Test
    fun `缓存命中不再重建聚合也不写回缓存`() {
        svc.dashboard(adminId)
        verifyNoInteractions(users, devices, subs, orders, alerts, metrics, logs, dataSource, profile)
        verify(cache, never()).put(any(DashboardCache.Snapshot::class.java) ?: snapshot())
    }

    // ---------- 按字段裁剪：billing ----------

    @Test
    fun `无 billing read 时营收数字归零且营收序列与套餐分布清空`() {
        `when`(perm.can(adminId, "billing", "read")).thenReturn(false)
        val r = svc.dashboard(adminId)
        assertEquals(0, r.kpis.monthRevenue, "月营收必须归零，不能把经营数字透给只看监控的角色")
        assertTrue(r.revenueSeries.isEmpty())
        assertTrue(r.planDist.isEmpty())
    }

    @Test
    fun `无 billing read 时非经营字段仍完整透出`() {
        `when`(perm.can(adminId, "billing", "read")).thenReturn(false)
        val r = svc.dashboard(adminId)
        assertEquals(10L, r.kpis.parentsTotal)
        assertEquals(5L, r.kpis.devicesTotal)
        assertEquals(3L, r.kpis.devicesOnline)
        assertEquals("UP", r.kpis.serverStatus)
        assertEquals(2L, r.alertsOpen)
        assertEquals(listOf(NameCount("广东", 6)), r.regionDist)
        assertEquals(snapshot().trends, r.trends)
    }

    @Test
    fun `有 billing read 时营收与套餐分布原样透出且不重复查库`() {
        val r = svc.dashboard(adminId)
        assertEquals(12345, r.kpis.monthRevenue)
        assertEquals(listOf(100, 200, 300), r.revenueSeries)
        assertEquals(listOf(NameCount("月卡", 3)), r.planDist)
        // 裁剪是「按字段」，不是再查一次库
        verifyNoInteractions(orders, subs)
    }

    // ---------- 按字段裁剪：audit + recent 绝不能进缓存 ----------

    @Test
    fun `无 audit read 时最近操作为空且不读审计`() {
        `when`(perm.can(adminId, "audit", "read")).thenReturn(false)
        val r = svc.dashboard(adminId)
        assertTrue(r.recent.isEmpty())
        verify(auditSvc, never()).audit(anyString(), any())
    }

    @Test
    fun `有 audit read 时最近操作只取前 6 条`() {
        val r = svc.dashboard(adminId)
        assertEquals(6, r.recent.size)
        assertEquals("id1", r.recent.first().id)
        assertEquals("id6", r.recent.last().id)
    }

    @Test
    fun `recent 每请求现算：缓存命中两次仍需查两次审计`() {
        svc.dashboard(adminId)
        svc.dashboard(adminId)
        verify(auditSvc, times(2)).audit(adminId, null)
    }
}
