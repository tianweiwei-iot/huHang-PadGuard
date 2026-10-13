package com.padguard.parent.data.api

import com.padguard.parent.data.model.*
import retrofit2.Response
import retrofit2.http.*

/**
 * 数据统计 API 接口定义
 * 对应设计文档"数据统计"章节
 * API 基础路径: /statistics
 */
interface StatisticsApi {

    @GET("statistics/{deviceId}/usage")
    suspend fun getUsageStats(
        @Path("deviceId") deviceId: String,
        @Query("period") period: String
    ): Response<ApiResponse<StatisticsReportDto>>

    @GET("statistics/{deviceId}/today")
    suspend fun getTodayUsage(
        @Path("deviceId") deviceId: String
    ): Response<ApiResponse<UsageStatsDto>>

    @GET("statistics/{deviceId}/web")
    suspend fun getWebActivity(
        @Path("deviceId") deviceId: String,
        @Query("period") period: String
    ): Response<ApiResponse<WebActivityStatsDto>>

    @GET("statistics/{deviceId}/violations")
    suspend fun getViolationStats(
        @Path("deviceId") deviceId: String,
        @Query("period") period: String
    ): Response<ApiResponse<ViolationStatsDto>>

    @GET("statistics/{deviceId}/export")
    suspend fun exportReport(
        @Path("deviceId") deviceId: String,
        @Query("period") period: String
    ): Response<ApiResponse<ExportResultDto>>

    /**
     * 单应用使用历史（"使用历史"二级页）。
     *
     * 服务端从已有的 APP_USAGE 用量日志里还原每一次"打开→关闭"的时段，
     * 孩子端无需额外上报。此前该接口缺失，页面只能落在错误态。
     */
    @GET("statistics/{deviceId}/app-usage/history")
    suspend fun getAppUsageHistory(
        @Path("deviceId") deviceId: String,
        @Query("packageName") packageName: String,
        @Query("period") period: String
    ): Response<ApiResponse<List<AppUsageHistoryEntryDto>>>
}
