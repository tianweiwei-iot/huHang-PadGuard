package com.padguard.core.transport.http

import com.padguard.core.data.model.BindResult
import com.padguard.core.data.model.Command
import com.padguard.core.data.model.Heartbeat
import kotlinx.serialization.json.JsonElement
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query

/**
 * 被管控端 HTTPS 接口（契约 §5）。
 *
 * 全部返回 `Response<ApiEnvelope<T>>` 而非直接返回业务对象：
 * 需要同时拿到 HTTP 状态码与业务 code，才能区分「令牌过期(401/40101)」
 * 与「网络异常」两类完全不同的处置路径。
 */
interface PadGuardApi {

    /** §5.1 设备绑定。绑定接口本身不带 Authorization，由拦截器按路径跳过。 */
    @POST("device/bind")
    suspend fun bind(@Body body: BindRequest): Response<ApiEnvelope<BindResult>>

    /** §5.2 时间同步 */
    @GET("device/time")
    suspend fun serverTime(): Response<ApiEnvelope<TimeResponse>>

    /**
     * §5.3 拉取策略包。
     * 返回 JsonElement 而不是 PolicyPackage：既要判断 `upToDate`，
     * 又要保留原始 JSON 文本加密落库（见 [PolicyFetchResult.Updated.rawJson]）。
     */
    @GET("device/policy")
    suspend fun policy(@Query("currentVersion") currentVersion: Int): Response<ApiEnvelope<JsonElement>>

    /** §5.4 批量上报行为日志，单次上限 500 条 */
    @POST("device/logs")
    suspend fun uploadLogs(@Body body: LogUploadRequest): Response<ApiEnvelope<LogUploadResponse>>

    /** 告警事件的 HTTPS 兜底通道（首选走 MQTT QoS1） */
    @POST("device/events")
    suspend fun uploadEvents(@Body body: EventUploadRequest): Response<ApiEnvelope<LogUploadResponse>>

    /** §5.5 上报截图 */
    @Multipart
    @POST("device/screenshot")
    suspend fun uploadScreenshot(
        @Part("shotId") shotId: RequestBody,
        @Part("capturedAt") capturedAt: RequestBody,
        @Part("triggerType") triggerType: RequestBody,
        @Part file: MultipartBody.Part
    ): Response<ApiEnvelope<ScreenshotAck>>

    /** §5.5.1 上报媒体文件（录音/录屏） */
    @Multipart
    @POST("device/media")
    suspend fun uploadMedia(
        @Part("taskId") taskId: RequestBody,
        @Part("durationSeconds") durationSeconds: RequestBody,
        @Part("mimeType") mimeType: RequestBody,
        @Part file: MultipartBody.Part
    ): Response<ApiEnvelope<MediaAck>>

    /**
     * §5.10 全量上报已安装应用台账。
     *
     * 走 HTTP 而不是 MQTT：清单通常几百条、几十 KB，
     * MQTT 上行走大包容易触发 broker 报文大小限制，失败后重传代价也高；
     * HTTP 天然支持 gzip 与更大的 body，更适合这种"周期性全量快照"。
     */
    @POST("device/apps")
    suspend fun uploadApps(@Body body: AppInventoryRequest): Response<ApiEnvelope<AppSyncAck>>

    /**
     * §5.6 上报定位轨迹。
     * data 用 JsonElement 而非 Unit：kotlinx.serialization 对 Unit 的处理依赖版本，
     * 用 JsonElement 可兼容服务端返回 `null` / `{}` / 任意扩展字段三种形态。
     */
    @POST("device/locations")
    suspend fun uploadLocations(@Body body: LocationUploadRequest): Response<ApiEnvelope<JsonElement>>

    /** §5.7 指令拉取（仅 POLLING 降级模式使用） */
    @GET("device/commands")
    suspend fun commands(@Query("since") since: Long): Response<ApiEnvelope<List<Command>>>

    /** §5.8 心跳降级上报（仅 MQTT 不可用时使用） */
    @POST("device/heartbeat")
    suspend fun heartbeat(@Body body: Heartbeat): Response<ApiEnvelope<HeartbeatAck>>

    /**
     * §5.9 指令回执降级通道（契约 V1.1 新增）。
     *
     * V1.0 只在 MQTT 上定义了 ack topic，但轮询模式下指令是从 §5.7 拉来的，
     * 没有回执通道 —— 服务端会看到"指令下发了但永远没有执行结果"，
     * 分不清是设备没收到还是执行失败。故补此接口。
     */
    @POST("device/ack")
    suspend fun ack(@Body body: AckUploadRequest): Response<ApiEnvelope<JsonElement>>
}
