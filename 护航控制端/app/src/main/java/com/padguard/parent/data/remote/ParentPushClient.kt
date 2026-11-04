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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * 事件类型：
 * - `usage.today`：孩子端上行用量后，服务端推送今日用量快照，首页据此即时刷新；
 * - `device.enrolled`：孩子端完成接入后，服务端推送接入完成，家长端据此结束等待；
 * - `device.online` / `device.offline`：设备上下线（服务端秒级判定），设备列表据此即时翻转；
 * - `usage.delay.requested` / `usage.delay.updated`：延时审批工单的创建与处理。
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
    private val usageDelayAdapter = moshi.adapter(UsageDelayEventDto::class.java)
    private val presenceAdapter = moshi.adapter(DevicePresenceEventDto::class.java)
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

    private suspend fun setActive(shouldBeActive: Boolean) {
        active = shouldBeActive
        if (shouldBeActive) connect() else disconnect()
    }

    /**
     * 第八轮 P2-7：原实现用 `runBlocking` 读 DataStore（token），阻塞 IO 线程池线程；
     * 且 `@Synchronized` 会在挂起点持锁（DataStore 读取是挂起 IO），高并发重连时可能长时间占住锁。
     * 改为 suspend + Mutex：等价互斥，不阻塞线程，所有调用点本就运行在 [scope] 协程内。
     *
     * 第十七轮修正：`disconnect()` / `reconnect()` 原先用 `@Synchronized`（对象监视器），
     * 与这里的 Mutex 是**两把互不相干的锁**，登出与建连仍能交错：
     * connect 在读 token 的挂起点上让出线程 → 登出拿到监视器、看到 webSocket 还是 null、
     * 什么也没关 → connect 醒来后建出一条**已鉴权但无人引用**的连接，永远不会被关闭。
     * 现在建连、断连、换地址重连**全部串到同一把 Mutex** 上，登出必然能关掉刚建好的连接。
     */
    private val connectMutex = Mutex()

    private suspend fun connect() = connectMutex.withLock { openSocketLocked() }

    /** 必须持有 [connectMutex] 才能调用 */
    private fun closeSocketLocked(reason: String) {
        webSocket?.close(1000, reason)
        webSocket = null
    }

    /** 必须持有 [connectMutex] 才能调用 */
    private suspend fun openSocketLocked() {
        if (webSocket != null) return
        // 拿到锁后复查 active：登出（disconnect）与退避重连竞态时，
        // 不复查会在登出后仍建立一条已认证连接且无人持有引用去关它。
        if (!active) return
        // token 拿不到（登录态刚被清 / 刷新尚未完成）→ 不连也不排重连：
        // 登录态 collector（isLoggedInFlow）在重新登录时会再次驱动 connect()，
        // 在这里硬排重连只会对着空 token 无限空转。
        val token = tokenManager.getAccessToken() ?: return
        val url = buildWsUrl() ?: return
        // 第八轮评估 P1-F：JWT 改走 Authorization 头，不再拼进 URL ——
        // URL 里的 token 会被反向代理访问日志、系统代理日志长期留存。
        // 服务端已支持头鉴权（query 兼容保留，仅供旧版客户端）。
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .build()
        webSocket = wsClient.newWebSocket(request, listener)
        // 不要在这里复位 reconnectAttempts。
        // connect() 也是断线退避重连的入口，在这里复位会让每次重连都从 1s 重新起算，
        // 指数退避形同虚设（服务端长时间宕机时变成 1s 一次的固定频率打点）。
        // 复位只属于 onOpen —— 连接真正建立才算"恢复健康"。
    }

    private suspend fun disconnect() = connectMutex.withLock { closeSocketLocked("logout") }

    /** 服务器地址变化：断开旧连接，按新地址重连（同一把锁内完成，登出不会插到中间） */
    private suspend fun reconnect() = connectMutex.withLock {
        closeSocketLocked("reconnect")
        openSocketLocked()
    }

    private fun scheduleReconnect() {
        // 连接引用由调用方（onFailure）在锁内清空，这里只负责排期
        if (!active) return
        val delayMs = (1000L * (1 shl min(reconnectAttempts, 5))).coerceAtMost(30_000L)
        reconnectAttempts++
        scope.launch {
            delay(delayMs)
            if (active && scope.isActive) connect()
        }
    }

    private fun buildWsUrl(): String? {
        val base = serverPrefs.getBaseUrl().trim().trimEnd('/')
        if (base.isBlank()) return null
        val scheme = if (base.startsWith("https://", ignoreCase = true)) "wss://" else "ws://"
        val host = base.removePrefix("http://").removePrefix("https://")
        return "$scheme$host/ws"
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
                // 孩子提交了延时申请：家长端据此即时弹出审批入口（仍由轮询兜底，防推送丢失）
                "usage.delay.requested" -> {
                    val dto = usageDelayAdapter.fromJson(text) ?: return
                    val deviceId = dto.deviceId ?: return
                    val ticketId = dto.ticketId ?: return
                    val minutes = dto.minutes ?: return
                    val createdAt = dto.createdAt ?: return
                    _messages.tryEmit(
                        ParentPushMessage.UsageDelayRequested(deviceId, ticketId, minutes, createdAt)
                    )
                }
                // 某条延时工单被处理（同意/拒绝/忽略）：家长端据此即时刷新审批列表
                "usage.delay.updated" -> {
                    val dto = usageDelayAdapter.fromJson(text) ?: return
                    val deviceId = dto.deviceId ?: return
                    val ticketId = dto.ticketId ?: return
                    val status = dto.status ?: return
                    _messages.tryEmit(
                        ParentPushMessage.UsageDelayUpdated(deviceId, ticketId, status)
                    )
                }
                // 设备上下线：服务端判定出状态翻转即刻推（孩子端 3s 一次存活信号，
                // 服务端约 5s 无信号判离线）。家长端拿到就改本地列表状态，
                // 不必等 10s 一次的列表轮询 —— 那条轮询现在只作为推送丢失的兜底。
                "device.online" -> {
                    val dto = presenceAdapter.fromJson(text) ?: return
                    val deviceId = dto.deviceId ?: return
                    _messages.tryEmit(ParentPushMessage.DevicePresence(deviceId, online = true))
                }
                "device.offline" -> {
                    val dto = presenceAdapter.fromJson(text) ?: return
                    val deviceId = dto.deviceId ?: return
                    _messages.tryEmit(ParentPushMessage.DevicePresence(deviceId, online = false))
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
            // 身份比对：reconnect() 关旧连 A、建新连 B 后，A 的 onClosed/onFailure 会异步迟到。
            // 无差别置空会把 B 也清掉并再排一次重连（B、C 并存，事件双份，滚雪球）。
            // 只有"死掉的 socket 仍是当前连接"时才清理并排重连。
            //
            // 清理动作必须在 connectMutex 内做：回调线程与 connect() 并发时，
            // 先判后清（原实现用 synchronized 是另一把锁）会把刚建好的新连接清掉。
            if (this@ParentPushClient.webSocket !== webSocket) return
            scope.launch {
                val cleared = connectMutex.withLock {
                    if (this@ParentPushClient.webSocket === webSocket) {
                        this@ParentPushClient.webSocket = null
                        true
                    } else {
                        false
                    }
                }
                if (cleared && active) scheduleReconnect()
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            onFailure(webSocket, java.io.IOException("closed: $code $reason"), null)
        }
    }
}

