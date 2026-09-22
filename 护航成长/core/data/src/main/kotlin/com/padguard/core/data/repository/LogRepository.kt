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

        /**
         * 算得上「一次拦截」的风险类型。
         *
         * 其余（策略自检失败、root、开发者选项、心跳超时…）属于**环境风险**，
         * 走告警上报给家长即可，不该出现在孩子端首页的「最近拦截」里 ——
         * 孩子看不懂，也无从处理，只会以为管控出了故障。
         */
        private val BLOCK_RISK_TYPES = setOf(
            RiskType.BLACKLIST_APP_LAUNCH,
            RiskType.BLOCKED_URL_ACCESS,
            RiskType.TIME_LIMIT_EXCEEDED,
            RiskType.UNINSTALL_ATTEMPT,
            RiskType.CLEAR_DATA_ATTEMPT
        )
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
     * - 时长 / 名单 / 卸载类拦截 → 来自 risk_event（按 [BLOCK_RISK_TYPES] 过滤）
     *
     * 按时间倒序合并后取前 [limit] 条，方便首页一行一个卡片展示。
     *
     * ## 为什么要过滤 risk_event
     * risk_event 里既有"真正被拦下来"的事件，也有策略自检、环境风险（已 root、
     * 开发者选项被打开、策略项需重新下发…）。后者不是一次拦截，混进「最近拦截」
     * 会让孩子端首页出现「管控事件：POLICY_APPLY_FAILED」这种看不懂也无从处理的条目。
     *
     * @param limit 最多返回多少条
     */
    fun observeRecentBlocks(limit: Int): Flow<List<RecentBlock>> {
        val urlFlow = logDao.observeRecentOfType(LogType.URL_BLOCKED.name, limit)
            .map { list -> list.map { it.toRecentBlock() } }
        val alertFlow = riskDao.observeRecent(limit)
            .map { list -> list.mapNotNull { it.toRecentBlock() } }
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

    /**
     * 风险事件 → 拦截条目。
     *
     * 只保留**真正拦下了某个动作**的类型；其余返回 null（首页不展示）。
     * 兜底文案也从裸枚举名改成人话 —— 曾经直接拼 `type.name`，
     * 首页就出现「管控事件：POLICY_APPLY_FAILED」这种内部代号。
     */
    private fun RiskEventEntity.toRecentBlock(): RecentBlock? {
        val type = runCatching { RiskType.valueOf(type) }.getOrNull() ?: return null
        if (type !in BLOCK_RISK_TYPES) return null

        val detail = decodePayload(detailJson)
        val pkg = detail["packageName"]?.takeIf { it.isNotBlank() }
        val summary = when {
            pkg != null && type == RiskType.TIME_LIMIT_EXCEEDED -> "$pkg 当日时长已用完"
            pkg != null -> "$pkg 被限制使用"
            type == RiskType.TIME_LIMIT_EXCEEDED -> "应用当日时长已用完"
            type == RiskType.UNINSTALL_ATTEMPT -> "尝试卸载管控"
            type == RiskType.FORCE_STOP_ATTEMPT -> "尝试强行停止管控"
            type == RiskType.CLEAR_DATA_ATTEMPT -> "尝试清除管控数据"
            else -> "管控事件：${riskTypeLabel(type)}"
        }
        return RecentBlock(
            summary = summary,
            timestamp = timestamp,
            source = RecentBlock.Source.RISK
        )
    }

    /** 展示用中文名，避免把内部枚举名直接甩给用户 */
    private fun riskTypeLabel(type: RiskType): String = when (type) {
        RiskType.BLACKLIST_APP_LAUNCH -> "黑名单应用被阻止"
        RiskType.BLOCKED_URL_ACCESS -> "违规网址被拦截"
        RiskType.TIME_LIMIT_EXCEEDED -> "当日时长已用完"
        RiskType.UNINSTALL_ATTEMPT -> "尝试卸载管控"
        RiskType.FORCE_STOP_ATTEMPT -> "尝试强行停止管控"
        RiskType.CLEAR_DATA_ATTEMPT -> "尝试清除管控数据"
        RiskType.CLOCK_TAMPERING -> "检测到修改系统时间"
        RiskType.ROOT_DETECTED -> "检测到设备已 root"
        RiskType.DEVELOPER_OPTIONS_ENABLED -> "检测到开发者选项被打开"
        RiskType.USB_DEBUG_ENABLED -> "检测到 USB 调试被打开"
        RiskType.PERMISSION_REVOKED -> "管控权限被削弱"
        RiskType.POLICY_APPLY_FAILED -> "部分管控项需重新下发"
        RiskType.INVALID_SIGNATURE -> "指令签名校验失败"
        RiskType.HEARTBEAT_TIMEOUT -> "与服务端通信超时"
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
