package com.padguard.server.config

import com.padguard.server.domain.Admin
import com.padguard.server.repository.AdminRepository
import com.padguard.server.security.Passwords
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.*

/**
 * 管控后台管理员种子化（独立组件）。
 *
 * 设计取舍：不改动既有 [com.padguard.server.config.DataInitializer]（含敏感初始化逻辑），
 * 而是用一个独立、职责单一的 Runner 在**首次启动且 admins 表为空**时，种入一个初始超级管理员。
 * 这样：
 * 1. 真实部署不会因缺管理员而无法登录后台；
 * 2. 清库 / 重新部署后可自动恢复入口账号，避免"后台锁死"；
 * 3. 与既有初始化逻辑解耦，互不污染，降低回归风险。
 *
 * 口令安全：不再写死弱口令。优先读取环境变量 `PADGUARD_ADMIN_PASSWORD`；
 * 未设置时生成 16 位强随机口令并打印到日志，**绝不**使用 admin123 这类弱默认。
 * 无论哪种方式，上线后都请立即在后台修改密码。
 */
@Component
class AdminDataInitializer(
    private val adminRepository: AdminRepository
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(AdminDataInitializer::class.java)

    override fun run(args: ApplicationArguments?) {
        if (adminRepository.count() > 0) {
            log.info("admins 表已存在数据，跳过种子化")
            return
        }
        val rawPassword = System.getenv("PADGUARD_ADMIN_PASSWORD")?.trim()?.takeIf { it.isNotBlank() }
            ?: run {
                val p = genInitialPassword()
                log.warn(
                    "PADGUARD_ADMIN_PASSWORD 未设置，已为初始超级管理员生成强随机口令（请妥善保存，登录后立即修改）：{}",
                    p
                )
                p
            }
        val admin = Admin(
            id = "A" + UUID.randomUUID().toString().replace("-", ""),
            username = "admin",
            passwordHash = Passwords.encode(rawPassword),
            displayName = "初始超级管理员",
            email = null,
            role = "super",
            status = "active",
            mfaEnabled = false,
            lastLoginAt = null,
            createdAt = System.currentTimeMillis()
        )
        adminRepository.save(admin)
        log.warn("已种入初始后台管理员：用户名=admin（角色=super）。请尽快登录后台修改密码！")
    }

    /** 生成 16 位强随机口令（字母 + 数字 + 符号，去除易混淆字符） */
    private fun genInitialPassword(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789!@#%^+"
        val rnd = SecureRandom()
        return (1..16).joinToString("") { chars[rnd.nextInt(chars.length)].toString() }
    }
}
