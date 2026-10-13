package com.padguard.server.service

import com.padguard.server.domain.Device
import com.padguard.server.domain.Subscription
import com.padguard.server.domain.SubStatus
import com.padguard.server.dto.DashboardResponse
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
 * W25 从 AdminService 抽离，统一挂 server:read。
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

        val devicesTotal = deviceRepository.count()
        val devicesOnline = deviceRepository.findAll().count { online(it) }.toLong()

        val subs = subscriptionRepository.findAll()
        val subsActive = subs.count { it.status == SubStatus.ACTIVE.name }.toLong()
        val subsTrial = subs.count { it.status == SubStatus.TRIAL.name }.toLong()

        val monthRevenue = monthPaidSum(paymentOrderRepository)
        val serverStatus = if (dbUp(dataSource)) "UP" else "DOWN"

        val revenueSeries = lastMonthsRevenue(paymentOrderRepository, 6).map { it.amount }
        val planDist = subs.groupBy { planCategory(it.plan) }
            .map { (cat, list) -> NameCount(planLabel(if (cat == "free") "TRIAL" else cat.uppercase()), list.size) }
        // users.region 字段存在（V13 起可维护），此前恒返回空数组导致首页柱状图永远空白。
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
            recent = recent
        )
    }
}
