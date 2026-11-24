package com.padguard.parent.data.remote

import com.padguard.parent.data.remote.EndpointSafety.Level
import com.padguard.parent.data.remote.EndpointSafety.Verdict
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

/**
 * P0「明文公网 HTTP」防线的回归测试。
 *
 * 这些用例同时是规则的可执行说明：私网/回环 http 是**刻意放行**的（私有化部署需要），
 * 公网 http 是**必须拒绝**的。任何一条被改成放行，都等于把儿童数据与登录令牌送上网明文链路。
 */
class EndpointSafetyTest {

    private fun allowed(raw: String, allow: Boolean = false): Verdict.Allowed {
        val v = EndpointSafety.check(raw, allow)
        assertTrue("期望放行但被拒绝，原因=${(v as? Verdict.Rejected)?.reason}，输入=$raw", v is Verdict.Allowed)
        return v as Verdict.Allowed
    }

    private fun rejected(raw: String, allow: Boolean = false): String {
        val v = EndpointSafety.check(raw, allow)
        assertTrue("期望拒绝但被放行，输入=$raw", v is Verdict.Rejected)
        return (v as Verdict.Rejected).reason
    }

    // ---------- HTTPS：任何 host 都放行 ----------

    @Test
    fun `https public domain is secure`() {
        assertEquals(Level.SECURE, allowed("https://api.example.com/v1/").level)
    }

    @Test
    fun `https public ip is secure`() {
        assertEquals(Level.SECURE, allowed("https://8.148.68.183:8090/v1/").level)
    }

    @Test
    fun `https lan ip is secure`() {
        assertEquals(Level.SECURE, allowed("https://192.168.1.10:8090/v1/").level)
    }

    // ---------- HTTP 私网：刻意放行 ----------

    @Test
    fun `http loopback is allowed`() {
        assertEquals(Level.LAN_CLEARTEXT, allowed("http://127.0.0.1:8090/v1/").level)
    }

    @Test
    fun `http localhost is allowed`() {
        assertEquals(Level.LAN_CLEARTEXT, allowed("http://localhost:8090/v1/").level)
    }

    @Test
    fun `http private 10 and 192 ranges are allowed`() {
        assertEquals(Level.LAN_CLEARTEXT, allowed("http://10.0.0.7:8090/v1/").level)
        assertEquals(Level.LAN_CLEARTEXT, allowed("http://192.168.42.1:8090/v1/").level)
    }

    @Test
    fun `http private 172_16 to 172_31 boundaries are allowed`() {
        assertEquals(Level.LAN_CLEARTEXT, allowed("http://172.16.0.1:8090/v1/").level)
        assertEquals(Level.LAN_CLEARTEXT, allowed("http://172.31.255.254:8090/v1/").level)
    }

    @Test
    fun `http link local 169_254 is allowed for usb tethering`() {
        assertEquals(Level.LAN_CLEARTEXT, allowed("http://169.254.3.2:8090/v1/").level)
    }

    @Test
    fun `http ipv6 loopback and unique local are allowed`() {
        assertEquals(Level.LAN_CLEARTEXT, allowed("http://[::1]:8090/v1/").level)
        assertEquals(Level.LAN_CLEARTEXT, allowed("http://[fd00::1]:8090/v1/").level)
        assertEquals(Level.LAN_CLEARTEXT, allowed("http://[fe80::1]:8090/v1/").level)
    }

    @Test
    fun `http ipv4 mapped private is allowed`() {
        assertEquals(Level.LAN_CLEARTEXT, allowed("http://[::ffff:192.168.1.10]:8090/v1/").level)
    }

    // ---------- HTTP 公网：必须拒绝 ----------

    @Test
    fun `the historical hardcoded public ip over http is rejected`() {
        val reason = rejected("http://8.148.68.183:8090/v1/")
        assertTrue(reason.contains("HTTPS"))
    }

    @Test
    fun `any public domain over http is rejected`() {
        rejected("http://api.example.com/v1/")
    }

    @Test
    fun `mdns style domain over http is rejected conservatively`() {
        rejected("http://padguard.local:8090/v1/")
    }

    @Test
    fun `private range boundaries that are actually public are rejected`() {
        rejected("http://172.15.255.255:8090/v1/")
        rejected("http://172.32.0.1:8090/v1/")
        rejected("http://192.169.0.1:8090/v1/")
        rejected("http://11.0.0.1:8090/v1/")
        rejected("http://100.64.0.1:8090/v1/") // CGNAT，不是局域网
        rejected("http://169.253.0.1:8090/v1/")
    }

    @Test
    fun `public ipv6 over http is rejected`() {
        rejected("http://[2001:4860:4860::8888]:8090/v1/")
    }

    // ---------- 绕过手法 ----------

    @Test
    fun `userinfo phishing url is rejected`() {
        rejected("https://safe.example.com@evil.example.com/v1/")
    }

    @Test
    fun `credentials embedded in url are rejected`() {
        rejected("https://admin:secret@api.example.com/v1/")
    }

    @Test
    fun `query and fragment are rejected`() {
        rejected("https://api.example.com/v1/?token=abc")
        rejected("https://api.example.com/v1/#frag")
    }

    @Test
    fun `non dotted decimal host cannot smuggle a public http endpoint`() {
        // 无论 OkHttp 是否把整数 host 规范化成 127.0.0.1，都不允许它变成「公网明文放行」。
        val v = EndpointSafety.check("http://2130706433:8090/v1/")
        assertTrue(v !is Verdict.Allowed || v.level != Level.EXPLICITLY_INSECURE)
    }

