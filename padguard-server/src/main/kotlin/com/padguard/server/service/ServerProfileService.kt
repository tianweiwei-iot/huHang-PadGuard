package com.padguard.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.dto.ServerProfileDto
import com.padguard.server.dto.ServerPublicInfoDto
import com.padguard.server.domain.SystemSetting
import com.padguard.server.repository.SystemSettingRepository
import com.padguard.server.security.AdminPermissionService
import org.springframework.stereotype.Service

/**
 * 服务器基本信息配置。复用 [SystemSetting] 通用键值表（key=`server.profile`），零迁移成本。
 *
 * - 读：任何已登录管理员可看（[ServerProfileController] 的 GET 端点经 AdminAuthInterceptor 鉴权）；
 * - 写：需 `server:write` 权限，由 [AdminPermissionService.require] 在服务端闸门拦截，前端仅做 UI 隐藏；
 * - 公开端点 [com.padguard.server.controller.ServerInfoController] 经 [getPublicInfo] 暴露脱敏信息，
 *   供客户端绑定引导/信息展示，无需登录。
 */
@Service
class ServerProfileService(
    private val systemSettingRepository: SystemSettingRepository,
    private val adminPerm: AdminPermissionService,
    private val objectMapper: ObjectMapper
) {
    companion object {
        const val KEY = "server.profile"
        const val DEFAULT_HOST = "192.168.1.12"
        const val DEFAULT_PORT = 8090
        const val DEFAULT_SCHEME = "http"
        const val DEFAULT_NAME = "PadGuard 管控服务端"
    }

    fun getServerProfile(): ServerProfileDto = load()

    fun saveServerProfile(adminId: String, req: ServerProfileDto) {
        adminPerm.require(adminId, "server", "write")
        val host = req.publicHost.trim()
        if (host.isBlank()) {
            throw BizException(ParentErr.PARAM_ERROR, "服务器地址(publicHost)不能为空", Audience.PARENT)
        }
        val port = req.port
        if (port < 1 || port > 65535) {
            throw BizException(ParentErr.PARAM_ERROR, "端口必须在 1-65535 之间", Audience.PARENT)
        }
        val scheme = req.scheme.trim().lowercase()
        if (scheme != "http" && scheme != "https") {
            throw BizException(ParentErr.PARAM_ERROR, "协议只能是 http 或 https", Audience.PARENT)
        }
        val dto = req.copy(publicHost = host, scheme = scheme, updatedAt = System.currentTimeMillis())
        systemSettingRepository.save(SystemSetting(KEY, objectMapper.writeValueAsString(dto)))
    }

    /** 对外公开信息（脱敏，缺失时回落默认值，绝不抛错） */
    fun getPublicInfo(): ServerPublicInfoDto {
        val p = load()
        return ServerPublicInfoDto(
            publicHost = if (p.publicHost.isBlank()) DEFAULT_HOST else p.publicHost,
            port = p.port,
            scheme = p.scheme,
            serverName = if (p.serverName.isBlank()) DEFAULT_NAME else p.serverName
        )
    }

    private fun load(): ServerProfileDto {
        val json = systemSettingRepository.findById(KEY).orElse(null)?.valueJson
        if (json.isNullOrBlank()) {
            return ServerProfileDto(DEFAULT_HOST, DEFAULT_PORT, DEFAULT_SCHEME, DEFAULT_NAME, "", null)
        }
        return runCatching { objectMapper.readValue(json, ServerProfileDto::class.java) }
            .getOrElse { ServerProfileDto(DEFAULT_HOST, DEFAULT_PORT, DEFAULT_SCHEME, DEFAULT_NAME, "", null) }
    }
}
