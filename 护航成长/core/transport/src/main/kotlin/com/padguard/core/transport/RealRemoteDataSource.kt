package com.padguard.core.transport

import com.padguard.core.common.Logger
import com.padguard.core.common.TimeProvider
import android.content.Context
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
import com.padguard.core.transport.http.CommandsResponse
import com.padguard.core.transport.http.CredentialStore
import com.padguard.core.transport.http.DeviceNameRequest
import com.padguard.core.transport.http.EventUploadRequest
import com.padguard.core.transport.http.AlertResolveRequest
import com.padguard.core.transport.http.HeartbeatAck
import com.padguard.core.transport.http.LocationUploadRequest
import com.padguard.core.transport.http.LogUploadRequest
import com.padguard.core.transport.http.LogUploadResponse
import com.padguard.core.transport.http.MediaAck
import com.padguard.core.transport.http.AvatarAck
import com.padguard.core.transport.http.DeviceProfileRequest
import com.padguard.core.transport.http.PadGuardApi
import com.padguard.core.transport.http.PolicyFetchResult
import com.padguard.core.transport.http.ScreenshotAck
import com.padguard.core.transport.http.UsageSessionDto
import com.padguard.core.transport.http.UsageDelayRequest
import com.padguard.core.transport.http.UsageRestReportRequest
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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
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
    @ApplicationContext private val context: Context,
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
     * 指令回执重试队列（磁盘态，R-P1-5）。
     * 进程被杀后重启首轮 flush 补发，闭合"回执丢失→服务端指令卡执行中"的离线红线回路。
     * 之所以用 JSON-Lines 落盘而非仅内存：内存队列在进程被杀后全部丢失，指令闭环断裂。
     */
    private val ackOutbox = AckOutbox(File(context.filesDir, "padguard_ack_outbox.jsonl"), json)
    /** flush 互斥：保证 drain→发送→remove 原子，避免并发 flush 重复取同一批回执。 */
    private val ackFlushMutex = Mutex()

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

    override suspend fun getUsageSession(): ApiResult<UsageSessionDto> =
        when (val r = caller.callEnvelope("usage-session") { api.usageSession() }) {
            is ApiResult.Success -> ApiResult.Success(r.value.data ?: UsageSessionDto(), r.serverTime)
            is ApiResult.BizError -> r
            is ApiResult.Failure -> r
        }

    override suspend fun startUsageSession(): ApiResult<UsageSessionDto> =
        when (val r = caller.callEnvelope("usage-session-start") { api.startUsageSession() }) {
            is ApiResult.Success -> ApiResult.Success(r.value.data ?: UsageSessionDto(), r.serverTime)
            is ApiResult.BizError -> r
            is ApiResult.Failure -> r
        }

    override suspend fun requestDelayUsageSession(minutes: Int): ApiResult<UsageSessionDto> =
        when (val r = caller.callEnvelope("usage-session-delay-request") { api.requestDelayUsageSession(UsageDelayRequest(minutes)) }) {
            is ApiResult.Success -> ApiResult.Success(r.value.data ?: UsageSessionDto(), r.serverTime)
            is ApiResult.BizError -> r
            is ApiResult.Failure -> r
        }

    override suspend fun reportEyeCareRest(restId: String, restMs: Long): ApiResult<Unit> =
        caller.callIgnoringData("usage-session-rest") {
            api.reportEyeCareRest(UsageRestReportRequest(restId, restMs))
        }

    // ==================== 上行 ====================

    /**
     * MQTT 心跳计数，用于周期性强制走一次 HTTPS。
     *
     * 见下方 [sendHeartbeat] 对"空 ack 破坏版本对账"的说明。
     */
    private var heartbeatSeq = 0

    override suspend fun sendHeartbeat(heartbeat: Heartbeat): ApiResult<HeartbeatAck> {
        heartbeatSeq++

        // 每 N 次心跳强制走 HTTPS，拿回带 policyVersion 的真实回执。
        //
        // ## 为什么必须有这条"对账心跳"
        // MQTT 心跳是单向 publish（QoS0），**没有响应体**，只能返回空 [HeartbeatAck]，
        // 其中 `policyVersion = 0`。而 GuardService 的版本对账条件是
        // `ack.policyVersion > 0 && != local`，0 会被直接跳过。
        // 后果链非常隐蔽且致命：
        //   1. MQTT 服务端关闭 / 未连接时，`mqtt.publish` 仍可能返回 true（写入本地会话），
        //      心跳"看起来成功"、在线状态也靠 markAlive 维持；
        //   2. 但 ack.policyVersion 恒为 0 → 漂移检测永不触发；
        //   3. 策略推送又走 MQTT（同样丢失）→ **家长改的任何策略都不会下发到设备**，
        //      家长端显示"已开启"、平板上纹丝不动，且永远不会自愈。
        //
        // 因此不能只依赖 MQTT：必须周期性用 HTTPS 心跳把服务端真实版本号取回来。
        // 频率取 2 次（约 30 秒一次 @15s 心跳），兼顾流量与纠偏延迟。
        val forceHttps = heartbeatSeq % HTTPS_RECONCILE_EVERY == 0
        if (forceHttps) {
            Logger.v(TAG) { "heartbeat #$heartbeatSeq routed to HTTPS for policy version reconciliation" }
            return caller.call("heartbeat") { api.heartbeat(heartbeat) }
        }

        // MQTT 模式：心跳走 QoS0 —— 心跳天然幂等且高频，丢一两个由下一个补上，
        // 用 QoS1 只会在弱网下堆积重传，反而拖慢真正重要的指令回执。
        if (settings.mode.value == TransportMode.MQTT && mqtt.connected.value) {
            val topic = MqttTopics.upHeartbeat(credentials.tenantId, credentials.deviceId)
            val payload = json.encodeToString(Heartbeat.serializer(), heartbeat)
            if (mqtt.publish(topic, payload, MqttTopics.QOS_HEARTBEAT)) {
                // MQTT 心跳无响应体，返回空 ack —— 版本对账交给上面的对账心跳。
                return ApiResult.Success(HeartbeatAck(), timeProvider.now())
            }
            Logger.w(TAG) { "mqtt heartbeat publish failed, falling back to HTTPS" }
        }
        return caller.call("heartbeat") { api.heartbeat(heartbeat) }
    }

    /**
     * 指令回执。
     *
     * ## 这里**刻意不走 MQTT**
     * 老实现是"MQTT 已连接就发 MQTT，publish 返回 true 即算成功"，
     * 但 `publish` 返回 true 只代表**写进了本地会话**，不代表服务端收到了。
     * 当服务端没有 broker（local profile `mqtt.enabled=false`）或 broker 地址不可达时，
     * 客户端仍可能报告 connected，于是回执全部投进黑洞：
     *   - 服务端 `commands.status` 永远停在 PENDING、`executed_at` 永远为 null；
     *   - 家长端只看到"指令发送中"，不知道设备其实早就锁上了；
     *   - 更糟的是孩子端已把 msgId 记进去重表，重发也执行不了 —— 指令永久"假失败"。
     *
     * 回执是**低频且必须可靠**的（每条指令一次），省下的那点开销远不抵闭环断裂的代价，
     * 因此统一走 HTTPS；失败时入 [ackRetryQueue] 由后续重发兜底。
     */
    override suspend fun sendAck(ack: CommandAck): Boolean {
        val result = caller.callIgnoringData("ack") {
            api.ack(AckUploadRequest(credentials.deviceId, listOf(ack)))
        }
        if (!result.isSuccess) {
            ackOutbox.enqueue(ack)
            Logger.w(TAG) { "ack ${ack.msgId} queued for disk retry" }
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

        // 高危告警同样**不走 MQTT**，原因与 [sendAck] 一致：
        // `mqtt.publish` 返回 true 不代表服务端收到，broker 缺失时告警会全部静默丢失 ——
        // 而"设备正在被卸载""检测到 root"这类告警恰恰是家长最需要知道的，
        // 丢一条就让管控失去最后一道感知能力。低频高价值，宁可多一次 TLS 握手。
        // （原注释说"比 HTTPS 少一次握手、毫秒级差异有意义" —— 那是建立在
        //   broker 确实存在的假设上；假设不成立时，快没有任何意义。）
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

    override suspend fun updateDeviceName(deviceId: String, name: String): ApiResult<Unit> {
        // 与 DeviceService.updateNameByDevice 对齐：孩子端本地修改设备名后上报服务端，
        // 服务端写入台账并下发 downConfig(deviceName) 同步给家长端看板。
        return caller.callIgnoringData("device-name") {
            api.updateName(DeviceNameRequest(name))
        }
    }

    override suspend fun uploadAvatar(bytes: ByteArray, fileName: String): ApiResult<String> {
        val body = bytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
        val filePart = MultipartBody.Part.createFormData("file", fileName, body)
        return when (val r = caller.call("device-avatar") { api.uploadAvatar(filePart) }) {
            is ApiResult.Success -> ApiResult.Success(r.value.url, r.serverTime)
            is ApiResult.BizError -> r
            is ApiResult.Failure -> r
        }
    }

    override suspend fun updateChildProfile(childName: String?, childNickname: String?): ApiResult<Unit> {
        return caller.callIgnoringData("device-profile") {
            api.updateProfile(DeviceProfileRequest(childName = childName, childNickname = childNickname))
        }
    }

    override suspend fun resolveActiveAlerts(categories: List<String>, reason: String?): ApiResult<Unit> {
        if (categories.isEmpty()) return ApiResult.Success(Unit, timeProvider.now())
        // 自愈恢复后关闭对应 ACTIVE 告警（恢复即关闭闭环）。失败由上层吞掉日志，不反噬本地自愈主链路。
        return caller.callIgnoringData("alerts-resolve") {
            api.resolveAlerts(AlertResolveRequest(categories, reason))
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

    override suspend fun uploadScreenshot(
        shotId: String,
        capturedAt: Long,
        triggerType: String,
        file: java.io.File
    ): ApiResult<ScreenshotAck> {
        val textType = "text/plain".toMediaType()
        // 与 uploadMedia 同口径：asRequestBody(File) 是流式 body，
        // 上传期间按块读盘，不把整张图读进堆（B22）。
        val filePart = MultipartBody.Part.createFormData(
            "file",
            file.name.ifBlank { "$shotId.jpg" },
            file.asRequestBody("image/jpeg".toMediaTypeOrNull())
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

    override suspend fun pullCommands(since: Long): ApiResult<CommandsResponse> {
        // 长轮询参数：有指令时服务端立即返回，没有则挂起到超时，指令延迟因此不再受轮询周期限制。
        // 响应做兼容解析：
        //  - 新服务端返回 { policyVersion, commands }（指令轮询顺带回传最新策略版本，支持 3s 级对账）；
        //  - 旧服务端（本端升级但服务端未升级时）直接返回 [...] 数组，无 policyVersion —— 此时
        //    回退到心跳对账路径（旧服务端心跳已带 policyVersion），避免指令轮询整体解析失败导致远程指令全断。
        return when (val r = caller.callEnvelope("commands") { api.commands(since, LONG_POLL_WAIT_MS) }) {
            is ApiResult.Success -> runCatching { parseCommandsResponse(r.value.data) }
                .fold(
                    onSuccess = { ApiResult.Success(it, r.value.serverTime) },
                    onFailure = { ApiResult.Failure(ApiCode.LOCAL_PARSE_ERROR, "commands parse failed: ${it.message}", it) }
                )
            is ApiResult.BizError -> r
            is ApiResult.Failure -> r
        }
    }

    /**
     * 兼容解析指令轮询响应（见 [pullCommands]）。
     * 旧服务端返回裸数组 → 视作 policyVersion=-1（交给心跳对账）；
     * 新服务端返回对象 → 直接反序列化；其它形态 → 空响应（不阻断主循环）。
     */
    private fun parseCommandsResponse(element: JsonElement?): CommandsResponse {
        if (element == null) return CommandsResponse()
        // 先滤掉孩子端不认识的指令类型（服务端先升级的场景）：
        // 不滤的话一条新类型指令会让整批反序列化失败，锁屏等既有指令跟着瘫痪。
        val result = filterKnownCommandTypes(element)
        if (result.droppedTypes.isNotEmpty()) {
            Logger.w(TAG) {
                "dropped ${result.droppedTypes.size} command(s) of unknown type ${result.droppedTypes} " +
                    "(server newer than device); other commands in the batch are kept"
            }
        }
        val filtered = result.element
        return when {
            filtered is JsonArray ->
                CommandsResponse(policyVersion = -1, commands = json.decodeFromJsonElement(ListSerializer(Command.serializer()), filtered))
            filtered is JsonObject ->
                json.decodeFromJsonElement(CommandsResponse.serializer(), filtered)
            else -> CommandsResponse()
        }
    }

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
            // 限频、非阻塞地尝试恢复 MQTT：见下方注释，绝不允许同步 connect 阻塞存活信号节奏。
            var nextMqttRetryAt = 0L
            while (isActive) {
                val intervalMs = settings.pollingIntervalSec.value * 1000L
                if (settings.mode.value == TransportMode.POLLING && credentials.isBound) {
                    val parked = runPollingRound()
                    // 限频、非阻塞地尝试恢复 MQTT：
                    // 1. Paho 已开启 isAutomaticReconnect —— 连接真正断开时它会在内部自动重连，
                    //    此处只在「初始连不上 / 想切回 MQTT 模式」时兜底，无需每轮都阻塞等待；
                    // 2. 关键修复：原先每轮都「同步」调用 mqtt.connect()，而 broker 不可达时
                    //    connect 要等满 20s 连接超时，会把存活信号间隔从 ~20s 拉长到 ~40s，
                    //    逼近甚至越过 presence 自适应超时（1.8×间隔，首周期更低），造成
                    //    POLLING 降级模式下周期性被判离线、家长端看到「一会在线一会掉线」。
                    //    改为后台限频尝试：存活信号（HTTP 长轮询）每轮照常发出，节奏不受拖累。
                    val now = timeProvider.now()
                    if (now >= nextMqttRetryAt) {
                        nextMqttRetryAt = now + MQTT_RECOVER_MIN_INTERVAL_MS
                        scope.launch {
                            if (mqtt.connect()) Logger.i(TAG) { "mqtt recovered, leaving polling mode" }
                        }
                    }
                    // 长轮询生效时，服务端已经替我们"等过"了一轮：返回后立刻发起下一次，
                    // 指令从入库到被取走只剩一次 RTT。未挂起（旧服务端 / 请求失败）才退避，
                    // 否则会退化成紧密循环空转刷屏。
                    if (!parked) delay(intervalMs)
                } else {
                    delay(intervalMs)
                }
            }
        }
    }

    /** @return true 表示本轮请求被服务端挂起过（长轮询生效），调用方无需再退避 */
    private suspend fun runPollingRound(): Boolean {
        val startedAt = timeProvider.now()
        var parked = false
        when (val r = pullCommands(lastPollAt)) {
            is ApiResult.Success -> {
                // 耗时足够长才说明服务端真的挂住了请求；立即返回说明它没支持长轮询
                parked = timeProvider.now() - startedAt >= LONG_POLL_PARKED_THRESHOLD_MS
                pollingHealthy = true
                lastPollAt = timeProvider.now()
                _downlink.tryEmit(Downlink.ConnectivityChanged(true, TransportMode.POLLING))
                // 策略版本对账不在这里做：3s 一次的 [drainPendingCommands]（所有模式在线时都跑）
                // 已经在响应里读到 policyVersion 并只在真正漂移时才全量拉新，
                // 这里若无条件上抛 PolicyChanged 会每轮空拉一次策略包，纯属浪费。
                r.value.commands.forEach { dispatchCommand(it) }
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
        return parked
    }

    private suspend fun flushAckRetryQueue() = ackFlushMutex.withLock {
        val batch = ackOutbox.drain(ACK_RETRY_BATCH)
        if (batch.isEmpty()) return
        val result = caller.callIgnoringData("ack-retry") {
            api.ack(AckUploadRequest(credentials.deviceId, batch))
        }
        if (!result.isSuccess) {
            // 留盘，下一轮再试；指令量受服务端下发频率约束，队列长度天然有界。
            Logger.w(TAG) { "ack retry failed, kept ${batch.size} on disk for next flush" }
        } else {
            ackOutbox.remove(batch)
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

        /**
         * 每多少次心跳强制走一次 HTTPS 做策略版本对账。
         *
         * 默认 2 次 ≈ 30 秒（心跳间隔 15s）：把家长改完策略后设备自动纠偏的
         * 最坏盲窗从 1 分钟压到 30 秒，同时不会让 MQTT 形同虚设
         * （每条心跳都回落 HTTPS 才是取 1 才会出现的问题，本值 ≥ 2 不会）。
         *
         * 为什么必须保留这条对账心跳：MQTT 心跳是单向 publish（QoS0）无响应体，
         * 推送又走 MQTT，broker 假活（TCP 半开 / 进程僵死）时策略推送会静默丢失、
         * 且本地版本对账永远为 0 不触发 —— 家长端显示"已开启"、平板上纹丝不动且永不自愈。
         * 周期性 HTTPS 心跳把服务端真实版本号取回来，是"假在线不脱管"的最后一道闸。
         */
        private const val HTTPS_RECONCILE_EVERY = 2

        /**
         * 长轮询挂起时长。
         *
         * 必须小于终端 HTTP 读超时（30s）：否则终端会先掐断连接，
         * 表现为指令依旧迟迟不生效，而服务端只留一条断开日志，极难定位。
         */
        private const val LONG_POLL_WAIT_MS = 20_000L

        /**
         * 判定「服务端确实把请求挂住了」的最小耗时。
         *
         * 低于此值说明服务端没支持长轮询（旧版本 / 参数被忽略），此时若仍立即发起
         * 下一轮就会变成紧密循环空转刷屏，因此退回普通轮询周期做退避。
         */
        private const val LONG_POLL_PARKED_THRESHOLD_MS = 1_000L

        /**
         * POLLING 降级模式下，后台尝试恢复 MQTT 的最小间隔。
         *
         * 不能每轮都试：每轮 runPollingRound 已是一次 20s 长轮询（存活信号源），
         * 若再同步 connect（失败要等 20s 连接超时）会把存活信号拖到 ~40s 而离线横跳。
         * 这里只兜底「初始连不上 + 成功后切回 MQTT 模式」，真断线靠 Paho 的
         * isAutomaticReconnect 自动重连，30s 探一次足够且零阻塞。
         */
        private const val MQTT_RECOVER_MIN_INTERVAL_MS = 30_000L
    }
}
