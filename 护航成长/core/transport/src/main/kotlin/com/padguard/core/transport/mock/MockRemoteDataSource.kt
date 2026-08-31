package com.padguard.core.transport.mock

import com.padguard.core.common.Crypto
import com.padguard.core.common.Logger
import com.padguard.core.common.TimeProvider
import com.padguard.core.data.model.BehaviorLog
import com.padguard.core.data.model.BindResult
import com.padguard.core.data.model.Command
import com.padguard.core.data.model.CommandAck
import com.padguard.core.data.model.CommandType
import com.padguard.core.data.model.Heartbeat
import com.padguard.core.data.model.LocationInfo
import com.padguard.core.data.model.RiskEvent
import com.padguard.core.data.model.policy.PolicyPackage
import com.padguard.core.data.model.policy.SceneType
import com.padguard.core.transport.CommandGate
import com.padguard.core.transport.Downlink
import com.padguard.core.transport.RemoteDataSource
import com.padguard.core.transport.TransportMode
import com.padguard.core.transport.TransportSettings
import com.padguard.core.transport.http.ApiCode
import com.padguard.core.transport.http.ApiResult
import com.padguard.core.transport.http.BindRequest
import com.padguard.core.transport.http.HeartbeatAck
import com.padguard.core.transport.http.LogUploadResponse
import com.padguard.core.transport.http.PolicyFetchResult
import com.padguard.core.transport.http.ScreenshotAck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * 本地 Mock 服务端（服务端未就绪期间的替身）。
 *
 * ## 为什么不用「假数据 + 直接 emit」的简易 Mock
 * 简易 Mock 会绕过签名、绕过幂等、绕过错误码分支，等真服务端上线时，
 * 终端里所有"只有在真实响应下才会走到"的代码路径都是第一次执行 —— 风险全部堆到联调期。
 *
 * 本实现刻意让 Mock 尽可能"像真的"：
 *
 * | 维度 | 做法 | 覆盖到的终端代码 |
 * |---|---|---|
 * | 指令签名 | 用 [MockServerState.HMAC_SECRET] 真实 HMAC-SHA256 签发 | CommandGate 关卡 2 完整执行 |
 * | 指令幂等 | 同一 msgId 可重复投递（[injectDuplicate]） | CommandGate 关卡 3 + DUPLICATED 回执 |
 * | 指令时效 | 可签发已过期指令（[injectExpired]） | CommandGate 关卡 1 |
 * | 伪造攻击 | 可用错误密钥签发（[injectForged]） | INVALID_SIGNATURE 高危告警链路 |
 * | 网络故障 | [failureRate] 概率性返回 Failure | 上层重试 / 离线队列 |
 * | 业务错误 | 特定绑定码返回 40302/40303 | 绑定页错误提示分支 |
 * | 通道降级 | 跟随 [TransportSettings.mode] 走推送或队列 | 轮询兜底路径 |
 *
 * ## 安全约束
 * 该类只在 `USE_MOCK_SERVER=true`（debug）时被 Hilt 装配；
 * release 构建下 [TransportSettings.useMock] 恒为 false，不会被选中，
 * 避免正式包出现"界面显示已受控、实际没连服务端"的致命假象。
 */
