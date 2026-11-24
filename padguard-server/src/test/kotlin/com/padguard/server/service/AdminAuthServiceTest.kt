package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.Admin
import com.padguard.server.dto.AdminLoginResponse
import com.padguard.server.repository.AdminRepository
import com.padguard.server.security.AdminPermissionService
import com.padguard.server.security.JwtTokenProvider
import com.padguard.server.security.Passwords
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import java.util.*

class AdminAuthServiceTest {

    private val repo = mock(AdminRepository::class.java)
    private val jwt = mock(JwtTokenProvider::class.java)
    private val perm = mock(AdminPermissionService::class.java)
    private val svc = AdminAuthService(repo, jwt, perm)

    private fun admin(role: String = "super", status: String = "active", hash: String = Passwords.encode("secret")): Admin =
        Admin(id = "A1", username = "admin", passwordHash = hash, role = role, status = status)

    @Test
    fun login_unknownUsername_throwsUnauthorized() {
        `when`(repo.findByUsername(anyString())).thenReturn(null)
        val ex = assertThrows(BizException::class.java) { svc.login("ghost", "x") }
        assertEquals(ParentErr.UNAUTHORIZED, ex.code)
    }

    @Test
    fun login_disabledAccount_throwsUnauthorized() {
        `when`(repo.findByUsername(anyString())).thenReturn(admin(status = "disabled"))
        val ex = assertThrows(BizException::class.java) { svc.login("admin", "secret") }
        assertEquals(ParentErr.UNAUTHORIZED, ex.code)
    }

    @Test
    fun login_wrongPassword_throwsUnauthorized() {
        `when`(repo.findByUsername(anyString())).thenReturn(admin())
        val ex = assertThrows(BizException::class.java) { svc.login("admin", "wrong") }
        assertEquals(ParentErr.UNAUTHORIZED, ex.code)
    }

    @Test
    fun login_success_returnsTokenAndPrivilegedDto() {
        val a = admin()
        `when`(repo.findByUsername(anyString())).thenReturn(a)
        `when`(jwt.createAdminToken(anyString(), anyLong())).thenReturn("tok-xyz")
        `when`(perm.rolePrivileges(anyString())).thenReturn(mapOf("rbac" to listOf("read", "write")))

        val resp: AdminLoginResponse = svc.login("admin", "secret")

        assertEquals("tok-xyz", resp.token)
        assertEquals("A1", resp.admin.id)
        assertEquals("super", resp.admin.roleId)
        // 权限必须随登录响应下发，否则前端 RBAC 菜单全空（PG.can 恒 false）
        assertEquals(listOf("read", "write"), resp.admin.privileges["rbac"])
        // 登录即记录最后登录时间——但只走单列 UPDATE，不整行 save 覆盖并发写回的 tokenVersion
        verify(repo).touchLastLoginAt(eq("A1") ?: "A1", anyLong())
        verify(repo, never()).save(any(Admin::class.java))
    }

    @Test
    fun logout_unknownAdmin_returnsRevokedFalse() {
        `when`(repo.findByIdForUpdate(anyString())).thenReturn(null)
        val r = svc.logout("ghost")
        assertEquals(false, r["revoked"])
        verify(repo, never()).save(any(Admin::class.java))
    }

    @Test
    fun logout_existingAdmin_bumpsTokenVersionAndSaves() {
        val a = admin()
        val before = a.tokenVersion
        // 必须行锁读：无锁 findById + 整行 save 会与 login 的并发写互相覆盖版本号
        `when`(repo.findByIdForUpdate(anyString())).thenReturn(a)
        val r = svc.logout("A1")
        assertEquals(true, r["revoked"])
        assertEquals(before + 1, a.tokenVersion)
        verify(repo).findByIdForUpdate("A1")
        verify(repo).save(a)
    }
}
