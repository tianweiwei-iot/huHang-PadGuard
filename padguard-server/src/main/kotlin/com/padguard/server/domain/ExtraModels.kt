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
    @Column(name = "created_at") var createdAt: Long = 0,
    /**
     * 归属：家长上传者的 userId（媒体上传、APK 分发、统计导出）。
     *
     * 文件下载路径为了让**孩子端**能取到家长发布的素材而整体放开了鉴权，
     * 于是这段路径上的儿童截图、录屏、家长导出的报表变成"拿到 id 即可下载"。
     * 记下归属后，带凭证的请求就能精确校验：别人的文件即使拿到 id 也打不开。
     * 可空：历史数据没有归属，仍按"能力 URL"放行（见 FilesController 的判定）。
     */
    @Column(name = "owner_user_id") var ownerUserId: String? = null,
    /** 归属：孩子端上报文件所属的设备（截图 / 录音 / 录屏 / 头像）。同理用于下载校验。 */
    @Column(name = "device_id") var deviceId: String? = null
)

@Entity
// 远程截图回执按 (device_id, shot_id) 精确回查，是截图链路每次都要走的点查。
@Table(
    name = "screenshots",
    indexes = [
        Index(name = "idx_screenshots_device_id", columnList = "device_id, captured_at"),
        Index(name = "idx_screenshots_device_shot", columnList = "device_id, shot_id")
    ]
)
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
    /** 逆地理编码得到的文字地址（WGS-84 坐标经高德 Web 接口反查）。缺 Key 或反查失败时为空。 */
    @Column(name = "address", length = 255) var address: String? = null,
    @Column(name = "received_at") var receivedAt: Long = 0
)

@Entity
// 家长端告警列表按「用户 + 设备 + 状态」过滤。缺复合索引时只能在内存里
// 先捞出该用户全部告警再 filter deviceId，数据一多就是全表扫描。
@Table(
    name = "alerts",
    indexes = [
        Index(name = "idx_alerts_device_id", columnList = "device_id"),
        Index(name = "idx_alerts_user_status", columnList = "user_id, status"),
        Index(name = "idx_alerts_user_device_status", columnList = "user_id, device_id, status")
    ]
)
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

/**
 * 电子围栏（多围栏，一对多：一台设备可配置多个安全区域）。
 *
 * 主键改用自增 UUID（[id]），[deviceId] 仅为归属字段并建索引；这样同一设备能存多条围栏。
 * 旧版单围栏表 `geofences`（PK=device_id）已在迁移脚本 `V2026101202__geofence_zones.sql`
 * 中整表迁移到本表并删除。
 */
