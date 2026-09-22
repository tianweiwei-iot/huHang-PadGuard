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
    private val policyRepository: PolicyRepository,
    private val appPolicyRepository: AppPolicyRepository,
    private val timeRestrictionRepository: TimeRestrictionRepository,
    private val deviceSettingRepository: DeviceSettingRepository,
    private val policyTemplateRepository: PolicyTemplateRepository,
    private val installedAppRepository: InstalledAppRepository,
    private val mqttGateway: MqttGateway,
    private val objectMapper: ObjectMapper
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
        val existing = appPolicyRepository.findByDeviceId(deviceId).associateBy { it.packageName }
        for (pkg in req.blockedPackages) {
            val e = existing[pkg] ?: AppPolicy(deviceId = deviceId, packageName = pkg)
            e.deviceId = deviceId
            e.packageName = pkg
            e.isBlocked = true
            appPolicyRepository.save(e)
        }
        rebuildPolicy(deviceId)
    }

    fun setAppLimit(userId: String, deviceId: String, req: DailyLimitRequest) {
        requireOwned(userId, deviceId)
        val s = deviceSettingOf(deviceId)
        s.dailyLimitMinutes = req.minutes
        deviceSettingRepository.save(s)
        rebuildPolicy(deviceId)
    }

    /** 读取设备每日总时长上限（分钟）。单一数据源为 DeviceSetting.dailyLimitMinutes，缺省为 0。 */
    fun getDailyLimit(userId: String, deviceId: String): Int {
        requireOwned(userId, deviceId)
        val s = deviceSettingOf(deviceId)
        return s.dailyLimitMinutes ?: 0
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
            smartShutdownEndTime = s.smartShutdownEnd
        )
    }

    fun updateWebUrls(userId: String, deviceId: String, req: UrlBlacklistRequest) {
        requireOwned(userId, deviceId)
        val s = deviceSettingOf(deviceId)
        s.webBlockedUrls = req.urls.joinToString(",")
        deviceSettingRepository.save(s)
        rebuildPolicy(deviceId)
    }

    fun updateBrowser(userId: String, deviceId: String, req: BrowserDisableRequest) {
        requireOwned(userId, deviceId)
        val s = deviceSettingOf(deviceId)
        s.browserDisabled = req.disabled
        deviceSettingRepository.save(s)
        rebuildPolicy(deviceId)
    }

    // ---------- 平板使用时间设置（「时间管控」页） ----------
    fun getTabletUsageSettings(userId: String, deviceId: String): TabletUsageSettingsDto {
        requireOwned(userId, deviceId)
        val s = deviceSettingOf(deviceId)
        return TabletUsageSettingsDto(
            deviceId = deviceId,
            enabledTimeRanges = s.enabledTimeRangesJson?.let(::parseTimeRanges),
            weekdayLimitMinutes = s.weekdayLimitMinutes ?: 120,
            weekendLimitMinutes = s.weekendLimitMinutes ?: 180,
            restAfterMinutes = s.restAfterMinutes ?: 60,
            restDurationMinutes = s.restDurationMinutes ?: 15,
            timeUpMessage = s.timeUpMessage ?: "",
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
        s.timeUpMessage = req.timeUpMessage.ifBlank { null }
        s.enabledTimeRangesJson = req.enabledTimeRanges
            ?.takeIf { it.isNotEmpty() }
            ?.let { objectMapper.writeValueAsString(it.map { r -> TimeRangeDto(r.startTime, r.endTime) }) }
        deviceSettingRepository.save(s)

        // 家长勾选「同步到设备」时立即重建策略包并推送通知；否则等待下次自然重建
        if (req.syncToDevice) rebuildPolicy(deviceId)
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
        val webUrls = settings.webBlockedUrls?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

        val pkg = mutableMapOf<String, Any?>(
            "version" to version,
            "deviceId" to deviceId,
            "scene" to (device.scene ?: "FAMILY"),
            "sceneMode" to (device.sceneMode ?: "NORMAL"),
            "peripheral" to mapOf("wifi" to "ALLOW", "bluetooth" to "DISABLE", "camera" to "DISABLE", "usbFileTransfer" to "DISABLE"),
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
            "app" to mapOf("mode" to "WHITELIST", "whitelist" to whitelist, "blacklist" to blacklist),
            "appLimit" to mapOf(
                "dailyTotalMinutes" to (settings.dailyLimitMinutes ?: 0),
                "weekdayTotalMinutes" to (settings.weekdayLimitMinutes ?: 120),
                "weekendTotalMinutes" to (settings.weekendLimitMinutes ?: 180),
                "restAfterMinutes" to (settings.restAfterMinutes ?: 60),
                "restDurationMinutes" to (settings.restDurationMinutes ?: 15),
                "timeUpMessage" to settings.timeUpMessage
            ),
            "schedule" to mapOf("timezone" to "Asia/Shanghai", "rules" to scheduleRules + usageRangeRules),
            "web" to mapOf("mode" to "BLACKLIST", "blacklist" to webUrls, "browserDisabled" to settings.browserDisabled),
            "monitoring" to mapOf("heartbeatIntervalSec" to settings.autoRefreshSeconds, "logUploadIntervalSec" to 300)
        )
        return objectMapper.writeValueAsString(pkg)
    }

    private fun deviceSettingOf(deviceId: String): DeviceSetting =
        deviceSettingRepository.findById(deviceId).orElse(DeviceSetting(deviceId = deviceId))

    private fun requireOwned(userId: String, deviceId: String) {
        val dev = deviceRepository.findById(deviceId).orElse(null)
            ?: throw BizException(ParentErr.DEVICE_NOT_FOUND, "设备不存在", Audience.PARENT)
        if (dev.userId != userId) {
            throw BizException(ParentErr.DEVICE_NOT_OWNED, "设备不属于当前用户", Audience.PARENT)
        }
    }
}
