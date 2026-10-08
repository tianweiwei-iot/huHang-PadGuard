package com.padguard.server.dto

/**
 * 服务器基本信息（运营可在后台配置，无需改代码发版）。
 *
 * 解决「局域网 IP 漂移导致客户端写死地址失效」的运维痛点：运维在后台把对外访问地址
 * （[publicHost]/[port]/[scheme]）改对即可；客户端引导/绑定页可读取 [ServerPublicInfoDto] 展示。
 */
data class ServerProfileDto(
    /** 对外访问域名或 IP（孩子端/家长端连接用的地址），如 192.168.1.12 */
    val publicHost: String = "",
    /** 对外端口，如 8090 */
    val port: Int = 8090,
    /** 协议：http / https */
    val scheme: String = "http",
    /** 服务器展示名称 */
    val serverName: String = "PadGuard 管控服务端",
    /** 备注（内部使用，不对外公开） */
    val remark: String = "",
    /** 最后更新时间戳（毫秒），仅服务端回填 */
    val updatedAt: Long? = null
)

/** 对外公开信息（脱敏，不含备注等内部字段），供绑定引导/信息展示 */
data class ServerPublicInfoDto(
    val publicHost: String,
    val port: Int,
    val scheme: String,
    val serverName: String
)
