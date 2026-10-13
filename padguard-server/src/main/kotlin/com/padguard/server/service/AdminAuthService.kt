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
        val token = jwt.createAdminToken(admin.id)
        return AdminLoginResponse(token, toAdminDto(admin))
    }
}
