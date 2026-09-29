package com.padguard.server.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

// ============ 分组 ============
data class DeviceGroupDto(
    val id: String, val name: String, val sceneType: String, val deviceCount: Int
)
data class CreateGroupRequest(val name: String, val sceneType: String)
data class AssignGroupRequest(val groupId: String)
data class RenameDeviceRequest(val name: String)

// ============ 监控 / 媒体 ============
data class ScreenshotDto(
    val deviceId: String, val imageUrl: String?, val thumbnailUrl: String?,
    val capturedAt: Long?, val width: Int?, val height: Int?, val status: String? = "READY"
)
data class ScreenshotRequestResult(
    val deviceId: String, val taskId: String, val status: String, val requestedAt: Long
)
data class TaskAcceptedDto(
    val taskId: String, val kind: String, val status: String, val requestedAt: Long
)
data class MediaResultDto(
    val url: String, val mimeType: String, val size: Long, val durationSeconds: Int? = null
)

/**
 * 截图 / 媒体文件上传回执。
 *
 * 与孩子端 `ScreenshotAck` / `MediaAck` 严格对齐：`accepted` 是「服务端是否已接收」的布尔标志。
 * 注意：它和日志 / 定位上报里的 `accepted`（表示"接收条数"的整数）语义不同，
 * 切勿复用 —— 早期用 `Map` 返回 `accepted=1` 曾导致孩子端 JSON 解析失败（Boolean 位置收到整数）。
 * 这里用强类型 DTO 固化契约，让字段类型在编译期就对不上会直接报错。
 */
data class UploadAckDto(val accepted: Boolean, val url: String)

/**
 * 家长端上传素材 / APK 的返回体。
 *
 * 只回 `url` 是不够的：信息发布页要在列表里显示"已选中的素材"并在撤销时区分文件，
 * 远程安装还要把原始文件名回显给家长确认（选错了装到孩子端才发现，往返成本太高）。
 * 因此把 id / 原始名 / 类型 / 大小一并带回。
 */
