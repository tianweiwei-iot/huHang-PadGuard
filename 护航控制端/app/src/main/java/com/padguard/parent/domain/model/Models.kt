package com.padguard.domain.model

import java.util.UUID

/**
 * 用户角色枚举
 * 对应设计文档：超级管理员、教师子账号、家长账号
 */
enum class UserRole {
    SUPER_ADMIN,    // 超级管理员
    TEACHER,        // 教师子账号（校园场景）
    PARENT          // 家长账号（家庭场景）
}

/**
 * 场景类型：家庭 / 校园
 * 对应设计文档"双场景差异化"
 */
enum class SceneType {
    FAMILY,     // 家庭管控
    SCHOOL      // 校园教学
}

/**
 * 设备在线状态
 */
enum class DeviceOnlineStatus {
    ONLINE,
    OFFLINE,
    UNKNOWN
}

/**
 * 设备管控模式
 */
enum class ControlMode {
    NORMAL,     // 正常模式
    LEARNING,   // 学习模式
    FOCUS       // 专注模式
}

/**
 * 告警级别
 */
enum class AlertLevel {
    INFO,
    WARNING,
    CRITICAL
}

/**
 * 告警状态
 */
enum class AlertStatus {
    ACTIVE,     // 活跃
    ACKNOWLEDGED,// 已确认
    RESOLVED,   // 已解决
    CLOSED      // 已关闭
}

/**
 * 用户信息（Domain Model）
 * 贯穿双场景：校园 + 家庭
 */
