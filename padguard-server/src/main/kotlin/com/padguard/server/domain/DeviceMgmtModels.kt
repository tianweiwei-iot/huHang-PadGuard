package com.padguard.server.domain

import jakarta.persistence.*

/**
 * 应用目录库（应用分发 / 远程升级的"物料"来源）。
 *
 * 为什么需要它：早期家长端只能从**自己手机本地**选一个 APK 临时下发
 * （[com.padguard.server.service.AppManageService.installApp]），每次换设备都要重新选文件，
 * 也无法对比"线上已装版本"决定要不要升级。目录库把 APK 托管在服务端、
 * 按账号统一维护一套"可下发的应用"，从而支撑：
 *   1. 应用分发：从目录里选一个应用，一键推送到多台被管控平板；
 *   2. 远程升级：目录里同一包名的新版本号高于设备已装版本号时，提示/触发升级。
 *
 * 归属到 userId（账号）而非 deviceId：一套应用物料可下发给该账号名下的多台设备。
 */
@Entity
@Table(
    name = "app_catalog",
    indexes = [
        Index(name = "idx_app_catalog_user", columnList = "user_id"),
        Index(name = "idx_app_catalog_user_pkg", columnList = "user_id, package_name")
    ]
)
class AppCatalog(
    @Id var id: String = "",
    @Column(name = "user_id") var userId: String = "",
    @Column(name = "package_name") var packageName: String = "",
    @Column(name = "app_name") var appName: String? = null,
    @Column(name = "version_code") var versionCode: Long = 0,
    @Column(name = "version_name") var versionName: String? = null,
    /** uploaded_files.id：APK 物理文件，下载地址由 [com.padguard.server.service.FileStorageService.url] 还原 */
    @Column(name = "file_id") var fileId: String = "",
    @Column(name = "file_name") var fileName: String? = null,
    @Column(name = "size_bytes") var sizeBytes: Long = 0,
    @Column(name = "icon_url") var iconUrl: String? = null,
    @Column(name = "description", columnDefinition = "text") var description: String? = null,
    @Column(name = "created_at") var createdAt: Long = 0,
    @Column(name = "updated_at") var updatedAt: Long = 0
)

/**
 * 应用分发 / 远程升级任务记录。
 *
 * 每条记录对应一次 INSTALL_APP 指令下发，落库是为了让家长端能回看
 * "给哪台设备、哪个版本、下发了没、成功没"，而不是只拿到一个 msgId 就再无下文。
 * status 由孩子端回执经 [com.padguard.server.service.CommandService] 收敛后回填。
 */
@Entity
@Table(
    name = "app_distribution",
    indexes = [
        Index(name = "idx_app_distribution_device", columnList = "device_id"),
        Index(name = "idx_app_distribution_user", columnList = "user_id")
    ]
)
class AppDistribution(
    @Id var id: String = "",
    @Column(name = "user_id") var userId: String = "",
    @Column(name = "device_id") var deviceId: String = "",
    /** 关联的目录库条目；本地临时安装时为 null */
    @Column(name = "catalog_id") var catalogId: String? = null,
    @Column(name = "package_name") var packageName: String = "",
    @Column(name = "version_code") var versionCode: Long = 0,
    /** INSTALL = 首次分发；UPGRADE = 远程升级（版本号高于已装） */
    @Column(name = "kind") var kind: String = "INSTALL",
    @Column(name = "msg_id") var msgId: String? = null,
    @Column(name = "status") var status: String = "PENDING",
    @Column(name = "error_code") var errorCode: String? = null,
    @Column(name = "error_message", columnDefinition = "text") var errorMessage: String? = null,
    @Column(name = "created_at") var createdAt: Long = 0,
    @Column(name = "finished_at") var finishedAt: Long? = null
)

/**
 * 设备权限策略（权限管理模块）。
 *
 * 记录家长在管控台为某台设备设定的"运行时权限授予集合"。
 * 字段是**期望态**：家长改了就下发 [com.padguard.server.service.CommandType.SET_RUNTIME_PERMISSION]，
 * 孩子端据此一次性授予/收回（DO/PO 才能改别人的权限）。
 *
 * granted_permissions 存逗号分隔的 Android 权限名（如 android.permission.CAMERA）。
 * 目标包默认是孩子端自身（监控需要相机/定位/麦克风），[targetPackage] 可指定其它受管应用。
 */
@Entity
@Table(name = "device_permission_policy")
class DevicePermissionPolicy(
    @Id @Column(name = "device_id") var deviceId: String = "",
    /** 权限授予的目标包；缺省为受管控端自身 */
    @Column(name = "target_package") var targetPackage: String? = null,
    @Column(name = "granted_permissions", columnDefinition = "text") var grantedPermissions: String = "",
    @Column(name = "updated_at") var updatedAt: Long = 0
)

/**
 * 设备功能策略（功能管理模块）。
 *
 * 记录家长在管控台为某台设备开关的"系统功能"，以 JSON 存一组 featureKey -> enabled。
 * 下发 [com.padguard.server.service.CommandType.SET_FEATURE_POLICY] 时整包下发给孩子端，
 * 孩子端逐项映射成 DPM 用户限制 / 相机截屏开关等并应用，关掉的就还原。
 */
@Entity
@Table(name = "device_feature_policy")
class DeviceFeaturePolicy(
    @Id @Column(name = "device_id") var deviceId: String = "",
    @Column(name = "features_json", columnDefinition = "text") var featuresJson: String = "{}",
    @Column(name = "updated_at") var updatedAt: Long = 0
)
