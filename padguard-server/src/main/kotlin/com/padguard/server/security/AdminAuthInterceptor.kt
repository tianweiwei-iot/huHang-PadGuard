package com.padguard.server.security

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.repository.AdminRepository
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor

/**
 * 管控后台（管理员）鉴权：Bearer 管理员 JWT -> request attribute "adminId"。
 *
 * 只覆盖 `/v1/admin/`（在 [com.padguard.server.config.WebConfig] 中注册，并排除登录接口）。
 * 与家长端 [ParentAuthInterceptor] 用同一套 JWT 密钥，但**强校验 scope=admin 声明**——
 * 家长令牌即使被截获也无法冒充管理员（scope 不符会被拒），反之亦然。
 *
 * 必须确认管理员在库里真实存在且处于启用状态：换库 / 清库后旧管理员令牌签名依然有效，
 * 若不校验存在性与状态，就会出现"幽灵管理员"，能把冻结的账号重新激活、改别人角色。
 */
@Component
class AdminAuthInterceptor(
    private val jwt: JwtTokenProvider,
    private val adminRepository: AdminRepository
) : HandlerInterceptor {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (handler !is HandlerMethod) return true
        val auth = request.getHeader("Authorization")
        if (auth.isNullOrBlank() || !auth.startsWith("Bearer ")) {
            throw BizException(ParentErr.UNAUTHORIZED, "missing or invalid Authorization", Audience.PARENT)
        }
        val token = auth.substring(7)
        // 先校验 scope，避免把家长令牌当管理员令牌放行
        if (!jwt.isAdminToken(token)) {
            throw BizException(ParentErr.UNAUTHORIZED, "invalid admin token", Audience.PARENT)
        }
        val payload = try {
            jwt.parseAdminToken(token)
        } catch (e: Exception) {
            throw BizException(ParentErr.UNAUTHORIZED, "invalid token", Audience.PARENT)
        }
        val adminId = payload.adminId
        val admin = adminRepository.findById(adminId).orElse(null)
            ?: throw BizException(ParentErr.UNAUTHORIZED, "管理员不存在或已被删除", Audience.PARENT)
        if (admin.status != "active") {
            throw BizException(ParentErr.UNAUTHORIZED, "管理员账号已停用", Audience.PARENT)
        }
        // 令牌版本号闸门：登出 / 改密码 / 停用账号都会把库内值 +1，
        // 令牌里的 ver 一旦落后即判定为已吊销。JWT 本身无法主动作废，这是唯一的吊销途径。
        // 历史令牌无 ver 声明时解析为 0，与新列的默认值一致，不会误杀存量会话。
        if (payload.version < admin.tokenVersion) {
            throw BizException(ParentErr.UNAUTHORIZED, "登录已失效，请重新登录", Audience.PARENT)
        }
        request.setAttribute("adminId", adminId)
        return true
    }

    companion object {
        const val ADMIN_ID_ATTR = "adminId"
    }
}
