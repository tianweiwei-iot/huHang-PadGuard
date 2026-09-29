package com.padguard.server.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

// ============ 控制端（家长） ============
data class LoginPasswordRequest(val phone: String, val password: String)
data class LoginSmsRequest(val phone: String, val smsCode: String)
data class SendSmsRequest(val phone: String)

data class UserDto(
    val id: String, val phone: String, val nickname: String?,
    val avatar: String?, val role: String, val sceneType: String
)

data class LoginResponse(val user: UserDto, val token: String, val refreshToken: String)
data class TokenResponse(val token: String, val refreshToken: String)

data class BindCodeResponse(val bindCode: String, val expiresAt: Long)

data class DeviceDto(
    val id: String, val name: String?, val deviceId: String, val model: String?,
    val osVersion: String?, val appVersion: String?, val onlineStatus: String,
    val lastOnlineTime: Long?, val batteryLevel: Int?, val controlMode: String?,
    val sceneMode: String?, val groupId: String?, val groupName: String?,
    val sceneType: String?, val latitude: Double?, val longitude: Double?,
    /** 家长是否仍处于远程锁屏状态；驱动家长端「锁屏 / 解锁」按钮的切换 */
    val remoteLocked: Boolean = false,
    /** 孩子端自定义资料：姓名 */
    val childName: String? = null,
    /** 孩子端自定义资料：昵称 */
    val childNickname: String? = null,
    /** 孩子端自定义资料：头像 URL */
    val childAvatar: String? = null
)

data class LockRequest(val reason: String? = null)
data class ModeRequest(val mode: String)

/** 限时解锁：durationMinutes 到点后自动恢复管控；packageName 留空表示整机放行 */
data class TempUnlockRequest(
    val durationMinutes: Int = 30,
    val packageName: String? = null,
    val reason: String? = null
)

data class CommandDto(
    val msgId: String, val type: String, val status: String,
    val payload: Map<String, Any?>?, val createdAt: Long, val executedAt: Long?
)

// ============ 被管控端（孩子） ============
@JsonIgnoreProperties(ignoreUnknown = true)
data class BindRequest(
    val bindCode: String,
    val deviceSn: String? = null,
    val fingerprint: String? = null,
    val model: String? = null,
    val brand: String? = null,
    val androidVersion: String? = null,
    val sdkInt: Int? = null,
    val controlMode: String? = null,
    val appVersion: String? = null
)

/**
 * 「账号密码绑定」请求：孩子端直接输入家长的手机号 + 密码完成绑定，
 * 供不方便扫码 / 配对码已过期的场景使用（说明书 §4.3 方式④）。
 *
 * 设备信息复用 [BindRequest]（`req` 字段），避免两处字段定义漂移。
 */
data class BindByAccountRequest(
    val phone: String,
    val password: String,
    val req: BindRequest
)

/** 孩子端自定义设备名（凭设备令牌鉴权） */
data class DeviceNameRequest(val name: String)

/**
 * 孩子端自定义个人资料（凭设备令牌鉴权）。
 * 各字段独立可选：传 null / 空串表示不修改该项，便于孩子端逐项设置。
 */
data class DeviceProfileRequest(
    val childName: String? = null,
    val childNickname: String? = null,
    val childAvatar: String? = null
)

data class BindResult(
    val deviceId: String, val deviceToken: String, val mqttUsername: String,
    val mqttPassword: String, val hmacSecret: String, val expiresAt: Long
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LogItem(
    val logId: String, val type: String? = null, val timestamp: Long? = null,
    val payload: Map<String, Any?>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LogsRequest(val deviceId: String, val logs: List<LogItem>)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LocationPoint(
    val lat: Double? = null, val lng: Double? = null, val accuracy: Double? = null,
    val ts: Long? = null, val provider: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LocationsRequest(val deviceId: String, val points: List<LocationPoint>)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HeartbeatDto(
    val policyVersion: Int? = null,
    val transportMode: String? = null,
    val controlMode: String? = null,
    val battery: Map<String, Any?>? = null,
    val location: Map<String, Any?>? = null,
    val network: Map<String, Any?>? = null
)

// ============ MQTT 数据包 ============
@JsonIgnoreProperties(ignoreUnknown = true)
data class CommandPacket(
    val msgId: String, val type: String, val version: Int = 1,
    val timestamp: Long, val expiresAt: Long, val priority: String,
    val payload: Map<String, Any?>?, val signature: String
)

/**
 * 指令回执。
 *
 * **所有字段必须给默认值**：孩子端实际上传的是包装体 `{deviceId, acks:[...]}`，
 * 历史上这里按"裸 AckPacket"强解析，`msgId` 拿不到就抛
 * `MissingKotlinParameterException` → 整个 ack 接口 500。
 * 后果是**每一条指令的回执都失败**：服务端指令永远停在"已下发"，
 * 家长端表现为"锁屏 / 录屏 / 截屏指令发送失败"，而孩子端其实已经执行成功 ——
 * 这是最典型的一类"看起来是端的问题、实际是协议不匹配"的故障。
 *
 * 字段给默认值 + [JsonIgnoreProperties] 后，单条与批量两种载荷都能安全解析。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AckPacket(
    val msgId: String = "", val deviceId: String = "", val status: String = "",
    val executedAt: Long? = null, val errorCode: String? = null,
    val errorMessage: String? = null, val retryCount: Int = 0
)

data class EventPacket(
    val eventId: String, val deviceId: String, val type: String,
    val level: String, val timestamp: Long, val detail: Map<String, Any?>? = null
)
