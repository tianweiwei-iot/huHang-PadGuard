package com.padguard.server.security

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ChildErr
import com.padguard.server.common.HashUtil
import com.padguard.server.common.ParentErr
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.UserRepository
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor

/**
 * 控制端（家长）鉴权：Bearer JWT -> request attribute "userId"。
 *
 * 除了验签，还必须确认用户在库里真实存在：
 * 服务端换库/清库后，客户端手里的旧 JWT 签名依然有效（密钥没变），
 * 若不校验存在性，就会出现"幽灵账号"——能正常调接口、能生成绑定码，
 * 但绑出来的设备挂在一个数据库里根本不存在的用户名下，家长端永远看不到。
 */
@Component
class ParentAuthInterceptor(
    private val jwt: JwtTokenProvider,
    private val users: UserRepository
) : HandlerInterceptor {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (handler !is HandlerMethod) return true
        val auth = request.getHeader("Authorization")
        if (auth.isNullOrBlank() || !auth.startsWith("Bearer ")) {
            throw BizException(ParentErr.UNAUTHORIZED, "missing or invalid Authorization", Audience.PARENT)
        }
        val userId = try {
            jwt.parseUserId(auth.substring(7))
        } catch (e: Exception) {
            throw BizException(ParentErr.UNAUTHORIZED, "invalid token", Audience.PARENT)
        }
        if (!users.existsById(userId)) {
            throw BizException(ParentErr.UNAUTHORIZED, "账号不存在或已被重置，请重新登录", Audience.PARENT)
        }
        request.setAttribute("userId", userId)
        return true
    }
}

/** 被管控端（孩子）鉴权：X-Device-Id + Bearer deviceToken -> request attribute "deviceId" */
@Component
class ChildAuthInterceptor(
    private val deviceRepository: DeviceRepository
) : HandlerInterceptor {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (handler !is HandlerMethod) return true
        val deviceId = request.getHeader("X-Device-Id")
            ?: throw BizException(ChildErr.TOKEN_INVALID, "missing X-Device-Id", Audience.CHILD)
        val auth = request.getHeader("Authorization")
            ?: throw BizException(ChildErr.TOKEN_INVALID, "missing Authorization", Audience.CHILD)
        if (!auth.startsWith("Bearer ")) {
            throw BizException(ChildErr.TOKEN_INVALID, "invalid Authorization", Audience.CHILD)
        }
        val device = deviceRepository.findById(deviceId).orElse(null)
            ?: throw BizException(ChildErr.DEVICE_UNBOUND, "device not found", Audience.CHILD)
        if (device.deviceTokenHash != HashUtil.sha256(auth.substring(7))) {
            throw BizException(ChildErr.TOKEN_INVALID, "invalid device token", Audience.CHILD)
        }
        request.setAttribute("deviceId", deviceId)
        return true
    }
}
