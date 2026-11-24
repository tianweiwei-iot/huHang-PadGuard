package com.padguard.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.Admin
import com.padguard.server.dto.AdminCreateRequest
import com.padguard.server.dto.AdminDto
import com.padguard.server.dto.AdminRoleCreateRequest
import com.padguard.server.dto.AdminRolePrivilegesDto
import com.padguard.server.dto.AdminUpdateAdminRequest
import com.padguard.server.repository.AdminRepository
import com.padguard.server.repository.SystemSettingRepository
import com.padguard.server.security.AdminPermissionService
import com.padguard.server.security.Passwords
import com.padguard.server.service.AdminAudit.RBAC_CREATE
import com.padguard.server.service.AdminAudit.RBAC_RESET_PWD
import com.padguard.server.service.AdminAudit.SETTINGS_WRITE
import com.padguard.server.service.MailService
import com.padguard.server.service.AuditService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*

class AdminRbacServiceTest {

    private lateinit var repo: AdminRepository
    private lateinit var perm: AdminPermissionService
    private lateinit var audit: AuditService
    private lateinit var mail: MailService
    private lateinit var sys: SystemSettingRepository
    private lateinit var svc: AdminRbacService

    @BeforeEach
    fun setup() {
        repo = mock(AdminRepository::class.java)
        perm = mock(AdminPermissionService::class.java)
        audit = mock(AuditService::class.java)
        mail = mock(MailService::class.java)
        sys = mock(SystemSettingRepository::class.java)
        svc = AdminRbacService(repo, perm, audit, mail, sys, ObjectMapper())
        // 默认成功路径：require 放行、角色权限/角色键可用、genPassword 回落默认、邮件通知成功
        `when`(perm.rolePrivileges(anyString())).thenReturn(mapOf("rbac" to listOf("read")))
        `when`(perm.allRoleKeys()).thenReturn(setOf("super", "ops", "cs", "finance", "audit", "rbac_custom"))
        `when`(perm.listRoles()).thenReturn(emptyList())
        `when`(sys.findById(anyString())).thenReturn(java.util.Optional.empty())
        `when`(mail.sendPasswordReset(anyString(), anyString(), anyString(), anyString())).thenReturn(true)
    }

    // ---------------- createAdmin ----------------

    @Test
    fun createAdmin_existingUsername_rejected() {
        `when`(repo.existsByUsername(anyString())).thenReturn(true)
        val ex = assertThrows(BizException::class.java) {
            svc.createAdmin("actor", AdminCreateRequest(username = "x", roleId = "ops"))
        }
        assertEquals(ParentErr.PARAM_ERROR, ex.code)
    }

    @Test
    fun createAdmin_unknownRole_rejected() {
        `when`(repo.existsByUsername(anyString())).thenReturn(false)
        `when`(repo.findById(eq("actor") ?: "actor")).thenReturn(java.util.Optional.of(Admin(id = "actor", role = "super")))
        val ex = assertThrows(BizException::class.java) {
            svc.createAdmin("actor", AdminCreateRequest(username = "x", roleId = "ghost"))
        }
        assertEquals(ParentErr.PARAM_ERROR, ex.code)
    }

    @Test
    fun createAdmin_escalationToSuper_blocked() {
        `when`(repo.existsByUsername(anyString())).thenReturn(false)
        `when`(repo.findById(eq("actor") ?: "actor")).thenReturn(java.util.Optional.of(Admin(id = "actor", role = "ops")))
        val ex = assertThrows(BizException::class.java) {
            svc.createAdmin("actor", AdminCreateRequest(username = "x", roleId = "super"))
        }
        assertEquals(ParentErr.FORBIDDEN, ex.code)
    }

    @Test
    fun createAdmin_success_savesAndAudits() {
        `when`(repo.existsByUsername(anyString())).thenReturn(false)
        `when`(repo.findById(eq("actor") ?: "actor")).thenReturn(java.util.Optional.of(Admin(id = "actor", role = "super")))
        `when`(repo.save(any(Admin::class.java))).thenAnswer { it.arguments[0] }
        val res = svc.createAdmin("actor", AdminCreateRequest(username = "newadmin", name = "New", email = "a@b.c", roleId = "ops"))
        assertTrue(res["ok"] as Boolean)
        val created = res["admin"] as AdminDto
        assertEquals("newadmin", created.username)
        assertNotNull(res["password"])
        verify(repo).save(any(Admin::class.java) ?: Admin())
        verify(audit).record(
            eq("actor") ?: "actor", any(), eq(RBAC_CREATE) ?: RBAC_CREATE,
            any(), any(), any(), any() ?: "SUCCESS", any()
        )
    }

