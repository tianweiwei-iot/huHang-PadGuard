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
    val longitude: Double?
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

data class UnlockApproveRequest(
    val durationMinutes: Int? = null,
    val packageName: String? = null
)

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
    val iconUrl: String?
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
    val dailyLimitMinutes: Int? = null
)

@JsonClass(generateAdapter = true)
data class AppInstallRequest(
    val apkUrl: String,
    val packageName: String,
    val versionCode: Long? = null,
    val appName: String? = null
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
