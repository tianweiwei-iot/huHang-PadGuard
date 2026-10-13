package com.padguard.server.service

import com.padguard.server.dto.ServerMetrics
import com.padguard.server.observability.RequestMetrics
import com.padguard.server.observability.RuntimeLogRing
import com.padguard.server.security.AdminPermissionService
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.lang.management.ManagementFactory
import javax.sql.DataSource

/**
 * 服务端监控（JVM 指标 / 数据库连通 / 实时日志）。W25 从 AdminService 抽离。
 */
@Service
class AdminServerMonitorService(
    private val adminPerm: AdminPermissionService,
    private val requestMetrics: RequestMetrics,
    private val runtimeLogRing: RuntimeLogRing,
    private val dataSource: DataSource,
    private val serverProfileService: ServerProfileService
) {
    private val log = LoggerFactory.getLogger(AdminServerMonitorService::class.java)

    fun serverMetrics(adminId: String): ServerMetrics {
        requireRead(adminPerm, adminId, "server")
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        val maxMb = rt.maxMemory() / (1024 * 1024)
        val up = dbUp(dataSource)
        return ServerMetrics(
            status = if (up) "UP" else "DOWN",
            db = if (up) "UP" else "DOWN",
            uptimeMs = System.currentTimeMillis() - startedAt,
            startedAt = fmt(startedAt) ?: "",
            jvmHeapUsedMb = usedMb.toInt(),
            jvmHeapMaxMb = maxMb.toInt(),
            threads = ManagementFactory.getThreadMXBean().threadCount,
            // 以下三项取自 RequestMetrics 的 60 秒滑动窗口真值（此前是硬编码假数字）。
            requestsPerMin = requestMetrics.requestsPerMin(),
            qpmHistory = requestMetrics.qpmHistory(),
            errorRate = requestMetrics.errorRate(),
            avgLatencyMs = requestMetrics.avgLatencyMs(),
            dbConnections = dbConnections(),
            instances = listOf(
                ServerMetrics.Instance(
                    name = "padguard-server",
                    // 主机地址取自后台可维护的「服务器基本信息」，此前写死 192.168.1.10:8090。
                    host = runCatching { serverProfileService.getServerProfile() }
                        .getOrNull()?.let { "${it.publicHost}:${it.port}" } ?: "—",
                    status = if (up) "UP" else "DOWN",
                    cpu = "—",
                    mem = "${usedMb.toInt()} / ${maxMb.toInt()} MB"
                )
            ),
            logs = runtimeLogRing.recent()
        )
    }

    /**
     * 数据库连接池使用情况。取 HikariCP 的池指标；若部署换成其他 DataSource 或 MXBean 不可用，
     * 返回明确说明而非占位符 "—"。
     */
    private fun dbConnections(): String = runCatching {
        val hikari = dataSource as? HikariDataSource
        val mx = hikari?.hikariPoolMXBean
        if (mx != null) {
            "${mx.activeConnections} 活跃 / ${mx.idleConnections} 空闲 / ${mx.totalConnections} 总数"
        } else {
            "${dataSource.javaClass.simpleName}（未暴露池指标）"
        }
    }.getOrDefault("不可用")

    /** 后台「实时日志」：直接透传进程内日志环形缓冲 */
    fun serverLogs(adminId: String): List<ServerMetrics.LogLine> {
        requireRead(adminPerm, adminId, "server")
        return runtimeLogRing.recent()
    }
}