data class FileUploadDto(
    val fileId: String,
    val url: String,
    val fileName: String,
    val contentType: String,
    val size: Long
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StartRecordRequest(val resolution: String = "HD_720P", val withAudio: Boolean = true)
data class ScreenRecordTaskDto(
    val taskId: String, val deviceId: String, val startedAt: Long,
    val resolution: String, val withAudio: Boolean
)
/**
 * 媒体任务（录屏 / 录音）的完整状态，供家长端轮询"停止后拿结果"。
 *
 * 之前只有 Service 层的 [MediaResultDto]，**没有任何 HTTP 接口暴露它** ——
 * 家长端点"停止录屏"后只能拿到 PENDING 的受理回执，永远查不到文件地址，
 * 结果就是"录屏明明录好了，家长端却没有可播放的入口"。
 * 这里把任务状态与文件地址合并成一个 DTO 一次性返回。
 */
data class MediaTaskStatusDto(
    val taskId: String, val deviceId: String, val kind: String, val status: String,
    val url: String? = null, val mimeType: String? = null,
    val size: Long? = null, val durationSeconds: Int? = null,
    val startedAt: Long = 0, val finishedAt: Long? = null
)

data class ScreenMonitorSettingsDto(
    val deviceId: String, val autoRefreshSeconds: Int, val highDefinition: Boolean,
    val recordResolution: String, val recordWithAudio: Boolean, val allowRemoteLock: Boolean
)

// ============ 定位 / 围栏 ============
data class LocationDto(
    val latitude: Double, val longitude: Double, val address: String? = null,
    val accuracy: Float? = null, val timestamp: Long
)
data class TrackPointDto(
    val latitude: Double, val longitude: Double, val address: String? = null,
    val accuracy: Float? = null, val timestamp: Long
)
data class GeofenceDto(
    val deviceId: String, val enabled: Boolean, val name: String?,
    val centerLatitude: Double, val centerLongitude: Double,
    val radiusMeters: Int, val alertOnExit: Boolean
)

// ============ 信息发布 ============
@JsonIgnoreProperties(ignoreUnknown = true)
data class MessagePublishRequest(
    val deviceId: String? = null,
    val contentType: String = "TEXT",
    val text: String? = null,
    val mediaUrl: String? = null,
    val mediaName: String? = null,
    val displaySeconds: Int = 10,
    val fullScreen: Boolean = false,
    val playAudio: Boolean = false
)
data class PublishedMessageDto(
    val id: String, val deviceId: String, val contentType: String,
    val summary: String, val displaySeconds: Int, val fullScreen: Boolean, val publishedAt: Long
)

// ============ 策略扩展 ============
@JsonIgnoreProperties(ignoreUnknown = true)
data class TimeRestrictionDto(
    val id: String? = null, val deviceId: String? = null,
    val dayOfWeek: Int = 1, val startTime: String, val endTime: String,
    val maxMinutes: Int? = null, val isEnabled: Boolean = true
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class DailyLimitRequest(val minutes: Int)
@JsonIgnoreProperties(ignoreUnknown = true)
data class AppPolicyDto(
    val id: String? = null, val deviceId: String? = null,
    val packageName: String, val appName: String? = null,
    val isBlocked: Boolean = false, val dailyLimitMinutes: Int? = null
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class UpdateBlacklistRequest(val blockedPackages: List<String> = emptyList())
@JsonIgnoreProperties(ignoreUnknown = true)
data class WebPolicyDto(
    val blockedUrls: List<String> = emptyList(),
    val browserDisabled: Boolean = false,
    val smartShutdownEnabled: Boolean = false,
    val smartShutdownStartTime: String? = null,
    val smartShutdownEndTime: String? = null
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class UrlBlacklistRequest(val urls: List<String> = emptyList())
@JsonIgnoreProperties(ignoreUnknown = true)
data class BrowserDisableRequest(val disabled: Boolean = false)
@JsonIgnoreProperties(ignoreUnknown = true)
data class ModeChangeRequest(val mode: String)
data class PolicyTemplateDto(
    val id: String, val name: String, val description: String?, val sceneType: String, val category: String?
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class ApplyTemplateRequest(val templateId: String)

/** 可用时间段（HH:mm 24 小时制），与家长端 TimeRangeDto 对齐 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TimeRangeDto(val startTime: String, val endTime: String)

/**
 * 平板使用时间设置（家长端「时间管控」页）。
 * 字段与家长端 TabletUsageSettingsDto 一一对应；syncToDevice 为 true 时立即重建并下发策略包。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TabletUsageSettingsDto(
    val deviceId: String = "",
    val enabledTimeRanges: List<TimeRangeDto>? = null,
    val weekdayLimitMinutes: Int = 120,
    val weekendLimitMinutes: Int = 180,
    val restAfterMinutes: Int = 60,
    val restDurationMinutes: Int = 15,
    val timeUpMessage: String = "",
    val syncToDevice: Boolean = false
)

/** 救援通道开关请求：解除被管控平板的调试 / 侧载限制，用于远程升级或 adb 救援。 */
data class SystemLockRelaxedRequest(val relaxed: Boolean = false)

// ============ 统计 ============
/**
 * 单个应用的使用明细。
 *
 * `startAt` / `endAt` 是**墙钟毫秒**：家长真正关心的是"晚上几点还在用"，
 * 只有总时长看不出时段问题。孩子端上报的是增量段，服务端按天取最小开始 / 最大结束还原全天区间。
 *
 * 老版本只有 `usageMinutes`，家长端的"开始时间/停止时间"两栏只能留空，
 * 这也是"使用情况看起来不像真实数据"的直接原因。
 */
data class AppUsageDto(
    val packageName: String,
    val appName: String,
    val usageMinutes: Int,
    val iconUrl: String? = null,
    val startAt: Long? = null,
    val endAt: Long? = null,
    val launchCount: Int = 0
)
data class UsageStatsDto(
    val deviceId: String, val date: String, val totalUsageMinutes: Int,
    val appUsages: List<AppUsageDto>? = null
)
data class DailyUsageDto(val date: String, val usageMinutes: Int, val violationCount: Int)
data class DomainVisitDto(val domain: String, val visitCount: Int, val lastVisitTime: Long)
data class WebActivityStatsDto(
    val totalVisits: Int, val topDomains: List<DomainVisitDto>? = null, val blockedAttempts: Int
)
data class DailyViolationDto(val date: String, val count: Int)
data class ViolationStatsDto(
    val totalCount: Int, val byCategory: Map<String, Int>? = null, val trend: List<DailyViolationDto>? = null
)
data class StatisticsReportDto(
    val deviceId: String, val period: String, val startDate: String, val endDate: String,
    val totalUsageMinutes: Int, val dailyUsages: List<DailyUsageDto>? = null,
    val topApps: List<AppUsageDto>? = null, val violationCount: Int, val alertCount: Int
)
data class ExportResultDto(val fileUrl: String, val fileName: String, val fileSize: Long)

// ============ 告警 ============
data class AlertDto(
    val id: String, val deviceId: String, val deviceName: String,
    val level: String, val title: String, val message: String?, val status: String,
    val category: String?, val triggeredAt: Long,
    val acknowledgedAt: Long? = null, val resolvedAt: Long? = null
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class CloseAlertRequest(val reason: String? = null)

// ============ 临时解锁工单 ============
data class UnlockTicketDto(
    val id: String, val deviceId: String, val packageName: String?, val appLabel: String?,
    val durationMinutes: Int?, val reason: String?, val status: String,
    val createdAt: Long, val resolvedAt: Long?
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class UnlockApproveRequest(val durationMinutes: Int? = null, val packageName: String? = null)
@JsonIgnoreProperties(ignoreUnknown = true)
data class UnlockRejectRequest(val reason: String? = null)

// ============ 应用监控 / 远程安装维护 ============
/** 孩子端上报的单条应用信息 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AppInventoryItem(
    val packageName: String,
    val appName: String? = null,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val isSystem: Boolean? = null,
    val installTime: Long? = null,
    val updateTime: Long? = null,
    /** 48dp PNG 图标的 Base64（不带 data: 前缀） */
    val iconBase64: String? = null,
    /**
     * 当前是否被隐藏。
     * 孩子端主动上报，用于纠正服务端状态：若家长在设备离线时改了开关，
     * 设备重新上报时会以设备真实状态为准，避免"服务端显示已隐藏、实际还看得见"。
     */
    val hidden: Boolean? = null
)

/** 孩子端全量应用台账上报 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AppInventoryRequest(val apps: List<AppInventoryItem> = emptyList())

/** 家长端看到的已安装应用（已合并本地应用策略） */
data class InstalledAppDto(
    val packageName: String,
    val appName: String? = null,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val isSystem: Boolean = false,
    val installed: Boolean = true,
    val suspended: Boolean = false,
    val installTime: Long? = null,
    val updateTime: Long? = null,
    val lastSeenAt: Long = 0,
    val blocked: Boolean = false,
    val dailyLimitMinutes: Int? = null,
    /** 应用图标地址；为空时管控端应回落到默认图标而不是显示空白 */
    val iconUrl: String? = null,
    /** 是否已被隐藏（家长关闭使用权限）。隐藏 ≠ 卸载：应用仍在，只是孩子端桌面不显示 */
    val hidden: Boolean = false
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AppInstallRequest(
    val apkUrl: String,
    val packageName: String,
    val versionCode: Long? = null,
    val appName: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SuspendRequest(val packageName: String, val suspended: Boolean = true)

/**
 * 应用「使用权限」开关请求。
 *
 * 为什么不复用 [SuspendRequest]：两者语义完全不同（挂起只是禁用、图标还在；隐藏是彻底不显示），
 * 复用同一个 `suspended` 字段会让读代码的人必须翻注释才知道这里 true 表示"隐藏"，
 * 一旦哪天有人照字段名理解，就会把"关闭权限"误实现成"挂起"。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AppHiddenRequest(val packageName: String, val hidden: Boolean = true)

/**
 * 批量设置应用「使用权限」（隐藏/显示）。
 *
 * `all=true` 时作用于全部已安装应用——家长端「一键授权全部 / 一键取消全部」走这条，
 * 不必先在前端逐个收集包名。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AppHiddenBatchRequest(
    val packages: List<String> = emptyList(),
    val hidden: Boolean = true,
    val all: Boolean = false
)

/** 单应用每日使用时长上限（分钟）。0 表示不限制。 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AppLimitRequest(val minutes: Int = 0)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BatchSuspendRequest(val packages: List<String> = emptyList(), val suspended: Boolean = true)
