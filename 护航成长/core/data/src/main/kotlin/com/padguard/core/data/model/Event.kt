package com.padguard.core.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 行为日志（对应接口契约 §5.4）。
 * 本地落库时以 [logId] 做幂等，服务端重复上报安全。
 */
@Serializable
data class BehaviorLog(
    @SerialName("logId") val logId: String,
    @SerialName("type") val type: LogType,
    @SerialName("timestamp") val timestamp: Long,
    @SerialName("payload") val payload: Map<String, String> = emptyMap(),
    /** 是否已成功上报 */
    val uploaded: Boolean = false
)

@Serializable
enum class LogType {
    APP_USAGE,
    APP_INSTALL,
    APP_UNINSTALL,
    URL_VISIT,
    URL_BLOCKED,
    SCREEN_ON_OFF,
    PERIPHERAL_CHANGE,
    SETTING_CHANGE_ATTEMPT,
    COMMAND_EXEC,
    DEVICE_BOOT,
    LOCATION,

    /**
     * 孩子端发起的临时解锁申请（契约扩展项，V1.1 未定义）。
     *
     * 复用日志队列而不是新开一个接口：申请本质上就是一条"需要送达服务端且允许延迟"的上行记录，
     * 日志队列已经解决了离线堆积、批量上报、logId 幂等这三件事，
     * 再造一套 pending 队列纯属重复劳动。服务端识别到该 type 后转为待审批工单即可。
     * payload 约定：{ requestId, packageName, appLabel, durationMinutes, reason }
     */
    UNLOCK_REQUEST,

    /**
     * 使用授权协议签署（说明书 §5.4 合规留存）。
     * 孩子点击「同意并授权」后写入，服务端据此向管控端展示「已阅已同意」。
     * payload 约定：{ version, signedAt, snMask }
     */
    AGREEMENT
}

/**
 * 风险告警事件（对应接口契约 §4.4）。
 * HIGH 级事件走 MQTT QoS1 优先上报，不受批量日志窗口限制。
 */
@Serializable
data class RiskEvent(
    @SerialName("eventId") val eventId: String,
    @SerialName("deviceId") val deviceId: String,
    @SerialName("type") val type: RiskType,
    @SerialName("level") val level: RiskLevel,
    @SerialName("timestamp") val timestamp: Long,
    @SerialName("detail") val detail: Map<String, String> = emptyMap()
)

@Serializable
enum class RiskType {
    UNINSTALL_ATTEMPT,
    FORCE_STOP_ATTEMPT,
    CLEAR_DATA_ATTEMPT,
    CLOCK_TAMPERING,
    ROOT_DETECTED,
    DEVELOPER_OPTIONS_ENABLED,
    USB_DEBUG_ENABLED,
    BLACKLIST_APP_LAUNCH,
    BLOCKED_URL_ACCESS,
    TIME_LIMIT_EXCEEDED,
    PERMISSION_REVOKED,
    POLICY_APPLY_FAILED,
    INVALID_SIGNATURE,
    HEARTBEAT_TIMEOUT
}

@Serializable
enum class RiskLevel { HIGH, NORMAL, INFO }

/** 心跳包（对应接口契约 §4.3） */
@Serializable
data class Heartbeat(
    @SerialName("deviceId") val deviceId: String,
    @SerialName("timestamp") val timestamp: Long,
    @SerialName("policyVersion") val policyVersion: Int,
    @SerialName("transportMode") val transportMode: String,
    @SerialName("controlMode") val controlMode: String,
    @SerialName("network") val network: NetworkInfo,
    @SerialName("battery") val battery: BatteryInfo,
    @SerialName("storage") val storage: StorageInfo,
    @SerialName("memory") val memory: MemoryInfo,
    @SerialName("screen") val screen: ScreenInfo,
    @SerialName("foregroundApp") val foregroundApp: String,
    @SerialName("location") val location: LocationInfo? = null,
    @SerialName("pendingLogCount") val pendingLogCount: Int
)

@Serializable
data class NetworkInfo(
    @SerialName("type") val type: String,
    @SerialName("ssid") val ssid: String = "",
    @SerialName("signalLevel") val signalLevel: Int = 0,
    @SerialName("ip") val ip: String = ""
)

@Serializable
data class BatteryInfo(
    @SerialName("level") val level: Int,
    @SerialName("charging") val charging: Boolean,
    @SerialName("temperature") val temperature: Float = 0f
)

@Serializable
data class StorageInfo(
    @SerialName("totalMb") val totalMb: Long,
    @SerialName("availableMb") val availableMb: Long
)

@Serializable
data class MemoryInfo(
    @SerialName("totalMb") val totalMb: Long,
    @SerialName("availableMb") val availableMb: Long
)

@Serializable
data class ScreenInfo(
    @SerialName("on") val on: Boolean,
    @SerialName("brightness") val brightness: Int = -1
)

@Serializable
data class LocationInfo(
    @SerialName("lat") val lat: Double,
    @SerialName("lng") val lng: Double,
    @SerialName("accuracy") val accuracy: Float = 0f,
    @SerialName("ts") val ts: Long,
    @SerialName("provider") val provider: String = ""
)

/** 绑定响应（对应接口契约 §2.2） */
@Serializable
data class BindResult(
    @SerialName("deviceId") val deviceId: String,
    @SerialName("deviceToken") val deviceToken: String,
    @SerialName("mqttUsername") val mqttUsername: String,
    @SerialName("mqttPassword") val mqttPassword: String,
    @SerialName("hmacSecret") val hmacSecret: String,
    @SerialName("expiresAt") val expiresAt: Long = 0L,
    /**
     * 可选：服务端指定的接入点（契约 V1.1 新增，缺省沿用构建期配置）。
     * 私有化部署场景下同一个 APK 要能对接不同学校的服务器，
     * 由绑定响应下发接入点可避免为改域名重新发包。
     */
    @SerialName("baseUrl") val baseUrl: String = "",
    @SerialName("mqttBroker") val mqttBroker: String = "",
    /** 可选：多租户隔离标识，用于 MQTT topic 前缀（契约 §9.2 待服务端确认） */
    @SerialName("tenantId") val tenantId: String = ""
)

/** 单应用当日使用统计，dayKey 为 yyyyMMdd */
@Serializable
data class AppUsageStat(
    val packageName: String,
    val dayKey: String,
    val usedMs: Long,
    val launchCount: Int,
    val lastUpdateAt: Long
)
