package com.padguard.server.service

import com.padguard.server.common.SERVER_ZONE_ID
import com.padguard.server.domain.Device
import com.padguard.server.domain.Subscription
import com.padguard.server.domain.SubStatus
import com.padguard.server.dto.DashboardResponse
import com.padguard.server.dto.DashboardTrends
import com.padguard.server.dto.KpiTrend
import com.padguard.server.dto.Kpis
import com.padguard.server.dto.NameCount
import com.padguard.server.observability.RequestMetrics
import com.padguard.server.observability.RuntimeLogRing
import com.padguard.server.repository.AlertRepository
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.PaymentOrderRepository
import com.padguard.server.repository.SubscriptionRepository
import com.padguard.server.repository.UserRepository
import com.padguard.server.security.AdminPermissionService
import com.padguard.server.service.ServerProfileService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import javax.sql.DataSource

/**
 * 仪表盘聚合（跨领域 KPI + 营收序列 + 套餐分布 + 区域分布 + 告警数 + 最近操作）。
 * 统一挂 server:read。
 */
@Service
class AdminDashboardService(
    private val userRepository: UserRepository,
    private val deviceRepository: DeviceRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val paymentOrderRepository: PaymentOrderRepository,
    private val alertRepository: AlertRepository,
    private val adminPerm: AdminPermissionService,
    private val adminAuditService: AdminAuditService,
    private val requestMetrics: RequestMetrics,
    private val runtimeLogRing: RuntimeLogRing,
    private val dataSource: DataSource,
    private val serverProfileService: ServerProfileService
) {
    private val log = LoggerFactory.getLogger(AdminDashboardService::class.java)

    fun dashboard(adminId: String): DashboardResponse {
        // S13：仪表盘是跨领域聚合（含服务器状态），统一挂 server:read。
        adminPerm.require(adminId, "server", "read")
        val users = userRepository.findAll()
        val parentsTotal = users.size.toLong()
        val parentsActive = users.count { (it.status ?: "active") == "active" }.toLong()
        val parentsSuspended = users.count { (it.status ?: "active") == "suspended" }.toLong()

        val allDevices = deviceRepository.findAll()
        val devicesTotal = deviceRepository.count()
        val devicesOnline = allDevices.count { online(it) }.toLong()

        val subs = subscriptionRepository.findAll()
        val subsActive = subs.count { it.status == SubStatus.ACTIVE.name }.toLong()
        val subsTrial = subs.count { it.status == SubStatus.TRIAL.name }.toLong()

        val monthRevenue = monthPaidSum(paymentOrderRepository)
        val serverStatus = if (dbUp(dataSource)) "UP" else "DOWN"

        val revenueSeries = lastMonthsRevenue(paymentOrderRepository, 6).map { it.amount }
        val planDist = subs.groupBy { planCategory(it.plan) }
            .map { (cat, list) -> NameCount(planLabel(if (cat == "free") "TRIAL" else cat.uppercase()), list.size) }
        // users.region 字段存在（V13 起可维护），早期恒返回空数组导致首页柱状图永远空白。
        val regionDist = userRepository.findAll()
            .groupBy { it.region?.takeIf { r -> r.isNotBlank() } ?: "未填写" }
            .map { (region, list) -> NameCount(region, list.size) }
            .sortedByDescending { it.count }

        val alertsOpen = alertRepository.countByStatus("ACTIVE")

        // 首页最近操作：必须尊重 audit:read —— 没有审计查看权限的角色不应在首页看到操作流。
        val recent = if (adminPerm.can(adminId, "audit", "read")) adminAuditService.audit(adminId, null).take(6) else emptyList()

        return DashboardResponse(
            kpis = Kpis(
                parentsTotal, parentsActive, parentsSuspended, devicesTotal, devicesOnline,
                subsActive, subsTrial, monthRevenue, serverStatus
            ),
            revenueSeries = revenueSeries,
            planDist = planDist,
            regionDist = regionDist,
            alertsOpen = alertsOpen,
            recent = recent,
            trends = buildTrends(users, allDevices, subs, revenueSeries)
        )
    }

    // ---------- KPI 趋势 ----------

    /**
     * 近 7 天，按服务端时区（Asia/Shanghai）自然日分桶的每日新增计数。
     *
     * `createdAt == 0` 的记录（历史脏数据 / 迁移前写入）无法归入任何一天，
     * 这里**直接剔除**而不是塞进第一天 —— 否则曲线左端会凭空多出一截，
     * 看起来像"上线首日涌入一批用户"。
     */
    private fun dailyCounts(epochs: Collection<Long>, days: Int): List<Int> {
        val zone = SERVER_ZONE_ID
        val today = java.time.LocalDate.now(zone)
        val start = today.minusDays((days - 1).toLong())
        val buckets = MutableList(days) { 0 }
        for (e in epochs) {
            if (e <= 0L) continue
            val d = java.time.Instant.ofEpochMilli(e).atZone(zone).toLocalDate()
            val idx = java.time.temporal.ChronoUnit.DAYS.between(start, d).toInt()
            if (idx in 0 until days) buckets[idx]++
        }
        return buckets
    }

    /** 由序列尾部相对首部的变化量定方向；用于"近 7 天新增"这类持续增长指标 */
    private fun growthTrend(series: List<Int>): KpiTrend {
        val delta = series.sum()
        val direction = when {
            delta > 0 -> "up"
            delta < 0 -> "down"
            else -> "flat"
        }
        return KpiTrend(
            series = if (series.isNotEmpty()) series else null,
            delta = if (series.isEmpty()) null else delta,
            unit = "count",
            direction = if (series.isEmpty()) null else direction,
            label = "近 7 天"
        )
    }

    /**
     * 营收趋势：本月相对上月的差额。
     *
     * 只有拿到至少两个月的真实数据才给出 delta —— 新部署环境首月只有一个月数据时
     * 返回 null（前端显示"—"），而不是拿 0 当上月光景、算出"环比增长 100%"。
     */
    private fun revenueTrend(series: List<Int>): KpiTrend {
        if (series.size < 2) {
            return KpiTrend(
                series = series.ifEmpty { null }, delta = null, unit = "fen",
                direction = null, label = "较上月"
            )
        }
        val last = series.last()
        val prev = series[series.size - 2]
        val delta = last - prev
        val direction = when {
            delta > 0 -> "up"
            delta < 0 -> "down"
            else -> "flat"
        }
        return KpiTrend(series = series, delta = delta, unit = "fen", direction = direction, label = "较上月")
    }

    private fun buildTrends(
        users: List<com.padguard.server.domain.User>,
        devices: List<Device>,
        subs: List<Subscription>,
        revenueSeries: List<Int>
    ): DashboardTrends = DashboardTrends(
        parent = growthTrend(dailyCounts(users.map { it.createdAt }, 7)),
        device = growthTrend(dailyCounts(devices.map { it.createdAt }, 7)),
        subscription = growthTrend(dailyCounts(subs.map { it.createdAt }, 7)),
        revenue = revenueTrend(revenueSeries)
    )
}
