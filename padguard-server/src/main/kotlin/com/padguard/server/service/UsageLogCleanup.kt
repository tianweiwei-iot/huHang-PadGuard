package com.padguard.server.service

import com.padguard.server.config.TraceIds

import com.padguard.server.repository.UsageLogRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneId
import com.padguard.server.common.SERVER_ZONE
import com.padguard.server.common.SERVER_ZONE_ID
import java.time.format.DateTimeFormatter

/**
 * 用量日志保留期清理。
 *
 * ## 为什么需要
 * `usage_logs` 是孩子端每 10 秒上行一条、只增不删的表，是库里体积增长最快的表。
 * 早期没有任何清理，历史永久累积——既无限膨胀存储，也违背"不该长期保存使用历史"的诉求
 * （见家长端『应用使用记录』相关反馈）。
 *
 * ## 保留期
 * 默认 [RETENTION_DAYS] 天。取 30 天而非更短：家长端『应用使用记录』的月视图需要近 30 天数据，
 * 保留期短于 30 天会让月视图缺数据。如需更激进（只留今日/近 7 天），改这个常量即可，
 * 代价是周/月历史视图相应变短。
 *
 * ## 合规凭证不参与清理
 * `usage_logs` 里混着**非用量**日志：孩子端 `LogType.AGREEMENT`（使用授权协议签署，
 * 说明书 §5.4 要求上报服务端「供管控端可查『已阅已同意』」）也走同一批量上行通道。
 * 合规凭证必须可事后追溯，按 30 天 TTL 删掉等于把审计证据清掉——设备用满一个月，
 * 就再也证明不了孩子是否签署过授权。因此 [PROTECTED_TYPES] 中的类型永久保留，
 * 只在设备解绑/易主时由 `DeviceDataPurgeService` 按设备整体清除（那才是归属结束的正确时机）。
 *
 * ## 执行与安全性
 * - 每日凌晨 4 点跑一次（[Scheduled] cron），按 [BATCH_SIZE] 分页删直到没有过期行；
 * - 每批 SELECT 主键 + DELETE 主键都是独立小事务，不再"一个事务删百万行"拖住 WAL；
 * - 整段包 try/catch：清理是"锦上添花"，绝不允许它把服务带崩；
 * - 删除走 `idx_usage_logs_received_at`（服务端落库时刻单索引）范围扫描，不会全表扫。
 *
 * ## 判据是「落库时刻」而不是「事件时间戳」
 * `threshold` 与 `received_at` 比较。若改用客户端上报的 `timestamp`：
 * 设备把时间改到未来 → 这些日志永远不满足过期条件、只增不删（表写满会连带全站写入失败）；
 * 改到过去 → 当天数据被当成历史删掉；`timestamp` 为 NULL 时 `NULL < ?` 为 UNKNOWN，
 * 那些行永远删不掉。`received_at` 由服务端在 [com.padguard.server.service.IngestService]
 * 写入时赋值，设备改不了，与 [RetentionCleanup] 的媒体清理同一口径。
 */
@Component
class UsageLogCleanup(
    private val usageLogRepository: UsageLogRepository,
    /** 多副本互斥锁（可选注入：纯 JVM 单测直构时为 null），语义同 [RetentionCleanup]。 */
    private val taskLock: com.padguard.server.support.TaskExecutionLock? = null
) {
    private val log = LoggerFactory.getLogger(UsageLogCleanup::class.java)
    private val zone = SERVER_ZONE_ID
    private val fmt = DateTimeFormatter.ISO_LOCAL_DATE

    private val holder = runCatching {
        "${java.net.InetAddress.getLocalHost().hostName}-${ProcessHandle.current().pid()}"
    }.getOrDefault("unknown-${ProcessHandle.current().pid()}")

    // zone 必须显式钉死：不带 zone 的 cron 走 JVM 默认时区，容器未设 TZ 时为 UTC，
    // 直观后果是"凌晨 4 点跑的历史清理"实际在北京时间中午 12 点执行，恰好压到使用高峰。
    @Scheduled(cron = "0 0 4 * * *", zone = SERVER_ZONE)
    fun cleanup() = TraceIds.withNewTrace("sched") {
        // 有锁时完全交给 runExclusively：抢锁失败返回 null 即跳过本轮。
        // 不可写成 `runExclusively(...) ?: cleanupInternal()`——那会让抢锁失败的副本再清一次。
        if (taskLock == null) cleanupInternal()
        else taskLock.runExclusively("usage-log-cleanup", holder, 10 * 60_000L) { cleanupInternal() }
    }

    /** 原清理逻辑；公开入口只负责给每次调度一个独立 traceId（见 TraceIdFilter 说明）。 */
    private fun cleanupInternal() {
        val threshold = System.currentTimeMillis() - RETENTION_DAYS * 86_400_000L
        runCatching {
            var deleted = 0
            while (true) {
                // 每轮都按同样的阈值重查：上一批删掉的行不再出现，天然推进游标。
                val ids = usageLogRepository.findExpiredIds(
                    threshold, PROTECTED_TYPES, PageRequest.of(0, BATCH_SIZE)
                )
                if (ids.isEmpty()) break
                val n = usageLogRepository.deleteByIds(ids)
                deleted += n
                // 有并发写入/数据库返回 0 时不能死循环；下一轮调度会再试。
                if (n < ids.size) break
            }
            if (deleted > 0) {
                val cutoff = fmt.format(Instant.ofEpochMilli(threshold).atZone(zone))
                log.info("usage_logs 保留期清理完成：删除 {} 条早于 {} 的历史记录", deleted, cutoff)
            }
        }.onFailure {
            // 同 RetentionCleanup：清理失败 = 用量日志表无限膨胀，必须按错误告警。
            log.error("usage_logs 保留期清理失败（表将持续膨胀，请检查事务/权限）: {}", it.message, it)
        }
    }

    companion object {
        /** 用量日志保留天数（默认 30 天，覆盖月视图所需历史） */
        const val RETENTION_DAYS: Long = 30

        /** 单批最多删 5000 行：足够 amortize 往返开销，又把事务/WAL 峰值压在可控范围。 */
        const val BATCH_SIZE: Int = 5000

        /**
         * 合规凭证类日志：不随用量保留期删除。
         *
         * 值必须与孩子端 `LogType` 枚举名逐字符一致（跨工程口径，见
         * `CommandContractCoverageTest` 的同类防线）。新增合规日志类型时，
         * **先在这里登记再上线**，否则会被下一次凌晨清理无声删掉。
         */
        val PROTECTED_TYPES: Set<String> = setOf("AGREEMENT")
    }
}
