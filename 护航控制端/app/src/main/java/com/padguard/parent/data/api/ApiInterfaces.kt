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

    /**
     * 生成 6 位一次性绑定码（10 分钟有效）。
     * 真实绑定由孩子端拿此码调用 /api/v1/device/bind 完成，家长端仅负责下发。
     */
    @POST("devices/bind-code")
    suspend fun generateBindCode(): Response<ApiResponse<BindCodeResponse>>

    /**
     * 解绑设备。
     *
     * 响应体为空，必须用 ResponseBody 接收：
     * 若声明为 `ApiResponse<Unit>`，Moshi 无法为 kotlin.Unit 创建适配器，
     * Retrofit 会在**创建 converter 阶段**直接抛
     * `IllegalArgumentException: Unable to create converter for kotlin.Unit`，
     * 请求根本发不出去 —— 表现为"点解绑就报错，但服务端一切正常"。
     * 与下方 setAppHidden 采用同一范式。
     */
    @POST("devices/{deviceId}/unbind")
    suspend fun unbindDevice(
        @Path("deviceId") deviceId: String
    ): Response<okhttp3.ResponseBody>

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

    /**
     * 请求实时截屏。
     *
     * 返回的是**指令受理回执**（只有 taskId/status），不是截图本身：
     * 图片由孩子端拍完单独上传，得再从截屏历史轮询。
     * 按 ScreenshotDto 解析虽然字段可空不会崩，但 imageUrl 恒为 null，兜底通道形同虚设。
     */
    @POST("monitor/{deviceId}/screenshot")
    suspend fun requestScreenshot(
        @Path("deviceId") deviceId: String
    ): Response<ApiResponse<ScreenshotAcceptedDto>>

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

    /**
     * 远程拍照。
     *
     * 服务端返回 TaskAcceptedDto（taskId/kind/status/requestedAt），**没有 url**。
     * 之前按 MediaResultDto 解析，其 url 是非空字段 → Moshi 直接抛
     * "Required value 'url' missing"，这一步在解析阶段就失败，根本走不到业务。
     */
    @POST("monitor/{deviceId}/photo")
    suspend fun takePhoto(
        @Path("deviceId") deviceId: String
    ): Response<ApiResponse<TaskAcceptedDto>>

    /** 远程录音 - 开始（同样只回执任务，无 url） */
    @POST("monitor/{deviceId}/record/start")
    suspend fun startRecording(
        @Path("deviceId") deviceId: String
    ): Response<ApiResponse<TaskAcceptedDto>>

    /** 远程录音 - 停止（同样只回执任务，无 url） */
    @POST("monitor/{deviceId}/record/stop")
    suspend fun stopRecording(
        @Path("deviceId") deviceId: String
    ): Response<ApiResponse<TaskAcceptedDto>>

    /**
     * 开始录屏。
     *
     * 服务端返回 TaskAcceptedDto（taskId/kind/status/requestedAt）。
     * 之前按 ScreenRecordTaskDto 解析，其 deviceId/startedAt/resolution/withAudio 均无默认值
     * → Moshi 抛缺字段异常，**家长端一点"录屏"就报启动失败**，而服务端其实早已下发指令。
     * 这是"录屏不可用"的直接原因：指令一直是通的，坏在家长端的响应解析。
     */
    @POST("monitor/{deviceId}/screen-record/start")
    suspend fun startScreenRecord(
        @Path("deviceId") deviceId: String,
        @Body request: StartRecordRequest
    ): Response<ApiResponse<TaskAcceptedDto>>

    /**
     * 停止录屏。
     *
     * 两处必须与服务端对齐，否则家长端"停止录屏"必然失败：
     * 1. 路径必须带 deviceId：服务端是 `/v1/monitor/{deviceId}/screen-record/{taskId}/stop`。
     *    少了 deviceId 会命中 5001「No static resource」，录屏只能靠孩子端 10 分钟兜底自动收尾。
     * 2. 返回的是**任务接受回执**（无 url），不是录屏文件地址：
     *    mp4 由孩子端编码后单独上传入库，停指令下发时文件还不存在。
     *    按 MediaResultDto 取 url 恒为 null，会误报"停止录屏失败"。
     */
    @POST("monitor/{deviceId}/screen-record/{taskId}/stop")
    suspend fun stopScreenRecord(
        @Path("deviceId") deviceId: String,
        @Path("taskId") taskId: String
    ): Response<ApiResponse<TaskAcceptedDto>>

    /**
     * 查询媒体任务状态与文件地址。
     *
     * 停止录屏是异步收尾：孩子端要停编码器、上传 mp4，服务端才会置 READY 并填 url。
     * 家长端据此轮询，拿到 url 后才展示"回放"入口。
     */
    @GET("monitor/{deviceId}/media/{taskId}")
    suspend fun getMediaTask(
        @Path("deviceId") deviceId: String,
        @Path("taskId") taskId: String
    ): Response<ApiResponse<MediaTaskStatusDto>>

    /**
     * 开启实时看屏推流。
     * 观看期间必须周期性重发：孩子端的推流有 TTL，续期停止即自动结束，
     * 这样家长关掉页面或掉线后，孩子平板不会一直推流耗电耗流量。
     * 返回体不解析（只需要 HTTP 成功与否），用 ResponseBody 避开 Moshi 对空泛型的兼容问题。
     */
    @POST("monitor/{deviceId}/live/start")
    suspend fun startLiveView(
        @Path("deviceId") deviceId: String
    ): Response<okhttp3.ResponseBody>

    @POST("monitor/{deviceId}/live/stop")
    suspend fun stopLiveView(
        @Path("deviceId") deviceId: String
    ): Response<okhttp3.ResponseBody>

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

