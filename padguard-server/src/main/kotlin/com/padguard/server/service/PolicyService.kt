package com.padguard.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.domain.Policy
import com.padguard.server.mqtt.MqttGateway
import com.padguard.server.repository.PolicyRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class PolicyService(
    private val policyRepository: PolicyRepository,
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
            "monitoring" to mapOf("heartbeatIntervalSec" to 30, "logUploadIntervalSec" to 300)
        )
        return objectMapper.writeValueAsString(pkg)
    }
}
