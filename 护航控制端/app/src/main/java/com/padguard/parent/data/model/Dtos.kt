package com.padguard.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// ==================== 通用响应包装 ====================

@JsonClass(generateAdapter = true)
data class ApiResponse<T>(
    val code: Int = 0,
    val message: String = "success",
    val data: T? = null,
    val timestamp: Long = System.currentTimeMillis()
)

// ==================== 认证相关 DTO ====================

@JsonClass(generateAdapter = true)
data class LoginPasswordRequest(
    val phone: String,
    val password: String
)

@JsonClass(generateAdapter = true)
data class LoginSmsRequest(
    val phone: String,
    val smsCode: String
)

@JsonClass(generateAdapter = true)
data class SendSmsRequest(
    val phone: String
)

@JsonClass(generateAdapter = true)
data class LoginResponse(
    val user: UserDto,
    val token: String,
    val refreshToken: String
)

@JsonClass(generateAdapter = true)
data class TokenResponse(
    val token: String,
    val refreshToken: String
)

@JsonClass(generateAdapter = true)
data class UserDto(
    val id: String,
    val phone: String,
    val nickname: String?,
    val avatar: String?,
    val role: String,         // "SUPER_ADMIN" | "TEACHER" | "PARENT"
    val sceneType: String     // "FAMILY" | "SCHOOL"
)

// ==================== 设备相关 DTO ====================

@JsonClass(generateAdapter = true)
data class DeviceDto(
    val id: String,
    // 后端 Device.toDto() 中 name/controlMode/sceneType 可能为 null，放宽可空以兼容解析
    val name: String?,
    val deviceId: String,
    val model: String?,
    val osVersion: String?,
    val appVersion: String?,
    val onlineStatus: String,   // "ONLINE" | "OFFLINE" | "UNKNOWN"
    val lastOnlineTime: Long?,
    val batteryLevel: Int?,
    val controlMode: String?,   // 后端可能为 null（孩子端尚未上报）
    val groupId: String?,
    val groupName: String?,     // 后端当前恒为 null
    val sceneType: String?,
    val latitude: Double?,
    val longitude: Double?,
    /** 家长是否仍处于远程锁屏状态；缺省 false 以兼容旧服务端 */
    val remoteLocked: Boolean? = false,
    /** 孩子端自定义资料：姓名 */
    val childName: String? = null,
    /** 孩子端自定义资料：昵称 */
    val childNickname: String? = null,
    /** 孩子端自定义资料：头像 URL */
    val childAvatar: String? = null
)

/** 家长端生成 6 位一次性绑定码响应 */
@JsonClass(generateAdapter = true)
data class BindCodeResponse(
    val bindCode: String,
    val expiresAt: Long
)

@JsonClass(generateAdapter = true)
data class BindDeviceRequest(
    val deviceId: String,
    val alias: String?
)

@JsonClass(generateAdapter = true)
data class RenameDeviceRequest(
    val name: String
)

@JsonClass(generateAdapter = true)
data class DeviceGroupDto(
    val id: String,
    val name: String,
    val sceneType: String,
    val deviceCount: Int
)

@JsonClass(generateAdapter = true)
data class CreateGroupRequest(
    val name: String,
    val sceneType: String
)

@JsonClass(generateAdapter = true)
data class AssignGroupRequest(
    val groupId: String
)

// ==================== 监控相关 DTO ====================

@JsonClass(generateAdapter = true)
data class ScreenshotDto(
    val deviceId: String,
    // 后端返回 imageUrl（而非 imageBase64），且尺寸/时间为可选
    val imageUrl: String?,
    val thumbnailUrl: String?,
    val capturedAt: Long?,
    val width: Int?,
    val height: Int?,
    val status: String? = null
)

@JsonClass(generateAdapter = true)
data class LocationDto(
    val latitude: Double,
    val longitude: Double,
    val address: String?,
    val accuracy: Float?,
    val timestamp: Long
)

@JsonClass(generateAdapter = true)
data class MediaResultDto(
    val url: String,
    val mimeType: String,
    val size: Long,
    val durationSeconds: Int? = null   // for audio/video
)

