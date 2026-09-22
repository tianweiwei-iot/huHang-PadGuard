package com.padguard.server.domain

import jakarta.persistence.*

@Entity
@Table(name = "device_groups")
class DeviceGroup(
    @Id var id: String = "",
    var name: String = "",
    @Column(name = "scene_type") var sceneType: String = "",
    @Column(name = "owner_user_id") var ownerUserId: String? = null,
    @Column(name = "created_at") var createdAt: Long = 0
)

@Entity
@Table(name = "uploaded_files")
class UploadedFile(
    @Id var id: String = "",
    @Column(name = "content_type") var contentType: String = "",
    @Column(name = "stored_path") var storedPath: String = "",
    var size: Long = 0,
    @Column(name = "created_at") var createdAt: Long = 0
)

@Entity
@Table(name = "screenshots")
class Screenshot(
    @Id var id: String = "",
    @Column(name = "device_id") var deviceId: String = "",
    @Column(name = "shot_id") var shotId: String? = null,
    @Column(name = "trigger_type") var triggerType: String? = null,
    @Column(name = "file_url") var fileUrl: String? = null,
    @Column(name = "thumbnail_url") var thumbnailUrl: String? = null,
    var width: Int? = null,
    var height: Int? = null,
    @Column(name = "captured_at") var capturedAt: Long? = null,
    @Column(name = "received_at") var receivedAt: Long = 0,
    var status: String = "READY"
)

@Entity
@Table(name = "location_tracks")
class LocationTrack(
    @Id var id: String = "",
    @Column(name = "device_id") var deviceId: String = "",
    var lat: Double? = null,
    var lng: Double? = null,
    var accuracy: Double? = null,
    var provider: String? = null,
    var ts: Long? = null,
    @Column(name = "received_at") var receivedAt: Long = 0
)

@Entity
@Table(name = "alerts")
class Alert(
    @Id var id: String = "",
    @Column(name = "device_id") var deviceId: String = "",
    @Column(name = "user_id") var userId: String? = null,
    var level: String = "INFO",
    var title: String = "",
    @Column(name = "message", columnDefinition = "text") var message: String? = null,
    var category: String? = null,
    var status: String = "ACTIVE",
    @Column(name = "detail_json", columnDefinition = "text") var detailJson: String? = null,
    @Column(name = "triggered_at") var triggeredAt: Long = 0,
    @Column(name = "acknowledged_at") var acknowledgedAt: Long? = null,
    @Column(name = "resolved_at") var resolvedAt: Long? = null
)

@Entity
@Table(name = "published_messages")
class PublishedMessage(
    @Id var id: String = "",
    @Column(name = "device_id") var deviceId: String = "",
    @Column(name = "content_type") var contentType: String = "",
    @Column(name = "text", columnDefinition = "text") var text: String? = null,
    @Column(name = "media_url") var mediaUrl: String? = null,
    @Column(name = "media_name") var mediaName: String? = null,
    @Column(name = "display_seconds") var displaySeconds: Int = 0,
    @Column(name = "full_screen") var fullScreen: Boolean = false,
    @Column(name = "play_audio") var playAudio: Boolean = false,
    @Column(name = "published_at") var publishedAt: Long = 0
)

@Entity
@Table(name = "geofences")
class Geofence(
    @Id @Column(name = "device_id") var deviceId: String = "",
    var enabled: Boolean = false,
    var name: String? = null,
    @Column(name = "center_lat") var centerLat: Double? = null,
    @Column(name = "center_lng") var centerLng: Double? = null,
    @Column(name = "radius_meters") var radiusMeters: Int? = null,
    @Column(name = "alert_on_exit") var alertOnExit: Boolean = true
)

@Entity
@Table(name = "unlock_tickets")
class UnlockTicket(
    @Id var id: String = "",
    @Column(name = "device_id") var deviceId: String = "",
    @Column(name = "user_id") var userId: String? = null,
    @Column(name = "package_name") var packageName: String? = null,
    @Column(name = "app_label") var appLabel: String? = null,
    @Column(name = "duration_minutes") var durationMinutes: Int? = null,
    @Column(name = "reason", columnDefinition = "text") var reason: String? = null,
    var status: String = "PENDING",
    @Column(name = "created_at") var createdAt: Long = 0,
    @Column(name = "resolved_at") var resolvedAt: Long? = null
)

@Entity
@Table(name = "app_policies")
class AppPolicy(
    @Id var id: String = "",
    @Column(name = "device_id") var deviceId: String = "",
    @Column(name = "package_name") var packageName: String = "",
    @Column(name = "app_name") var appName: String? = null,
    @Column(name = "is_blocked") var isBlocked: Boolean = false,
    @Column(name = "daily_limit_minutes") var dailyLimitMinutes: Int? = null
)

@Entity
@Table(name = "time_restrictions")
class TimeRestriction(
    @Id var id: String = "",
    @Column(name = "device_id") var deviceId: String = "",
    @Column(name = "day_of_week") var dayOfWeek: Int = 1,
    @Column(name = "start_time") var startTime: String = "",
    @Column(name = "end_time") var endTime: String = "",
    @Column(name = "max_minutes") var maxMinutes: Int? = null,
    @Column(name = "is_enabled") var isEnabled: Boolean = true
)

