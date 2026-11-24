package com.padguard.server.config

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.padguard.server.PadGuardServerApplication
import com.padguard.server.domain.Command
import com.padguard.server.mqtt.ChildUplinkHandler
import com.padguard.server.repository.CommandRepository
import com.padguard.server.repository.DeviceEventRepository
import com.padguard.server.service.AlertService
import com.padguard.server.service.AppDistributionConverger
import com.padguard.server.service.DeviceService
import com.padguard.server.ws.WebSocketPushService
import org.eclipse.paho.mqttv5.common.MqttMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.slf4j.MDC
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * traceId 跨线程传播的护栏。
 *
 * 同步请求链路由 [TraceIdFilter] 覆盖，但 `@Async` 工作线程与 MQTT 回调线程都是
 * ThreadLocal 之外的执行体：不显式传播，这两条链路的日志在 console pattern 里
 * traceId 一栏是空的。孩子端上行丢一条消息、逆地理回填失败时，恰恰是最需要
 * 「把同一次事件的几十行日志聚起来」的场景。
 */
@DisplayName("TraceIds —— 异步链路传播与 MDC 卫生")
class TraceIdsTest {

    @Test
    @DisplayName("withTrace 嵌套 / 异常后恢复旧值，退出作用域不留残留")
    fun `withTrace restores the previous MDC value`() {
        TraceIds.withTrace("outer") {
            TraceIds.withTrace("inner") { assertEquals("inner", TraceIds.current()) }
            assertEquals("outer", TraceIds.current(), "嵌套退出必须恢复到外层 ID")
            runCatching { TraceIds.withTrace("boom") { error("expected") } }
            assertEquals("outer", TraceIds.current(), "异常路径也必须恢复，否则请求后半段日志失去关联")
        }
        assertNull(TraceIds.current(), "最外层退出必须清空，线程池复用会串号")
    }

    @Test
    @DisplayName("newId 带前缀且格式安全，两次生成不重复")
    fun `newId is safe and unique`() {
        val a = TraceIds.newId("mqtt")
        val b = TraceIds.newId("mqtt")
        assertTrue(a.startsWith("mqtt-"))
        assertTrue(Regex("[A-Za-z0-9_-]{1,64}").matches(a))
        assertNotEquals(a, b)
        assertTrue(Regex("[A-Za-z0-9_-]{1,64}").matches(TraceIds.newId()))
    }

    @Test
    @DisplayName("decorate 在提交时刻捕获 MDC，而不是在执行时读")
    fun `decorate captures context at submission time`() {
        val seen = AtomicReference<String?>(null)
        val wrapped = TraceIds.withTrace("req-7") {
            TraceIds.decorate(Runnable { seen.set(TraceIds.current()) })
        }
        assertNull(TraceIds.current(), "作用域退出后调用线程必须干净")
        Thread(wrapped).apply { start(); join(5_000) }
        assertEquals("req-7", seen.get(), "异步执行线程没有继承提交时刻的 traceId")
    }

    @Test
    @DisplayName("应用 @Async 执行器把请求 traceId 带进工作线程")
    fun `application taskExecutor propagates traceId to async threads`() {
        val executor = PadGuardServerApplication().taskExecutor() as ThreadPoolTaskExecutor
        try {
            val seen = AtomicReference<String?>(null)
            val done = CountDownLatch(1)
            TraceIds.withTrace("req-async-42") {
                executor.execute { seen.set(TraceIds.current()); done.countDown() }
            }
            assertTrue(done.await(5, TimeUnit.SECONDS), "异步任务未执行")
            assertEquals("req-async-42", seen.get(), "@Async 日志没有继承请求 traceId")

            // 没有请求上下文时（定时任务触发的异步）也应生成独立 ID，而不是留空。
            val orphanSeen = AtomicReference<String?>(null)
            val done2 = CountDownLatch(1)
            executor.execute { orphanSeen.set(TraceIds.current()); done2.countDown() }
            assertTrue(done2.await(5, TimeUnit.SECONDS))
            assertTrue(
                !orphanSeen.get().isNullOrBlank() && orphanSeen.get()!!.startsWith("async-"),
                "无上下文的异步任务应生成 async- 独立 ID，实际=${orphanSeen.get()}"
            )
        } finally {
            executor.shutdown()
        }
    }

    @Test
    @DisplayName("MQTT 上行回调内部的日志有 traceId，且每条报文各自独立")
    fun `mqtt uplink callback runs inside its own trace scope`() {
        val seen = mutableListOf<String?>()
        val commandRepository = Mockito.mock(CommandRepository::class.java)
        Mockito.`when`(commandRepository.findByMsgIdAndDeviceId(anyString(), anyString())).thenAnswer {
            synchronized(seen) { seen.add(TraceIds.current()) }
            Command(id = "c1", deviceId = "dev", msgId = "m1", type = "LOCK", status = "PENDING")
        }
        val handler = ChildUplinkHandler(
            null,
            jacksonObjectMapper(),
            commandRepository,
            Mockito.mock(DeviceEventRepository::class.java),
            Mockito.mock(DeviceService::class.java),
            Mockito.mock(WebSocketPushService::class.java),
            Mockito.mock(AlertService::class.java),
            Mockito.mock(AppDistributionConverger::class.java),
            false
        )
        val payload = """{"msgId":"m1","status":"SUCCESS"}""".toByteArray()

        handler.messageArrived("pg/v1/t/up/dev/ack", MqttMessage().apply { this.payload = payload })
        handler.messageArrived("pg/v1/t/up/dev/ack", MqttMessage().apply { this.payload = payload })

        assertEquals(2, seen.size, "两条上行都应进入业务处理")
        assertTrue(!seen[0].isNullOrBlank(), "MQTT 回调线程的日志没有 traceId")
        assertTrue(seen[0]!!.startsWith("mqtt-"), "MQTT 链路 ID 应有可辨识前缀：${seen[0]}")
        assertNotEquals(seen[0], seen[1], "两条不同报文共用一个 traceId 等于没做区分")
        assertNull(MDC.get(TraceIds.MDC_KEY), "回调结束后 Paho 线程不得残留 traceId")
    }
}
