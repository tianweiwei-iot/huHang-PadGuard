package com.padguard.server.ws

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.springframework.web.socket.WebSocketSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [WebSocketPushService] 的会话治理单测（R-P3-7）。
 *
 * 锁定的都是"量级一上来才会炸"的问题：同一账号堆积大量会话把连接吃满、
 * 死会话不清理导致每次推送空转。这些问题在单机联调时永远看不到。
 */
class WebSocketPushServiceTest {

    private val mapper = ObjectMapper()

    private fun session(open: Boolean = true): WebSocketSession {
        val s = mock(WebSocketSession::class.java)
        Mockito.`when`(s.isOpen).thenReturn(open)
        return s
    }

    @Test
    fun `超出单账号上限时踢掉最旧的会话并关闭它`() {
        val svc = WebSocketPushService(mapper, maxSessionsPerUser = 2, users = com.padguard.server.support.PushTestUsers.permissive())
        val first = session()
        val second = session()
        val third = session()

        svc.register("u1", first)
        svc.register("u1", second)
        svc.register("u1", third)

        // 最旧的被关闭
        verify(first, times(1)).close(any())
        verify(second, never()).close(any())
        verify(third, never()).close(any())

        // 推送只到留下的两条，被踢的那条不再收到消息
        svc.pushToUser("u1", mapOf("type" to "ping"))
        verify(first, never()).sendMessage(any())
        verify(second, times(1)).sendMessage(any())
        verify(third, times(1)).sendMessage(any())
    }

    @Test
    fun `默认上限是 5，第 6 条连进来时最旧的被踢`() {
        val svc = WebSocketPushService(mapper, users = com.padguard.server.support.PushTestUsers.permissive())
        val sessions = (1..6).map { session() }
        sessions.forEach { svc.register("u1", it) }

        verify(sessions.first(), times(1)).close(any())
        // 前 5 条里除最旧外都应保留
        for (s in sessions.subList(1, 5)) verify(s, never()).close(any())
    }

    @Test
    fun `注册时顺手清掉已经关闭的死会话，避免集合无限增长`() {
        val svc = WebSocketPushService(mapper, maxSessionsPerUser = 2, users = com.padguard.server.support.PushTestUsers.permissive())
        val dead = session(open = false)
        val alive = session()

        svc.register("u1", dead)
        svc.register("u1", alive)
        // 死会话不计入上限：再来一条时不该把 alive 踢掉
        val newest = session()
        svc.register("u1", newest)

        verify(alive, never()).close(any())
        svc.pushToUser("u1", mapOf("type" to "ping"))
        verify(dead, never()).sendMessage(any())
        verify(alive, times(1)).sendMessage(any())
    }

    @Test
    fun `推送时遇到关闭的会话跳过并清理，不影响其它会话`() {
        val svc = WebSocketPushService(mapper, maxSessionsPerUser = 5, users = com.padguard.server.support.PushTestUsers.permissive())
        val dead = session(open = false)
        val alive = session()
        svc.register("u1", dead)
        svc.register("u1", alive)

        svc.pushToUser("u1", mapOf("type" to "alert"))

        verify(dead, never()).sendMessage(any())
        verify(alive, times(1)).sendMessage(any())
        // 清理后第二次推送不应再遍历到死会话（sendMessage 次数仍为 1）
        svc.pushToUser("u1", mapOf("type" to "alert"))
        verify(dead, never()).sendMessage(any())
        verify(alive, times(2)).sendMessage(any())
    }

    @Test
    fun `单个会话发送失败不影响同账号其它会话`() {
        val svc = WebSocketPushService(mapper, maxSessionsPerUser = 5, users = com.padguard.server.support.PushTestUsers.permissive())
        val broken = session()
        Mockito.doThrow(IllegalStateException("broken pipe"))
            .`when`(broken).sendMessage(any())
        val ok = session()
        svc.register("u1", broken)
        svc.register("u1", ok)

        svc.pushToUser("u1", mapOf("type" to "alert"))

        verify(ok, atLeastOnce()).sendMessage(any())
        // 失败的会话被清理：再推一次只会到 ok
        svc.pushToUser("u1", mapOf("type" to "alert"))
        verify(ok, times(2)).sendMessage(any())
    }

    @Test
    fun `注销后不再收到推送`() {
        val svc = WebSocketPushService(mapper, users = com.padguard.server.support.PushTestUsers.permissive())
        val s = session()
        svc.register("u1", s)
        svc.unregister("u1", s)

        svc.pushToUser("u1", mapOf("type" to "ping"))
        verify(s, never()).sendMessage(any())
    }

    @Test
    fun `上限配置非法时至少保留一条，不会把自己踢掉`() {
        val svc = WebSocketPushService(mapper, maxSessionsPerUser = 0, users = com.padguard.server.support.PushTestUsers.permissive())
        val only = session()
        svc.register("u1", only)

        verify(only, never()).close(any())
        svc.pushToUser("u1", mapOf("type" to "ping"))
        verify(only, times(1)).sendMessage(any())
    }

    /**
     * 慢客户端隔离（R46）。
     *
     * `sendMessage` 是阻塞写：客户端半开连接时单次调用会卡到容器超时（Tomcat 默认 20 秒）。
     * 若此时其它线程的推送继续排队等同一会话的 socket 锁，一次网络故障会被放大成
     * N 个调用线程各卡 20 秒 —— 而这些线程大多是 MQTT 上行回调线程，会把全部设备的心跳/回执一起拖死。
     * 因此同一会话在写期间，后续推送必须**直接跳过**，且不能把它误判成死会话（下一条还要能送达）。
     */
    @Test
    fun `同一会话上一条推送未写完时后续推送跳过而不是排队`() {
        val svc = WebSocketPushService(mapper, users = com.padguard.server.support.PushTestUsers.permissive())
        val slow = session()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Mockito.doAnswer {
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
            null
        }.`when`(slow).sendMessage(any())
        svc.register("u1", slow)

        val t = Thread { svc.pushToUser("u1", mapOf("type" to "alert.new")) }
        t.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS), "首条推送应进入 sendMessage")

        // 首条仍卡在 socket 上：这条必须立刻返回（跳过），绝不能在 sendMessage 上排队
        svc.pushToUser("u1", mapOf("type" to "alert.new"))
        verify(slow, times(1)).sendMessage(any())

        release.countDown()
        t.join(10_000)
        assertFalse(t.isAlive, "首条推送应已返回")

        // 被跳过不等于会话已死：写完后下一条照常投递
        svc.pushToUser("u1", mapOf("type" to "alert.new"))
        verify(slow, times(2)).sendMessage(any())
        verify(slow, never()).close(any())
    }
}
