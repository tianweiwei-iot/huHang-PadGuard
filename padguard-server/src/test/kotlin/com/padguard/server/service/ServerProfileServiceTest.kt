package com.padguard.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.SystemSetting
import com.padguard.server.dto.ServerProfileDto
import com.padguard.server.repository.SystemSettingRepository
import com.padguard.server.security.AdminPermissionService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.Optional

/**
 * [ServerProfileService] 单测：服务器基本信息配置。信任关键不变量：
 * ① 写操作必须由 [AdminPermissionService.require](server:write) 在服务端闸门拦截，前端隐藏形同虚设；
 * ② 参数校验（空 host / 端口越界 / 协议非法）入口即拒；
 * ③ 读路径未配置/JSON 损坏必须回落默认值，绝不抛错，更绝不编一个假地址返回；
 * ④ 公开地址优先级 env(PADGUARD_PUBLIC_HOST) > 后台保存值 > 请求 Host 头 > 空，
 *    且 Host 头须剥离端口（含 IPv6 字面量 `[::1]:8090`）。
 * 不加载 Spring 上下文：直接构造 + mock 依赖 + 真实 ObjectMapper。
 */
class ServerProfileServiceTest {

    private data class Fx(
        val svc: ServerProfileService,
        val repo: SystemSettingRepository,
        val adminPerm: AdminPermissionService
    )

    private fun fx(configuredHost: String = ""): Fx {
        val repo = mock(SystemSettingRepository::class.java)
        val adminPerm = mock(AdminPermissionService::class.java)
        val svc = ServerProfileService(repo, adminPerm, ObjectMapper(), configuredHost)
        `when`(repo.findById(any() ?: "")).thenReturn(Optional.empty())
        `when`(repo.save(any(SystemSetting::class.java) ?: SystemSetting("", ""))).thenAnswer { it.arguments[0] }
        return Fx(svc, repo, adminPerm)
    }

    @Test
    fun getServerProfileReturnsDefaultsWhenUnset() {
        val (svc, _, _) = fx()
        val dto = svc.getServerProfile()
        assertEquals(8090, dto.port)
        assertEquals("http", dto.scheme)
        assertEquals("PadGuard 管控服务端", dto.serverName)
        assertEquals("", dto.publicHost)
    }

    @Test
    fun getServerProfileFallsBackOnMalformedJson() {
        val (svc, repo, _) = fx()
        `when`(repo.findById(any() ?: "")).thenReturn(Optional.of(SystemSetting(ServerProfileService.KEY, "{bad json")))
        val dto = svc.getServerProfile()
        assertEquals(8090, dto.port)
        assertEquals("", dto.publicHost)
    }

    @Test
    fun getServerProfileParsesSavedJson() {
        val (svc, repo, _) = fx()
        val saved = ServerProfileDto("h.example.com", 443, "https", "My Server", "r", 123L)
        `when`(repo.findById(any() ?: "")).thenReturn(
            Optional.of(SystemSetting(ServerProfileService.KEY, ObjectMapper().writeValueAsString(saved)))
        )
        val dto = svc.getServerProfile()
        assertEquals("h.example.com", dto.publicHost)
        assertEquals(443, dto.port)
        assertEquals("https", dto.scheme)
        assertEquals("My Server", dto.serverName)
        assertEquals(123L, dto.updatedAt)
    }

    @Test
    fun saveRequiresServerWritePermission() {
        val fx = fx()
        doThrow(BizException(ParentErr.FORBIDDEN, "forbidden", Audience.PARENT))
            .`when`(fx.adminPerm).require(any() ?: "", any() ?: "", any() ?: "")
        val ex = assertThrows(BizException::class.java) {
            fx.svc.saveServerProfile("admin1", ServerProfileDto("h", 8090, "http", "n"))
        }
        assertEquals(ParentErr.FORBIDDEN, ex.code)
        verify(fx.repo, never()).save(any(SystemSetting::class.java) ?: SystemSetting("", ""))
    }

