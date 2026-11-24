package com.padguard.parent.data.remote

import com.padguard.parent.BuildConfig
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
 * 连接服务端 `/ws`，鉴权走 **`Authorization: Bearer <JWT>` 请求头**
 * （R-P3-6：此处旧注释写的 `/ws?token=<JWT>` 早已不成立 —— token 拼进 URL 会被反向代理
 * 访问日志、系统代理日志长期留存；服务端仍兼容 query 形态，但仅用于旧版客户端）。
 * 连上后把服务端推来的实时事件按类型分发成 [Flow]。
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
    private val usageStartAdapter = moshi.adapter(UsageStartEventDto::class.java)
    private val unlockAdapter = moshi.adapter(UnlockRequestedEventDto::class.java)
    private val presenceAdapter = moshi.adapter(DevicePresenceEventDto::class.java)
    private val profileUpdatedAdapter = moshi.adapter(DeviceProfileUpdatedEventDto::class.java)
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

    private fun buildWsUrl(): String? =
        // 兜底：地址构建涉及用户可编辑配置（「⚙ 高级设置」），任何解析异常都只能导致
        // 「本轮不建连」，绝不允许把异常抛进 IO 协程把整个 App 打崩。
        // 2026-11-07 的事故正是这里抛了 IllegalArgumentException 却无人捕获：登录后
        // 自动连推送 → 崩溃 → 重启后自动登录 → 再崩，形成无法自救的崩溃循环。
        runCatching { wsUrlOf(serverPrefs.getBaseUrl(), BuildConfig.ALLOW_CLEARTEXT_PUBLIC) }
            .onFailure { android.util.Log.w("ParentPushClient", "构建 WS 地址失败，跳过本次连接", it) }
            .getOrNull()

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
                // 孩子发起了解锁申请：立即触发消息中心刷新（弹窗/铃铛由 refresh 收敛）。
                // 此前服务端只落库不推，家长端只能等 10s 轮询——孩子锁屏干等、家长无感。
                "unlock.requested" -> {
                    val dto = unlockAdapter.fromJson(text) ?: return
                    val deviceId = dto.deviceId ?: return
                    val ticketId = dto.ticketId ?: return
                    _messages.tryEmit(ParentPushMessage.UnlockRequested(deviceId, ticketId))
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
                // 「开始计时」工单创建（家长自己发起，多端登录时另一台设备即时看到）
                // 与闭环（孩子确认）：消费方据此刷新计时状态卡，不必等 15s 轮询。
                // 孩子确认尤其关键——家长正盯着"等待孩子确认"，推送让翻转秒级可见。
                "usage.start.requested", "usage.start.confirmed" -> {
                    val dto = usageStartAdapter.fromJson(text) ?: return
                    val deviceId = dto.deviceId ?: return
                    _messages.tryEmit(
                        ParentPushMessage.UsageStartUpdated(
                            deviceId = deviceId,
                            status = dto.status ?: return,
                            confirmed = dto.confirmedAt != null
                        )
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
                // 孩子端改了姓名/昵称/头像：服务端写完库即推，家长端若正停在设备详情页
                // 必须立刻重拉。此前该 type 无人消费，详情页只有进页面那一次快照，
                // 会出现「首页已是新昵称、详情页还是旧的」同 App 内自相矛盾。
                // 10 秒设备列表轮询是推送丢失时的兜底（见 DeviceDetailViewModel 实时字段指纹）。
                "device.profile.updated" -> {
                    val dto = profileUpdatedAdapter.fromJson(text) ?: return
                    val deviceId = dto.deviceId ?: return
                    _messages.tryEmit(ParentPushMessage.DeviceProfileUpdated(deviceId))
                }
                // 风险告警新增 / 自愈关闭（服务端 AlertService 建警与批量关单时推）。
                // 只作"有事发生"的纯信号：由 RiskAlertCenterViewModel 立即 refresh 全量重拉。
                // 刻意不解析报文体——alert.new 的 deviceId 嵌在 alert 对象里、alert.resolved 在顶层，
                // 两种结构不同；且 WS 不承载告警内容、不当第二事实源，解析失败反而丢信号。
                // 此前这两个 type 无人消费，告警产生与自愈都要等 15s 轮询——"已修好却持续报警"的断裂点。
                "alert.new", "alert.resolved" -> {
                    _messages.tryEmit(ParentPushMessage.AlertsChanged)
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

/** `device.profile.updated` 事件体：只带设备主键，具体内容由随后的详情接口重拉。 */
@JsonClass(generateAdapter = true)
private data class DeviceProfileUpdatedEventDto(
    val deviceId: String? = null,
    val timestamp: Long? = null
)

/** `device.enrolled` 事件体 */
@JsonClass(generateAdapter = true)
private data class EnrollmentEventDto(val sessionId: String?, val deviceId: String?)

/** 解锁申请事件体（`unlock.requested`） */
@JsonClass(generateAdapter = true)
private data class UnlockRequestedEventDto(
    val deviceId: String? = null,
    val ticketId: String? = null
)

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

/** 「开始计时」工单事件体（`usage.start.requested` / `usage.start.confirmed` 共用） */
@JsonClass(generateAdapter = true)
private data class UsageStartEventDto(
    val deviceId: String? = null,
    val requestId: String? = null,
    val status: String? = null,
    val createdAt: Long? = null,
    val confirmedAt: Long? = null
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

    /** 孩子发起了解锁申请（待家长审批），仅服务端 → 家长端下行 */
    data class UnlockRequested(
        val deviceId: String,
        val ticketId: String
    ) : ParentPushMessage

    /** 某条延时工单被家长处理（同意 / 拒绝 / 忽略），用于即时刷新审批列表 */
    data class UsageDelayUpdated(
        val deviceId: String,
        val ticketId: String,
        val status: String
    ) : ParentPushMessage

    /**
     * 「开始计时」工单状态更新（家长发起 / 孩子确认）。
     * 家长端计时卡据此即时切换"未开始 / 等待孩子确认 / 今日已开始"，
     * 不必等时间管控页的 15 秒轮询。
     */
    data class UsageStartUpdated(
        val deviceId: String,
        /** CONFIRMED = 孩子已确认开始计时；PENDING = 已发起待确认 */
        val status: String,
        val confirmed: Boolean
    ) : ParentPushMessage

    /**
     * 设备上线 / 下线（服务端约 5 秒级判定后推送）。
     *
     * 消费方（[com.padguard.parent.data.remote.ApiDataSource]）直接改本地设备列表状态，
     * 让首页/设备页的在线徽标立即翻转；HTTP 轮询退化为"推送丢失时的兜底"。
     */
    data class DevicePresence(val deviceId: String, val online: Boolean) : ParentPushMessage

    /**
     * 孩子端资料（姓名 / 昵称 / 头像）已更新。
     *
     * 消费方 [com.padguard.parent.presentation.viewmodel.DeviceDetailViewModel] 据此静默重拉详情；
     * 只带主键不传内容，避免 WS 报文与详情接口出现第二份资料事实源。
     */
    data class DeviceProfileUpdated(val deviceId: String) : ParentPushMessage

    /**
     * 风险告警发生变化（新增 `alert.new` / 自愈关闭 `alert.resolved`）的纯信号。
     * 消费方 [com.padguard.parent.presentation.viewmodel.RiskAlertCenterViewModel] 收到即全量 refresh，
     * 让红点/列表在秒级跟随服务端状态，而不是等 15s 轮询。不携带告警内容，HTTP 才是唯一事实源。
     */
    data object AlertsChanged : ParentPushMessage
}

/**
 * 把 HTTP baseUrl 转成 WebSocket 地址，纯函数、无 Android 依赖，便于 JVM 单测。
 *
 * ## 两条硬约束（都是 2026-11-07 事故的直接教训）
 * 1. **绝不能** 对 `HttpUrl.Builder` 调 `scheme("ws")` / `scheme("wss")`：
 *    OkHttp 的 `HttpUrl` 只认 `http` / `https`，其它 scheme 直接抛
 *    `IllegalArgumentException: unexpected scheme: ws`。该异常发生在 IO 协程里，
 *    没人捕获就会杀掉进程 —— 表现为「家长端一登录就闪退，重启自动登录又闪退」的死循环。
 *    正确做法是在 http/https 域内拼好路径，再把 scheme 前缀替换成 ws/wss。
 * 2. 主机名 / 端口 / IPv6 方括号一律交给 `HttpUrl.toString()` 渲染，
 *    不手工拼 `"$scheme://$host:$port"`（IPv6 会丢方括号、默认端口会写出 `:443`）。
 *
 * 另外这里要独立过一遍 [EndpointSafety]：WS 不走 `HostSelectionInterceptor`，
 * 握手却带着 `Authorization: Bearer <JWT>`，用 ws:// 过公网等于把令牌明文送出去。
 * 被拒绝时返回 null（调用方走退避重连），绝不降级成明文连接。
 */
internal fun wsUrlOf(rawBase: String, allowCleartextPublic: Boolean): String? {
    val base = rawBase.trim().trimEnd('/')
    if (base.isEmpty()) return null

    val verdict = EndpointSafety.check(base, allowCleartextPublic)
    if (verdict !is EndpointSafety.Verdict.Allowed) return null

    val httpUrl = verdict.normalizedUrl.toHttpUrlOrNull() ?: return null
    // 丢掉 REST 前缀（/v1/、/api/v1/ 都归零为 /ws），否则会连到 /v1/ws —— 服务端无此端点
    val httpForm = httpUrl.newBuilder()
        .encodedPath("/ws")
        .encodedQuery(null)
        .fragment(null)
        .build()
        .toString()
    return if (httpUrl.isHttps) {
        "wss://" + httpForm.removePrefix("https://")
    } else {
        "ws://" + httpForm.removePrefix("http://")
    }
}
