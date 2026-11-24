package com.padguard.server.mqtt

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.padguard.server.domain.DeviceEvent
import com.padguard.server.repository.CommandRepository
import com.padguard.server.repository.DeviceEventRepository
import com.padguard.server.service.AlertService
import com.padguard.server.service.AppDistributionConverger
import com.padguard.server.service.DeviceService
import com.padguard.server.ws.WebSocketPushService
import org.eclipse.paho.mqttv5.common.MqttMessage
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doAnswer
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * HIGH 风险事件实时单发通道（`.../alert/realtime`）的路由回归。
 *
 * 这条通道是隧道静默失效等 HIGH 告警的**秒级**路径（批量 event 通道默认 5 分钟窗口）。
 * 其 topic 是双段类型 `alert/realtime`，而旧实现取 `segments[5]` 只得到 `alert`，
 * 与 `when(type)` 的 `"alert/realtime"` 分支永远匹配不上 —— 消息进得来、却静默丢弃，
 * 且外层 runCatching 无事可 catch（无异常），连 warn 日志都没有，故障完全隐形。
 *
 * 本测试直接对真实 `messageArrived` 发报文，断言事件确实进入落库链路；
 * 另设单段 `event` 通道对照，证明测试构造正确、不是"怎么都过/不过"。
 */
class ChildUplinkRealtimeAlertRoutingTest {

    private val repo = Mockito.mock(DeviceEventRepository::class.java)
    private val deviceService = Mockito.mock(DeviceService::class.java)
    private val alertService = Mockito.mock(AlertService::class.java)

    private fun handler() = ChildUplinkHandler(
        null,
        jacksonObjectMapper(),
        Mockito.mock(CommandRepository::class.java),
        repo,
        deviceService, // getHmacSecret 默认返回 null
        Mockito.mock(WebSocketPushService::class.java),
        alertService,
        Mockito.mock(AppDistributionConverger::class.java),
        // 本测试聚焦"双段路由解析"而非验签。安全加固后无密钥裸报文在
        // requireUplinkSignature=true 时必须拒收（见下方独立用例），这里显式关闭开关
        // 构造裸报文，保证路由断言不被验签分支吞掉。
        false
    )

    private fun deliver(topic: String) {
        `when`<Set<String>>(repo.findExistingEventIds(anyList())).thenReturn(emptySet())
        val payload = """{"eventId":"ev-high-1","deviceId":"dev-1","type":"RISK","level":"HIGH","timestamp":1000}"""
        handler().messageArrived(topic, MqttMessage(payload.toByteArray(StandardCharsets.UTF_8)))
    }

    @Test
    fun `双段 alert realtime 上行必须进入事件落库链路`() {
        deliver("pg/v1/default/up/dev-1/alert/realtime")

        Mockito.verify(repo).saveAll(any<Iterable<DeviceEvent>>() ?: emptyList())
    }

    @Test
    fun `单段 event 上行对照仍然进入事件落库链路`() {
        // 对照用例：证明上面那条的构造（验签放行/反序列化/去重）本身没问题，
        // alert/realtime 的失败只可能出在 type 路由解析上。
        deliver("pg/v1/default/up/dev-1/event")

        Mockito.verify(repo).saveAll(any<Iterable<DeviceEvent>>() ?: emptyList())
    }

    @Test
    fun `上行业务处理不占用 MQTT 回调线程`() {
        val executor = Executors.newSingleThreadExecutor()
        val slowService = Mockito.mock(DeviceService::class.java)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        doAnswer {
            started.countDown()
            release.await(3, TimeUnit.SECONDS)
            null
        }.`when`(slowService).getHmacSecret("dev-1")
        val asyncHandler = ChildUplinkHandler(
            null, jacksonObjectMapper(),
            Mockito.mock(CommandRepository::class.java), repo, slowService,
            Mockito.mock(WebSocketPushService::class.java), alertService,
            Mockito.mock(AppDistributionConverger::class.java), true, executor
        )
        val payload = """{"eventId":"ev-async","deviceId":"dev-1","type":"RISK","level":"HIGH","timestamp":1000}"""

        val callback = Thread {
            asyncHandler.messageArrived(
                "pg/v1/default/up/dev-1/event",
                MqttMessage(payload.toByteArray(StandardCharsets.UTF_8))
            )
        }
        callback.start()
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS), "worker 必须开始处理上行")
            callback.join(1_000)
            assertFalse(callback.isAlive, "Paho 回调线程必须立刻返回，不能被查库/落库拖住")
            release.countDown()
            callback.join(3_000)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `无密钥设备的裸报文在强制验签开启时必须被拒收`() {
        // 安全回归：信封分支一直拒收无密钥设备，裸报文分支旧实现却无条件放行，
        // 去掉 s/p 即可伪造 event/heartbeat 推假告警和假在线状态。两者必须口径一致。
        val secureHandler = ChildUplinkHandler(
            null, jacksonObjectMapper(),
            Mockito.mock(CommandRepository::class.java), repo, deviceService,
            Mockito.mock(WebSocketPushService::class.java), alertService,
            Mockito.mock(AppDistributionConverger::class.java), true
        )
        val payload = """{"eventId":"ev-forged","deviceId":"dev-1","type":"RISK","level":"HIGH","timestamp":1000}"""
        secureHandler.messageArrived(
            "pg/v1/default/up/dev-1/event",
            MqttMessage(payload.toByteArray(StandardCharsets.UTF_8))
        )
        Mockito.verify(repo, Mockito.never()).saveAll(any<Iterable<DeviceEvent>>() ?: emptyList())
    }
}
