package com.padguard.data.api

import com.padguard.data.model.*
import retrofit2.Response
import retrofit2.http.*

/**
 * 管控策略 API 接口定义
 * 对应设计文档"全维度管控"章节
 * API 基础路径: /policies
 */
interface PolicyApi {

    // === 使用时长 ===
    @GET("policies/{deviceId}/time-restrictions")
    suspend fun getTimeRestrictions(
        @Path("deviceId") deviceId: String
    ): Response<ApiResponse<List<TimeRestrictionDto>>>

    @PUT("policies/{deviceId}/time-restrictions")
    suspend fun setTimeRestriction(
        @Path("deviceId") deviceId: String,
        @Body request: TimeRestrictionDto
    ): Response<ApiResponse<TimeRestrictionDto>>

    @DELETE("policies/time-restrictions/{restrictionId}")
    suspend fun deleteTimeRestriction(
        @Path("restrictionId") restrictionId: String
    ): Response<ApiResponse<Unit>>

    /** 读取设备每日总时长上限（分钟），后端以 DeviceSetting.dailyLimitMinutes 为唯一来源 */
    @GET("policies/{deviceId}/daily-limit")
    suspend fun getGlobalDailyLimit(
        @Path("deviceId") deviceId: String
    ): Response<ApiResponse<Int>>

    /**
     * 设置设备每日总时长上限（分钟）。
     * 后端仅暴露 /apps/limit 作为每日总时长写入入口，统一由此落库。
     */
    @PUT("policies/{deviceId}/apps/limit")
    suspend fun setGlobalDailyLimit(
        @Path("deviceId") deviceId: String,
        @Body request: DailyLimitRequest
    ): Response<ApiResponse<Unit>>

    // === 应用管控 ===
    @GET("policies/{deviceId}/apps")
    suspend fun getInstalledApps(
        @Path("deviceId") deviceId: String
    ): Response<ApiResponse<List<AppPolicyDto>>>

    @PUT("policies/{deviceId}/apps/blacklist")
    suspend fun updateAppBlacklist(
        @Path("deviceId") deviceId: String,
        @Body request: UpdateBlacklistRequest
    ): Response<ApiResponse<Unit>>

    /**
     * 设置单应用时长限制。
     * 后端当前未单列"单应用"限制，/apps/limit 实际写入设备每日总时长；
     * 此处复用同一入口，将 AppPolicy.dailyLimitMinutes 作为每日总时长写入，保持行为一致。
     */
    @PUT("policies/{deviceId}/apps/limit")
    suspend fun setAppTimeLimit(
        @Path("deviceId") deviceId: String,
        @Body request: DailyLimitRequest
    ): Response<ApiResponse<Unit>>

    // === 上网管控 ===
    @GET("policies/{deviceId}/web")
    suspend fun getWebPolicy(
        @Path("deviceId") deviceId: String
    ): Response<ApiResponse<WebPolicyDto>>

    @PUT("policies/{deviceId}/web/urls")
    suspend fun updateUrlBlacklist(
        @Path("deviceId") deviceId: String,
        @Body request: UrlBlacklistRequest
    ): Response<ApiResponse<Unit>>

    @PUT("policies/{deviceId}/web/browser")
    suspend fun setBrowserDisabled(
        @Path("deviceId") deviceId: String,
        @Body request: BrowserDisableRequest
    ): Response<ApiResponse<Unit>>

    // === 远程指令 ===
    //
    // 这几个指令接口**一律用 ResponseBody 不解析响应体**：
    // 服务端返回的是指令对象（msgId/type/status/payload...），
    // 而这里原先声明 `ApiResponse<Unit>`，Moshi 拿不到 Unit 的适配器，
    // 在**解析阶段**就抛异常 → 家长端弹"锁屏指令发送失败"，
    // 但服务端其实已经把指令下发成功、孩子端也照常锁屏了。
    // 指令类接口只关心 HTTP 是否成功，用 execRaw 处理即可。
    @POST("policies/{deviceId}/lock")
    suspend fun lockScreen(
        @Path("deviceId") deviceId: String
    ): Response<okhttp3.ResponseBody>

    /** 与 [lockScreen] 对称的解锁通道；缺了它远程锁屏就是单向闸门 */
    @POST("policies/{deviceId}/unlock")
    suspend fun unlockScreen(
        @Path("deviceId") deviceId: String
    ): Response<okhttp3.ResponseBody>

    @POST("policies/{deviceId}/mode")
    suspend fun setControlMode(
        @Path("deviceId") deviceId: String,
        @Body request: ModeChangeRequest
    ): Response<okhttp3.ResponseBody>

