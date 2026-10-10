package com.padguard.server.repository

import com.padguard.server.domain.Admin
import com.padguard.server.domain.AdminRoleOverride
import com.padguard.server.domain.CustomAdminRole
import com.padguard.server.domain.SystemSetting
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface AdminRepository : JpaRepository<Admin, String> {
    fun findByUsername(username: String): Admin?
    fun existsByUsername(username: String): Boolean
    fun countByStatus(status: String): Long
}

@Repository
interface AdminRoleOverrideRepository : JpaRepository<AdminRoleOverride, String> {
    fun findByRoleKey(roleKey: String): AdminRoleOverride?
}

/**
 * 后台自定义角色表（V24__admin_custom_roles.sql）。
 *
 * 内置角色定义在代码常量 [com.padguard.server.domain.AdminRoles.ROLES] 中，
 * 运营自建角色落库到这里；读取时两者合并，权限判定与前端矩阵共用同一份结果。
 */
@Repository
interface CustomAdminRoleRepository : JpaRepository<CustomAdminRole, String> {
    fun existsByName(name: String): Boolean
}

@Repository
interface SystemSettingRepository : JpaRepository<SystemSetting, String>
