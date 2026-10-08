package com.padguard.server.domain

import jakarta.persistence.*

/**
 * 管控后台管理员账号。
 *
 * 与「家长账号」[User] 完全隔离：家长走 `/v1/` 的 Bearer JWT（由 [com.padguard.server.security.ParentAuthInterceptor] 校验），
 * 管理员走 `/v1/admin/` 的 Bearer JWT（由 [com.padguard.server.security.AdminAuthInterceptor] 校验，token 带 scope=admin）。
 * 两套令牌由 [com.padguard.server.security.JwtTokenProvider] 用同一密钥签发但带不同 scope 声明，互不越权。
 *
 * 角色（[role]）引用 [AdminRoles] 中定义的固定角色键，权限矩阵由 [com.padguard.server.security.AdminPermissionService] 解析，
 * 真正的管理操作闸门在服务端（前端只做 UI 隐藏，越权请求会被拦截）。
 */
@Entity
@Table(name = "admins")
class Admin(
    @Id var id: String = "",
    @Column(name = "username", unique = true, nullable = false) var username: String = "",
    @Column(name = "password_hash", nullable = false) var passwordHash: String = "",
    var displayName: String? = null,
    var email: String? = null,
    /** 角色键，引用 AdminRoles.ROLES 的 id（super/ops/cs/finance/audit 或自定义） */
    @Column(name = "role_key", nullable = false) var role: String = "ops",
    /** 账号状态：active（启用）/ disabled（停用） */
    @Column(name = "status", nullable = false) var status: String = "active",
    /** 是否开启 MFA（双因素认证）；预留字段，登录流程后续可强制校验 */
    @Column(name = "mfa_enabled") var mfaEnabled: Boolean = false,
    @Column(name = "last_login_at") var lastLoginAt: Long? = null,
    @Column(name = "created_at") var createdAt: Long = 0
)

/**
 * 角色权限覆盖（可编辑 RBAC 的持久化）。
 *
 * 角色的基础权限在 [AdminRoles] 中以代码定义为权威默认值（保证一致性、不漂移）；
 * 运营在后台对某个角色勾选/取消的权限，通过本表 override 持久化（按 role_key 唯一）。
 * [com.padguard.server.security.AdminPermissionService] 解析时：有 override 用 override，解析失败回退默认，
 * 因此永远不会因 JSON 损坏而让权限判定崩溃。
 */
@Entity
@Table(name = "admin_role_overrides")
class AdminRoleOverride(
    @Id var id: String = "",
    @Column(name = "role_key", unique = true, nullable = false) var roleKey: String = "",
    /** 权限矩阵 JSON：{ "area": ["action", ...], ... } */
    @Column(name = "privileges_json", columnDefinition = "text") var privilegesJson: String = "{}"
)

/**
 * 系统设置键值对（运营可调，无需改代码发版）。
 *
 * 用单表 key-value 而非为每种配置建列：新增配置项零迁移成本，
 * 读取时按 key 取、缺失即回落默认值（见 [com.padguard.server.service.AdminService.loadSettings]）。
 */
@Entity
@Table(name = "system_settings")
class SystemSetting(
    // 注意：字段名 key 是 SQL 保留字，必须显式用 \"key\" 声明为带引号的定界标识符，
    // 否则 Hibernate 生成的 SELECT/INSERT（如 select ss1_0.key ...）在 H2 与 PostgreSQL 下都会报语法错误。
    // Flyway V13 已用 "key" 建表，这里保持一致（H2 MODE=PostgreSQL / PostgreSQL 均大小写敏感匹配）。
    @Id @Column(name = "\"key\"") var key: String = "",
    @Column(name = "value_json", columnDefinition = "text") var valueJson: String = ""
)

/**
 * 后台固定角色与权限模型（area:action 动词模型）。
 *
 * 角色定义为代码常量（类似主流 CMS 的内置角色），作为权限的**权威默认值**；
 * 可编辑性通过 [AdminRoleOverride] 叠加，而非让角色定义本身漂移。
 * 新增角色请加在 [ROLES]；新增领域/动作请同步更新 [AREAS]，前端 rbac 视图的矩阵列与之对应。
 */
object AdminRoles {
    /** 领域 -> 该领域下可用动作（与前端 rbac.js 的 AREAS 逐一对应） */
    val AREAS: Map<String, List<String>> = mapOf(
        "parents" to listOf("read", "write", "create", "suspend", "reset_pwd", "delete"),
        "devices" to listOf("read", "write", "lock", "unlock", "unbind"),
        "billing" to listOf("read", "write", "refund", "manual"),
        "rbac" to listOf("read", "write", "assign", "reset_pwd", "delete"),
        "server" to listOf("read", "write"),
        "audit" to listOf("read"),
        "settings" to listOf("read", "write")
    )

    data class RoleDef(
        val id: String,
        val name: String,
        val desc: String,
        val isSystem: Boolean,
        val privileges: Map<String, List<String>>
    )

    private fun allOf(areas: Map<String, List<String>>): Map<String, List<String>> =
        areas.mapValues { (_, acts) -> acts.toList() }

    private val READONLY: Map<String, List<String>> = AREAS.mapValues { (_, acts) -> listOf("read") }

    val ROLES: List<RoleDef> = listOf(
        RoleDef(
            id = "super", name = "超级管理员",
            desc = "拥有全部权限，含系统设置与角色管理", isSystem = true,
            privileges = allOf(AREAS)
        ),
        RoleDef(
            id = "ops", name = "运营",
            desc = "账号开通、设备与策略运营，不含财务", isSystem = true,
            privileges = mapOf(
                "parents" to listOf("read", "write", "create", "suspend", "delete"),
                "devices" to listOf("read", "write", "lock", "unlock"),
                "billing" to listOf("read"),
                "rbac" to listOf("read"),
                "server" to listOf("read"),
                "audit" to listOf("read"),
                "settings" to listOf("read")
            )
        ),
        RoleDef(
            id = "cs", name = "客服",
            desc = "只读查看与基础处理，用于工单答疑", isSystem = true,
            privileges = READONLY
        ),
        RoleDef(
            id = "finance", name = "财务",
            desc = "订阅、订单、退款与营收报表", isSystem = true,
            privileges = mapOf(
                "parents" to listOf("read"),
                "devices" to listOf("read"),
                "billing" to listOf("read", "write", "refund", "manual"),
                "rbac" to listOf("read"),
                "server" to listOf("read"),
                "audit" to listOf("read"),
                "settings" to listOf("read")
            )
        ),
        RoleDef(
            id = "audit", name = "审计",
            desc = "合规审计，全量只读 + 审计日志", isSystem = true,
            privileges = mapOf(
                "parents" to listOf("read"),
                "devices" to listOf("read"),
                "billing" to listOf("read"),
                "rbac" to listOf("read"),
                "server" to listOf("read"),
                "audit" to listOf("read", "write"),
                "settings" to listOf("read")
            )
        )
    )

    fun defaultPrivileges(roleKey: String): Map<String, List<String>> =
        ROLES.firstOrNull { it.id == roleKey }?.privileges ?: READONLY

    fun roleName(roleKey: String): String =
        ROLES.firstOrNull { it.id == roleKey }?.name ?: roleKey

    fun isSystemRole(roleKey: String): Boolean =
        ROLES.any { it.id == roleKey }

    /** 所有领域动作的并集，供校验 "action 是否合法" 使用 */
    fun validAction(area: String, action: String): Boolean =
        AREAS[area]?.contains(action) == true
}