    // === 解锁 / 解锁申请 ===
    @POST("policies/{deviceId}/unlock")
    suspend fun unlock(
        @Path("deviceId") deviceId: String
    ): Response<okhttp3.ResponseBody>

    @POST("policies/{deviceId}/temp-unlock")
    suspend fun tempUnlock(
        @Path("deviceId") deviceId: String,
        @Body request: TempUnlockRequest
    ): Response<okhttp3.ResponseBody>

    @GET("devices/{deviceId}/unlock-tickets")
    suspend fun getUnlockTickets(
        @Path("deviceId") deviceId: String
    ): Response<ApiResponse<List<UnlockTicketDto>>>

    /**
     * 同意放行。
     *
     * 声明为 `ResponseBody` 而不是 `ApiResponse<Unit>`：Moshi 没有 Unit 的适配器，
     * 用前者会在**解析响应**时抛异常，服务端其实已经批准并下发了指令，
     * 家长端却弹"操作失败"，家长反复点击、孩子端反复收到解锁指令。
     * 指令类接口只关心 HTTP 是否成功，交给 execRaw 处理即可。
     */
    @POST("devices/{deviceId}/unlock-tickets/{ticketId}/approve")
    suspend fun approveUnlockTicket(
        @Path("deviceId") deviceId: String,
        @Path("ticketId") ticketId: String,
        @Body request: UnlockApproveRequest
    ): Response<okhttp3.ResponseBody>

    /**
     * 拒绝申请。
     *
     * 声明为 `ResponseBody` 而非 `ApiResponse<Unit>`：Moshi 没有 Unit 的适配器，
     * 一旦声明成泛型实体就会在**解析阶段**抛异常，界面弹出"操作失败"——
     * 而实际上服务端已经处理成功了。与 unlock/temp-unlock 等"只发指令不看返回值"
     * 的接口保持同一套写法（tempUnlock 早已是这样）。
     */
    @POST("devices/{deviceId}/unlock-tickets/{ticketId}/reject")
    suspend fun rejectUnlockTicket(
        @Path("deviceId") deviceId: String,
        @Path("ticketId") ticketId: String,
        @Body request: UnlockRejectRequest
    ): Response<okhttp3.ResponseBody>

    /**
     * 忽略（归档）解锁申请：关闭弹窗并留在消息中心，不下发消息给孩子、工单仍可批准。
     * 响应体是空对象，必须用 execRaw 走"不看返回值"的通道，
     * 否则会重蹈 ApiResponse<Unit> 解析失败导致"发送失败"的覆辙。
     */
    @POST("devices/{deviceId}/unlock-tickets/{ticketId}/dismiss")
    suspend fun dismissUnlockTicket(
        @Path("deviceId") deviceId: String,
        @Path("ticketId") ticketId: String
    ): Response<okhttp3.ResponseBody>

    // === 策略模板 ===
    @GET("policies/templates")
    suspend fun getTemplates(
        @Query("sceneType") sceneType: String
    ): Response<ApiResponse<List<PolicyTemplateDto>>>

    @POST("policies/{deviceId}/apply-template")
    suspend fun applyTemplate(
        @Path("deviceId") deviceId: String,
        @Body request: ApplyTemplateRequest
    ): Response<ApiResponse<Unit>>

    // === 平板使用时间设置 ===
    @GET("policies/{deviceId}/tablet-usage-settings")
    suspend fun getTabletUsageSettings(
        @Path("deviceId") deviceId: String
    ): Response<ApiResponse<TabletUsageSettingsDto>>

    @PUT("policies/{deviceId}/tablet-usage-settings")
    suspend fun updateTabletUsageSettings(
        @Path("deviceId") deviceId: String,
        @Body request: TabletUsageSettingsDto
    ): Response<ApiResponse<TabletUsageSettingsDto>>

    // === 未成年人模式（P1 合规底座） ===
    @GET("policies/{deviceId}/minor-mode")
    suspend fun getMinorMode(
        @Path("deviceId") deviceId: String
    ): Response<ApiResponse<MinorModeDto>>

    @POST("policies/{deviceId}/minor-mode")
    suspend fun setMinorMode(
        @Path("deviceId") deviceId: String,
        @Body request: MinorModeRequestDto
    ): Response<okhttp3.ResponseBody>

    /** 各龄档合规默认值；家长端选档界面直接用它展示后果，无需先下发 */
    @GET("policies/age-bands")
    suspend fun getAgeBandDefaults(): Response<ApiResponse<Map<String, AgeBandDefaultDto>>>
}
