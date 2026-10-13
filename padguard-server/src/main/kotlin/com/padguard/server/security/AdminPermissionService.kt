package com.padguard.server.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.AdminRoles
import com.padguard.server.domain.AdminRoles.RoleDef
import com.padguard.server.domain.CustomAdminRole
import com.padguard.server.repository.AdminRepository
import com.padguard.server.repository.AdminRoleOverrideRepository
import com.padguard.server.repository.CustomAdminRoleRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 后台 RBAC 权限闸门（area:action 动词模型）。
 *
 * 设计原则与 [com.padguard.server.security.SubscriptionInterceptor] 一致：**真正的闸门必须在服务端**，
 * 前端只做 UI 隐藏。任何写操作（冻结账号、退款、改角色、改设置）在 [require] 处统一校验，
 * 越权请求直接抛 FORBIDDEN，绝不落到业务层。
 *
 * 权限来源分两层：
 * 1. 代码常量 [AdminRoles.ROLES] 定义每个角色的基础权限（权威默认值，不漂移）；
 * 2. [com.padguard.server.domain.AdminRoleOverride] 持久化运营在后台勾选调整的权限（按 role_key 唯一）。
 *    解析时 override 优先；JSON 损坏则回退默认，保证权限判定永不为 null / 崩溃。
 */
