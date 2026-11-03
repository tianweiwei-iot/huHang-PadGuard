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

    /**
     * 旧签名格式（不含转义）。**仅用于验签回退**，不得用于签发。
     *
     * 服务端与终端曾经都用"直接拼 `"k":"v"`、不做任何转义"的方式构造 canonical 串。
     * 这种格式有歧义：值里含 `"` 或 `\` 时，不同 payload 可以拼出同一个 canonical 串
     * （例如值 `a","b` 与两个键拼出的结果撞车），签名因此不再是 payload 的函数。
     *
     * 现在两端都改为 [canonicalJson] 的转义版本。但两端**不可能同时升级**：
     *   服务端先升 → 旧 APK 按旧格式算，签名对不上，全部指令被拒（含锁屏）；
     *   终端先升  → 服务端仍按旧格式签，同样全拒。
     * 这条链路一旦断，家长看到的是"设备不响应"而非报错，排障代价极高。
     * 所以保留本方法一个版本周期：验签失败时按旧格式再试一次，成功后正常执行并记日志。
     */
    fun signingPayloadLegacy(): String = "$msgId|$type|$timestamp|${legacyCanonicalJson()}"

    /**
     * 规范化 payload：按键升序、值字符串化、标准 JSON 字符串转义。
     *
     * 与服务端 `CommandService.canonicalPayload` **逐字符一致**，任一侧改动都必须同步另一侧，
     * 否则所有指令的签名都对不上（见 [signingPayloadLegacy] 的说明）。
     */
    private fun canonicalJson(): String =
        payload.entries.sortedBy { it.key }
            .joinToString(",", "{", "}") { "\"${escape(it.key)}\":\"${escape(it.value)}\"" }

    /** 转义规则（两端一致）：`\` → `\\`，`"` → `\"`，控制字符 → `\u00xx`（小写四位十六进制） */
    internal fun escape(raw: String): String {
        if (raw.none { it == '\\' || it == '"' || it.code < 0x20 }) return raw
        return buildString(raw.length + 8) {
            raw.forEach { ch ->
                when {
                    ch == '\\' -> append("\\\\")
                    ch == '"' -> append("\\\"")
                    ch.code < 0x20 -> append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                    else -> append(ch)
                }
            }
        }
    }

    private fun legacyCanonicalJson(): String =
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

    /** 远程拍照（需摄像头采集） */
    PHOTO,

    /** 开始录音（需麦克风采集） */
    RECORD,

    /** 停止录音 */
    STOP_RECORD,

    /** 开始录屏（需 MediaProjection 授权） */
    SCREEN_RECORD,

    /** 停止录屏 */
    STOP_SCREEN_RECORD,

    /**
     * 开启实时看屏：孩子端持续把屏幕帧推到服务端，家长端拉流观看。
     * 与 SCREENSHOT（单张静态图）的区别：这是一条**持续的视频流**，
     * 孩子屏幕上滑动一下家长端同一秒就能看到，而不是等下一次轮询。
     */
    LIVE_VIEW_START,

    /** 停止实时看屏（关闭推流，省电省流量） */
    LIVE_VIEW_STOP,

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

    /**
     * 隐藏/显示应用（使用权限开关）：payload { packageName, hidden }
     *
     * 与 [SET_APP_SUSPENDED] 的区别：挂起的应用仍在桌面，只是点开会被系统拦截并提示"已停用"，
     * 孩子天天看到一个点不开的图标，既碍眼又等于在提示"这里有个被禁的应用"。
     * 隐藏则是让应用从桌面彻底消失，符合"没开启权限的应用不显示"的产品约定。
     */
    SET_APP_HIDDEN,

    /**
     * 权限管理：授予 / 收回某包名的运行时权限（DO/PO 才能改别人的权限）。
     * payload { packageName, permission, grant }。grant=true 一次性授予，false 收回（回归系统默认）。
     */
    SET_RUNTIME_PERMISSION,

    /**
     * 功能管理：整包下发设备功能开关，孩子端逐项映射成 DPM 用户限制 / 相机截屏开关等并应用。
     * payload 为扁平的 featureKey -> "true"/"false"（featureKey 见 DeviceAdminBridge.applyFeaturePolicy）。
     */
    SET_FEATURE_POLICY,

    /** 屏幕水印启停：payload { enabled, content } */
    SET_WATERMARK,

    /** 外设权限临时放行：payload { item, enabled } */
    PERIPHERAL_OVERRIDE,

    /** 运行参数调整：payload { heartbeatIntervalSec, transportMode, ... } */
    UPDATE_CONFIG,

    /**
     * 整机管控总开关：暂停 / 恢复设备管控。payload { active }（"true"/"false"）。
     * 暂停后孩子端释放设备——锁屏浮层不铺、无障碍拦截解除、DO 不回收权限、不钉 Kiosk，
     * 家长/操作员可自由进入系统设置。用于"取消应用管控后设备应可正常使用"的正确落地：
     * 释放设备必须由这个总开关驱动，而非只清空应用黑白名单。
     */
    SET_MANAGEMENT_ACTIVE,

    /** 立即上报本地缓存日志 */
    FLUSH_LOGS,

    /** 恢复出厂（高危，需 Device Owner + 二次确认） */
    WIPE,

    /**
     * 远程揭示管理控制台：payload {}（无参数）。
     *
     * 孩子端图标隐藏后，家长的进入通道有两条：悬浮卡密码闸（设备在手边时），
     * 以及本指令（设备不在手边 / 图标找不到时）——收到后点亮桌面图标并拉起管理台
     * （EXTRA_REVEAL 会话，配置结束自动重新隐藏）。它是「未设密码也允许隐藏图标」的
     * 前置安全网：只要家长端可达，就永远存在一条远程把图标要回来的路。
     */
    REVEAL_MANAGEMENT_UI
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
    /**
     * 已认领但尚未执行完毕的中间态。
     *
     * 认领阶段只写 PENDING，[recordResult] 才置终态（SUCCESS/FAILED/...）；
     * 若认领即写 SUCCESS，进程在"认领→执行"之间崩溃时本地已是 SUCCESS，而服务端因 7 天幂等窗口把重发指令判重丢弃，
     * 会导致指令静默丢失、本地却显示"已成功"。启动时会清理残留 PENDING 使其可被服务端重发重新认领。
     */
    PENDING,

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
