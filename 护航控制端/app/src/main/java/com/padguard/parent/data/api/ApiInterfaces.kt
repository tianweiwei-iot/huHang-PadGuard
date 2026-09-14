package com.padguard.data.api

import com.padguard.data.model.*
import retrofit2.Response
import retrofit2.http.*

/**
 * 认证 API 接口定义
 * 对应设计文档"账号与权限"章节 (P0)
 *
 * API 基础路径: /auth
 * 参考规范: RESTful + JWT Token 鉴权
 */
interface AuthApi {

    /** 手机号+密码登录 */
    @POST("auth/login/password")
    suspend fun loginWithPassword(
        @Body request: LoginPasswordRequest
    ): Response<ApiResponse<LoginResponse>>

    /** 手机号+验证码登录 */
    @POST("auth/login/sms")
    suspend fun loginWithSms(
        @Body request: LoginSmsRequest
    ): Response<ApiResponse<LoginResponse>>

    /** 发送短信验证码 */
    @POST("auth/sms/send")
    suspend fun sendSmsCode(
        @Body request: SendSmsRequest
    ): Response<ApiResponse<Unit>>

    /** 刷新 Token */
    @POST("auth/refresh")
    suspend fun refreshToken(
        @Header("Refresh-Token") refreshToken: String
    ): Response<ApiResponse<TokenResponse>>

    /** 退出登录 */
    @POST("auth/logout")
    suspend fun logout(): Response<ApiResponse<Unit>>
}

/**
 * 设备管理 API 接口定义
 * 对应设计文档"设备资源管理"章节
 * API 基础路径: /devices
 */
interface DeviceApi {

    /** 获取设备列表 */
    @GET("devices")
    suspend fun getDeviceList(
        @Query("groupId") groupId: String? = null,
        @Query("status") status: String? = null
    ): Response<ApiResponse<List<DeviceDto>>>

    /** 获取设备详情 */
    @GET("devices/{deviceId}")
    suspend fun getDeviceDetail(
        @Path("deviceId") deviceId: String
    ): Response<ApiResponse<DeviceDto>>

    /** 绑定设备（扫码/手动） */
    @POST("devices/bind")
    suspend fun bindDevice(
        @Body request: BindDeviceRequest
    ): Response<ApiResponse<DeviceDto>>

    /** 解绑设备 */
    @POST("devices/{deviceId}/unbind")
    suspend fun unbindDevice(
        @Path("deviceId") deviceId: String
    ): Response<ApiResponse<Unit>>

    /** 修改设备别名 */
    @PUT("devices/{deviceId}/name")
    suspend fun renameDevice(
        @Path("deviceId") deviceId: String,
        @Body request: RenameDeviceRequest
    ): Response<ApiResponse<Unit>>

    /** 获取分组列表 */
    @GET("devices/groups")
    suspend fun getGroups(
        @Query("sceneType") sceneType: String? = null
    ): Response<ApiResponse<List<DeviceGroupDto>>>

    /** 创建分组 */
    @POST("devices/groups")
    suspend fun createGroup(
        @Body request: CreateGroupRequest
    ): Response<ApiResponse<DeviceGroupDto>>

    /** 分配设备到分组 */
    @PUT("devices/{deviceId}/group")
    suspend fun assignToGroup(
        @Path("deviceId") deviceId: String,
        @Body request: AssignGroupRequest
    ): Response<ApiResponse<Unit>>
}

/**
 * 实时监控 API 接口定义
 * 对应设计文档"实时监控"章节 (P1)
 * API 基础路径: /monitor
 */
interface MonitorApi {

    /** 请求实时截屏 */
    @POST("monitor/{deviceId}/screenshot")
    suspend fun requestScreenshot(
        @Path("deviceId") deviceId: String
    ): Response<ApiResponse<ScreenshotDto>>

    /** 获取截屏历史 */
    @GET("monitor/{deviceId}/screenshots")
    suspend fun getScreenshotHistory(
        @Path("deviceId") deviceId: String,
        @Query("limit") limit: Int = 20
    ): Response<ApiResponse<List<ScreenshotDto>>>

