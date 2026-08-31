package com.padguard.core.transport

import com.padguard.core.data.model.BehaviorLog
import com.padguard.core.data.model.BindResult
import com.padguard.core.data.model.Command
import com.padguard.core.data.model.CommandAck
import com.padguard.core.data.model.Heartbeat
import com.padguard.core.data.model.LocationInfo
import com.padguard.core.data.model.RiskEvent
import com.padguard.core.transport.http.ApiResult
import com.padguard.core.transport.http.BindRequest
import com.padguard.core.transport.http.HeartbeatAck
import com.padguard.core.transport.http.LogUploadResponse
import com.padguard.core.transport.http.PolicyFetchResult
import com.padguard.core.transport.http.ScreenshotAck
import kotlinx.coroutines.flow.Flow

/**
 * 服务端下行事件（已通过 MQTT 或 HTTPS 轮询归一化）。
 *
 * 上层（GuardService / CommandExecutor）只面对这一个流，
 * 不需要知道当前走的是 MQTT 还是轮询，也不需要知道是不是 Mock。
 */
sealed interface Downlink {

    /** 已通过签名/时效/幂等三关的指令 */
    data class CommandReceived(val command: Command) : Downlink

    /** 指令被拒绝（仍需回执，便于服务端定位签名不一致等问题） */
    data class CommandRejected(val ack: CommandAck, val reason: String) : Downlink

    /** 策略版本变更通知，payload 只含版本号，上层需再走 HTTPS 拉全量 */
    data class PolicyChanged(val version: Int) : Downlink

    /** 运行参数调整 */
    data class ConfigChanged(val values: Map<String, String>) : Downlink

    /** 通道连通性变化，用于状态页展示与告警 */
    data class ConnectivityChanged(val online: Boolean, val mode: TransportMode) : Downlink
}

/**
 * 被管控端与服务端交互的唯一出口。
 *
 * 之所以做成接口 + 双实现（真实 / Mock）：
 * 服务端尚未就绪，但绑定→心跳→策略→指令→回执这条主链路必须先在真机上跑通并联调，
 * 否则等服务端上线时才发现协议对不上，返工成本极高。
 * Mock 实现完全走同一套模型与状态机，切换只改一个开关，不改任何调用方代码。
 */
interface RemoteDataSource {

    /** 归一化后的下行事件流 */
    val downlink: Flow<Downlink>

    /** 建立实时通道（MQTT 连接或启动轮询）。绑定成功后调用，幂等。 */
    suspend fun start()

    suspend fun stop()

    /** 是否处于在线（实时通道可用）状态 */
    fun isOnline(): Boolean

    // ---------- 一次性请求 ----------

    suspend fun bind(request: BindRequest): ApiResult<BindResult>

    /** @return 服务端标准时间（毫秒）。内部已完成 [com.padguard.core.common.TimeProvider] 校准。 */
    suspend fun syncTime(): ApiResult<Long>

    suspend fun fetchPolicy(currentVersion: Int): ApiResult<PolicyFetchResult>

    // ---------- 上行 ----------

    suspend fun sendHeartbeat(heartbeat: Heartbeat): ApiResult<HeartbeatAck>

    /** 指令回执优先走 MQTT QoS1；MQTT 不可用时落到本地队列，由 HTTPS 兜底 */
    suspend fun sendAck(ack: CommandAck): Boolean

    suspend fun uploadLogs(deviceId: String, logs: List<BehaviorLog>): ApiResult<LogUploadResponse>

    suspend fun uploadEvents(deviceId: String, events: List<RiskEvent>): ApiResult<LogUploadResponse>

    suspend fun uploadLocations(deviceId: String, points: List<LocationInfo>): ApiResult<Unit>

    suspend fun uploadScreenshot(
        shotId: String,
        capturedAt: Long,
        triggerType: String,
        jpeg: ByteArray
    ): ApiResult<ScreenshotAck>

    /** 轮询降级通道主动拉取指令；MQTT 模式下不应调用 */
    suspend fun pullCommands(since: Long): ApiResult<List<Command>>
}
