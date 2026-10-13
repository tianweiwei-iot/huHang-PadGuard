package com.padguard.parent.data.api

import com.padguard.parent.data.model.*
import retrofit2.Response
import retrofit2.http.*

/**
 * 风险预警 API 接口定义
 * 对应设计文档"风险预警"章节
 * API 基础路径: /alerts
 */
interface AlertApi {

    /** 获取告警列表 */
    @GET("alerts")
    suspend fun getAlerts(
        @Query("deviceId") deviceId: String? = null,
        @Query("status") status: String? = null,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20
    ): Response<ApiResponse<List<AlertDto>>>

    /**
     * 获取未读告警数量。
     *
     * 服务端返回的是 `{"count": N}` 对象。**不能**声明成 `ApiResponse<Int>`——
     * Moshi 拿到 OBJECT 却要填 Int 字段会在解析阶段抛异常，
     * 表现为"接口明明 200 了却统一走 failure"。
     */
    @GET("alerts/unread-count")
    suspend fun getUnreadCount(): Response<ApiResponse<UnreadCountDto>>

    /** 确认告警 */
    @POST("alerts/{alertId}/acknowledge")
    suspend fun acknowledgeAlert(
        @Path("alertId") alertId: String
    ): Response<ApiResponse<Unit>>

    /** 关闭告警 */
    @POST("alerts/{alertId}/close")
    suspend fun closeAlert(
        @Path("alertId") alertId: String,
        @Body request: CloseAlertRequest? = null
    ): Response<ApiResponse<Unit>>
}
