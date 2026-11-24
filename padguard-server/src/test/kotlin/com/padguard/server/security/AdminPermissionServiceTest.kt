package com.padguard.server.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.common.BizException
import com.padguard.server.domain.Admin
import com.padguard.server.domain.AdminRoleOverride
import com.padguard.server.domain.CustomAdminRole
import com.padguard.server.repository.AdminRepository
import com.padguard.server.repository.AdminRoleOverrideRepository
import com.padguard.server.repository.CustomAdminRoleRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import java.util.Optional

/**
 * `AdminPermissionService` 是后台 RBAC 的信任关键闸门（area:action 动词模型）：
 * 所有后台写操作在 Service 层经 [AdminPermissionService.require] 统一校验，越权请求直接拒。
 * 此前**零单测**。
 *
 * 覆盖：
 * - `rolePrivileges` 三层优先级（override > 自定义角色基线 > 内置默认）、非法领域被 normalize 过滤；
 * - `hasPrivilege` / `can`（账号状态非 active 直接 false）/ `require`（不足抛 FORBIDDEN）/ `requireSuper`（硬编码）；
 * - `saveRolePrivileges`：锁定角色（super）拒绝编辑、配置了写权限却未配置查看权限时前置拦截。
 *
 * 不加载 Spring 上下文，沿用既定 Mockito 占位范式（`any()` 仅用于可空参数）。
 */
class AdminPermissionServiceTest {
    private val adminRepository = mock(AdminRepository::class.java)
    private val overrideRepository = mock(AdminRoleOverrideRepository::class.java)
    private val customRoleRepository = mock(CustomAdminRoleRepository::class.java)
    private val objectMapper = ObjectMapper()

    private fun svc(): AdminPermissionService =
        AdminPermissionService(adminRepository, overrideRepository, customRoleRepository, objectMapper)

    @Test
    fun rolePrivileges_overrideTakesPrecedence() {
        `when`(overrideRepository.findByRoleKey("ops"))
            .thenReturn(AdminRoleOverride(roleKey = "ops", privilegesJson = """{"parents":["read","write"]}"""))
        val p = svc().rolePrivileges("ops")
        assertEquals(listOf("read", "write"), p["parents"])
        assertFalse(p.containsKey("devices"))
    }

    @Test
    fun rolePrivileges_customRoleFallbackWhenNoOverride() {
        `when`(overrideRepository.findByRoleKey("ops")).thenReturn(null)
        `when`(customRoleRepository.findById("ops")).thenReturn(Optional.of(
            CustomAdminRole(id = "ops", name = "ops", privilegesJson = """{"devices":["read"]}""")))
        val p = svc().rolePrivileges("ops")
        assertEquals(listOf("read"), p["devices"])
    }

    @Test
    fun rolePrivileges_defaultFallbackWhenNothingMatched() {
        `when`(overrideRepository.findByRoleKey("ops")).thenReturn(null)
        `when`(customRoleRepository.findById("ops")).thenReturn(Optional.empty())
        val p = svc().rolePrivileges("ops")
        // 内置 ops 默认 parents: read/write/create/suspend/delete
        assertEquals(listOf("read", "write", "create", "suspend", "delete"), p["parents"])
    }

    @Test
    fun rolePrivileges_unknownAreaFilteredOut() {
        `when`(overrideRepository.findByRoleKey("ops"))
            .thenReturn(AdminRoleOverride(roleKey = "ops", privilegesJson = """{"bogus":["x"],"parents":["read"]}"""))
        val p = svc().rolePrivileges("ops")
        assertEquals(setOf("parents"), p.keys)
    }

    @Test
    fun hasPrivilege_reflectsResolvedMatrix() {
        `when`(overrideRepository.findByRoleKey("ops")).thenReturn(null)
        `when`(customRoleRepository.findById("ops")).thenReturn(Optional.empty())
        assertTrue(svc().hasPrivilege("ops", "parents", "read"))
        assertFalse(svc().hasPrivilege("ops", "parents", "refund"))
    }

    @Test
    fun hasPrivilege_falseForUnknownArea() {
        assertFalse(svc().hasPrivilege("ops", "nope", "x"))
    }

    @Test
    fun can_falseWhenAdminInactive() {
        `when`(adminRepository.findById("a1")).thenReturn(Optional.of(Admin(id = "a1", status = "disabled", role = "ops")))
        assertFalse(svc().can("a1", "parents", "read"))
    }

    @Test
    fun can_trueWhenActiveAndPrivileged() {
        `when`(adminRepository.findById("a1")).thenReturn(Optional.of(Admin(id = "a1", status = "active", role = "ops")))
        assertTrue(svc().can("a1", "parents", "read"))
        assertFalse(svc().can("a1", "billing", "refund")) // ops 在 billing 仅 read
    }

    @Test
    fun require_throwsWhenNotAllowed() {
        `when`(adminRepository.findById("a1")).thenReturn(Optional.of(Admin(id = "a1", status = "active", role = "cs")))
        val ex = assertThrows(BizException::class.java) { svc().require("a1", "parents", "delete") }
        assertTrue(ex.message!!.contains("parents:delete"))
    }

    @Test
    fun requireSuper_blocksNonSuper() {
        `when`(adminRepository.findById("ops")).thenReturn(Optional.of(Admin(id = "ops", status = "active", role = "ops")))
        assertThrows(BizException::class.java) { svc().requireSuper("ops") }
        `when`(adminRepository.findById("sup")).thenReturn(Optional.of(Admin(id = "sup", status = "active", role = "super")))
        svc().requireSuper("sup") // super 不抛
    }

    @Test
    fun saveRolePrivileges_rejectsLockedRole() {
        assertThrows(BizException::class.java) {
            svc().saveRolePrivileges("super", mapOf("parents" to listOf("read")))
        }
    }

    @Test
    fun saveRolePrivileges_rejectsWriteWithoutRead() {
        assertThrows(BizException::class.java) {
            svc().saveRolePrivileges("ops", mapOf("parents" to listOf("write")))
        }
    }

    @Test
    fun saveRolePrivileges_persistsWhenValid() {
        `when`(overrideRepository.findByRoleKey("ops")).thenReturn(null)
        svc().saveRolePrivileges("ops", mapOf("parents" to listOf("read", "write")))
        verify(overrideRepository).save(any(AdminRoleOverride::class.java))
    }
}
