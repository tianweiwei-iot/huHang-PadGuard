package com.padguard.parent.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 操作类失败提示（下发指令 / 读列表 / 设置策略）的回归测试。
 *
 * 历史缺陷：这些位置统一写 `"设置失败：${e.message}"`，把 OkHttp 的英文原文
 * 原样推到家长面前；`message` 为 null 时更会显示成"设置失败：null"。
 * 这里把"中文 + 具体原因、且 null 不裸奔"钉成可执行规则。
 */
class ActionErrorMessageTest {

    private val fallback = "请稍后重试"

    private fun assertReadable(msg: String) {
        assertTrue("提示必须是中文：[$msg]", msg.any { it.code in 0x4E00..0x9FFF })
        listOf("null", "Failed", "timeout", "Unable", "Exception", "HTTP ").forEach {
            assertFalse("提示里不应残留「$it」：[$msg]", msg.contains(it))
        }
    }

    @Test
    fun `服务端中文业务原因原样透传`() {
        listOf(
            "设备不存在或已解绑",
            "订阅已到期，请续费后再试",
            "该设备未绑定到当前账号"
        ).forEach { reason ->
            val msg = Exception(reason).readableActionMessage(fallback)
            assertEquals(reason, msg)
            assertReadable(msg)
        }
    }

    @Test
    fun `连接被拒绝翻译成连不上服务器`() {
        val msg = ConnectException("Failed to connect to /192.168.1.12:8090")
            .readableActionMessage(fallback)
        assertReadable(msg)
        assertTrue("应指明连不上服务器：[$msg]", msg.contains("连不上服务器"))
    }

    @Test
    fun `超时翻译成连接超时`() {
        val msg = SocketTimeoutException("timeout").readableActionMessage(fallback)
        assertReadable(msg)
        assertTrue("应指明超时：[$msg]", msg.contains("超时"))
    }

    @Test
    fun `域名解析失败给出改地址的指引`() {
        val msg = UnknownHostException("Unable to resolve host \"padguard.local\": No address associated")
            .readableActionMessage(fallback)
        assertReadable(msg)
        assertTrue("应提示检查服务器地址：[$msg]", msg.contains("地址"))
    }

    @Test
    fun `非 IO 的英文异常回落中文兜底而不是冒充网络错误`() {
        // 这条守住一个反向风险：把任何英文 message 都翻译成"连不上服务器"，
        // 等于用一个假原因覆盖真原因，家长会去查网络而问题其实在别处。
        val msg = IllegalStateException("index out of bounds").readableActionMessage(fallback)
        assertEquals(fallback, msg)
    }

    @Test
    fun `message 为 null 时不再显示 null`() {
        val bare = object : RuntimeException() {}
        assertEquals(fallback, bare.readableActionMessage(fallback))
    }

    @Test
    fun `订阅过期引导续费`() {
        val msg = ParentBizException(ParentBizException.CODE_SUBSCRIPTION_REQUIRED, "")
            .readableActionMessage(fallback)
        assertTrue("应引导续费：[$msg]", msg.contains("续费"))
    }

    @Test
    fun `令牌失效不说成密码错误`() {
        // 拿着有效令牌调接口时收到 1002，只可能是令牌过期/被吊销。
        // 说"账号或密码错误"会让家长反复去改一个本来正确的密码。
        val msg = ParentBizException(1002, "").readableActionMessage(fallback)
        assertReadable(msg)
        assertTrue("应提示重新登录：[$msg]", msg.contains("重新登录"))
        assertFalse("不应说成密码错误：[$msg]", msg.contains("密码"))
    }

    @Test
    fun `登录场景的1002仍是账号或密码错误`() {
        val msg = readableUserFacingError(ParentBizException(1002, ""), "登录失败")
        assertTrue("登录场景应说账号或密码错误：[$msg]", msg.contains("密码"))
    }

    @Test
    fun `未知业务码回落中文兜底`() {
        assertEquals(fallback, ParentBizException(9999, "").readableActionMessage(fallback))
    }

    @Test
    fun `历史英文与业务码前缀被剥掉`() {
        val msg = ParentBizException(2001, "HTTP 200: [2001] 设备不存在")
            .readableActionMessage(fallback)
        assertEquals("设备不存在", msg)
    }

    @Test
    fun `IO 异常不会被当成业务异常丢弃`() {
        val e: Throwable = IOException("socket closed")
        val msg = e.readableActionMessage(fallback)
        assertReadable(msg)
    }
}