@Singleton
class MockRemoteDataSource @Inject constructor(
    private val commandGate: CommandGate,
    private val timeProvider: TimeProvider,
    private val settings: TransportSettings,
    private val json: Json
) : RemoteDataSource {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _downlink = MutableSharedFlow<Downlink>(extraBufferCapacity = 64)
    override val downlink: Flow<Downlink> = _downlink.asSharedFlow()

    /** Mock 服务端侧状态，供调试页直接展示，便于肉眼确认链路 */
    private val _stats = MutableStateFlow(MockStats())
    val stats: StateFlow<MockStats> = _stats.asStateFlow()

    private val stateMutex = Mutex()

    /** 待下发指令队列：轮询模式下由 [pullCommands] 取走 */
    private val pendingCommands = ArrayDeque<Command>()

    /** 已收到的回执，调试页可查看"指令是否真的被执行了" */
    private val receivedAcks = ArrayDeque<CommandAck>()

    private var deviceId: String = ""
    private var policyVersion: Int = 1
    private var scene: SceneType = SceneType.FAMILY
    private var started = false

    private val heartbeatCounter = AtomicInteger()

    /** 模拟网络往返延迟（毫秒），0 表示不延迟 */
    @Volatile
    var latencyMs: Long = 120L

    /** 模拟网络故障概率 0f~1f。混沌测试用：验证上层重试与离线队列是否真的兜住了 */
    @Volatile
    var failureRate: Float = 0f

    // ==================== 通道生命周期 ====================

    override suspend fun start() {
        stateMutex.withLock {
            if (started) return
            started = true
        }
        Logger.i(TAG) { "mock transport started, mode=${settings.mode.value}" }
        scope.launch {
            // 模拟建连耗时，让上层的"连接中"状态有机会被观察到
            delay(300)
            _downlink.emit(Downlink.ConnectivityChanged(online = true, mode = settings.mode.value))
            _stats.value = _stats.value.copy(online = true)
        }
    }

    override suspend fun stop() {
        stateMutex.withLock {
            if (!started) return
            started = false
        }
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancelChildren()
        _downlink.emit(Downlink.ConnectivityChanged(online = false, mode = settings.mode.value))
        _stats.value = _stats.value.copy(online = false)
        Logger.i(TAG) { "mock transport stopped" }
    }

    override fun isOnline(): Boolean = started && settings.mode.value == TransportMode.MQTT

    // ==================== 一次性请求 ====================

    override suspend fun bind(request: BindRequest): ApiResult<BindResult> {
        simulateNetwork<BindResult>()?.let { return it }

        // 绑定码格式校验放在 Mock 里，让终端的输入校验与错误提示能被真正验证
        when (request.bindCode.trim()) {
            MockServerState.BIND_CODE_EXPIRED ->
                return ApiResult.BizError(ApiCode.BIND_CODE_EXPIRED, "绑定码已过期，请让家长/老师重新生成")

            MockServerState.BIND_CODE_CONFLICT ->
                return ApiResult.BizError(ApiCode.BIND_CONFLICT, "该绑定码已被其他设备使用")
        }
        if (request.bindCode.trim().length !in 6..12) {
            return ApiResult.BizError(ApiCode.BIND_CODE_EXPIRED, "绑定码格式不正确")
        }

        val id = "MOCK-" + Crypto.sha256Hex(request.fingerprint.ifBlank { UUID.randomUUID().toString() })
            .take(12).uppercase()

        stateMutex.withLock {
            deviceId = id
            policyVersion = 1
        }
        _stats.value = _stats.value.copy(bound = true, deviceId = id, policyVersion = 1)
        Logger.i(TAG) { "mock bind ok: deviceId=$id sn=${request.deviceSn} mode=${request.controlMode}" }

        return ApiResult.Success(
            BindResult(
                deviceId = id,
                deviceToken = MockServerState.DEVICE_TOKEN_PREFIX + UUID.randomUUID().toString().take(16),
                mqttUsername = "mock-$id",
                mqttPassword = UUID.randomUUID().toString().take(20),
                hmacSecret = MockServerState.HMAC_SECRET,
                // 30 天后过期，用于验证 token 续期逻辑
                expiresAt = timeProvider.now() + 30L * 24 * 3600 * 1000,
                // Mock 不覆盖接入点，保持终端使用构建期配置
                baseUrl = "",
                mqttBroker = "",
                tenantId = MockServerState.TENANT_ID
            ),
            timeProvider.now()
        )
    }

    override suspend fun syncTime(): ApiResult<Long> {
        simulateNetwork<Long>()?.let { return it }
        // 故意给一点偏差，验证终端 offset 校准是否生效
        val serverTime = System.currentTimeMillis() + MOCK_SERVER_CLOCK_SKEW_MS
        timeProvider.syncServerTime(serverTime, latencyMs)
        return ApiResult.Success(serverTime, serverTime)
    }

    override suspend fun fetchPolicy(currentVersion: Int): ApiResult<PolicyFetchResult> {
        simulateNetwork<PolicyFetchResult>()?.let { return it }

        val (id, version, currentScene) = stateMutex.withLock {
            Triple(deviceId, policyVersion, scene)
        }
        if (currentVersion >= version) {
            Logger.d(TAG) { "policy up to date (local=$currentVersion, server=$version)" }
            return ApiResult.Success(PolicyFetchResult.UpToDate, timeProvider.now())
        }

        val policy = buildPolicy(id, version, currentScene)
        // 与真实链路一致：rawJson 才是落库对象，避免"反序列化再序列化"丢掉未知字段
        val raw = json.encodeToString(PolicyPackage.serializer(), policy)
        Logger.i(TAG) { "policy delivered: v$currentVersion -> v$version, scene=$currentScene, ${raw.length}B" }
        return ApiResult.Success(PolicyFetchResult.Updated(policy, raw), timeProvider.now())
    }

    // ==================== 上行 ====================

    override suspend fun sendHeartbeat(heartbeat: Heartbeat): ApiResult<HeartbeatAck> {
        simulateNetwork<HeartbeatAck>()?.let { return it }

        val count = heartbeatCounter.incrementAndGet()
        val (version, pending) = stateMutex.withLock { policyVersion to pendingCommands.size }
        _stats.value = _stats.value.copy(
            heartbeatCount = count,
            lastHeartbeatAt = timeProvider.now(),
            lastForegroundApp = heartbeat.foregroundApp,
            lastBatteryLevel = heartbeat.battery.level,
            pendingCommandCount = pending
        )
        Logger.d(TAG) {
            "heartbeat #$count from ${heartbeat.deviceId}: policyV=${heartbeat.policyVersion} " +
                "mode=${heartbeat.transportMode} control=${heartbeat.controlMode} fg=${heartbeat.foregroundApp}"
        }
        return ApiResult.Success(
            HeartbeatAck(
                policyVersion = version,
                pendingCommandCount = pending,
                // 每 10 个心跳要求一次日志上报，验证服务端驱动的 flush 分支
                flushLogs = count % 10 == 0
            ),
            timeProvider.now()
        )
    }

    override suspend fun sendAck(ack: CommandAck): Boolean {
        if (rollFailure()) {
            Logger.w(TAG) { "mock ack dropped (chaos): ${ack.msgId}" }
            return false
        }
        delayIfNeeded()
        stateMutex.withLock {
            receivedAcks.addLast(ack)
            while (receivedAcks.size > ACK_HISTORY) receivedAcks.removeFirst()
        }
        _stats.value = _stats.value.copy(
            ackCount = _stats.value.ackCount + 1,
            lastAck = "${ack.msgId.take(8)} ${ack.status}"
        )
        Logger.i(TAG) { "ack received: ${ack.msgId} -> ${ack.status} ${ack.errorMessage.orEmpty()}" }
        return true
    }

    override suspend fun uploadLogs(deviceId: String, logs: List<BehaviorLog>): ApiResult<LogUploadResponse> {
        simulateNetwork<LogUploadResponse>()?.let { return it }
        _stats.value = _stats.value.copy(uploadedLogCount = _stats.value.uploadedLogCount + logs.size)
        Logger.d(TAG) { "logs accepted: ${logs.size} (types=${logs.map { it.type }.distinct()})" }
        return ApiResult.Success(LogUploadResponse(accepted = logs.size, duplicated = 0), timeProvider.now())
    }

    override suspend fun uploadEvents(deviceId: String, events: List<RiskEvent>): ApiResult<LogUploadResponse> {
        simulateNetwork<LogUploadResponse>()?.let { return it }
        _stats.value = _stats.value.copy(
            uploadedEventCount = _stats.value.uploadedEventCount + events.size,
            lastEvent = events.lastOrNull()?.let { "${it.type}/${it.level}" }.orEmpty()
        )
        events.forEach { Logger.w(TAG) { "risk event: ${it.type} ${it.level} ${it.detail}" } }
        return ApiResult.Success(LogUploadResponse(accepted = events.size), timeProvider.now())
    }

    override suspend fun uploadLocations(deviceId: String, points: List<LocationInfo>): ApiResult<Unit> {
        simulateNetwork<Unit>()?.let { return it }
        _stats.value = _stats.value.copy(
            uploadedLocationCount = _stats.value.uploadedLocationCount + points.size,
            lastLocation = points.lastOrNull()?.let { "%.5f,%.5f".format(it.lat, it.lng) }.orEmpty()
        )
        return ApiResult.Success(Unit, timeProvider.now())
    }

    override suspend fun uploadScreenshot(
        shotId: String,
        capturedAt: Long,
        triggerType: String,
        jpeg: ByteArray
    ): ApiResult<ScreenshotAck> {
        simulateNetwork<ScreenshotAck>()?.let { return it }
        _stats.value = _stats.value.copy(
            screenshotCount = _stats.value.screenshotCount + 1,
            lastScreenshotKb = jpeg.size / 1024
        )
        Logger.i(TAG) { "screenshot accepted: $shotId ${jpeg.size / 1024}KB trigger=$triggerType" }
        return ApiResult.Success(ScreenshotAck(shotId = shotId, accepted = true), timeProvider.now())
    }

    override suspend fun pullCommands(since: Long): ApiResult<List<Command>> {
        simulateNetwork<List<Command>>()?.let { return it }
        val drained = stateMutex.withLock {
            val list = pendingCommands.toList()
            pendingCommands.clear()
            list
        }
        if (drained.isNotEmpty()) {
            Logger.i(TAG) { "polling delivered ${drained.size} command(s)" }
            _stats.value = _stats.value.copy(pendingCommandCount = 0)
        }
        return ApiResult.Success(drained, timeProvider.now())
    }

    // ==================== 调试注入 API（供调试页调用） ====================

    /**
     * 下发一条正常指令。
     *
     * MQTT 模式直接推送（走 [CommandGate] 后进 [downlink]）；
     * 轮询模式则入队，等终端下一轮 [pullCommands] 取走 —— 两条路径都能被真实验证。
     */
    suspend fun injectCommand(
        type: CommandType,
        payload: Map<String, String> = emptyMap(),
        ttlSec: Long = 300
    ): String {
        val now = timeProvider.now()
        val command = sign(
            Command(
                msgId = "mock-" + UUID.randomUUID().toString().take(12),
                type = type,
                timestamp = now,
                expiresAt = now + ttlSec * 1000,
                priority = if (type == CommandType.WIPE) Command.Priority.HIGH else Command.Priority.NORMAL,
                payload = payload
            ),
            MockServerState.HMAC_SECRET
        )
        deliver(command)
        return command.msgId
    }

    /** 重复投递同一条指令，验证幂等窗口是否真的拦住了（应回执 DUPLICATED） */
    suspend fun injectDuplicate(type: CommandType = CommandType.LOCK_SCREEN) {
        val now = timeProvider.now()
        val command = sign(
            Command(
                msgId = "mock-dup-fixed-id",
                type = type,
                timestamp = now,
                expiresAt = now + 300_000,
                payload = mapOf("reason" to "duplicate test")
            ),
            MockServerState.HMAC_SECRET
        )
        deliver(command)
        deliver(command)
    }

    /** 下发一条已过期指令，验证时效关卡（应回执 REJECTED，不得执行） */
    suspend fun injectExpired(type: CommandType = CommandType.UNLOCK) {
        val now = timeProvider.now()
        val command = sign(
            Command(
                msgId = "mock-exp-" + UUID.randomUUID().toString().take(8),
                type = type,
                timestamp = now - 600_000,
                expiresAt = now - 60_000,
                payload = mapOf("reason" to "expired test")
            ),
            MockServerState.HMAC_SECRET
        )
        deliver(command)
    }

    /**
     * 用错误密钥签发指令，模拟中间人/自建 Broker 伪造攻击。
     * 预期：被拒 + 上报 INVALID_SIGNATURE 高危事件（这是最关键的一条安全断言）。
     */
    suspend fun injectForged(type: CommandType = CommandType.WIPE) {
        val now = timeProvider.now()
        val command = sign(
            Command(
                msgId = "mock-forge-" + UUID.randomUUID().toString().take(8),
                type = type,
                timestamp = now,
                expiresAt = now + 300_000,
                payload = mapOf("reason" to "forged signature test")
            ),
            secret = "wrong-secret-attacker"
        )
        deliver(command)
    }

    /** 服务端侧改策略：版本号 +1 并推送变更通知，终端应随后走 HTTPS 拉全量 */
    suspend fun bumpPolicy(newScene: SceneType? = null) {
        val version = stateMutex.withLock {
            newScene?.let { scene = it }
            ++policyVersion
        }
        _stats.value = _stats.value.copy(policyVersion = version, scene = (newScene ?: scene).name)
        _downlink.emit(Downlink.PolicyChanged(version))
        Logger.i(TAG) { "policy bumped to v$version (scene=${newScene ?: scene})" }
    }

    /** 推送运行参数变更（等价于 UPDATE_CONFIG 的 config topic 形态） */
    suspend fun pushConfig(values: Map<String, String>) {
        _downlink.emit(Downlink.ConfigChanged(values))
    }

    /** 模拟 MQTT 掉线：验证降级为轮询后指令是否仍能送达 */
    suspend fun simulateDisconnect(degradeToPolling: Boolean = true) {
        if (degradeToPolling) settings.setMode(TransportMode.POLLING)
        _stats.value = _stats.value.copy(online = false)
        _downlink.emit(Downlink.ConnectivityChanged(online = false, mode = settings.mode.value))
    }

    suspend fun simulateReconnect() {
        settings.setMode(TransportMode.MQTT)
        _stats.value = _stats.value.copy(online = true)
        _downlink.emit(Downlink.ConnectivityChanged(online = true, mode = TransportMode.MQTT))
    }

    fun recentAcks(): List<CommandAck> = receivedAcks.toList()

    // ==================== 内部实现 ====================

    /** 与真实服务端使用同一套 signingPayload 规范化规则，字段顺序不一致会立刻暴露 */
    private fun sign(command: Command, secret: String): Command =
        command.copy(signature = Crypto.hmacSha256(command.signingPayload(), secret))

    private suspend fun deliver(command: Command) {
        if (settings.mode.value == TransportMode.POLLING || !started) {
            stateMutex.withLock {
                pendingCommands.addLast(command)
                _stats.value = _stats.value.copy(pendingCommandCount = pendingCommands.size)
            }
            Logger.d(TAG) { "command queued for polling: ${command.msgId} ${command.type}" }
            return
        }
        dispatch(command)
    }

    /** 推送路径也必须过门卫，Mock 不给任何"特权通道" */
    private suspend fun dispatch(command: Command) {
        when (val verdict = commandGate.evaluate(command)) {
            is CommandGate.Verdict.Accepted ->
                _downlink.emit(Downlink.CommandReceived(verdict.command))

            is CommandGate.Verdict.Rejected -> _downlink.emit(
                Downlink.CommandRejected(
                    ack = CommandAck(
                        msgId = verdict.command.msgId,
                        deviceId = deviceId,
                        status = verdict.ackStatus,
                        executedAt = timeProvider.now(),
                        errorCode = verdict.ackStatus.name,
                        errorMessage = verdict.reason
                    ),
                    reason = verdict.reason
                )
            )
        }
    }

    private fun buildPolicy(id: String, version: Int, currentScene: SceneType): PolicyPackage {
        val now = timeProvider.now()
        return when (currentScene) {
            SceneType.FAMILY -> MockServerState.familyPolicy(id, version, now)
            SceneType.SCHOOL -> MockServerState.schoolPolicy(id, version, now)
        }
    }

    /** 统一的网络模拟：先延迟，再按概率抛故障。返回非 null 表示调用方应直接返回该结果。 */
    private suspend fun <T> simulateNetwork(): ApiResult<T>? {
        delayIfNeeded()
        return if (rollFailure()) {
            Logger.w(TAG) { "mock network failure injected (rate=$failureRate)" }
            ApiResult.Failure(ApiCode.LOCAL_NETWORK_ERROR, "mock injected network failure")
        } else {
            null
        }
    }

    private suspend fun delayIfNeeded() {
        if (latencyMs > 0) delay(latencyMs)
    }

    private fun rollFailure(): Boolean = failureRate > 0f && Random.nextFloat() < failureRate

    companion object {
        private const val TAG = "MockRemote"
        private const val ACK_HISTORY = 50

        /** Mock 服务端与本机墙钟的固定偏差：7 秒，用于验证 offset 校准确实生效 */
        private const val MOCK_SERVER_CLOCK_SKEW_MS = 7_000L
    }
}

/** Mock 服务端可观测状态，调试页直接绑定展示 */
data class MockStats(
    val bound: Boolean = false,
    val online: Boolean = false,
    val deviceId: String = "",
    val policyVersion: Int = 0,
    val scene: String = SceneType.FAMILY.name,
    val heartbeatCount: Int = 0,
    val lastHeartbeatAt: Long = 0L,
    val lastForegroundApp: String = "",
    val lastBatteryLevel: Int = 0,
    val pendingCommandCount: Int = 0,
    val ackCount: Int = 0,
    val lastAck: String = "",
    val uploadedLogCount: Int = 0,
    val uploadedEventCount: Int = 0,
    val lastEvent: String = "",
    val uploadedLocationCount: Int = 0,
    val lastLocation: String = "",
    val screenshotCount: Int = 0,
    val lastScreenshotKb: Int = 0
)
