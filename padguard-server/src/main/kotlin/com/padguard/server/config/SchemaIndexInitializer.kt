package com.padguard.server.config

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * 启动后保障查询索引存在（幂等）。
 *
 * ## 为什么不能只依赖实体上的 @Index
 * 实体上的 `@Index` 只在 Hibernate **建表**那一瞬间生效。而本地 / 家庭部署用的是
 * `ddl-auto=update`（`flyway.enabled=false`），对**已存在的表**补索引这件事
 * Hibernate 并不保证——实测同一份代码下 commands / alerts / device_events 的索引建出来了，
 * 数据量最大的 `usage_logs` 却一条都没建。于是"索引在不在"变成了一个
 * 随 Hibernate 内部行为漂移的隐性约定：开发机上快、用户机器上慢，且极难定位。
 *
 * 这里改为显式、幂等、可观测：启动后对每张表执行一次 `CREATE INDEX IF NOT EXISTS`。
 * 语句同时兼容 H2 与 PostgreSQL，任一失败只记日志、绝不阻断启动
 * （索引缺失是性能问题，不该让服务起不来）。
 *
 * 执行时机用 ApplicationRunner：此时 Hibernate 的 ddl-auto 已完成，
 * 全新库上"先建表再建索引"的顺序是确定的。
 */
@Component
class SchemaIndexInitializer(
    private val jdbc: JdbcTemplate
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(SchemaIndexInitializer::class.java)

    override fun run(args: ApplicationArguments?) {
        var ok = 0
        for (sql in INDEX_DDL) {
            runCatching {
                jdbc.execute(sql)
                ok++
            }.onFailure {
                log.warn("索引创建失败（已忽略，不阻断启动）: {} -> {}", sql, it.message)
            }
        }
        log.info("schema 索引保障完成: 目标 {} 条，成功 {}", INDEX_DDL.size, ok)
    }

    private companion object {
        /**
         * 覆盖所有高频查询路径的索引。全部使用 IF NOT EXISTS，重复启动无副作用。
         * 顺序：先列被高频轮询/统计命中的表。
         */
        val INDEX_DDL: List<String> = listOf(
            // 指令轮询（孩子端每 3 秒一次）的完整过滤条件
            "CREATE INDEX IF NOT EXISTS idx_commands_poll ON commands (device_id, status, expires_at, created_at)",
            "CREATE INDEX IF NOT EXISTS idx_commands_device_id ON commands (device_id)",

            // 使用日志：数据量最大的表，统计看板按「设备 + 时间范围 / 类型」扫描
            "CREATE INDEX IF NOT EXISTS idx_usage_logs_device_ts ON usage_logs (device_id, timestamp)",
            "CREATE INDEX IF NOT EXISTS idx_usage_logs_device_type ON usage_logs (device_id, type, timestamp)",
            "CREATE INDEX IF NOT EXISTS idx_usage_logs_device_id ON usage_logs (device_id)",
            // 保留期清理：按事件时间戳范围删除，单 (timestamp) 索引让 DELETE 走范围扫描而非全表
            "CREATE INDEX IF NOT EXISTS idx_usage_logs_ts ON usage_logs (timestamp)",

            // 设备事件（违规统计）
            "CREATE INDEX IF NOT EXISTS idx_device_events_device_ts ON device_events (device_id, timestamp)",
            "CREATE INDEX IF NOT EXISTS idx_device_events_device_id ON device_events (device_id)",

            // 告警列表按「用户 + 设备 + 状态」过滤
            "CREATE INDEX IF NOT EXISTS idx_alerts_user_device_status ON alerts (user_id, device_id, status)",
            "CREATE INDEX IF NOT EXISTS idx_alerts_user_status ON alerts (user_id, status)",
            "CREATE INDEX IF NOT EXISTS idx_alerts_device_id ON alerts (device_id)",

            // 审计日志分页（只增不删，增长最快的表之一）
            "CREATE INDEX IF NOT EXISTS idx_audit_logs_device ON audit_logs (device_id, created_at)",
            "CREATE INDEX IF NOT EXISTS idx_audit_logs_user ON audit_logs (user_id, created_at)",

            // 远程截图回执按 (device_id, shot_id) 精确回查
            "CREATE INDEX IF NOT EXISTS idx_screenshots_device_shot ON screenshots (device_id, shot_id)",
            "CREATE INDEX IF NOT EXISTS idx_screenshots_device_id ON screenshots (device_id, captured_at)",

            // 应用台账按设备查（统计聚合图标时回查）
            "CREATE INDEX IF NOT EXISTS idx_installed_apps_device ON installed_apps (device_id, installed)"
        )
    }
}
