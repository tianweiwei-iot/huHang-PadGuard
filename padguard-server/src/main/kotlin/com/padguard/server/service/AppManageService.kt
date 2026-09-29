package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.AppPolicy
import com.padguard.server.domain.InstalledApp
import com.padguard.server.dto.AppHiddenBatchRequest
import com.padguard.server.dto.AppHiddenRequest
import com.padguard.server.dto.AppInstallRequest
import com.padguard.server.dto.AppInventoryRequest
import com.padguard.server.dto.AppLimitRequest
import com.padguard.server.dto.InstalledAppDto
import com.padguard.server.repository.AppPolicyRepository
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.InstalledAppRepository
import com.padguard.server.service.PolicyExtensionService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * 应用监控 + 远程安装维护。
 *
 * ## 数据从哪来
 * 已安装应用台账由**孩子端主动上报**，不是家长端下发指令去问。
 * 区别在于：下发指令必须等设备在线才拿得到，而上报的心跳/日志通道本来就在跑，
 * 顺带把清单带上来几乎不增加成本，还能保证设备离线后管控端仍看得到最后一次的快照。
 *
 * ## 为什么卸载后不物理删除记录
 * 孩子自己卸掉某个违规应用是很常见的对抗手段。若台账跟着删除，
 * 家长就永远看不到"曾经装过"这件事，事后复盘无从谈起。
 * 因此上报里缺失的包只置 `installed=false`，记录永久保留。
 *
 * ## 指令语义
 * 安装/卸载/挂起都是**异步**的：这里只负责签发指令并返回 msgId，
 * 真实结果由孩子端通过日志上行通道回传。
 * 返回 msgId 而不是返回"成功"，是为了让管控端能用 `/v1/commands/{msgId}` 轮询到终态，
 * 而不是拿到一个永远为真的假成功。
 */