    @Test
    fun saveRejectsBlankHost() {
        val fx = fx()
        val ex = assertThrows(BizException::class.java) {
            fx.svc.saveServerProfile("admin1", ServerProfileDto("", 8090, "http", "n"))
        }
        assertEquals(ParentErr.PARAM_ERROR, ex.code)
    }

    @Test
    fun saveRejectsPortOutOfRange() {
        val fx = fx()
        assertEquals(
            ParentErr.PARAM_ERROR,
            assertThrows(BizException::class.java) {
                fx.svc.saveServerProfile("admin1", ServerProfileDto("h", 0, "http", "n"))
            }.code
        )
        assertEquals(
            ParentErr.PARAM_ERROR,
            assertThrows(BizException::class.java) {
                fx.svc.saveServerProfile("admin1", ServerProfileDto("h", 65536, "http", "n"))
            }.code
        )
    }

    @Test
    fun saveRejectsBadScheme() {
        val fx = fx()
        val ex = assertThrows(BizException::class.java) {
            fx.svc.saveServerProfile("admin1", ServerProfileDto("h", 8090, "ftp", "n"))
        }
        assertEquals(ParentErr.PARAM_ERROR, ex.code)
    }

    @Test
    fun saveTrimsAndPersists() {
        val fx = fx()
        fx.svc.saveServerProfile("admin1", ServerProfileDto(" H.EXAMPLE.COM ", 8443, "HTTPS", "Name"))
        verify(fx.adminPerm).require(eq("admin1") ?: "admin1", eq("server") ?: "server", eq("write") ?: "write")
        val captor = ArgumentCaptor.forClass(SystemSetting::class.java)
        verify(fx.repo).save(captor.capture())
        val saved = ObjectMapper().readValue(captor.value.valueJson, ServerProfileDto::class.java)
        assertEquals("H.EXAMPLE.COM", saved.publicHost)
        assertEquals("https", saved.scheme)
        assertEquals(8443, saved.port)
        assertNotNull(saved.updatedAt)
    }

    @Test
    fun getPublicInfoNoHostReturnsEmpty() {
        val (svc, _, _) = fx()
        assertEquals("", svc.getPublicInfo(null).publicHost)
    }

    @Test
    fun getPublicInfoEnvHostWins() {
        val (svc, repo, _) = fx(configuredHost = "env.host.com")
        `when`(repo.findById(any() ?: "")).thenReturn(
            Optional.of(
                SystemSetting(
                    ServerProfileService.KEY,
                    ObjectMapper().writeValueAsString(ServerProfileDto("saved.host.com", 8090, "http", "n"))
                )
            )
        )
        assertEquals("env.host.com", svc.getPublicInfo("req.host.com").publicHost)
    }

    @Test
    fun getPublicInfoSavedHostWhenEnvBlank() {
        val (svc, repo, _) = fx()
        `when`(repo.findById(any() ?: "")).thenReturn(
            Optional.of(
                SystemSetting(
                    ServerProfileService.KEY,
                    ObjectMapper().writeValueAsString(ServerProfileDto("saved.host.com", 8090, "http", "n"))
                )
            )
        )
        assertEquals("saved.host.com", svc.getPublicInfo("req.host.com").publicHost)
    }

    @Test
    fun getPublicInfoFallsBackToRequestHost() {
        val (svc, _, _) = fx()
        assertEquals("req.example.com", svc.getPublicInfo("req.example.com:8090").publicHost)
    }

    @Test
    fun getPublicInfoSanitizesIpv6() {
        val (svc, _, _) = fx()
        assertEquals("[::1]", svc.getPublicInfo("[::1]:8090").publicHost)
    }

    @Test
    fun getPublicInfoDefaultServerNameWhenBlank() {
        val (svc, _, _) = fx(configuredHost = "x")
        assertEquals("PadGuard 管控服务端", svc.getPublicInfo(null).serverName)
    }
}
