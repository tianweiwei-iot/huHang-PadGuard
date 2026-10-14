package com.padguard.parent.data.model

import com.padguard.parent.domain.model.Enrollment
import com.padguard.parent.domain.model.EnrollmentState
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

/**
 * 手机号注册请求，对应服务端 `POST /v1/auth/register`。
 *
 * 字段必须与服务端 `RegisterRequest` 严格一致（phone / password / nickname），
 * 否则会出现"端上注册成功但昵称丢失"这类不报错的静默降级。
 * nickname 可空：留空时服务端用「家长+手机后 4 位」兜底。
 *
 * 注册成功后服务端直接返回与登录相同的 [LoginResponse]（含 token），
 * 因此注册与登录共用同一个响应模型，端上无需二次登录。
 */
@JsonClass(generateAdapter = true)
data class RegisterRequest(
    val phone: String,
    val password: String,
    val nickname: String? = null
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
    /** 家长端切换的管控模式（NORMAL / LEARNING / FOCUS），由服务端 applyMode 落库并随设备详情下发；早期缺失会导致 sceneMode 反序列化被静默丢弃。 */
    val sceneMode: String?,
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
    val childAvatar: String? = null,
    /**
     * 设备操作密码是否已设置（由服务端 [guardPasswordSet] 派生下发）。
     * 应用管理页据此决定是否置灰「打开应用也要密码」开关；缺省 false 以兼容旧服务端。
     */
    val guardPasswordSet: Boolean? = null
)

/**
 * 服务端一次返回的**完整接入信息**。
 *
 * 二维码载荷与配对码在这里同源（同一会话的两种呈现），家长端照此渲染即可，
 * 不再需要为两个展示位置各发一次请求 —— 那正是"二维码和配对码不是同一个码"的成因。
 *
 * [serverNow] 由服务端下发，用于计算剩余有效期：终端时钟不准时，
 * 本地算出的倒计时会离谱地偏大或偏小。
 */
@JsonClass(generateAdapter = true)
data class EnrollmentDto(
    val sessionId: String,
    val pairingCode: String,
    val qrPayload: String,
    val state: String,
    val deviceId: String?,
    val expiresAt: Long,
    val serverNow: Long
) {
    /**
     * 落域：记录收到快照的**单调时刻**，剩余时长据此计算。
     *
     * 用 elapsedRealtime 而不是 currentTimeMillis：后者会随用户改系统时间、NTP 校时跳变，
     * 倒计时会突然多出几小时或直接归零。
     */
    fun toDomain(): Enrollment = Enrollment(
        sessionId = sessionId,
        pairingCode = pairingCode,
        qrPayload = qrPayload,
        state = EnrollmentState.of(state),
        deviceId = deviceId,
        expiresAt = expiresAt,
        serverNow = serverNow,
        receivedAtRealtime = android.os.SystemClock.elapsedRealtime()
    )
}

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
    // 以下两个字段为预留（wire 兼容继续下发），孩子端录屏恒按原生分辨率、不含音轨；
    // 家长端 UI 已明示，详见《前后端契约对齐确认表》§六 录屏能力矩阵。
    val resolution: String,            // "SD_480P" | "HD_720P" | "FHD_1080P"（预留未生效）
    val withAudio: Boolean             // 预留未生效（录屏不混入设备声音）
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

/**
 * 实时看屏状态（含带宽遥测）。
 * 对应服务端 [StreamStatusDto]；字段名逐字符对齐，便于 Moshi 直解。
 *
 * 注意：[upstreamBps] / [downstreamBps] 是服务端按 1s 滚动窗口统计的**字节**速率
 * （上行=孩子端推流占带宽，下行=服务端转发给家长端占带宽），不是比特率（bit/s）。
 */
