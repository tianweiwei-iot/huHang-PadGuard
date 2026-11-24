package com.padguard.parent.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ParentPushClient` 的 WS 地址构建。
 *
 * 回归背景（2026-11-07 事故）：旧实现用 `HttpUrl.Builder.scheme("ws")` 把 http 地址
 * 改成 WebSocket 地址，OkHttp 只接受 http/https，直接抛
 * `IllegalArgumentException: unexpected scheme: ws`；异常发生在 IO 协程里无人捕获，
 * 进程被杀 —— 家长端「登录即闪退、重启自动登录又闪退」，无法自救。
 *
 * 这些用例锁住三件事：
 * 1. 返回的是合法 ws/wss 字符串，**不再经过 HttpUrl.Builder.scheme("ws")**；
 * 2. REST 前缀（/v1/、/api/v1/）不会带进 /ws；
 * 3. 任何非法/不安全输入返回 null，绝不抛异常（抛异常 = 可能杀进程）。
 */
class WsUrlTest {

    @Test
    fun `内网 http 地址转成 ws 且丢掉 REST 前缀`() {
        assertEquals(
            "ws://192.168.1.10:8090/ws",
            wsUrlOf("http://192.168.1.10:8090/v1/", allowCleartextPublic = false)
        )
        assertEquals(
            "ws://192.168.1.10:8090/ws",
            wsUrlOf("http://192.168.1.10:8090/api/v1/", allowCleartextPublic = false)
        )
    }

    @Test
    fun `https 地址转成 wss 且不写默认端口`() {
        assertEquals(
            "wss://pad.example.com/ws",
            wsUrlOf("https://pad.example.com/v1/", allowCleartextPublic = false)
        )
    }

    @Test
    fun `非默认端口保留在 wss 地址里`() {
        assertEquals(
            "wss://pad.example.com:8443/ws",
            wsUrlOf("https://pad.example.com:8443/v1/", allowCleartextPublic = false)
        )
    }

    @Test
    fun `大写 scheme 也能正确转换`() {
        assertEquals(
            "ws://10.0.0.5:8090/ws",
            wsUrlOf("HTTP://10.0.0.5:8090/v1/", allowCleartextPublic = false)
        )
    }

    @Test
    fun `公网明文 http 被拒绝而不是降级连接`() {
        assertNull(wsUrlOf("http://8.148.68.183:8090/v1/", allowCleartextPublic = false))
    }

    @Test
    fun `显式开关放行公网明文时才返回 ws`() {
        assertEquals(
            "ws://8.148.68.183:8090/ws",
            wsUrlOf("http://8.148.68.183:8090/v1/", allowCleartextPublic = true)
        )
    }

    @Test
    fun `非法输入一律返回 null 而不抛异常`() {
        // 这几种输入在旧实现里会走到 HttpUrl.Builder 并抛 IllegalArgumentException
        assertNull(wsUrlOf("", allowCleartextPublic = false))
        assertNull(wsUrlOf("   ", allowCleartextPublic = false))
        assertNull(wsUrlOf("192.168.1.10:8090", allowCleartextPublic = false))
        assertNull(wsUrlOf("ftp://192.168.1.10/", allowCleartextPublic = false))
        assertNull(wsUrlOf("http://", allowCleartextPublic = false))
    }

    @Test
    fun `结果里不含 REST 前缀也不残留 http scheme`() {
        val url = wsUrlOf("http://127.0.0.1:8090/v1/", allowCleartextPublic = false)!!
        assertTrue(url.startsWith("ws://"))
        assertFalse(url.contains("/v1/"))
        assertFalse(url.startsWith("http"))
    }

    @Test
    fun `IPv6 回环地址保留方括号`() {
        val url = wsUrlOf("http://[::1]:8090/v1/", allowCleartextPublic = false)
        assertEquals("ws://[::1]:8090/ws", url)
    }
}
