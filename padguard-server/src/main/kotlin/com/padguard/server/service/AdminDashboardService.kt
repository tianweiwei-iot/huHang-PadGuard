package com.padguard.server.service

import com.padguard.server.common.SERVER_ZONE_ID
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
    private val serverProfileService: ServerProfileService,
    private val dashboardCache: DashboardCache
) {
    private val log = LoggerFactory.getLogger(AdminDashboardService::class.java)

    fun dashboard(adminId: String): DashboardResponse {
        // S13：仪表盘是跨领域聚合（含服务器状态），统一挂 server:read。
        // 权限校验必须**先于**缓存查询：缓存命中永远不能替代鉴权，
        // 否则无权限的管理员能在 TTL 窗口内读到完整仪表盘。
        adminPerm.require(adminId, "server", "read")
        val now = System.currentTimeMillis()

        /*
         * 这里所有数字都是聚合结果，没有一处需要实体本身 —— 全部下推成 count / group by。
         *
         * 改造前是一次请求 6 次全表扫描：`userRepository.findAll()` 被调了两次
         * （总量一次、区域分布又一次）、设备一次、订阅一次，外加营收的两次订单全表
         * （本月收入一遍、近 6 月曲线又一遍）。后台首页是运营停留最久、刷新最频繁的一页，
         * 而家长 / 设备 / 订单三张表都只增不减 —— 这个页面会随业务增长稳定地越来越慢，
         * 且慢得毫无征兆（不是某个功能坏了，是整个后台开始发卡）。
         *
         * 顺带修掉一个只有并发时才会暴露的不一致：营收的两个数字原来分两次查询，
         * 期间若有订单入账，首页会显示"本月营收 ¥X，但营收曲线最后一点是 ¥Y"。
         * 现在两者出自同一次查询的同一批数据。
         *
         * 聚合部分走 30 秒短缓存（见 [DashboardCache] 类注释）：
         * KPI 是分钟级数字，不值得每次刷新都打十余次聚合查询；
         * `recent` 依赖当前管理员的 audit:read 权限，必须每请求现算，绝不能进缓存。
         */
        val cached = dashboardCache.get(now)
        val agg = cached ?: buildAggregate(now).also { dashboardCache.put(it) }

        // 首页最近操作：必须尊重 audit:read —— 没有审计查看权限的角色不应在首页看到操作流。
        val recent = if (adminPerm.can(adminId, "audit", "read")) adminAuditService.audit(adminId, null).take(6) else emptyList()
        // 营收同口径：仪表盘把月营收 / 营收曲线 / 套餐分布和服务器状态挤在同一个响应里，
        // 只挂 server:read 时，一个"只看服务器监控"的角色在首页能读到全部经营数据，
        // 而它访问 /v1/admin/billing/revenue 是 403 —— 同一个数字两套口径，等于权限形同虚设。
        // 聚合快照本身与请求者无关（走缓存），这里是**按字段裁剪**，不是再查一次库。
        val canBilling = adminPerm.can(adminId, "billing", "read")

        return DashboardResponse(
            kpis = Kpis(
                agg.parentsTotal, agg.parentsActive, agg.parentsSuspended, agg.devicesTotal, agg.devicesOnline,
                agg.subsActive, agg.subsTrial,
                if (canBilling) agg.monthRevenue else 0,
                agg.serverStatus
            ),
            revenueSeries = if (canBilling) agg.revenueSeries else emptyList(),
            planDist = if (canBilling) agg.planDist else emptyList(),
            regionDist = agg.regionDist,
            alertsOpen = agg.alertsOpen,
            recent = recent,
            trends = agg.trends
        )
    }

    /** 构建与请求者无关的聚合快照（缓存未命中时执行，一次请求十余条聚合查询） */
    private fun buildAggregate(now: Long): DashboardCache.Snapshot {
        val parentsTotal = userRepository.count()
        // 注意用 coalesce 版本：status 是后加的列，存量行为 NULL，派生查询 countByStatus 不计入它们。
        val parentsActive = userRepository.countByStatusOrNullAsActive("active")
        val parentsSuspended = userRepository.countByStatusOrNullAsActive("suspended")

        val devicesTotal = deviceRepository.count()
        val devicesOnline = deviceRepository.countOnlineSince(now - ONLINE_STALE_MS)

        val subsActive = subscriptionRepository.countByStatus(SubStatus.ACTIVE.name)
        val subsTrial = subscriptionRepository.countByStatus(SubStatus.TRIAL.name)

        val serverStatus = if (dbUp(dataSource)) "UP" else "DOWN"

        val paidOrders = paidOrdersSince(paymentOrderRepository, 6, now)
        val monthRevenue = monthPaidSumOf(paidOrders, now)
        val revenueSeries = lastMonthsRevenueOf(paidOrders, 6, now).map { it.amount }

        val planDist = subscriptionRepository.countGroupedByPlan()
            .groupBy { planCategory(it[0] as String?) }
            .map { (cat, rows) ->
                NameCount(
                    planLabel(if (cat == "free") "TRIAL" else cat.uppercase()),
                    rows.sumOf { (it[1] as Number).toInt() }
                )
            }
        // users.region 字段存在（V13 起可维护），早期恒返回空数组导致首页柱状图永远空白。
        // 归并仍在内存里做：分组数等于区域数（个位数），把 NULL/空串合并成"未填写"
        // 用 SQL 表达反而要依赖 nullif 之类各家方言不一的东西。
        val regionDist = userRepository.countGroupedByRegion()
            .map { row ->
                ((row[0] as String?)?.takeIf { it.isNotBlank() } ?: "未填写") to (row[1] as Number).toInt()
            }
            .groupBy({ it.first }, { it.second })
            .map { (region, counts) -> NameCount(region, counts.sum()) }
            .sortedByDescending { it.count }

        // 待处理口径 = ACTIVE + ACKNOWLEDGED，与后台铃铛角标（app.js 的
        // status !== "closed"）和告警列表（AdminAuditService 把 ACKNOWLEDGED 渲染为
        // "家长已确认"、且仍提供"标记为已处理"按钮）保持一致。
        // 原来只数 ACTIVE：只要存在任意一条家长已确认未关单的告警，
        // 首页卡片和铃铛就显示两个不同的数，运营无法信任该 KPI。
        // CLOSED / RESOLVED 是终态（家长关闭 / 自愈关闭 / 后台关单），不计入。
        val alertsOpen = alertRepository.countByStatus("ACTIVE") +
            alertRepository.countByStatus("ACKNOWLEDGED")

        val trendSince = trendWindowStart(TREND_DAYS)
        return DashboardCache.Snapshot(
            parentsTotal = parentsTotal,
            parentsActive = parentsActive,
            parentsSuspended = parentsSuspended,
            devicesTotal = devicesTotal,
            devicesOnline = devicesOnline,
            subsActive = subsActive,
            subsTrial = subsTrial,
            monthRevenue = monthRevenue,
            serverStatus = serverStatus,
            revenueSeries = revenueSeries,
            planDist = planDist,
            regionDist = regionDist,
            alertsOpen = alertsOpen,
            trends = DashboardTrends(
                parent = growthTrend(dailyCounts(userRepository.findCreatedAtSince(trendSince), TREND_DAYS)),
                device = growthTrend(dailyCounts(deviceRepository.findCreatedAtSince(trendSince), TREND_DAYS)),
                subscription = growthTrend(dailyCounts(subscriptionRepository.findCreatedAtSince(trendSince), TREND_DAYS)),
                revenue = revenueTrend(revenueSeries)
            ),
            builtAt = now
        )
    }

    // ---------- KPI 趋势 ----------

    /** 趋势窗口的起点：含最早那一天 00:00（北京时间），与 [dailyCounts] 的分桶边界一致。 */
    private fun trendWindowStart(days: Int): Long {
        val zone = SERVER_ZONE_ID
        val start = java.time.LocalDate.now(zone).minusDays((days - 1).toLong())
        return start.atStartOfDay(zone).toInstant().toEpochMilli()
    }

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
            label = "近 $TREND_DAYS 天"
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

    private companion object {
        /** KPI 趋势的天数窗口。改这里时记得同步 [growthTrend] 的 label 文案。 */
        const val TREND_DAYS = 7
    }
}
