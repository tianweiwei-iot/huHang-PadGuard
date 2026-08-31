package com.padguard.core.transport.http

import com.padguard.core.data.model.BehaviorLog
import com.padguard.core.data.model.LocationInfo
import com.padguard.core.data.model.RiskEvent
import com.padguard.core.data.model.policy.PolicyPackage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ==================== 绑定 ====================

/** 契约 §5.1 请求体 */
@Serializable
data class BindRequest(
    @SerialName("bindCode") val bindCode: String,
    @SerialName("deviceSn") val deviceSn: String = "",
    @SerialName("imei") val imei: String = "",
    @SerialName("fingerprint") val fingerprint: String,
    @SerialName("model") val model: String,
    @SerialName("brand") val brand: String,
    @SerialName("androidVersion") val androidVersion: String,
    @SerialName("sdkInt") val sdkInt: Int,
    @SerialName("resolution") val resolution: String,
    @SerialName("controlMode") val controlMode: String,
    @SerialName("appVersion") val appVersion: String,
    /** 鸿蒙兼容层设备上报 HarmonyOS 版本，便于服务端区分统计 */
    @SerialName("romInfo") val romInfo: String = ""
)

// ==================== 对时 ====================

@Serializable
data class TimeResponse(
    @SerialName("serverTime") val serverTime: Long
)

// ==================== 策略 ====================

/**
 * 策略拉取结果。
 *
 * 服务端在 `data` 里返回两种形态之一（契约 §5.3）：
 * - `{ "upToDate": true }`
 * - 完整策略包
 *
 * 之所以要保留 [rawJson]：策略包要 AES-GCM 加密原样落库，
 * 如果先反序列化再重新序列化，服务端后续新增的未知字段会在本地丢失，
 * 导致降级到旧版终端后策略语义被静默削弱。
 */
sealed interface PolicyFetchResult {
    data object UpToDate : PolicyFetchResult
    data class Updated(val policy: PolicyPackage, val rawJson: String) : PolicyFetchResult
}

// ==================== 日志 ====================

@Serializable
data class LogUploadRequest(
    @SerialName("deviceId") val deviceId: String,
    @SerialName("logs") val logs: List<BehaviorLog>
)

@Serializable
data class LogUploadResponse(
    @SerialName("accepted") val accepted: Int = 0,
    @SerialName("duplicated") val duplicated: Int = 0
)

@Serializable
data class EventUploadRequest(
    @SerialName("deviceId") val deviceId: String,
    @SerialName("events") val events: List<RiskEvent>
)

/** 契约 V1.1 §5.9：轮询模式下的指令回执批量上报 */
@Serializable
data class AckUploadRequest(
    @SerialName("deviceId") val deviceId: String,
    @SerialName("acks") val acks: List<com.padguard.core.data.model.CommandAck>
)

// ==================== 定位 ====================

@Serializable
data class LocationUploadRequest(
    @SerialName("deviceId") val deviceId: String,
    @SerialName("points") val points: List<LocationInfo>
)

// ==================== 心跳 ====================

/**
 * 心跳响应。
 *
 * [policyVersion] 让服务端可以在心跳响应里"顺带"告知最新策略版本号，
 * 终端发现本地版本落后即触发一次 HTTPS 全量拉取。
 * 这样即使 MQTT 的 policy 通知丢失，策略最迟也会在一个心跳周期内收敛，
 * 不需要额外的定时对账任务。
 */
@Serializable
data class HeartbeatAck(
    @SerialName("policyVersion") val policyVersion: Int = -1,
    @SerialName("pendingCommandCount") val pendingCommandCount: Int = 0,
    /** 服务端要求终端立即执行一次日志上报 */
    @SerialName("flushLogs") val flushLogs: Boolean = false
)

// ==================== 截图 ====================

@Serializable
data class ScreenshotAck(
    @SerialName("shotId") val shotId: String = "",
    @SerialName("accepted") val accepted: Boolean = false
)
