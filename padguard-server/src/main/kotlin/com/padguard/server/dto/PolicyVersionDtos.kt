package com.padguard.server.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * 第二步「功能补齐」：策略版本历史、回滚、批量下发、定时生效的请求/响应契约。
 *
 * 设计口径（见 docs/第2步-阶段报告.md）：
 * - 回滚**不是**把旧版本号重新下发给设备（孩子端 `PolicyPackage.mergeWith` 只接受更高版本，
 *   低版本包会被静默丢弃），而是「取历史快照的内容 + 分配一个更高的新版本号」。
 * - 回滚行在 `PolicyExtensionService.ensureRebuilt` 中跳过子表自愈，否则回滚写入的包
 *   会在设备下一次拉策略时被立刻重建覆盖，表现为「回滚成功但设备没变」。
 * - 任何一次家长主动改设置会走 `rebuildPolicy` 写 NORMAL 行，自动退出回滚态。
 */

/** 策略版本回滚 / 定时生效窗口 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RollbackPolicyRequest(
    val targetVersion: Int = 0,
    /** 受信毫秒时间戳；null / <=0 表示不设置窗口（沿用目标版本原有窗口） */
    val effectiveFrom: Long? = null,
    val effectiveTo: Long? = null
)

/** 单独设置 / 清除定时生效窗口（不改策略内容） */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PolicyScheduleRequest(
    /** null 或 <=0 表示清除该端窗口 */
    val effectiveFrom: Long? = null,
    val effectiveTo: Long? = null
)

/** 批量重新发布每台设备的当前策略（把子表配置重新压成新版本并通知设备立即拉取） */
@JsonIgnoreProperties(ignoreUnknown = true)
data class BatchPublishRequest(val deviceIds: List<String> = emptyList())

/** 批量下发管控指令 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class BatchCommandRequest(
    val deviceIds: List<String> = emptyList(),
    val type: String = "",
    val payload: Map<String, Any?>? = null
)

/** 单个策略版本的摘要（不含包体，避免列表接口把历史大包一次性拉出来） */
data class PolicyVersionItem(
    val version: Int,
    val updatedAt: Long,
    val source: String,
    val effectiveFrom: Long?,
    val effectiveTo: Long?,
    val current: Boolean,
    /** 该版本是回滚产物时，记录被回滚到的历史版本号；否则为 null */
    val rollbackOf: Int?
)

data class PolicyVersionList(
    val deviceId: String,
    val currentVersion: Int,
    val rollbackActive: Boolean,
    val versions: List<PolicyVersionItem>
)

/** 回滚结果。`rollbackActive=true` 时家长端必须提示「当前处于回滚态，设置页显示的是回滚前配置」 */
data class RollbackResult(
    val deviceId: String,
    val version: Int,
    val rolledBackTo: Int,
    val rollbackActive: Boolean = true,
    val effectiveFrom: Long? = null,
    val effectiveTo: Long? = null,
    val idempotent: Boolean = false
)

/** 批量操作逐设备结果；失败项不泄漏他人设备信息，统一返回可读原因 */
data class BatchDeviceResult(
    val deviceId: String,
    val ok: Boolean,
    val message: String? = null,
    val version: Int? = null,
    val msgId: String? = null
)

data class BatchResult(
    val total: Int,
    val succeeded: Int,
    val failed: Int,
    val results: List<BatchDeviceResult>
)
