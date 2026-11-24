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
    private val objectMapper: ObjectMapper,
    /**
     * 显式声明的对外地址（`PADGUARD_PUBLIC_HOST`）。为空时不做任何猜测，
     * 由 [getPublicInfo] 回落到"本次请求的 Host 头"。
     *
     * 这里曾经硬编码一台具体机器的公网 IP 作默认值 —— 那等于把"某次部署的地址"
     * 编进所有部署的产物里：任何私有化部署只要没在后台填过 publicHost，
     * 公开端点就会把**别人的服务器地址**返回给客户端，引导页上的二维码/地址直接错。
     * 更糟的是它把一台真实机器的 IP 暴露在源码与安装包里，无任何收益。
     */
    @org.springframework.beans.factory.annotation.Value("\${padguard.public-host:}")
    private val configuredHost: String = ""
) {
    companion object {
        const val KEY = "server.profile"

        /**
         * 端口默认值只在"完全没配置"时用于展示，不再带任何主机名。
         * 8090 对应 home/发布包启动器；compose + nginx 的默认映射是 8080（对外 443），
         * 两者不同源，生产部署务必在管理端显式配置 server.profile，别依赖这个兜底值。
         */
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

    /**
     * 对外公开信息（脱敏，绝不抛错）。
     *
     * 主机地址的取值优先级（前一档为空才看下一档）：
     * 1. 环境变量 `PADGUARD_PUBLIC_HOST` —— 运维明确知道对外地址时用它，最权威；
     * 2. 后台「服务器信息」页保存的值 —— 家长可见可改；
     * 3. 本次请求的 `Host` 头 —— 客户端**实际连到的**地址，没配置时这就是正确答案；
     * 4. 空串 —— 让前端显示"未配置"并引导去后台填写，而不是编一个地址出来。
     *
     * @param requestHost 请求的 Host 头（可能含端口），可为空
     */
    fun getPublicInfo(requestHost: String? = null): ServerPublicInfoDto {
        val p = load()
        val host = sequenceOf(configuredHost.trim(), p.publicHost.trim(), sanitizeHost(requestHost))
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        return ServerPublicInfoDto(
            publicHost = host,
            port = p.port,
            scheme = p.scheme,
            serverName = if (p.serverName.isBlank()) DEFAULT_NAME else p.serverName
        )
    }

    /** Host 头形如 `example.com:8090` —— 端口另有字段承载，这里只取主机部分 */
    private fun sanitizeHost(raw: String?): String {
        val h = raw?.trim().orEmpty()
        if (h.isEmpty()) return ""
        // IPv6 字面量形如 [::1]:8090
        if (h.startsWith("[")) return h.substringBefore(']') + "]"
        return h.substringBefore(':')
    }

    private fun load(): ServerProfileDto {
        val json = systemSettingRepository.findById(KEY).orElse(null)?.valueJson
        if (json.isNullOrBlank()) {
            // 未配置时不伪造主机，留给 getPublicInfo 按优先级回落
            return ServerProfileDto("", DEFAULT_PORT, DEFAULT_SCHEME, DEFAULT_NAME, "", null)
        }
        return runCatching { objectMapper.readValue(json, ServerProfileDto::class.java) }
            .getOrElse { ServerProfileDto("", DEFAULT_PORT, DEFAULT_SCHEME, DEFAULT_NAME, "", null) }
    }
}