    @Test
    fun `blank and malformed urls are rejected`() {
        rejected("")
        rejected("   ")
        rejected("8.148.68.183:8090/v1")   // 缺 scheme
        rejected("ftp://api.example.com/v1/")
        rejected("http://")
    }

    // ---------- 显式开关与规范化 ----------

    @Test
    fun `explicit build switch allows cleartext public but marks it`() {
        assertEquals(
            Level.EXPLICITLY_INSECURE,
            allowed("http://8.148.68.183:8090/v1/", allow = true).level
        )
    }

    @Test
    fun `explicit switch does not bypass https or userinfo rules`() {
        assertEquals(Level.SECURE, allowed("https://api.example.com/v1/", allow = true).level)
        rejected("https://admin:secret@api.example.com/v1/", allow = true)
    }

    @Test
    fun `normalize appends trailing slash exactly once`() {
        assertEquals("http://192.168.1.10:8090/v1/", EndpointSafety.normalize("http://192.168.1.10:8090/v1"))
        assertEquals("http://192.168.1.10:8090/v1/", EndpointSafety.normalize("  http://192.168.1.10:8090/v1/  "))
        assertEquals(null, EndpointSafety.normalize("  "))
        assertEquals(
            "http://192.168.1.10:8090/v1/",
            allowed("http://192.168.1.10:8090/v1").normalizedUrl
        )
    }

    @Test
    fun `port and api prefix survive normalization`() {
        assertEquals(
            "https://api.example.com/api/v1/",
            allowed("https://api.example.com/api/v1").normalizedUrl
        )
    }

    // ---------- 2026-11-07「连不上服务器」：地址自愈 ----------

    @Test
    fun `host without api prefix is auto completed to v1`() {
        // 家长只填 IP:端口时，旧版会把请求打到 /devices（404）却报「连不上服务器」。
        assertEquals(
            "http://192.168.1.11:8090/v1/",
            allowed("http://192.168.1.11:8090").normalizedUrl
        )
        assertEquals(
            "https://api.example.com/v1/",
            allowed("https://api.example.com").normalizedUrl
        )
    }

    @Test
    fun `custom api prefix is not overwritten by auto completion`() {
        assertEquals(
            "http://192.168.1.11:8090/api/v1/",
            allowed("http://192.168.1.11:8090/api/v1/").normalizedUrl
        )
    }

    @Test
    fun `lan candidates are bounded private neighbours plus emulator loopback`() {
        val candidates = lanCandidates("http://192.168.1.10:8090/v1/".toHttpUrl())
        val hosts = candidates.map { it.host }
        assertTrue(hosts.contains("192.168.1.11"))
        assertTrue(hosts.contains("192.168.1.12"))
        assertTrue(hosts.contains("10.0.2.2"))
        assertFalse(hosts.contains("192.168.1.10")) // 已失败的地址不重复探测
        assertTrue(candidates.size <= 10)
        assertTrue(candidates.all { it.encodedPath == "/v1/" })
        assertTrue(candidates.all { it.port == 8090 })
    }

    @Test
    fun `public host does not generate a whole subnet of probes`() {
        val hosts = lanCandidates("http://8.148.68.183:8090/v1/".toHttpUrl()).map { it.host }
        assertEquals(setOf("10.0.2.2", "127.0.0.1"), hosts.toSet())
    }

    @Test
    fun `lan candidates keep custom api prefix instead of hardcoding v1`() {
        // 私有化部署可能挂 /api/v1/；自愈写回时若硬编码 /v1/，换 IP 成功后全线 404。
        val candidates = lanCandidates("http://192.168.1.10:8090/api/v1/".toHttpUrl())
        assertTrue(candidates.isNotEmpty())
        assertTrue(candidates.all { it.encodedPath == "/api/v1/" })
        assertTrue(candidates.all { it.port == 8090 })
    }

    @Test
    fun `health probe only accepts PadGuard health payload`() {
        assertTrue(
            isPadGuardHealth(
                """{"status":"UP","ready":true,"db":"UP","uptimeMs":5677416,"startedAt":"2026-11-07 17:50:00"}"""
            )
        )
        // Jackson 输出可能带空白，去空白后仍应识别
        assertTrue(isPadGuardHealth("""{ "status": "UP", "ready" : true, "db" : "UP" }"""))
        assertFalse(isPadGuardHealth("OK"))
        assertFalse(isPadGuardHealth("<html><body>router admin</body></html>"))
        assertFalse(isPadGuardHealth("""{"status":"UP"}"""))          // 缺 ready / db
        assertFalse(isPadGuardHealth("""{"status":"DOWN","ready":false,"db":"DOWN"}"""))
        assertFalse(isPadGuardHealth(""))
    }

    @Test
    fun `only connection level failures trigger address recovery`() {
        assertTrue(isConnectionLevelFailure(ConnectException("Connection refused")))
        assertTrue(isConnectionLevelFailure(SocketTimeoutException("timeout")))
        assertTrue(isConnectionLevelFailure(IOException("Failed to connect to /192.168.1.10")))
        // TLS 失败的正解是改回 http，换 IP 只会掩盖真实原因
        assertFalse(isConnectionLevelFailure(SSLException("SSL handshake failed")))
        assertFalse(isConnectionLevelFailure(IOException("服务器地址不安全：公网地址必须使用 HTTPS")))
    }
}