@Entity
@Table(name = "geofence_zones")
class GeofenceZone(
    @Id var id: String = java.util.UUID.randomUUID().toString(),
    @Column(name = "device_id") var deviceId: String = "",
    var enabled: Boolean = false,
    var name: String? = null,
    @Column(name = "center_lat") var centerLat: Double? = null,
    @Column(name = "center_lng") var centerLng: Double? = null,
    @Column(name = "radius_meters") var radiusMeters: Int? = null,
    /** 离开安全区时告警（默认开，与旧版行为一致）。 */
    @Column(name = "alert_on_exit") var alertOnExit: Boolean = true,
    /** 进入安全区时告警（默认关，P-L2 新增；多数家庭仅在"离家/离校"时关心）。 */
    @Column(name = "alert_on_enter") var alertOnEnter: Boolean = false,
    @Column(name = "created_at") var createdAt: Long = 0
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
    /**
     * 护眼模式：是否启用。
     *
     * 孩子端以悬浮滤镜的方式实现（一层暖色半透明遮罩），不依赖任何 Device Owner 能力 ——
     * 本项目绝大多数设备跑在没有 DO 的降级态，只能走这条路。
     */
    @Column(name = "eye_protection_enabled") var eyeProtectionEnabled: Boolean = false,
    /** 滤镜强度 1~5：1 近乎无感，5 最暖最暗。越界值由服务层夹到范围内。 */
    @Column(name = "eye_protection_level") var eyeProtectionLevel: Int = 3,
    /**
     * 应用名单模式：BLACKLIST（只管名单内的）/ WHITELIST（只放行名单内的）。
     *
     * 之前 [PolicyExtensionService.rebuildPolicy] 把 mode 硬编码成 BLACKLIST，
     * 家长端即使选了白名单也下发不出去 —— 白名单模式形同虚设。
     * 可空 + null 视为 BLACKLIST：存量行为 NULL 时保持原有黑名单语义，不改变既有行为。
     */
    @Column(name = "app_list_mode") var appListMode: String? = "BLACKLIST",
    /**
     * 非 DO 设备上，打开受管控应用是否要求输入管理密码。
     *
     * 只在**无 Device Owner** 时有意义：有 DO 时管控由系统完成（隐藏/挂起），
     * 根本走不到"打开"这一步；无 DO 时由无障碍服务在启动受管控应用时要求密码。
     * 默认 false —— 白名单模式下每开一个应用都输密码对多数家庭过重，是否开启由家长决定。
     */
    @Column(name = "require_password_on_launch") var requirePasswordOnLaunch: Boolean? = false,
    @Column(name = "web_blocked_urls", columnDefinition = "text") var webBlockedUrls: String? = null,
    /**
     * 内容管控：搜索引擎强制安全搜索（P2）。
     * 开启后策略包下发 `web.safeSearch=true`，孩子端对主流搜索引擎注入 safe 参数。
     * 默认值 false（不强制），由家长在「上网管控」页开启。
     */
    // 可空 Boolean?：存量行（迁移/加列前创建）该列不存在或为 NULL，Hibernate 会把缺失列读成 NULL，
    // 若声明成原始类型 boolean 会在读取时直接抛
    // "Null value was assigned to a property of primitive type" —— 所有依赖 deviceSettingOf 的接口（时间管控、
    // 上网管控、策略包重建）全部 500，孩子端因此拿不到策略包。写法与同类 systemLockRelaxed 保持一致。
    @Column(name = "web_safe_search") var webSafeSearch: Boolean? = false,
    /**
     * 内容管控：按分类屏蔽（P2）。逗号分隔的 url_categories.id 列表。
     * 命中这些分类的网址在设备端被拦截。空表示不按分类屏蔽。
     */
    @Column(name = "web_blocked_categories", columnDefinition = "text") var webBlockedCategories: String? = null,
    // ---- 平板使用时间设置（家长端「时间管控」页，GET/PUT policies/{deviceId}/tablet-usage-settings）----
    /** 启用时间管控总开关：false 时孩子端不按时长/时段执行管控。缺省为 true（存量行迁移后默认开启）。 */
    @Column(name = "time_control_enabled") var timeControlEnabled: Boolean = true,
    // 可空 + null 视为默认值：存量行（迁移前创建）这些列为 NULL，读取时由服务层兜底
    @Column(name = "weekday_limit_minutes") var weekdayLimitMinutes: Int? = 120,
    @Column(name = "weekend_limit_minutes") var weekendLimitMinutes: Int? = 180,
    @Column(name = "rest_after_minutes") var restAfterMinutes: Int? = 60,
    @Column(name = "rest_duration_minutes") var restDurationMinutes: Int? = 15,
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
    @Column(name = "system_lock_relaxed") var systemLockRelaxed: Boolean? = false,
    /**
     * 非 DO 设备：安装 / 卸载确认页是否要求管理密码（无障碍软拦截）。
     *
     * 只在**无 Device Owner** 时有意义：有 DO 时安装/卸载由系统限制（DISALLOW_INSTALL_APPS 等）管控，
     * 根本走不到「确认页」这一步；无 DO 时由无障碍服务在检测到进入 packageinstaller 确认页时弹密码框并顶回。
     * 默认 true —— 安装/卸载默认需要密码（核心安全能力），家长可在「应用管理」页关闭。
     * 注意：与同表的 [requirePasswordOnLaunch] 一样，仅当家长已设置「设备操作密码」时该闸门才会真正生效
     * （孩子端 [GuardPasswordManager.isEnabledNow] 为 false 时一律放行）。
     */
    @Column(name = "require_password_on_install") var requirePasswordOnInstall: Boolean? = true,
    @Column(name = "require_password_on_uninstall") var requirePasswordOnUninstall: Boolean? = true
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
    uniqueConstraints = [UniqueConstraint(columnNames = ["device_id", "package_name"])],
    indexes = [Index(name = "idx_installed_apps_device", columnList = "device_id, installed")]
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
    /**
     * 是否被隐藏（家长关闭使用权限，孩子端桌面不再显示；应用仍在，不是卸载）
     *
     * 必须带数据库默认值：家庭部署包用的是 `ddl-auto=update`，
     * 老库里已经有台账数据时，`add column hidden boolean not null` 会因为
     * 无法给既有行填充 NOT NULL 而执行失败（H2 报 NULL not allowed），
     * 之后每次查询该表都报 "Column hidden not found"，整个应用列表接口 500。
     */
    @org.hibernate.annotations.ColumnDefault("false")
    var hidden: Boolean = false,
    @Column(name = "install_time") var installTime: Long? = null,
    @Column(name = "update_time") var updateTime: Long? = null,
    @Column(name = "first_seen_at") var firstSeenAt: Long = 0,
    @Column(name = "last_seen_at") var lastSeenAt: Long = 0,
    /** 应用图标的可访问地址（空表示尚未采集到） */
    @Column(name = "icon_url") var iconUrl: String? = null,
    /**
     * 图标内容哈希。
     * 孩子端每 30 分钟全量上报一次，若每次都重新落盘，
     * 上百个图标会反复产生文件写入并堆满磁盘。只在哈希变化时覆盖。
     */
    @Column(name = "icon_hash") var iconHash: String? = null
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

/**
 * 审计日志（P4-5）：记录"谁在何时对哪台设备做了什么"。
 *
 * 触发点覆盖绑定/解绑、策略变更、解锁审批、指令下发等关键操作，作为合规追溯与责任界定的底座。
 * 写入采用独立事务（REQUIRES_NEW），且记录失败不影响主业务流程（由 [com.padguard.server.service.AuditService] 兜底）。
 */
@Entity
// 审计日志只增不删，是增长最快的表之一；家长端按「用户/设备 + 时间倒序」分页查询，
// 必须走 (device_id, created_at) / (user_id, created_at) 索引，否则每次翻页都全表捞。
@Table(
    name = "audit_logs",
    indexes = [
        Index(name = "idx_audit_logs_user", columnList = "user_id, created_at"),
        Index(name = "idx_audit_logs_device", columnList = "device_id, created_at")
    ]
)
class AuditLog(
    @Id var id: String = "",
    @Column(name = "user_id") var userId: String? = null,
    @Column(name = "device_id") var deviceId: String? = null,
    @Column(name = "action") var action: String = "",
    @Column(name = "target_type") var targetType: String? = null,
    @Column(name = "target_id") var targetId: String? = null,
    @Column(name = "detail", columnDefinition = "text") var detail: String? = null,
    @Column(name = "result") var result: String = "SUCCESS",
    @Column(name = "ip") var ip: String? = null,
    @Column(name = "created_at") var createdAt: Long = 0
)

/**
 * 网址分类库（P2）。本地库 + 云端分类 API 的落库缓存。
 * 风险等级 risk_level：LOW / MEDIUM / HIGH；blocked_by_default 表示命中即默认拦截（如黄赌毒）。
 */
@Entity
@Table(name = "url_categories")
class UrlCategory(
    @Id var id: String = "",
    var name: String = "",
    @Column(name = "description", columnDefinition = "text") var description: String? = null,
    @Column(name = "risk_level") var riskLevel: String = "MEDIUM",
    @Column(name = "blocked_by_default") var blockedByDefault: Boolean = false,
    @Column(name = "created_at") var createdAt: Long = 0
)

/**
 * 敏感词库（P2）。文本鉴黄/涉赌/暴力/诈骗等的本地词典，作为云端 AI 识别的本地兜底。
 * 命中后生成告警（涉密内容只告警、不全文展示）。
 */
@Entity
@Table(name = "sensitive_words")
class SensitiveWord(
    @Id var id: String = "",
    @Column(name = "word") var word: String = "",
    @Column(name = "category") var category: String = "PORN",
    @Column(name = "level") var level: String = "HIGH",
    @Column(name = "created_at") var createdAt: Long = 0
)