@JsonClass(generateAdapter = true)
data class StartRecordRequest(
    val resolution: String,            // "SD_480P" | "HD_720P" | "FHD_1080P"
    val withAudio: Boolean
)

@JsonClass(generateAdapter = true)
data class ScreenRecordTaskDto(
    val taskId: String,
    val deviceId: String,
    val startedAt: Long,
    val resolution: String,
    val withAudio: Boolean
)

/**
 * 任务接受回执。
 *
 * 停止录屏/录音时服务端只确认"停指令已下发"，此时 mp4 还没由孩子端传回来，
 * 因此**没有 url 字段**。曾误按 MediaResultDto 解析，取 url 恒为 null，
 * 家长端每次点"停止录屏"都弹"停止录屏失败"，而孩子端其实已经正常收尾上传了。
 */
@JsonClass(generateAdapter = true)
data class TaskAcceptedDto(
    val taskId: String,
    val kind: String? = null,
    val status: String? = null,
    val requestedAt: Long? = null
)

/**
 * 截屏指令的受理回执。
 *
 * 服务端 `/monitor/{deviceId}/screenshot` 返回的是
 * `ScreenshotRequestResult(deviceId, taskId, status, requestedAt)` —— **没有图片地址**，
 * 图片由孩子端拍完单独上传，要从截屏历史里轮询。
 * 之前按 ScreenshotDto 解析，imageUrl/capturedAt 恒为 null，截屏兜底通道等于废掉。
 */
@JsonClass(generateAdapter = true)
data class ScreenshotAcceptedDto(
    val deviceId: String,
    val taskId: String,
    val status: String? = null,
    val requestedAt: Long? = null
)

/**
 * 媒体任务（录屏 / 录音）完整状态。
 *
 * 停止录屏后 mp4 由孩子端**异步**上传，服务端把任务置为 READY 后才填 url，
 * 因此家长端点完"停止"要轮询 `GET monitor/{deviceId}/media/{taskId}`，
 * 直到 status == "READY" 才拿到可播放的文件地址。
 */
@JsonClass(generateAdapter = true)
data class MediaTaskStatusDto(
    val taskId: String,
    val deviceId: String,
    val kind: String,
    val status: String,
    val url: String? = null,
    val mimeType: String? = null,
    val size: Long? = null,
    val durationSeconds: Int? = null,
    val startedAt: Long = 0,
    val finishedAt: Long? = null
)

@JsonClass(generateAdapter = true)
data class ScreenMonitorSettingsDto(
    val deviceId: String,
    val autoRefreshSeconds: Int,
    val highDefinition: Boolean,
    val recordResolution: String,
    val recordWithAudio: Boolean,
    val allowRemoteLock: Boolean
)

// ==================== 信息发布相关 DTO ====================

@JsonClass(generateAdapter = true)
data class MessagePublishRequestDto(
    val deviceId: String,
    val contentType: String,           // "TEXT" | "IMAGE" | "VIDEO" | "AUDIO"
    val text: String?,
    val mediaUrl: String?,
    val mediaName: String?,
    val displaySeconds: Int,
    val fullScreen: Boolean,
    val playAudio: Boolean
)

@JsonClass(generateAdapter = true)
data class PublishedMessageDto(
    val id: String,
    val deviceId: String,
    val contentType: String,
    val summary: String,
    val displaySeconds: Int,
    val fullScreen: Boolean,
    val publishedAt: Long
)

// ==================== 定位 / 电子围栏相关 DTO ====================

@JsonClass(generateAdapter = true)
data class GeofenceDto(
    val deviceId: String,
    val enabled: Boolean,
    val name: String?,
    val centerLatitude: Double,
    val centerLongitude: Double,
    val radiusMeters: Int,
    val alertOnExit: Boolean
)

@JsonClass(generateAdapter = true)
data class LocationTrackPointDto(
    val latitude: Double,
    val longitude: Double,
    val address: String?,
    val accuracy: Float?,
    val timestamp: Long
)

