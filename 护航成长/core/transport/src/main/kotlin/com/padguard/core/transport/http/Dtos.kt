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

@Serializable
data class MediaAck(
    @SerialName("taskId") val taskId: String = "",
    @SerialName("accepted") val accepted: Boolean = false
)

/** §5.12 头像上传回执：服务端返回可访问的 URL */
@Serializable
data class AvatarAck(
    @SerialName("url") val url: String = "",
    @SerialName("accepted") val accepted: Boolean = false
)

/** §5.13 孩子端自定义个人资料请求体（姓名 / 昵称 / 头像 URL 独立可选） */
@Serializable
data class DeviceProfileRequest(
    @SerialName("childName") val childName: String? = null,
    @SerialName("childNickname") val childNickname: String? = null,
    @SerialName("childAvatar") val childAvatar: String? = null
)

// ==================== 应用台账 ====================

/**
 * 单条已安装应用信息。
 *
 * [isSystem] 用于让管控端把"系统组件"与"用户安装的应用"分开呈现 ——
 * 家长关心的是孩子自己装了什么，把几百个系统服务混在列表里只会让页面不可用。
 */
@Serializable
data class AppInventoryItem(
    @SerialName("packageName") val packageName: String,
    @SerialName("appName") val appName: String? = null,
    @SerialName("versionName") val versionName: String? = null,
    @SerialName("versionCode") val versionCode: Long? = null,
    @SerialName("isSystem") val isSystem: Boolean? = null,
    @SerialName("installTime") val installTime: Long? = null,
    @SerialName("updateTime") val updateTime: Long? = null,
    /**
     * 应用图标（48dp 见方的 PNG，Base64）。
     *
     * 家长端"今日使用情况"要按图标辨认应用——一堆只有文字的列表里，
     * 家长要认出"com.tencent.mm 是微信"得靠猜。
     *
     * 为什么不传 URL 而传内联 Base64：图标属于设备本地资产，孩子端没有可对外访问的图床，
     * 先传图再传清单会引入"图传成功、清单失败"的不一致态。
     * 48dp PNG 压缩后约 1~3KB，一次全量同步（约 100 个应用）约 200KB，30 分钟一次完全可以接受。
     * 服务端按内容哈希判断是否变化，没变就不重复落盘。
     */
    @SerialName("iconBase64") val iconBase64: String? = null,
    /**
     * 是否已被管控端隐藏（家长关闭了使用权限）。
     * 隐藏的应用没有启动入口，但仍是已安装状态，不能与"已卸载"混为一谈。
     */
    @SerialName("hidden") val hidden: Boolean? = null
)

@Serializable
data class AppInventoryRequest(
    @SerialName("apps") val apps: List<AppInventoryItem>
)

/** 服务端返回的同步统计，仅用于日志与问题定位，不参与业务判断 */
@Serializable
data class AppSyncAck(
    @SerialName("inserted") val inserted: Int = 0,
    @SerialName("updated") val updated: Int = 0,
    @SerialName("removed") val removed: Int = 0
)
