package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.User
import com.padguard.server.domain.UserStatus
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

    /**
     * 登录前置闸门：账号必须处于可用状态。
     *
     * 之前只校验"手机号存在 + 密码正确"，完全不看 `users.status`，
     * 于是后台点「冻结」只改了一个没人读的字段 —— 家长端照常登录、照常用全部权益。
     * 校验必须在登录**与**令牌刷新**与**每次接口调用三处都做，缺一处就等于没冻结
     * （详见 ParentAuthInterceptor 与 BillingService.ensureActive 的同类注释）。
     *
     * 注意校验顺序：先验证凭据再判状态。反过来会泄露"这个手机号是不是我们用户"，
     * 属于可被枚举的账号存在性信息。
     */
    private fun requireUsableAccount(user: User) {
        val status = UserStatus.of(user.status)
        if (UserStatus.isUsable(status)) return
        val (code, msg) = when (status) {
            UserStatus.SUSPENDED -> ParentErr.ACCOUNT_SUSPENDED to
                "账号已被冻结，无法登录。如需恢复请联系客服。"
            UserStatus.PENDING -> ParentErr.ACCOUNT_PENDING to
                "账号尚未激活，请等待开通后再登录。"
            else -> ParentErr.ACCOUNT_SUSPENDED to
                "账号状态异常（$status），无法登录。如需恢复请联系客服。"
        }
        log.info("login/refresh rejected: userId={}, status={}", user.id, status)
        throw BizException(code, msg, Audience.PARENT)
    }

    fun loginWithPassword(req: LoginPasswordRequest): LoginResponse {
        val user = userRepository.findByPhone(req.phone)
            ?: throw BizException(ParentErr.UNAUTHORIZED, "用户不存在", Audience.PARENT)
        if (!Passwords.matches(req.password, user.passwordHash)) {
            throw BizException(ParentErr.UNAUTHORIZED, "密码错误", Audience.PARENT)
        }
        requireUsableAccount(user)
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
        requireUsableAccount(user)
        return buildLoginResponse(user)
    }

    /**
     * 用 refreshToken 换发新令牌。
     *
     * 这里**必须**复检账号状态：refreshToken 有效期 30 天，冻结账号时它还在客户端手里，
     * 若放行续期，家长端就能靠它无限续出新的 accessToken —— 冻结形同虚设。
     * 拒绝续期后家长端 OkHttp Authenticator 会清空本地会话并退回登录页。
     */
    fun refresh(refreshToken: String): TokenResponse {
        val userId = try {
            jwt.parseRefreshToken(refreshToken)
        } catch (e: Exception) {
            throw BizException(ParentErr.UNAUTHORIZED, "refresh 失效", Audience.PARENT)
        }
        // 必须确认用户仍存在：服务器换库/清库后旧令牌签名依然有效，
        // 若不校验，客户端会拿着"幽灵账号"的令牌永远续期，绑定的设备全挂在不存在的用户下。
        val user = userRepository.findById(userId).orElse(null)
            ?: throw BizException(ParentErr.UNAUTHORIZED, "账号不存在或已被重置，请重新登录", Audience.PARENT)
        requireUsableAccount(user)
        return TokenResponse(jwt.createAccessToken(userId), jwt.createRefreshToken(userId))
    }

    private fun buildLoginResponse(user: User): LoginResponse {
        // 最近登录时刻：后台「家长账号」列表要展示它，注册/登录成功后必须落库，
        // 否则那一列永远是空的，运营无法判断账号是否还在使用。
        runCatching {
            user.lastLoginAt = System.currentTimeMillis()
            userRepository.save(user)
        }.onFailure { log.warn("update lastLoginAt failed for {}: {}", user.id, it.message) }
        val access = jwt.createAccessToken(user.id)
        val refresh = jwt.createRefreshToken(user.id)
        return LoginResponse(
            user = UserDto(user.id, user.phone, user.nickname, user.avatar, user.role, user.sceneType),
            token = access,
            refreshToken = refresh
        )
    }
}
