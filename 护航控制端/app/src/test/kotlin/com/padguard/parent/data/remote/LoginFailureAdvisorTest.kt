package com.padguard.parent.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException

/**
 * 登录失败后的"下一步动作"判定。
 *
 * 这里刻意用**服务端真实产出的原文**做用例（见 `AuthService.kt`），
 * 因为分类完全依赖文案：服务端改了措辞而这里没跟上，用户就会失去快捷入口
 * （不会出错，但会退化）。用原文当样本能让这种漂移在改服务端时就暴露。
 */
class LoginFailureAdvisorTest {

    @Test
    fun `手机号未注册才给去注册`() {
        val msg = "用户不存在"
        assertEquals(LoginFailureAdvisor.Kind.NOT_REGISTERED, LoginFailureAdvisor.classify(msg))
        assertTrue(LoginFailureAdvisor.suggestsRegistration(msg))
        assertFalse("未注册不应引导去重置密码", LoginFailureAdvisor.suggestsPasswordReset(msg))
    }

    @Test
    fun `密码错误引导去重置，绝不引导注册`() {
        val msg = "密码错误"
        assertEquals(LoginFailureAdvisor.Kind.WRONG_PASSWORD, LoginFailureAdvisor.classify(msg))
        assertTrue(LoginFailureAdvisor.suggestsPasswordReset(msg))
        // 最危险的误判：把"密码错"当成"没账号" → 用户会去注册新号，丢掉绑定关系
        assertFalse("密码错误不能被当成未注册", LoginFailureAdvisor.suggestsRegistration(msg))
    }

    @Test
    fun `临时锁定只提示等待`() {
        val msg = "密码连续错误次数过多，账号已临时锁定，请约 5 分钟后再试"
        assertEquals(LoginFailureAdvisor.Kind.LOCKED, LoginFailureAdvisor.classify(msg))
        assertFalse(LoginFailureAdvisor.suggestsRegistration(msg))
        assertFalse(LoginFailureAdvisor.suggestsPasswordReset(msg))
    }

    @Test
    fun `冻结与未激活各自归类`() {
        assertEquals(
            LoginFailureAdvisor.Kind.FROZEN,
            LoginFailureAdvisor.classify("账号已被冻结，无法登录。如需恢复请联系客服。")
        )
        assertEquals(
            LoginFailureAdvisor.Kind.INACTIVE,
            LoginFailureAdvisor.classify("账号尚未激活，请等待开通后再登录。")
        )
    }

    @Test
    fun `连不上服务器归到连接类，不误导成账号问题`() {
        val msg = ServerProbe.describeFailure(ConnectException("refused"), "http://192.168.1.10:8090").message
        assertEquals(LoginFailureAdvisor.Kind.CONNECTION, LoginFailureAdvisor.classify(msg))
        assertFalse(LoginFailureAdvisor.suggestsRegistration(msg))
    }

    @Test
    fun `注册页回显的已注册提示不能被当成未注册`() {
        val msg = "手机号已注册，请直接登录"
        assertEquals(LoginFailureAdvisor.Kind.OTHER, LoginFailureAdvisor.classify(msg))
        assertFalse(LoginFailureAdvisor.suggestsRegistration(msg))
    }

    @Test
    fun `空与无关文案一律 OTHER`() {
        listOf(null, "", "   ", "验证码错误或已过期", "服务端内部错误，请稍后重试").forEach {
            assertEquals("误判：$it", LoginFailureAdvisor.Kind.OTHER, LoginFailureAdvisor.classify(it))
            assertFalse(LoginFailureAdvisor.suggestsRegistration(it))
        }
    }
}
