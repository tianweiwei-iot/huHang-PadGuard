package com.padguard.parent.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 登录/认证失败提示的回归测试：家长看到的必须是「中文 + 具体原因」。
 *
 * 服务端 AuthService 早已返回中文具体原因，历史缺陷在客户端：把 OkHttp 抛出的英文
 * IOException、"HTTP 401: [1002] " 前缀、以及整段错误 JSON 原样显示。
 * 这里把「不能出现英文提示」钉成可执行规则。
 */
class LoginErrorMessageTest {

    private fun assertChineseMessage(msg: String) {
        assertTrue("提示必须是中文：[$msg]", msg.any { it.code in 0x4E00..0x9FFF })
        listOf("HTTP ", "Failed", "timeout", "Unable", "[1002]", "success").forEach {
            assertFalse("提示里不应残留「$it」：[$msg]", msg.contains(it))
        }
    }

    @Test
    fun `服务端返回的中文具体原因原样透传`() {
        listOf(
            "用户不存在",
            "密码错误",
            "账号已被冻结，无法登录。如需恢复请联系客服。",
            "账号尚未激活，请等待开通后再登录。",
            "密码连续错误次数过多，账号已临时锁定，请约 12 分钟后再试",
            "请求过于频繁，请一分钟后再试"
        ).forEach { reason ->
            val msg = readableUserFacingError(Exception(reason), "登录失败，请稍后重试")
            assertEquals(reason, msg)
            assertChineseMessage(msg)
        }
    }

    @Test
    fun `剥掉历史的英文与业务码前缀`() {
        assertEquals(
            "密码错误",
            readableUserFacingError(Exception("HTTP 401: [1002] 密码错误"), "登录失败")
        )
        assertEquals(
            "用户不存在",
            readableUserFacingError(Exception("[1002] 用户不存在"), "登录失败")
        )
    }

    @Test
    fun `业务异常按码给中文兜底`() {
        assertEquals(
            "密码错误",
            readableUserFacingError(ParentBizException(1002, "密码错误"), "登录失败")
        )
        assertEquals(
            "账号或密码错误，请检查后重试",
            readableUserFacingError(ParentBizException(1002, ""), "登录失败")
        )
        assertEquals(
            "账号已被冻结，无法登录，如需恢复请联系客服",
            readableUserFacingError(ParentBizException(1004, ""), "登录失败")
        )
        assertEquals(
            "操作过于频繁，请稍后再试",
            readableUserFacingError(ParentBizException(1006, ""), "登录失败")
        )
        assertEquals(
            "订阅已到期，请到「我的-订阅」页续费后再试",
            readableUserFacingError(ParentBizException(4001, ""), "登录失败")
        )
    }

    @Test
    fun `连接失败翻译为连不上服务器`() {
        val msg = readableUserFacingError(
            IOException("Failed to connect to /192.168.1.12:8090"),
            "登录失败，请稍后重试"
        )
        assertTrue(msg, msg.contains("连不上服务器"))
        assertChineseMessage(msg)
    }

    @Test
    fun `域名无法解析给出具体原因`() {
        val msg = readableUserFacingError(UnknownHostException("Unable to resolve host"), "登录失败")
        assertTrue(msg, msg.contains("无法解析服务器地址"))
        assertChineseMessage(msg)
    }

    @Test
    fun `连接超时给出具体原因`() {
        val msg = readableUserFacingError(SocketTimeoutException("timeout"), "登录失败")
        assertTrue(msg, msg.contains("连接超时"))
        assertChineseMessage(msg)
    }

    @Test
    fun `英文超时异常也能翻译`() {
        val msg = readableUserFacingError(Exception("timeout"), "登录失败")
        assertTrue(msg, msg.contains("连接超时"))
        assertChineseMessage(msg)
    }

    @Test
    fun `地址安全校验的中文原因要保留`() {
        val reason = "服务器地址不安全：公网明文地址已被拒绝"
        assertEquals(reason, readableUserFacingError(IOException(reason), "登录失败"))
    }

    @Test
    fun `未知英文异常回落中文不泄漏原文`() {
        val msg = readableUserFacingError(Exception("Something went wrong"), "登录失败，请稍后重试")
        assertEquals("登录失败，请稍后重试", msg)
        assertChineseMessage(msg)
    }

    @Test
    fun `空异常回落调用方中文文案`() {
        assertEquals("注册失败，请稍后重试", readableUserFacingError(null, "注册失败，请稍后重试"))
    }

    @Test
    fun `网络异常翻译不破坏业务异常类型`() {
        val biz = ParentBizException(4001, "订阅已到期")
        assertTrue(translateNetworkFailure(biz) === biz)
        val translated = translateNetworkFailure(IOException("Failed to connect to /127.0.0.1:8090"))
        assertTrue(translated.message!!.contains("连不上服务器"))
    }

    // 「注册/登录连不上」时最有用的信息是**当前连的是哪台机器**。
    // 设备可能仍存着旧公网地址、debug 示例 IP 或模拟器回环，只说「请检查网络」无法定位。
    private fun target(url: String) = url.toHttpUrlOrNull()!!

    @Test
    fun `连接被拒绝时提示带上真实地址与端口`() {
        val msg = connectionFailureMessage(
            target("http://192.168.1.10:8090/v1/"),
            ConnectException("Failed to connect to /192.168.1.10:8090")
        )
        assertTrue(msg, msg.contains("http://192.168.1.10:8090/v1/"))
        assertTrue(msg, msg.contains("连接被拒绝"))
        assertTrue(msg, msg.contains("高级设置"))
        assertChineseMessage(msg)
    }

    @Test
    fun `旧地址被升级成 https 时提示改回内网 http`() {
        val msg = connectionFailureMessage(
            target("https://8.148.68.183:8090/v1/"),
            javax.net.ssl.SSLHandshakeException("SSL handshake aborted")
        )
        assertTrue(msg, msg.contains("HTTPS/TLS 握手失败"))
        assertTrue(msg, msg.contains("改回 http://"))
        assertChineseMessage(msg)
    }

    @Test
    fun `域名解析失败与超时给出不同原因且都带地址`() {
        val bad = target("http://padguard.local:8090/v1/")
        val dnsMsg = connectionFailureMessage(bad, UnknownHostException("Unable to resolve host"))
        assertTrue(dnsMsg, dnsMsg.contains("无法解析"))
        val msg = connectionFailureMessage(bad, SocketTimeoutException("timeout"))
        assertTrue(msg, msg.contains("连接超时"))
        assertTrue(msg, msg.contains("padguard.local:8090"))
        assertChineseMessage(msg)
    }

    @Test
    fun `拦截器产出的中文提示经过统一出口后不被二次改写`() {
        val msg = connectionFailureMessage(
            target("http://192.168.1.12:8090/v1/"),
            ConnectException("Failed to connect")
        )
        assertEquals(msg, readableUserFacingError(IOException(msg), "注册失败，请稍后重试"))
        assertChineseMessage(msg)
    }
}
