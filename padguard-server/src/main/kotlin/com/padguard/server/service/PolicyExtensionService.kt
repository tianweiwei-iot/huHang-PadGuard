package com.padguard.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.*
import com.padguard.server.dto.*
import com.padguard.server.mqtt.MqttGateway
import com.padguard.server.repository.*
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class PolicyExtensionService(
    private val deviceRepository: DeviceRepository,
    private val policyRepository: PolicyRepository,
    private val appPolicyRepository: AppPolicyRepository,
    private val timeRestrictionRepository: TimeRestrictionRepository,
    private val deviceSettingRepository: DeviceSettingRepository,
    private val policyTemplateRepository: PolicyTemplateRepository,
    private val mqttGateway: MqttGateway,
    private val objectMapper: ObjectMapper
) {
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
    fun listAppPolicies(userId: String, deviceId: String): List<AppPolicyDto> {
        requireOwned(userId, deviceId)
        return appPolicyRepository.findByDeviceId(deviceId).map {
            AppPolicyDto(it.id, deviceId, it.packageName, it.appName, it.isBlocked, it.dailyLimitMinutes)
        }
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

    // ---------- 上网管控 ----------
    fun getWebPolicy(userId: String, deviceId: String): WebPolicyDto {
        requireOwned(userId, deviceId)
        val s = deviceSettingOf(deviceId)
        return WebPolicyDto(
            blockedUrls = s.webBlockedUrls?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
            browserDisabled = s.browserDisabled,
            smartShutdownEnabled = s.smartShutdownEnabled,
            smartShutdownStartTime = s.smartShutdownStart,
            smartShutdownEnd = s.smartShutdownEnd
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
    fun rebuildPolicy(deviceId: String) {
        val device = deviceRepository.findById(deviceId).orElse(null) ?: return
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
        val webUrls = settings.webBlockedUrls?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

        val pkg = mutableMapOf<String, Any?>(
            "version" to 1,
            "deviceId" to deviceId,
            "scene" to (device.scene ?: "FAMILY"),
            "sceneMode" to (device.sceneMode ?: "NORMAL"),
            "peripheral" to mapOf("wifi" to "ALLOW", "bluetooth" to "DISABLE", "camera" to "DISABLE", "usbFileTransfer" to "DISABLE"),
            "systemLock" to mapOf("developerOptions" to "DISABLE", "usbDebug" to "DISABLE"),
            "app" to mapOf("mode" to "WHITELIST", "whitelist" to whitelist, "blacklist" to blacklist),
            "appLimit" to mapOf("dailyTotalMinutes" to settings.dailyLimitMinutes),
            "schedule" to mapOf("timezone" to "Asia/Shanghai", "rules" to scheduleRules),
            "web" to mapOf("mode" to "BLACKLIST", "blacklist" to webUrls, "browserDisabled" to settings.browserDisabled),
            "monitoring" to mapOf("heartbeatIntervalSec" to settings.autoRefreshSeconds, "logUploadIntervalSec" to 300)
        )

        val latest = policyRepository.findFirstByDeviceIdOrderByVersionDesc(deviceId)
        val version = (latest?.version ?: 0) + 1
        pkg["version"] = version
        policyRepository.save(
            Policy(
                id = UUID.randomUUID().toString(), deviceId = deviceId, version = version,
                packageJson = objectMapper.writeValueAsString(pkg), scene = device.scene,
                updatedAt = System.currentTimeMillis()
            )
        )
        mqttGateway.publishPolicyNotify(deviceId, version)
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
