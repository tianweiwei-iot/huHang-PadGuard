package com.padguard.server.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.AdminRoles
import com.padguard.server.domain.AdminRoles.RoleDef
import com.padguard.server.repository.AdminRepository
import com.padguard.server.repository.AdminRoleOverrideRepository
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
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(AdminPermissionService::class.java)

    /** 解析某角色的有效权限（override 覆盖默认） */
    fun rolePrivileges(roleKey: String): Map<String, List<String>> {
        val override = runCatching { overrideRepository.findByRoleKey(roleKey) }.getOrNull()
        if (override != null) {
            val parsed = runCatching {
                objectMapper.readValue(override.privilegesJson, Map::class.java) as Map<String, Any?>
            }.getOrNull()
            if (parsed != null) {
                // 规范化：只保留合法领域，动作转成字符串列表
                return parsed.entries.filter { AdminRoles.AREAS.containsKey(it.key) }.associate { (area, v) ->
                    area to when (v) {
                        is List<*> -> v.filterIsInstance<String>()
                        is String -> listOf(v)
                        else -> emptyList()
                    }
                }
            }
            log.warn("角色权限 override 解析失败，回退默认值: role={}", roleKey)
        }
        return AdminRoles.defaultPrivileges(roleKey)
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

    /** 列出全部角色（含生效权限），供前端 RBAC 矩阵渲染 */
    fun listRoles(): List<RoleDef> = AdminRoles.ROLES.map { def ->
        def.copy(privileges = rolePrivileges(def.id))
    }

    /** 保存角色权限覆盖（运营在后台勾选后持久化） */
    fun saveRolePrivileges(roleKey: String, privileges: Map<String, List<String>>) {
        // 超级管理员是系统最后的破窗账号，其全量权限由代码 allOf(AREAS) 固定，
        // 禁止经 override 覆盖——否则一旦保存一个被收窄的 super 权限，超管会自锁、后台可能锁死。
        if (roleKey == "super") {
            throw BizException(ParentErr.FORBIDDEN, "超级管理员权限为系统内置，不可编辑", Audience.PARENT)
        }
        // 只保留合法领域与合法动作，避免越权写入不存在的领域/动作
        val clean = privileges.entries
            .filter { AdminRoles.AREAS.containsKey(it.key) }
            .associate { (area, acts) ->
                area to acts.filter { a -> AdminRoles.AREAS[area]?.contains(a) == true }.distinct()
            }
        val json = objectMapper.writeValueAsString(clean)
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
}
