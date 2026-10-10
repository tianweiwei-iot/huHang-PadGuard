package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.AppCatalog
import com.padguard.server.domain.AppDistribution
import com.padguard.server.dto.*
import com.padguard.server.repository.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

/**
 * 设备管理增强：应用目录库 + 一键分发 + 远程升级 + 权限管理 + 功能管理。
 *
 * 设计要点：
 * 1. 分发/升级复用既有 [CommandType.INSTALL_APP] 指令与孩子端 [com.padguard.core.engine.command.CommandExecutor]，
 *    服务端不中转 APK，孩子端从 `/v1/files/{fileId}` 自行下载安装（DO/PO 静默通道）。
 * 2. 权限/功能策略是**期望态**：家长改动即下发对应指令，同时把期望态落库，
 *    指令只下发真正变化的部分（权限做差量、功能整包下发由孩子端全量覆盖）。
 * 3. 所有写操作都先 `requireOwned` 校验设备归属，避免越权往他人设备下发指令。
 */
@Service
class DeviceMgmtService(
    private val fileStorageService: FileStorageService,
    private val deviceRepository: DeviceRepository,
    private val ownershipGuard: com.padguard.server.security.DeviceOwnershipGuard,
    private val installedAppRepository: InstalledAppRepository,
    private val appCatalogRepository: AppCatalogRepository,
    private val appDistributionRepository: AppDistributionRepository,
    private val permissionPolicyRepository: DevicePermissionPolicyRepository,
    private val featurePolicyRepository: DeviceFeaturePolicyRepository,
    private val commandService: CommandService,
    private val objectMapper: com.fasterxml.jackson.databind.ObjectMapper
) {

    private val log = LoggerFactory.getLogger(DeviceMgmtService::class.java)

    // ==================== 应用目录库 ====================

    /**
     * 上传一个 APK 进目录库（按账号维护）。
     *
     * 包名/版本号由家长端从 APK 中解析后随表单带来（Android 端用 PackageManager 可读），
     * 服务端不内嵌 APK 解析器。同一包名重复上传视为"更新物料"：保留多条记录，
     * 升级判定取该包名 versionCode 最大的一条。
     */
    fun uploadCatalog(
        userId: String, deviceId: String,
        file: MultipartFile,
        packageName: String, versionCode: Long, versionName: String?,
        appName: String?, description: String?, iconUrl: String?
    ): AppCatalogDto {
        requireOwned(userId, deviceId)
        if (file.isEmpty) throw BizException(ParentErr.PARAM_ERROR, "APK 文件为空", Audience.PARENT)
        if (packageName.isBlank()) throw BizException(ParentErr.PARAM_ERROR, "包名不能为空", Audience.PARENT)
        val mime = "application/vnd.android.package-archive"
        val stored = fileStorageService.storeDetailed(
            mime, file.bytes, file.originalFilename ?: "$packageName.apk", ownerUserId = userId
        )
        val now = System.currentTimeMillis()
        val entity = AppCatalog(
            id = UUID.randomUUID().toString(),
            userId = userId,
            packageName = packageName,
            appName = appName ?: packageName,
            versionCode = versionCode,
            versionName = versionName,
            fileId = stored.id,
            fileName = stored.name,
            sizeBytes = file.size,
            iconUrl = iconUrl,
            description = description,
            createdAt = now,
            updatedAt = now
        )
        appCatalogRepository.save(entity)
        log.info("catalog uploaded: user={} pkg={} vc={} fileId={}", userId, packageName, versionCode, stored.id)
        return toCatalogDto(entity)
    }

    fun listCatalog(userId: String, deviceId: String): List<AppCatalogDto> {
        requireOwned(userId, deviceId)
        return appCatalogRepository.findByUserId(userId)
            .sortedWith(compareBy({ it.packageName }, { -it.versionCode }))
            .map { toCatalogDto(it) }
    }

    fun deleteCatalog(userId: String, deviceId: String, catalogId: String) {
        requireOwned(userId, deviceId)
        val entity = appCatalogRepository.findById(catalogId).orElse(null)
            ?: throw BizException(ParentErr.PARAM_ERROR, "目录条目不存在", Audience.PARENT)
        if (entity.userId != userId) throw BizException(ParentErr.DEVICE_NOT_OWNED, "无权操作该条目", Audience.PARENT)
        appCatalogRepository.delete(entity)
    }

    // ==================== 一键分发 / 远程升级 ====================

    /**
     * 一键分发：从目录库取一个 APK，下发 INSTALL_APP 到目标设备。
     *
     * force=false 时，若设备已装同包名且版本号不低于目录版本，则视为"已是最新"直接返回，
     * 避免重复下发；force=true 才无条件下发（用于"重新安装"）。
     */
    fun distribute(userId: String, deviceId: String, catalogId: String, force: Boolean): AppDistributionDto {
        requireOwned(userId, deviceId)
        val catalog = appCatalogRepository.findById(catalogId).orElse(null)
            ?: throw BizException(ParentErr.PARAM_ERROR, "目录条目不存在", Audience.PARENT)
        if (catalog.userId != userId) throw BizException(ParentErr.DEVICE_NOT_OWNED, "无权操作该条目", Audience.PARENT)
        if (!force) {
            val installed = installedAppRepository.findByDeviceIdAndPackageName(deviceId, catalog.packageName)
            if (installed != null && (installed.versionCode ?: 0) >= catalog.versionCode) {
                throw BizException(ParentErr.PARAM_ERROR, "设备已安装相同或更高版本，无需分发", Audience.PARENT)
            }
        }
        return issueInstall(userId, deviceId, catalog, kind = "INSTALL")
    }

    /**
     * 升级检查：对目录库里每个包名，取 versionCode 最大的一条，与设备已装版本比较。
     */
    fun upgradeCheck(userId: String, deviceId: String): List<UpgradeCheckItem> {
        requireOwned(userId, deviceId)
        val catalogs = appCatalogRepository.findByUserId(userId)
        val latestByPkg = catalogs.groupBy { it.packageName }.mapValues { (_, list) -> list.maxByOrNull { it.versionCode }!! }
        return latestByPkg.map { (pkg, catalog) ->
            val installed = installedAppRepository.findByDeviceIdAndPackageName(deviceId, pkg)
            val installedVc = installed?.versionCode ?: 0
            UpgradeCheckItem(
                catalogId = catalog.id,
                packageName = pkg,
                appName = catalog.appName,
                catalogVersionCode = catalog.versionCode,
                catalogVersionName = catalog.versionName,
                installedVersionCode = installedVc,
                installedVersionName = installed?.versionName,
                upgradeAvailable = installedVc > 0 && catalog.versionCode > installedVc
            )
        }.sortedBy { it.appName ?: it.packageName }
    }

    /**
     * 远程升级：仅当目录版本高于设备已装版本时下发；否则提示已是最新。
     */
    fun upgrade(userId: String, deviceId: String, catalogId: String): AppDistributionDto {
        requireOwned(userId, deviceId)
        val catalog = appCatalogRepository.findById(catalogId).orElse(null)
            ?: throw BizException(ParentErr.PARAM_ERROR, "目录条目不存在", Audience.PARENT)
        if (catalog.userId != userId) throw BizException(ParentErr.DEVICE_NOT_OWNED, "无权操作该条目", Audience.PARENT)
        val installed = installedAppRepository.findByDeviceIdAndPackageName(deviceId, catalog.packageName)
        val installedVc = installed?.versionCode ?: 0
        if (installedVc <= 0 || catalog.versionCode <= installedVc) {
            throw BizException(ParentErr.PARAM_ERROR, "设备未安装该应用或已是最新版本", Audience.PARENT)
        }
        return issueInstall(userId, deviceId, catalog, kind = "UPGRADE")
    }

    private fun issueInstall(userId: String, deviceId: String, catalog: AppCatalog, kind: String): AppDistributionDto {
        val apkUrl = fileStorageService.url(catalog.fileId)
        val cmd = commandService.issueCommand(
            deviceId, CommandType.INSTALL_APP,
            mapOf(
                CommandKey.APK_URL to apkUrl,
                CommandKey.PACKAGE_NAME to catalog.packageName,
                CommandKey.VERSION_CODE to catalog.versionCode
            )
        )
        val now = System.currentTimeMillis()
        val record = AppDistribution(
            id = UUID.randomUUID().toString(),
            userId = userId, deviceId = deviceId, catalogId = catalog.id,
            packageName = catalog.packageName, versionCode = catalog.versionCode,
            kind = kind, msgId = cmd.msgId, status = "PENDING", createdAt = now
        )
        appDistributionRepository.save(record)
        // 预置台账：安装完成前家长端也应看到"分发中"的目标
        val entity = installedAppRepository.findByDeviceIdAndPackageName(deviceId, catalog.packageName)
            ?: AppCatalogInstalledSeed(deviceId, catalog).also { installedAppRepository.save(it) }
        entity.installed = false
        entity.versionCode = catalog.versionCode
        entity.lastSeenAt = now
        installedAppRepository.save(entity)
        return toDistributionDto(record)
    }

    /** 临时构造 InstalledApp 种子（与 AppManageService.installApp 同口径） */
    private fun AppCatalogInstalledSeed(deviceId: String, catalog: AppCatalog): com.padguard.server.domain.InstalledApp =
        com.padguard.server.domain.InstalledApp(
            id = UUID.randomUUID().toString(), deviceId = deviceId, packageName = catalog.packageName,
            appName = catalog.appName, isSystem = false, firstSeenAt = System.currentTimeMillis()
        )

    // ==================== 权限管理 ====================

    fun getPermissions(userId: String, deviceId: String): PermissionPolicyDto {
        requireOwned(userId, deviceId)
        val entity = permissionPolicyRepository.findByDeviceId(deviceId)
        return PermissionPolicyDto(
            targetPackage = entity?.targetPackage,
            permissions = entity?.grantedPermissions?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
        )
    }

    /**
     * 设置权限策略：按"当前已存集合"做差量下发。
     * 新增的权限 -> 授予；移除的权限 -> 收回；不变的 -> 不下发。
     * 目标包为空时孩子端按"自身（受管控端）"处理。
     */
    fun setPermissions(userId: String, deviceId: String, req: PermissionPolicyDto): PermissionPolicyDto {
        requireOwned(userId, deviceId)
        val current = permissionPolicyRepository.findByDeviceId(deviceId)
        val currentSet = current?.grantedPermissions?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        val nextSet = req.permissions.map { it.trim() }.filter { it.isNotBlank() }.toSet()

        val target = (req.targetPackage?.takeIf { it.isNotBlank() } ?: current?.targetPackage)?.takeIf { it.isNotBlank() }
        val toGrant = (nextSet - currentSet)
        val toRevoke = (currentSet - nextSet)

        toGrant.forEach { perm ->
            commandService.issueCommand(
                deviceId, CommandType.SET_RUNTIME_PERMISSION,
                mapOf(
                    CommandKey.PACKAGE_NAME to (target ?: ""),
                    CommandKey.PERMISSION to perm,
                    CommandKey.GRANT to true
                )
            )
        }
        toRevoke.forEach { perm ->
            commandService.issueCommand(
                deviceId, CommandType.SET_RUNTIME_PERMISSION,
                mapOf(
                    CommandKey.PACKAGE_NAME to (target ?: ""),
                    CommandKey.PERMISSION to perm,
                    CommandKey.GRANT to false
                )
            )
        }

        val now = System.currentTimeMillis()
        val entity = current ?: com.padguard.server.domain.DevicePermissionPolicy(deviceId = deviceId)
        entity.targetPackage = target
        entity.grantedPermissions = nextSet.joinToString(",")
        entity.updatedAt = now
        permissionPolicyRepository.save(entity)

        log.info("permissions set: device={} target={} grant={} revoke={}", deviceId, target, toGrant, toRevoke)
        return PermissionPolicyDto(targetPackage = target, permissions = nextSet.toList())
    }

    // ==================== 功能管理 ====================

    fun getFeatures(userId: String, deviceId: String): FeaturePolicyDto {
        requireOwned(userId, deviceId)
        val entity = featurePolicyRepository.findByDeviceId(deviceId) ?: return FeaturePolicyDto(emptyMap())
        val map = runCatching {
            @Suppress("UNCHECKED_CAST")
            val raw = objectMapper.readValue(entity.featuresJson, Map::class.java) as Map<String, Any?>
            raw.mapValues { (_, v) -> v?.toString()?.toBooleanStrictOrNull() ?: false }
        }.getOrElse { emptyMap() }
        return FeaturePolicyDto(features = map)
    }

    /**
     * 设置功能策略：整包下发 featureKey -> true/false，孩子端逐项应用（关掉的还原）。
     * 这里只负责把期望态落库 + 下发指令；真正能力是否生效由孩子端回执决定。
     */
    fun setFeatures(userId: String, deviceId: String, req: FeaturePolicyDto): FeaturePolicyDto {
        requireOwned(userId, deviceId)
        // 只允许已知 featureKey 下发，防止任意字符串污染指令 payload
        val known = setOf(
            CommandKey.FEATURE_CAMERA_DISABLED,
            CommandKey.FEATURE_SCREENSHOT_DISABLED,
            CommandKey.FEATURE_FACTORY_RESET_DISABLED,
            CommandKey.FEATURE_SAFE_BOOT_DISABLED,
            CommandKey.FEATURE_UNKNOWN_SOURCES_DISABLED,
            CommandKey.FEATURE_CONFIG_DATETIME_DISABLED,
            CommandKey.FEATURE_USB_DEBUG_DISABLED,
            CommandKey.FEATURE_USB_DATA_DISABLED,
            CommandKey.FEATURE_STATUS_BAR_DISABLED,
            CommandKey.FEATURE_AUTO_TIME_ENFORCED
        )
        val filtered = req.features.filterKeys { it in known }
        val payload = filtered.mapValues { (_, v) -> v } // Boolean -> toString "true"/"false"
        commandService.issueCommand(deviceId, CommandType.SET_FEATURE_POLICY, payload)

        val now = System.currentTimeMillis()
        val entity = featurePolicyRepository.findByDeviceId(deviceId)
            ?: com.padguard.server.domain.DeviceFeaturePolicy(deviceId = deviceId)
        entity.featuresJson = objectMapper.writeValueAsString(filtered)
        entity.updatedAt = now
        featurePolicyRepository.save(entity)

        log.info("features set: device={} features={}", deviceId, filtered)
        return FeaturePolicyDto(features = filtered)
    }

    // ==================== 工具 ====================

    private fun toCatalogDto(e: AppCatalog) = AppCatalogDto(
        id = e.id, packageName = e.packageName, appName = e.appName,
        versionCode = e.versionCode, versionName = e.versionName,
        apkUrl = fileStorageService.url(e.fileId), fileName = e.fileName,
        sizeBytes = e.sizeBytes, iconUrl = e.iconUrl, description = e.description,
        createdAt = e.createdAt, updatedAt = e.updatedAt
    )

    private fun toDistributionDto(r: AppDistribution) = AppDistributionDto(
        id = r.id, deviceId = r.deviceId, catalogId = r.catalogId,
        packageName = r.packageName, versionCode = r.versionCode, kind = r.kind,
        msgId = r.msgId, status = r.status, errorCode = r.errorCode,
        errorMessage = r.errorMessage, createdAt = r.createdAt, finishedAt = r.finishedAt
    )

    /**
     * 设备归属校验已收敛至 [com.padguard.server.security.DeviceOwnershipGuard]（唯一实现），
     * 消除逐 Service 复制私有副本的漏网风险（详见该组件类注释）。
     */
    private fun requireOwned(userId: String, deviceId: String) =
        ownershipGuard.requireOwned(userId, deviceId)
}
