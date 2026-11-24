package com.padguard.child.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 守护链路四态 [computeDaemonOnlineState] 的契约回归。
 *
 * 这条函数直接决定家长端"链路"芯片显示"在线 / 降级 / 离线"，是"孩子端永远不会掉线"
 * 可观测性的分水岭：只要 HTTPS 拉取骨干还通（httpUp），就绝不报 OFFLINE，
 * 避免把"MQTT 抖动但仍在监管"的设备误判为脱管；同时真离线（双通道皆断）必须如实 OFFLINE。
 */
class DaemonOnlineStateTest {

    @Test
    fun `双通道在线返回 ONLINE`() {
        assertEquals("ONLINE", computeDaemonOnlineState(transportUp = true, httpUp = true))
    }

    @Test
    fun `MQTT 断但 HTTPS 骨干在返回 MQTT_DOWN_HTTP_OK`() {
        // 关键不变量：传输通道断了、但 HTTPS 拉取循环仍工作 → 设备仍在监管下，绝不报 OFFLINE
        assertEquals("MQTT_DOWN_HTTP_OK", computeDaemonOnlineState(transportUp = false, httpUp = true))
    }

    @Test
    fun `传输在线但 HTTPS 骨干断返回 HTTP_DOWN`() {
        assertEquals("HTTP_DOWN", computeDaemonOnlineState(transportUp = true, httpUp = false))
    }

    @Test
    fun `双通道皆断返回 OFFLINE`() {
        assertEquals("OFFLINE", computeDaemonOnlineState(transportUp = false, httpUp = false))
    }

    @Test
    fun `MQTT 降级态仍视为受监控不等于离线`() {
        // 守护语义：httpUp 为真即监管未中断，家长端不应误报离线
        val degraded = computeDaemonOnlineState(transportUp = false, httpUp = true)
        assertEquals("MQTT_DOWN_HTTP_OK", degraded)
        assert(degraded != "OFFLINE") { "MQTT 降级但 HTTPS 在，绝不能误判为 OFFLINE" }
    }
}
