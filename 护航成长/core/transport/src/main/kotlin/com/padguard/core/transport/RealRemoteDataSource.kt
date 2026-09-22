package com.padguard.core.transport

import com.padguard.core.common.Logger
import com.padguard.core.common.TimeProvider
import com.padguard.core.data.model.BehaviorLog
import com.padguard.core.data.model.BindResult
import com.padguard.core.data.model.Command
import com.padguard.core.data.model.CommandAck
import com.padguard.core.data.model.Heartbeat
import com.padguard.core.data.model.LocationInfo
import com.padguard.core.data.model.RiskEvent
import com.padguard.core.data.model.policy.PolicyPackage
import com.padguard.core.transport.http.AppInventoryItem
import com.padguard.core.transport.http.AppInventoryRequest
import com.padguard.core.transport.http.AppSyncAck
import com.padguard.core.transport.http.AckUploadRequest
import com.padguard.core.transport.http.ApiCaller
import com.padguard.core.transport.http.ApiCode
import com.padguard.core.transport.http.ApiResult
import com.padguard.core.transport.http.BindRequest
import com.padguard.core.transport.http.CredentialStore
import com.padguard.core.transport.http.EventUploadRequest
import com.padguard.core.transport.http.HeartbeatAck
import com.padguard.core.transport.http.LocationUploadRequest
import com.padguard.core.transport.http.LogUploadRequest
import com.padguard.core.transport.http.LogUploadResponse
import com.padguard.core.transport.http.MediaAck
import com.padguard.core.transport.http.PadGuardApi
import com.padguard.core.transport.http.PolicyFetchResult
import com.padguard.core.transport.http.ScreenshotAck
import com.padguard.core.transport.mqtt.MqttInbound
import com.padguard.core.transport.mqtt.MqttTopics
import com.padguard.core.transport.mqtt.MqttTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 真实服务端实现：MQTT 实时通道 + HTTPS 大包通道 + 轮询降级。
 *
 * 通道选择由 [TransportSettings.mode] 决定，切换是自动的：
 * ```
 * MQTT 连接成功 ──────────────► MQTT 模式（心跳/指令/回执走 MQTT）
 *        │ 连续 3 次失败
 *        ▼
 * POLLING 模式（心跳/指令/回执全走 HTTPS）
 *        │ 每轮轮询后尝试重连 MQTT，成功即切回
 *        ▲───────────────────────┘
 * ```
 * 无论哪种模式，策略包与批量日志始终走 HTTPS —— MQTT 不适合传大包，
 * 且策略包需要 gzip + 断点重试，走 REST 更可控。
 */
