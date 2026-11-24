package com.padguard.server.repository

import com.padguard.server.domain.Admin
import com.padguard.server.domain.AdminRoleOverride
import com.padguard.server.domain.CustomAdminRole
import com.padguard.server.domain.SystemSetting
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
interface AdminRepository : JpaRepository<Admin, String> {
    fun findByUsername(username: String): Admin?

    /**
     * 悲观行锁读。后台"只改一两个字段"的写操作（改资料 / 停用 / 重置密码）必须走这里：
     * `findById` 返回脱管快照，`save` 是整行 merge，会把并发提交的 `token_version` 写回旧值，
     * 让"刚刚被吊销的旧令牌"重新生效。与 [com.padguard.server.repository.UserRepository.findByIdForUpdate] 同口径。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Admin a where a.id = :id")
    fun findByIdForUpdate(@Param("id") id: String): Admin?

    /**
     * 只更新最后登录时刻的**单列 UPDATE**（与 [com.padguard.server.repository.UserRepository.touchLastLoginAt] 同口径）。
     *
     * 绝不能用 `save(admin)` 记 lastLoginAt：那是把登录时读到的整行快照写回数据库。
     * 若在读快照到 save 之间该管理员在另一端点退出（`logout` 把 token_version +1），
     * 旧快照会把版本号覆盖回旧值 —— 刚被吊销的令牌重新生效，退出等于没退出。
     * 单列 UPDATE 只碰这一列，天然不覆盖任何并发写入。
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Admin a set a.lastLoginAt = :ts where a.id = :id")
    fun touchLastLoginAt(@Param("id") id: String, @Param("ts") ts: Long): Int

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
