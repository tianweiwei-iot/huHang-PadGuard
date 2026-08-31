package com.padguard.data.api

import com.padguard.data.model.*
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
}
