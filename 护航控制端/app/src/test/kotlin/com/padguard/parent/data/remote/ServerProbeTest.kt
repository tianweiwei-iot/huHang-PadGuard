package com.padguard.parent.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * 「测试连接」的纯逻辑覆盖。
 *
 * 只测解析与文案映射，不发起真实网络请求 —— 单测跑在 JVM 上，不能依赖本机是否
 * 起了服务端。真实探测留给手测（登录页点一下即可）。
 */
class ServerProbeTest {

    // ---------- originOf ----------

    @Test
    fun `接口前缀被剥掉，健康接口挂在根路径`() {
        assertEquals(
            "http://192.168.1.11:8090",
            ServerProbe.originOf("http://192.168.1.11:8090/v1/")
        )
        // 历史写法：不带结尾斜杠、或前缀写成 /api/v1，也必须能剥干净
        assertEquals("http://192.168.1.11:8090", ServerProbe.originOf("http://192.168.1.11:8090"))
        assertEquals(
            "http://10.0.2.2:8090",
            ServerProbe.originOf("http://10.0.2.2:8090/api/v1/")
        )
    }

    @Test
    fun `https 与不带端口的地址都能正确还原`() {
        assertEquals(
            "https://padguard.example.com",
            ServerProbe.originOf("https://padguard.example.com/v1/")
        )
        assertEquals("https://padguard.example.com:8443", ServerProbe.originOf("https://padguard.example.com:8443/v1/"))
    }

    @Test
    fun `非法地址返回 null 而不是瞎猜一个 endpoint`() {
        assertNull("缺协议头必须拒绝", ServerProbe.originOf("192.168.1.11:8090/v1/"))
        assertNull("空 host 必须拒绝", ServerProbe.originOf("http:///v1/"))
        assertNull("空白必须拒绝", ServerProbe.originOf("   "))
        assertNull("端口非数字必须拒绝", ServerProbe.originOf("http://192.168.1.11:abc/v1/"))
        assertNull("端口越界必须拒绝", ServerProbe.originOf("http://192.168.1.11:70000/v1/"))
    }

    @Test
    fun `地址里带账号密码时只保留 host`() {
        assertEquals("http://192.168.1.11:8090", ServerProbe.originOf("http://u:p@192.168.1.11:8090/v1/"))
    }

    // ---------- describeHttp ----------

    @Test
    fun `健康接口 UP 判定为连接正常`() {
        val r = ServerProbe.describeHttp(200, """{"status":"UP","db":"UP"}""", "http://a:8090")
        assertTrue(r.ok)
        assertTrue("要明确告诉用户可以保存", r.message.contains("可以保存"))
    }

    @Test
    fun `服务端在但数据库 DOWN 必须单独提示`() {
        val r = ServerProbe.describeHttp(200, """{"status":"UP","db":"DOWN"}""", "http://a:8090")
        // 服务端确实连上了，所以 ok=true；但必须点明"登录会失败"，否则用户以为万事大吉
        assertTrue(r.ok)
        assertTrue(r.message.contains("数据库"))
    }

    @Test
    fun `200 但不是健康接口说明端口指向了别的系统`() {
        val r = ServerProbe.describeHttp(200, """{"hello":"world"}""", "http://a:8090")
        assertFalse("别的系统不能被当成护航服务端", r.ok)
        assertTrue(r.message.contains("端口"))
    }

    @Test
    fun `美化打印的健康响应同样判定为正常`() {
        // 前置代理/调试网关可能把 JSON 美化输出，不能因此把健康响应误判成"不是护航服务端"
        val pretty = """
            {
              "status": "UP",
              "ready": true,
              "db": "UP"
            }
        """.trimIndent()
        assertTrue(ServerProbe.describeHttp(200, pretty, "http://a:8090").ok)
    }

    @Test
    fun `404 要指向端口或前缀写错，而不是笼统的连接失败`() {
        val r = ServerProbe.describeHttp(404, "", "http://a:8090")
        assertFalse(r.ok)
        assertTrue(r.message.contains("404"))
        assertTrue(r.message.contains("端口"))
    }

    @Test
    fun `5xx 要说明不是地址问题`() {
        val r = ServerProbe.describeHttp(503, "", "http://a:8090")
        assertFalse(r.ok)
        assertTrue("必须排除地址因素，避免用户反复改地址", r.message.contains("不是地址问题"))
    }

    // ---------- describeFailure ----------

    @Test
    fun `连接被拒绝要提示端口默认是 8090`() {
        val r = ServerProbe.describeFailure(ConnectException("Connection refused"), "http://a:8090")
        assertFalse(r.ok)
        assertTrue(r.message.contains("连接被拒绝"))
        assertTrue("本工程默认 8090，写成 8080 是最常见错误", r.message.contains("8090"))
    }

    @Test
    fun `未知主机要提示改填 IP`() {
        val r = ServerProbe.describeFailure(UnknownHostException("Unable to resolve host"), "http://padguard:8090")
        assertFalse(r.ok)
        assertTrue(r.message.contains("IP"))
    }

    @Test
    fun `超时要点明同一 WiFi 与防火墙`() {
        val r = ServerProbe.describeFailure(SocketTimeoutException("timeout"), "http://a:8090")
        assertFalse(r.ok)
        assertTrue(r.message.contains("WiFi"))
    }

    @Test
    fun `TLS 握手失败要提示内网服务端只开 HTTP`() {
        val r = ServerProbe.describeFailure(SSLHandshakeException("handshake failed"), "https://a:8090")
        assertFalse(r.ok)
        assertTrue(r.message.contains("HTTP"))
    }

    @Test
    fun `明文流量被系统拦截时给出专门文案`() {
        val r = ServerProbe.describeFailure(
            java.io.IOException("Cleartext HTTP traffic to 1.2.3.4 not permitted"),
            "http://1.2.3.4:8090"
        )
        assertFalse(r.ok)
        assertTrue(r.message.contains("明文"))
    }

    // ---------- looksLikeConnectionFailure ----------

    @Test
    fun `连接类失败会被识别出来，用于自动展开地址表单`() {
        val samples = listOf(
            ServerProbe.describeFailure(ConnectException("refused"), "http://a:8090").message,
            ServerProbe.describeFailure(UnknownHostException("x"), "http://a:8090").message,
            ServerProbe.describeFailure(SocketTimeoutException("t"), "http://a:8090").message,
            // 明文被系统拦截时也必须展开表单：解法就在表单里（把协议改成 HTTP/HTTPS）
            ServerProbe.describeFailure(
                java.io.IOException("Cleartext HTTP traffic not permitted"),
                "http://a:8090"
            ).message,
            "连不上服务器 http://192.168.1.10:8090/v1/：该地址上没有服务在监听"
        )
        samples.forEach { assertTrue("未识别：$it", ServerProbe.looksLikeConnectionFailure(it)) }
    }

    @Test
    fun `账号类失败不能被误判成连接失败`() {
        listOf("密码错误", "用户不存在", "账号已被冻结，无法登录", "验证码错误或已过期", null, "").forEach {
            assertFalse("误判：$it", ServerProbe.looksLikeConnectionFailure(it))
        }
    }
}