/** 推送报文信封：先读 type 决定如何解析，避免把其它类型误当某一种 DTO */
@JsonClass(generateAdapter = true)
private data class PushEnvelope(val type: String?)

/**
 * `device.online` / `device.offline` 事件体。
 *
 * 两个事件共用同一形状（都只带设备主键），`lastSeen` 仅 offline 携带，这里不解析 ——
 * 家长端要的是"在不在线"，具体时刻由随后的列表刷新补齐，少一个字段就少一处不一致。
 */
@JsonClass(generateAdapter = true)
private data class DevicePresenceEventDto(val deviceId: String? = null)

/** `device.enrolled` 事件体 */
@JsonClass(generateAdapter = true)
private data class EnrollmentEventDto(val sessionId: String?, val deviceId: String?)

/**
 * 延时审批工单事件体（`usage.delay.requested` / `usage.delay.updated` 共用，
 * 各自只带自己关心的字段，其余为 null）。
 */
@JsonClass(generateAdapter = true)
private data class UsageDelayEventDto(
    val deviceId: String? = null,
    val ticketId: String? = null,
    val minutes: Int? = null,
    val createdAt: Long? = null,
    val status: String? = null
)

/** 家长端实时推送消息 */
sealed interface ParentPushMessage {
    /** 今日用量快照刷新（deviceId + 领域模型，图标已补成绝对地址） */
    data class UsageToday(val deviceId: String, val stats: UsageStats) : ParentPushMessage

    /** 一台设备完成了接入（孩子端扫码 / 输入接入码后由服务端推送） */
    data class DeviceEnrolled(val sessionId: String, val deviceId: String) : ParentPushMessage

    /** 孩子提交了延时申请（待家长审批），仅服务端 → 家长端下行 */
    data class UsageDelayRequested(
        val deviceId: String,
        val ticketId: String,
        val minutes: Int,
        val createdAt: Long
    ) : ParentPushMessage

    /** 某条延时工单被家长处理（同意 / 拒绝 / 忽略），用于即时刷新审批列表 */
    data class UsageDelayUpdated(
        val deviceId: String,
        val ticketId: String,
        val status: String
    ) : ParentPushMessage

    /**
     * 设备上线 / 下线（服务端约 5 秒级判定后推送）。
     *
     * 消费方（[com.padguard.parent.data.remote.ApiDataSource]）直接改本地设备列表状态，
     * 让首页/设备页的在线徽标立即翻转；HTTP 轮询退化为"推送丢失时的兜底"。
     */
    data class DevicePresence(val deviceId: String, val online: Boolean) : ParentPushMessage
}
