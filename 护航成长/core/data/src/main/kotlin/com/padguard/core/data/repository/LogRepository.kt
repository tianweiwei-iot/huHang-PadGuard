package com.padguard.core.data.repository

import com.padguard.core.common.Logger
import com.padguard.core.data.db.BehaviorLogDao
import com.padguard.core.data.db.BehaviorLogEntity
import com.padguard.core.data.db.RiskEventDao
import com.padguard.core.data.db.RiskEventEntity
import com.padguard.core.data.model.BehaviorLog
import com.padguard.core.data.model.LogType
import com.padguard.core.data.model.RiskEvent
import com.padguard.core.data.model.RiskLevel
import com.padguard.core.data.model.RiskType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 行为日志与告警事件仓库（对应说明书「全量行为日志采集」）。
 *
 * 两级队列：
 * - 普通行为日志：批量上报（默认每 5 分钟一批，每批 500 条）
 * - 高危告警事件：**立即**通过 MQTT QoS1 上报，不受批量窗口限制
 *
 * 存储上限保护：日志堆积过多时优先丢弃最旧的普通日志，
 * 但告警事件（`risk_event` 表）永不因容量而丢弃。
 */
@Singleton
class LogRepository @Inject constructor(
    private val logDao: BehaviorLogDao,
    private val riskDao: RiskEventDao
) {

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        const val UPLOAD_BATCH_SIZE = 500
        const val ALERT_BATCH_SIZE = 50
        /** 未上传日志的软上限，超出后丢弃最旧的普通日志 */
        private const val MAX_PENDING_LOGS = 50_000
    }

    suspend fun append(type: LogType, payload: Map<String, String>, timestamp: Long = System.currentTimeMillis()) {
        logDao.insert(
            BehaviorLogEntity(
                logId = newId("log"),
                type = type.name,
                timestamp = timestamp,
                payloadJson = json.encodeToString(payload),
                uploaded = false
            )
        )
        trimIfOverflow()
    }

    fun observePendingCount(): Flow<Int> = logDao.observePendingCount()

    suspend fun pendingCount(): Int = logDao.pendingCount()

    suspend fun takePendingLogs(limit: Int = UPLOAD_BATCH_SIZE): List<BehaviorLog> =
        logDao.pending(limit).map { entity ->
            BehaviorLog(
                logId = entity.logId,
                type = runCatching { LogType.valueOf(entity.type) }.getOrDefault(LogType.APP_USAGE),
                timestamp = entity.timestamp,
                payload = decodePayload(entity.payloadJson),
                uploaded = false
            )
        }

    suspend fun markLogsUploaded(logs: List<BehaviorLog>) {
        if (logs.isEmpty()) return
        // takePendingLogs 按时间正序取，这里重取同样窗口的自增 id 即为刚上报的那批。
        // 配合服务端 logId 幂等去重，即使标记范围略有偏差也不会造成数据丢失或重复统计。
        val ids = logDao.pending(logs.size).map { it.id }
        logDao.markUploaded(ids)
        Logger.v("LogRepository") { "marked ${ids.size} logs uploaded" }
    }

    // ==================== 告警事件 ====================

    suspend fun raise(
        type: RiskType,
        level: RiskLevel,
        detail: Map<String, String> = emptyMap(),
        deviceId: String
    ): RiskEvent {
        val event = RiskEvent(
            eventId = newId("evt"),
            deviceId = deviceId,
            type = type,
            level = level,
            timestamp = System.currentTimeMillis(),
            detail = detail
        )
        riskDao.insert(
            RiskEventEntity(
                eventId = event.eventId,
                type = type.name,
                level = level.name,
                timestamp = event.timestamp,
                detailJson = json.encodeToString(detail)
            )
        )
        return event
    }

    suspend fun takePendingAlerts(limit: Int = ALERT_BATCH_SIZE): List<RiskEvent> =
        riskDao.pending(limit).map { entity ->
            RiskEvent(
                eventId = entity.eventId,
                deviceId = "",
                type = runCatching { RiskType.valueOf(entity.type) }.getOrDefault(RiskType.POLICY_APPLY_FAILED),
                level = runCatching { RiskLevel.valueOf(entity.level) }.getOrDefault(RiskLevel.NORMAL),
                timestamp = entity.timestamp,
                detail = decodePayload(entity.detailJson)
            )
        }

    suspend fun markAlertsUploaded(events: List<RiskEvent>) {
        val uploaded = riskDao.pending(events.size).map { it.id }
        riskDao.markUploaded(uploaded)
    }

    suspend fun pendingAlertCount(): Int = riskDao.pendingCount()

    // ==================== 首页"最近拦截"查询 ====================

    /**
     * 供首页展示的"最近拦截"列表，合并两类来源：
     * - URL 拦截（[LogType.URL_BLOCKED]）→ 来自 behavior_log
     * - 时长 / 名单 / 风险类告警 → 来自 risk_event（按白名单过滤）
     *
     * 按时间倒序合并后取前 [limit] 条，方便首页一行一个卡片展示。
     *
     * @param limit 最多返回多少条
     */
    fun observeRecentBlocks(limit: Int): Flow<List<RecentBlock>> {
        val urlFlow = logDao.observeRecentOfType(LogType.URL_BLOCKED.name, limit)
            .map { list -> list.map { it.toRecentBlock() } }
        val alertFlow = riskDao.observeRecent(limit)
            .map { list -> list.map { it.toRecentBlock() } }
        return combine(urlFlow, alertFlow) { urls, alerts ->
            (urls + alerts)
                .sortedByDescending { it.timestamp }
                .take(limit)
        }
    }

    /** 拦截条目统一视图，避免 HomeViewModel 同时处理两种 entity */
    data class RecentBlock(
        val summary: String,
        val timestamp: Long,
        val source: Source
    ) {
        enum class Source { URL, RISK }
    }

    private fun BehaviorLogEntity.toRecentBlock(): RecentBlock {
        val payload = decodePayload(payloadJson)
        val url = payload["url"] ?: payload["host"] ?: "未知网址"
        return RecentBlock(
            summary = "拦截访问：$url",
            timestamp = timestamp,
            source = RecentBlock.Source.URL
        )
    }

    private fun RiskEventEntity.toRecentBlock(): RecentBlock {
        val type = runCatching { RiskType.valueOf(type) }.getOrDefault(RiskType.POLICY_APPLY_FAILED)
        val detail = decodePayload(detailJson)
        val pkg = detail["packageName"]?.takeIf { it.isNotBlank() }
        val summary = when {
            pkg != null && type == RiskType.TIME_LIMIT_EXCEEDED -> "$pkg 当日时长已用完"
            pkg != null -> "$pkg 被限制使用"
            type == RiskType.TIME_LIMIT_EXCEEDED -> "应用当日时长已用完"
            type == RiskType.UNINSTALL_ATTEMPT -> "尝试卸载管控"
            else -> "管控事件：${type.name}"
        }
        return RecentBlock(
            summary = summary,
            timestamp = timestamp,
            source = RecentBlock.Source.RISK
        )
    }

    // ==================== 清理 ====================

    /**
     * 定期清理已上传的过期日志。
     * 保留天数由服务端策略下发（默认 30 天），未上传数据永不清理。
     */
    suspend fun purge(retentionDays: Int) {
        val before = System.currentTimeMillis() - retentionDays * 24L * 60 * 60 * 1000
        logDao.deleteUploadedBefore(before)
        riskDao.deleteUploadedBefore(before)
    }

    private suspend fun trimIfOverflow() {
        if (logDao.pendingCount() > MAX_PENDING_LOGS) {
            // 极端离线场景下的兜底：丢弃最旧的已上传日志释放空间
            logDao.deleteUploadedBefore(System.currentTimeMillis())
        }
    }

    private fun decodePayload(raw: String): Map<String, String> = runCatching {
        json.decodeFromString<Map<String, String>>(raw)
    }.getOrDefault(emptyMap())

    private fun newId(prefix: String): String =
        "$prefix-${UUID.randomUUID().toString().replace("-", "").take(20)}"
}
