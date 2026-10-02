package com.padguard.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.domain.Policy
import com.padguard.server.dto.MinorModeRequest
import com.padguard.server.mqtt.MqttGateway
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.PolicyRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * 分龄档位的合规默认值（服务端侧唯一事实源）。
 *
 * 为什么服务端也要存一份，而不是全交给端上算：
 * 家长端展示"开启后每天可用 60 分钟、22:00–06:00 停止服务"必须在**下发之前**就显示给家长看，
 * 若只有端上有默认值，家长端要么再抄一份（两处必然漂移），要么点了开启才知道后果。
 */
private data class AgeBandDefault(
    val dailyLimitMinutes: Int,
    val continuousMinutes: Int,
    val restMinutes: Int,
    val curfewStart: String,
    val curfewEnd: String
)

private val AGE_BAND_DEFAULTS: Map<String, AgeBandDefault> = mapOf(
    "UNDER_3" to AgeBandDefault(30, 15, 10, "20:00", "07:00"),
    "BAND_3_8" to AgeBandDefault(60, 20, 10, "21:00", "07:00"),
    "BAND_8_12" to AgeBandDefault(60, 30, 10, "22:00", "06:00"),
    "BAND_12_16" to AgeBandDefault(60, 30, 10, "22:00", "06:00"),
    "BAND_16_18" to AgeBandDefault(120, 30, 10, "22:00", "06:00")
)

/** 服务端已知的分龄档位；未知值一律回落到 8–12 岁档 */
private fun normalizeAgeBand(value: String?): String =
    AGE_BAND_DEFAULTS.keys.firstOrNull { it.equals(value?.trim(), ignoreCase = true) } ?: "BAND_8_12"

