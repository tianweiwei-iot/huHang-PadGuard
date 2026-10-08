package com.padguard.server.controller

import com.padguard.server.common.ApiResponse
import com.padguard.server.dto.ServerProfileDto
import com.padguard.server.service.ServerProfileService
import org.springframework.web.bind.annotation.*

/**
 * 管控后台「服务器基本信息」配置端点（前缀 /v1/admin）。
 *
 * 鉴权与权限闸门由 [com.padguard.server.config.WebConfig] 的 AdminAuthInterceptor（要求管理员登录）
 * 与 [com.padguard.server.service.ServerProfileService.saveServerProfile] 内的 [com.padguard.server.security.AdminPermissionService.require]
 * （server:write）兜住，前端仅做 UI 隐藏。
 */
@RestController
@RequestMapping("/v1/admin")
class ServerProfileController(
    private val serverProfileService: ServerProfileService
) {

    @GetMapping("/server-profile")
    fun get(): ApiResponse<ServerProfileDto> =
        ApiResponse.ok(serverProfileService.getServerProfile())

    @PutMapping("/server-profile")
    fun save(
        @RequestAttribute("adminId") adminId: String,
        @RequestBody req: ServerProfileDto
    ): ApiResponse<ServerProfileDto> {
        serverProfileService.saveServerProfile(adminId, req)
        return ApiResponse.ok(serverProfileService.getServerProfile())
    }
}