@Singleton
class RealRemoteDataSource @Inject constructor(
    private val api: PadGuardApi,
    private val caller: ApiCaller,
    private val mqtt: MqttTransport,
    private val gate: CommandGate,
    private val credentials: CredentialStore,
    private val settings: TransportSettings,
    private val timeProvider: TimeProvider,
    private val json: Json
) : RemoteDataSource {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startMutex = Mutex()

    private val _downlink = MutableSharedFlow<Downlink>(extraBufferCapacity = 128)
    override val downlink: Flow<Downlink> = _downlink.asSharedFlow()

    private var inboundJob: Job? = null
    private var connectivityJob: Job? = null
    private var pollingJob: Job? = null

    /**
     * MQTT 发布失败的回执重试队列（内存态）。
     * 之所以不落库：回执丢失的后果是服务端显示"执行中"，而 CommandRecord 表里
     * 已有完整执行留痕，服务端可通过下一次心跳的 pendingCommandCount 对账补偿。
     * 落库会带来"重启后重发大量陈旧回执"的噪音，收益不成正比。
     */
    private val ackRetryQueue = ConcurrentLinkedQueue<CommandAck>()

    @Volatile
    private var lastPollAt: Long = 0L

    @Volatile
    private var pollingHealthy = false

    override suspend fun start() = startMutex.withLock {
        credentials.refresh()
        if (!credentials.isBound) {
            Logger.i(TAG) { "not bound, transport stays idle" }
            return
        }

        if (inboundJob == null) {
            inboundJob = scope.launch {
                mqtt.inbound.collect { handleInbound(it) }
            }
        }
        if (connectivityJob == null) {
            connectivityJob = scope.launch {
                mqtt.connected.collect { online ->
                    _downlink.tryEmit(Downlink.ConnectivityChanged(online, settings.mode.value))
                    if (online) flushAckRetryQueue()
                }
            }
        }

        val connected = mqtt.connect()
        if (!connected) {
            Logger.w(TAG) { "mqtt unavailable, falling back to HTTPS polling" }
            settings.setMode(TransportMode.POLLING)
        }
        ensurePollingLoop()
    }

    override suspend fun stop() = startMutex.withLock {
        pollingJob?.cancel(); pollingJob = null
        inboundJob?.cancel(); inboundJob = null
        connectivityJob?.cancel(); connectivityJob = null
        mqtt.disconnect()
    }

    override fun isOnline(): Boolean = when (settings.mode.value) {
        TransportMode.MQTT -> mqtt.connected.value
        TransportMode.POLLING -> pollingHealthy
    }

    // ==================== 一次性请求 ====================

    override suspend fun bind(request: BindRequest): ApiResult<BindResult> {
        val result = caller.call("bind") { api.bind(request) }
        if (result is ApiResult.Success) {
            // 服务端可下发接入点覆盖构建期默认值（契约 V1.1）
            result.value.baseUrl.takeIf { it.isNotBlank() }?.let(settings::setBaseUrl)
            result.value.mqttBroker.takeIf { it.isNotBlank() }?.let(settings::setMqttBroker)
        }
        return result
    }

    override suspend fun syncTime(): ApiResult<Long> =
        when (val r = caller.call("time") { api.serverTime() }) {
            is ApiResult.Success -> {
                // ApiCaller 已完成校准，这里只把值透出给调用方展示
                ApiResult.Success(r.value.serverTime, r.serverTime)
            }
            is ApiResult.BizError -> r
            is ApiResult.Failure -> r
        }

    override suspend fun fetchPolicy(currentVersion: Int): ApiResult<PolicyFetchResult> {
        return when (val r = caller.callEnvelope("policy") { api.policy(currentVersion) }) {
            is ApiResult.BizError -> r
            is ApiResult.Failure -> r
            is ApiResult.Success -> {
                val element = r.value.data
                    ?: return ApiResult.Failure(ApiCode.LOCAL_PARSE_ERROR, "policy: empty data")
                val obj = runCatching { element.jsonObject }.getOrNull()
                    ?: return ApiResult.Failure(ApiCode.LOCAL_PARSE_ERROR, "policy: data is not an object")

                if (obj["upToDate"]?.jsonPrimitive?.booleanOrNull == true) {
                    return ApiResult.Success(PolicyFetchResult.UpToDate, r.serverTime)
                }
                val rawJson = obj.toString()
                val policy = runCatching {
                    json.decodeFromString(PolicyPackage.serializer(), rawJson)
                }.getOrElse {
                    Logger.e(TAG, it) { "failed to parse policy package" }
                    return ApiResult.Failure(ApiCode.LOCAL_PARSE_ERROR, "policy parse failed: ${it.message}", it)
                }
                ApiResult.Success(PolicyFetchResult.Updated(policy, rawJson), r.serverTime)
            }
        }
    }

    // ==================== 上行 ====================

    override suspend fun sendHeartbeat(heartbeat: Heartbeat): ApiResult<HeartbeatAck> {
        // MQTT 模式：心跳走 QoS0 —— 心跳天然幂等且高频，丢一两个由下一个补上，
        // 用 QoS1 只会在弱网下堆积重传，反而拖慢真正重要的指令回执。
        if (settings.mode.value == TransportMode.MQTT && mqtt.connected.value) {
            val topic = MqttTopics.upHeartbeat(credentials.tenantId, credentials.deviceId)
            val payload = json.encodeToString(Heartbeat.serializer(), heartbeat)
            if (mqtt.publish(topic, payload, MqttTopics.QOS_HEARTBEAT)) {
                // MQTT 心跳无响应体，返回空 ack；策略版本对账由 policy 通知或轮询兜底
                return ApiResult.Success(HeartbeatAck(), timeProvider.now())
            }
            Logger.w(TAG) { "mqtt heartbeat publish failed, falling back to HTTPS" }
        }
        return caller.call("heartbeat") { api.heartbeat(heartbeat) }
    }

    override suspend fun sendAck(ack: CommandAck): Boolean {
        if (settings.mode.value == TransportMode.MQTT && mqtt.connected.value) {
            val topic = MqttTopics.upAck(credentials.tenantId, credentials.deviceId)
            val payload = json.encodeToString(CommandAck.serializer(), ack)
            if (mqtt.publish(topic, payload, MqttTopics.QOS_RELIABLE)) return true
        }
        val result = caller.callIgnoringData("ack") {
            api.ack(AckUploadRequest(credentials.deviceId, listOf(ack)))
        }
        if (!result.isSuccess) {
            ackRetryQueue.offer(ack)
            Logger.w(TAG) { "ack ${ack.msgId} queued for retry (queue=${ackRetryQueue.size})" }
            return false
        }
        return true
    }

    override suspend fun uploadLogs(deviceId: String, logs: List<BehaviorLog>): ApiResult<LogUploadResponse> {
        if (logs.isEmpty()) return ApiResult.Success(LogUploadResponse(), timeProvider.now())
        return caller.call("logs") { api.uploadLogs(LogUploadRequest(deviceId, logs)) }
    }

    override suspend fun uploadEvents(deviceId: String, events: List<RiskEvent>): ApiResult<LogUploadResponse> {
        if (events.isEmpty()) return ApiResult.Success(LogUploadResponse(), timeProvider.now())

        // 高危告警首选 MQTT QoS1：比 HTTPS 少一次 TLS 握手，
        // "正在被卸载"这类事件必须在进程被杀之前发出去，毫秒级差异有意义。
        if (settings.mode.value == TransportMode.MQTT && mqtt.connected.value) {
            val topic = MqttTopics.upEvent(credentials.tenantId, credentials.deviceId)
            val allPublished = events.all { event ->
                mqtt.publish(
                    topic,
                    json.encodeToString(RiskEvent.serializer(), event.copy(deviceId = deviceId)),
                    MqttTopics.QOS_RELIABLE
                )
            }
            if (allPublished) {
                return ApiResult.Success(LogUploadResponse(accepted = events.size), timeProvider.now())
            }
        }
        return caller.call("events") {
            api.uploadEvents(EventUploadRequest(deviceId, events.map { it.copy(deviceId = deviceId) }))
        }
    }

    override suspend fun uploadLocations(deviceId: String, points: List<LocationInfo>): ApiResult<Unit> {
        if (points.isEmpty()) return ApiResult.Success(Unit, timeProvider.now())
        return caller.callIgnoringData("locations") {
            api.uploadLocations(LocationUploadRequest(deviceId, points))
        }
    }

    override suspend fun uploadScreenshot(
        shotId: String,
        capturedAt: Long,
        triggerType: String,
        jpeg: ByteArray
    ): ApiResult<ScreenshotAck> {
        val textType = "text/plain".toMediaType()
        val filePart = MultipartBody.Part.createFormData(
            "file",
            "$shotId.jpg",
            jpeg.toRequestBody("image/jpeg".toMediaType())
        )
        return caller.call("screenshot") {
            api.uploadScreenshot(
                shotId = shotId.toRequestBody(textType),
                capturedAt = capturedAt.toString().toRequestBody(textType),
                triggerType = triggerType.toRequestBody(textType),
                file = filePart
            )
        }
    }

    override suspend fun uploadMedia(
        taskId: String,
        durationSeconds: Int,
        mimeType: String,
        file: java.io.File
    ): ApiResult<MediaAck> {
        val textType = "text/plain".toMediaType()
        val body = file.asRequestBody(mimeType.toMediaTypeOrNull())
        val filePart = MultipartBody.Part.createFormData("file", file.name, body)
        return caller.call("media") {
            api.uploadMedia(
                taskId = taskId.toRequestBody(textType),
                durationSeconds = durationSeconds.toString().toRequestBody(textType),
                mimeType = mimeType.toRequestBody(textType),
                file = filePart
            )
        }
    }

    override suspend fun uploadApps(
        deviceId: String,
        apps: List<AppInventoryItem>
    ): ApiResult<AppSyncAck> {
        // 空清单不上报：服务端会把"空清单"理解成"所有应用都被卸载了"，
        // 那会让整个台账被误置为已卸载，是一次请求就能毁掉全部历史数据的操作。
        if (apps.isEmpty()) return ApiResult.Success(AppSyncAck(), timeProvider.now())
        return caller.call("apps") { api.uploadApps(AppInventoryRequest(apps)) }
    }

    override suspend fun pullCommands(since: Long): ApiResult<List<Command>> =
        caller.call("commands") { api.commands(since) }

    // ==================== 下行处理 ====================

    private suspend fun handleInbound(inbound: MqttInbound) {
        when (inbound.kind) {
            MqttTopics.DownlinkKind.COMMAND -> {
                val command = runCatching {
                    json.decodeFromString(Command.serializer(), inbound.payload)
                }.getOrElse {
                    // 无法解析的指令连 msgId 都拿不到，无法回执，只能记日志
                    Logger.e(TAG, it) { "malformed command payload from ${inbound.topic}" }
                    return
                }
                dispatchCommand(command)
            }

            MqttTopics.DownlinkKind.POLICY -> {
                val version = runCatching {
                    json.parseToJsonElement(inbound.payload).jsonObject["version"]?.jsonPrimitive?.intOrNull
                }.getOrNull() ?: -1
                Logger.i(TAG) { "policy change notified, version=$version" }
                _downlink.tryEmit(Downlink.PolicyChanged(version))
            }

            MqttTopics.DownlinkKind.CONFIG -> {
                val values = parseFlatMap(inbound.payload)
                applyConfig(values)
                _downlink.tryEmit(Downlink.ConfigChanged(values))
            }

            MqttTopics.DownlinkKind.UNKNOWN ->
                Logger.w(TAG) { "unhandled topic: ${inbound.topic}" }
        }
    }

    /** 指令统一过三道关后再交给上层，保证 [Downlink.CommandReceived] 一定是可信指令 */
    private suspend fun dispatchCommand(command: Command) {
        when (val verdict = gate.evaluate(command)) {
            is CommandGate.Verdict.Accepted ->
                _downlink.tryEmit(Downlink.CommandReceived(verdict.command))

            is CommandGate.Verdict.Rejected -> {
                val ack = CommandAck(
                    msgId = command.msgId,
                    deviceId = credentials.deviceId,
                    status = verdict.ackStatus,
                    executedAt = timeProvider.now(),
                    errorCode = verdict.ackStatus.name,
                    errorMessage = verdict.reason
                )
                _downlink.tryEmit(Downlink.CommandRejected(ack, verdict.reason))
                // 拒绝也要回执，否则服务端无法区分"被拒"与"没收到"
                sendAck(ack)
            }
        }
    }

    private fun applyConfig(values: Map<String, String>) {
        settings.applyRemoteConfig(
            heartbeatSec = values["heartbeatIntervalSec"]?.toIntOrNull(),
            pollingSec = values["pollingIntervalSec"]?.toIntOrNull(),
            logUploadSec = values["logUploadIntervalSec"]?.toIntOrNull()
        )
        values["transportMode"]?.uppercase()?.let { mode ->
            runCatching { TransportMode.valueOf(mode) }.getOrNull()?.let(settings::setMode)
        }
    }

    // ==================== 轮询降级 ====================

    private fun ensurePollingLoop() {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch {
            while (isActive) {
                val intervalMs = settings.pollingIntervalSec.value * 1000L
                if (settings.mode.value == TransportMode.POLLING && credentials.isBound) {
                    runPollingRound()
                    // 每轮结束尝试恢复 MQTT：校园网切换后端口可能重新放通
                    if (mqtt.connect()) {
                        Logger.i(TAG) { "mqtt recovered, leaving polling mode" }
                    }
                }
                delay(intervalMs)
            }
        }
    }

    private suspend fun runPollingRound() {
        when (val r = pullCommands(lastPollAt)) {
            is ApiResult.Success -> {
                pollingHealthy = true
                lastPollAt = timeProvider.now()
                _downlink.tryEmit(Downlink.ConnectivityChanged(true, TransportMode.POLLING))
                r.value.forEach { dispatchCommand(it) }
            }
            is ApiResult.BizError -> {
                pollingHealthy = !r.isFatal()
                Logger.w(TAG) { "polling biz error ${r.code}: ${r.message}" }
            }
            is ApiResult.Failure -> {
                pollingHealthy = false
                _downlink.tryEmit(Downlink.ConnectivityChanged(false, TransportMode.POLLING))
            }
        }
        flushAckRetryQueue()
    }

    private suspend fun flushAckRetryQueue() {
        if (ackRetryQueue.isEmpty()) return
        val batch = mutableListOf<CommandAck>()
        while (batch.size < ACK_RETRY_BATCH) {
            batch.add(ackRetryQueue.poll() ?: break)
        }
        if (batch.isEmpty()) return
        val result = caller.callIgnoringData("ack-retry") {
            api.ack(AckUploadRequest(credentials.deviceId, batch))
        }
        if (!result.isSuccess) {
            // 放回队列，下一轮再试；此处不做无限增长保护是因为
            // 指令量本身受服务端下发频率约束，队列长度天然有界
            batch.forEach { ackRetryQueue.offer(it) }
        } else {
            Logger.i(TAG) { "flushed ${batch.size} pending acks" }
        }
    }

    private fun parseFlatMap(payload: String): Map<String, String> = runCatching {
        val obj: JsonObject = json.parseToJsonElement(payload).jsonObject
        obj.mapValues { (_, v) -> v.jsonPrimitive.contentOrNull.orEmpty() }
    }.getOrElse {
        Logger.w(TAG, it) { "failed to parse config payload" }
        emptyMap()
    }

    companion object {
        private const val TAG = "RealRemoteDataSource"
        private const val ACK_RETRY_BATCH = 50
    }
}