    @Test
    fun createRole_success() {
        val req = AdminRoleCreateRequest(id = "custom1", name = "自定义", privileges = mapOf("rbac" to listOf("read")))
        val res = svc.createRole("actor", req)
        assertTrue(res["ok"] as Boolean)
        verify(perm).createRole(eq("custom1") ?: "custom1", anyString() ?: "", any() ?: "", any() ?: emptyMap())
        verify(audit).record(
            eq("actor") ?: "actor", any(), eq("RBAC_ROLE_CREATE") ?: "RBAC_ROLE_CREATE",
            any(), any(), any(), any() ?: "SUCCESS", any()
        )
    }

    @Test
    fun `RBAC 权限结构变更必须经 super 闸门 防止委托后自我提权`() {
        // rbac:write / rbac:assign 可被委托给 ops；若不额外要求 super，
        // ops 可把 ops 角色权限改成全量或直接自授全权限自定义角色，等价提权的 super。
        svc.createRole("actor", AdminRoleCreateRequest(id = "custom2", name = "x", privileges = emptyMap()))
        svc.saveRole("actor", AdminRolePrivilegesDto(id = "ops", privileges = emptyMap()))
        svc.resetRolePrivileges("actor", "ops")
        svc.deleteRole("actor", "custom2")
        verify(perm, times(4)).requireSuper(eq("actor") ?: "actor")
    }

    @Test
    fun saveRole_success() {
        val dto = AdminRolePrivilegesDto(id = "ops", privileges = mapOf("rbac" to listOf("read")))
        val res = svc.saveRole("actor", dto)
        assertTrue(res["ok"] as Boolean)
        verify(perm).saveRolePrivileges(eq("ops") ?: "ops", any() ?: emptyMap())
        verify(audit).record(
            eq("actor") ?: "actor", any(), eq("RBAC_ROLE_SAVE") ?: "RBAC_ROLE_SAVE",
            any(), any(), any(), any() ?: "SUCCESS", any()
        )
    }

    // ---------------- deleteAdmin ----------------

    @Test
    fun deleteAdmin_self_rejected() {
        val ex = assertThrows(BizException::class.java) { svc.deleteAdmin("actor", "actor") }
        assertEquals(ParentErr.PARAM_ERROR, ex.code)
    }

    @Test
    fun deleteAdmin_lastSuper_blocked() {
        `when`(repo.findById(eq("actor") ?: "actor")).thenReturn(java.util.Optional.of(Admin(id = "actor", role = "super")))
        val target = Admin(id = "t", role = "super", status = "active")
        `when`(repo.findById(eq("t") ?: "t")).thenReturn(java.util.Optional.of(target))
        `when`(repo.findAll()).thenReturn(listOf(target))
        val ex = assertThrows(BizException::class.java) { svc.deleteAdmin("actor", "t") }
        assertEquals(ParentErr.FORBIDDEN, ex.code)
    }

    @Test
    fun deleteAdmin_cannotManageSuperByNonSuper() {
        `when`(repo.findById(eq("actor") ?: "actor")).thenReturn(java.util.Optional.of(Admin(id = "actor", role = "ops")))
        val target = Admin(id = "t", role = "super")
        `when`(repo.findById(eq("t") ?: "t")).thenReturn(java.util.Optional.of(target))
        val ex = assertThrows(BizException::class.java) { svc.deleteAdmin("actor", "t") }
        assertEquals(ParentErr.FORBIDDEN, ex.code)
    }

    @Test
    fun deleteAdmin_success() {
        `when`(repo.findById(eq("actor") ?: "actor")).thenReturn(java.util.Optional.of(Admin(id = "actor", role = "super")))
        val target = Admin(id = "t", role = "ops")
        `when`(repo.findById(eq("t") ?: "t")).thenReturn(java.util.Optional.of(target))
        `when`(repo.findAll()).thenReturn(listOf(target, Admin(id = "actor", role = "super")))
        val res = svc.deleteAdmin("actor", "t")
        assertTrue(res["ok"] as Boolean)
        verify(repo).delete(target)
    }

    // ---------------- updateAdmin ----------------

