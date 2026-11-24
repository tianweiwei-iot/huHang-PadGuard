package com.padguard.server.dto

/**
 * 第四步合规闭环（数据主体权利）相关请求/响应 DTO。
 * 对应《个人信息保护法》撤回同意、查阅复制、删除/注销、申诉。
 */

/** 撤回 / 恢复授权：deviceIds 为空表示名下全部设备。 */
data class WithdrawConsentRequest(val deviceIds: List<String>? = null)

/**
 * 不满十四周岁监护人单独同意。
 * [childBirthDate] 用 ISO yyyy-MM-dd，仅用于年龄分档与留痕，不代表已做实名核验。
 */
data class Under14GuardianConsentRequest(
    val deviceId: String,
    val childBirthDate: String,
    val consent: Boolean,
    val consentTextVersion: String
)

/** 单独同意当前状态（供家长端回显，避免重复勾选却无凭证）。 */
data class GuardianConsentStatusDto(
    val deviceId: String,
    val childBirthDate: String? = null,
    val consented: Boolean = false,
    val consentTextVersion: String? = null,
    val decidedAt: Long? = null
)

/** 提交账号注销请求。 */
data class AccountDeletionRequestReq(val reason: String? = null)

/** 凭注销凭证确认 / 取消。 */
data class AccountDeletionConfirmReq(val ticket: String)
data class AccountDeletionCancelReq(val ticket: String)

/** 提交申诉。deviceId 为空表示不针对具体设备。 */
data class SubmitAppealRequest(
    val deviceId: String? = null,
    val subject: String,
    val detail: String? = null,
    val contact: String? = null
)

/** 注销请求创建响应（含冷静期与执行时刻）。 */
data class DeletionRequestCreatedDto(
    val ticket: String,
    val requestedAt: Long,
    val executeAt: Long,
    val cooldownMs: Long,
    val status: String
)

/** 申诉概览（不暴露处理人等内部字段）。 */
data class AppealDto(
    val id: String,
    val deviceId: String?,
    val subject: String,
    val detail: String?,
    val contact: String?,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long
)
