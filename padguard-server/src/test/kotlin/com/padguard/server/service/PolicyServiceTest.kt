package com.padguard.server.service

import com.padguard.server.domain.Device
import com.padguard.server.domain.Policy
import com.padguard.server.dto.MinorModeRequest
import com.padguard.server.mqtt.MqttGateway
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.PolicyRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * 未成年人模式（分龄护苗）的单元测试。
 *
 * 核心回归点：家长开启未成年人模式但**未显式设置**每日/周末时长时，
 * 必须回落到分龄默认档位（见 [MinorModeRequest] 注释：null = 沿用分龄默认），
 * 绝不能写 0。0 对孩子端意味着"整天禁用"，与"用默认 60 分钟"完全相反，
 * 且会让 [com.padguard.server.service.PolicyService.minorModeOf] 里的
 * `?: def.dailyLimitMinutes` 兜底彻底失效（0 不是 null，兜底不触发）。
 */
class PolicyServiceTest {

    private class FakePolicyRepo : FakeJpaRepository<Policy, String>(), PolicyRepository {
        override val table = LinkedHashMap<String, Policy>()
        override fun idOf(entity: Policy) = entity.id
        override fun findFirstByDeviceIdOrderByVersionDesc(deviceId: String): Policy? =
            table.values.filter { it.deviceId == deviceId }.maxByOrNull { it.version }
    }

    private class FakeDeviceRepo : FakeJpaRepository<Device, String>(), DeviceRepository {
        override val table = LinkedHashMap<String, Device>()
        override fun idOf(entity: Device) = entity.id
        override fun findByUserId(userId: String): List<Device> = emptyList()
        override fun countByUserId(userId: String): Long = 0L
        override fun countByOnlineStatus(onlineStatus: String): Long = 0L
        override fun findByDeviceSn(deviceSn: String): List<Device> = emptyList()
        override fun findByLastActiveAtGreaterThan(since: Long): List<Device> = emptyList()
    }

    private val mapper = ObjectMapper()

    private fun svc(): Pair<PolicyService, FakePolicyRepo> {
        val policyRepo = FakePolicyRepo()
        // MQTT 关闭（mqttClient=null）时 MqttGateway 静默跳过下发，测试无需真实 broker。
        val s = PolicyService(
            policyRepo, FakeDeviceRepo(),
            MqttGateway(null, mapper, "default"),
            mapper, "default"
        )
        return s to policyRepo
    }

    /** 从落库的策略包里取出 minorMode 子对象，供校验"是否自定义"等标记。
     * 取该设备版本号最大的那条（applyMinorMode 会先建默认 v1 再写 v2+，不能用 first）。 */
    private fun minorModeOf(repo: FakePolicyRepo, deviceId: String): Map<String, Any?> {
        val policy = repo.table.values.filter { it.deviceId == deviceId }.maxByOrNull { it.version }!!
        val json = policy.packageJson!!
        @Suppress("UNCHECKED_CAST")
        return mapper.readValue(json, Map::class.java)["minorMode"] as Map<String, Any?>
    }

    @Test
    fun `applyMinorMode falls back to age-band default when daily limit is not specified`() {
        val (s, repo) = svc()
        s.applyMinorMode("dev-1", MinorModeRequest(enabled = true, ageBand = "BAND_8_12"))

        val view = s.minorModeOf("dev-1")
        assertEquals(60, view["dailyLimitMinutes"] as Int, "8-12 岁档未设时长应回落到 60 分钟，而非 0（0 会让设备整天锁定）")
        assertEquals(60, view["weekendLimitMinutes"] as Int, "周末时长未设应与工作日相同，落到分龄默认")
        assertEquals(false, minorModeOf(repo, "dev-1")["parentOverridden"], "家长未自定义时长，parentOverridden 应为 false")
    }

    @Test
    fun `applyMinorMode honors explicit zero daily limit as a hard lock`() {
        val (s, repo) = svc()
        s.applyMinorMode("dev-2", MinorModeRequest(enabled = true, ageBand = "BAND_8_12", dailyLimitMinutes = 0))

        val view = s.minorModeOf("dev-2")
        assertEquals(0, view["dailyLimitMinutes"] as Int, "显式传 0 是家长真的要锁死设备，必须保留 0")
        assertEquals(true, minorModeOf(repo, "dev-2")["parentOverridden"], "显式给了时长，parentOverridden 应为 true")
    }

    @Test
    fun `applyMinorMode preserves an explicit daily limit and uses the matching band default for weekend`() {
        val (s, repo) = svc()
        s.applyMinorMode("dev-3", MinorModeRequest(enabled = true, ageBand = "UNDER_3", dailyLimitMinutes = 45))

        val view = s.minorModeOf("dev-3")
        assertEquals(45, view["dailyLimitMinutes"] as Int, "显式给的时长必须原样保留")
        assertEquals(30, view["weekendLimitMinutes"] as Int, "UNDER_3 周末未设应落到该档默认 30 分钟")
        assertEquals(true, minorModeOf(repo, "dev-3")["parentOverridden"], "显式给了时长，parentOverridden 应为 true")
    }
}
