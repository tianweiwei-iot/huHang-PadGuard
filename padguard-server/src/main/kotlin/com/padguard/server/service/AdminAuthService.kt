package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.Admin
import com.padguard.server.dto.AdminLoginResponse
import com.padguard.server.repository.AdminRepository
import com.padguard.server.security.JwtTokenProvider
import com.padguard.server.security.Passwords
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 后台管理员登录。
 */
@Service
class AdminAuthService(
    private val adminRepository: AdminRepository,
    private val jwt: JwtTokenProvider
) {
    private val log = LoggerFactory.getLogger(AdminAuthService::class.java)

    fun login(username: String, password: String): AdminLoginResponse {
        val admin = adminRepository.findByUsername(username)
            ?: throw BizException(ParentErr.UNAUTHORIZED, "管理员不存在", Audience.PARENT)
        if (admin.status != "active") {
            throw BizException(ParentErr.UNAUTHORIZED, "管理员账号已停用", Audience.PARENT)
        }
        if (!Passwords.matches(password, admin.passwordHash)) {
            throw BizException(ParentErr.UNAUTHORIZED, "密码错误", Audience.PARENT)
        }
        admin.lastLoginAt = System.currentTimeMillis()
        adminRepository.save(admin)
        val token = jwt.createAdminToken(admin.id, admin.tokenVersion)
        return AdminLoginResponse(token, toAdminDto(admin))
    }

    /**
     * 退出登录：把该管理员的令牌版本号 +1，作废其名下**全部**已签发的令牌。
     *
     * 设计说明：
     * - 幂等 —— 重复调用只是继续递增，不报错。前端"点两次退出"或令牌已过期后再点都不会炸。
     * - 覆盖全部会话 —— 同一个账号在多个浏览器登录时，一次退出全部失效；
     *   这也是选版本号而非 jti 黑名单的原因（后者只能吊销调用方手里的那一张）。
     * - 管理员已被删除时静默返回 —— 退出登录本就是"让自己失效"，账号不存在时目标已达成，
     *   抛错只会让前端卡在退出界面出不去。
     */
    fun logout(adminId: String): Map<String, Any> {
        val admin = adminRepository.findById(adminId).orElse(null) ?: return mapOf("revoked" to false)
        admin.tokenVersion = admin.tokenVersion + 1
        adminRepository.save(admin)
        log.info("管理员退出登录，已吊销其全部令牌: adminId={} newVersion={}", adminId, admin.tokenVersion)
        return mapOf("revoked" to true)
    }
}