    @Test
    fun updateAdmin_invalidStatus_rejected() {
        `when`(repo.findById(eq("actor") ?: "actor")).thenReturn(java.util.Optional.of(Admin(id = "actor", role = "super")))
        val target = Admin(id = "t", role = "ops", status = "active")
        `when`(repo.findByIdForUpdate(eq("t") ?: "t")).thenReturn(target)
        val ex = assertThrows(BizException::class.java) {
            svc.updateAdmin("actor", "t", AdminUpdateAdminRequest(status = "weird"))
        }
        assertEquals(ParentErr.PARAM_ERROR, ex.code)
    }

    @Test
    fun updateAdmin_disableLastSuper_blocked() {
        `when`(repo.findById(eq("actor") ?: "actor")).thenReturn(java.util.Optional.of(Admin(id = "actor", role = "super")))
        val target = Admin(id = "t", role = "super", status = "active")
        `when`(repo.findByIdForUpdate(eq("t") ?: "t")).thenReturn(target)
        `when`(repo.findAll()).thenReturn(listOf(target))
        val ex = assertThrows(BizException::class.java) {
            svc.updateAdmin("actor", "t", AdminUpdateAdminRequest(status = "disabled"))
        }
        assertEquals(ParentErr.FORBIDDEN, ex.code)
    }

    @Test
    fun updateAdmin_partialEmail_noNullOverwrite() {
        `when`(repo.findById(eq("actor") ?: "actor")).thenReturn(java.util.Optional.of(Admin(id = "actor", role = "super")))
        val target = Admin(id = "t", role = "ops", status = "active", email = "old@x.com")
        `when`(repo.findByIdForUpdate(eq("t") ?: "t")).thenReturn(target)
        `when`(repo.save(any(Admin::class.java))).thenAnswer { it.arguments[0] }
        val res = svc.updateAdmin("actor", "t", AdminUpdateAdminRequest(email = "new@x.com"))
        assertTrue(res["ok"] as Boolean)
        assertEquals("new@x.com", target.email)
        assertEquals("active", target.status) // status 未传，必须保持原值，绝不可被写成 null
        verify(repo).save(target)
    }

    // ---------------- resetAdminPassword ----------------

    @Test
    fun resetAdminPassword_success_revokesTokensAndMails() {
        `when`(repo.findById(eq("actor") ?: "actor")).thenReturn(java.util.Optional.of(Admin(id = "actor", role = "super")))
        val target = Admin(id = "t", role = "ops", status = "active", passwordHash = Passwords.encode("old"), email = "a@b.c")
        val before = target.tokenVersion
        `when`(repo.findByIdForUpdate(eq("t") ?: "t")).thenReturn(target)
        `when`(repo.save(any(Admin::class.java))).thenAnswer { it.arguments[0] }
        val res = svc.resetAdminPassword("actor", "t")
        assertTrue(res["ok"] as Boolean)
        val newPwd = res["newPwd"] as String
        assertTrue(Passwords.matches(newPwd, target.passwordHash))
        assertEquals(before + 1, target.tokenVersion) // 旧令牌立即作废
        verify(mail).sendPasswordReset(anyString() ?: "", anyString() ?: "", anyString() ?: "", anyString() ?: "")
        verify(audit).record(
            eq("actor") ?: "actor", any(), eq(RBAC_RESET_PWD) ?: RBAC_RESET_PWD,
            any(), any(), any(), any() ?: "SUCCESS", any()
        )
    }

    @Test
    fun resetAdminPassword_nonSuperTargetingSuper_rejected() {
        // 前提：super 把 rbac:reset_pwd 授给了 ops。权限点放行，但跨级护栏必须拦下。
        `when`(repo.findById(eq("actor") ?: "actor"))
            .thenReturn(java.util.Optional.of(Admin(id = "actor", role = "ops")))
        val target = Admin(id = "super-1", role = "super", status = "active", passwordHash = Passwords.encode("old"))
        `when`(repo.findByIdForUpdate(eq("super-1") ?: "super-1")).thenReturn(target)

        val ex = assertThrows(BizException::class.java) { svc.resetAdminPassword("actor", "super-1") }

        assertEquals(ParentErr.FORBIDDEN, ex.code)
        assertTrue(Passwords.matches("old", target.passwordHash), "被拒绝后不得改超管密码")
        verify(repo, never()).save(any(Admin::class.java) ?: Admin())
        verify(mail, never()).sendPasswordReset(anyString() ?: "", anyString() ?: "", anyString() ?: "", anyString() ?: "")
    }

    // ---------------- changeOwnPassword ----------------