/**
 * 应用监控 / 远程安装维护 API 接口定义
 * 对应设计文档"应用管理"与"远程维护"章节
 * API 基础路径: /devices/{deviceId}/apps
 */
interface AppManageApi {

    /** 获取设备已安装应用台账（含黑名单/限额合并结果） */
    @GET("devices/{deviceId}/apps")
    suspend fun getInstalledApps(
        @Path("deviceId") deviceId: String,
        @Query("onlyInstalled") onlyInstalled: Boolean = true
    ): Response<ApiResponse<List<InstalledAppDto>>>

    /** 远程安装：下发 INSTALL_APP 指令，APK 由被管控端自行下载 */
    @POST("devices/{deviceId}/apps/install")
    suspend fun installApp(
        @Path("deviceId") deviceId: String,
        @Body request: AppInstallRequest
    ): Response<ApiResponse<Unit>>

    /** 远程卸载 */
    @POST("devices/{deviceId}/apps/uninstall")
    suspend fun uninstallApp(
        @Path("deviceId") deviceId: String,
        @Body request: SuspendRequest
    ): Response<ApiResponse<Unit>>

    /** 单应用挂起 / 恢复 */
    @PUT("devices/{deviceId}/apps/suspend")
    suspend fun suspendApp(
        @Path("deviceId") deviceId: String,
        @Body request: SuspendRequest
    ): Response<ApiResponse<Unit>>

    /** 批量挂起 / 恢复（"一键禁用所有游戏"这类场景） */
    @PUT("devices/{deviceId}/apps/suspend/batch")
    suspend fun suspendAppsBatch(
        @Path("deviceId") deviceId: String,
        @Body request: BatchSuspendRequest
    ): Response<ApiResponse<Map<String, Int>>>

    /**
     * 使用权限开关：关闭 = 在孩子端隐藏该应用（不是卸载）。
     * 响应体为空，用 ResponseBody 接收，避免 ApiResponse<Unit> 触发 Moshi 解析失败。
     */
    @PUT("devices/{deviceId}/apps/hidden")
    suspend fun setAppHidden(
        @Path("deviceId") deviceId: String,
        @Body request: AppHiddenRequest
    ): Response<okhttp3.ResponseBody>

    /**
     * 批量设置使用权限（隐藏 / 显示）。
     * `hidden=true` = 在孩子端隐藏（关闭权限）；`all=true` 作用于全部已安装应用。
     * 响应 data 形如 `{"sent": n}`。
     */
    @PUT("devices/{deviceId}/apps/hidden/batch")
    suspend fun setAppsHiddenBatch(
        @Path("deviceId") deviceId: String,
        @Body request: AppHiddenBatchRequest
    ): Response<ApiResponse<Map<String, Int>>>

    /**
     * 设置单个应用的每日使用时长上限（分钟）。0 表示不限制。
     */
    @PUT("devices/{deviceId}/apps/{packageName}/limit")
    suspend fun setAppLimit(
        @Path("deviceId") deviceId: String,
        @Path("packageName") packageName: String,
        @Body request: AppLimitRequest
    ): Response<ApiResponse<Unit>>
}

/**
 * 文件通道：家长端上传素材 / APK。
 *
 * 此前家长侧**没有任何写入文件的通道**（只有孩子端上报截图/录屏时能写），
 * 于是信息发布只能填外部 URL、远程安装无从下手 —— 功能等于没做。
 * 这里补上 multipart 上传，是信息发布素材与远程安装 APK 的共同地基。
 */
interface FileApi {

    @Multipart
    @POST("media/upload")
    suspend fun upload(
        @Part file: okhttp3.MultipartBody.Part
    ): Response<ApiResponse<FileUploadDto>>
}