    /** 获取设备实时信息 */
    @GET("monitor/{deviceId}/info")
    suspend fun getDeviceInfo(
        @Path("deviceId") deviceId: String
    ): Response<ApiResponse<DeviceDto>>

    /** 获取设备位置 */
    @GET("monitor/{deviceId}/location")
    suspend fun getLocation(
        @Path("deviceId") deviceId: String
    ): Response<ApiResponse<LocationDto>>

    /** 远程拍照 */
    @POST("monitor/{deviceId}/photo")
    suspend fun takePhoto(
        @Path("deviceId") deviceId: String
    ): Response<ApiResponse<MediaResultDto>>

    /** 远程录音 - 开始 */
    @POST("monitor/{deviceId}/record/start")
    suspend fun startRecording(
        @Path("deviceId") deviceId: String
    ): Response<ApiResponse<MediaResultDto>>

    /** 远程录音 - 停止 */
    @POST("monitor/{deviceId}/record/stop")
    suspend fun stopRecording(
        @Path("deviceId") deviceId: String
    ): Response<ApiResponse<MediaResultDto>>

    /** 开始录屏 */
    @POST("monitor/{deviceId}/screen-record/start")
    suspend fun startScreenRecord(
        @Path("deviceId") deviceId: String,
        @Body request: StartRecordRequest
    ): Response<ApiResponse<ScreenRecordTaskDto>>

    /** 停止录屏，返回录屏文件 */
    @POST("monitor/screen-record/{taskId}/stop")
    suspend fun stopScreenRecord(
        @Path("taskId") taskId: String
    ): Response<ApiResponse<MediaResultDto>>

    /** 获取屏幕监控设置 */
    @GET("monitor/{deviceId}/screen-settings")
    suspend fun getScreenMonitorSettings(
        @Path("deviceId") deviceId: String
    ): Response<ApiResponse<ScreenMonitorSettingsDto>>

    /** 更新屏幕监控设置 */
    @PUT("monitor/{deviceId}/screen-settings")
    suspend fun updateScreenMonitorSettings(
        @Path("deviceId") deviceId: String,
        @Body settings: ScreenMonitorSettingsDto
    ): Response<ApiResponse<ScreenMonitorSettingsDto>>
}

/**
 * 信息发布 API 接口定义
 * 对应设计文档"实时管控 > 信息发布"章节
 * API 基础路径: /messages
 */
interface MessageApi {

    /** 向被管控平板实时发布信息 */
    @POST("messages/{deviceId}/publish")
    suspend fun publishMessage(
        @Path("deviceId") deviceId: String,
        @Body request: MessagePublishRequestDto
    ): Response<ApiResponse<PublishedMessageDto>>

    /** 获取已发布信息记录 */
    @GET("messages/{deviceId}/history")
    suspend fun getPublishedMessages(
        @Path("deviceId") deviceId: String,
        @Query("limit") limit: Int = 20
    ): Response<ApiResponse<List<PublishedMessageDto>>>
}

/**
 * 定位与电子围栏 API 接口定义
 * 对应设计文档"实时管控 > 定位"章节
 * API 基础路径: /location
 */
interface LocationApi {

    /** 获取设备实时位置 */
    @GET("location/{deviceId}/current")
    suspend fun getDeviceLocation(
        @Path("deviceId") deviceId: String
    ): Response<ApiResponse<LocationDto>>

    /** 获取电子围栏配置 */
    @GET("location/{deviceId}/geofence")
    suspend fun getGeofence(
        @Path("deviceId") deviceId: String
    ): Response<ApiResponse<GeofenceDto>>

    /** 更新电子围栏配置 */
    @PUT("location/{deviceId}/geofence")
    suspend fun updateGeofence(
        @Path("deviceId") deviceId: String,
        @Body config: GeofenceDto
    ): Response<ApiResponse<GeofenceDto>>

    /** 获取越界后的移动轨迹 */
    @GET("location/{deviceId}/track")
    suspend fun getTrackHistory(
        @Path("deviceId") deviceId: String
    ): Response<ApiResponse<List<LocationTrackPointDto>>>
}
