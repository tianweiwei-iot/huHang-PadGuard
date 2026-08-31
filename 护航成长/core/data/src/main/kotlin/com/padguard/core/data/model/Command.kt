package com.padguard.core.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 服务端下发的远程指令（对应接口契约 §4.1）。
 *
 * 安全校验（在终端侧强制执行，任一失败即丢弃并记录告警）：
 * 1. [expiresAt] 早于当前时间 → 防重放
 * 2. [signature] HMAC-SHA256 校验失败 → 防伪造
 * 3. [msgId] 重复 → 幂等丢弃，但仍回执 DUPLICATED
 */
@Serializable
data class Command(
    @SerialName("msgId") val msgId: String,
    @SerialName("type") val type: CommandType,
    @SerialName("version") val version: Int = 1,
    @SerialName("timestamp") val timestamp: Long = 0L,
    @SerialName("expiresAt") val expiresAt: Long = 0L,
    @SerialName("priority") val priority: Priority = Priority.NORMAL,
    @SerialName("payload") val payload: Map<String, String> = emptyMap(),
    @SerialName("signature") val signature: String = ""
) {
    /** 参与签名的规范化字符串，字段顺序必须与服务端一致 */
    fun signingPayload(): String = "$msgId|$type|$timestamp|${canonicalJson()}"

    private fun canonicalJson(): String =
        payload.entries.sortedBy { it.key }.joinToString(",", "{", "}") { "\"${it.key}\":\"${it.value}\"" }

    fun payloadString(key: String, default: String = ""): String = payload[key] ?: default
    fun payloadInt(key: String, default: Int = 0): Int = payload[key]?.toIntOrNull() ?: default
    fun payloadLong(key: String, default: Long = 0L): Long = payload[key]?.toLongOrNull() ?: default
    fun payloadBoolean(key: String, default: Boolean = false): Boolean =
        payload[key]?.toBooleanStrictOrNull() ?: default

    enum class Priority { HIGH, NORMAL, LOW }
}

@Serializable
enum class CommandType {
    /** 远程立即锁屏 */
    LOCK_SCREEN,

    /** 解除锁屏 */
    UNLOCK,

    /** 限时解锁：payload 中的 durationMinutes 到点后自动恢复管控 */
    TEMP_UNLOCK,

    /** 设备重启（需 Device Owner） */
    REBOOT,

    /** 关机（需 Device Owner） */
    SHUTDOWN,

    /** 全屏消息推送 */
    SHOW_MESSAGE,

    /** 远程截屏 */
    SCREENSHOT,

    /** 立即上报一次定位 */
    LOCATE,

    /** 策略一键重置：终端丢弃本地缓存并重新拉取全量 */
    RESET_POLICY,

    /** 强制刷新策略（等价于收到 policy 通知） */
    REFRESH_POLICY,

    /** 静默安装应用：payload { apkUrl, packageName, versionCode } */
    INSTALL_APP,

    /** 静默卸载应用：payload { packageName } */
    UNINSTALL_APP,

    /** 临时挂起/恢复应用：payload { packageName, suspended } */
    SET_APP_SUSPENDED,

    /** 屏幕水印启停：payload { enabled, content } */
    SET_WATERMARK,

    /** 外设权限临时放行：payload { item, enabled } */
    PERIPHERAL_OVERRIDE,

    /** 运行参数调整：payload { heartbeatIntervalSec, transportMode, ... } */
    UPDATE_CONFIG,

    /** 立即上报本地缓存日志 */
    FLUSH_LOGS,

    /** 恢复出厂（高危，需 Device Owner + 二次确认） */
    WIPE
}

/** 指令执行回执（对应接口契约 §4.2） */
@Serializable
data class CommandAck(
    @SerialName("msgId") val msgId: String,
    @SerialName("deviceId") val deviceId: String,
    @SerialName("status") val status: AckStatus,
    @SerialName("executedAt") val executedAt: Long,
    @SerialName("errorCode") val errorCode: String? = null,
    @SerialName("errorMessage") val errorMessage: String? = null,
    @SerialName("retryCount") val retryCount: Int = 0
)

@Serializable
enum class AckStatus {
    SUCCESS,

    /** 执行失败，可重试 */
    FAILED,

    /** 当前权限模式不支持该指令（如降级模式下无法静默安装） */
    UNSUPPORTED,

    /** 幂等命中，已丢弃 */
    DUPLICATED,

    /** 签名或时效校验失败 */
    REJECTED
}

/** 指令执行记录，用于本地审计与失败重试 */
@Serializable
data class CommandRecord(
    val msgId: String,
    val type: CommandType,
    val receivedAt: Long,
    val executedAt: Long? = null,
    val status: AckStatus,
    val retryCount: Int = 0,
    val errorMessage: String? = null
)
