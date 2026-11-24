package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.common.SensitiveData
import com.padguard.server.config.ProdConfigAssertion
import com.padguard.server.domain.User
import com.padguard.server.domain.UserSession
import com.padguard.server.domain.UserStatus
import com.padguard.server.dto.LoginResponse
import com.padguard.server.dto.LoginPasswordRequest
import com.padguard.server.dto.LoginSmsRequest
import com.padguard.server.dto.ChangePasswordRequest
import com.padguard.server.dto.RegisterRequest
import com.padguard.server.dto.TokenResponse
import com.padguard.server.dto.UserDto
import com.padguard.server.dto.UserSessionView
import com.padguard.server.repository.UserRepository
import com.padguard.server.repository.UserSessionRepository
import com.padguard.server.security.JwtTokenProvider
import com.padguard.server.security.ParentCredentialGate
import com.padguard.server.security.Passwords
import com.padguard.server.ws.WebSocketPushService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import java.util.regex.Pattern

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val jwt: JwtTokenProvider,
    private val billingService: BillingService,
    @Value("\${padguard.auth.sms-login-enabled:false}") private val smsLoginEnabled: Boolean,
    private val env: Environment,
    private val userSessionRepository: UserSessionRepository? = null
) {
    private val log = LoggerFactory.getLogger(AuthService::class.java)

    /**
     * 吊销家长令牌时同步掐断其 WebSocket 长连接（新增缺陷 K）。
     *
     * 用可选字段注入而非新增构造参数：本类被多处 JVM 单测直构，改签名会连锁改测试；
     * 而 Spring 侧必然注入成功。即使某些场景没注入，[WebSocketPushService.pushToUser]
     * 的推送前复核仍会挡住旧连接，属于 fail-safe 降级而非 fail-open。
     */
    @Autowired(required = false)
    private var webSocketPush: WebSocketPushService? = null

    /**
     * 家长凭证/会话治理写操作的审计留痕（R45）。
     *
     * 与 [webSocketPush] 同款可选字段注入：本类被多处 JVM 单测直构，改构造签名会连锁改测试。
     * record 走 REQUIRES_NEW 且内部吞异常，审计失败绝不影响改密/登出主流程。
     * detail 只记事件语义，绝不落新旧密码等敏感值。
     */
    @Autowired(required = false)
    private var auditTrail: AuditService? = null

    /** 中国大陆手机号：1 开头、第二位 3-9、共 11 位 */
    private val CN_MOBILE = Pattern.compile("^1[3-9]\\d{9}$")

    /**
     * 登录失败账号级锁定（纵深防御，配合 resilience4j 限流）。
     *
     * 为什么需要：限流只按"IP+账号"限速（10 次/分钟），专业爆破仍可低速尝试；
     * 这里再加一道"连续 N 次失败 → 锁 M 分钟"的账号级闸门，大幅抬升爆破成本。
     * 仅对已存在账号计数，避免被用来恶意锁定受害者账号(DoS)。
     *
     * 计数存在 users 表的 `login_fail_count` / `login_locked_until` 两列，不是进程内 Map：
     * 内存态下「重启即清零」（等一次发布就能白拿一轮重试额度）、
     * 「多实例各算各的」（5 次阈值在 3 实例下实际接近 15 次）。见 [UserRepository.registerLoginFailure]。
     */
    @Value("\${padguard.auth.max-fail-attempts:5}")
    private val maxFailAttempts: Int = 5

    @Value("\${padguard.auth.lock-minutes:15}")
    private val lockMinutes: Int = 15

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
        // 与孩子端 bind-by-account 共用同一份「账号是否可用」判定（新增缺陷 M）。
        if (ParentCredentialGate.isUsable(user)) return
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
        // 先查账号，再做锁定判定：锁定期存在这一行上，没有账号就没有锁定态。
        // 顺序反过来（先看锁定再看账号）会让"未注册手机号"与"已注册但被锁"返回不同提示，
        // 而后者只可能是真实账号 —— 等于给攻击者一个免费的号码枚举接口。
        val user = userRepository.findByPhone(req.phone)
            ?: throw BizException(ParentErr.UNAUTHORIZED, "用户不存在", Audience.PARENT)
        val now = System.currentTimeMillis()
        val lockUntil = user.loginLockedUntil ?: 0L
        if (ParentCredentialGate.isLocked(lockUntil, now)) {
            val remainMin = ParentCredentialGate.remainingMinutes(lockUntil, now)
            throw BizException(
                ParentErr.TOO_MANY_REQUESTS,
                "密码连续错误次数过多，账号已临时锁定，请约 ${remainMin} 分钟后再试",
                Audience.PARENT
            )
        }
        if (!Passwords.matches(req.password, user.passwordHash)) {
            recordFailure(req.phone, user.loginFailCount ?: 0)
            throw BizException(ParentErr.UNAUTHORIZED, "密码错误", Audience.PARENT)
        }
        // 登录成功：清空失败计数与锁定。
        // 只在确实有残留时才写库 —— 正常用户每次登录都多一条 UPDATE 是没必要的写放大。
        if (ParentCredentialGate.shouldClearFailure(user.loginFailCount, user.loginLockedUntil)) {
            runCatching { userRepository.clearLoginFailure(req.phone) }
                .onFailure { log.warn("clear login failure count failed for {}: {}", SensitiveData.maskPhone(req.phone), it.message) }
        }
        requireUsableAccount(user)
        return buildLoginResponse(user)
    }

    /**
     * 记录一次失败并尝试加锁。
     *
     * 计数自增交给数据库的单条 UPDATE（见仓储注释），这里只负责判定"本次是否触发锁定"以留痕。
     * 并发下可能两条日志都说自己触发了锁定，不影响正确性 —— 锁定本身由数据库串行化。
     *
     * 写库失败**不向上抛**：锁定只是限流之外的第二道防御，让它把"密码正确也登不进去"
     * 变成 500 是本末倒置；此时仍返回"密码错误"，限流仍在生效。
     */
    private fun recordFailure(phone: String, knownCount: Int) {
        val max = ParentCredentialGate.maxAttempts(maxFailAttempts)
        val lockUntil = ParentCredentialGate.lockUntilAfter(System.currentTimeMillis(), lockMinutes)
        runCatching { userRepository.registerLoginFailure(phone, max, lockUntil) }
            .onFailure { log.warn("record login failure failed for {}: {}", SensitiveData.maskPhone(phone), it.message) }
        if (knownCount + 1 >= max) {
            log.warn("account locked due to {} consecutive failed logins: phone={}", max, SensitiveData.maskPhone(phone))
        }
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
        log.info("parent registered: id={}, phone={}", id, SensitiveData.maskPhone(req.phone))
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
     *
     * 第八轮评估 P2-1 追加的生产闸门：`prod` profile 下**无论配置怎么写都技术性禁用**——
     * 此前仅靠配置项兜底，一次误配置（如照抄 local 的 .env）就会把免密入口带上公网。
     * 接入真实短信服务商并上线验证码校验时，需连同本闸门一起评审移除。
     */
    fun loginWithSms(req: LoginSmsRequest): LoginResponse {
        // 原实现判断 `activeProfiles.contains("prod")`，但 docker-compose.prod.yml / Dockerfile
        // 都没有激活任何 profile（activeProfiles 为空），该闸门在真实生产环境是死代码。
        // 改为「非开发 profile 一律禁用」：与 ProdConfigAssertion 的 isDevLike 同口径，
        // 不依赖某个名为 prod 的 profile 存在。
        val profiles = env.activeProfiles.toList()
        val isDevLike = profiles.any { it in ProdConfigAssertion.DEV_PROFILES }
        // 注意：无 active profile（空列表）就是当前生产容器的真实形态，必须按生产处理。
        if (!isDevLike) {
            log.warn("sms login blocked in non-dev profile: phone={}, profiles={}", SensitiveData.maskPhone(req.phone), profiles)
            throw BizException(ParentErr.UNAUTHORIZED, "短信登录未启用，请使用密码登录", Audience.PARENT)
        }
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
    @Transactional
    fun refresh(refreshToken: String): TokenResponse {
        val payload = try {
            jwt.parseUserToken(refreshToken, "refresh")
        } catch (e: Exception) {
            // 文案里不出现 refresh 这类技术词：这句话会被家长端原样弹到屏幕上，
            // "refresh 失效"对家长等于没说。具体原因（过期/签名不符）也不外泄，
            // 统一给"请重新登录"这一条家长能照做的动作。
            throw BizException(ParentErr.UNAUTHORIZED, "登录状态已过期，请重新登录", Audience.PARENT)
        }
        // 必须确认用户仍存在：服务器换库/清库后旧令牌签名依然有效，
        // 若不校验，客户端会拿着"幽灵账号"的令牌永远续期，绑定的设备全挂在不存在的用户下。
        val user = userRepository.findById(payload.userId).orElse(null)
            ?: throw BizException(ParentErr.UNAUTHORIZED, "账号不存在或已被重置，请重新登录", Audience.PARENT)
        requireUsableAccount(user)
        // 吊销校验必须在这里也做一遍：登出后家长端手里的 refreshToken 仍是合法签名，
        // 只挡 access 的话，客户端凭它 30 天内随时能换出一对全新令牌，登出等于没登出。
        // 版本不等（非仅小于）即吊销：库回滚/降版后持超前令牌同样拒绝。
        if (payload.version != user.tokenVersion) {
            throw BizException(ParentErr.UNAUTHORIZED, "登录状态已失效，请重新登录", Audience.PARENT)
        }
        // 单会话吊销 + 原子轮换：refresh token 携带的 jti 必须在 user_sessions 里有对应
        // 未吊销记录且归属本用户，否则视为已在该设备「退出登录」或令牌被轮换作废。
        //
        // 用条件 UPDATE 一次性完成「认领旧 jti → 换新 jti」：DB 保证旧 jti 只能被成功
        // 轮换一次。旧实现是「查 jti→校验→改实体字段→save」的读改写，在 refresh 无事务、
        // 端点又无频控时，攻击者并发打两个请求可让两者都在 jti 被改前通过校验、各自签出
        // 一对有效令牌，令"单次使用"与单设备踢下线失效（客户端单飞 Mutex 挡不住直连接口）。
        // 归属校验（userId）与吊销校验（revoked=false）一并并入 WHERE，返回 0 即按失效拒绝。
        val newJti = java.util.UUID.randomUUID().toString()
        userSessionRepository?.let { repo ->
            val jti = payload.jti
            if (jti.isNullOrBlank()) {
                throw BizException(ParentErr.UNAUTHORIZED, "登录状态已过期，请重新登录", Audience.PARENT)
            }
            val rotated = repo.rotateRefreshJti(jti, newJti, user.id, System.currentTimeMillis())
            if (rotated != 1) {
                throw BizException(ParentErr.UNAUTHORIZED, "登录状态已过期，请重新登录", Audience.PARENT)
            }
        }
        return TokenResponse(
            jwt.createAccessToken(user.id, user.tokenVersion),
            jwt.createRefreshToken(user.id, user.tokenVersion, newJti)
        )
    }

    /**
     * 退出登录：把 `users.token_version` +1，令该账号**全部已签发令牌**立刻失效
     * （本机 access、refresh，以及其他设备上的会话）。
     *
     * 为什么不是"只吊销当前这一个令牌"：JWT 无状态，服务端没有令牌清单可比对；
     * 要做到逐个令牌吊销就得引入黑名单存储（等于把无状态改成有状态，成本和故障面都更大）。
     * 版本号是"整账号吊销"，与用户点「退出登录」时的心智一致：
     * 我退出了，谁也别想再用我的登录态。
     *
     * 行锁自增：多端同时点登出时避免后写覆盖先写导致自增被吞（详见 [UserRepository.findByIdForUpdate]）。
     */
    @Transactional
    fun logout(userId: String): Map<String, Any> {
        val user = userRepository.findByIdForUpdate(userId)
            // 令牌已通过拦截器校验（账号存在），这里为空只可能是并发删号，直接放行即可。
            ?: return mapOf("ok" to true)
        user.tokenVersion += 1
        userRepository.save(user)
        // 同时吊销该账号的 refresh 会话记录：单设备退出场景下，被退出的那台无法再续期。
        userSessionRepository?.runCatching { revokeAllForUser(userId, System.currentTimeMillis()) }
            ?.onFailure { log.warn("revoke user sessions on logout failed for {}: {}", userId, it.message) }
        // HTTP 侧令牌已失效，但已建立的 WS 长连接不会再送令牌来校验 —— 必须主动掐断，
        // 否则家长退出登录后旧连接仍能继续接收告警 / 解锁申请 / 截图 / 定位推送。
        webSocketPush?.revokeUser(userId)
        log.info("家长登出，已吊销其全部令牌与会话: userId={}, newVersion={}", userId, user.tokenVersion)
        auditTrail?.record(userId, null, AuditAction.PARENT_LOGOUT, detail = "登出并吊销全部令牌与会话")
        return mapOf("ok" to true)
    }

    /**
     * 家长自助修改登录密码。
     *
     * 为什么必须有这个入口：初始密码与后台「重置密码」产出的都是服务端随机强口令，
     * 口令文件与后台弹窗都写着"请登录后尽快修改"，但改造前服务端根本没有修改接口 ——
     * 家长只能一直用一张截图里的随机串登录，运营重置一次就要再人工转告一次。
     *
     * 一次动作里做五件事，缺一条都会留下缺口：
     * 1. 校验旧口令 —— 令牌不等于身份（二手平板、借用他人手机都可能留着有效 token）；
     * 2. 校验新口令强度并拒绝"新旧相同" —— 否则界面点成功、实际没变，
     *    家长以为已经改掉临时口令，下次反而不记得；
     * 3. token_version +1 并掐断 WS —— 与 [logout] 同一套机制，让**其他**设备上
     *    早已签发的令牌立即失效（"别人的手机还登着我的号"正是改密码最常见的动机）；
     * 4. 给**当前**设备签发新令牌 —— 上一步会连本机令牌一起作废，
     *    不换一对新的就把操作者自己踢回登录页，变成"改完密码反被登出"；
     * 5. 清零登录失败计数 —— 改密码常发生在"连输错好几次"之后，
     *    不清理则用户改完仍处在锁定窗口里，体感是"改了也没用"。
     */
    @Transactional
    fun changePassword(userId: String, req: ChangePasswordRequest): TokenResponse {
        val user = userRepository.findByIdForUpdate(userId)
            ?: throw BizException(ParentErr.UNAUTHORIZED, "登录状态已失效，请重新登录", Audience.PARENT)
        if (!Passwords.matches(req.oldPassword, user.passwordHash)) {
            throw BizException(ParentErr.UNAUTHORIZED, "原密码不正确", Audience.PARENT)
        }
        validatePassword(req.newPassword)
        if (Passwords.matches(req.newPassword, user.passwordHash)) {
            throw BizException(ParentErr.PARAM_ERROR, "新密码不能与原密码相同", Audience.PARENT)
        }
        user.passwordHash = Passwords.encode(req.newPassword)
        user.tokenVersion += 1
        userRepository.save(user)
        runCatching { userRepository.clearLoginFailure(user.phone) }
            .onFailure { log.warn("clear login failure after password change failed: userId={}, {}", user.id, it.message) }
        // 改密后旧 refresh 会话一并作废，避免「改完密码旧设备仍登着」；当前设备通过下方新令牌重新建会话。
        userSessionRepository?.runCatching { revokeAllForUser(userId, System.currentTimeMillis()) }
            ?.onFailure { log.warn("revoke user sessions on password change failed for {}: {}", userId, it.message) }
        webSocketPush?.revokeUser(user.id)
        log.info("家长修改登录密码，其余会话已吊销: userId={}, newVersion={}", userId, user.tokenVersion)
        auditTrail?.record(user.id, null, AuditAction.PARENT_PASSWORD_CHANGE, detail = "修改登录密码并吊销全部旧会话")
        return issueSessionTokens(user)
    }

    /**
     * 由后台重置密码等管理动作调用：强制该账号的既有会话全部下线。
     *
     * 与 [logout] 同一套机制，独立成方法是因为调用方（管理员）持有的是 adminId，
     * 不能走"当前登录用户"的语义。
     */
    @Transactional
    fun revokeAllSessions(userId: String): Long {
        val user = userRepository.findByIdForUpdate(userId) ?: return 0
        user.tokenVersion += 1
        userRepository.save(user)
        userSessionRepository?.runCatching { revokeAllForUser(userId, System.currentTimeMillis()) }
            ?.onFailure { log.warn("revoke user sessions failed for {}: {}", userId, it.message) }
        webSocketPush?.revokeUser(userId)
        log.info("管理动作吊销家长会话: userId={}, newVersion={}", userId, user.tokenVersion)
        return user.tokenVersion
    }

    /**
     * 列出当前账号的活跃登录会话（用于「登录设备」管理页）。
     * 仅返回未吊销的会话；不暴露 refresh_jti 等敏感字段。
     */
    fun listSessions(userId: String): List<UserSessionView> {
        val sessions = userSessionRepository?.findByUserIdAndRevokedFalse(userId) ?: emptyList()
        return sessions.map {
            UserSessionView(it.id, it.deviceLabel, it.createdAt, it.lastRefreshedAt)
        }
    }

    /**
     * 单设备退出登录（仅吊销该会话的 refresh token，不影响其他设备）。
     *
     * 与 logout 的区别：logout 走 token_version +1（整账号踢下线），
     * 这里只置位该会话的 revoked，使那一台设备的 refresh token 立刻失效、
     * 下次刷新被拒；其 access token 仍有效至 ≤120 分钟自然过期（标准单设备退出语义，
     * 避免「踢一台连带把其他设备也登出」）。不调用 revokeUser 掐断 WS，避免误伤其他设备连接。
     */
    @Transactional
    fun revokeSession(userId: String, sessionId: String) {
        val repo = userSessionRepository
            ?: throw BizException(ParentErr.SERVER_ERROR, "会话服务暂不可用", Audience.PARENT)
        val s = repo.findByUserIdAndIdAndRevokedFalse(userId, sessionId)
            ?: throw BizException(ParentErr.PARAM_ERROR, "会话不存在或已退出", Audience.PARENT)
        s.revoked = true
        s.revokedAt = System.currentTimeMillis()
        repo.save(s)
        log.info("家长单设备会话已吊销: userId={}, sessionId={}", userId, sessionId)
        auditTrail?.record(
            userId, null, AuditAction.PARENT_SESSION_REVOKE,
            targetType = "session", targetId = sessionId
        )
    }

    private fun buildLoginResponse(user: User): LoginResponse {
        // 最近登录时刻：后台「家长账号」列表要展示它，注册/登录成功后必须落库，
        // 否则那一列永远是空的，运营无法判断账号是否还在使用。
        //
        // 这里刻意**不**用 `save(user)`：那是把调用方的整行快照写回数据库。
        // 家长在 A 端登出（token_version +1）与 B 端登录几乎同时发生时，
        // B 端手里的旧快照会把 version 写回旧值 —— 刚被吊销的令牌重新生效。
        val now = System.currentTimeMillis()
        runCatching { userRepository.touchLastLoginAt(user.id, now) }
            .onFailure { log.warn("update lastLoginAt failed for {}: {}", user.id, it.message) }
        user.lastLoginAt = now
        val tokens = issueSessionTokens(user)
        return LoginResponse(
            user = UserDto(user.id, user.phone, user.nickname, user.avatar, user.role, user.sceneType),
            token = tokens.token,
            refreshToken = tokens.refreshToken
        )
    }

    /**
     * 签发一对令牌，并为本次登录新建一条 user_sessions 记录（携带随机 jti）。
     * 之后每次 refresh 都轮换 jti（旧 refresh token 立即失效），实现单设备吊销与防重放。
     * session 写入失败**不阻断登录**（best-effort）：无状态兜底仍可工作，
     * 只是该账号暂时失去「单设备踢下线」能力，全局 token_version 吊销仍有效。
     */
    private fun issueSessionTokens(user: User): TokenResponse {
        val jti = java.util.UUID.randomUUID().toString()
        userSessionRepository?.runCatching {
            save(
                UserSession(
                    id = "us_" + java.util.UUID.randomUUID().toString().replace("-", "").take(20),
                    userId = user.id,
                    deviceLabel = null,
                    refreshJti = jti,
                    createdAt = System.currentTimeMillis(),
                    lastRefreshedAt = System.currentTimeMillis()
                )
            )
        }?.onFailure { log.warn("create user session failed for {}: {}", user.id, it.message) }
        return TokenResponse(
            token = jwt.createAccessToken(user.id, user.tokenVersion),
            refreshToken = jwt.createRefreshToken(user.id, user.tokenVersion, jti)
        )
    }
}
