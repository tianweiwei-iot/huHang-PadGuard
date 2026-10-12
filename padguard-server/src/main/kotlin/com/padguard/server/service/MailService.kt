package com.padguard.server.service

import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Service

/**
 * 邮件发送（密码重置通知等）。
 *
 * 设计取舍：**可降级、不阻断主流程**。
 * 1. 未配置 SMTP（[padguard.mail.enabled] 为 false 或 [spring.mail.host] 为空）时，[sendPasswordReset]
 *    仅记一条 WARN 日志并返回 false，调用方仍会把明文新口令带回后台弹窗，由管理员通过安全渠道转告 ——
 *    系统绝不会因此卡住"重置密码"这一管理动作。
 * 2. 配置了 SMTP 但发送失败时，同样记日志并返回 false，不向上抛异常（避免一次邮件故障连累账号密码重置失败）。
 *
 * 凭据全部通过环境变量注入（PADGUARD_MAIL_*），不进配置文件/安装包；[padguard.mail.enabled]
 * 默认 false，杜绝"误以为发了邮件、实际没发"的静默失效。
 */
@Service
class MailService(
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private val mailSender: JavaMailSender?,
    @Value("\${padguard.mail.enabled:false}") private val enabled: Boolean,
    @Value("\${padguard.mail.from:no-reply@padguard.com}") private val from: String,
    @Value("\${spring.mail.host:}") private val host: String
) {

    private val log = LoggerFactory.getLogger(MailService::class.java)

    /**
     * 发送密码重置通知邮件。
     *
     * @return true=已成功发送；false=未发送（未配置或发送失败，调用方应改用后台弹窗明文转告）
     */
    fun sendPasswordReset(
        toEmail: String?,
        accountName: String,
        accountType: String,
        newPassword: String
    ): Boolean {
        if (toEmail.isNullOrBlank()) {
            log.warn("密码重置通知未发送：账号 {}（{}）未登记邮箱，无法邮件通知，请通过安全渠道转告新口令。", accountName, accountType)
            return false
        }
        if (!enabled || host.isBlank()) {
            log.warn(
                "密码重置通知未发送（邮件未启用）：账号 {}（{}）邮箱={}；请在 application.yml 配置 padguard.mail.enabled=true 与 SMTP，" +
                    "或将明文新口令通过安全渠道转告对方。",
                accountName, accountType, toEmail
            )
            return false
        }
        return try {
            val sender = mailSender ?: run {
                log.warn("密码重置通知未发送：JavaMailSender 未初始化（SMTP 未配置），退回后台弹窗明文转告。")
                return false
            }
            val msg: MimeMessage = sender.createMimeMessage()
            val helper = MimeMessageHelper(msg, true, "UTF-8")
            helper.setFrom(InternetAddress(from, "PadGuard 管控平台", "UTF-8"))
            helper.setTo(toEmail)
            helper.setSubject("【PadGuard】您的账号密码已重置")
            helper.setText(buildBody(accountName, accountType, newPassword), false)
            sender.send(msg)
            log.info("密码重置通知已发送至 {}（账号 {} / {}）", toEmail, accountName, accountType)
            true
        } catch (e: Exception) {
            log.error("密码重置邮件发送失败：to={}, account={}，将退回后台弹窗明文转告。原因：{}", toEmail, accountName, e.message)
            false
        }
    }

    private fun buildBody(accountName: String, accountType: String, newPassword: String): String = """
        <div style="font-family:-apple-system,Segoe UI,Roboto,'PingFang SC','Microsoft YaHei',sans-serif;max-width:480px;margin:0 auto">
          <h2 style="color:#1f2937">密码重置通知</h2>
          <p style="color:#374151;line-height:1.7">您好，您的 <b>${escape(accountType)}</b> 账号 <b>${escape(accountName)}</b> 密码已被重置。</p>
          <p style="color:#374151;line-height:1.7">新的登录密码为：</p>
          <p style="background:#f3f4f6;border-radius:8px;padding:12px 16px;font-size:18px;letter-spacing:1px;color:#111827;font-family:monospace">${escape(newPassword)}</p>
          <p style="color:#6b7280;line-height:1.7;font-size:13px">请尽快使用新密码登录并修改为您自己的密码。若非本人操作，请立即联系管理员。</p>
          <p style="color:#9ca3af;font-size:12px;margin-top:24px">本邮件由 PadGuard 管控平台自动发送，请勿直接回复。</p>
        </div>
    """.trimIndent()

    private fun escape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")
}
