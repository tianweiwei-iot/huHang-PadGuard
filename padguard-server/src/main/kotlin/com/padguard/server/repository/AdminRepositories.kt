package com.padguard.server.repository

import com.padguard.server.domain.Admin
import com.padguard.server.domain.AdminRoleOverride
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

@Repository
interface SystemSettingRepository : JpaRepository<SystemSetting, String>
