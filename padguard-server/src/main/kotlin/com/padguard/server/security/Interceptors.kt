package com.padguard.server.security

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ChildErr
import com.padguard.server.common.HashUtil
import com.padguard.server.common.ParentErr
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.UserRepository
import com.padguard.server.service.DeviceService
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
            jwt.parseAccessToken(auth.substring(7))
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

/**
 * 文件下载的**软**鉴权：能认出调用者就记下身份，认不出来也不拦。
 *
 * ## 为什么必须"软"
 * files 这段下载路径为了让孩子端能取到家长发布的素材而整体放开了家长 JWT ——
 * 孩子端用图片加载库直接加载 URL，**不会带任何自定义请求头**。
 * 一旦在这里强制鉴权，所有孩子端的素材图/视频会集体 404，这是不能付出的代价。
 *
 * ## 那安全怎么保证
 * 认得出身份时就做精确校验（见 [com.padguard.server.controller.FilesController.get]）：
 * 家长拿不到别人家设备的截图，别的设备也拿不到本机 media 下的录音录屏。
 * 认不出身份时退回"能力 URL"（随机 128 位 fileId，不可穷举），与业界 Share Link 同一思路。
 * 上传于本次改造之前的历史文件没有归属记录，同样按能力 URL 放行，不会突然打不开。
 */
@Component
class FileAccessInterceptor(
    private val jwt: JwtTokenProvider,
    private val users: UserRepository,
    private val deviceRepository: DeviceRepository,
    private val deviceService: DeviceService
) : HandlerInterceptor {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (handler !is HandlerMethod) return true

        // 家长 JWT：识别成功且账号存在才记为已认证调用方
        val auth = request.getHeader("Authorization")
        val token = auth?.takeIf { it.startsWith("Bearer ") }?.substring(7)
        val matchedParent = token
            ?.let { runCatching { jwt.parseAccessToken(it) }.getOrNull() }
            ?.takeIf { runCatching { users.existsById(it) }.getOrDefault(false) }
        if (matchedParent != null) {
            request.setAttribute("fileViewerUserId", matchedParent)
            return true
        }

        // 孩子端 deviceToken：孩子端这次 HTTP 请求拿不到也不影响下载（会退回能力 URL），
        // 认出来则用于校验"这份截图是不是这台设备自己的"。
        val deviceId = request.getHeader("X-Device-Id")
        if (!deviceId.isNullOrBlank() && token != null) {
            val dev = runCatching { deviceRepository.findById(deviceId).orElse(null) }.getOrNull()
            if (dev != null && dev.deviceTokenHash == HashUtil.sha256(token)) {
                request.setAttribute("fileViewerDeviceId", deviceId)
                deviceService.markAlive(deviceId, com.padguard.server.common.ClientIps.of(request))
            }
        }
        return true
    }
}

/** 被管控端（孩子）鉴权：X-Device-Id + Bearer deviceToken -> request attribute "deviceId" */
@Component
class ChildAuthInterceptor(
    private val deviceRepository: DeviceRepository,
    private val deviceService: DeviceService
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
        // 任何已鉴权的孩子端 HTTP 活动都视为存活信号（见 DeviceService.markAlive 注释）：
        // POLLING 模式下主循环心跳不触达服务端，靠长轮询/日志等高频请求兜底刷新在线状态。
        // 顺带把来源 IP 交给 DeviceService 记录 —— 家庭部署下服务端与平板同网段，
        // 这是"局域网发现"唯一可信的数据来源（服务端无法主动探测平板，它没有监听端口）。
        deviceService.markAlive(deviceId, com.padguard.server.common.ClientIps.of(request))
        return true
    }
}