@Entity
@Table(name = "device_settings")
class DeviceSetting(
    @Id @Column(name = "device_id") var deviceId: String = "",
    @Column(name = "auto_refresh_seconds") var autoRefreshSeconds: Int = 15,
    @Column(name = "high_definition") var highDefinition: Boolean = false,
    @Column(name = "record_resolution") var recordResolution: String = "HD_720P",
    @Column(name = "record_with_audio") var recordWithAudio: Boolean = true,
    @Column(name = "allow_remote_lock") var allowRemoteLock: Boolean = true,
    @Column(name = "browser_disabled") var browserDisabled: Boolean = false,
    @Column(name = "smart_shutdown_enabled") var smartShutdownEnabled: Boolean = false,
    @Column(name = "smart_shutdown_start") var smartShutdownStart: String? = null,
    @Column(name = "smart_shutdown_end") var smartShutdownEnd: String? = null,
    @Column(name = "daily_limit_minutes") var dailyLimitMinutes: Int = 120,
    @Column(name = "web_blocked_urls", columnDefinition = "text") var webBlockedUrls: String? = null,
    // ---- 平板使用时间设置（家长端「时间管控」页，GET/PUT policies/{deviceId}/tablet-usage-settings）----
    // 可空 + null 视为默认值：存量行（迁移前创建）这些列为 NULL，读取时由服务层兜底
    @Column(name = "weekday_limit_minutes") var weekdayLimitMinutes: Int? = 120,
    @Column(name = "weekend_limit_minutes") var weekendLimitMinutes: Int? = 180,
    @Column(name = "rest_after_minutes") var restAfterMinutes: Int? = 60,
    @Column(name = "rest_duration_minutes") var restDurationMinutes: Int? = 15,
    @Column(name = "time_up_message", columnDefinition = "text") var timeUpMessage: String? = null,
    /** 可用时间段 JSON：[{"startTime":"08:00","endTime":"18:00"}]，null 表示未设置 */
    @Column(name = "enabled_time_ranges", columnDefinition = "text") var enabledTimeRangesJson: String? = null,
    /**
     * 救援通道：临时解除「系统设置锁定」中的调试与侧载限制。
     *
     * ## 为什么必须有这个开关
     * 被管控平板一旦按默认策略封死 DISALLOW_DEBUGGING_FEATURES + ADB_ENABLED=0 +
     * DISALLOW_INSTALL_UNKNOWN_SOURCES，就再也无法侧载新版 APK，也无法用 adb 救援 ——
     * 而"解除限制"这件事本身又只能由已安装的孩子端（Device Owner）执行，
     * 于是形成死锁：要升级必须先升级。此开关把解除动作做成服务端可下发指令，
     * 由孩子端下一次拉取策略时自行解封，从而打破死锁。
     *
     * 默认 false（保持最严管控）；仅在需要远程升级/救援时由家长端打开，事后应关闭。
     */
    // 可空 Boolean?：存量行在加列前不存在该列，Hibernate 把缺失列读成 NULL，
    // 声明成原始类型 boolean 会在读取时直接抛
    // "Null value was assigned to a property of primitive type" —— 整个策略接口 500。
    @Column(name = "system_lock_relaxed") var systemLockRelaxed: Boolean? = false
)

@Entity
@Table(name = "policy_templates")
class PolicyTemplate(
    @Id var id: String = "",
    var name: String = "",
    @Column(name = "description", columnDefinition = "text") var description: String? = null,
    @Column(name = "scene_type") var sceneType: String = "",
    var category: String? = null,
    @Column(name = "package_json", columnDefinition = "text") var packageJson: String? = null
)

/**
 * 设备已安装应用台账（远程运维 / 应用监控的数据底座）。
 *
 * 为什么需要一张独立的表，而不是每次让管控端实时去设备拉：
 * 1. 实时拉要下发指令并等设备回包，列表页打开要转好几秒，且设备离线时什么都看不到；
 * 2. 历史"装过什么、什么时候装的"本身就是有价值的审计信息，
 *    孩子自己卸掉的违规应用，家长应当仍能在台账里看见痕迹。
 *
 * 台账由孩子端周期性全量上报（幂等 upsert），[lastSeenAt] 用于判断"本次是否还在"，
 * 已卸载的应用保留记录但置 [installed=false]，不物理删除。
 */
@Entity
@Table(
    name = "installed_apps",
    uniqueConstraints = [UniqueConstraint(columnNames = ["device_id", "package_name"])]
)
class InstalledApp(
    @Id var id: String = "",
    @Column(name = "device_id") var deviceId: String = "",
    @Column(name = "package_name") var packageName: String = "",
    @Column(name = "app_name") var appName: String? = null,
    @Column(name = "version_name") var versionName: String? = null,
    @Column(name = "version_code") var versionCode: Long? = null,
    @Column(name = "is_system") var isSystem: Boolean = false,
    /** 当前是否仍处于安装状态（上报中缺失即置 false，不物理删除，保留审计痕迹） */
    var installed: Boolean = true,
    /** 是否被管控端挂起（DO 的 setPackagesSuspended，不是卸载） */
    var suspended: Boolean = false,
    @Column(name = "install_time") var installTime: Long? = null,
    @Column(name = "update_time") var updateTime: Long? = null,
    @Column(name = "first_seen_at") var firstSeenAt: Long = 0,
    @Column(name = "last_seen_at") var lastSeenAt: Long = 0
)

@Entity
@Table(name = "media_tasks")
class MediaTask(
    @Id var id: String = "",
    @Column(name = "device_id") var deviceId: String = "",
    var kind: String = "",
    var status: String = "PENDING",
    var url: String? = null,
    @Column(name = "mime_type") var mimeType: String? = null,
    var size: Long? = null,
    @Column(name = "duration_seconds") var durationSeconds: Int? = null,
    @Column(name = "started_at") var startedAt: Long = 0,
    @Column(name = "finished_at") var finishedAt: Long? = null
)
