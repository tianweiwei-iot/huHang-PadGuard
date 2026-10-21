package com.padguard.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.Admin
import com.padguard.server.domain.AdminRoles
import com.padguard.server.dto.AdminCreateRequest
import com.padguard.server.dto.AdminDto
import com.padguard.server.dto.AdminRoleCreateRequest
import com.padguard.server.dto.AdminRolePrivilegesDto
import com.padguard.server.dto.AdminUpdateAdminRequest
import com.padguard.server.repository.AdminRepository
import com.padguard.server.repository.SystemSettingRepository
import com.padguard.server.security.AdminPermissionService
import com.padguard.server.security.Passwords
import com.padguard.server.service.MailService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 权限 / 角色 / 管理员管理（RBAC）。
 */
@Service
class AdminRbacService(
    private val adminRepository: AdminRepository,
    private val adminPerm: AdminPermissionService,
    private val auditService: AuditService,
    private val mailService: MailService,
    private val systemSettingRepository: SystemSettingRepository,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(AdminRbacService::class.java)

    /** 权限元数据（领域/动作中文名 + 危险等级 + 系统锁定角色），供前端渲染矩阵 */
    fun permissionSchema(adminId: String): Map<String, Any> {
        requireRead(adminPerm, adminId, "rbac")
        return adminPerm.permissionSchema()
    }

    fun listRoles(adminId: String): List<AdminRoles.RoleDef> {
        requireRead(adminPerm, adminId, "rbac")
        return adminPerm.listRoles()
    }

    /**
     * 新建自定义角色。早期「新建角色」只在前端内存 push 一条，刷新即丢、服务端不存在该角色。
     * 现在落库并参与权限判定。
     */
    @Transactional
    fun createRole(adminId: String, req: AdminRoleCreateRequest): Map<String, Any> {
        adminPerm.require(adminId, "rbac", "write")
        adminPerm.createRole(req.id, req.name, req.description, req.privileges)
        auditService.record(adminId, null, "RBAC_ROLE_CREATE", targetType = "role", targetId = req.id, detail = "新建角色 ${req.name}")
        val created = adminPerm.listRoles().firstOrNull { it.id == req.id }
        return if (created != null) mapOf<String, Any>("ok" to true, "role" to created)
        else mapOf<String, Any>("ok" to true)
    }

    @Transactional
    fun deleteRole(adminId: String, roleId: String): Map<String, Any> {
        adminPerm.require(adminId, "rbac", "write")
        adminPerm.deleteRole(roleId)
        auditService.record(adminId, null, "RBAC_ROLE_DELETE", targetType = "role", targetId = roleId, detail = "删除角色 $roleId")
        return mapOf("ok" to true)
    }

    @Transactional
    fun saveRole(adminId: String, role: AdminRolePrivilegesDto): Map<String, Any> {
        adminPerm.require(adminId, "rbac", "write")
        adminPerm.saveRolePrivileges(role.id, role.privileges)
        auditService.record(adminId, null, "RBAC_ROLE_SAVE", targetType = "role", targetId = role.id, detail = "保存角色权限 ${role.id}")
        return mapOf("ok" to true)
    }

    /**
     * 还原角色权限到系统默认值。内置角色的权限一旦在矩阵里被改乱，早期没有任何自助恢复手段。
     */
    @Transactional
    fun resetRolePrivileges(adminId: String, roleId: String): Map<String, Any> {
        adminPerm.require(adminId, "rbac", "write")
        adminPerm.resetRolePrivileges(roleId)
        auditService.record(adminId, null, "RBAC_ROLE_RESET", targetType = "role", targetId = roleId, detail = "还原角色权限 $roleId")
        return mapOf("ok" to true)
    }

    fun listAdmins(adminId: String): List<AdminDto> {
        requireRead(adminPerm, adminId, "rbac")
        return adminRepository.findAll().map { toAdminDto(it) }
    }

    @Transactional
    fun createAdmin(adminId: String, req: AdminCreateRequest): Map<String, Any> {
        adminPerm.require(adminId, "rbac", "write")
        ensureNotEscalatingToSuper(adminRepository, adminId, req.roleId)
        if (adminRepository.existsByUsername(req.username)) {
            throw BizException(ParentErr.PARAM_ERROR, "登录账号已存在", Audience.PARENT)
        }
        // 内置角色 + 运营自建角色（V24 admin_roles）都可被指派
        if (!adminPerm.allRoleKeys().contains(req.roleId)) {
            throw BizException(ParentErr.PARAM_ERROR, "角色不存在", Audience.PARENT)
        }
        val pwd = genPassword(systemSettingRepository, objectMapper)
        val admin = Admin(
            id = "A" + UUID.randomUUID().toString().replace("-", ""),
            username = req.username, passwordHash = Passwords.encode(pwd),
            displayName = req.name?.takeIf { it.isNotBlank() } ?: req.username,
            email = req.email, role = req.roleId, status = "active",
            createdAt = System.currentTimeMillis()
        )
        adminRepository.save(admin)
        auditService.record(adminId, admin.id, AdminAudit.RBAC_CREATE, targetType = "admin", targetId = admin.id, detail = "新建管理员 ${admin.username} 角色 ${req.roleId}")
        return mapOf("ok" to true, "admin" to toAdminDto(admin), "password" to pwd)
    }

    @Transactional
    fun assignRole(adminId: String, targetAdminId: String, roleId: String): Map<String, Any> {
        adminPerm.require(adminId, "rbac", "assign")
        ensureNotEscalatingToSuper(adminRepository, adminId, roleId)
        if (!adminPerm.allRoleKeys().contains(roleId)) {
            throw BizException(ParentErr.PARAM_ERROR, "角色不存在", Audience.PARENT)
        }
        val target = adminRepository.findById(targetAdminId).orElse(null)
            ?: throw BizException(ParentErr.PARAM_ERROR, "管理员不存在", Audience.PARENT)
        val old = target.role
        target.role = roleId
        adminRepository.save(target)
        auditService.record(adminId, target.id, AdminAudit.RBAC_ASSIGN, targetType = "admin", targetId = target.id, detail = "变更角色 $old -> $roleId")
        return mapOf("ok" to true)
    }

    @Transactional
    fun updateAdmin(adminId: String, targetAdminId: String, req: AdminUpdateAdminRequest): Map<String, Any> {
        adminPerm.require(adminId, "rbac", "write")
        val target = adminRepository.findById(targetAdminId).orElse(null)
            ?: throw BizException(ParentErr.PARAM_ERROR, "管理员不存在", Audience.PARENT)
        req.name?.takeIf { it.isNotBlank() }?.let { target.displayName = it }
        target.email = req.email?.takeIf { it.isNotBlank() }
        val newStatus = req.status?.takeIf { it.isNotBlank() }
        if (newStatus != null) {
            if (newStatus != "active" && newStatus != "disabled") {
                throw BizException(ParentErr.PARAM_ERROR, "状态仅支持 active / disabled", Audience.PARENT)
            }
            // 安全护栏：禁用最后一个超级管理员会锁死后台
            if (newStatus == "disabled" && target.role == "super" && countActiveSuperAdmins() <= 1) {
                throw BizException(ParentErr.FORBIDDEN, "不能停用最后一个超级管理员（将锁死后台）", Audience.PARENT)
            }
            target.status = newStatus
        }
        adminRepository.save(target)
        auditService.record(adminId, target.id, AdminAudit.RBAC_UPDATE, targetType = "admin", targetId = target.id, detail = "修改管理员 ${target.username}")
        return mapOf("ok" to true)
    }

    @Transactional
    fun deleteAdmin(adminId: String, targetAdminId: String): Map<String, Any> {
        adminPerm.require(adminId, "rbac", "delete")
        if (targetAdminId == adminId) {
            throw BizException(ParentErr.PARAM_ERROR, "不能删除当前登录的管理员账号", Audience.PARENT)
        }
        val target = adminRepository.findById(targetAdminId).orElse(null)
            ?: throw BizException(ParentErr.PARAM_ERROR, "管理员不存在", Audience.PARENT)
        // 安全护栏：删除最后一个超级管理员会锁死后台
        if (target.role == "super" && countActiveSuperAdmins() <= 1) {
            throw BizException(ParentErr.FORBIDDEN, "不能删除最后一个超级管理员（将锁死后台）", Audience.PARENT)
        }
        adminRepository.delete(target)
        auditService.record(adminId, target.id, AdminAudit.RBAC_DELETE, targetType = "admin", targetId = target.id, detail = "删除管理员 ${target.username}")
        return mapOf("ok" to true)
    }

    @Transactional
    fun resetAdminPassword(adminId: String, targetAdminId: String): Map<String, Any> {
        adminPerm.require(adminId, "rbac", "reset_pwd")
        val target = adminRepository.findById(targetAdminId).orElse(null)
            ?: throw BizException(ParentErr.PARAM_ERROR, "管理员不存在", Audience.PARENT)
        val newPwd = genPassword(systemSettingRepository, objectMapper)
        target.passwordHash = Passwords.encode(newPwd)
        // 密码已变更，旧令牌必须立即作废：否则拿到旧令牌的人仍能以该管理员身份操作后台。
        target.tokenVersion = target.tokenVersion + 1
        adminRepository.save(target)
        auditService.record(adminId, target.id, AdminAudit.RBAC_RESET_PWD, targetType = "admin", targetId = target.id, detail = "重置管理员密码 ${target.username}（已吊销其全部令牌）")
        // 重置生效后立即邮件通知对方（若已登记邮箱且已配置 SMTP）；否则退回后台弹窗明文转告
        val emailed = mailService.sendPasswordReset(target.email, target.username, "管理员账号", newPwd)
        return mapOf("ok" to true, "newPwd" to newPwd, "emailed" to emailed)
    }

    /**
     * 管理员自助修改自己的密码。与「重置他人密码」区分：此处必须校验当前密码，且不涉及 RBAC 闸门。
     * 新密码强制至少 8 位。
     */
    @Transactional
    fun changeOwnPassword(adminId: String, currentPassword: String, newPassword: String): Map<String, Any> {
        if (newPassword.length < 8) {
            throw BizException(ParentErr.PARAM_ERROR, "新密码至少 8 位", Audience.PARENT)
        }
        val admin = adminRepository.findById(adminId).orElse(null)
            ?: throw BizException(ParentErr.UNAUTHORIZED, "管理员不存在", Audience.PARENT)
        if (!Passwords.matches(currentPassword, admin.passwordHash)) {
            throw BizException(ParentErr.UNAUTHORIZED, "当前密码错误", Audience.PARENT)
        }
        admin.passwordHash = Passwords.encode(newPassword)
        // 同上：改完密码即吊销本人全部会话。副作用是调用方当前这张令牌也会失效，
        // 前端改密成功后应直接跳登录页重新登录 —— 这是刻意的，也是"改密码即踢人"的标准行为。
        admin.tokenVersion = admin.tokenVersion + 1
        adminRepository.save(admin)
        auditService.record(adminId, null, "RBAC_SELF_CHG_PWD", targetType = "admin", targetId = adminId, detail = "修改自身密码（已吊销其全部令牌）")
        return mapOf("ok" to true, "reloginRequired" to true)
    }

    /** 统计处于启用状态的超级管理员数量（用于防锁死护栏） */
    private fun countActiveSuperAdmins(): Int =
        adminRepository.findAll().count { it.role == "super" && it.status == "active" }
}
