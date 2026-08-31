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
    val iconUrl: String?
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
    val imageBase64: String?,        // Base64 编码的截图
    val thumbnailUrl: String?,       // 缩略图 URL
    val capturedAt: Long,
    val width: Int,
    val height: Int
)

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
