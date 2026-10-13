package com.padguard.core.data.repository

import com.padguard.core.common.Logger
import com.padguard.core.data.db.CommandRecordDao
import com.padguard.core.data.db.CommandRecordEntity
import com.padguard.core.data.model.AckStatus
import com.padguard.core.data.model.CommandType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 指令仓库：负责指令**幂等去重**与执行留痕。
 *
 * 防重放三道关（对应接口契约 §4.1）：
 * 1. [isDuplicate] —— msgId 命中历史窗口（最近 500 条）即判重
 * 2. 时效校验由调用方基于 Command.expiresAt 执行
 * 3. 签名校验由调用方基于 Command.signature 执行
 *
 * 幂等窗口持久化在本地数据库，因此设备重启后依然有效，
 * 避免了"重启后重放旧指令"的绕过路径。
 */
@Singleton
class CommandRepository @Inject constructor(
    private val dao: CommandRecordDao
) {

    companion object {
        /** 幂等窗口大小：保留最近 500 条 msgId */
        const val IDEMPOTENT_WINDOW = 500
        /** 记录保留时长：7 天 */
        private const val RETENTION_MS = 7 * 24 * 60 * 60 * 1000L
    }

    private val mutex = Mutex()

    suspend fun isDuplicate(msgId: String): Boolean = mutex.withLock {
        dao.get(msgId) != null
    }

    /**
     * 登记指令（在处理前调用，抢占幂等标记）。
     * @return false 表示已处理过，调用方应回执 DUPLICATED 并终止
     *
     * W51 修复：认领阶段只写 [AckStatus.PENDING] 中间态，[recordResult] 才置终态（SUCCESS/FAILED/...）。
     * 旧实现直接写 SUCCESS，进程在"认领→执行"之间崩溃会导致：本地记录已是成功、但服务端重发被幂等窗口判重丢弃，
     * 指令静默丢失却显示"已成功"。配合 [resetInFlightOnStartup] 清理残留 PENDING，保证崩溃的指令可被重发重放。
     */
    suspend fun tryClaim(
        msgId: String,
        type: CommandType,
        receivedAt: Long = System.currentTimeMillis()
    ): Boolean = mutex.withLock {
        if (dao.get(msgId) != null) return false
        dao.upsert(
            CommandRecordEntity(
                msgId = msgId,
                type = type.name,
                receivedAt = receivedAt,
                executedAt = null,
                status = AckStatus.PENDING.name
            )
        )
        true
    }

    /**
     * 启动清理（W51）：删除所有残留 [AckStatus.PENDING] 中间态记录。
     *
     * 这些记录意味着"认领了但没执行完就退出了"（进程崩溃 / 设备重启）。
     * 不清的话服务端在 7 天幂等窗口内重发会被 tryClaim 判重丢弃，指令永久丢失。
     * 清除后服务端重发会重新认领并执行，保证最终一致。
     */
    suspend fun resetInFlightOnStartup() {
        runCatching { dao.deleteByStatus(AckStatus.PENDING.name) }
            .onFailure { Logger.w(TAG) { "resetInFlightOnStartup failed: ${it.message}" } }
    }
    private val TAG = "CommandRepository"

    suspend fun recordResult(
        msgId: String,
        status: AckStatus,
        executedAt: Long = System.currentTimeMillis(),
        errorMessage: String? = null,
        retryCount: Int = 0
    ) {
        val existing = dao.get(msgId) ?: run {
            Logger.w("CommandRepository") { "result for unknown command: $msgId" }
            return
        }
        dao.upsert(
            existing.copy(
                executedAt = executedAt,
                status = status.name,
                errorMessage = errorMessage,
                retryCount = retryCount
            )
        )
    }

    suspend fun pendingRetryCandidates(): List<CommandRecordEntity> =
        dao.recentIds().let { ids ->
            ids.mapNotNull { dao.get(it) }
                .filter { it.status == AckStatus.FAILED.name && it.retryCount < 3 }
        }

    suspend fun purgeExpired() {
        dao.deleteBefore(System.currentTimeMillis() - RETENTION_MS)
    }
}
