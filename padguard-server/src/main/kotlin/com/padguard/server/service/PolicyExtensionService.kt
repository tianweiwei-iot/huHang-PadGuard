package com.padguard.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.*
import com.padguard.server.dto.*
import com.padguard.server.mqtt.MqttGateway
import com.padguard.server.repository.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 家长端策略扩展：时段限制、应用管控、设置与模板应用，以及「由子表重建策略包」。
 *
 * 类级 @Transactional：派生删除方法（deleteBy...）与多表写操作必须在事务内执行，
 * 否则运行期抛 "No EntityManager with actual transaction"（历史上删除时段限制就因此失败）。
 */
@Service
@Transactional
class PolicyExtensionService(
    private val deviceRepository: DeviceRepository,
    private val ownershipGuard: com.padguard.server.security.DeviceOwnershipGuard,
    private val policyRepository: PolicyRepository,
    private val appPolicyRepository: AppPolicyRepository,
    private val timeRestrictionRepository: TimeRestrictionRepository,
    private val deviceSettingRepository: DeviceSettingRepository,
    private val policyTemplateRepository: PolicyTemplateRepository,
    private val installedAppRepository: InstalledAppRepository,
    private val mqttGateway: MqttGateway,
    private val objectMapper: ObjectMapper,
    private val contentFilterService: ContentFilterService,
    private val auditService: AuditService
) {
    private val log = LoggerFactory.getLogger(PolicyExtensionService::class.java)
    // ---------- 时段限制 ----------
    fun listTimeRestrictions(userId: String, deviceId: String): List<TimeRestrictionDto> {
        requireOwned(userId, deviceId)
        return timeRestrictionRepository.findByDeviceId(deviceId).map {
            TimeRestrictionDto(it.id, deviceId, it.dayOfWeek, it.startTime, it.endTime, it.maxMinutes, it.isEnabled)
        }
    }

    fun addTimeRestriction(userId: String, deviceId: String, req: TimeRestrictionDto): TimeRestrictionDto {
        requireOwned(userId, deviceId)
        val e = timeRestrictionRepository.save(
            TimeRestriction(
                id = UUID.randomUUID().toString(), deviceId = deviceId,
                dayOfWeek = req.dayOfWeek, startTime = req.startTime, endTime = req.endTime,
                maxMinutes = req.maxMinutes, isEnabled = req.isEnabled
            )
        )
        rebuildPolicy(deviceId)
        return TimeRestrictionDto(e.id, deviceId, e.dayOfWeek, e.startTime, e.endTime, e.maxMinutes, e.isEnabled)
    }

    /**
     * 新增或更新一条时段限制（家长端「保存」走 PUT）。
     *
     * 必须是 upsert 而不是只有新增：家长端保存时可能带 id（编辑已有条目）也可能不带（新建）。
     * 若只按新增处理，编辑一次就多出一条规则 ——
     * 家长看到的是"我改了时间，却变成两条"，而旧的那条仍在生效。
     */
    fun setTimeRestriction(userId: String, deviceId: String, req: TimeRestrictionDto): TimeRestrictionDto {
        requireOwned(userId, deviceId)
        val existing = req.id?.takeIf { it.isNotBlank() }
            ?.let { timeRestrictionRepository.findByDeviceIdAndId(deviceId, it).orElse(null) }
        val e = if (existing != null) {
            existing.dayOfWeek = req.dayOfWeek
            existing.startTime = req.startTime
            existing.endTime = req.endTime
            existing.maxMinutes = req.maxMinutes
            existing.isEnabled = req.isEnabled
            timeRestrictionRepository.save(existing)
        } else {
            timeRestrictionRepository.save(
                TimeRestriction(
                    id = UUID.randomUUID().toString(), deviceId = deviceId,
                    dayOfWeek = req.dayOfWeek, startTime = req.startTime, endTime = req.endTime,
                    maxMinutes = req.maxMinutes, isEnabled = req.isEnabled
                )
            )
        }
        rebuildPolicy(deviceId)
        return TimeRestrictionDto(e.id, deviceId, e.dayOfWeek, e.startTime, e.endTime, e.maxMinutes, e.isEnabled)
    }

    fun deleteTimeRestriction(userId: String, deviceId: String, id: String) {
        requireOwned(userId, deviceId)
        timeRestrictionRepository.deleteByDeviceIdAndId(deviceId, id)
        rebuildPolicy(deviceId)
    }

    // ---------- 应用管控 ----------
    /**
     * 应用管控列表：已安装应用台账（installed_apps）为主视图，合并每包的管控策略。
     *
     * 之前只返回 app_policies 表（仅存家长显式设置过的黑名单/限额），
     * 家长从未设置过时列表恒为空，导致管控页「应用」Tab 空白。
     * 现以台账为准：未设置策略的已安装应用默认 isBlocked=false；
     * 已卸载但仍有策略记录的包保留在列表尾部，便于家长看见并清理。
     */
    fun listAppPolicies(userId: String, deviceId: String): List<AppPolicyDto> {
        requireOwned(userId, deviceId)
        val policies = appPolicyRepository.findByDeviceId(deviceId).associateBy { it.packageName }
        val installed = installedAppRepository.findByDeviceIdAndInstalledTrue(deviceId)
            .sortedWith(
                compareByDescending<InstalledApp> { !it.isSystem }
                    .thenBy { it.appName?.lowercase().orEmpty() }
            )

        val result = mutableListOf<AppPolicyDto>()
        val seen = mutableSetOf<String>()
        for (app in installed) {
            seen += app.packageName
            val p = policies[app.packageName]
            result += AppPolicyDto(
                id = p?.id, deviceId = deviceId, packageName = app.packageName,
                appName = app.appName ?: app.packageName,
                isBlocked = p?.isBlocked ?: false, dailyLimitMinutes = p?.dailyLimitMinutes
            )
        }
        for (p in appPolicyRepository.findByDeviceId(deviceId)) {
            if (p.packageName !in seen) {
                result += AppPolicyDto(
                    id = p.id, deviceId = deviceId, packageName = p.packageName,
                    appName = p.appName ?: p.packageName,
                    isBlocked = p.isBlocked, dailyLimitMinutes = p.dailyLimitMinutes
                )
            }
        }
        return result
    }

    fun setAppBlacklist(userId: String, deviceId: String, req: UpdateBlacklistRequest) {
        requireOwned(userId, deviceId)
        val blocked = req.blockedPackages.toSet()
        val existing = appPolicyRepository.findByDeviceId(deviceId).associateBy { it.packageName }

        // 全量语义：名单内的置为管控，名单外的解除管控。
        // 原实现"只增不减"会把应用永久钉死在管控态 —— 家长取消管控（传空名单）后
        // 这些应用依旧解不开，属设计缺陷；改为以本次名单为准，开启/取消才都成立。
        for ((pkg, e) in existing) {
            val shouldBlock = pkg in blocked
            if (e.isBlocked != shouldBlock) {
                e.isBlocked = shouldBlock
                appPolicyRepository.save(e)
            }
        }
        // 名单里、但台账尚未登记的应用（还没上报过清单）也要补建记录
        for (pkg in blocked) {
            if (pkg !in existing) {
                val e = AppPolicy(deviceId = deviceId, packageName = pkg)
                e.isBlocked = true
                appPolicyRepository.save(e)
            }
        }
        auditService.record(userId, deviceId, AuditAction.POLICY_APP_UPDATE,
            targetType = "device", targetId = deviceId, detail = "blocked=${req.blockedPackages.size}条")
        rebuildPolicy(deviceId)
    }

    /**
     * 设置应用管控模式（白名单 / 黑名单）与「启动也要密码」开关。
     *
     * 落库到 [DeviceSetting] 后调 [rebuildPolicy] 重建策略包并下发 ——
     * 孩子端 [com.padguard.core.data.model.policy.AppPolicy] 会带上这两个字段：
     * - `mode`：决定孩子端按"只管名单内"还是"只放行名单内"执行；
     * - `requirePasswordOnLaunch`：无 DO 设备上由无障碍服务在启动受管控应用时要求密码。
     *
     * 非法 mode 一律回落 BLACKLIST：不因为一个拼写错误把设备变成"全设备拦截"。
     */
    fun setAppListPolicy(userId: String, deviceId: String, req: AppListPolicyRequest) {
        requireOwned(userId, deviceId)
        val s = deviceSettingOf(deviceId)
        s.appListMode = if (req.mode.equals("WHITELIST", ignoreCase = true)) "WHITELIST" else "BLACKLIST"
        s.requirePasswordOnLaunch = req.requirePasswordOnLaunch
        s.requirePasswordOnInstall = req.requirePasswordOnInstall
        s.requirePasswordOnUninstall = req.requirePasswordOnUninstall
        deviceSettingRepository.save(s)
        auditService.record(
            userId, deviceId, AuditAction.POLICY_APP_UPDATE,
            targetType = "device", targetId = deviceId,
            detail = "mode=${s.appListMode}, requirePasswordOnLaunch=${req.requirePasswordOnLaunch}"
        )
        rebuildPolicy(deviceId)
    }

    /**
     * 护眼模式读写。
     *
     * 落 [DeviceSetting] + [rebuildPolicy] 下发，与其余策略开关同一套路：
     * 存储、审计、重建下发三步都在一处，不会出现"库里改了但平板没收到"。
     *
     * 强度夹到 1~5：孩子端按这个区间映射滤镜透明度，越界值会让可选档位出现
     * "设了 9 但和 5 一样"这种无法解释的现象。
     */
    fun setEyeProtection(userId: String, deviceId: String, req: EyeProtectionRequest): EyeProtectionDto {
        requireOwned(userId, deviceId)
        val s = deviceSettingOf(deviceId)
        s.eyeProtectionEnabled = req.enabled
        s.eyeProtectionLevel = req.filterLevel.coerceIn(MIN_EYE_LEVEL, MAX_EYE_LEVEL)
        deviceSettingRepository.save(s)
        auditService.record(
            userId, deviceId, AuditAction.POLICY_EYE_PROTECTION_UPDATE,
            targetType = "device", targetId = deviceId,
            detail = "eyeProtection=${s.eyeProtectionEnabled}, level=${s.eyeProtectionLevel}"
        )
        rebuildPolicy(deviceId)
        return EyeProtectionDto(s.eyeProtectionEnabled, s.eyeProtectionLevel)
    }

    fun getEyeProtection(userId: String, deviceId: String): EyeProtectionDto {
        requireOwned(userId, deviceId)
        val s = deviceSettingOf(deviceId)
        return EyeProtectionDto(s.eyeProtectionEnabled, s.eyeProtectionLevel.coerceIn(MIN_EYE_LEVEL, MAX_EYE_LEVEL))
    }

    /** 读取当前应用管控模式，供家长端回显。 */
    fun getAppListPolicy(userId: String, deviceId: String): AppListPolicyDto {
        requireOwned(userId, deviceId)
        val s = deviceSettingOf(deviceId)
        val appPolicies = appPolicyRepository.findByDeviceId(deviceId)
        return AppListPolicyDto(
            mode = s.appListMode?.takeIf { it == "WHITELIST" } ?: "BLACKLIST",
            requirePasswordOnLaunch = s.requirePasswordOnLaunch ?: false,
            requirePasswordOnInstall = s.requirePasswordOnInstall ?: true,
            requirePasswordOnUninstall = s.requirePasswordOnUninstall ?: true,
            whitelist = appPolicies.filter { !it.isBlocked }.map { it.packageName },
            blacklist = appPolicies.filter { it.isBlocked }.map { it.packageName }
        )
    }

    fun setAppLimit(userId: String, deviceId: String, req: DailyLimitRequest) {        requireOwned(userId, deviceId)
        val s = deviceSettingOf(deviceId)
        s.dailyLimitMinutes = req.minutes
        deviceSettingRepository.save(s)
        rebuildPolicy(deviceId)
    }

    /** 读取设备每日总时长上限（分钟）。单一数据源为 DeviceSetting.dailyLimitMinutes，缺省为 120。 */
    fun getDailyLimit(userId: String, deviceId: String): Int {
        requireOwned(userId, deviceId)
        val s = deviceSettingOf(deviceId)
        // 未配置或显式置 0 时回落到产品默认 120 分钟，避免家长端看到「0/1 分钟」这种失真值。
        return if (s.dailyLimitMinutes > 0) s.dailyLimitMinutes else 120
    }

    // ---------- 上网管控 ----------
    fun getWebPolicy(userId: String, deviceId: String): WebPolicyDto {
        requireOwned(userId, deviceId)
        val s = deviceSettingOf(deviceId)
        return WebPolicyDto(
            blockedUrls = s.webBlockedUrls?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
            browserDisabled = s.browserDisabled,
            smartShutdownEnabled = s.smartShutdownEnabled,
            smartShutdownStartTime = s.smartShutdownStart,
            smartShutdownEndTime = s.smartShutdownEnd,
            safeSearch = s.webSafeSearch ?: false,
            blockedCategories = contentFilterService.blockedCategoriesForDevice(deviceId).toList()
        )
    }

    fun updateWebUrls(userId: String, deviceId: String, req: UrlBlacklistRequest) {
        requireOwned(userId, deviceId)
        val s = deviceSettingOf(deviceId)
        s.webBlockedUrls = req.urls.joinToString(",")
        deviceSettingRepository.save(s)
        auditService.record(userId, deviceId, AuditAction.POLICY_WEB_UPDATE,
            targetType = "device", targetId = deviceId, detail = "blockedUrls=${req.urls.size}条")
        rebuildPolicy(deviceId)
    }

    fun updateBrowser(userId: String, deviceId: String, req: BrowserDisableRequest) {
        requireOwned(userId, deviceId)
        val s = deviceSettingOf(deviceId)
        s.browserDisabled = req.disabled
        deviceSettingRepository.save(s)
        auditService.record(userId, deviceId, AuditAction.POLICY_WEB_UPDATE,
            targetType = "device", targetId = deviceId, detail = "browserDisabled=${req.disabled}")
        rebuildPolicy(deviceId)
    }

    // ---------- 平板使用时间设置（「时间管控」页） ----------
    fun getTabletUsageSettings(userId: String, deviceId: String): TabletUsageSettingsDto {
        requireOwned(userId, deviceId)
        val s = deviceSettingOf(deviceId)
        return TabletUsageSettingsDto(
            deviceId = deviceId,
            enabled = s.timeControlEnabled,
            enabledTimeRanges = s.enabledTimeRangesJson?.let(::parseTimeRanges),
            weekdayLimitMinutes = s.weekdayLimitMinutes ?: DeviceSetting.DEFAULT_WEEKDAY_LIMIT_MINUTES,
            weekendLimitMinutes = s.weekendLimitMinutes ?: DeviceSetting.DEFAULT_WEEKEND_LIMIT_MINUTES,
            restAfterMinutes = s.restAfterMinutes ?: 60,
            restDurationMinutes = s.restDurationMinutes ?: 15,
            syncToDevice = false
        )
    }

    fun updateTabletUsageSettings(userId: String, deviceId: String, req: TabletUsageSettingsDto): TabletUsageSettingsDto {
        requireOwned(userId, deviceId)
        val s = deviceSettingOf(deviceId)
        s.weekdayLimitMinutes = req.weekdayLimitMinutes.coerceIn(0, 24 * 60)
        s.weekendLimitMinutes = req.weekendLimitMinutes.coerceIn(0, 24 * 60)
        s.restAfterMinutes = req.restAfterMinutes.coerceAtLeast(0)
        s.restDurationMinutes = req.restDurationMinutes.coerceAtLeast(0)
        s.enabledTimeRangesJson = req.enabledTimeRanges
            ?.takeIf { it.isNotEmpty() }
            ?.let { objectMapper.writeValueAsString(it) }
        s.timeControlEnabled = req.enabled
        deviceSettingRepository.save(s)

        // 家长勾选「同步到设备」时立即重建策略包并推送通知；否则等待下次自然重建
        if (req.syncToDevice) rebuildPolicy(deviceId)
        auditService.record(userId, deviceId, AuditAction.POLICY_TABLET_USAGE_UPDATE,
            targetType = "device", targetId = deviceId,
            detail = "weekday=${s.weekdayLimitMinutes} weekend=${s.weekendLimitMinutes} " +
                "restAfter=${s.restAfterMinutes} sync=${req.syncToDevice}")
        return getTabletUsageSettings(userId, deviceId)
    }

    /**
     * 救援通道开关：解除 / 恢复被管控平板的「开发者选项 + USB 调试 + 未知来源安装」限制。
     *
     * 打开后立即重建策略包并推送通知，孩子端下次拉取策略即自行解封；
     * 解封后平板才能侧载新版 APK 或打开 USB 调试被 adb 接管，从而完成远程升级。
     *
     * @return 当前开关状态
     */
    fun setSystemLockRelaxed(userId: String, deviceId: String, relaxed: Boolean): Boolean {
        requireOwned(userId, deviceId)
        val s = deviceSettingOf(deviceId)
        s.systemLockRelaxed = relaxed
        deviceSettingRepository.save(s)
        auditService.record(userId, deviceId, AuditAction.POLICY_SYSTEM_LOCK_UPDATE,
            targetType = "device", targetId = deviceId, detail = "relaxed=$relaxed")
        rebuildPolicy(deviceId)
        log.warn("systemLock relaxed={} for device {} by user {}", relaxed, deviceId, userId)
        return relaxed
    }

    /** 查询救援通道开关状态。 */
    fun getSystemLockRelaxed(userId: String, deviceId: String): Boolean {
        requireOwned(userId, deviceId)
        return deviceSettingOf(deviceId).systemLockRelaxed ?: false
    }

    private fun parseTimeRanges(json: String): List<TimeRangeDto> =
        runCatching {
            val type = objectMapper.typeFactory.constructCollectionType(List::class.java, TimeRangeDto::class.java)
            objectMapper.readValue<List<TimeRangeDto>>(json, type)
        }.getOrElse { emptyList() }

    // ---------- 策略模板 ----------
    fun listTemplates(sceneType: String?): List<PolicyTemplateDto> {
        val list = if (sceneType.isNullOrBlank()) policyTemplateRepository.findAll()
        else policyTemplateRepository.findBySceneType(sceneType)
        return list.map { PolicyTemplateDto(it.id, it.name, it.description, it.sceneType, it.category) }
    }

    fun applyTemplate(userId: String, deviceId: String, req: ApplyTemplateRequest) {
        requireOwned(userId, deviceId)
        val tpl = policyTemplateRepository.findById(req.templateId).orElse(null)
            ?: throw BizException(ParentErr.PARAM_ERROR, "模板不存在", Audience.PARENT)
        val base = if (tpl.packageJson != null) {
            objectMapper.readValue(tpl.packageJson, Map::class.java) as MutableMap<String, Any?>
        } else mutableMapOf()
        base["deviceId"] = deviceId
        val latest = policyRepository.findFirstByDeviceIdOrderByVersionDesc(deviceId)
        val version = (latest?.version ?: 0) + 1
        base["version"] = version
        policyRepository.save(
            Policy(
                id = UUID.randomUUID().toString(), deviceId = deviceId, version = version,
                packageJson = objectMapper.writeValueAsString(base), scene = tpl.sceneType,
                updatedAt = System.currentTimeMillis()
            )
        )
        auditService.record(userId, deviceId, AuditAction.POLICY_TEMPLATE_APPLY,
            targetType = "template", targetId = tpl.id, detail = "template=${tpl.name}")
        mqttGateway.publishPolicyNotify(deviceId, version)
    }

    // ---------- 由子表重建策略包（单一数据源） ----------

    /**
     * 设备拉取策略前的兜底校验：子表数据与策略包一旦漂移，在此自愈。
     *
     * 背景：策略包理论上只由子表（时段/应用/设置）经 [rebuildPolicy] 生成，
     * 但历史数据可能来自旧版本代码、模板应用（模板内含演示用 LOCK 规则）或直接改库，
     * 这些路径都会让策略包停留在过期内容，而设备端只会忠实执行——
     * 表现为锁屏显示与家长端配置完全不符的"虚假锁定状态"。
     * 通知（MQTT）在服务重启窗口还会丢失，家长不改配置就永远无人触发 rebuild，
     * 因此必须在设备主动拉取这条必经路径上做版本一致性校验。
     */
    fun ensureRebuilt(deviceId: String) {
        val latest = policyRepository.findFirstByDeviceIdOrderByVersionDesc(deviceId) ?: return
        val expected = buildPolicyPackage(deviceId, latest.version) ?: return
        if (latest.packageJson != expected) {
            log.warn("policy drift detected for {} (v{}), rebuilding", deviceId, latest.version)
            rebuildPolicy(deviceId)
        }
    }

    fun rebuildPolicy(deviceId: String) {
        val device = deviceRepository.findById(deviceId).orElse(null) ?: return
        val latest = policyRepository.findFirstByDeviceIdOrderByVersionDesc(deviceId)
        val version = (latest?.version ?: 0) + 1
        val packageJson = buildPolicyPackage(deviceId, version) ?: return
        policyRepository.save(
            Policy(
                id = UUID.randomUUID().toString(), deviceId = deviceId, version = version,
                packageJson = packageJson, scene = device.scene,
                updatedAt = System.currentTimeMillis()
            )
        )
        mqttGateway.publishPolicyNotify(deviceId, version)
    }

    /** 由子表纯构建策略包 JSON（含指定 version）；设备不存在时返回 null */
    private fun buildPolicyPackage(deviceId: String, version: Int): String? {
        val device = deviceRepository.findById(deviceId).orElse(null) ?: return null
        val appPolicies = appPolicyRepository.findByDeviceId(deviceId)
        val whitelist = appPolicies.filter { !it.isBlocked }.map { it.packageName }
        val blacklist = appPolicies.filter { it.isBlocked }.map { it.packageName }
        val timeRests = timeRestrictionRepository.findByDeviceId(deviceId)
        val settings = deviceSettingOf(deviceId)

        val scheduleRules = timeRests.filter { it.isEnabled }.map {
            mapOf(
                "dayTypes" to listOf(if (it.dayOfWeek in 1..5) "WEEKDAY" else "WEEKEND"),
                "start" to it.startTime, "end" to it.endTime, "action" to "UNLOCK"
            )
        }
        // 「时间管控」页设置的可用时间段：对工作日与休息日同时生效
        val usageRanges = settings.enabledTimeRangesJson?.let(::parseTimeRanges).orEmpty()
        val usageRangeRules = usageRanges.map {
            mapOf(
                "dayTypes" to listOf("WEEKDAY", "WEEKEND"),
                "start" to it.startTime, "end" to it.endTime, "action" to "UNLOCK"
            )
        }
        // 家长授权意图（唯一权威）：installed_apps.hidden = true 即"已取消授权"。
        //
        // 为什么必须随策略包整体下发：`SET_APP_HIDDEN` 是逐包的即时指令，
        // 设备离线或指令丢失时孩子端会永久停在旧状态，而服务端已记成"已取消授权"，
        // 两端分裂且无从自愈。策略包每轮都会下发，是权威意图最合适的载体——
        // 孩子端按它全量对齐本地意图账本即可收敛。
        // 只取仍安装在设备上的包：已卸载的只剩历史台账，不该再驱动孩子端门禁。
        val revokedPackages = installedAppRepository.findByDeviceId(deviceId)
            .filter { it.hidden && it.installed }
            .map { it.packageName }

        val webUrls = settings.webBlockedUrls?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        // 内容管控：安全搜索开关 + 实际应屏蔽的分类集合（默认屏蔽 ∪ 家长自定义）。
        // 孩子端 WebEnforcer 据此对主流搜索引擎注入 safe 参数、拦截命中分类的网址。
        val blockedCategories = contentFilterService.blockedCategoriesForDevice(deviceId).toList()

        // 单个应用的每日时长上限：以 app_policies.daily_limit_minutes 为准，下发给孩子的 appLimit.rules。
        // 与「设备每日总时长」（device_settings.daily_limit_minutes）是两套独立维度。
        val limitRules = appPolicies
            .filter { (it.dailyLimitMinutes ?: 0) > 0 }
            .map {
                mapOf(
                    "packageName" to it.packageName,
                    "dailyMinutes" to it.dailyLimitMinutes,
                    "dayTypes" to listOf("WEEKDAY", "WEEKEND")
                )
            }

        val pkg = mutableMapOf<String, Any?>(
            "version" to version,
            "deviceId" to deviceId,
            "scene" to (device.scene ?: "FAMILY"),
            //  sceneMode 读 device.sceneMode（由 applyMode 同步落库的唯一事实源）。
            // 不可再写死 "NORMAL" 兜底覆盖，否则会冲掉家长刚切换的模式，使切换对孩子的设备不生效。
            "sceneMode" to (device.sceneMode ?: "NORMAL"),
            "peripheral" to mapOf("wifi" to "ALLOW", "bluetooth" to "DISABLE", "camera" to "DISABLE", "usbFileTransfer" to "DISABLE"),
            // 护眼：独立顶级段，不塞进 appLimit —— 后者是"应用限时"，两者毫不相干，
            // 混在一起迟早在"调护眼为什么动了限时"上排查不清。
            // 孩子端以悬浮暖色滤镜实现（无需 DO），这是 C 端存量设备唯一能生效的做法。
            "eyeProtection" to mapOf(
                "enabled" to settings.eyeProtectionEnabled,
                "level" to settings.eyeProtectionLevel.coerceIn(MIN_EYE_LEVEL, MAX_EYE_LEVEL)
            ),
            // 救援通道打开时整体解封：开发者选项 / USB 调试 / 未知来源安装。
            // 缺了第三项，"解除限制后还是装不上" —— 因为侧载本身就是被这一项挡住的。
            "systemLock" to if (settings.systemLockRelaxed == true) {
                mapOf(
                    "developerOptions" to "ALLOW",
                    "usbDebug" to "ALLOW",
                    "unknownSourceInstall" to "ALLOW"
                )
            } else {
                mapOf("developerOptions" to "DISABLE", "usbDebug" to "DISABLE")
            },
            // 默认必须是**黑名单**而不是白名单。
            //
            // 白名单的语义是"不在名单里的一律挂起"，而名单来自 app_policy 子表 ——
            // 只要台账新增了一个尚未建策略行的应用（孩子刚装的游戏、系统更新带出来的新组件），
            // 它就不在名单里，于是被静默挂起：孩子端表现为"这应用打不开了"，
            // 家长端却什么都没设置过，排查时根本对不上。
            // 更极端的情况是子表为空（新建设备、数据迁移），白名单为空 = 全设备应用被挂起 = 变砖。
            // 家长端现在的操作语义本来就是"禁用某个应用"（黑名单），这里保持一致。
            //
            // mode 不再硬编码：家长选白名单时必须能真正下发，
            // 否则"白名单模式"在服务端就被吞掉了，孩子端永远按黑名单执行。
            // 兜底仍用 BLACKLIST：mode 为空或非法值时保持既有语义，不改变存量设备行为。
            "app" to mapOf(
                "mode" to (settings.appListMode?.takeIf { it == "WHITELIST" } ?: "BLACKLIST"),
                "whitelist" to whitelist,
                "blacklist" to blacklist,
                // 无 DO 降级：启动受管控应用时是否要密码（仅非 DO 设备生效）
                "requirePasswordOnLaunch" to (settings.requirePasswordOnLaunch ?: false),
                // 无 DO 降级：安装 / 卸载确认页是否要密码（仅非 DO 设备生效，默认都要）
                "installPolicy" to mapOf(
                    "requirePasswordOnInstall" to (settings.requirePasswordOnInstall ?: true),
                    "requirePasswordOnUninstall" to (settings.requirePasswordOnUninstall ?: true)
                ),
                // 服务端权威的「已取消授权」集合。孩子端每轮策略应用时据此对齐本地意图账本，
                // 使"取消授权"不再依赖逐条指令一定能送达。
                "hiddenPackages" to revokedPackages
            ),
            "appLimit" to mapOf(
                "enabled" to (settings.timeControlEnabled ?: true),
                // dailyTotalMinutes 是「工作日/周末都不配置时的兜底总时长」。
                // 它必须跟随家长在「时间管控」页设置的工作日额度，否则老设备缓存的策略包
                // （早期种子包只含 dailyTotalMinutes、没有 weekday/weekend 字段）在未成年人模式
                // 派生时会回落到分龄默认值（如 BAND_16_18=120 分钟），表现为"家长改了时长也没反应、平板始终 2 小时"。
                // 这里与 weekdayTotalMinutes 同源，保证兜底值也是家长真实设置的每日额度。
                "dailyTotalMinutes" to (settings.weekdayLimitMinutes ?: 0),
                "weekdayTotalMinutes" to (settings.weekdayLimitMinutes ?: DeviceSetting.DEFAULT_WEEKDAY_LIMIT_MINUTES),
                "weekendTotalMinutes" to (settings.weekendLimitMinutes ?: DeviceSetting.DEFAULT_WEEKEND_LIMIT_MINUTES),
                "restAfterMinutes" to (settings.restAfterMinutes ?: 60),
                "restDurationMinutes" to (settings.restDurationMinutes ?: 15),
                "rules" to limitRules
            ),
            "schedule" to mapOf(
                "enabled" to (settings.timeControlEnabled ?: true),
                "timezone" to "Asia/Shanghai", "rules" to scheduleRules + usageRangeRules
            ),
            "web" to mapOf(
                "mode" to "BLACKLIST", "blacklist" to webUrls,
                "browserDisabled" to settings.browserDisabled,
                "safeSearch" to (settings.webSafeSearch ?: false),
                "blockedCategories" to blockedCategories
            ),
            "monitoring" to mapOf(
                "heartbeatIntervalSec" to settings.autoRefreshSeconds,
                "logUploadIntervalSec" to 300,
                // 与默认策略包保持一致地显式下发，避免重建策略后周期定位被静默关掉
                "locationIntervalSec" to DEFAULT_LOCATION_INTERVAL_SEC
            ),
            // 未成年人模式**必须原样继承**：它是合规强制项，不属于"由子表重建"的范畴。
            //
            // 子表（app_policy / time_restriction / device_settings）里根本没有 minorMode 的落点，
            // 若这里不显式继承，[ensureRebuilt] 一旦检测到漂移并触发 [rebuildPolicy]，
            // 新包就会直接没有 minorMode 字段 —— 孩子端按默认值解析得到 enabled=false，
            // 分龄时长、护眼、宵禁在设备上瞬间全部消失。
            // 表现为：家长端明明亮着"已开启"，平板上却毫无管控，且重启也不会恢复。
            // 同理适用于 eyeCare / kiosk / security：它们也没有子表落点。
            "minorMode" to (latestMinorMode(deviceId) ?: mapOf(
                "enabled" to false,
                "ageBand" to (device.ageBand ?: "BAND_8_12"),
                "curfewEnabled" to true,
                "curfewStart" to "22:00",
                "curfewEnd" to "06:00",
                "dailyLimitMinutes" to 0,
                "weekendLimitMinutes" to 0,
                "continuousMinutes" to 0,
                "restMinutes" to 0,
                "parentExemptUntil" to 0L,
                "requireParentAuthToExit" to true,
                "parentOverridden" to false
            ))
        )
        // 操作密码闸门：哈希随策略包下发，孩子端只存哈希、明文永不出服务端。
        // 三态：null=从未设置(忽略)；""非空串=家长已清除(孩子端清除)；非空=已启用。
        val guardHash = device.guardPasswordHash
        pkg["guard"] = mapOf(
            "passwordHash" to (guardHash ?: ""),
            "clear" to (guardHash != null && guardHash.isBlank())
        )
        // 同样没有子表落点的 eyeCare / kiosk / security 一并继承，避免被重建清空
        latestPackageFields(deviceId, "eyeCare", "kiosk", "security").forEach { (k, v) -> pkg.putIfAbsent(k, v) }
        //
        // 但 eyeCare 必须**由「时间管控」页的休息设置覆盖**，不能只靠继承：
        // 家长在该页设的「休息提醒 / 每次休息时长」原先只写进 appLimit（孩子端旧模型没有这两个字段，
        // 反序列化时静默丢弃），而 eyeCare 段又没有子表落点、只能从旧包继承 ——
        // 于是这两项"保存成功、下发成功、设备毫无反应"，是典型的静默断链。
        // 时间管控页是家长设置休息节奏的**唯一入口**，这里就是 eyeCare 的权威来源。
        pkg["eyeCare"] = buildEyeCareSegment(settings)
        return objectMapper.writeValueAsString(pkg)
    }

    /**
     * 由「时间管控」页的休息设置构建护眼休息（eyeCare）段。
     *
     * 语义映射（与孩子端 [com.padguard.core.engine.enforcer.EyeCareEnforcer] 一致）：
     * - `restAfterMinutes`（使用多久后提醒休息） → `continuousMinutes`（连续使用多久强制休息）
     * - `restDurationMinutes`（每次休息时长）   → `restMinutes`
     * - `forceLockDuringRest` 置 true：休息期间直接锁屏，
     *   否则"提醒了但孩子可以马上划掉继续玩"，家长要的强制休息落空。
     *
     * 任一项为 0、或时间管控总开关关闭时显式下发 `enabled=false`：
     * 这既表达"家长没设休息"，也避免旧包里残留的 enabled=true 被一直继承下去。
     */
    private fun buildEyeCareSegment(s: DeviceSetting): Map<String, Any?> {
        val after = (s.restAfterMinutes ?: 0).coerceAtLeast(0)
        val rest = (s.restDurationMinutes ?: 0).coerceAtLeast(0)
        val on = s.timeControlEnabled && after > 0 && rest > 0
        return mapOf(
            "enabled" to on,
            "continuousMinutes" to if (on) after else 0,
            "restMinutes" to if (on) rest else 0,
            "forceLockDuringRest" to on
        )
    }

    /** 从当前最新策略包里取未成年人模式配置；没有则返回 null */
    private fun latestMinorMode(deviceId: String): Map<String, Any?>? =
        latestPackageFields(deviceId, "minorMode")["minorMode"] as? Map<String, Any?>

    /**
     * 从当前最新策略包里挑出指定字段，供重建时继承。
     *
     * 只做浅拷贝且不解析成强类型对象：这些字段的结构由孩子端模型定义，
     * 服务端不做解释，原样搬运最不容易出错。
     */
    private fun latestPackageFields(deviceId: String, vararg keys: String): Map<String, Any?> {
        val json = policyRepository.findFirstByDeviceIdOrderByVersionDesc(deviceId)?.packageJson
            ?: return emptyMap()
        val map = runCatching { objectMapper.readValue(json, Map::class.java) as Map<*, *> }
            .getOrElse { return emptyMap() }
        val out = linkedMapOf<String, Any?>()
        keys.forEach { k ->
            if (map.containsKey(k)) out[k] = map[k]
        }
        return out
    }

    private fun deviceSettingOf(deviceId: String): DeviceSetting =
        deviceSettingRepository.findById(deviceId).orElse(DeviceSetting(deviceId = deviceId))

    /**
     * 设备归属校验已收敛至 [com.padguard.server.security.DeviceOwnershipGuard]（唯一实现），
     * 消除逐 Service 复制私有副本的漏网风险（详见该组件类注释）。
     */
    private fun requireOwned(userId: String, deviceId: String) =
        ownershipGuard.requireOwned(userId, deviceId)

    private companion object {
        /** 护眼滤镜强度的有效区间：孩子端按此映射遮罩透明度，两端必须同源。 */
        const val MIN_EYE_LEVEL = 1
        const val MAX_EYE_LEVEL = 5
    }
}
