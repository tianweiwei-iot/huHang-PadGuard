package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.User
import com.padguard.server.dto.LoginResponse
import com.padguard.server.dto.LoginPasswordRequest
import com.padguard.server.dto.LoginSmsRequest
import com.padguard.server.dto.RegisterRequest
import com.padguard.server.dto.TokenResponse
import com.padguard.server.dto.UserDto
import com.padguard.server.repository.UserRepository
import com.padguard.server.security.JwtTokenProvider
import com.padguard.server.security.Passwords
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import java.util.regex.Pattern

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val jwt: JwtTokenProvider,
    private val billingService: BillingService,
    @Value("\${padguard.auth.sms-login-enabled:false}") private val smsLoginEnabled: Boolean
) {
    private val log = LoggerFactory.getLogger(AuthService::class.java)

    /** 中国大陆手机号：1 开头、第二位 3-9、共 11 位 */
    private val CN_MOBILE = Pattern.compile("^1[3-9]\\d{9}$")
    fun loginWithPassword(req: LoginPasswordRequest): LoginResponse {
        val user = userRepository.findByPhone(req.phone)
            ?: throw BizException(ParentErr.UNAUTHORIZED, "用户不存在", Audience.PARENT)
        if (!Passwords.matches(req.password, user.passwordHash)) {
            throw BizException(ParentErr.UNAUTHORIZED, "密码错误", Audience.PARENT)
        }
        return buildLoginResponse(user)
    }

    /**
     * 手机号自助注册。
     *
     * 责任：建账号 + 设密码 + **注册即送免费试用**（默认 trial-days 天，当前 1 天）。
     * 送试用必须在注册时显式完成，不能等用户首次调受保护接口才由 statusOf 顺手创建——
     * 那样注册成功但试用记录缺失的窗口里，任何对订阅状态的读取都会默默新建一条，
     * 行为不可控（且无法向客户端明确表达"刚注册就有 N 天试用"）。
     *
     * 注册成功后直接签发 token 自动登录，省去"注册完再登录一次"的往返。
     */
    @Transactional
    fun register(req: RegisterRequest): LoginResponse {
        validatePhone(req.phone)
        validatePassword(req.password)
        if (userRepository.existsByPhone(req.phone)) {
            throw BizException(ParentErr.PARAM_ERROR, "手机号已注册，请直接登录", Audience.PARENT)
        }
        val now = System.currentTimeMillis()
        val id = "U" + UUID.randomUUID().toString().replace("-", "").take(20)
        val u = User(
            id = id,
            phone = req.phone,
            passwordHash = Passwords.encode(req.password),
            nickname = req.nickname?.takeIf { it.isNotBlank() } ?: ("家长" + req.phone.takeLast(4)),
            role = "", sceneType = "", status = "active", createdAt = now
        )
        userRepository.save(u)
        // 注册即送试用：保证订阅记录与账号同生，客户端立刻能展示"剩余 N 天"。
        // runCatching 兜底：计费侧任何异常都不得拖垮注册（后端稳定第一）。
        // 即便试用创建失败，首次读取订阅时仍会由 statusOf/ensureActive 惰性补建，行为可控。
        runCatching { billingService.statusOf(id) }
            .onFailure { log.warn("trial subscription creation failed for {}: {}", id, it.message) }
        log.info("parent registered: id={}, phone={}", id, req.phone)
        return buildLoginResponse(u)
    }

    private fun validatePhone(phone: String) {
        if (phone.isBlank() || !CN_MOBILE.matcher(phone).matches()) {
            throw BizException(ParentErr.PARAM_ERROR, "手机号格式不正确", Audience.PARENT)
        }
    }

    private fun validatePassword(password: String) {
        if (password.length < 6) {
            throw BizException(ParentErr.PARAM_ERROR, "密码至少 6 位", Audience.PARENT)
        }
    }

    /**
     * 短信登录。
     *
     * 当前**没有接入短信服务商**：`sms/send` 只返回一个空的 ok，验证码也从未被校验过。
     * 也就是说只要手机号在库里，任何人都能直接进入家长端 —— 这是一条免密入口。
     * 因此默认关闭，只有真正接入短信服务商后才可开启。
     */
    fun loginWithSms(req: LoginSmsRequest): LoginResponse {
        if (!smsLoginEnabled) {
            throw BizException(ParentErr.UNAUTHORIZED, "短信登录未启用，请使用密码登录", Audience.PARENT)
        }
        val user = userRepository.findByPhone(req.phone)
            ?: throw BizException(ParentErr.UNAUTHORIZED, "用户不存在", Audience.PARENT)
        return buildLoginResponse(user)
    }

    fun refresh(refreshToken: String): TokenResponse {
        val userId = try {
            jwt.parseRefreshToken(refreshToken)
        } catch (e: Exception) {
            throw BizException(ParentErr.UNAUTHORIZED, "refresh 失效", Audience.PARENT)
        }
        // 必须确认用户仍存在：服务器换库/清库后旧令牌签名依然有效，
        // 若不校验，客户端会拿着"幽灵账号"的令牌永远续期，绑定的设备全挂在不存在的用户下。
        if (!userRepository.existsById(userId)) {
            throw BizException(ParentErr.UNAUTHORIZED, "账号不存在或已被重置，请重新登录", Audience.PARENT)
        }
        return TokenResponse(jwt.createAccessToken(userId), jwt.createRefreshToken(userId))
    }

    private fun buildLoginResponse(user: User): LoginResponse {
        val access = jwt.createAccessToken(user.id)
        val refresh = jwt.createRefreshToken(user.id)
        return LoginResponse(
            user = UserDto(user.id, user.phone, user.nickname, user.avatar, user.role, user.sceneType),
            token = access,
            refreshToken = refresh
        )
    }
}
