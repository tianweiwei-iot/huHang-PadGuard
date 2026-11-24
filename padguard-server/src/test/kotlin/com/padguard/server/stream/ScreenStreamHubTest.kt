package com.padguard.server.stream

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * [ScreenStreamHub] 的实时性与内存边界。
 *
 * 看屏是「有界队列 + 丢最旧帧」的中继：一旦有人把缓冲改成无界、或把缓存帧
 * 无条件下发给新订阅者，家长端就会看到几十秒前的旧画面（甚至设备卸载前的残图）
 * 却以为在看直播。这里同时锁死按设备累积的缓存回收，避免长期运行只增不减导致 OOM。
 */
class ScreenStreamHubTest {

    @Suppress("UNCHECKED_CAST")
    private fun frameTimes(hub: ScreenStreamHub): ConcurrentHashMap<String, AtomicLong> {
        val f = ScreenStreamHub::class.java.getDeclaredField("lastFrameAt")
        f.isAccessible = true
        return f.get(hub) as ConcurrentHashMap<String, AtomicLong>
    }

    private fun pollBytes(sub: ScreenStreamHub.Subscriber) = sub.poll(20)?.get(0)

    @Test
    fun `订阅者队列满三帧时丢最旧帧 保证永远拿到最新画面`() {
        val sub = ScreenStreamHub.Subscriber()
        (1..4).forEach { sub.offer(byteArrayOf(it.toByte())) }
        assertEquals(listOf<Byte>(2, 3, 4), listOf(pollBytes(sub)!!, pollBytes(sub)!!, pollBytes(sub)!!))
        assertNull(sub.poll(20))
    }

    @Test
    fun `订阅者关闭后不再接收帧且立即返回 null`() {
        val sub = ScreenStreamHub.Subscriber()
        sub.offer(byteArrayOf(1))
        sub.close()
        sub.offer(byteArrayOf(2))
        assertNull(sub.poll(20))
    }

    @Test
    fun `新订阅者立即收到 30 秒内的最近一帧`() {
        val hub = ScreenStreamHub()
        hub.publish("D1", byteArrayOf(7))
        val sub = hub.subscribe("D1")!!
        assertEquals(7.toByte(), pollBytes(sub)!!)
        assertTrue(hub.lastFrameAt("D1") > 0L)
    }

    @Test
    fun `设备超过 30 秒无新帧时不回放陈旧缓存帧`() {
        val hub = ScreenStreamHub()
        hub.publish("D1", byteArrayOf(9))
        frameTimes(hub)["D1"]!!.set(System.currentTimeMillis() - 31_000L)
        val sub = hub.subscribe("D1")!!
        assertNull(sub.poll(20))
    }

    @Test
    fun `从未推流的设备订阅后无缓存帧`() {
        val hub = ScreenStreamHub()
        assertNull(hub.subscribe("D1")!!.poll(20))
        assertEquals(0L, hub.lastFrameAt("D1"))
    }

    @Test
    fun `单设备并发观看达上限后返回 null 而不是空流`() {
        val hub = ScreenStreamHub()
        val s1 = hub.subscribe("D1")
        val s2 = hub.subscribe("D1")
        val s3 = hub.subscribe("D1")
        assertTrue(listOf(s1, s2, s3).all { it != null })
        assertNull(hub.subscribe("D1"))
    }

    @Test
    fun `退订后释放名额并且不再收到新帧`() {
        val hub = ScreenStreamHub()
        val s1 = hub.subscribe("D1")!!
        hub.subscribe("D1")
        hub.subscribe("D1")
        hub.unsubscribe("D1", s1)
        val s4 = hub.subscribe("D1")
        assertTrue(s4 != null)
        hub.publish("D1", byteArrayOf(3))
        assertNull(s1.poll(20))
        assertEquals(3.toByte(), pollBytes(s4!!)!!)
    }

    @Test
    fun `publish 把同一帧投递给所有订阅者并累计上行字节`() {
        val hub = ScreenStreamHub()
        val a = hub.subscribe("D1")!!
        val b = hub.subscribe("D1")!!
        hub.publish("D1", ByteArray(120) { 1 })
        assertEquals(1.toByte(), pollBytes(a)!!)
        assertEquals(1.toByte(), pollBytes(b)!!)
        assertEquals(120L, hub.totalUpBytes("D1"))
    }

