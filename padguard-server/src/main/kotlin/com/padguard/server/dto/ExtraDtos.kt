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
@JsonIgnoreProperties(ignoreUnknown = true)
data class StartRecordRequest(val resolution: String = "HD_720P", val withAudio: Boolean = true)
data class ScreenRecordTaskDto(
    val taskId: String, val deviceId: String, val startedAt: Long,
    val resolution: String, val withAudio: Boolean
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
data class AppUsageDto(
    val packageName: String, val appName: String, val usageMinutes: Int, val iconUrl: String? = null
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
    val updateTime: Long? = null
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
    val dailyLimitMinutes: Int? = null
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

@JsonIgnoreProperties(ignoreUnknown = true)
data class BatchSuspendRequest(val packages: List<String> = emptyList(), val suspended: Boolean = true)
