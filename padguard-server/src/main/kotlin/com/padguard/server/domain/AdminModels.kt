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
 * 后台自定义角色（运营自建，区别于 [AdminRoles.ROLES] 里的内置角色）。
 *
 * 存在必要性：后台「角色权限 → 新建角色」早期只在前端内存里 push 一条记录，
 * 刷新即丢，且服务端压根没有该角色 —— 管理员一旦被指派到它，权限解析为空（什么都干不了）。
 * 现在自定义角色落库（V24），[com.padguard.server.security.AdminPermissionService]
 * 会把内置角色与自定义角色合并后再做权限判定与矩阵渲染。
 *
 * 权限矩阵本身仍可走 [AdminRoleOverride] 覆盖：新建时写入 [privilegesJson] 作为基线，
 * 后续在矩阵里勾选的调整落在 override 上，两者优先级为 override > 本表 > 内置默认值。
 */
@Entity
@Table(name = "admin_roles")
class CustomAdminRole(
    @Id var id: String = "",
    @Column(name = "name", nullable = false) var name: String = "",
    @Column(name = "description") var description: String? = null,
    /** 内置角色镜像为 true（不可删除）；运营自建为 false */
    @Column(name = "is_system", nullable = false) var isSystem: Boolean = false,
    /** 权限矩阵 JSON：{ "area": ["action", ...], ... } */
    @Column(name = "privileges_json", columnDefinition = "text") var privilegesJson: String = "{}",
    @Column(name = "created_at") var createdAt: Long = 0,
    @Column(name = "updated_at") var updatedAt: Long = 0
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
    /**
     * 单个动作的定义（危险等级仅用于前端高亮与二次确认，不参与权限判定）。
     *
     * @property key   动作键，与 Service 层 `adminPerm.require(area, key)` 的实参逐字对应
     * @property name  中文名，下发前端渲染矩阵表头
     * @property danger 高危标记：删除 / 退款 / 解绑 / 冻结 / 重置密码等不可逆或直接影响用户资产的动作
     */
    data class ActionDef(val key: String, val name: String, val danger: Boolean = false)

    /** 领域定义：一组动作的集合 */
    data class AreaDef(val key: String, val name: String, val actions: List<ActionDef>)

    private fun act(key: String, name: String, danger: Boolean = false) = ActionDef(key, name, danger)

    /**
     * 领域元数据（中文名 + 动作定义），经 `GET /v1/admin/rbac/permissions` 下发前端。
     *
     * 前端矩阵**不再自带副本**，根除前后端白名单漂移：早期前端硬编码了一个服务端未登记的
     * `monitor` 领域，而服务端在 `sanitize()` 里把未知领域静默丢弃，
     * 运营勾选后存不进去、刷新又变回未勾选 —— 表现为"矩阵不可设置"。
     *
     * 维护约定：新增领域/动作只改这里 + Service 层 `adminPerm.require(...)` 的实参，
     * 前端矩阵自动出现对应行/列，无需改任何前端代码。
     */
    val AREA_DEFS: List<AreaDef> = listOf(
        AreaDef("parents", "家长账号", listOf(
            act("read", "查看"), act("write", "编辑"), act("create", "新建"),
            act("suspend", "冻结/启用", danger = true), act("reset_pwd", "重置密码", danger = true),
            act("delete", "删除", danger = true))),
        AreaDef("devices", "设备管理", listOf(
            act("read", "查看"), act("write", "编辑"),
            act("lock", "远程锁屏", danger = true), act("unlock", "解除锁屏"),
            act("unbind", "解绑设备", danger = true))),
        AreaDef("billing", "订阅计费", listOf(
            act("read", "查看"), act("write", "编辑"),
            act("refund", "退款", danger = true), act("manual", "手动开通", danger = true))),
        AreaDef("rbac", "角色权限", listOf(
            act("read", "查看"), act("write", "编辑角色"), act("assign", "变更角色", danger = true),
            act("reset_pwd", "重置密码", danger = true), act("delete", "删除管理员", danger = true))),
        AreaDef("server", "服务端", listOf(
            act("read", "查看监控"), act("write", "运维操作", danger = true))),
        AreaDef("audit", "审计告警", listOf(
            act("read", "查看日志"), act("write", "处理告警"))),
        AreaDef("settings", "系统设置", listOf(
            act("read", "查看"), act("write", "修改配置", danger = true)))
    )

    /**
     * 领域 -> 该领域下可用动作（**全系统唯一的权限白名单**）。
     *
     * 由 [AREA_DEFS] 单向派生，"允许哪些动作"因此只有一处真相；
     * 必须声明在 [AREA_DEFS] **之后** —— Kotlin object 按书写顺序初始化属性，
     * 写在前面会在类初始化时取到尚未赋值的 AREA_DEFS 而抛 NPE。
     */
    val AREAS: Map<String, List<String>> = AREA_DEFS.associate { area -> area.key to area.actions.map { it.key } }

    data class RoleDef(
        val id: String,
        val name: String,
        val desc: String,
        val isSystem: Boolean,
        val privileges: Map<String, List<String>>,
        /** 该角色下的管理员数量；>0 时禁止删除，避免制造孤儿管理员 */
        val memberCount: Int = 0,
        /** 是否存在持久化 override（true 表示已被运营调整过，可「还原默认」） */
        val overridden: Boolean = false
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

    /**
     * 权限被系统锁定、不允许在后台编辑的角色。
     *
     * 目前仅 `super`：它是系统最后的破窗账号，其全量权限由 [allOf] 固定。
     * 一旦允许收窄 super 的权限，超管会自锁、后台可能永久锁死且无人能解。
     *
     * 注意与 isSystem 区分：其余内置角色（ops/cs/finance/audit）**可以**被编辑，
     * 这正是 RBAC 的意义所在 —— 早期前端用 isSystem 一刀切全部 disable，
     * 导致四个最常用的内置角色矩阵全是灰的、"不可设置"。
     */
    val LOCKED_ROLES: Set<String> = setOf("super")

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
