package com.padguard.core.data.repository

import com.padguard.core.common.Logger
import com.padguard.core.data.db.UnlockRequestDao
import com.padguard.core.data.db.UnlockRequestEntity
import com.padguard.core.data.model.LogType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 临时解锁申请仓库（对应说明书「违规拦截 → 申请放行」闭环）。
 *
 * ## 为什么申请要走"日志队列"而不是独立接口
 * 申请是一条允许延迟送达的上行记录，日志队列已经解决了离线堆积、批量上报、
 * logId 幂等三件事。这里的做法是"双写"：
 * 1. 写 `unlock_request` 表 —— 供本端 UI 展示状态（申请中 / 已同意 / 已拒绝）；
 * 2. 追加一条 [LogType.UNLOCK_REQUEST] 行为日志 —— 借现成通道送到服务端。
 *
 * 两张表用同一个 `requestId` 关联，服务端审批后下发 `TEMP_UNLOCK` 指令，
 * 指令执行器再调 [approveLatest] 把本地状态推进到 APPROVED。
 *
 * ## 防刷设计
 * [hasPendingRequest] 让 UI 在有在途申请时禁用提交按钮；
 * [expireStale] 在超过 [UnlockRequestEntity.STALE_HOURS] 后自动放行下一次申请，
 * 避免家长一直不处理导致孩子永远卡在"申请中"。
 */