// ==================== 策略相关 DTO ====================

@JsonClass(generateAdapter = true)
data class TimeRestrictionDto(
    val id: String?,
    val deviceId: String?,
    val dayOfWeek: Int,
    val startTime: String,
    val endTime: String,
    val maxMinutes: Int?,
    val isEnabled: Boolean
)

@JsonClass(generateAdapter = true)
data class DailyLimitRequest(
    val minutes: Int
)

@JsonClass(generateAdapter = true)
data class AppPolicyDto(
    val id: String?,
    val deviceId: String,
    val packageName: String,
    val appName: String,
    val isBlocked: Boolean,
    val dailyLimitMinutes: Int?
)

@JsonClass(generateAdapter = true)
data class UpdateBlacklistRequest(
    val blockedPackages: List<String>
)

@JsonClass(generateAdapter = true)
data class WebPolicyDto(
    val id: String?,
    val deviceId: String,
    val blockedUrls: List<String>,
    val browserDisabled: Boolean,
    val smartShutdownEnabled: Boolean,
    val smartShutdownStartTime: String?,
    val smartShutdownEndTime: String?
)

@JsonClass(generateAdapter = true)
data class UrlBlacklistRequest(
    val urls: List<String>
)

@JsonClass(generateAdapter = true)
data class BrowserDisableRequest(
    val disabled: Boolean
)

@JsonClass(generateAdapter = true)
data class ModeChangeRequest(
    val mode: String   // "NORMAL" | "LEARNING" | "FOCUS"
)

data class TempUnlockRequest(
    val durationMinutes: Int = 30,
    val packageName: String? = null,
    val reason: String? = null
)

@JsonClass(generateAdapter = true)
data class UnlockTicketDto(
    val id: String,
    val deviceId: String,
    val packageName: String? = null,
    val appLabel: String? = null,
    val durationMinutes: Int? = null,
    val reason: String? = null,
    val status: String = "PENDING",
    val createdAt: Long = 0L
)

// 这两个请求体必须带 @JsonClass：Moshi 对没有注解的 Kotlin 类不做反射序列化
// （项目没引入 moshi-kotlin-reflect），缺注解会在**构造请求体**时就抛异常，
// 于是 Retrofit 一个字节都没发出去 —— 表现是"家长点了同意放行，抓不到任何请求，
// 服务端工单永远停在 PENDING，孩子端毫无反应"，且仓库层只返回一个笼统的失败。
// 同文件其余请求体都带了注解，唯独这两个漏了。
@JsonClass(generateAdapter = true)
data class UnlockApproveRequest(
    val durationMinutes: Int? = null,
    val packageName: String? = null
)

@JsonClass(generateAdapter = true)
data class UnlockRejectRequest(
    val reason: String? = null
)

@JsonClass(generateAdapter = true)
data class PolicyTemplateDto(
    val id: String,
    val name: String,
    val description: String?,
    val sceneType: String,
    val category: String?
)

@JsonClass(generateAdapter = true)
data class ApplyTemplateRequest(
    val templateId: String
)

@JsonClass(generateAdapter = true)
data class TimeRangeDto(
    val startTime: String,
    val endTime: String
)

@JsonClass(generateAdapter = true)
data class TabletUsageSettingsDto(
    val deviceId: String,
    val enabledTimeRanges: List<TimeRangeDto>?,
    val weekdayLimitMinutes: Int,
    val weekendLimitMinutes: Int,
    val restAfterMinutes: Int,
    val restDurationMinutes: Int,
    val timeUpMessage: String,
    val syncToDevice: Boolean
)

// ==================== 统计相关 DTO ====================

@JsonClass(generateAdapter = true)
data class UsageStatsDto(
    val deviceId: String,
    val date: String,
    val totalUsageMinutes: Int,
    val appUsages: List<AppUsageDto>?
)

@JsonClass(generateAdapter = true)
data class AppUsageDto(
    val packageName: String,
    val appName: String,
    val usageMinutes: Int,
    val iconUrl: String? = null,
    /** 开始使用的墙钟毫秒（服务端按天取最小起点） */
    val startAt: Long? = null,
    /** 停止使用的墙钟毫秒（服务端按天取最大终点） */
    val endAt: Long? = null,
    val launchCount: Int = 0
)

