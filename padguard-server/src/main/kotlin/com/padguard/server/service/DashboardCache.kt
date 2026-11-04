package com.padguard.server.service

import com.padguard.server.dto.DashboardTrends
import com.padguard.server.dto.NameCount
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * 仪表盘聚合结果的短 TTL 缓存。
 *
 * ## 为什么要有这层
 * 上一轮把全表扫描清掉之后，dashboard 一次请求仍要打十余次 count / group by / 窗口查询。
 * 后台首页是运营停留最久、手动刷新最频繁的一页 —— 但 KPI 数字对实时性的要求是
 * "分钟级就够"，没有人盯着首页看秒级变化。30 秒缓存把高频刷新的代价压到接近零，
 * 同时保证任何一次刷新看到的数字最多滞后 30 秒。
 *
 * ## 为什么不缓存整个 DashboardResponse
 * `recent`（最近操作）依赖**当前管理员**是否有 audit:read 权限 —— 不同管理员结果不同。
 * 缓存里混入任何按请求者区分的字段，都会变成"无权限的管理员在 30 秒内读到
 * 有权限者的操作流"这一越权。所以缓存的只有全局聚合，`recent` 每请求现算
 * （audit 列表上一轮已下推分页，成本可控）。
 *
 * ## 为什么权限校验必须在缓存之外
 * `adminPerm.require` 若被缓存短路，一个只有 server:read 之外权限的管理员
 * 会在缓存命中的 30 秒窗口内看到完整仪表盘。缓存命中永远不能替代鉴权。
 *
 * TTL=0（或负数）表示禁用缓存，每次现算 —— 集成测试必须用这个值，
 * 否则"创建家长 → 再查总数变了"这类断言会读到缓存的旧值，测试失去意义。
 */
@Component
class DashboardCache(
    /** 缓存有效期（毫秒），默认 30 秒；0 表示禁用 */
    @Value("\${padguard.admin.dashboard-cache-ttl-ms:30000}")
    private val ttlMs: Long = 30_000
) {
    /**
     * 一次 dashboard 请求里**与请求者无关**的全部聚合结果。
     * 字段与 [com.padguard.server.dto.DashboardResponse] 对齐，唯独不含 recent。
     */
    data class Snapshot(
        val parentsTotal: Long,
        val parentsActive: Long,
        val parentsSuspended: Long,
        val devicesTotal: Long,
        val devicesOnline: Long,
        val subsActive: Long,
        val subsTrial: Long,
        val monthRevenue: Int,
        val serverStatus: String,
        val revenueSeries: List<Int>,
        val planDist: List<NameCount>,
        val regionDist: List<NameCount>,
        val alertsOpen: Long,
        val trends: DashboardTrends?,
        val builtAt: Long
    )

    @Volatile
    private var snapshot: Snapshot? = null

    /** 命中返回缓存；过期或禁用返回 null。 */
    fun get(now: Long): Snapshot? =
        snapshot?.takeIf { ttlMs > 0 && now - it.builtAt < ttlMs }

    fun put(value: Snapshot) {
        snapshot = value
    }
}