@Service
class PolicyService(
    private val policyRepository: PolicyRepository,
    private val deviceRepository: DeviceRepository,
    private val mqttGateway: MqttGateway,
    private val objectMapper: ObjectMapper,
    @Value("\${padguard.mqtt.tenant:default}") private val tenant: String
) {
    fun createDefault(deviceId: String) {
        policyRepository.save(
            Policy(
                id = UUID.randomUUID().toString(), deviceId = deviceId, version = 1,
                packageJson = buildDefaultPolicyJson(deviceId),
                scene = "FAMILY", updatedAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * 一键设置未成年人模式。
     *
     * 与 [applyMode] 走同一套"读旧包 → 改字段 → 版本+1 → 通知孩子端"流程，
     * 差别只在写入的字段与 device.ageBand 的同步。
     *
     * @return 新的策略版本号
     */
    fun applyMinorMode(deviceId: String, req: MinorModeRequest): Int {
        val policy = policyRepository.findFirstByDeviceIdOrderByVersionDesc(deviceId)
            ?: run { createDefault(deviceId); policyRepository.findFirstByDeviceIdOrderByVersionDesc(deviceId)!! }

        @Suppress("UNCHECKED_CAST")
        val base: MutableMap<String, Any?> = if (policy.packageJson != null) {
            objectMapper.readValue(policy.packageJson, Map::class.java) as MutableMap<String, Any?>
        } else mutableMapOf()

        val band = normalizeAgeBand(req.ageBand)
        val def = AGE_BAND_DEFAULTS.getValue(band)
        val now = System.currentTimeMillis()

        base["minorMode"] = mapOf(
            "enabled" to req.enabled,
            "ageBand" to band,
            "curfewEnabled" to (req.curfewEnabled ?: true),
            "curfewStart" to (req.curfewStart?.takeIf { it.isNotBlank() } ?: def.curfewStart),
            "curfewEnd" to (req.curfewEnd?.takeIf { it.isNotBlank() } ?: def.curfewEnd),
            "dailyLimitMinutes" to (req.dailyLimitMinutes ?: 0),
            "weekendLimitMinutes" to (req.weekendLimitMinutes ?: 0),
            "continuousMinutes" to (req.continuousMinutes ?: 0),
            "restMinutes" to (req.restMinutes ?: 0),
            // 豁免是"今晚"这类一次性动作，只在本次请求带 exemptMinutes 时写入。
            // 显式传 0（或负数）= **取消豁免**：家长改主意了要立刻收回，
            // 若只能等到期，"豁免发出去就收不回来"会让这个按钮没人敢点。
            "parentExemptUntil" to when {
                req.exemptMinutes == null ->
                    (base["minorMode"] as? Map<*, *>)?.get("parentExemptUntil") ?: 0L
                req.exemptMinutes > 0 -> now + req.exemptMinutes * 60_000L
                else -> 0L
            },
            "requireParentAuthToExit" to true,
            // 家长一旦显式给了时长，就标记为"已由家长调整"，供合规审计区分默认与自定义
            "parentOverridden" to (req.dailyLimitMinutes != null || req.weekendLimitMinutes != null)
        )

        val newVersion = policy.version + 1
        policyRepository.save(
            Policy(
                id = UUID.randomUUID().toString(), deviceId = deviceId, version = newVersion,
                packageJson = objectMapper.writeValueAsString(base), scene = policy.scene,
                updatedAt = now
            )
        )

        // 同步 Device.ageBand，让家长端拉设备列表即可展示档位。
        // 失败不影响策略已下发的事实，因此吞掉异常只记日志。
        runCatching {
            deviceRepository.findById(deviceId).orElse(null)?.let {
                it.ageBand = band
                deviceRepository.save(it)
            }
        }.onFailure { /* 设备记录缺失时不影响策略下发 */ }

        mqttGateway.publishPolicyNotify(deviceId, newVersion)
        return newVersion
    }

    /**
     * 读取当前未成年人模式配置（供家长端回显）。
     *
     * 缺失时按"未开启 + 8–12 岁档"给出默认视图，保证家长端拿到的一定是可渲染对象。
     */
    fun minorModeOf(deviceId: String): Map<String, Any?> {
        val policy = policyRepository.findFirstByDeviceIdOrderByVersionDesc(deviceId)
        val raw = policy?.packageJson?.let {
            runCatching { objectMapper.readValue(it, Map::class.java) }.getOrNull()
        }
        val current = raw?.get("minorMode") as? Map<*, *>
        val band = normalizeAgeBand(current?.get("ageBand")?.toString() ?: deviceRepository.findById(deviceId).orElse(null)?.ageBand)
        val def = AGE_BAND_DEFAULTS.getValue(band)
        return mapOf(
            "enabled" to (current?.get("enabled") as? Boolean ?: false),
            "ageBand" to band,
            "curfewEnabled" to (current?.get("curfewEnabled") as? Boolean ?: true),
            "curfewStart" to (current?.get("curfewStart")?.toString() ?: def.curfewStart),
            "curfewEnd" to (current?.get("curfewEnd")?.toString() ?: def.curfewEnd),
            "dailyLimitMinutes" to ((current?.get("dailyLimitMinutes") as? Int) ?: def.dailyLimitMinutes),
            "weekendLimitMinutes" to ((current?.get("weekendLimitMinutes") as? Int) ?: def.dailyLimitMinutes),
            "continuousMinutes" to ((current?.get("continuousMinutes") as? Int) ?: def.continuousMinutes),
            "restMinutes" to ((current?.get("restMinutes") as? Int) ?: def.restMinutes),
            "parentExemptUntil" to ((current?.get("parentExemptUntil") as? Number)?.toLong() ?: 0L),
            "requireParentAuthToExit" to true
        )
    }

    /** 全部档位的默认值，家长端选择龄档时直接展示后果（无需先下发） */
    fun ageBandDefaults(): Map<String, Map<String, Any>> =
        AGE_BAND_DEFAULTS.mapValues { (_, v) ->
            mapOf(
                "dailyLimitMinutes" to v.dailyLimitMinutes,
                "continuousMinutes" to v.continuousMinutes,
                "restMinutes" to v.restMinutes,
                "curfewStart" to v.curfewStart,
                "curfewEnd" to v.curfewEnd
            )
        }

    /** 孩子端拉策略：版本一致返回 upToDate，否则返回全量包 */
    fun getForChild(deviceId: String, currentVersion: Int): Any {
        val policy = policyRepository.findFirstByDeviceIdOrderByVersionDesc(deviceId)
        if (policy == null) {
            createDefault(deviceId)
            return getForChild(deviceId, currentVersion)
        }
        return if (policy.version == currentVersion) {
            mapOf("upToDate" to true)
        } else {
            objectMapper.readValue(policy.packageJson ?: "{}", Map::class.java)
        }
    }

    /** 家长端改模式：全量包追加 sceneMode，版本+1，通知孩子端重新拉取 */
    fun applyMode(deviceId: String, mode: String): Int {
        val policy = policyRepository.findFirstByDeviceIdOrderByVersionDesc(deviceId)
            ?: run { createDefault(deviceId); policyRepository.findFirstByDeviceIdOrderByVersionDesc(deviceId)!! }

        val base: MutableMap<String, Any?> = if (policy.packageJson != null) {
            objectMapper.readValue(policy.packageJson, Map::class.java) as MutableMap<String, Any?>
        } else mutableMapOf()

        base["sceneMode"] = mode
        val newVersion = policy.version + 1
        policyRepository.save(
            Policy(
                id = UUID.randomUUID().toString(), deviceId = deviceId, version = newVersion,
                packageJson = objectMapper.writeValueAsString(base), scene = policy.scene,
                updatedAt = System.currentTimeMillis()
            )
        )
        mqttGateway.publishPolicyNotify(deviceId, newVersion)
        return newVersion
    }

    private fun buildDefaultPolicyJson(deviceId: String): String {
        val pkg = mapOf(
            "version" to 1,
            "deviceId" to deviceId,
            "scene" to "FAMILY",
            "peripheral" to mapOf("wifi" to "ALLOW", "bluetooth" to "DISABLE", "camera" to "DISABLE"),
            "systemLock" to mapOf("developerOptions" to "DISABLE", "usbDebug" to "DISABLE"),
            "app" to mapOf(
                "mode" to "WHITELIST",
                "whitelist" to listOf("com.android.calculator2"),
                "blacklist" to listOf("com.tencent.mm")
            ),
            "schedule" to mapOf("timezone" to "Asia/Shanghai", "rules" to emptyList<Any>()),
            "monitoring" to mapOf("heartbeatIntervalSec" to 30, "logUploadIntervalSec" to 300),
            // 默认**不**开启未成年人模式：分龄涉及到家长对孩子年龄的确认，
            // 由家长在控制端明确选择档位后开启，避免产品替家长做这个决定。
            // 档位先落到 8–12 岁（覆盖大多数在管设备），家长改档后整体覆盖。
            "minorMode" to mapOf(
                "enabled" to false,
                "ageBand" to "BAND_8_12",
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
            )
        )
        return objectMapper.writeValueAsString(pkg)
    }
}