@JsonClass(generateAdapter = true)
data class StatisticsReportDto(
    val deviceId: String,
    val period: String,
    val startDate: String,
    val endDate: String,
    val totalUsageMinutes: Int,
    val dailyUsages: List<DailyUsageDto>?,
    val topApps: List<AppUsageDto>?,
    val violationCount: Int,
    val alertCount: Int
)

@JsonClass(generateAdapter = true)
data class DailyUsageDto(
    val date: String,
    val usageMinutes: Int,
    val violationCount: Int
)

@JsonClass(generateAdapter = true)
data class WebActivityStatsDto(
    val totalVisits: Int,
    val topDomains: List<DomainVisitDto>?,
    val blockedAttempts: Int
)

@JsonClass(generateAdapter = true)
data class DomainVisitDto(
    val domain: String,
    val visitCount: Int,
    val lastVisitTime: Long
)

@JsonClass(generateAdapter = true)
data class ViolationStatsDto(
    val totalCount: Int,
    val byCategory: Map<String, Int>?,
    val trend: List<DailyViolationDto>?
)

@JsonClass(generateAdapter = true)
data class DailyViolationDto(
    val date: String,
    val count: Int
)

@JsonClass(generateAdapter = true)
data class ExportResultDto(
    val fileUrl: String,
    val fileName: String,
    val fileSize: Long
)

// ==================== 告警相关 DTO ====================

@JsonClass(generateAdapter = true)
data class AlertDto(
    val id: String,
    val deviceId: String,
    val deviceName: String,
    val level: String,       // "INFO" | "WARNING" | "CRITICAL"
    val title: String,
    val message: String?,
    val status: String,      // "ACTIVE" | "ACKNOWLEDGED" | "RESOLVED" | "CLOSED"
    val category: String?,
    val triggeredAt: Long,
    val acknowledgedAt: Long?,
    val resolvedAt: Long?
)

@JsonClass(generateAdapter = true)
data class CloseAlertRequest(
    val reason: String?
)

// ==================== 应用监控 / 远程安装维护 ====================

/**
 * 设备已安装应用（服务端台账 + 本地应用策略合并结果）
 *
 * [blocked] / [dailyLimitMinutes] 由服务端与 app_policies 表合并后下发，
 * 控制端无需再请求一次策略接口即可直接渲染开关状态。
 */
@JsonClass(generateAdapter = true)
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
    val iconUrl: String? = null,
    val hidden: Boolean = false
)

@JsonClass(generateAdapter = true)
data class AppInstallRequest(
    val apkUrl: String,
    val packageName: String,
    val versionCode: Long? = null,
    val appName: String? = null
)

/** 使用权限开关：hidden=true 表示在孩子端隐藏该应用（不显示，但不是卸载） */
data class AppHiddenRequest(
    val packageName: String,
    val hidden: Boolean = true
)

/** 上传结果：id 用于后续引用，url 可直接访问，name/type/size 用于界面回显 */
@JsonClass(generateAdapter = true)
data class FileUploadDto(
    val fileId: String,
    val url: String,
    val fileName: String,
    val contentType: String,
    val size: Long
)

@JsonClass(generateAdapter = true)
data class SuspendRequest(
    val packageName: String,
    val suspended: Boolean = true
)

@JsonClass(generateAdapter = true)
data class BatchSuspendRequest(
    val packages: List<String> = emptyList(),
    val suspended: Boolean = true
)

/** 批量设置使用权限（隐藏 / 显示）。`all=true` 作用于全部已安装应用（一键授权全部 / 一键取消全部）。 */
data class AppHiddenBatchRequest(
    val packages: List<String> = emptyList(),
    val hidden: Boolean = true,
    val all: Boolean = false
)

/** 设置单个应用的每日使用时长上限（分钟，0 = 不限制）。 */
data class AppLimitRequest(val minutes: Int = 0)