    @Test
    fun `上行与下行字节分别累计且累计值不被速率结算清零`() {
        val hub = ScreenStreamHub()
        hub.addUplink("D1", 500L)
        hub.addDownlink("D1", 250L)
        hub.upstreamBps("D1")
        hub.downstreamBps("D1")
        assertEquals(500L, hub.totalUpBytes("D1"))
        assertEquals(250L, hub.totalDownBytes("D1"))
        assertEquals(0L, hub.upstreamBps("UNKNOWN"))
        assertEquals(0L, hub.downstreamBps("UNKNOWN"))
        assertEquals(0L, hub.totalUpBytes("UNKNOWN"))
    }

    @Test
    fun `滚动窗口在满 1 秒后结算并重置字节`() {
        val hub = ScreenStreamHub()
        hub.addUplink("D1", 1000L)
        @Suppress("UNCHECKED_CAST")
        val starts = ScreenStreamHub::class.java.getDeclaredField("upWindowStart")
            .apply { isAccessible = true }.get(hub) as ConcurrentHashMap<String, AtomicLong>
        starts["D1"]!!.set(System.currentTimeMillis() - 1000L)
        val bps = hub.upstreamBps("D1")
        assertTrue(bps in 900L..1100L, "actual=$bps")
        assertEquals(1000L, hub.totalUpBytes("D1"))
    }

    @Test
    fun `推流设备注册与注销维护并发准入集合`() {
        val hub = ScreenStreamHub()
        assertFalse(hub.isPublishing("D1"))
        hub.registerPublisher("D1")
        hub.registerPublisher("D2")
        assertTrue(hub.isPublishing("D1"))
        assertEquals(2, hub.publisherCount())
        assertEquals(setOf("D1", "D2"), hub.publishersSnapshot().toSet())
        hub.unregisterPublisher("D1")
        assertFalse(hub.isPublishing("D1"))
        assertEquals(1, hub.publisherCount())
    }

    @Test
    fun `evictIdleDevices 不清活跃推流中的设备缓存`() {
        val hub = ScreenStreamHub()
        hub.registerPublisher("D1")
        hub.publish("D1", byteArrayOf(1))
        frameTimes(hub)["D1"]!!.set(System.currentTimeMillis() - 10 * 60_000L)
        hub.evictIdleDevices()
        assertTrue(hub.lastFrameAt("D1") > 0L)
    }

    @Test
    fun `evictIdleDevices 不清仍有订阅者的设备缓存`() {
        val hub = ScreenStreamHub()
        hub.publish("D1", byteArrayOf(1))
        val sub = hub.subscribe("D1")!!
        frameTimes(hub)["D1"]!!.set(System.currentTimeMillis() - 10 * 60_000L)
        hub.evictIdleDevices()
        assertTrue(hub.lastFrameAt("D1") > 0L)
        hub.unsubscribe("D1", sub)
    }

    @Test
    fun `evictIdleDevices 回收长期空闲设备且不再回放旧帧`() {
        val hub = ScreenStreamHub()
        hub.publish("D1", byteArrayOf(1))
        val sub = hub.subscribe("D1")!!
        hub.unsubscribe("D1", sub)
        frameTimes(hub)["D1"]!!.set(System.currentTimeMillis() - 6 * 60_000L)
        hub.evictIdleDevices()
        assertEquals(0L, hub.lastFrameAt("D1"))
        assertNull(hub.subscribe("D1")!!.poll(20))
    }

    @Test
    fun `evict 彻底移除帧缓存 上下行统计与订阅列表`() {
        val hub = ScreenStreamHub()
        hub.publish("D1", byteArrayOf(1))
        hub.subscribe("D1")
        hub.addUplink("D1", 100L)
        hub.addDownlink("D1", 90L)
        hub.evict("D1")
        assertEquals(0L, hub.lastFrameAt("D1"))
        assertEquals(0L, hub.totalUpBytes("D1"))
        assertEquals(0L, hub.totalDownBytes("D1"))
        assertNull(hub.subscribe("D1")!!.poll(20))
    }

    @Test
    fun `带宽汇总日志在无推流与有推流时都不抛异常`() {
        val hub = ScreenStreamHub()
        assertDoesNotThrow { hub.logAggregateBandwidth() }
        hub.registerPublisher("D1")
        hub.addUplink("D1", 2048L)
        hub.addDownlink("D1", 1024L)
        assertDoesNotThrow { hub.logAggregateBandwidth() }
    }
}
