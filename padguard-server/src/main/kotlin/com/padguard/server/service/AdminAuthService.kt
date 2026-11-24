package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.Admin
import com.padguard.server.dto.AdminLoginResponse
import com.padguard.server.repository.AdminRepository
import com.padguard.server.security.AdminPermissionService
import com.padguard.server.security.JwtTokenProvider
import com.padguard.server.security.Passwords
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 后台管理员登录。
 */
@Service
class AdminAuthService(
    private val adminRepository: AdminRepository,
    private val jwt: JwtTokenProvider,
    private val adminPerm: AdminPermissionService
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
        // 只更新 lastLoginAt 一列，绝不用 save(admin)：BCrypt 校验期间管理员可能在另一端
        // 退出（tokenVersion +1），整行快照写回会把版本号覆盖回旧值、让已吊销令牌复活。
        val now = System.currentTimeMillis()
        runCatching { adminRepository.touchLastLoginAt(admin.id, now) }
            .onFailure { log.warn("update admin lastLoginAt failed: {}", it.message) }
        // 只更新内存快照供本次响应的 DTO 展示（不 save，不写回其它字段）
        admin.lastLoginAt = now
        val token = jwt.createAdminToken(admin.id, admin.tokenVersion)
        // 必须带 adminPerm：前端 RBAC 完全依赖登录响应里的 AdminDto.privileges 渲染
        // 侧边栏与操作按钮（PG.can），不传则恒为 emptyMap → 登录后零菜单、全站按钮隐藏。
        return AdminLoginResponse(token, toAdminDto(admin, adminPerm))
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
    @Transactional
    fun logout(adminId: String): Map<String, Any> {
        // 行锁读 + 事务：tokenVersion 只增不减，若用无锁 findById + 整行 save，
        // 与 login/改密/改角色的并发写会互相覆盖，把刚 +1 的版本号写回旧值 ——
        // 退出被抵消、已吊销令牌重新有效。与家长端 AuthService.logout 同口径。
        val admin = adminRepository.findByIdForUpdate(adminId) ?: return mapOf("revoked" to false)
        admin.tokenVersion = admin.tokenVersion + 1
        adminRepository.save(admin)
        log.info("管理员退出登录，已吊销其全部令牌: adminId={} newVersion={}", adminId, admin.tokenVersion)
        return mapOf("revoked" to true)
    }
}