@Singleton
class UnlockRequestRepository @Inject constructor(
    private val dao: UnlockRequestDao,
    private val logRepository: LogRepository
) {

    /**
     * 提交一次临时解锁申请。
     *
     * @param packageName 目标应用包名；整机放行（时段锁/总时长耗尽）传空串
     * @param appLabel 展示用应用名
     * @param reasonText 孩子填写的理由
     * @param durationMinutes 期望放行时长，会被夹到 [MIN_MINUTES]..[MAX_MINUTES]
     * @return 生成的 requestId
     */
    suspend fun submit(
        packageName: String,
        appLabel: String,
        reasonText: String,
        durationMinutes: Int
    ): String {
        val requestId = "ur-${UUID.randomUUID().toString().replace("-", "").take(20)}"
        val now = System.currentTimeMillis()
        val minutes = durationMinutes.coerceIn(MIN_MINUTES, MAX_MINUTES)

        dao.insert(
            UnlockRequestEntity(
                requestId = requestId,
                packageName = packageName,
                appLabel = appLabel.ifBlank { packageName },
                reasonText = reasonText.trim().take(MAX_REASON_CHARS),
                durationMinutes = minutes,
                createdAt = now,
                status = UnlockRequestEntity.STATUS_PENDING
            )
        )

        // 借日志队列上行：断网时会自然堆积，联网后随下一批日志一起送达。
        // 注意先落 unlock_request 再入队，顺序不能反 ——
        // 反了的话入队成功但本地无记录，孩子看不到"申请中"，只会以为没提上去而重复点。
        enqueueUplink(requestId, packageName, appLabel, reasonText, minutes, now)

        Logger.i(TAG) { "unlock request submitted: $requestId pkg=$packageName ${minutes}min" }
        return requestId
    }

    /**
     * 补偿：把落了库但没成功入队的申请重新塞进日志队列。
     *
     * 触发时机是"申请已写库、追加日志时进程被杀/DB 抛错"这种窄窗口。
     * 不做补偿的话这条申请永远不会到达家长，而孩子端却一直显示"申请中"，
     * 属于最难排查的一类问题，所以宁可多这一个由前台服务周期调用的兜底。
     *
     * @return 重新入队的条数
     */
    suspend fun requeueUnsent(limit: Int = 20): Int {
        val unsent = dao.pendingUpload(limit)
        unsent.forEach { entity ->
            enqueueUplink(
                requestId = entity.requestId,
                packageName = entity.packageName,
                appLabel = entity.appLabel,
                reasonText = entity.reasonText,
                minutes = entity.durationMinutes,
                timestamp = entity.createdAt
            )
        }
        if (unsent.isNotEmpty()) {
            Logger.i(TAG) { "requeued ${unsent.size} unsent unlock requests" }
        }
        return unsent.size
    }

    /**
     * 入队一条上行日志，成功后才把 `uploaded` 置 1。
     *
     * 这里 `uploaded` 的语义是"**已交付日志队列**"，不是"服务端已收到" ——
     * 真正的送达状态由 behavior_log 表自己跟踪，两级标记各管一段，不要混淆。
     */
    private suspend fun enqueueUplink(
        requestId: String,
        packageName: String,
        appLabel: String,
        reasonText: String,
        minutes: Int,
        timestamp: Long
    ) {
        logRepository.append(
            type = LogType.UNLOCK_REQUEST,
            payload = mapOf(
                "requestId" to requestId,
                "packageName" to packageName,
                "appLabel" to appLabel,
                "durationMinutes" to minutes.toString(),
                "reason" to reasonText.trim().take(MAX_REASON_CHARS)
            ),
            timestamp = timestamp
        )
        dao.markUploaded(listOf(requestId))
    }

    /** 申请列表（含历史），按时间倒序 */
    fun observeRecent(limit: Int = 20): Flow<List<UnlockRequest>> =
        dao.observeRecent(limit).map { list -> list.map { it.toModel() } }

    /** 是否存在在途申请，UI 用它禁用重复提交 */
    fun hasPendingRequest(): Flow<Boolean> =
        dao.observePendingCount().map { it > 0 }

    /**
     * 收到 `TEMP_UNLOCK` 指令时回填审批结果。
     *
     * 找不到匹配的在途申请属于**正常情况** —— 家长也可以不经申请主动放行，
     * 因此这里静默返回而不是报错。
     *
     * @param packageName 指令 payload 里的包名；为空表示整机放行，匹配任意在途申请
     */
    suspend fun approveLatest(packageName: String) {
        val target = dao.latestPending(packageName) ?: return
        dao.updateStatus(
            requestId = target.requestId,
            status = UnlockRequestEntity.STATUS_APPROVED,
            decidedAt = System.currentTimeMillis()
        )
        Logger.i(TAG) { "unlock request approved: ${target.requestId}" }
    }

    /** 家长明确拒绝（服务端可通过 SHOW_MESSAGE 附带 requestId 下发） */
    suspend fun reject(requestId: String) {
        dao.updateStatus(
            requestId = requestId,
            status = UnlockRequestEntity.STATUS_REJECTED,
            decidedAt = System.currentTimeMillis()
        )
    }

    /** 把超时未审批的申请置为失效，允许重新发起 */
    suspend fun expireStale() {
        val before = System.currentTimeMillis() - UnlockRequestEntity.STALE_HOURS * 60L * 60 * 1000
        dao.expireStale(before)
    }

    /** 清理历史申请记录（与日志保留策略一致） */
    suspend fun purge(retentionDays: Int) {
        dao.deleteBefore(System.currentTimeMillis() - retentionDays * 24L * 60 * 60 * 1000)
    }

    private fun UnlockRequestEntity.toModel() = UnlockRequest(
        requestId = requestId,
        packageName = packageName,
        appLabel = appLabel,
        reasonText = reasonText,
        durationMinutes = durationMinutes,
        createdAt = createdAt,
        status = when (status) {
            UnlockRequestEntity.STATUS_APPROVED -> UnlockRequest.Status.APPROVED
            UnlockRequestEntity.STATUS_REJECTED -> UnlockRequest.Status.REJECTED
            UnlockRequestEntity.STATUS_EXPIRED -> UnlockRequest.Status.EXPIRED
            else -> UnlockRequest.Status.PENDING
        },
        decidedAt = decidedAt
    )

    companion object {
        private const val TAG = "UnlockRequestRepo"

        const val MIN_MINUTES = 5
        const val MAX_MINUTES = 120
        private const val MAX_REASON_CHARS = 200
    }
}

/** 供 UI 消费的申请模型，屏蔽 Room entity 与字符串状态 */
data class UnlockRequest(
    val requestId: String,
    val packageName: String,
    val appLabel: String,
    val reasonText: String,
    val durationMinutes: Int,
    val createdAt: Long,
    val status: Status,
    val decidedAt: Long?
) {
    enum class Status { PENDING, APPROVED, REJECTED, EXPIRED }
}
