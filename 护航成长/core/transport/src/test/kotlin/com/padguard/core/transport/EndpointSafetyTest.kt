package com.padguard.core.transport

import com.padguard.core.transport.Level
import com.padguard.core.transport.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 孩子端「明文公网传输」防线的回归测试（与 [com.padguard.parent.data.remote.EndpointSafetyTest] 对等）。
 *
 * 这些用例同时是规则的可执行说明：私网/回环 http / 加密链路是**刻意放行**的（私有化部署需要），
 * 公网明文是**必须拒绝**的。任何一条被改成放行，都等于把儿童数据与登录令牌送上网明文链路。
 *
 * 注意：本测试固化的是**运行期**规则；release 构建期守卫在 `app/build.gradle.kts` 的
 * `validateReleaseEndpoints`，两者改动必须同步。
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
        assertEquals(Level.SECURE, allowed("https://api.example.com/api/v1/").level)
    }

    @Test
    fun `https public ip is secure`() {
        assertEquals(Level.SECURE, allowed("https://8.148.68.183:8090/api/v1/").level)
    }

    @Test
    fun `https lan ip is secure`() {
        assertEquals(Level.SECURE, allowed("https://192.168.1.10:8090/api/v1/").level)
    }

    // ---------- HTTP 私网：刻意放行 ----------

    @Test
    fun `http loopback is allowed`() {
        assertEquals(Level.LAN_CLEARTEXT, allowed("http://127.0.0.1:8090/api/v1/").level)
    }

    @Test
    fun `http localhost is allowed`() {
        assertEquals(Level.LAN_CLEARTEXT, allowed("http://localhost:8090/api/v1/").level)
    }

    @Test
    fun `http private 10 and 192 ranges are allowed`() {
        assertEquals(Level.LAN_CLEARTEXT, allowed("http://10.0.0.7:8090/api/v1/").level)
        assertEquals(Level.LAN_CLEARTEXT, allowed("http://192.168.42.1:8090/api/v1/").level)
    }

    @Test
    fun `http private 172_16 to 172_31 boundaries are allowed`() {
        assertEquals(Level.LAN_CLEARTEXT, allowed("http://172.16.0.1:8090/api/v1/").level)
        assertEquals(Level.LAN_CLEARTEXT, allowed("http://172.31.255.254:8090/api/v1/").level)
    }

    @Test
    fun `http link local 169_254 is allowed for usb tethering`() {
        assertEquals(Level.LAN_CLEARTEXT, allowed("http://169.254.3.2:8090/api/v1/").level)
    }

    @Test
    fun `http ipv6 loopback and unique local are allowed`() {
        assertEquals(Level.LAN_CLEARTEXT, allowed("http://[::1]:8090/api/v1/").level)
        assertEquals(Level.LAN_CLEARTEXT, allowed("http://[fd00::1]:8090/api/v1/").level)
        assertEquals(Level.LAN_CLEARTEXT, allowed("http://[fe80::1]:8090/api/v1/").level)
    }

    @Test
    fun `http ipv4 mapped private is allowed`() {
        assertEquals(Level.LAN_CLEARTEXT, allowed("http://[::ffff:192.168.1.10]:8090/api/v1/").level)
    }

    // ---------- HTTP 公网：必须拒绝 ----------

    @Test
    fun `any public domain over http is rejected`() {
        rejected("http://api.example.com/api/v1/")
    }

    @Test
    fun `mdns style domain over http is rejected conservatively`() {
        rejected("http://padguard.local:8090/api/v1/")
    }

    @Test
    fun `private range boundaries that are actually public are rejected`() {
        rejected("http://172.15.255.255:8090/api/v1/")
        rejected("http://172.32.0.1:8090/api/v1/")
        rejected("http://192.169.0.1:8090/api/v1/")
        rejected("http://11.0.0.1:8090/api/v1/")
        rejected("http://100.64.0.1:8090/api/v1/") // CGNAT，不是局域网
        rejected("http://169.253.0.1:8090/api/v1/")
    }

    @Test
    fun `public ipv6 over http is rejected`() {
        rejected("http://[2001:4860:4860::8888]:8090/api/v1/")
    }

    // ---------- 绕过手法 ----------

    @Test
    fun `userinfo phishing url is rejected`() {
        rejected("https://safe.example.com@evil.example.com/api/v1/")
    }

    @Test
    fun `credentials embedded in url are rejected`() {
        rejected("https://admin:secret@api.example.com/api/v1/")
    }

    @Test
    fun `query and fragment are rejected`() {
        rejected("https://api.example.com/api/v1/?token=abc")
        rejected("https://api.example.com/api/v1/#frag")
    }

    @Test
    fun `non dotted decimal host cannot smuggle a public http endpoint`() {
        val v = EndpointSafety.check("http://2130706433:8090/api/v1/")
        assertTrue(v !is Verdict.Allowed || v.level != Level.EXPLICITLY_INSECURE)
    }

    @Test
    fun `blank and malformed urls are rejected`() {
        rejected("")
        rejected("   ")
        rejected("8.148.68.183:8090/api/v1")   // 缺 scheme
        rejected("ftp://api.example.com/api/v1/")
        rejected("http://")
    }

    // ---------- 显式开关与规范化 ----------

    @Test
    fun `explicit build switch allows cleartext public but marks it`() {
        assertEquals(
            Level.EXPLICITLY_INSECURE,
            allowed("http://8.148.68.183:8090/api/v1/", allow = true).level
        )
    }

    @Test
    fun `explicit switch does not bypass https or userinfo rules`() {
        assertEquals(Level.SECURE, allowed("https://api.example.com/api/v1/", allow = true).level)
        rejected("https://admin:secret@api.example.com/api/v1/", allow = true)
    }

    @Test
    fun `normalize appends trailing slash exactly once`() {
        assertEquals("http://192.168.1.10:8090/api/v1/", EndpointSafety.normalize("http://192.168.1.10:8090/api/v1"))
        assertEquals("http://192.168.1.10:8090/api/v1/", EndpointSafety.normalize("  http://192.168.1.10:8090/api/v1/  "))
        assertEquals(null, EndpointSafety.normalize("  "))
    }

    @Test
    fun `host without api prefix is auto completed to api-slash-v1`() {
        // 孩子只填 IP:端口时，旧版会把请求打到根路径 404 却报「连不上服务器」。
        assertEquals(
            "http://192.168.1.11:8090/api/v1/",
            allowed("http://192.168.1.11:8090").normalizedUrl
        )
        assertEquals(
            "https://api.example.com/api/v1/",
            allowed("https://api.example.com").normalizedUrl
        )
    }

    @Test
    fun `custom api prefix is not overwritten by auto completion`() {
        assertEquals(
            "http://192.168.1.11:8090/v1/",
            allowed("http://192.168.1.11:8090/v1/").normalizedUrl
        )
    }

    // ---------- MQTT broker URI ----------

    private fun mqttAllowed(raw: String, allow: Boolean = false): Verdict.Allowed {
        val v = EndpointSafety.checkMqtt(raw, allow)
        assertTrue("期望放行但被拒绝，原因=${(v as? Verdict.Rejected)?.reason}，输入=$raw", v is Verdict.Allowed)
        return v as Verdict.Allowed
    }

    private fun mqttRejected(raw: String, allow: Boolean = false): String {
        val v = EndpointSafety.checkMqtt(raw, allow)
        assertTrue("期望拒绝但被放行，输入=$raw", v is Verdict.Rejected)
        return (v as Verdict.Rejected).reason
    }

    @Test
    fun `ssl broker any host is secure`() {
        assertEquals(Level.SECURE, mqttAllowed("ssl://api.example.com:8883").level)
        assertEquals(Level.SECURE, mqttAllowed("ssl://8.148.68.183:8883").level)
    }

    @Test
    fun `tls and wss brokers are secure`() {
        assertEquals(Level.SECURE, mqttAllowed("tls://broker.example.com:8883").level)
        assertEquals(Level.SECURE, mqttAllowed("wss://broker.example.com:443/mqtt").level)
    }

    @Test
    fun `tcp over lan is allowed`() {
        assertEquals(Level.LAN_CLEARTEXT, mqttAllowed("tcp://192.168.1.11:1883").level)
        assertEquals(Level.LAN_CLEARTEXT, mqttAllowed("tcp://10.0.0.7:1883").level)
        assertEquals(Level.LAN_CLEARTEXT, mqttAllowed("tcp://127.0.0.1:1883").level)
    }

    @Test
    fun `tcp over public ip is rejected`() {
        mqttRejected("tcp://8.148.68.183:1883")
        mqttRejected("tcp://api.example.com:1883")
    }

    @Test
    fun `tcp over public ip allowed only with explicit switch`() {
        assertEquals(
            Level.EXPLICITLY_INSECURE,
            mqttAllowed("tcp://8.148.68.183:1883", allow = true).level
        )
    }

    @Test
    fun `ws over lan is allowed but public is rejected`() {
        assertEquals(Level.LAN_CLEARTEXT, mqttAllowed("ws://192.168.1.11:8083/mqtt").level)
        mqttRejected("ws://api.example.com:8083/mqtt")
    }

    @Test
    fun `mqtt uri without scheme is rejected`() {
        mqttRejected("192.168.1.11:1883")
    }

    @Test
    fun `mqtt uri with userinfo is rejected`() {
        mqttRejected("tcp://device:token@192.168.1.11:1883")
        mqttRejected("ssl://device:token@api.example.com:8883")
    }

    @Test
    fun `unknown mqtt scheme is rejected`() {
        mqttRejected("http://192.168.1.11:1883")
    }

    // ---------- isLanHost 单元 ----------

    @Test
    fun `isLanHost boundaries`() {
        assertTrue(EndpointSafety.isLanHost("127.0.0.1"))
        assertTrue(EndpointSafety.isLanHost("localhost"))
        assertTrue(EndpointSafety.isLanHost("10.255.255.255"))
        assertTrue(EndpointSafety.isLanHost("172.16.0.1"))
        assertTrue(EndpointSafety.isLanHost("172.31.255.254"))
        assertTrue(EndpointSafety.isLanHost("192.168.0.1"))
        assertTrue(EndpointSafety.isLanHost("169.254.1.1"))
        assertFalse(EndpointSafety.isLanHost("8.8.8.8"))
        assertFalse(EndpointSafety.isLanHost("172.15.0.1"))
        assertFalse(EndpointSafety.isLanHost("172.32.0.1"))
        assertFalse(EndpointSafety.isLanHost("192.169.0.1"))
        assertFalse(EndpointSafety.isLanHost("api.example.com"))
        assertFalse(EndpointSafety.isLanHost("100.64.0.1"))
        assertFalse(EndpointSafety.isLanHost(""))
    }
}
