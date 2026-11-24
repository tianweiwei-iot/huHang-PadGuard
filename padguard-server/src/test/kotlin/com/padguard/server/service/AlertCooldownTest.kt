package com.padguard.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.domain.Alert
import com.padguard.server.domain.Device
import com.padguard.server.repository.AlertRepository
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.ws.WebSocketPushService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * 第 7 批红护栏：告警冷却表的失败路径残留与无界增长。
 *
 * `AlertService.create` 先写 `lastRaisedAt[key] = now`，**之后**才 `findById(deviceId)`。
 * 设备不存在时直接 return null，但冷却名额已经占下 —— 该设备随后被创建、真的产生风险时，
 * 第一次告警会被这个幽灵 key 静默抑制整整一个冷却窗口（默认 10 分钟）。
 * 而 key 含 deviceId，`raiseForged` 会把 MQTT topic 里的 deviceId 传进来，每个新 id 各留一条
 * 永不删除的条目。
 */
@DisplayName("告警冷却表 —— 失败路径不占位、条目有界")
class AlertCooldownTest {

    private class Fixture {
        val alerts = Mockito.mock(AlertRepository::class.java)
        val devices = Mockito.mock(DeviceRepository::class.java)
        val ws = Mockito.mock(WebSocketPushService::class.java)
        val service = AlertService(alerts, devices, ws, ObjectMapper())

        init {
            Mockito.`when`(alerts.save(Mockito.any(Alert::class.java)))
                .thenAnswer { it.getArgument<Alert>(0) }
        }

        @Suppress("UNCHECKED_CAST")
        fun cooldownMap(): ConcurrentHashMap<String, Long> {
            val f = AlertService::class.java.getDeclaredField("lastRaisedAt")
            f.isAccessible = true
            return f.get(service) as ConcurrentHashMap<String, Long>
        }
    }

    @Test
    @DisplayName("设备不存在时的失败 create 不得抑制该设备随后的真实告警")
    fun `a failed create must not consume the cooldown slot`() {
        val fx = Fixture()
        Mockito.`when`(fx.devices.findById("devX"))
            .thenReturn(Optional.empty(), Optional.of(Device(id = "devX", userId = "u1")))

        assertNull(fx.service.create("devX", "WARNING", "t", "m", "FORGED_UPLINK"))

        val real = fx.service.create("devX", "WARNING", "t", "m", "FORGED_UPLINK")
        assertNotNull(
            real,
            "幽灵冷却 key 把设备出现后的第一条真实告警抑制了；高危告警会静默丢失一个冷却窗口"
        )
    }

    @Test
    @DisplayName("大量不存在设备的 create 不应在冷却表里留下永久条目")
    fun `failed creates leave no unbounded cooldown entries`() {
        val fx = Fixture()
        Mockito.`when`(fx.devices.findById(Mockito.anyString())).thenReturn(Optional.empty())

        repeat(200) { i ->
            fx.service.create("ghost-$i", "WARNING", "t", "m", "FORGED_UPLINK")
        }

        assertEquals(
            0, fx.cooldownMap().size,
            "冷却表只加不删：每个失败/伪造的 deviceId 都留下一条永不淘汰的 key，长期运行无界增长"
        )
    }

    @Test
    @DisplayName("自愈关闭告警后必须复位冷却，否则刚修好又复发时家长收不到第二次告警")
    fun `resolving alerts must clear the cooldown key so relapse raises again`() {
        val fx = Fixture()
        Mockito.`when`(fx.devices.findById("devR"))
            .thenReturn(Optional.of(Device(id = "devR", userId = "u1")))

        // 建一条告警 → 占下 (devR, CONTROL_LOST) 冷却名额
        assertNotNull(fx.service.create("devR", "WARNING", "t", "m", "CONTROL_LOST"))
        assertNotNull(fx.cooldownMap()["devR\u0000CONTROL_LOST"])

        // 自愈成功 → 服务端关闭告警（findBy... 返回空列表即幂等早退分支）
        Mockito.`when`(fx.alerts.findByDeviceIdOrderByTriggeredAtDesc("devR"))
            .thenReturn(emptyList())
        fx.service.resolveActiveByCategory("devR", "CONTROL_LOST")

        // 冷却 key 必须被清掉
        assertNull(
            fx.cooldownMap()["devR\u0000CONTROL_LOST"],
            "自愈关闭后冷却 key 残留：问题真实复发时第二次告警会被静默吞掉整个冷却窗口"
        )
        // 立刻复发 → 必须能重新建警
        assertNotNull(
            fx.service.create("devR", "WARNING", "t", "m", "CONTROL_LOST"),
            "自愈后复发的告警被冷却吞掉——恰在家长最需要知道时收不到"
        )
    }

    @Test
    @DisplayName("自愈只复位本设备本类别的冷却，不误伤其它类别/设备")
    fun `resolving one category must not clear other cooldown keys`() {
        val fx = Fixture()
        Mockito.`when`(fx.devices.findById(Mockito.anyString()))
            .thenReturn(Optional.of(Device(id = "devR", userId = "u1")))

        assertNotNull(fx.service.create("devR", "WARNING", "t", "m", "CONTROL_LOST"))
        assertNotNull(fx.service.create("devR", "WARNING", "t", "m", "WEB_GUARD_DOWN"))
        assertNotNull(fx.service.create("devOther", "WARNING", "t", "m", "CONTROL_LOST"))
        assertEquals(3, fx.cooldownMap().size)

        Mockito.`when`(fx.alerts.findByDeviceIdOrderByTriggeredAtDesc("devR"))
            .thenReturn(emptyList())
        fx.service.resolveActiveByCategory("devR", "CONTROL_LOST")

        assertNull(fx.cooldownMap()["devR\u0000CONTROL_LOST"], "本设备本类别应被复位")
        assertNotNull(fx.cooldownMap()["devR\u0000WEB_GUARD_DOWN"], "不得误伤同设备其它类别的冷却")
        assertNotNull(fx.cooldownMap()["devOther\u0000CONTROL_LOST"], "不得误伤其它设备的冷却")
    }
}
