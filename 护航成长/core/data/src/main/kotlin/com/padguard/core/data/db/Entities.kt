package com.padguard.core.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 策略包本地缓存。
 * 单表单行（id 恒为 [SINGLETON_ID]），payload 为 AES 加密后的策略 JSON，
 * 防止用户通过 root 或 adb backup 直接读取/篡改管控规则。
 */
@Entity(tableName = "policy")
data class PolicyEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val version: Int,
    val encryptedJson: String,
    val updatedAt: Long,
    /** 来源标记：REMOTE / DEFAULT，便于排查"策略为何是默认值" */
    val source: String
) {
    companion object {
        const val SINGLETON_ID = 0
        const val SOURCE_REMOTE = "REMOTE"
        const val SOURCE_DEFAULT = "DEFAULT"
    }
}

/**
 * 行为日志队列。
 * 断网期间持续堆积（心跳里的 pendingLogCount 即此表未上传条数），
 * 联网后按批次上报，服务端按 logId 幂等去重。
 */
@Entity(
    tableName = "behavior_log",
    indices = [Index(value = ["logId"], unique = true), Index(value = ["uploaded"])]
)
data class BehaviorLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val logId: String,
    val type: String,
    val timestamp: Long,
    val payloadJson: String,
    val uploaded: Boolean = false
)

/**
 * 指令执行记录。
 * 用于本地审计、失败重试，以及向服务端回执。
 */
@Entity(tableName = "command_record", indices = [Index(value = ["receivedAt"])])
data class CommandRecordEntity(
    @PrimaryKey val msgId: String,
    val type: String,
    val receivedAt: Long,
    val executedAt: Long?,
    val status: String,
    val retryCount: Int = 0,
    val errorMessage: String? = null
)

/**
 * 单应用当日使用统计。
 *
 * 注意：统计基准为 [android.os.SystemClock.elapsedRealtime] 的累计增量，
 * 与墙钟无关，因此修改系统时间无法清零或缩短已用时长。
 */
@Entity(tableName = "app_usage", primaryKeys = ["packageName", "dayKey"])
data class AppUsageEntity(
    val packageName: String,
    val dayKey: String,
    val usedMs: Long,
    val launchCount: Int,
    val lastUpdateAt: Long
)

/**
 * 当日全局亮屏使用时长，同样基于单调时钟累计，跨进程共享。
 */
@Entity(tableName = "daily_usage")
data class DailyUsageEntity(
    @PrimaryKey val dayKey: String,
    val totalMs: Long,
    val lastUpdateAt: Long
)

/**
 * 待上报的高危告警事件。
 * 独立于普通日志队列，保证优先上传（对应契约 §7 同步优先级 P1）。
 */
@Entity(tableName = "risk_event", indices = [Index(value = ["eventId"], unique = true)])
data class RiskEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: String,
    val type: String,
    val level: String,
    val timestamp: Long,
    val detailJson: String,
    val uploaded: Boolean = false
)

/**
 * 临时解锁申请（孩子端发起 → 家长审批）。
 *
 * 为什么要本地建表而不是"发出去就忘"：
 * 断网时申请必须能先落盘、联网后补发；而且孩子需要看到"申请中/已同意/已拒绝"的状态，
 * 否则会反复重复提交。状态机由本地 + 服务端共同推进：
 * - 本地提交后 [status] = PENDING，随行为日志队列上报；
 * - 家长同意 → 服务端下发 [com.padguard.core.data.model.CommandType.TEMP_UNLOCK]
 *   → 指令执行器把最近一条 PENDING 置为 APPROVED；
 * - 超过 [STALE_HOURS] 仍无结果 → 本地置 EXPIRED，允许重新申请。
 */
@Entity(
    tableName = "unlock_request",
    indices = [Index(value = ["requestId"], unique = true), Index(value = ["status"])]
)
data class UnlockRequestEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val requestId: String,
    /** 申请放行的应用包名；整机申请（时段/总时长被锁）时为空串 */
    val packageName: String,
    /** 展示用应用名，避免详情页再查 PackageManager */
    val appLabel: String,
    val reasonText: String,
    val durationMinutes: Int,
    val createdAt: Long,
    /** PENDING / APPROVED / REJECTED / EXPIRED */
    val status: String,
    val decidedAt: Long? = null,
    val uploaded: Boolean = false
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_APPROVED = "APPROVED"
        const val STATUS_REJECTED = "REJECTED"
        const val STATUS_EXPIRED = "EXPIRED"

        /** 超过该时长仍未审批则视为失效，防止 PENDING 永久占位堵住再次申请 */
        const val STALE_HOURS = 12
    }
}

/**
 * 使用授权协议签署记录（说明书 §5.4 合规留存 / §10.1）。
 *
 * 孩子在「同意并授权」后写入；管控端可据此展示「已阅已同意」。
 * [snMask] 仅存脱敏序列号后 4 位，原始 SN 不出端。
 */
@Entity(tableName = "agreement", indices = [Index(value = ["signedAt"])])
data class AgreementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val version: String,
    val signedAt: Long,
    val snMask: String,
    val deviceId: String = ""
)
