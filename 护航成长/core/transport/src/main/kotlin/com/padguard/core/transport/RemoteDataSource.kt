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
import com.padguard.core.transport.http.CommandsResponse
import com.padguard.core.transport.http.HeartbeatAck
import com.padguard.core.transport.http.LogUploadResponse
import com.padguard.core.transport.http.PolicyFetchResult
import com.padguard.core.transport.http.AppInventoryItem
import com.padguard.core.transport.http.AppSyncAck
import com.padguard.core.transport.http.ScreenshotAck
import com.padguard.core.transport.http.MediaAck
import com.padguard.core.transport.http.UsageSessionDto
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

    /**
     * 拉取当前使用计时会话（三端时间一致的权威来源）。
     *
     * 返回的 [com.padguard.core.transport.http.UsageSessionDto] 携带服务端墙钟 serverNow，
     * 三端据此派生完全一致的剩余时长。
     */
    suspend fun getUsageSession(): ApiResult<UsageSessionDto>

    /** 孩子端确认"计时开始"：服务端锁定今日计时起点与额度（幂等） */
    suspend fun startUsageSession(): ApiResult<UsageSessionDto>

    /** 孩子端申请延时：仅发起审批工单（须经家长审批才生效），返回刷新后的会话 */
    suspend fun requestDelayUsageSession(minutes: Int): ApiResult<UsageSessionDto>

    /**
     * 上报护眼强制休息时长（毫秒），服务端据此从每日总时长中扣除。
     *
     * @param restId 本次休息唯一标识（= 休息开始墙钟），服务端幂等去重
     * @param restMs 实际休息毫秒（已钳制到 [0, restMinutes*60000]）
     */
    suspend fun reportEyeCareRest(restId: String, restMs: Long): ApiResult<Unit>

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

    /**
     * 上报媒体文件（录音/录屏），服务端关联到媒体任务。
     * 用 [java.io.File] 而非 ByteArray：录屏文件动辄几十 MB，
     * 整体读进内存会直接 OOM，走流式 RequestBody 才稳。
     */
    suspend fun uploadMedia(
        taskId: String,
        durationSeconds: Int,
        mimeType: String,
        file: java.io.File
    ): ApiResult<MediaAck>

    /**
     * 全量上报已安装应用台账（应用监控 / 远程运维的数据源）。
     *
     * 必须是"全量"而非增量：服务端以"本次上报里没出现 = 已卸载"来判定卸载，
     * 增量上报无法区分"没出现"和"没变化"，会让台账永久残留已卸载的应用。
     */
    suspend fun uploadApps(deviceId: String, apps: List<AppInventoryItem>): ApiResult<AppSyncAck>

    /**
     * 轮询降级通道主动拉取指令；MQTT 模式下不应调用。
     * 返回 [com.padguard.core.transport.http.CommandsResponse]，除指令列表外携带服务端最新策略
     * 版本号，使 3s 一次的指令轮询同时承担策略版本对账，家长改策略后最坏 3s 内设备感知并全量拉新。
     */
    suspend fun pullCommands(since: Long): ApiResult<CommandsResponse>

    /** 孩子端自定义设备名并上报服务端（实时同步到家长端台账） */
    suspend fun updateDeviceName(deviceId: String, name: String): ApiResult<Unit>

    /** 孩子端上传头像，返回服务端可访问 URL（实时同步到家长端台账） */
    suspend fun uploadAvatar(bytes: ByteArray, fileName: String): ApiResult<String>

    /** 孩子端自定义个人资料（姓名 / 昵称），实时同步至服务端 + 家长端 */
    suspend fun updateChildProfile(childName: String? = null, childNickname: String? = null): ApiResult<Unit>
}