    @Test
    fun changeOwnPassword_tooShort_rejected() {
        val ex = assertThrows(BizException::class.java) { svc.changeOwnPassword("actor", "old", "short") }
        assertEquals(ParentErr.PARAM_ERROR, ex.code)
    }

    @Test
    fun changeOwnPassword_wrongCurrent_rejected() {
        `when`(repo.findByIdForUpdate(eq("actor") ?: "actor")).thenReturn(Admin(id = "actor", passwordHash = Passwords.encode("real")))
        val ex = assertThrows(BizException::class.java) { svc.changeOwnPassword("actor", "wrong", "newpassword123") }
        assertEquals(ParentErr.UNAUTHORIZED, ex.code)
    }

    @Test
    fun changeOwnPassword_success_revokesOwnTokens() {
        val admin = Admin(id = "actor", passwordHash = Passwords.encode("real"))
        val before = admin.tokenVersion
        // 行锁读：改密整行 save 写回 tokenVersion，必须与 logout/login 并发串行化
        `when`(repo.findByIdForUpdate(eq("actor") ?: "actor")).thenReturn(admin)
        `when`(repo.save(any(Admin::class.java))).thenAnswer { it.arguments[0] }
        val res = svc.changeOwnPassword("actor", "real", "newpassword123")
        assertTrue(res["ok"] as Boolean)
        assertTrue(Passwords.matches("newpassword123", admin.passwordHash))
        assertEquals(before + 1, admin.tokenVersion)
        verify(audit).record(
            eq("actor") ?: "actor", any(), eq("RBAC_SELF_CHG_PWD") ?: "RBAC_SELF_CHG_PWD",
            any(), any(), any(), any() ?: "SUCCESS", any()
        )
    }

    // ---------------- assignRole ----------------

    @Test
    fun assignRole_unknownRole_rejected() {
        `when`(perm.allRoleKeys()).thenReturn(setOf("super", "ops"))
        val ex = assertThrows(BizException::class.java) { svc.assignRole("actor", "t", "ghost") }
        assertEquals(ParentErr.PARAM_ERROR, ex.code)
    }

    @Test
    fun assignRole_cannotAssignSuperByNonSuper() {
        `when`(repo.findById(eq("actor") ?: "actor")).thenReturn(java.util.Optional.of(Admin(id = "actor", role = "ops")))
        `when`(perm.allRoleKeys()).thenReturn(setOf("super", "ops"))
        val ex = assertThrows(BizException::class.java) { svc.assignRole("actor", "t", "super") }
        assertEquals(ParentErr.FORBIDDEN, ex.code)
    }

    @Test
    fun assignRole_success() {
        `when`(repo.findById(eq("actor") ?: "actor")).thenReturn(java.util.Optional.of(Admin(id = "actor", role = "super")))
        `when`(perm.allRoleKeys()).thenReturn(setOf("super", "ops"))
        val target = Admin(id = "t", role = "ops")
        `when`(repo.findById(eq("t") ?: "t")).thenReturn(java.util.Optional.of(target))
        `when`(repo.save(any(Admin::class.java))).thenAnswer { it.arguments[0] }
        val res = svc.assignRole("actor", "t", "ops")
        assertTrue(res["ok"] as Boolean)
        assertEquals("ops", target.role)
        verify(repo).save(target)
    }

    // ---------------- read gates ----------------

    @Test
    fun listAdmins_requiresRead() {
        doThrow(BizException(ParentErr.FORBIDDEN, "no", Audience.PARENT)).`when`(perm).require(anyString(), anyString(), anyString())
        val ex = assertThrows(BizException::class.java) { svc.listAdmins("actor") }
        assertEquals(ParentErr.FORBIDDEN, ex.code)
    }

    @Test
    fun permissionSchema_requiresRead() {
        doThrow(BizException(ParentErr.FORBIDDEN, "no", Audience.PARENT)).`when`(perm).require(anyString(), anyString(), anyString())
        val ex = assertThrows(BizException::class.java) { svc.permissionSchema("actor") }
        assertEquals(ParentErr.FORBIDDEN, ex.code)
    }

    @Test
    fun listAdmins_success_mapsPrivileges() {
        `when`(repo.findAll()).thenReturn(listOf(Admin(id = "a", role = "ops"), Admin(id = "b", role = "super")))
        val list = svc.listAdmins("actor")
        assertEquals(2, list.size)
        verify(perm).rolePrivileges(eq("ops") ?: "ops")
        verify(perm).rolePrivileges(eq("super") ?: "super")
    }
}
