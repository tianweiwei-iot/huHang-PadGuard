package com.padguard.server.config

import com.padguard.server.domain.Admin
import com.padguard.server.repository.AdminRepository
import com.padguard.server.security.Passwords
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
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
 * 种子账号固定为 admin / admin123（角色 super）。上线后请立即在后台修改密码，
 * 或在 [com.padguard.server.service.AdminService] 中替换为从环境变量注入的强口令。
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
        val admin = Admin(
            id = "A" + UUID.randomUUID().toString().replace("-", ""),
            username = "admin",
            passwordHash = Passwords.encode("admin123"),
            displayName = "初始超级管理员",
            email = null,
            role = "super",
            status = "active",
            mfaEnabled = false,
            lastLoginAt = null,
            createdAt = System.currentTimeMillis()
        )
        adminRepository.save(admin)
        log.warn("已种入初始后台管理员：用户名=admin / 密码=admin123（角色=super）。请尽快登录后台修改密码！")
    }
}
