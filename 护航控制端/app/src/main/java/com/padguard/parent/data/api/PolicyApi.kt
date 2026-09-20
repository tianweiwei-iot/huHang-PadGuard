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
    @POST("policies/{deviceId}/lock")
    suspend fun lockScreen(
        @Path("deviceId") deviceId: String
    ): Response<ApiResponse<Unit>>

    @POST("policies/{deviceId}/mode")
    suspend fun setControlMode(
        @Path("deviceId") deviceId: String,
        @Body request: ModeChangeRequest
    ): Response<ApiResponse<Unit>>

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
}