@Service
class AdminPermissionService(
    private val adminRepository: AdminRepository,
    private val overrideRepository: AdminRoleOverrideRepository,
    private val customRoleRepository: CustomAdminRoleRepository,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(AdminPermissionService::class.java)

    /**
     * 解析某角色的有效权限，优先级：override > 自定义角色基线 > 内置角色默认值。
     *
     * 三层而非两层的原因：内置角色是代码常量（权威默认），自定义角色是运营新建（落库），
     * override 是运营在矩阵里的后续微调。任何一层 JSON 损坏都回退到下一层，
     * 保证权限判定永远是"确定的矩阵"，不会因脏数据崩溃或凭空放大权限。
     */
    fun rolePrivileges(roleKey: String): Map<String, List<String>> {
        val override = runCatching { overrideRepository.findByRoleKey(roleKey) }.getOrNull()
        if (override != null) {
            val parsed = runCatching {
                objectMapper.readValue(override.privilegesJson, Map::class.java) as Map<String, Any?>
            }.getOrNull()
            if (parsed != null) return normalize(parsed)
            log.warn("角色权限 override 解析失败，回退下一层: role={}", roleKey)
        }
        return basePrivileges(roleKey)
    }

    /** 角色基线权限：自定义角色取库中基线，否则取内置默认值 */
    private fun basePrivileges(roleKey: String): Map<String, List<String>> {
        val custom = runCatching { customRoleRepository.findById(roleKey).orElse(null) }.getOrNull()
        if (custom != null) {
            val parsed = runCatching {
                objectMapper.readValue(custom.privilegesJson, Map::class.java) as Map<String, Any?>
            }.getOrNull()
            if (parsed != null) return normalize(parsed)
            log.warn("自定义角色权限解析失败，回退默认值: role={}", roleKey)
        }
        return AdminRoles.defaultPrivileges(roleKey)
    }

    /** 规范化：只保留合法领域，动作转成字符串列表并去重 */
    private fun normalize(raw: Map<String, Any?>): Map<String, List<String>> =
        raw.entries.filter { AdminRoles.AREAS.containsKey(it.key) }.associate { (area, v) ->
            area to when (v) {
                is List<*> -> v.filterIsInstance<String>().distinct()
                is String -> listOf(v)
                else -> emptyList()
            }
        }

    /** 角色是否拥有某 area:action */
    fun hasPrivilege(roleKey: String, area: String, action: String): Boolean {
        if (!AdminRoles.AREAS.containsKey(area)) return false
        return rolePrivileges(roleKey)[area]?.contains(action) == true
    }

    /** 管理员是否拥有某权限（取该管理员的角色判定） */
    fun can(adminId: String, area: String, action: String): Boolean {
        val admin = adminRepository.findById(adminId).orElse(null) ?: return false
        if (admin.status != "active") return false
        return hasPrivilege(admin.role, area, action)
    }

    /**
     * 校验权限，不足则抛 FORBIDDEN。所有后台写操作在 Service 层调用前统一经过它。
     * super 角色拥有全部权限（area:action 全集），这里 hasPrivilege 已天然满足。
     */
    fun require(adminId: String, area: String, action: String) {
        if (!can(adminId, area, action)) {
            throw BizException(ParentErr.FORBIDDEN, "无权限执行该操作（需要 $area:$action）", Audience.PARENT)
        }
    }

    /**
     * 列出全部角色（内置 + 自定义，含生效权限、成员数与是否已被调整），
     * 供前端 RBAC 矩阵渲染、指派下拉与删除前校验使用。
     *
     * 成员数的作用是让前端在删除前就能给出"该角色下还有 N 个管理员"的明确提示，
     * 而不是等服务端拒绝后运营才知道原因。
     */
    fun listRoles(): List<RoleDef> {
        val members = roleMemberCounts()
        val builtin = AdminRoles.ROLES.map { def ->
            def.copy(
                privileges = rolePrivileges(def.id),
                memberCount = members[def.id] ?: 0,
                overridden = hasOverride(def.id)
            )
        }
        val custom = runCatching { customRoleRepository.findAll() }.getOrNull().orEmpty()
            .filter { !AdminRoles.ROLES.any { b -> b.id == it.id } }   // 同名内置角色以内置为准，避免重复行
            .map { r ->
                RoleDef(
                    id = r.id,
                    name = r.name,
                    desc = r.description ?: "自定义角色",
                    isSystem = r.isSystem,
                    privileges = rolePrivileges(r.id),
                    memberCount = members[r.id] ?: 0,
                    overridden = hasOverride(r.id)
                )
            }
        return builtin + custom
    }

    /** 各角色下的管理员数量 */
    private fun roleMemberCounts(): Map<String, Int> =
        runCatching { adminRepository.findAll() }.getOrNull().orEmpty()
            .groupingBy { it.role }.eachCount()

    private fun hasOverride(roleKey: String): Boolean =
        runCatching { overrideRepository.findByRoleKey(roleKey) != null }.getOrDefault(false)

    /**
     * 权限元数据（领域 + 动作的中文名与危险等级），下发给前端渲染矩阵。
     *
     * 前端不再自带白名单副本，从根上避免"前端有列、服务端不认"的静默丢弃。
     */
    fun permissionSchema(): Map<String, Any> = mapOf(
        "areas" to AdminRoles.AREA_DEFS.map { area ->
            mapOf(
                "key" to area.key,
                "name" to area.name,
                "actions" to area.actions.map { a ->
                    mapOf("key" to a.key, "name" to a.name, "danger" to a.danger)
                }
            )
        },
        // 前端用它判断哪些复选框是"系统保留、不可取消勾选"
        "lockedRoles" to AdminRoles.LOCKED_ROLES.toList()
    )

    /** 可被指派的角色键全集（内置 + 自定义），供创建/编辑管理员时校验 roleId 合法性 */
    fun allRoleKeys(): Set<String> =
        AdminRoles.ROLES.map { it.id }.toSet() +
            runCatching { customRoleRepository.findAll() }.getOrNull().orEmpty().map { it.id }.toSet()

    /** 角色是否可被删除：内置角色与仍有成员的角色不可删 */
    fun isRoleDeletable(roleKey: String): Boolean {
        if (AdminRoles.ROLES.any { it.id == roleKey }) return false
        val custom = runCatching { customRoleRepository.findById(roleKey).orElse(null) }.getOrNull() ?: return false
        if (custom.isSystem) return false
        val inUse = runCatching { adminRepository.findAll().any { it.role == roleKey } }.getOrDefault(true)
        return !inUse
    }

    /** 新建自定义角色（运营自助扩展权限体系，落库后即刻可用于指派） */
    fun createRole(id: String, name: String, description: String?, privileges: Map<String, List<String>>) {
        if (AdminRoles.ROLES.any { it.id == id }) {
            throw BizException(ParentErr.PARAM_ERROR, "角色标识与内置角色冲突：$id", Audience.PARENT)
        }
        if (runCatching { customRoleRepository.findById(id).orElse(null) }.getOrNull() != null) {
            throw BizException(ParentErr.PARAM_ERROR, "角色标识已存在：$id", Audience.PARENT)
        }
        val now = System.currentTimeMillis()
        customRoleRepository.save(
            CustomAdminRole(
                id = id,
                name = name,
                description = description,
                isSystem = false,
                privilegesJson = objectMapper.writeValueAsString(sanitize(privileges)),
                createdAt = now,
                updatedAt = now
            )
        )
        log.info("自定义角色已创建: role={} name={}", id, name)
    }

    /** 删除自定义角色；内置角色 / 有成员的角色会被拒绝，避免制造孤儿管理员 */
    fun deleteRole(roleKey: String) {
        if (AdminRoles.ROLES.any { it.id == roleKey }) {
            throw BizException(ParentErr.FORBIDDEN, "内置角色不可删除", Audience.PARENT)
        }
        val custom = runCatching { customRoleRepository.findById(roleKey).orElse(null) }.getOrNull()
            ?: throw BizException(ParentErr.PARAM_ERROR, "角色不存在", Audience.PARENT)
        if (custom.isSystem) throw BizException(ParentErr.FORBIDDEN, "内置角色不可删除", Audience.PARENT)
        if (runCatching { adminRepository.findAll().any { it.role == roleKey } }.getOrDefault(true)) {
            throw BizException(ParentErr.PARAM_ERROR, "该角色下仍有管理员，请先改派角色", Audience.PARENT)
        }
        overrideRepository.findByRoleKey(roleKey)?.let { overrideRepository.delete(it) }
        customRoleRepository.delete(custom)
        log.info("自定义角色已删除: role={}", roleKey)
    }

    /** 只保留合法领域与合法动作，杜绝写入不存在的权限项 */
    private fun sanitize(privileges: Map<String, List<String>>): Map<String, List<String>> =
        privileges.entries
            .filter { AdminRoles.AREAS.containsKey(it.key) }
            .associate { (area, acts) ->
                area to acts.filter { a -> AdminRoles.AREAS[area]?.contains(a) == true }.distinct()
            }

    /** 保存角色权限覆盖（运营在后台勾选后持久化） */
    fun saveRolePrivileges(roleKey: String, privileges: Map<String, List<String>>) {
        if (roleKey in AdminRoles.LOCKED_ROLES) {
            throw BizException(
                ParentErr.FORBIDDEN,
                "「${AdminRoles.roleName(roleKey)}」权限为系统锁定，不可编辑", Audience.PARENT
            )
        }
        if (roleKey !in allRoleKeys()) {
            throw BizException(ParentErr.PARAM_ERROR, "角色不存在，无法保存权限：$roleKey", Audience.PARENT)
        }
        // 检查并提交本题：read 是其余动作的前提，没有 read 的角色在服务端读接口就会被拦，
        // 却仍能执行写操作（写接口不校验 read）—— 这种"能改不能看"的组合对运营是陷阱，在保存前置拦截。
        requireReadWhenHavingWrites(roleKey, privileges)

        // 只保留合法领域与合法动作，避免越权写入不存在的领域/动作
        val json = objectMapper.writeValueAsString(sanitize(privileges))
        val existing = overrideRepository.findByRoleKey(roleKey)
        if (existing != null) {
            existing.privilegesJson = json
            overrideRepository.save(existing)
        } else {
            overrideRepository.save(
                com.padguard.server.domain.AdminRoleOverride(
                    id = "aro_" + roleKey,
                    roleKey = roleKey,
                    privilegesJson = json
                )
            )
        }
        log.info("角色权限已保存: role={}", roleKey)
    }

    /**
     * 还原某角色的权限到系统默认值（清除 override）。
     *
     * 存在的意义：内置角色一旦在矩阵里被改乱（比如误取消了某个读权限导致菜单打不开），
     * 早期没有任何自助恢复手段，只能改代码或改数据库。现在运营可一键回滚。
     */
    fun resetRolePrivileges(roleKey: String) {
        if (roleKey in AdminRoles.LOCKED_ROLES) {
            throw BizException(ParentErr.FORBIDDEN, "「${AdminRoles.roleName(roleKey)}」为系统锁定角色，无需还原", Audience.PARENT)
        }
        if (roleKey !in allRoleKeys()) {
            throw BizException(ParentErr.PARAM_ERROR, "角色不存在：$roleKey", Audience.PARENT)
        }
        val removed = overrideRepository.findByRoleKey(roleKey)?.let {
            overrideRepository.delete(it)
            true
        } ?: false
        log.info("角色权限已还原默认: role={} hadOverride={}", roleKey, removed)
    }

    /**
     * 校验"有写权限就必须有读权限"。
     *
     * 服务端写接口（如 parents:suspend）不校验 parents:read，因此若只勾 write 不勾 read，
     * 运营会陷入"列表打不开、但直接调接口又能成功"的诡异状态。
     * 在保存时前置拦截，把矛盾挡在配置阶段而不是运行阶段。
     */
    private fun requireReadWhenHavingWrites(roleKey: String, privileges: Map<String, List<String>>) {
        val bad = privileges.entries
            .filter { (area, acts) -> acts.any { it != "read" } && !acts.contains("read") }
            .map { (area, _) -> AdminRoles.AREA_DEFS.firstOrNull { it.key == area }?.name ?: area }
        if (bad.isNotEmpty()) {
            throw BizException(
                ParentErr.PARAM_ERROR,
                "配置了写权限但未配置「查看」权限的领域：${bad.joinToString("、")}。" +
                    "请至少保留查看权限，否则该角色将无法打开对应页面。",
                Audience.PARENT
            )
        }
        // roleKey 仅用于日志上下文，避免误删导致未使用告警
        log.debug("保存角色权限 read 前置校验通过: role={}", roleKey)
    }
}
