package com.padguard.server.ws

import com.padguard.server.domain.UserStatus
import com.padguard.server.repository.UserRepository
import com.padguard.server.security.JwtTokenProvider
import org.slf4j.LoggerFactory
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.WebSocketMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.stereotype.Component

/** 家长端 WebSocket：优先 Authorization: Bearer 头鉴权；旧版客户端兼容 wss://host/ws?token=<JWT> */
@Component
class DeviceWebSocketHandler(
    private val jwt: JwtTokenProvider,
    private val users: UserRepository,
    private val webSocketPush: WebSocketPushService
) : WebSocketHandler {

    private val log = LoggerFactory.getLogger(DeviceWebSocketHandler::class.java)

    /**
     * query token 兼容开关（`padguard.ws.query-token-enabled`，默认 **false**）。
     *
     * URL 里的 JWT 会被反向代理访问日志、浏览器历史、系统代理日志长期留存；
     * Tomcat accesslog 的 `%r` 也记录完整 query（application.yml），等于把有效期
     * 120 分钟的家长 access token 明文落盘。旧默认值 true 只是为兼容早期客户端，
     * 新版家长端已在握手时带 Authorization 头，生产必须默认关闭 query 通道。
     * 确需短期兼容旧包时显式注入 `padguard.ws.query-token-enabled=true` 并留运维记录。
     */
    @org.springframework.beans.factory.annotation.Value("\${padguard.ws.query-token-enabled:false}")
    private var queryTokenEnabled: Boolean = false

    /**
     * 握手鉴权：只验签是不够的。
     *
     * WebSocket 握手被 [com.padguard.server.config.WebConfig] 从家长端拦截器里排除
     * （token 走 query 参数而非 Authorization 头），因此这里必须自己把拦截器做的事补齐：
     * 校验账号存在 + 账号状态可用。否则后台冻结家长后，
     * 该家长的长连接仍能收到告警、上下线、解锁申请等推送 —— 冻结出现一条绕过口子。
     */
    override fun afterConnectionEstablished(session: WebSocketSession) {
        // 第八轮评估 P1-F：token 优先取 Authorization 头（JWT 不再进 URL），
        // URL 里的 token 会被反向代理访问日志、浏览器历史、系统代理日志长期留存。
        // 保留 query 方式仅为向后兼容旧版客户端；新版客户端一律走头。
        val headerToken = session.handshakeHeaders.getFirst("Authorization")
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring(7)?.trim()?.takeIf { it.isNotBlank() }
        if (headerToken == null && session.uri?.query != null && queryTokenEnabled) {
            // 兼容模式观测：记录仍在用 query token 的旧客户端（不含 token 本体），
            // 运营依据该日志确认"客户端已全部升级"后可安全关闭 query-token-enabled。
            log.info("ws handshake via legacy query token: uri={}", session.uri?.path)
        }
        val token = headerToken
            ?: if (queryTokenEnabled) session.uri?.query?.let { parseToken(it) } else null
        // 必须是 access token（refresh/管理员令牌不得建立家长推送通道），
        // 且账号必须真实存在 —— 与 ParentAuthInterceptor 的"幽灵账号"防护同口径。
        val userId = if (token != null) {
            try {
                val parsed = jwt.parseUserToken(token, "access")
                // 令牌版本必须与库内一致：家长登出 / 后台重置密码后，
                // 旧令牌不得再建立推送通道（否则还能收到自家孩子的告警与解锁申请）。
                val owner = users.findById(parsed.userId).orElse(null)
                if (owner != null && parsed.version == owner.tokenVersion) parsed.userId else null
            } catch (e: Exception) { null }
        } else null

        val user = userId?.let { runCatching { users.findById(it).orElse(null) }.getOrNull() }
        if (user == null || !UserStatus.isUsable(user.status)) {
            log.info("WS rejected: userId={}, reason=unknown-or-not-usable", userId)
            session.close(CloseStatus.NOT_ACCEPTABLE)
            return
        }
        session.attributes["userId"] = user.id
        // 记下"这条连接是用哪一版令牌建立的"：HTTP 每次请求都能拿 JWT 比对 token_version，
        // WS 握手后就再也不会送来令牌，只能在建立时把版本钉在会话上。
        // 家长登出 / 后台冻结 / 重置密码后，WebSocketPushService 推送前据此识别旧连接并踢线
        // （新增缺陷 K）；不写这个属性会退化成"只按账号状态复核"，登出后旧连接仍收推送。
        session.attributes[WebSocketPushService.ATTR_TOKEN_VERSION] = user.tokenVersion
        webSocketPush.register(user.id, session)
        log.info("WS connected: user=${user.id}")
    }

    override fun handleMessage(session: WebSocketSession, message: WebSocketMessage<*>) {
        // 家长端为单向接收；客户端上行消息暂不处理
    }

    override fun handleTransportError(session: WebSocketSession, exception: Throwable) {
        // 只打 `exception.message` 时，NPE / 部分 IOException 的消息是 null，现场日志会出现
        // 一行毫无信息量的 `WS transport error: null`，无法区分"手机断网"和"服务端 NPE"。
        // 这里补上异常类型；不打整段堆栈是刻意的——移动端掉线属高频事件，堆栈会把日志刷爆。
        val detail = exception.message?.takeIf { it.isNotBlank() } ?: "(no message)"
        log.warn("WS transport error: ${exception.javaClass.simpleName} - $detail (session=${session.id})")
    }

    override fun afterConnectionClosed(session: WebSocketSession, closeStatus: CloseStatus) {
        val userId = session.attributes["userId"] as? String
        if (userId != null) webSocketPush.unregister(userId, session)
    }

    override fun supportsPartialMessages(): Boolean = false

    private fun parseToken(query: String): String? =
        query.split("&").firstOrNull { it.startsWith("token=") }?.substringAfter("token=")
}
