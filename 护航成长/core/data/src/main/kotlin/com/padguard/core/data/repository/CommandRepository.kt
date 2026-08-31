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
                status = AckStatus.SUCCESS.name
            )
        )
        true
    }

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
