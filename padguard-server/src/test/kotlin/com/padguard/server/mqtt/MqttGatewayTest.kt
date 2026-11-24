package com.padguard.server.mqtt

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.dto.CommandPacket
import org.eclipse.paho.mqttv5.client.MqttClient
import org.eclipse.paho.mqttv5.common.MqttMessage
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.reset

/**
 * [MqttGateway] 的下行契约。
 *
 * 重点不是「能发出去」，而是三条容易在重构里丢掉的约束：
 * - 主题必须带租户前缀，策略通知默认 retained（设备上线即拿到最新版本）；
 * - 解绑/易主必须向策略与配置两个主题各发一条**空 retained** 顶掉旧消息，
 *   否则新家长上线会先收到上一任的策略；
 * - 发布异常必须抛出且累计连续失败次数（供告警消费），成功一次即清零；
 *   而 MQTT 被显式关闭时静默跳过、由 HTTP 轮询补发，绝不是故障。
 */
class MqttGatewayTest {

    private lateinit var client: MqttClient
    private val om = ObjectMapper()

    @BeforeEach
    fun setUp() {
        client = mock(MqttClient::class.java)
    }

    private fun gateway(retained: Boolean = true, mqtt: MqttClient? = client) =
        MqttGateway(mqtt, om, "tenantA", retained)

    /** 用调用记录取 (topic, message)，避开 Paho 多重载与 captor 的 Kotlin 空值坑。 */
    private fun publishes(): List<Pair<String, MqttMessage>> =
        mockingDetails(client).invocations
            .filter { it.method.name == "publish" && it.arguments.size == 2 }
            .map { (it.arguments[0] as String) to (it.arguments[1] as MqttMessage) }

    private fun commandPacket() = CommandPacket(
        msgId = "M1", type = "LOCK", version = 1, timestamp = 10L, expiresAt = 20L,
        priority = "HIGH", payload = mapOf("reason" to "bedtime"), signature = "sig"
    )

    @Test
    fun `下行主题带租户前缀且按用途区分`() {
        val gw = gateway()
        assertEquals("pg/v1/tenantA/down/D1/command", gw.downCommandTopic("D1"))
        assertEquals("pg/v1/tenantA/down/D1/policy", gw.downPolicyTopic("D1"))
        assertEquals("pg/v1/tenantA/down/D1/config", gw.downConfigTopic("D1"))
    }

    @Test
    fun `指令下发 QoS1 非 retained 且载荷含 msgId`() {
        gateway().publishCommand("D1", commandPacket())
        val calls = publishes()
        assertEquals(1, calls.size)
        assertEquals("pg/v1/tenantA/down/D1/command", calls[0].first)
        val msg = calls[0].second
        assertEquals(1, msg.qos)
        assertFalse(msg.isRetained)
        val json = String(msg.payload, Charsets.UTF_8)
        assertTrue(json.contains("\"msgId\":\"M1\""))
        assertTrue(json.contains("\"type\":\"LOCK\""))
    }

    @Test
    fun `策略版本通知默认 retained 且只含 version`() {
        gateway().publishPolicyNotify("D1", 7)
        val msg = publishes().single().second
        assertTrue(msg.isRetained)
        assertEquals("{\"version\":7}", String(msg.payload, Charsets.UTF_8))
    }

    @Test
    fun `关闭策略 retained 配置后退回非 retained`() {
        gateway(retained = false).publishPolicyNotify("D1", 3)
        assertFalse(publishes().single().second.isRetained)
    }

    @Test
    fun `运行参数下发序列化为 JSON 对象`() {
        gateway().publishConfig("D1", mapOf("deviceName" to "孩子的平板"))
        val call = publishes().single()
        assertEquals("pg/v1/tenantA/down/D1/config", call.first)
        assertTrue(String(call.second.payload, Charsets.UTF_8).contains("deviceName"))
        assertFalse(call.second.isRetained)
    }

    @Test
    fun `clearRetained 向策略与配置主题各发一条空 retained 消息`() {
        gateway().clearRetained("D1")
        val calls = publishes()
        assertEquals(2, calls.size)
        assertEquals(
            setOf("pg/v1/tenantA/down/D1/policy", "pg/v1/tenantA/down/D1/config"),
            calls.map { it.first }.toSet()
        )
        calls.forEach { (_, msg) ->
            assertEquals(0, msg.payload.size)
            assertTrue(msg.isRetained)
            assertEquals(1, msg.qos)
        }
    }

    @Test
    fun `clearRetained 单条失败只告警 不影响另一条也不上抛`() {
        doThrow(RuntimeException("broker down"))
            .`when`(client).publish(anyString(), any(MqttMessage::class.java) ?: MqttMessage(ByteArray(0)))
        assertDoesNotThrow { gateway().clearRetained("D1") }
        assertEquals(2, publishes().size)
    }

    @Test
    fun `MQTT 显式关闭时所有下发静默跳过且不上抛`() {
        val gw = gateway(mqtt = null)
        assertDoesNotThrow {
            gw.publishCommand("D1", commandPacket())
            gw.publishPolicyNotify("D1", 1)
            gw.publishConfig("D1", emptyMap())
            gw.clearRetained("D1")
        }
        assertEquals(0, gw.consecutivePublishFailures())
        assertEquals(0, publishes().size)
    }

    @Test
    fun `连续发布失败累计次数且异常继续上抛`() {
        doThrow(RuntimeException("broker down"))
            .`when`(client).publish(anyString(), any(MqttMessage::class.java) ?: MqttMessage(ByteArray(0)))
        val gw = gateway()
        assertThrows(RuntimeException::class.java) { gw.publishConfig("D1", emptyMap()) }
        assertThrows(RuntimeException::class.java) { gw.publishConfig("D1", emptyMap()) }
        assertEquals(2, gw.consecutivePublishFailures())
    }

    @Test
    fun `成功发布一次即清零连续失败计数`() {
        doThrow(RuntimeException("broker down"))
            .`when`(client).publish(anyString(), any(MqttMessage::class.java) ?: MqttMessage(ByteArray(0)))
        val gw = gateway()
        assertThrows(RuntimeException::class.java) { gw.publishConfig("D1", emptyMap()) }
        assertEquals(1, gw.consecutivePublishFailures())
        reset(client)
        gw.publishConfig("D1", emptyMap())
        assertEquals(0, gw.consecutivePublishFailures())
    }

    @Test
    fun `clearRetained 失败不计入发布失败streak`() {
        doThrow(RuntimeException("broker down"))
            .`when`(client).publish(anyString(), any(MqttMessage::class.java) ?: MqttMessage(ByteArray(0)))
        val gw = gateway()
        assertDoesNotThrow { gw.clearRetained("D1") }
        assertEquals(0, gw.consecutivePublishFailures())
    }
}
