package com.padguard.parent.data.remote

import com.padguard.parent.data.model.UsageStatsDto
import com.padguard.parent.data.model.toDomain
import com.padguard.parent.domain.model.UsageStats
import com.padguard.parent.di.ServerPrefs
import com.padguard.parent.data.auth.TokenManager
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.math.min

/**
 * 家长端实时推送通道（WebSocket）。
 *
 * ## 职责
 * 连接服务端 `/ws?token=<JWT>`，把服务端推来的实时事件按类型分发成 [Flow]。
 * 两类事件：
 * - `usage.today`：孩子端上行用量后，服务端推送今日用量快照，首页据此即时刷新；
 * - `device.enrolled`：孩子端完成接入后，服务端推送接入完成，家长端据此结束等待。
 *
 * 之所以要有一条真正的实时通道，而不是让各页面各轮询各的：
 * "孩子端什么时候绑上"这件事本质是事件而不是状态轮询能优雅回答的，
 * 轮询要么慢（体验差）要么密（耗电耗流量）。推送是快路径，
 * 各处的查询接口退化为"推送丢失时的兜底"，两者职责清晰。
 *
 * ## 连接生命周期（自愈）
 * - 由登录态驱动：登录后自动连接，退出登录自动断开；
 * - 服务器地址（[ServerPrefs.baseUrl]）变化即重连，换网络/换电脑无需重启 App；
 * - 断线指数退避重连（1s→2s→…→上限 30s），期间不影响各页面（查询兜底仍在）。
 *
 * 单例持有唯一连接，多页面/多 ViewModel 收集同一条 [Flow] 不会建多条 WS。
 */
@Singleton
class ParentPushClient @Inject constructor(
    private val serverPrefs: ServerPrefs,
    private val tokenManager: TokenManager,
    private val moshi: Moshi,
    @Named("ws") private val wsClient: OkHttpClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _messages = MutableSharedFlow<ParentPushMessage>(extraBufferCapacity = 64)
    val messages: Flow<ParentPushMessage> = _messages.asSharedFlow()

    @Volatile private var active = false
    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var reconnectAttempts = 0
    @Volatile private var started = false

    private val usageStatsAdapter = moshi.adapter(UsageStatsDto::class.java)
    private val enrollmentAdapter = moshi.adapter(EnrollmentEventDto::class.java)
    private val envelopeAdapter = moshi.adapter(PushEnvelope::class.java)

    /** 应用启动后调用一次：按登录态与服务器地址自动管理连接（幂等，多次调用安全） */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            tokenManager.isLoggedInFlow().collect { loggedIn -> setActive(loggedIn) }
        }
        scope.launch {
            serverPrefs.baseUrl.collect { if (active) reconnect() }
        }
    }

    private fun setActive(shouldBeActive: Boolean) {
        active = shouldBeActive
        if (shouldBeActive) connect() else disconnect()
    }

    @Synchronized
    private fun connect() {
        if (webSocket != null) return
        // token 拿不到（登录态刚被清 / 刷新尚未完成）→ 不连也不排重连：
        // 登录态 collector（isLoggedInFlow）在重新登录时会再次驱动 connect()，
        // 在这里硬排重连只会对着空 token 无限空转。
        val token = runBlocking { tokenManager.getAccessToken() } ?: return
        val url = buildWsUrl(token) ?: return
        val request = Request.Builder().url(url).build()
        webSocket = wsClient.newWebSocket(request, listener)
        // 不要在这里复位 reconnectAttempts。
        // connect() 也是断线退避重连的入口，在这里复位会让每次重连都从 1s 重新起算，
        // 指数退避形同虚设（服务端长时间宕机时变成 1s 一次的固定频率打点）。
        // 复位只属于 onOpen —— 连接真正建立才算"恢复健康"。
    }

    @Synchronized
    private fun disconnect() {
        webSocket?.close(1000, "logout")
        webSocket = null
    }

    /** 服务器地址变化：断开旧连接，按新地址重连 */
    @Synchronized
    private fun reconnect() {
        webSocket?.close(1000, "reconnect")
        webSocket = null
        connect()
    }

    private fun scheduleReconnect() {
        synchronized(this) { webSocket = null }
        if (!active) return
        val delayMs = (1000L * (1 shl min(reconnectAttempts, 5))).coerceAtMost(30_000L)
        reconnectAttempts++
        scope.launch {
            delay(delayMs)
            if (active && scope.isActive) connect()
        }
    }

    private fun buildWsUrl(token: String): String? {
        val base = serverPrefs.getBaseUrl().trim().trimEnd('/')
        if (base.isBlank()) return null
        val scheme = if (base.startsWith("https://", ignoreCase = true)) "wss://" else "ws://"
        val host = base.removePrefix("http://").removePrefix("https://")
        return "$scheme$host/ws?token=$token"
    }

    private fun handleText(text: String) {
        runCatching {
            val env = envelopeAdapter.fromJson(text) ?: return
            when (env.type) {
                "usage.today" -> {
                    val dto = usageStatsAdapter.fromJson(text) ?: return
                    // 直接转成领域模型并补绝对图标地址，首页拿到即可用，无需再发 HTTP
                    val stats = dto.toDomain().withAbsoluteIcons()
                    _messages.tryEmit(ParentPushMessage.UsageToday(dto.deviceId, stats))
                }
                // 孩子端完成接入：家长端据此结束"等待孩子扫码"，不必轮询
                "device.enrolled" -> {
                    val dto = enrollmentAdapter.fromJson(text) ?: return
                    val sessionId = dto.sessionId
                    val deviceId = dto.deviceId
                    if (sessionId.isNullOrBlank() || deviceId.isNullOrBlank()) return
                    _messages.tryEmit(ParentPushMessage.DeviceEnrolled(sessionId, deviceId))
                }
            }
        }.onFailure { /* 单条报文解析失败忽略，不影响后续 */ }
    }

    /** 把相对图标地址补成绝对地址，与服务端 HTTP 路径解析口径一致 */
    private fun UsageStats.withAbsoluteIcons(): UsageStats =
        copy(appUsages = appUsages.map { a ->
            a.copy(iconUrl = resolveAbsoluteUrl(serverPrefs.getBaseUrl(), a.iconUrl))
        })

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectAttempts = 0
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handleText(text)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(this@ParentPushClient) { this@ParentPushClient.webSocket = null }
            if (active) scheduleReconnect()
        }
    }
}

/** 推送报文信封：先读 type 决定如何解析，避免把其它类型误当某一种 DTO */
@JsonClass(generateAdapter = true)
private data class PushEnvelope(val type: String?)

/** `device.enrolled` 事件体 */
@JsonClass(generateAdapter = true)
private data class EnrollmentEventDto(val sessionId: String?, val deviceId: String?)

/** 家长端实时推送消息 */
sealed interface ParentPushMessage {
    /** 今日用量快照刷新（deviceId + 领域模型，图标已补成绝对地址） */
    data class UsageToday(val deviceId: String, val stats: UsageStats) : ParentPushMessage

    /** 一台设备完成了接入（孩子端扫码 / 输入接入码后由服务端推送） */
    data class DeviceEnrolled(val sessionId: String, val deviceId: String) : ParentPushMessage
}
