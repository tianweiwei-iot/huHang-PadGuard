package com.padguard.server.security

import com.padguard.server.common.BizException
import com.padguard.server.common.ClientIps
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

/**
 * 限流闸门回归（第八轮评估第四批测试补齐）。
 *
 * RateLimit 是四个匿名可爆破入口的唯一频控防线，此前的回归全靠手工点。
 * 这里固化其核心不变量：
 * 1. 同维度计数到阈值即 429（BizException），第 N 次之前全部放行；
 * 2. 场景隔离：login 打满不影响 bind（各自独立计数）；
 * 3. IP 与账号双维度独立：账号维度打满只拦同账号，换账号仍受 IP 维度约束；
 * 4. 默认不采信 X-Forwarded-For（客户端可伪造，采信等于给攻击者无限个计数桶）；
 * 5. 未知场景回退保守默认 10 次（漏配置不等于无限制）。
 */
class RateLimitTest {

    private val props = RateLimitProperties()
    private val rateLimit = RateLimit(props)

    private fun request(ip: String, xff: String? = null): HttpServletRequest =
        MockHttpServletRequest().apply {
            remoteAddr = ip
            if (xff != null) addHeader("X-Forwarded-For", xff)
        }

    @Test
    fun `allows up to limit then rejects`() {
        val req = request("10.0.0.1")
        repeat(props.login) { assertDoesNotThrow { rateLimit.require("login", req, "13800000001") } }
        assertThrows(BizException::class.java) { rateLimit.require("login", req, "13800000001") }
    }

    @Test
    fun `scenarios count independently`() {
        val req = request("10.0.1.1")
        repeat(props.login) { assertDoesNotThrow { rateLimit.require("login", req) } }
        assertThrows(BizException::class.java) { rateLimit.require("login", req) }
        // login 场景已满，bind 场景必须仍是全新计数
        assertDoesNotThrow { rateLimit.require("bind", req) }
        assertDoesNotThrow { rateLimit.require("admin_login", req) }
    }

    @Test
    fun `account dimension does not block other accounts but ip dimension does`() {
        val req = request("10.0.2.1")
        // 账号 A 打满（IP + 账号两个维度都各加满一次）
        repeat(props.bindByAccount) { assertDoesNotThrow { rateLimit.require("bind_by_account", req, "acc-A") } }
        // 同 IP 换账号 B：账号维度是新桶，但 IP 维度已满 → 仍被拦（这正是双维度的意义）
        assertThrows(BizException::class.java) { rateLimit.require("bind_by_account", req, "acc-B") }
        // 换 IP 同账号：账号维度已满 → 仍被拦（防"换 IP 绕过"）
        val req2 = request("10.0.2.2")
        assertThrows(BizException::class.java) { rateLimit.require("bind_by_account", req2, "acc-A") }
        // 换 IP 换账号：两个维度都是新桶 → 放行
        assertDoesNotThrow { rateLimit.require("bind_by_account", req2, "acc-B") }
    }

    @Test
    fun `forwarded-for header is not trusted by default`() {
        // 同一 TCP 对端，攻击者每请求换一个 XFF 值：计数必须仍落在 remoteAddr 桶上
        repeat(props.login) {
            assertDoesNotThrow { rateLimit.require("login", request("10.0.3.1", "1.2.3.$it")) }
        }
        assertThrows(BizException::class.java) { rateLimit.require("login", request("10.0.3.1", "5.6.7.8")) }
    }

    @Test
    fun `forwarded-for is honored when explicitly trusted`() {
        val trusted = RateLimit(RateLimitProperties().apply { trustForwardedFor = true })
        // 同一对端携带不同 XFF：各自独立计数（受信代理已重写/追加了真实 IP）
        repeat(3) { assertDoesNotThrow { trusted.require("login", request("10.0.4.1", "1.2.3.$it")) } }
        // 同一 XFF 打到阈值 → 拦
        repeat(props.login) { assertDoesNotThrow { trusted.require("login", request("10.0.4.9", "9.9.9.9")) } }
        val ex = assertThrows(BizException::class.java) { trusted.require("login", request("10.0.4.9", "9.9.9.9")) }
        assertTrue(ex.message!!.contains("频繁"))
    }

    @Test
    fun `unknown scenario falls back to conservative default`() {
        val req = request("10.0.5.1")
        repeat(RateLimitCompanion.defaultLimit()) { assertDoesNotThrow { rateLimit.require("no_such_scene", req) } }
        assertThrows(BizException::class.java) { rateLimit.require("no_such_scene", req) }
    }
}

/** 访问 RateLimit 的 DEFAULT_LIMIT 私有常量（避免测试里硬编码数字漂移） */
private object RateLimitCompanion {
    fun defaultLimit(): Int =
        RateLimit::class.java.declaredFields.first { it.name == "DEFAULT_LIMIT" }
            .apply { isAccessible = true }.getInt(null)
}