@JsonClass(generateAdapter = true)
data class ScreenStatusDto(
    val deviceId: String,
    val lastFrameAt: Long,
    val frameAgeMs: Long,
    val live: Boolean,
    val upstreamBps: Long,
    val downstreamBps: Long,
    val totalBytes: Long
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

/** 电子围栏（多围栏）。[id] 为 null 表示"新建"，由服务端生成。 */
@JsonClass(generateAdapter = true)
data class GeofenceZoneDto(
    val id: String? = null,
    val deviceId: String,
    val enabled: Boolean,
    val name: String?,
    val centerLatitude: Double,
    val centerLongitude: Double,
    val radiusMeters: Int,
    val alertOnExit: Boolean,
    val alertOnEnter: Boolean = false,
    val createdAt: Long = 0
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

/**
 * 应用管控模式设置请求。
 *
 * @param mode BLACKLIST = 只管控名单内；WHITELIST = 只放行名单内（其余拦截）
 * @param requirePasswordOnLaunch 无 DO 设备上，打开受管控应用是否要求输入管理密码
 * @param requirePasswordOnInstall 无 DO 设备上，安装确认页是否要求输入管理密码（默认 true）
 * @param requirePasswordOnUninstall 无 DO 设备上，卸载确认页是否要求输入管理密码（默认 true）
 */
@JsonClass(generateAdapter = true)
data class AppListPolicyRequest(
    val mode: String = "BLACKLIST",
    val requirePasswordOnLaunch: Boolean = false,
    val requirePasswordOnInstall: Boolean = true,
    val requirePasswordOnUninstall: Boolean = true
)

/** 应用管控模式查询结果（供页面回显） */
@JsonClass(generateAdapter = true)
data class AppListPolicyDto(
    val mode: String = "BLACKLIST",
    val requirePasswordOnLaunch: Boolean = false,
    val requirePasswordOnInstall: Boolean = true,
    val requirePasswordOnUninstall: Boolean = true,
    val whitelist: List<String> = emptyList(),
    val blacklist: List<String> = emptyList()
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

@JsonClass(generateAdapter = true)
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
    val enabled: Boolean = true,
    val enabledTimeRanges: List<TimeRangeDto>?,
    val weekdayLimitMinutes: Int,
    val weekendLimitMinutes: Int,
    val restAfterMinutes: Int,
    val restDurationMinutes: Int,
    val syncToDevice: Boolean
)

/**
 * 平板使用计时会话响应（与服务端 UsageSessionDto 字段一一对应）。
 *
 * 三端一律用 [serverNow] - [startEpoch] 计算已用时长，剩余 = (quotaMinutes + extendedMinutes) 分钟 - 已用，
 * 因此家长端显示与孩子端、服务端同一份数字，不再各自按本地时钟计算导致对不上。
 */
@JsonClass(generateAdapter = true)
data class UsageSessionDto(
    val deviceId: String = "",
    val serverNow: Long = 0L,
    val active: Boolean = false,
    val dayKey: String = "",
    val startEpoch: Long? = null,
    val quotaMinutes: Int = 0,
    val extendedMinutes: Int = 0,
    val remainingMinutes: Int = 0,
    val remainingMs: Long = 0L,
    val countdownThresholdMs: Long = 10 * 60_000L,
    val delayOptions: List<Int> = listOf(15, 30, 45, 60),
    val maxDailyExtendMinutes: Int = 120,
    val lastDelayEpoch: Long? = null
)

// ==================== 指令回执（可观测性） ====================

/**
 * 下发指令时服务端返回的对象。
 *
 * 之前家长端把锁屏/解锁接口的响应体当成"不用看的空响应"丢掉（用 ResponseBody 接收），
 * 于是只有"指令发出去了"这件事是确定的，**到底生效没有完全靠猜** ——
 * 这正是"点了锁屏但孩子端没反应，也不知道卡在哪一步"的根因。
 * 拿到 [msgId] 才能去查指令状态，把链路从"发后不理"变成"可追踪"。
 */
/** 锁屏 / 解锁时附带的说明，会展示在孩子端锁屏页上 */
@JsonClass(generateAdapter = true)
data class LockReasonRequest(val reason: String? = null)

/** 水印开关请求；content 留空时孩子端用默认文案 */
@JsonClass(generateAdapter = true)
data class WatermarkRequestDto(
    val enabled: Boolean = true,
    val content: String? = null
)

/** 操作密码闸门：家长端设置 / 清除设备操作密码（密码由服务端哈希，明文不出服务端） */
@JsonClass(generateAdapter = true)
data class GuardPasswordRequestDto(
    val password: String? = null,
    val clear: Boolean = false
)

@JsonClass(generateAdapter = true)
data class CommandResultDto(
    val msgId: String = "",
    val type: String = "",
    /** PENDING / SUCCESS / FAILED / EXPIRED / UNSUPPORTED / REJECTED */
    val status: String = "",
    val payload: Map<String, Any?>? = null,
    val createdAt: Long = 0,
    val executedAt: Long? = null,
    /** 指令回执的错误码 / 描述，说明"为什么没生效" */
    val errorCode: String? = null,
    val errorMessage: String? = null
)

// ==================== 未成年人模式（合规底座） ====================

/**
 * 未成年人模式当前配置。
 *
 * 服务端返回的对象里，时长类字段 `0` 表示"沿用该档位的合规默认值"，
 * 端上展示时要用 [AgeBandDefaultDto] 补齐，不能直接把 0 显示成"0 分钟"。
 */
@JsonClass(generateAdapter = true)
data class MinorModeDto(
    val enabled: Boolean = false,
    val ageBand: String? = null,
    val curfewEnabled: Boolean = true,
    val curfewStart: String? = null,
    val curfewEnd: String? = null,
    val dailyLimitMinutes: Int = 0,
    val weekendLimitMinutes: Int = 0,
    val continuousMinutes: Int = 0,
    val restMinutes: Int = 0,
    val parentExemptUntil: Long = 0L,
    val requireParentAuthToExit: Boolean = true
)

/** 一键设置未成年人模式。除 enabled / ageBand 外全部可空 = 沿用档位默认值。 */
@JsonClass(generateAdapter = true)
data class MinorModeRequestDto(
    val enabled: Boolean = true,
    val ageBand: String? = null,
    val curfewEnabled: Boolean? = null,
    val curfewStart: String? = null,
    val curfewEnd: String? = null,
    val dailyLimitMinutes: Int? = null,
    val weekendLimitMinutes: Int? = null,
    val continuousMinutes: Int? = null,
    val restMinutes: Int? = null,
    /** >0 时按当前时间累加写入豁免到期时间；只压过宵禁与护眼，不动每日总额度 */
    val exemptMinutes: Int? = null
)

/** 某一龄档的合规默认值，家长端在选档界面直接展示"开启后会发生什么" */
@JsonClass(generateAdapter = true)
data class AgeBandDefaultDto(
    val dailyLimitMinutes: Int = 60,
    val continuousMinutes: Int = 30,
    val restMinutes: Int = 10,
    val curfewStart: String = "22:00",
    val curfewEnd: String = "06:00"
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

/**
 * 单应用一次使用时段的传输对象。
 *
 * 服务端返回的是**已按服务端时区格式化好的字符串**（date / startTime / endTime），
 * 端上不做二次解析——避免端上再套一层时区导致与服务端口径不一致。
 */
@JsonClass(generateAdapter = true)
data class AppUsageHistoryEntryDto(
    val date: String,
    val startTime: String,
    val endTime: String,
    val usageSeconds: Int
) {
    fun toDomain() = com.padguard.parent.domain.model.AppUsageHistoryEntry(
        date = date, startTime = startTime, endTime = endTime, usageSeconds = usageSeconds
    )
}

/**
 * 未读告警数的传输对象。
 *
 * 服务端 `/v1/alerts/unread-count` 返回的是 `{"count": N}` **对象**而不是裸数字。
 * 早期这里声明成 `ApiResponse<Int>`，Moshi 反序列化时拿到 OBJECT 却要写成 INT，
 * 直接抛 JsonDataException —— 属典型的"契约错配在解析阶段就炸"。
 */
@JsonClass(generateAdapter = true)
data class UnreadCountDto(
    val count: Int
)

// ==================== 批量导入设备台账 ====================

@JsonClass(generateAdapter = true)
data class DeviceImportRowDto(
    val hardwareId: String = "",
    val alias: String? = null,
    val model: String? = null,
    val groupName: String? = null
) {
    fun toDomain() = com.padguard.parent.domain.model.DeviceImportRow(
        hardwareId = hardwareId, alias = alias, model = model, groupName = groupName
    )
}

@JsonClass(generateAdapter = true)
data class DeviceImportRequestDto(val rows: List<DeviceImportRowDto> = emptyList())

@JsonClass(generateAdapter = true)
data class DeviceImportResultDto(
    val totalCount: Int,
    val successCount: Int,
    val skippedCount: Int,
    val failedRows: List<String> = emptyList()
) {
    fun toDomain() = com.padguard.parent.domain.model.DeviceImportResult(
        totalCount = totalCount,
        successCount = successCount,
        skippedCount = skippedCount,
        failedRows = failedRows
    )
}

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
@JsonClass(generateAdapter = true)
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
@JsonClass(generateAdapter = true)
data class AppHiddenBatchRequest(
    val packages: List<String> = emptyList(),
    val hidden: Boolean = true,
    val all: Boolean = false
)

/** 设置单个应用的每日使用时长上限（分钟，0 = 不限制）。 */
@JsonClass(generateAdapter = true)
data class AppLimitRequest(val minutes: Int = 0)

// ---------------------------------------------------------------------------
// 订阅与计费
// ---------------------------------------------------------------------------

/** 可购买套餐。金额一律用「分」的整数传递，避免浮点对不齐账。 */
@JsonClass(generateAdapter = true)
data class PlanDto(
    val key: String,
    val label: String,
    val months: Int,
    val amountFen: Int,
    /** 展示用价格文本，如 "¥298" */
    val priceText: String,
    val deviceLimit: Int
)

/**
 * 当前订阅状态。
 *
 * [active] 决定"能不能用"，[paid] 决定"是不是已付费用户"——两者不等价：
 * 试用期内 active=true 但 paid=false，UI 要据此分别展示"试用剩余 N 天"与"有效期至 X"。
 */
@JsonClass(generateAdapter = true)
data class SubscriptionDto(
    val status: String,
    val plan: String,
    val planLabel: String,
    val deviceLimit: Int,
    val deviceUsed: Int,
    val startedAt: Long,
    val expiredAt: Long,
    /** 剩余天数（向下取整）；已过期为 0 */
    val remainDays: Int,
    val paid: Boolean,
    val active: Boolean,
    val autoRenew: Boolean
) {
    companion object {
        const val STATUS_TRIAL = "TRIAL"
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_EXPIRED = "EXPIRED"
        const val STATUS_REVOKED = "REVOKED"
    }
}

@JsonClass(generateAdapter = true)
data class CreateOrderRequest(
    val plan: String,
    /** 支付渠道；目前仅 MANUAL（后台开通）可用 */
    val channel: String? = null
)

@JsonClass(generateAdapter = true)
data class CreateOrderResponse(
    val orderNo: String,
    val amountFen: Int,
    val channel: String,
    val payDeadline: Long,
    /** 拉起支付所需的参数；渠道未接入时为 null */
    val payParams: Map<String, Any?>? = null
)

@JsonClass(generateAdapter = true)
data class OrderDto(
    val orderNo: String,
    val plan: String,
    val planLabel: String,
    val amountFen: Int,
    val priceText: String,
    val channel: String,
    val status: String,
    val createdAt: Long,
    val paidAt: Long?
)

/**
 * 收银台上的一个支付渠道。
 *
 * [mock] 显式告诉 UI 这是示例支付（不会产生真实扣款），避免家长误以为已经付款。
 * 接入真实渠道后该位由服务端置 false，前端无需改动。
 */
@JsonClass(generateAdapter = true)
data class PayChannelDto(
    val key: String,
    val label: String,
    /** 是否可选：false 时置灰（生产环境尚未接入） */
    val enabled: Boolean,
    /** 是否为示例支付 */
    val mock: Boolean,
    val note: String? = null
)

// ==================== 局域网发现 ====================

/**
 * 局域网里发现的一台待接入平板。
 *
 * `deviceId` 是**硬件编号**（deviceSn）而非服务端台账 id：接入时服务端靠它定位那条无主记录。
 */
@JsonClass(generateAdapter = true)
data class LanDeviceDto(
    val deviceId: String,
    val model: String?,
    val ipAddress: String,
    val osVersion: String?
) {
    fun toDomain() = com.padguard.parent.domain.model.LanDevice(
        deviceId = deviceId, model = model, ipAddress = ipAddress, osVersion = osVersion
    )
}

@JsonClass(generateAdapter = true)
data class LanBindRequestDto(
    val hardwareId: String = "",
    val alias: String? = null
)

// ==================== 护眼（蓝光滤镜） ====================

@JsonClass(generateAdapter = true)
data class EyeProtectionRequest(
    val enabled: Boolean = false,
    val filterLevel: Int = 3
)

@JsonClass(generateAdapter = true)
data class EyeProtectionDto(
    val enabled: Boolean,
    val level: Int
)
