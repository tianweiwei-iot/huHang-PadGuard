package com.padguard.core.transport.http

import com.padguard.core.data.model.BehaviorLog
import com.padguard.core.data.model.Command
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

// ==================== 平板使用计时会话（三端时间一致） ====================

/**
 * 使用计时会话响应（与服务端 UsageSessionDto 字段一一对应）。
 *
 * 三端一律用 [serverNow] - [startEpoch] 计算已用时长，剩余 = (quotaMinutes + extendedMinutes) 分钟 - 已用，
 * 因此家长端 / 孩子端 / 服务端显示同一份数字，不再各自按本地时钟计算导致对不上。
 */
@Serializable
data class UsageSessionDto(
    @SerialName("deviceId") val deviceId: String = "",
    @SerialName("serverNow") val serverNow: Long = 0L,
    @SerialName("active") val active: Boolean = false,
    @SerialName("dayKey") val dayKey: String = "",
    @SerialName("startEpoch") val startEpoch: Long? = null,
    @SerialName("quotaMinutes") val quotaMinutes: Int = 0,
    @SerialName("extendedMinutes") val extendedMinutes: Int = 0,
    @SerialName("remainingMinutes") val remainingMinutes: Int = 0,
    @SerialName("remainingMs") val remainingMs: Long = 0L,
    @SerialName("countdownThresholdMs") val countdownThresholdMs: Long = 10 * 60_000L,
    @SerialName("delayOptions") val delayOptions: List<Int> = listOf(15, 30, 45, 60),
    @SerialName("maxDailyExtendMinutes") val maxDailyExtendMinutes: Int = 120,
    @SerialName("lastDelayEpoch") val lastDelayEpoch: Long? = null
)

/** 延时申请请求：minutes 必须落在 delayOptions 内 */
@Serializable
data class UsageDelayRequest(
    @SerialName("minutes") val minutes: Int = 0
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

// ==================== 指令轮询 ====================

/**
 * 指令轮询响应（契约 §5.7 扩充）。
 *
 * ## 为什么要在指令轮询里"顺带"回传 [policyVersion]
 * 孩子端走 HTTPS 轮询（MQTT 未启用），原本唯一的策略版本对账通道是**心跳回执**：
 * 心跳 30s 一次、且长轮询挂起时本地拿不到真实版本，最坏 reconciliation 延迟约 1 分钟。
 * 而指令轮询是**独立 3s 高频循环**（`COMMAND_PULL_INTERVAL_MS`），几乎恒定在线。
 * 把最新策略版本号搭在指令轮询响应里，终端发现本地版本落后即触发一次 HTTPS 全量
 * 拉取 —— 家长改完时间管控策略后，平板上限**最坏 3s 内**生效，不再受心跳节奏掣肘。
 *
 * 与 [HeartbeatAck.policyVersion] 同源（`deviceService.policyVersionOf`，唯一事实源是
 * `policies` 表最新版本号），两条通道口径完全一致，互不打架。
 *
 * [policyVersion] 默认 -1：旧服务端 / 解析失败时取 -1，`> 0` 判定自动跳过，避免误触发全量拉取。
 */
@Serializable
data class CommandsResponse(
    @SerialName("policyVersion") val policyVersion: Int = -1,
    @SerialName("commands") val commands: List<Command> = emptyList()
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
     * 本机**已生效**的授权状态回显：true = 家长已取消该应用的使用授权。
     *
     * ## 语义（曾因含混引发线上事故，务必按此理解）
     * 这是设备在**回报自己执行到了哪一步**，不是上报"应该是怎样"。
     * 授权状态的唯一权威在服务端（家长意图），本字段不得反向覆盖它。
     *
     * 早期它被写成"是否已被隐藏"（由启动器可见性反推）。本产品早已改为
     * **密码门禁模型**——取消授权的应用图标照常显示、打开时才弹家长密码，
     * 于是该推导恒为 false；服务端又按"以设备上报为准"回写，
     * 家长刚点的「一键取消全部授权」在下一个上报周期被整体覆盖回"已授权"。
     * 现在取值为授权意图账本的真实回显，与服务端同源、幂等且无副作用。
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
