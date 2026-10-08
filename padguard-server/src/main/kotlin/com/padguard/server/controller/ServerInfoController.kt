package com.padguard.server.controller

import com.padguard.server.common.ApiResponse
import com.padguard.server.dto.ServerPublicInfoDto
import com.padguard.server.service.ServerProfileService
import org.springframework.web.bind.annotation.*

/**
 * 公开端点：返回服务器对外访问信息（脱敏），供客户端绑定引导/信息展示。
 *
 * 免鉴权：已在 [com.padguard.server.config.WebConfig] 中把 `/v1/server/info` 排除出
 * ParentAuthInterceptor 与 SubscriptionInterceptor 的拦截范围，匿名即可访问，且信息已脱敏（不含备注）。
 */
@RestController
@RequestMapping("/v1")
class ServerInfoController(
    private val serverProfileService: ServerProfileService
) {

    @GetMapping("/server/info")
    fun info(): ApiResponse<ServerPublicInfoDto> =
        ApiResponse.ok(serverProfileService.getPublicInfo())
}
