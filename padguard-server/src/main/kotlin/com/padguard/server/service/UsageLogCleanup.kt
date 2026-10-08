package com.padguard.server.service

import com.padguard.server.repository.UsageLogRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 用量日志保留期清理。
 *
 * ## 为什么需要
 * `usage_logs` 是孩子端每 10 秒上行一条、只增不删的表，是库里体积增长最快的表。
 * 此前没有任何清理，历史永久累积——既无限膨胀存储，也违背"不该长期保存使用历史"的诉求
 * （见家长端『应用使用记录』相关反馈）。
 *
 * ## 保留期
 * 默认 [RETENTION_DAYS] 天。取 30 天而非更短：家长端『应用使用记录』的月视图需要近 30 天数据，
 * 保留期短于 30 天会让月视图缺数据。如需更激进（只留今日/近 7 天），改这个常量即可，
 * 代价是周/月历史视图相应变短。
 *
 * ## 执行与安全性
 * - 每日凌晨 4 点跑一次（[Scheduled] cron），每次只删"早于保留期"的那一天增量，单次删除量有界；
 * - 整段包 try/catch：清理是"锦上添花"，绝不允许它把服务带崩；
 * - 删除走 `idx_usage_logs_ts`（时间戳单索引）范围扫描，不会全表扫。
 */
@Component
class UsageLogCleanup(
    private val usageLogRepository: UsageLogRepository
) {
    private val log = LoggerFactory.getLogger(UsageLogCleanup::class.java)
    private val zone = ZoneId.of("Asia/Shanghai")
    private val fmt = DateTimeFormatter.ISO_LOCAL_DATE

    @Scheduled(cron = "0 0 4 * * *")
    fun cleanup() {
        val threshold = System.currentTimeMillis() - RETENTION_DAYS * 86_400_000L
        runCatching {
            val deleted = usageLogRepository.deleteByTimestampBefore(threshold)
            if (deleted > 0) {
                val cutoff = fmt.format(Instant.ofEpochMilli(threshold).atZone(zone))
                log.info("usage_logs 保留期清理完成：删除 {} 条早于 {} 的历史记录", deleted, cutoff)
            }
        }.onFailure {
            log.warn("usage_logs 保留期清理失败（已忽略，不影响服务）: {}", it.message)
        }
    }

    companion object {
        /** 用量日志保留天数（默认 30 天，覆盖月视图所需历史） */
        const val RETENTION_DAYS: Long = 30
    }
}
