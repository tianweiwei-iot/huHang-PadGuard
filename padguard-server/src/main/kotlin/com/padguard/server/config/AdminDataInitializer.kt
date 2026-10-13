package com.padguard.server.config

import com.padguard.server.domain.Admin
import com.padguard.server.repository.AdminRepository
import com.padguard.server.security.Passwords
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.io.File
import java.security.SecureRandom
import java.util.*

/**
 * 管控后台管理员种子化（独立组件）。
 *
 * ## 设计取舍
 * 不改动既有 [DataInitializer]（含敏感初始化逻辑），用一个独立、职责单一的 Runner 管理管理员账号：
 * 1. 真实部署不会因缺管理员而无法登录后台；
 * 2. 清库 / 重新部署后可自动恢复入口账号，避免"后台锁死"；
 * 3. 与既有初始化逻辑解耦，互不污染，降低回归风险。
 *
 * ## 弱口令收口（与 DataInitializer 家长侧同口径）
 * `admin/admin123` 早期对所有环境无条件播种且**无任何强制重置机制**——家长侧演示账号
 * 有"识别出厂弱口令并强制重置"的处理，管理员侧早期漏配，等于把后台全权入口的默认凭据
 * 永久印在页面上（index.html 登录表单还预填了这对凭据）。现对齐为：
 * - local profile：保留 admin/admin123 便于联调；
 * - 正式部署首次启动（admins 表为空）：随机生成强口令并写入本地文件（不硬编码）；
 * - 正式部署存量库：识别仍在使用 admin123 的管理员并**强制重置**，随机新口令落盘。
 */
@Component
class AdminDataInitializer(
    private val adminRepository: AdminRepository,
    private val env: Environment,
    /**
     * 初始/重置口令文件的落盘路径。
     *
     * 早期实现写死相对路径（容器工作目录）——docker-compose 只挂了 `./uploads:/app/uploads`，
     * 工作目录在容器可写层里：**容器一重建文件就丢**，而管理员口令已被强制重置出 admin123，
     * 后果是全新部署直接锁死后台、没人知道口令。默认改到挂载卷内的 uploads/ 下，
     * 并支持用环境变量/配置覆盖到 secrets 卷（`PADGUARD_INITIAL_PASSWORD_FILE`）。
     */
    @Value("\${padguard.initial-password-file:uploads/管理员初始密码-请登录后修改.txt}")
    private val initialPasswordFile: String
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(AdminDataInitializer::class.java)

    override fun run(args: ApplicationArguments?) {
        if (adminRepository.count() == 0L) {
            if (env.activeProfiles.contains(PROFILE_LOCAL)) {
                seedFactoryAdmin()
            } else {
                seedInitialAdmin()
            }
            return
        }
        if (!env.activeProfiles.contains(PROFILE_LOCAL)) {
            forceResetFactoryPassword()
        }
    }

    private fun seedFactoryAdmin() {
        adminRepository.save(newAdmin(FACTORY_PASSWORD))
        log.warn("已种入初始后台管理员（仅 local 联调）：用户名=admin（角色=super）")
    }

    private fun seedInitialAdmin() {
        val password = randomPassword()
        adminRepository.save(newAdmin(password))
        writeInitialPasswordFile("admin", password, reset = false)
        log.warn("已创建初始后台管理员 admin，随机口令已落盘（请登录后立即修改并删除该文件）")
    }

    /** 正式部署：识别仍在使用出厂弱口令的管理员并强制重置（每次启动幂等检查） */
    private fun forceResetFactoryPassword() {
        val weak = adminRepository.findAll().filter { Passwords.matches(FACTORY_PASSWORD, it.passwordHash) }
        if (weak.isEmpty()) return
        val password = randomPassword()
        weak.forEach { admin ->
            admin.passwordHash = Passwords.encode(password)
            adminRepository.save(admin)
            log.warn("管理员 {} 仍在使用出厂弱口令，已强制重置", admin.username)
        }
        writeInitialPasswordFile(weak.joinToString(",") { it.username }, password, reset = true)
    }

    private fun newAdmin(password: String) = Admin(
        id = "A" + UUID.randomUUID().toString().replace("-", ""),
        username = "admin",
        passwordHash = Passwords.encode(password),
        displayName = "初始超级管理员",
        email = null,
        role = "super",
        status = "active",
        mfaEnabled = false,
        lastLoginAt = null,
        createdAt = System.currentTimeMillis()
    )

    /**
     * 把初始/重置后的明文口令写入服务端工作目录（与家长侧 writeInitialPasswordFile 同思路）：
     * 口令不能硬编码，也不能只打日志（管理员不会去翻日志），"随机生成 + 落显眼文件 + 日志提示路径"
     * 是既安全又可用的组合。多个账号共用同一批重置时共用一个新口令。
     */
    private fun writeInitialPasswordFile(usernames: String, password: String, reset: Boolean) {
        val file = File(initialPasswordFile)
        runCatching {
            // 目标目录可能不存在（uploads 卷未预建 / secrets 卷空挂载），先建目录再写
            file.parentFile?.mkdirs()
            file.writeText(
                """
                PadGuard 管理员初始口令
                ============================
                账号：$usernames
                初始密码：$password

                ${if (reset) "检测到上述账号仍在使用出厂弱口令，已强制重置为上述随机口令。" else ""}
                请登录后台后立即修改密码。
                此文件包含明文口令，妥善保管，用后可删除。
                """.trimIndent(), Charsets.UTF_8
            )
            // 使用绝对路径：相对路径在容器里没人知道落在哪，"文件写了却找不到"
            log.warn("管理员口令已写入 {}（请登录后立即修改并删除该文件）", file.absolutePath)
        }.onFailure { log.error("写入管理员初始密码文件失败: {} -> {}", initialPasswordFile, it.message) }
    }

    private fun randomPassword(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
        val rnd = SecureRandom()
        return (1..16).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
    }

    companion object {
        private const val PROFILE_LOCAL = "local"
        const val FACTORY_PASSWORD = "admin123"
    }
}