@Service
class AppManageService(
    private val fileStorageService: FileStorageService,
    private val deviceRepository: DeviceRepository,
    private val installedAppRepository: InstalledAppRepository,
    private val appPolicyRepository: AppPolicyRepository,
    private val commandService: CommandService,
    private val policyExtensionService: PolicyExtensionService
) {

    private val log = LoggerFactory.getLogger(AppManageService::class.java)

    // ==================== 孩子端上报 ====================

    /**
     * 全量同步应用台账（幂等）。
     *
     * @return 本次新增 / 更新 / 标记卸载的数量，便于排障时判断"上报有没有真的生效"
     */
    fun syncInventory(deviceId: String, req: AppInventoryRequest): Map<String, Int> {
        if (!deviceRepository.existsById(deviceId)) {
            throw BizException(ParentErr.DEVICE_NOT_FOUND, "设备不存在", Audience.CHILD)
        }
        val now = System.currentTimeMillis()
        val existing = installedAppRepository.findByDeviceId(deviceId)
            .associateBy { it.packageName }
            .toMutableMap()

        var inserted = 0
        var updated = 0
        val seen = HashSet<String>()

        for (item in req.apps) {
            val pkg = item.packageName.takeIf { it.isNotBlank() } ?: continue
            seen.add(pkg)
            val current = existing[pkg]
            if (current == null) {
                installedAppRepository.save(
                    InstalledApp(
                        id = UUID.randomUUID().toString(),
                        deviceId = deviceId,
                        packageName = pkg,
                        appName = item.appName,
                        versionName = item.versionName,
                        versionCode = item.versionCode,
                        isSystem = item.isSystem ?: false,
                        installed = true,
                        installTime = item.installTime,
                        updateTime = item.updateTime,
                        firstSeenAt = now,
                        lastSeenAt = now,
                        iconUrl = storeIcon(pkg, item.iconBase64),
                        iconHash = iconHash(item.iconBase64),
                        // 开启管控后新上报的应用默认处于「未授权 / 隐藏」状态：
                        // 孩子端只有被管控端显式授权的应用才可见可用，其余一律隐藏。
                        hidden = true
                    )
                )
                // 新应用默认隐藏：下发隐藏指令让孩子端真正不显示该应用。
                // 家长在管控端「授权」后会收到 SET_APP_HIDDEN(false) 取消隐藏。
                commandService.issueCommand(
                    deviceId, CommandType.SET_APP_HIDDEN,
                    mapOf(CommandKey.PACKAGE_NAME to pkg, CommandKey.HIDDEN to true)
                )
                inserted++
            } else {
                // 图标只在内容真的变了时才重新落盘（见 [storeIcon] 说明）
                val (hash, url) = iconUpdate(pkg, item.iconBase64, current.iconHash)
                // 只在字段真的变了时才写库：孩子端上报频率不低，
                // 无条件 save 会让每次上报都产生 N 条 UPDATE，白白拖慢数据库。
                val iconChanged = url != null && hash != current.iconHash
                // 隐藏状态永远以设备上报为准：家长可能在设备离线时点过开关，
                // 服务端若只记住自己的值，就会显示一个"已经不生效"的状态。
                val hiddenChanged = item.hidden != null && item.hidden != current.hidden
                // 被隐藏的应用读不到包信息（Android 11+ 的包可见性对隐藏包同样生效），
                // 上报里只剩一个裸包名。这时绝不能拿"包名当应用名、0 当版本号"去覆盖已有记录：
                // 家长端点一次"关闭使用权限"，列表里的"计算器"就会退化成一串包名，
                // 看起来像数据损坏。缺字段时沿用旧值，等它恢复可见后再被真实值刷新。
                val nextName = item.appName
                    ?.takeIf { it.isNotBlank() && it != pkg }
                    ?: current.appName
                val nextVersionName = item.versionName ?: current.versionName
                val nextVersionCode = item.versionCode?.takeIf { it > 0L } ?: current.versionCode
                val changed = current.appName != nextName ||
                    current.versionName != nextVersionName ||
                    current.versionCode != nextVersionCode ||
                    current.installed != true ||
                    current.updateTime != item.updateTime ||
                    iconChanged ||
                    hiddenChanged
                if (changed) {
                    current.appName = nextName
                    current.versionName = nextVersionName
                    current.versionCode = nextVersionCode
                    current.isSystem = item.isSystem ?: current.isSystem
                    current.installed = true
                    current.updateTime = item.updateTime
                    current.lastSeenAt = now
                    if (iconChanged) {
                        current.iconUrl = url
                        current.iconHash = hash
                    }
                    if (hiddenChanged) current.hidden = item.hidden ?: current.hidden
                    installedAppRepository.save(current)
                    updated++
                }
            }
        }

        // 本次没出现的包 = 已被卸载，置 installed=false（保留审计痕迹，不删除）
        var removed = 0
        for ((pkg, entity) in existing) {
            if (pkg in seen) continue
            if (entity.installed) {
                entity.installed = false
                installedAppRepository.save(entity)
                removed++
            }
        }
        return mapOf("inserted" to inserted, "updated" to updated, "removed" to removed)
    }

    /**
     * 计算本次上报图标的哈希与（必要时）新地址。
     *
     * @return `hash` 为图标内容哈希（无图标时为 null）；`url` 仅在**内容变化**时非空。
     *
     * 为什么要按哈希判断而不是每次都存：
     * 孩子端每 30 分钟全量上报一次，一台设备上百个应用。
     * 无条件落盘意味着每天产生上百 × 48 次文件写入，磁盘很快堆满历史图标，
     * 而这些文件里绝大多数是同一张图的重复副本。
     */
    private fun iconUpdate(pkg: String, base64: String?, currentHash: String?): Pair<String?, String?> {
        val hash = iconHash(base64) ?: return null to null
        if (hash == currentHash) return hash to null
        return hash to storeIcon(pkg, base64)
    }

    private fun iconHash(base64: String?): String? {
        val bytes = decodeIcon(base64) ?: return null
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    /** 落盘图标并返回可访问地址；解码失败返回 null（个别 ROM 的图标无法解码，不能因此让整条台账失败） */
    private fun storeIcon(pkg: String, base64: String?): String? {
        val bytes = decodeIcon(base64) ?: return null
        return runCatching { fileStorageService.store("image/png", bytes, "$pkg.png") }
            .onFailure { log.warn("store icon failed for {}: {}", pkg, it.message) }
            .getOrNull()
    }

    private fun decodeIcon(base64: String?): ByteArray? {
        if (base64.isNullOrBlank()) return null
        return runCatching { java.util.Base64.getDecoder().decode(base64) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    // ==================== 家长端查询 ====================

    /** 已安装应用列表；与本地应用策略（黑名单/限额）合并输出，省掉管控端二次拼装 */
    fun listApps(userId: String, deviceId: String, onlyInstalled: Boolean = true): List<InstalledAppDto> {
        requireOwned(userId, deviceId)
        val apps = if (onlyInstalled) installedAppRepository.findByDeviceIdAndInstalledTrue(deviceId)
        else installedAppRepository.findByDeviceId(deviceId)
        val policies = appPolicyRepository.findByDeviceId(deviceId).associateBy { it.packageName }

        return apps
            .sortedWith(compareByDescending<InstalledApp> { !it.isSystem }
                .thenBy { it.appName?.lowercase().orEmpty() })
            .map {
                val policy = policies[it.packageName]
                InstalledAppDto(
                    packageName = it.packageName,
                    appName = it.appName,
                    versionName = it.versionName,
                    versionCode = it.versionCode,
                    isSystem = it.isSystem,
                    installed = it.installed,
                    suspended = it.suspended,
                    installTime = it.installTime,
                    updateTime = it.updateTime,
                    lastSeenAt = it.lastSeenAt,
                    blocked = policy?.isBlocked ?: false,
                    dailyLimitMinutes = policy?.dailyLimitMinutes,
                    iconUrl = it.iconUrl,
                    hidden = it.hidden
                )
            }
    }

    // ==================== 远程运维指令 ====================

    /**
     * 远程安装。
     *
     * 只校验参数并签发指令：APK 实际由**孩子端**从 [AppInstallRequest.apkUrl] 下载，
     * 服务端不中转文件。原因很实际 —— 平板端下载走的是家庭/学校网络，
     * 而服务端在公网，由服务端代理下载再转发只会白白多一跳且拖慢大文件。
     */
    fun installApp(userId: String, deviceId: String, req: AppInstallRequest) =
        commandService.issueCommand(
            deviceId, CommandType.INSTALL_APP,
            mapOf(
                CommandKey.APK_URL to req.apkUrl,
                CommandKey.PACKAGE_NAME to req.packageName,
                CommandKey.VERSION_CODE to (req.versionCode ?: 0L)
            )
        ).also {
            // 预置台账记录：安装尚未完成时家长端也应能看到"正在安装"的目标，
            // 否则列表里凭空少一个包，家长会以为指令丢了。
            val now = System.currentTimeMillis()
            val entity = installedAppRepository.findByDeviceIdAndPackageName(deviceId, req.packageName)
                ?: InstalledApp(
                    id = UUID.randomUUID().toString(),
                    deviceId = deviceId, packageName = req.packageName,
                    appName = req.appName, isSystem = false, firstSeenAt = now
                )
            entity.installed = false
            entity.versionCode = req.versionCode ?: entity.versionCode
            entity.lastSeenAt = now
            installedAppRepository.save(entity)
        }

    /**
     * 隐藏 / 显示应用（家长端「使用权限」开关）。
     *
     * 为什么不用挂起（SET_APP_SUSPENDED）替代：
     * 挂起的应用图标仍在桌面，点开只是被系统拦一下并提示"已停用"。
     * 孩子天天看到一堆点不开的图标，既碍眼，又等于明晃晃地标注"这里有被禁的应用"，
     * 反而刺激去找绕过办法。隐藏才是"没开启权限就不显示"的正确实现。
     *
     * 这里同时把台账的 hidden 标记写回：家长端列表的开关状态直接读这个字段，
     * 不必等设备下一次周期上报（隐藏后孩子端会立即同步，但网络可能不通）。
     */
    fun setAppHidden(userId: String, deviceId: String, req: AppHiddenRequest) {
        requireOwned(userId, deviceId)
        val entity = installedAppRepository.findByDeviceIdAndPackageName(deviceId, req.packageName)
            ?: throw BizException(ParentErr.PARAM_ERROR, "设备上没有这个应用", Audience.PARENT)
        entity.hidden = req.hidden
        entity.lastSeenAt = System.currentTimeMillis()
        installedAppRepository.save(entity)
        commandService.issueCommand(
            deviceId, CommandType.SET_APP_HIDDEN,
            mapOf(
                CommandKey.PACKAGE_NAME to req.packageName,
                CommandKey.HIDDEN to req.hidden
            )
        )
    }

    /**
     * 批量设置应用「使用权限」（隐藏 / 显示）。
     *
     * `all=true` 时作用于全部已安装应用——对应管控端「一键授权全部 / 一键取消全部」。
     * 与 [setAppHidden] 同语义：隐藏即对孩子端不可见、不可用。
     */
    fun setAppsHiddenBatch(userId: String, deviceId: String, req: AppHiddenBatchRequest): Int {
        requireOwned(userId, deviceId)
        val targets = if (req.all) {
            installedAppRepository.findByDeviceIdAndInstalledTrue(deviceId).map { it.packageName }
        } else {
            req.packages
        }.filter { it.isNotBlank() && it != SELF_PACKAGE }

        var sent = 0
        for (pkg in targets.distinct()) {
            val entity = installedAppRepository.findByDeviceIdAndPackageName(deviceId, pkg)
                ?: InstalledApp(
                    id = UUID.randomUUID().toString(), deviceId = deviceId,
                    packageName = pkg, firstSeenAt = System.currentTimeMillis()
                )
            entity.hidden = req.hidden
            entity.lastSeenAt = System.currentTimeMillis()
            installedAppRepository.save(entity)
            commandService.issueCommand(
                deviceId, CommandType.SET_APP_HIDDEN,
                mapOf(CommandKey.PACKAGE_NAME to pkg, CommandKey.HIDDEN to req.hidden)
            )
            sent++
        }
        return sent
    }

    /**
     * 设置单个应用的每日使用时长上限（分钟）。
     *
     * 落库到 [AppPolicy.dailyLimitMinutes]，并经 [PolicyExtensionService] 重建策略包，
     * 以 `appLimit.rules` 形式下发给孩子端 [com.padguard.core.engine.enforcer.AppLimitEnforcer] 执行。
     * 0 表示不限制。
     */
    fun setAppLimit(userId: String, deviceId: String, packageName: String, minutes: Int) {
        requireOwned(userId, deviceId)
        var policy = appPolicyRepository.findByDeviceIdAndPackageName(deviceId, packageName)
        if (policy == null) {
            policy = AppPolicy(deviceId = deviceId, packageName = packageName)
            appPolicyRepository.save(policy)
            policy = appPolicyRepository.findByDeviceIdAndPackageName(deviceId, packageName)!!
        }
        policy.dailyLimitMinutes = if (minutes > 0) minutes else null
        appPolicyRepository.save(policy)
        policyExtensionService.rebuildPolicy(deviceId)
    }

    /**
     * 远程卸载。
     *
     * 自我保护：拒绝卸载管控端自身。孩子端 [CommandExecutor] 里同样有一道拦截，
     * 这里再拦一次不是重复劳动 —— 服务端是唯一的指令入口，
     * 在这里挡住能让"误操作"根本不产生指令记录，事后审计也干净。
     */
    fun uninstallApp(userId: String, deviceId: String, packageName: String) {
        requireOwned(userId, deviceId)
        if (packageName.isBlank()) {
            throw BizException(ParentErr.PARAM_ERROR, "packageName 不能为空", Audience.PARENT)
        }
        if (packageName == SELF_PACKAGE || packageName.endsWith(".child")) {
            throw BizException(ParentErr.PARAM_ERROR, "不允许卸载管控端自身", Audience.PARENT)
        }
        commandService.issueCommand(
            deviceId, CommandType.UNINSTALL_APP, mapOf(CommandKey.PACKAGE_NAME to packageName)
        )
            .also { installedAppRepository.findByDeviceIdAndPackageName(deviceId, packageName)?.let {
                it.installed = false
                installedAppRepository.save(it)
            } }
    }

    /**
     * 应用挂起 / 恢复。
     *
     * 挂起不是卸载：应用仍在，只是被系统禁用到"打开即闪退"的状态，可一键恢复。
     * 对"这个学期先别用，放假再放开"这类诉求比卸载友好得多。
     */
    fun setSuspended(userId: String, deviceId: String, packageName: String, suspended: Boolean) {
        requireOwned(userId, deviceId)
        commandService.issueCommand(
            deviceId, CommandType.SET_APP_SUSPENDED,
            mapOf(CommandKey.PACKAGE_NAME to packageName, CommandKey.SUSPENDED to suspended)
        ).also {
            // 本地先乐观更新，避免家长点完要等一个上报周期才看到状态变化
            installedAppRepository.findByDeviceIdAndPackageName(deviceId, packageName)?.let {
                it.suspended = suspended
                installedAppRepository.save(it)
            }
        }
    }

    /** 批量挂起/恢复：管控端常见的"一键禁用所有游戏"场景，逐条发指令体验太差 */
    fun setSuspendedBatch(userId: String, deviceId: String, packages: List<String>, suspended: Boolean): Int {
        requireOwned(userId, deviceId)
        var sent = 0
        for (pkg in packages.distinct().filter { it.isNotBlank() && it != SELF_PACKAGE }) {
            commandService.issueCommand(
                deviceId, CommandType.SET_APP_SUSPENDED,
                mapOf(CommandKey.PACKAGE_NAME to pkg, CommandKey.SUSPENDED to suspended)
            )
            installedAppRepository.findByDeviceIdAndPackageName(deviceId, pkg)?.let {
                it.suspended = suspended
                installedAppRepository.save(it)
            }
            sent++
        }
        return sent
    }

    // ==================== 工具 ====================

    private fun requireOwned(userId: String, deviceId: String) {
        val dev = deviceRepository.findById(deviceId).orElse(null)
            ?: throw BizException(ParentErr.DEVICE_NOT_FOUND, "设备不存在", Audience.PARENT)
        if (dev.userId != userId) {
            throw BizException(ParentErr.DEVICE_NOT_OWNED, "设备不属于当前用户", Audience.PARENT)
        }
    }

    private companion object {
        const val SELF_PACKAGE = "com.padguard.child"
    }
}
