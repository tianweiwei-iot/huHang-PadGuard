package com.padguard.server.security

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ChildErr
import com.padguard.server.common.HashUtil
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.UserStatus
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
 *
 * **还必须校验账号状态**（[UserStatus.isUsable]）：accessToken 有效期 120 分钟，
 * 后台冻结账号时客户端手里的令牌并没有过期，只挡登录的话，
 * 已登录的家长端还能继续用满两小时再靠 refreshToken 无限续下去 ——
 * 冻结就成了"下次登录才生效"的空文。这里拒绝后客户端会立刻退回登录页。
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
            // 这些 message 会经 GlobalExceptionHandler 原样回到客户端并上屏，必须是中文具体原因。
            throw BizException(ParentErr.UNAUTHORIZED, "登录状态已失效，请重新登录（缺少有效的登录令牌）", Audience.PARENT)
        }
        val payload = try {
            jwt.parseUserToken(auth.substring(7), "access")
        } catch (e: Exception) {
            throw BizException(ParentErr.UNAUTHORIZED, "登录状态已失效，请重新登录（令牌无效或已过期）", Audience.PARENT)
        }
        val user = users.findById(payload.userId).orElse(null)
            ?: throw BizException(ParentErr.UNAUTHORIZED, "账号不存在或已被重置，请重新登录", Audience.PARENT)
        // 吊销校验：登出 / 后台重置密码会把 users.token_version +1，
        // 此前签发的令牌都停留在旧版本，必须与"账号不存在"同样处理（退回登录页）。
        // 没有这一步，/auth/logout 就只是客户端删了个本地文件，服务端令牌照常可用。
        // 版本不等于（而非仅小于）即吊销：库被回滚/手工降版后，持超前版本令牌同样不可信，
        // 否则"恢复旧备份"反而会让已吊销令牌重新可用。
        if (payload.version != user.tokenVersion) {
            throw BizException(ParentErr.UNAUTHORIZED, "登录状态已失效，请重新登录", Audience.PARENT)
        }
        val status = UserStatus.of(user.status)
        if (!UserStatus.isUsable(status)) {
            throw BizException(
                if (status == UserStatus.PENDING) ParentErr.ACCOUNT_PENDING else ParentErr.ACCOUNT_SUSPENDED,
                if (status == UserStatus.SUSPENDED) "账号已被冻结，无法继续使用。如需恢复请联系客服。"
                else "账号尚未激活，请等待开通后再使用。",
                Audience.PARENT
            )
        }
        request.setAttribute("userId", payload.userId)
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

        // 家长 JWT：识别成功、账号存在**且账号状态可用**才记为已认证调用方。
        // 冻结账号在这里一律不认：否则 /v1/files/** 是 ParentAuthInterceptor 的排除路径，
        // 被冻结的家长仍能以家长身份下载素材（精确归属校验会放行本家人的文件）。
        val auth = request.getHeader("Authorization")
        val token = auth?.takeIf { it.startsWith("Bearer ") }?.substring(7)
        val matchedParent = token
            // S5：typ 隔离 —— 只认 access token，refresh/admin token 不得冒充下载身份
            ?.let { runCatching { jwt.parseUserToken(it, "access") }.getOrNull() }
            // 冻结/待激活账号不认（同事侧）：查实体校验状态，而非只判存在性；
            // 已登出的令牌同样不认——否则登出后仍能以家长身份下载本家素材与截图。
            ?.let { p ->
                runCatching { users.findById(p.userId).orElse(null) }.getOrNull()
                    ?.takeIf { UserStatus.isUsable(it.status) && p.version == it.tokenVersion }
            }
            ?.id
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
            ?: throw BizException(ChildErr.TOKEN_INVALID, "设备凭证缺失：请求未带设备标识，请重新绑定设备", Audience.CHILD)
        val auth = request.getHeader("Authorization")
            ?: throw BizException(ChildErr.TOKEN_INVALID, "设备凭证缺失：请求未带设备令牌，请重新绑定设备", Audience.CHILD)
        if (!auth.startsWith("Bearer ")) {
            throw BizException(ChildErr.TOKEN_INVALID, "设备凭证格式不正确，请重新绑定设备", Audience.CHILD)
        }
        val device = deviceRepository.findById(deviceId).orElse(null)
            ?: throw BizException(ChildErr.DEVICE_UNBOUND, "设备未绑定或已被解绑，请在家长端重新绑定", Audience.CHILD)
        if (device.deviceTokenHash != HashUtil.sha256(auth.substring(7))) {
            throw BizException(ChildErr.TOKEN_INVALID, "设备令牌已失效，请重新绑定设备", Audience.CHILD)
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
