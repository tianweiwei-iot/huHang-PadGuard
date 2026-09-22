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
    val sceneType: String?, val latitude: Double?, val longitude: Double?
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
data class CommandPacket(
    val msgId: String, val type: String, val version: Int = 1,
    val timestamp: Long, val expiresAt: Long, val priority: String,
    val payload: Map<String, Any?>?, val signature: String
)

data class AckPacket(
    val msgId: String, val deviceId: String, val status: String,
    val executedAt: Long?, val errorCode: String? = null,
    val errorMessage: String? = null, val retryCount: Int = 0
)

data class EventPacket(
    val eventId: String, val deviceId: String, val type: String,
    val level: String, val timestamp: Long, val detail: Map<String, Any?>? = null
)
