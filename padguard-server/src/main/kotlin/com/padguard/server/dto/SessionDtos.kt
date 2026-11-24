package com.padguard.server.dto

/**
 * 登录会话视图：家长端「登录设备」管理页展示用。
 *
 * 只暴露非敏感的展示字段（会话 id、设备标签、创建/最近刷新时刻），
 * 绝不外带 refresh_jti / userId / revoked 等机密或内部状态字段。
 */
data class UserSessionView(
    val id: String,
    val deviceLabel: String?,
    val createdAt: Long,
    val lastRefreshedAt: Long
)