data class User(
    val id: String,
    val phone: String,
    val nickname: String?,
    val avatar: String?,
    val role: UserRole,
    val sceneType: SceneType,
    val token: String?,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 子账号/孩子信息
 * 校园场景=学生，家庭场景=孩子
 */
data class ChildAccount(
    val id: String,
    val name: String,
    val avatar: String?,
    val deviceId: String?,          // 绑定的设备ID
    val deviceName: String?,        // 设备别名
    val gradeOrAge: String?,        // 年级（校园）或年龄（家庭）
    val sceneType: SceneType
)

/**
 * 设备信息
 * 对应设计文档"设备资源管理"章节
 */
data class Device(
    val id: String,
    val name: String,
    val deviceId: String,           // 硬件设备唯一标识
    val model: String?,             // 设备型号 (如 "Xiaomi Pad 6")
    val osVersion: String?,         // Android 版本
    val appVersion: String?,        // 管控端(被控端)版本号
    val onlineStatus: DeviceOnlineStatus,
    val lastOnlineTime: Long?,      // 最后上线时间戳
    val batteryLevel: Int?,         // 电量 0-100
    val controlMode: ControlMode,
    val groupId: String?,           // 所属分组ID
    val groupName: String?,         // 分组名称
    val sceneType: SceneType,
    val latitude: Double?,          // GPS纬度
    val longitude: Double?          // GPS经度
)

/**
 * 设备使用时长统计
 * 对应设计文档"全维度管控 > 使用时长"
 */
data class UsageStats(
    val deviceId: String,
    val date: String,               // 日期 yyyy-MM-dd
    val totalUsageMinutes: Int,     // 总使用时长(分钟)
    val appUsages: List<AppUsage> = emptyList(), // 各应用使用详情
    val screenUnlockCount: Int = 0, // 解锁次数
    val firstUseTime: Long? = null, // 首次使用时间
    val lastUseTime: Long? = null   // 最后使用时间
)

/**
 * 单应用使用记录
 */
data class AppUsage(
    val packageName: String,
    val appName: String,
    val usageMinutes: Int,
    val iconUrl: String?,
    val startTime: String? = null,  // HH:mm:ss
    val endTime: String? = null,     // HH:mm:ss
    val usageSeconds: Int = usageMinutes * 60
)

/**
 * 单应用一次使用记录（应用历史子页用）
 * 表示某天该应用的"打开 → 关闭"时段。
 */
data class AppUsageHistoryEntry(
    val date: String,        // yyyy-MM-dd
    val startTime: String,   // HH:mm:ss
    val endTime: String,     // HH:mm:ss
    val usageSeconds: Int
)

/**
 * 应用管控策略
 * 对应设计文档"应用管理 > 应用黑名单"
 */
data class AppPolicy(
    val id: String = UUID.randomUUID().toString(),
    val deviceId: String,
    val packageName: String,
    val appName: String,
    val isBlocked: Boolean,         // 是否在黑名单中
    val dailyLimitMinutes: Int? = null,  // 每日时限(分钟)，null=不限
    val iconUrl: String?
)

/**
 * 上网策略
 * 对应设计文档"上网与内容管理"
 */
data class WebPolicy(
    val id: String = UUID.randomUUID().toString(),
    val deviceId: String,
    val blockedUrls: List<String> = emptyList(),
    val browserDisabled: Boolean = false,
    val smartShutdownEnabled: Boolean = false,
    val smartShutdownStartTime: String? = null,   // HH:mm
    val smartShutdownEndTime: String? = null      // HH:mm
)

/**
 * 时段限制规则
 * 对应设计文档"分时段设置"
 */
data class TimeRestriction(
    val id: String = UUID.randomUUID().toString(),
    val deviceId: String,
    val dayOfWeek: Int,              // 1=周一 ... 7=周日
    val startTime: String,           // HH:mm
    val endTime: String,             // HH:mm
    val maxMinutes: Int?,            // 该时段最大使用分钟数
    val isEnabled: Boolean = true
)

/**
 * 远程指令
 * 对应设计文档"即时远程指令"
 */
data class RemoteCommand(
    val id: String = UUID.randomUUID().toString(),
    val deviceId: String,
    val type: CommandType,
    val params: Map<String, Any?> = emptyMap(),
    val status: CommandStatus = CommandStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val executedAt: Long? = null
)

enum class CommandType {
    LOCK_SCREEN,                     // 一键锁屏
    UNLOCK_SCREEN,                   // 解锁屏幕
    SEND_MESSAGE,                    // 发送消息/广播
    TEMPORARY_AUTH,                  // 临时授权
    REBOOT_DEVICE,                   // 重启设备
    TAKE_SCREENSHOT,                 // 截屏
    TAKE_PHOTO,                      // 拍照
    START_RECORDING,                 // 开始录音
    STOP_RECORDING,                  // 停止录音
    GET_LOCATION,                    // 获取位置
    RING_DEVICE,                     // 响铃找设备
    CLEAR_DATA                       // 清除数据
}

enum class CommandStatus {
    PENDING,                         // 待执行
    SENT,                           // 已发送
    EXECUTED,                        // 已执行
    FAILED,                         // 执行失败
    TIMEOUT                         // 超时
}

/**
 * 实时截屏数据
 */
data class ScreenshotData(
    val deviceId: String,
    val imageUrl: String?,           // 服务端图片路径（/v1/files/{id}），PENDING 时为 null
    val thumbnailUrl: String?,       // 缩略图 URL（当前服务端未生成，可为 null）
    val status: String?,             // PENDING / READY
    val capturedAt: Long,            // 采集时间戳（毫秒），PENDING 时为 0
    val width: Int,
    val height: Int
) {
    /** 是否已可展示（服务端已收到设备上传的截图） */
    val isReady: Boolean get() = status == "READY" && imageUrl != null
}

/**
 * 告警信息
 * 对应设计文档"风险预警"
 */
data class Alert(
    val id: String,
    val deviceId: String,
    val deviceName: String,
    val level: AlertLevel,
    val title: String,
    val message: String,
    val status: AlertStatus,
    val category: AlertCategory,
    val triggeredAt: Long,
    val acknowledgedAt: Long? = null,
    val resolvedAt: Long? = null
)

enum class AlertCategory {
    DEVICE_OFFLINE,                  // 设备离线
    APP_VIOLATION,                   // 应用违规
    TIME_LIMIT_EXCEEDED,             // 超时使用
    URL_VIOLATION,                   // 访问违规网址
    LOCATION_GEOFENCE_EXIT,          // 越界
    BATTERY_LOW,                     // 低电量
    SYSTEM_ANOMALY                   // 系统异常
}

/**
 * 统计报告
 * 对应设计文档"数据统计"
 */
data class StatisticsReport(
    val deviceId: String,
    val period: ReportPeriod,
    val startDate: String,
    val endDate: String,
    val totalUsageMinutes: Int,
    val dailyUsages: List<DailyUsage> = emptyList(),
    val topApps: List<AppUsage> = emptyList(),
    val violationCount: Int = 0,
    val alertCount: Int = 0
)

enum class ReportPeriod {
    DAILY,
    WEEKLY,
    MONTHLY
}

data class DailyUsage(
    val date: String,
    val usageMinutes: Int,
    val violationCount: Int = 0
)

/**
 * 平板使用时间设置
 * 用于家长端设置孩子成长端（被控端）的使用时长、时段、休息间隔与提示文案。
 */
data class TabletUsageSettings(
    val deviceId: String,
    val enabled: Boolean = true,
    val enabledTimeRanges: List<TimeRange> = listOf(TimeRange("08:00", "18:00")),
    val weekdayLimitMinutes: Int = 120,
    val weekendLimitMinutes: Int = 180,
    val restAfterMinutes: Int = 60,
    val restDurationMinutes: Int = 15,
    val timeUpMessage: String = "观看时间已达上限，请休息一会儿再回来！",
    val syncToDevice: Boolean = false
)

/**
 * 时间段（HH:mm - HH:mm）
 */
data class TimeRange(
    val startTime: String, // HH:mm
    val endTime: String    // HH:mm
)

// ==================== 实时管控：屏幕监控 / 信息发布 / 定位 ====================

/**
 * 设备位置（定位点 / 移动轨迹点）
 *
 * 由「实时管控-定位」页与设备详情页消费。
 */
data class LocationInfo(
    val latitude: Double,
    val longitude: Double,
    val address: String?,
    val accuracy: Float?,
    val timestamp: Long
)

/**
 * 录屏画质档位
 */
enum class RecordResolution(val label: String, val shortSide: Int) {
    SD_480P("流畅 480P", 480),
    HD_720P("高清 720P", 720),
    FHD_1080P("超清 1080P", 1080)
}

/**
 * 屏幕监控设置（家长端可配置项，下发至被管控平板）
 */
data class ScreenMonitorSettings(
    val deviceId: String,
    val autoRefreshSeconds: Int = 5,                        // 实时画面自动刷新间隔（秒）
    val highDefinition: Boolean = true,                     // 高清画面
    val recordResolution: RecordResolution = RecordResolution.HD_720P,
    val recordWithAudio: Boolean = false,                   // 录屏是否同时采集设备声音
    val allowRemoteLock: Boolean = true                     // 是否允许远程锁屏
)

/**
 * 录屏任务
 */
data class ScreenRecordTask(
    val taskId: String,
    val deviceId: String,
    val startedAt: Long,
    val resolution: RecordResolution,
    val withAudio: Boolean
)

/**
 * 信息发布内容类型
 */
enum class MessageContentType(val label: String) {
    TEXT("文字"),
    IMAGE("图片"),
    VIDEO("视频"),
    AUDIO("声音")
}

/**
 * 信息发布请求
 */
data class MessagePublishRequest(
    val deviceId: String,
    val contentType: MessageContentType,
    val text: String? = null,          // 文字内容；媒体类型下作为附加说明
    val mediaUrl: String? = null,      // 图片 / 视频 / 声音资源地址
    val mediaName: String? = null,     // 素材名称（界面展示用）
    val displaySeconds: Int = 10,      // 平板端显示时长（秒）
    val fullScreen: Boolean = false,   // 是否霸屏显示
    val playAudio: Boolean = true      // 声音类内容是否播放声音
)

/**
 * 已发布信息记录
 */
data class PublishedMessage(
    val id: String = UUID.randomUUID().toString(),
    val deviceId: String,
    val contentType: MessageContentType,
    val summary: String,
    val displaySeconds: Int,
    val fullScreen: Boolean,
    val publishedAt: Long
)

/**
 * 地图服务提供方
 */
enum class MapProvider(val label: String) {
    BAIDU("百度地图"),
    AMAP("高德地图")
}

/**
 * 地图视图模式
 */
enum class MapViewMode(val label: String) {
    MODE_2D("2D"),
    MODE_3D("3D")
}

/**
 * 电子围栏配置
 */
data class GeofenceConfig(
    val deviceId: String,
    val enabled: Boolean = false,
    val name: String = "安全区域",
    val centerLatitude: Double = 0.0,
    val centerLongitude: Double = 0.0,
    val radiusMeters: Int = 500,
    val alertOnExit: Boolean = true      // 越界后记录移动轨迹并触发告警
)

// ==================== 设备管理：接入 / 台账 / 权限 / 功能 / 分发 / 升级 ====================

/**
 * 设备接入方式
 * 对标主流 MDM（Scalefusion/Hexnode/希沃集控）的设备注册方式
 */
enum class DeviceAccessMethod(val label: String, val description: String) {
    QR_CODE("扫码接入", "被控平板出示二维码，家长端扫码绑定"),
    PAIRING_CODE("配对码接入", "输入被控平板展示的 6 位配对码"),
    LAN_DISCOVERY("局域网发现", "自动发现同一局域网内的待接入平板"),
    EXCEL_IMPORT("Excel 导入", "通过 xlsx/csv 表格批量导入设备台账")
}

/**
 * 绑定二维码 / 配对码的载荷格式：
 * padguard://bind?deviceId=<硬件ID>&token=<一次性配对令牌>
 */
const val BIND_PAYLOAD_SCHEME = "padguard://bind"

/**
 * 局域网内发现的待接入设备
 */
data class LanDevice(
    val deviceId: String,       // 硬件设备唯一标识
    val model: String?,         // 设备型号
    val ipAddress: String,      // 局域网 IP
    val osVersion: String?
)

/**
 * Excel / CSV 导入的单行设备台账
 * 列约定：设备编号(必填)、设备别名、型号、分组
 */
data class DeviceImportRow(
    val hardwareId: String,
    val alias: String?,
    val model: String?,
    val groupName: String?
)

/**
 * 批量导入结果
 */
data class DeviceImportResult(
    val totalCount: Int,        // 表格中的总行数
    val successCount: Int,      // 成功接入数
    val skippedCount: Int,      // 已存在被跳过数
    val failedRows: List<String> = emptyList()  // 失败行描述（如"第3行：设备编号为空"）
)

/**
 * 设备权限配置（对标 MDM 设备限制策略）
 */
data class DevicePermissionConfig(
    val deviceId: String,
    val cameraAllowed: Boolean = false,         // 允许使用摄像头
    val microphoneAllowed: Boolean = false,     // 允许使用麦克风
    val locationAllowed: Boolean = true,        // 允许定位
    val usbAllowed: Boolean = false,            // 允许 USB / 外接存储
    val unknownSourceInstallAllowed: Boolean = false, // 允许安装未知来源应用
    val screenshotAllowed: Boolean = true       // 允许截屏（关闭则禁止被控端截屏）
)

/**
 * 设备功能开关配置（对标 MDM 功能管控 / Kiosk 能力）
 */
data class DeviceFunctionConfig(
    val deviceId: String,
    val learningModeEnabled: Boolean = false,   // 学习模式（仅可用学习类应用）
    val messagePushEnabled: Boolean = true,     // 允许家长端消息推送
    val screenTimeLimitEnabled: Boolean = true, // 启用使用时长限制
    val eyeProtectionEnabled: Boolean = true,   // 护眼模式
    val remoteLockEnabled: Boolean = true       // 允许远程锁屏
)

/**
 * 应用分发动作
 */
enum class AppDispatchAction(val label: String) {
    INSTALL("下发安装"),
    UNINSTALL("远程卸载"),
    WHITELIST("加入白名单"),
    BLACKLIST("加入黑名单")
}

/**
 * 可分发的应用（企业应用库）
 */
data class DistributableApp(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val iconUrl: String?,
    val apkUrl: String
)

/**
 * 设备升级信息（远程升级维护）
 */
data class DeviceUpgradeInfo(
    val deviceId: String,
    val currentAppVersion: String,
    val latestAppVersion: String,
    val hasUpdate: Boolean = currentAppVersion != latestAppVersion,
    val releaseNote: String? = null,
    val lastCheckTime: Long = System.currentTimeMillis()
)

/**
 * 设备已安装应用（应用监控 / 远程安装维护）
 *
 * [suspended] 与 [blocked] 是两种完全不同的状态，UI 上必须区分清楚：
 * - blocked：应用策略层面的禁用，孩子打开会被拦截页挡住，家长可随时解除；
 * - suspended：系统级挂起（Device Owner 的 setPackagesSuspended），
 *   应用图标直接消失、打开即闪退，是更重的手段。
 * 混为一谈会让家长在"我只是想限制时长"时误用了最重的手段。
 */
data class InstalledApp(
    val packageName: String,
    val appName: String,
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
