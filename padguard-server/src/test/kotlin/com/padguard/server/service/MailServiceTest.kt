package com.padguard.server.service

import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.mail.javamail.JavaMailSender
import java.util.Properties

/**
 * [MailService] 单测：核心不变量是「可降级、绝不阻断主流程」。
 * 未配置 SMTP / 未启用 / 发送失败 / 收件人无邮箱，都必须返回 false 且不抛异常，
 * 让上层用后台弹窗明文转告新口令，而不是让「重置密码」这一管理动作卡死。
 *
 * 注意：[JavaMailSender.send] 同时存在 `send(MimeMessage)` 与 `send(vararg MimeMessage!)` 两个重载，
 * 在 Kotlin 里用 matcher 调 `send` 会产生重载歧义且无法稳定匹配生产侧的单参调用，
 * 因此本测试不直验 `send`，而是直验**唯一无歧义**的 [JavaMailSender.createMimeMessage]——
 * 它仅在下发路径（try 块内、send 之前）被调用一次，足以证明是否进入了发送分支；
 * 整段发送逻辑包在 try/catch 中，异常一律被吞并返回 false。
 * 不加载 Spring 上下文（规避全量集成 OOM）：直接构造、[JavaMailSender] 用 mock。
 */
class MailServiceTest {

    private fun newService(
        enabled: Boolean = false,
        host: String = "",
        mailSender: JavaMailSender? = null,
        from: String = "no-reply@padguard.com"
    ): MailService = MailService(mailSender, enabled, from, host)

    private fun realMessage(): MimeMessage = MimeMessage(Session.getDefaultInstance(Properties()))

    @Test
    fun blankEmailReturnsFalseWithoutSending() {
        val sender = mock(JavaMailSender::class.java)
        val svc = newService(enabled = true, host = "smtp.example.com", mailSender = sender)
        assertFalse(svc.sendPasswordReset(null, "admin", "admin", "newpass123"))
        verify(sender, never()).createMimeMessage()
    }

    @Test
    fun disabledMailReturnsFalseWithoutSending() {
        val sender = mock(JavaMailSender::class.java)
        val svc = newService(enabled = false, host = "smtp.example.com", mailSender = sender)
        assertFalse(svc.sendPasswordReset("a@b.com", "admin", "admin", "newpass123"))
        verify(sender, never()).createMimeMessage()
    }

    @Test
    fun blankHostReturnsFalseWithoutSending() {
        val sender = mock(JavaMailSender::class.java)
        val svc = newService(enabled = true, host = "", mailSender = sender)
        assertFalse(svc.sendPasswordReset("a@b.com", "admin", "admin", "newpass123"))
        verify(sender, never()).createMimeMessage()
    }

    @Test
    fun uninitializedSenderReturnsFalseWithoutNpe() {
        val svc = newService(enabled = true, host = "smtp.example.com", mailSender = null)
        assertFalse(svc.sendPasswordReset("a@b.com", "admin", "admin", "newpass123"))
    }

    @Test
    fun enabledAndConfiguredEntersSendPathAndReturnsTrue() {
        val sender = mock(JavaMailSender::class.java)
        `when`(sender.createMimeMessage()).thenReturn(realMessage())
        val svc = newService(enabled = true, host = "smtp.example.com", mailSender = sender)
        assertTrue(svc.sendPasswordReset("a@b.com", "admin", "admin", "newpass123"))
        verify(sender).createMimeMessage()
    }

    @Test
    fun failureInSendBlockIsSwallowedReturnsFalse() {
        val sender = mock(JavaMailSender::class.java)
        // 让发送路径中的任意一步抛异常（这里打在 createMimeMessage 上），验证整段被 try/catch 吞掉
        doThrow(RuntimeException("smtp down")).`when`(sender).createMimeMessage()
        val svc = newService(enabled = true, host = "smtp.example.com", mailSender = sender)
        assertFalse(svc.sendPasswordReset("a@b.com", "admin", "admin", "newpass123"))
    }

    @Test
    fun htmlSpecialPasswordDoesNotThrow() {
        val sender = mock(JavaMailSender::class.java)
        `when`(sender.createMimeMessage()).thenReturn(realMessage())
        val svc = newService(enabled = true, host = "smtp.example.com", mailSender = sender)
        assertTrue(
            svc.sendPasswordReset(
                "a@b.com", "<b>admin</b>", "admin", "<script>alert(1)</script>"
            )
        )
        verify(sender).createMimeMessage()
    }
}
