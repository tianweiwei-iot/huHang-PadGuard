package com.padguard.parent.data.remote

import com.padguard.data.api.AlertApi
import com.padguard.data.api.AppManageApi
import com.padguard.data.api.AuthApi
import com.padguard.data.api.DeviceApi
import com.padguard.data.api.LocationApi
import com.padguard.data.api.MessageApi
import com.padguard.data.api.MonitorApi
import com.padguard.data.api.PolicyApi
import com.padguard.data.api.StatisticsApi
import com.padguard.parent.data.auth.TokenManager
import com.padguard.data.model.AlertDto
import com.padguard.data.model.ApiResponse
import com.padguard.data.model.AppInstallRequest
import com.padguard.data.model.AppPolicyDto
import com.padguard.data.model.BatchSuspendRequest
import com.padguard.data.model.InstalledAppDto
import com.padguard.data.model.SuspendRequest
import com.padguard.data.model.AppUsageDto
import com.padguard.data.model.AssignGroupRequest
import com.padguard.data.model.BindCodeResponse
import com.padguard.data.model.BrowserDisableRequest
import com.padguard.data.model.CloseAlertRequest
import com.padguard.data.model.CreateGroupRequest
import com.padguard.data.model.DailyLimitRequest
import com.padguard.data.model.DailyUsageDto
import com.padguard.data.model.DailyViolationDto
import com.padguard.data.model.DeviceDto
import com.padguard.data.model.DeviceGroupDto
import com.padguard.data.model.DomainVisitDto
import com.padguard.data.model.GeofenceDto
import com.padguard.data.model.LocationDto
import com.padguard.data.model.LocationTrackPointDto
import com.padguard.data.model.LoginPasswordRequest
import com.padguard.data.model.LoginResponse
import com.padguard.data.model.LoginSmsRequest
import com.padguard.data.model.MessagePublishRequestDto
import com.padguard.data.model.PolicyTemplateDto
import com.padguard.data.model.PublishedMessageDto
import com.padguard.data.model.RenameDeviceRequest
import com.padguard.data.model.UpdateBlacklistRequest
import com.padguard.data.model.ScreenMonitorSettingsDto
import com.padguard.data.model.SendSmsRequest
import com.padguard.data.model.ScreenshotDto
import com.padguard.data.model.StartRecordRequest
import com.padguard.data.model.StatisticsReportDto
import com.padguard.data.model.TimeRestrictionDto
import com.padguard.data.model.UsageStatsDto
import com.padguard.data.model.UrlBlacklistRequest
import com.padguard.data.model.ViolationStatsDto
import com.padguard.data.model.WebActivityStatsDto
import com.padguard.data.model.WebPolicyDto
import com.padguard.domain.model.Alert
import com.padguard.domain.model.AlertCategory
import com.padguard.domain.model.AlertLevel
import com.padguard.domain.model.AlertStatus
import com.padguard.domain.model.AppPolicy
import com.padguard.domain.model.AppUsage
import com.padguard.domain.model.ControlMode
import com.padguard.domain.model.DailyUsage
import com.padguard.domain.model.Device
import com.padguard.domain.repository.DeviceGroup
import com.padguard.domain.model.DeviceOnlineStatus
import com.padguard.domain.model.DevicePermissionConfig
import com.padguard.domain.model.DeviceFunctionConfig
import com.padguard.domain.model.DistributableApp
import com.padguard.domain.model.GeofenceConfig
import com.padguard.domain.model.InstalledApp
import com.padguard.domain.model.LocationInfo
import com.padguard.domain.model.MessageContentType
import com.padguard.domain.model.MessagePublishRequest
import com.padguard.domain.model.PublishedMessage
import com.padguard.domain.model.RecordResolution
import com.padguard.domain.model.ReportPeriod
import com.padguard.domain.model.SceneType
import com.padguard.domain.model.ScreenMonitorSettings
import com.padguard.domain.model.ScreenshotData
import com.padguard.domain.model.StatisticsReport
import com.padguard.domain.model.TimeRestriction
import com.padguard.domain.model.UsageStats
import com.padguard.domain.model.User
import com.padguard.domain.model.UserRole
import com.padguard.domain.repository.ViolationStats
import com.padguard.domain.repository.WebActivityStats
import com.padguard.domain.repository.AlertRepository
import com.padguard.domain.repository.AuthRepository
import com.padguard.domain.repository.DeviceRepository
import com.padguard.domain.repository.LocationRepository
import com.padguard.domain.repository.MessageRepository
import com.padguard.domain.repository.MonitorRepository
import com.padguard.domain.repository.PolicyRepository
import com.padguard.domain.repository.StatisticsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Response
import retrofit2.Retrofit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 真实后端数据源（接真线）。
 *
 * 取代 Mock 阶段的 [com.padguard.data.local.LocalDataSource]，将 8 个领域仓储接口映射到
 * `https://<域名>/v1/` 下的真实 REST 接口（统一响应体 [ApiResponse]，约定 `code == 0` 为成功）。
 *
 * 设计原则（与用户稳定性要求一致）：
 * - 统一异常处理：所有请求经 [exec] 收敛为 `Result<ApiResponse<T>>`，区分 HTTP 错误、业务码错误与网络异常；
 * - 防御式解析：后端可空字段已在 DTO 层放宽，映射时再以安全枚举/默认值兜底，避免单点脏数据导致整体崩溃；
 * - 实时能力：设备列表与告警通过轮询 Flow 暴露（WebSocket 推送为后续生产演进项）。
 *
 * 暂未对接的能力（后端尚未提供接口）将返回明确失败或安全默认值，并在方法注释中标注，便于后续补齐。
 */
@Singleton
class ApiDataSource @Inject constructor(
    private val authApi: AuthApi,
    private val deviceApi: DeviceApi,
    private val monitorApi: MonitorApi,
    private val messageApi: MessageApi,
    private val locationApi: LocationApi,
    private val policyApi: PolicyApi,
    private val statisticsApi: StatisticsApi,
    private val alertApi: AlertApi,
    private val appManageApi: AppManageApi,
    private val tokenManager: TokenManager,
    private val authenticatedClient: OkHttpClient,
    private val retrofit: Retrofit
) : AuthRepository, DeviceRepository, MonitorRepository, PolicyRepository,
    StatisticsRepository, AlertRepository, MessageRepository, LocationRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ==================== 统一请求封装 ====================

    private suspend fun <T> exec(call: suspend () -> Response<ApiResponse<T>>): Result<ApiResponse<T>> {
        return try {
            val resp = call()
            if (resp.isSuccessful) {
                val body = resp.body()
                if (body == null) {
                    Result.failure(Exception("服务端返回空响应"))
                } else if (body.code == 0) {
                    Result.success(body)
                } else {
                    Result.failure(Exception("[${(body.code)}] ${body.message}"))
                }
            } else {
                val err = runCatching { resp.errorBody()?.string() }.getOrNull()
                Result.failure(Exception("HTTP ${resp.code()}: ${err ?: resp.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==================== AuthRepository ====================

    override suspend fun loginWithPassword(phone: String, password: String): Result<User> {
        return exec { authApi.loginWithPassword(LoginPasswordRequest(phone, password)) }.map { resp ->
            val lr = resp.data ?: throw Exception("登录响应缺少数据")
            tokenManager.saveLogin(
                userId = lr.user.id, phone = lr.user.phone, nickname = lr.user.nickname,
                avatar = lr.user.avatar, role = lr.user.role, sceneType = lr.user.sceneType,
                accessToken = lr.token, refreshToken = lr.refreshToken
            )
            lr.toDomainUser()
        }
    }

    override suspend fun loginWithSmsCode(phone: String, code: String): Result<User> {
        return exec { authApi.loginWithSms(LoginSmsRequest(phone, code)) }.map { resp ->
            val lr = resp.data ?: throw Exception("登录响应缺少数据")
            tokenManager.saveLogin(
                userId = lr.user.id, phone = lr.user.phone, nickname = lr.user.nickname,
                avatar = lr.user.avatar, role = lr.user.role, sceneType = lr.user.sceneType,
                accessToken = lr.token, refreshToken = lr.refreshToken
            )
            lr.toDomainUser()
        }
    }

    override suspend fun sendSmsCode(phone: String): Result<Unit> =
        exec { authApi.sendSmsCode(SendSmsRequest(phone)) }.map { Unit }

    override suspend fun logout(): Result<Unit> {
        runCatching { authApi.logout() } // 尽力通知服务端，失败不影响本地登出
        tokenManager.clear()
        _deviceListFlow.value = emptyList()
        return Result.success(Unit)
    }

    override suspend fun refreshToken(): Result<User> {
        val refresh = tokenManager.getRefreshToken()
            ?: return Result.failure(Exception("无刷新令牌，请重新登录"))
        return exec { authApi.refreshToken(refresh) }.map { resp ->
            val tr = resp.data ?: throw Exception("刷新响应缺少数据")
            tokenManager.saveTokens(tr.token, tr.refreshToken)
            tokenManager.currentUserFlow().firstOrNull()
                ?: throw Exception("刷新后本地用户丢失")
        }
    }

    override fun getCurrentUserFlow(): Flow<User?> = tokenManager.currentUserFlow()

    override suspend fun isLoggedIn(): Boolean = tokenManager.isLoggedIn()

    // ==================== DeviceRepository ====================

    private val _deviceListFlow = MutableStateFlow<List<Device>>(emptyList())

    init {
        startDevicePolling()
    }

    private fun startDevicePolling() {
        scope.launch {
            while (isActive) {
                if (tokenManager.getAccessToken() != null) refreshDeviceList()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun refreshDeviceList() {
        exec { deviceApi.getDeviceList() }.onSuccess { resp ->
            _deviceListFlow.value = resp.data.orEmpty().map { it.toDomain() }
        }
    }

    override fun observeDeviceList(): Flow<List<Device>> = _deviceListFlow.asStateFlow()

    override suspend fun getDeviceList(): Result<List<Device>> =
        exec { deviceApi.getDeviceList() }.map { it.data.orEmpty().map { d -> d.toDomain() } }

    override suspend fun getDeviceDetail(deviceId: String): Result<Device> =
        exec { deviceApi.getDeviceDetail(deviceId) }.map { it.data?.toDomain() ?: throw Exception("设备不存在") }

    override suspend fun bindDevice(deviceId: String, alias: String?): Result<Device> =
        Result.failure(
            Exception("设备绑定需由孩子端输入配对码完成；请在「接入」页生成配对码 / 二维码后，由被控平板扫码或输入。")
        )

    override suspend fun unbindDevice(deviceId: String): Result<Unit> {
        val result = exec { deviceApi.unbindDevice(deviceId) }.map { Unit }
        // 解绑后必须立刻刷新列表：否则要等 POLL_INTERVAL_MS 轮询才更新，
        // 家长端看着设备还在列表里，会误以为"解绑没生效"。
        // 注意必须写成显式 suspend 调用 —— Result.onSuccess 的 lambda 不是挂起上下文。
        if (result.isSuccess) refreshDeviceList()
        return result
    }

    override suspend fun renameDevice(deviceId: String, newName: String): Result<Unit> =
        exec { deviceApi.renameDevice(deviceId, RenameDeviceRequest(newName)) }.map { Unit }

    override suspend fun getDeviceGroups(): Result<List<DeviceGroup>> =
        exec { deviceApi.getGroups(null) }.map { it.data.orEmpty().map { g -> g.toDomain() } }

    override suspend fun createGroup(name: String, sceneType: SceneType): Result<DeviceGroup> =
        exec { deviceApi.createGroup(CreateGroupRequest(name, sceneType.name)) }
            .map { it.data?.toDomain() ?: throw Exception("创建分组失败") }

    override suspend fun assignDeviceToGroup(deviceId: String, groupId: String): Result<Unit> =
        exec { deviceApi.assignToGroup(deviceId, AssignGroupRequest(groupId)) }.map { Unit }

    // ----- 设备接入 -----

    override suspend fun generateBindQrPayload(): Result<String> =
        exec { deviceApi.generateBindCode() }.map { resp ->
            val code = resp.data?.bindCode ?: throw Exception("生成绑定码失败")
            "padguard://bind?code=$code"
        }

    override suspend fun bindByQrPayload(payload: String, alias: String?): Result<Device> {
        val code = parseBindCode(payload)
            ?: return Result.failure(IllegalArgumentException("二维码无效，请扫描孩子端展示的接入码"))
        return bindByPairingCode(code, alias)
    }

    override suspend fun generatePairingCode(): Result<String> =
        exec { deviceApi.generateBindCode() }.map { resp ->
            resp.data?.bindCode ?: throw Exception("生成配对码失败")
        }

    override suspend fun bindByPairingCode(code: String, alias: String?): Result<Device> {
        if (!code.matches(Regex("\\d{6}"))) {
            return Result.failure(IllegalArgumentException("配对码为 6 位数字，请重新输入"))
        }
        // 真实绑定由孩子端拿 code 调 /api/v1/device/bind 完成；此处轮询设备列表等待新设备出现
        val before = exec { deviceApi.getDeviceList() }
            .getOrElse { return Result.failure(it) }
            .data.orEmpty().mapNotNull { it.deviceId }.toSet()

        repeat(BIND_POLL_ATTEMPTS) {
            delay(BIND_POLL_INTERVAL_MS)
            val current = exec { deviceApi.getDeviceList() }.getOrNull()?.data ?: return@repeat
            val fresh = current.firstOrNull { d -> d.deviceId !in before }
            if (fresh != null) return Result.success(fresh.toDomain())
        }
        return Result.failure(Exception("等待孩子端完成绑定超时，请确认孩子端已输入配对码"))
    }

    override suspend fun discoverLanDevices(): Result<List<com.padguard.domain.model.LanDevice>> =
        Result.failure(
            Exception("局域网发现需在真机网络环境下通过 mDNS 扫描实现，当前版本后台未提供该能力。")
        )

    override suspend fun bindLanDevice(
        lanDevice: com.padguard.domain.model.LanDevice,
        alias: String?
    ): Result<Device> = Result.failure(Exception("局域网绑定需被控平板联网被发现后接入，当前版本未对接。"))

    override suspend fun importDevices(
        rows: List<com.padguard.domain.model.DeviceImportRow>
    ): Result<com.padguard.domain.model.DeviceImportResult> =
        Result.failure(Exception("批量导入设备台账需服务端支持，当前版本未对接。"))

    // ----- 设备权限 / 功能 / 分发 / 升级（后端暂未提供接口，返回安全默认 / 明确失败） -----

    override suspend fun getDevicePermissions(deviceId: String): Result<DevicePermissionConfig> =
        Result.success(DevicePermissionConfig(deviceId = deviceId))

    override suspend fun updateDevicePermissions(config: DevicePermissionConfig): Result<DevicePermissionConfig> =
        Result.failure(Exception("服务端未提供设备权限配置接口，暂不支持下发。"))

    override suspend fun getDeviceFunctionConfig(deviceId: String): Result<DeviceFunctionConfig> =
        Result.success(DeviceFunctionConfig(deviceId = deviceId))

    override suspend fun updateDeviceFunctionConfig(config: DeviceFunctionConfig): Result<DeviceFunctionConfig> =
        Result.failure(Exception("服务端未提供设备功能开关配置接口，暂不支持下发。"))

    override suspend fun getDistributableApps(deviceId: String): Result<List<com.padguard.domain.model.DistributableApp>> =
        Result.success(emptyList())

    override suspend fun dispatchApp(
        deviceId: String,
        packageName: String,
        action: com.padguard.domain.model.AppDispatchAction
    ): Result<Unit> = Result.failure(Exception("服务端未提供应用分发接口，暂不支持。"))

    override suspend fun getDeviceUpgradeInfo(deviceId: String): Result<com.padguard.domain.model.DeviceUpgradeInfo> =
        Result.failure(Exception("服务端未提供远程升级信息接口，暂不支持。"))

    override suspend fun upgradeDevice(deviceId: String): Result<Unit> =
        Result.failure(Exception("服务端未提供远程升级接口，暂不支持。"))

    // ==================== MonitorRepository ====================

    override suspend fun requestScreenshot(deviceId: String): Result<ScreenshotData> =
        exec { monitorApi.requestScreenshot(deviceId) }.map { it.data?.toDomain() ?: throw Exception("截屏请求失败") }

    override suspend fun getDeviceInfo(deviceId: String): Result<Device> =
        exec { deviceApi.getDeviceDetail(deviceId) }.map { it.data?.toDomain() ?: throw Exception("设备不存在") }

    override suspend fun getScreenshotHistory(deviceId: String, limit: Int): Result<List<ScreenshotData>> =
        exec { monitorApi.getScreenshotHistory(deviceId, limit) }.map { it.data.orEmpty().map { s -> s.toDomain() } }

    override suspend fun downloadImage(imageUrl: String): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            runCatching {
                // resolve 同时兼容相对路径（/v1/files/{id}）与绝对 URL；
                // 占位 baseUrl 的 host 由 HostSelectionInterceptor 统一改写为真实服务器
                val url = retrofit.baseUrl().resolve(imageUrl) ?: error("非法图片地址: $imageUrl")
                authenticatedClient.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                    check(resp.isSuccessful) { "图片下载失败: HTTP ${resp.code}" }
                    resp.body?.bytes() ?: error("图片下载失败: 空响应")
                }
            }
        }

    override suspend fun takePhoto(deviceId: String): Result<String> =
        exec { monitorApi.takePhoto(deviceId) }.map { it.data?.url ?: throw Exception("拍照请求失败") }

    override suspend fun startRecording(deviceId: String): Result<String> =
        exec { monitorApi.startRecording(deviceId) }.map { it.data?.url ?: throw Exception("录音请求失败") }

    override suspend fun stopRecording(deviceId: String): Result<String> =
        exec { monitorApi.stopRecording(deviceId) }.map { it.data?.url ?: throw Exception("停止录音失败") }

    override suspend fun startScreenRecord(
        deviceId: String,
        resolution: RecordResolution,
        withAudio: Boolean
    ): Result<com.padguard.domain.model.ScreenRecordTask> =
        exec { monitorApi.startScreenRecord(deviceId, StartRecordRequest(resolution.name, withAudio)) }
            .map { it.data?.toDomain() ?: throw Exception("录屏启动失败") }

    override suspend fun stopScreenRecord(taskId: String): Result<String> =
        exec { monitorApi.stopScreenRecord(taskId) }.map { it.data?.url ?: throw Exception("停止录屏失败") }

    override suspend fun getScreenMonitorSettings(deviceId: String): Result<ScreenMonitorSettings> =
        exec { monitorApi.getScreenMonitorSettings(deviceId) }.map { it.data?.toDomain() ?: throw Exception("读取监控设置失败") }

    override suspend fun updateScreenMonitorSettings(settings: ScreenMonitorSettings): Result<ScreenMonitorSettings> =
        exec { monitorApi.updateScreenMonitorSettings(settings.deviceId, settings.toDto()) }
            .map { it.data?.toDomain() ?: throw Exception("更新监控设置失败") }

    // ==================== MessageRepository ====================

    override suspend fun publishMessage(request: MessagePublishRequest): Result<PublishedMessage> =
        exec { messageApi.publishMessage(request.deviceId, request.toDto()) }
            .map { it.data?.toDomain() ?: throw Exception("信息发布失败") }

    override suspend fun getPublishedMessages(deviceId: String, limit: Int): Result<List<PublishedMessage>> =
        exec { messageApi.getPublishedMessages(deviceId, limit) }.map { it.data.orEmpty().map { m -> m.toDomain() } }

    // ==================== LocationRepository ====================

    override suspend fun getDeviceLocation(deviceId: String): Result<LocationInfo> =
        exec { locationApi.getDeviceLocation(deviceId) }.map { it.data?.toDomain() ?: throw Exception("获取位置失败") }

    override suspend fun getGeofence(deviceId: String): Result<com.padguard.domain.model.GeofenceConfig> =
        exec { locationApi.getGeofence(deviceId) }.map { it.data?.toDomain() ?: throw Exception("读取围栏失败") }

    override suspend fun updateGeofence(config: com.padguard.domain.model.GeofenceConfig): Result<com.padguard.domain.model.GeofenceConfig> =
        exec { locationApi.updateGeofence(config.deviceId, config.toDto()) }
            .map { it.data?.toDomain() ?: throw Exception("更新围栏失败") }

    override suspend fun getTrackHistory(deviceId: String): Result<List<LocationInfo>> =
        exec { locationApi.getTrackHistory(deviceId) }.map { it.data.orEmpty().map { p -> p.toDomain() } }

    // ==================== PolicyRepository ====================

    override suspend fun getTimeRestrictions(deviceId: String): Result<List<TimeRestriction>> =
        exec { policyApi.getTimeRestrictions(deviceId) }.map { it.data.orEmpty().map { t -> t.toDomain() } }

    override suspend fun setTimeRestriction(restriction: TimeRestriction): Result<TimeRestriction> =
        exec { policyApi.setTimeRestriction(restriction.deviceId, restriction.toDto()) }
            .map { it.data?.toDomain() ?: throw Exception("保存时段限制失败") }

    override suspend fun deleteTimeRestriction(restrictionId: String): Result<Unit> =
        exec { policyApi.deleteTimeRestriction(restrictionId) }.map { Unit }

    override suspend fun setGlobalDailyLimit(deviceId: String, minutes: Int): Result<Unit> =
        exec { policyApi.setGlobalDailyLimit(deviceId, DailyLimitRequest(minutes)) }.map { Unit }

    override suspend fun getGlobalDailyLimit(deviceId: String): Result<Int> =
        exec { policyApi.getGlobalDailyLimit(deviceId) }.map { it.data ?: 0 }

    override suspend fun getInstalledApps(deviceId: String): Result<List<AppPolicy>> =
        exec { policyApi.getInstalledApps(deviceId) }.map { it.data.orEmpty().map { a -> a.toDomain() } }

    override suspend fun updateAppBlacklist(deviceId: String, blockedPackages: List<String>): Result<Unit> =
        exec { policyApi.updateAppBlacklist(deviceId, UpdateBlacklistRequest(blockedPackages)) }.map { Unit }

    override suspend fun setAppTimeLimit(policy: AppPolicy): Result<Unit> =
        // 后端 /apps/limit 实际写入设备每日总时长；此处复用该入口写入
        exec { policyApi.setAppTimeLimit(policy.deviceId, DailyLimitRequest(policy.dailyLimitMinutes ?: 0)) }.map { Unit }

    override suspend fun installApp(deviceId: String, appUrl: String): Result<Unit> =
        Result.failure(Exception("服务端未提供远程安装接口，暂不支持。"))

    override suspend fun uninstallApp(deviceId: String, packageName: String): Result<Unit> =
        exec { appManageApi.uninstallApp(deviceId, SuspendRequest(packageName)) }.map { Unit }

    override suspend fun getAppInventory(deviceId: String): Result<List<InstalledApp>> =
        exec { appManageApi.getInstalledApps(deviceId) }
            .map { it.data.orEmpty().map { dto -> dto.toDomain() } }

    override suspend fun installRemoteApp(deviceId: String, app: DistributableApp): Result<Unit> =
        exec {
            appManageApi.installApp(
                deviceId,
                AppInstallRequest(apkUrl = app.apkUrl, packageName = app.packageName, appName = app.appName)
            )
        }.map { Unit }

    override suspend fun setAppSuspended(deviceId: String, packageName: String, suspended: Boolean): Result<Unit> =
        exec { appManageApi.suspendApp(deviceId, SuspendRequest(packageName, suspended)) }.map { Unit }

    override suspend fun setAppsSuspended(
        deviceId: String,
        packages: List<String>,
        suspended: Boolean
    ): Result<Unit> =
        exec { appManageApi.suspendAppsBatch(deviceId, BatchSuspendRequest(packages, suspended)) }.map { Unit }

    override suspend fun getWebPolicy(deviceId: String): Result<com.padguard.domain.model.WebPolicy> =
        exec { policyApi.getWebPolicy(deviceId) }.map { it.data?.toDomain() ?: throw Exception("读取上网策略失败") }

    override suspend fun updateUrlBlacklist(deviceId: String, urls: List<String>): Result<Unit> =
        exec { policyApi.updateUrlBlacklist(deviceId, UrlBlacklistRequest(urls)) }.map { Unit }

    override suspend fun setBrowserDisabled(deviceId: String, disabled: Boolean): Result<Unit> =
        exec { policyApi.setBrowserDisabled(deviceId, BrowserDisableRequest(disabled)) }.map { Unit }

    override suspend fun lockScreen(deviceId: String): Result<Unit> =
        exec { policyApi.lockScreen(deviceId) }.map { Unit }

    override suspend fun setEyeProtection(deviceId: String, enabled: Boolean, filterLevel: Int): Result<Unit> =
        Result.failure(Exception("服务端未提供护眼参数接口，暂不支持。"))

    override suspend fun unlock(deviceId: String): Result<Unit> =
        exec { policyApi.unlock(deviceId) }.map { Unit }

    override suspend fun tempUnlock(deviceId: String, durationMinutes: Int): Result<Unit> =
        exec {
            policyApi.tempUnlock(
                deviceId,
                com.padguard.data.model.TempUnlockRequest(durationMinutes = durationMinutes)
            )
        }.map { Unit }

    override suspend fun getUnlockTickets(deviceId: String): Result<List<com.padguard.domain.repository.UnlockTicket>> =
        exec { policyApi.getUnlockTickets(deviceId) }.map { list ->
            list.data.orEmpty().map { dto ->
                com.padguard.domain.repository.UnlockTicket(
                    id = dto.id, deviceId = dto.deviceId, packageName = dto.packageName,
                    appLabel = dto.appLabel, durationMinutes = dto.durationMinutes,
                    reason = dto.reason, status = dto.status, createdAt = dto.createdAt
                )
            }
        }

    override suspend fun approveUnlockTicket(
        deviceId: String, ticketId: String, durationMinutes: Int?
    ): Result<Unit> =
        exec {
            policyApi.approveUnlockTicket(
                deviceId, ticketId,
                com.padguard.data.model.UnlockApproveRequest(durationMinutes = durationMinutes)
            )
        }.map { Unit }

    override suspend fun ignoreUnlockTicket(deviceId: String, ticketId: String): Result<Unit> =
        exec {
            policyApi.rejectUnlockTicket(
                deviceId, ticketId, com.padguard.data.model.UnlockRejectRequest(reason = "家长已忽略")
            )
        }.map { Unit }

    override suspend fun setControlMode(deviceId: String, mode: ControlMode): Result<Unit> =
        exec { policyApi.setControlMode(deviceId, com.padguard.data.model.ModeChangeRequest(mode.name)) }.map { Unit }

    override suspend fun getPolicyTemplates(sceneType: SceneType): Result<List<com.padguard.domain.repository.PolicyTemplate>> =
        exec { policyApi.getTemplates(sceneType.name) }.map { it.data.orEmpty().map { t -> t.toDomain() } }

    override suspend fun applyPolicyTemplate(deviceId: String, templateId: String): Result<Unit> =
        exec { policyApi.applyTemplate(deviceId, com.padguard.data.model.ApplyTemplateRequest(templateId)) }.map { Unit }

    override suspend fun getTabletUsageSettings(deviceId: String): Result<com.padguard.domain.model.TabletUsageSettings> =
        Result.failure(Exception("服务端未提供平板使用时间设置接口，待后端补齐。"))

    override suspend fun updateTabletUsageSettings(settings: com.padguard.domain.model.TabletUsageSettings): Result<com.padguard.domain.model.TabletUsageSettings> =
        Result.failure(Exception("服务端未提供平板使用时间设置接口，待后端补齐。"))

    // ==================== StatisticsRepository ====================

    override suspend fun getUsageStats(deviceId: String, period: ReportPeriod): Result<StatisticsReport> =
        exec { statisticsApi.getUsageStats(deviceId, period.name.lowercase()) }
            .map { it.data?.toDomain() ?: throw Exception("获取统计失败") }

    override suspend fun getTodayUsage(deviceId: String): Result<UsageStats> =
        exec { statisticsApi.getTodayUsage(deviceId) }.map { it.data?.toDomain() ?: throw Exception("获取今日使用失败") }

    override suspend fun getAppUsageHistory(
        deviceId: String,
        packageName: String,
        period: ReportPeriod
    ): Result<List<com.padguard.domain.model.AppUsageHistoryEntry>> =
        Result.failure(Exception("服务端未提供单应用使用历史接口，暂不支持。"))

    override suspend fun getWebActivityStats(deviceId: String, period: ReportPeriod): Result<WebActivityStats> =
        exec { statisticsApi.getWebActivity(deviceId, period.name.lowercase()) }
            .map { it.data?.toDomain() ?: throw Exception("获取上网统计失败") }

    override suspend fun getViolationStats(deviceId: String, period: ReportPeriod): Result<ViolationStats> =
        exec { statisticsApi.getViolationStats(deviceId, period.name.lowercase()) }
            .map { it.data?.toDomain() ?: throw Exception("获取违规统计失败") }

    override suspend fun exportReport(deviceId: String, period: ReportPeriod): Result<String> =
        exec { statisticsApi.exportReport(deviceId, period.name.lowercase()) }
            .map { it.data?.fileUrl ?: throw Exception("导出报告失败") }

    // ==================== AlertRepository ====================

    override suspend fun getAlerts(
        deviceId: String?,
        status: AlertStatus?
    ): Result<List<Alert>> =
        exec { alertApi.getAlerts(deviceId, status?.name, 0, 100) }
            .map { it.data.orEmpty().map { a -> a.toDomain() } }

    override suspend fun getUnreadAlertCount(): Result<Int> =
        exec { alertApi.getUnreadCount() }.map { it.data ?: 0 }

    override suspend fun acknowledgeAlert(alertId: String): Result<Unit> =
        exec { alertApi.acknowledgeAlert(alertId) }.map { Unit }

    override suspend fun closeAlert(alertId: String, reason: String?): Result<Unit> =
        exec { alertApi.closeAlert(alertId, reason?.let { CloseAlertRequest(it) }) }.map { Unit }

    override fun observeAlerts(): Flow<Alert> = flow {
        var lastId: String? = null
        while (true) {
            if (tokenManager.getAccessToken() != null) {
                exec { alertApi.getAlerts(null, AlertStatus.ACTIVE.name, 0, 50) }.onSuccess { resp ->
                    val newest = resp.data.orEmpty().maxByOrNull { it.triggeredAt }
                    if (newest != null && newest.id != lastId) {
                        lastId = newest.id
                        emit(newest.toDomain())
                    }
                }
            }
            delay(ALERT_POLL_INTERVAL_MS)
        }
    }

    // ==================== 辅助 ====================

    private fun parseBindCode(payload: String): String? {
        val trimmed = payload.trim()
        if (trimmed.matches(Regex("\\d{6}"))) return trimmed
        // 兼容 "padguard://bind?code=XXXX" 形式（孩子端扫码/ NFC 亦解析此格式）
        if (trimmed.startsWith("padguard://bind")) {
            val q = trimmed.substringAfter("?", "")
            val params = q.split("&").mapNotNull { kv ->
                val pair = kv.split("=", limit = 2)
                if (pair.size == 2) pair[0] to pair[1] else null
            }.toMap()
            val code = params["code"] ?: params["bindCode"]
            if (code != null && code.matches(Regex("\\d{6}"))) return code
        }
        return null
    }

    companion object {
        private const val POLL_INTERVAL_MS = 10_000L
        private const val BIND_POLL_ATTEMPTS = 20
        private const val BIND_POLL_INTERVAL_MS = 2_000L
        private const val ALERT_POLL_INTERVAL_MS = 15_000L
    }
}

// ==================== DTO -> Domain 映射（防御式） ====================

private fun LoginResponse.toDomainUser(): User = User(
    id = user.id, phone = user.phone, nickname = user.nickname, avatar = user.avatar,
    role = safeEnum(user.role) { UserRole.PARENT },
    sceneType = safeEnum(user.sceneType) { SceneType.FAMILY },
    token = token
)

private fun DeviceDto.toDomain(): Device = Device(
    id = id, name = name ?: "未命名设备", deviceId = deviceId, model = model, osVersion = osVersion,
    appVersion = appVersion, onlineStatus = safeEnum(onlineStatus) { DeviceOnlineStatus.UNKNOWN },
    lastOnlineTime = lastOnlineTime, batteryLevel = batteryLevel,
    controlMode = safeEnum(controlMode) { ControlMode.NORMAL }, groupId = groupId, groupName = groupName,
    sceneType = safeEnum(sceneType) { SceneType.FAMILY }, latitude = latitude, longitude = longitude
)

private fun DeviceGroupDto.toDomain(): DeviceGroup = DeviceGroup(
    id = id, name = name, sceneType = safeEnum(sceneType) { SceneType.FAMILY }, deviceCount = deviceCount
)

private fun ScreenshotDto.toDomain(): ScreenshotData = ScreenshotData(
    deviceId = deviceId, imageUrl = imageUrl, thumbnailUrl = thumbnailUrl, status = status,
    capturedAt = capturedAt ?: 0, width = width ?: 0, height = height ?: 0
)

private fun com.padguard.data.model.ScreenRecordTaskDto.toDomain(): com.padguard.domain.model.ScreenRecordTask =
    com.padguard.domain.model.ScreenRecordTask(
        taskId = taskId, deviceId = deviceId, startedAt = startedAt,
        resolution = safeEnum(resolution) { RecordResolution.HD_720P }, withAudio = withAudio
    )

private fun ScreenMonitorSettingsDto.toDomain(): ScreenMonitorSettings = ScreenMonitorSettings(
    deviceId = deviceId, autoRefreshSeconds = autoRefreshSeconds, highDefinition = highDefinition,
    recordResolution = safeEnum(recordResolution) { RecordResolution.HD_720P },
    recordWithAudio = recordWithAudio, allowRemoteLock = allowRemoteLock
)

private fun ScreenMonitorSettings.toDto(): ScreenMonitorSettingsDto = ScreenMonitorSettingsDto(
    deviceId = deviceId, autoRefreshSeconds = autoRefreshSeconds, highDefinition = highDefinition,
    recordResolution = recordResolution.name, recordWithAudio = recordWithAudio, allowRemoteLock = allowRemoteLock
)

private fun LocationDto.toDomain(): LocationInfo = LocationInfo(
    latitude = latitude, longitude = longitude, address = address, accuracy = accuracy, timestamp = timestamp
)

private fun GeofenceDto.toDomain(): GeofenceConfig = GeofenceConfig(
    deviceId = deviceId, enabled = enabled, name = name ?: "安全区域", centerLatitude = centerLatitude,
    centerLongitude = centerLongitude, radiusMeters = radiusMeters, alertOnExit = alertOnExit
)

private fun GeofenceConfig.toDto(): GeofenceDto = GeofenceDto(
    deviceId = deviceId, enabled = enabled, name = name, centerLatitude = centerLatitude,
    centerLongitude = centerLongitude, radiusMeters = radiusMeters, alertOnExit = alertOnExit
)

private fun TimeRestrictionDto.toDomain(): TimeRestriction = TimeRestriction(
    id = id ?: "", deviceId = deviceId ?: "", dayOfWeek = dayOfWeek, startTime = startTime, endTime = endTime,
    maxMinutes = maxMinutes, isEnabled = isEnabled
)

private fun TimeRestriction.toDto(): TimeRestrictionDto = TimeRestrictionDto(
    id = id, deviceId = deviceId, dayOfWeek = dayOfWeek, startTime = startTime, endTime = endTime,
    maxMinutes = maxMinutes, isEnabled = isEnabled
)

private fun InstalledAppDto.toDomain(): InstalledApp = InstalledApp(
    packageName = packageName,
    appName = appName ?: packageName,
    versionName = versionName,
    versionCode = versionCode,
    isSystem = isSystem,
    installed = installed,
    suspended = suspended,
    installTime = installTime,
    updateTime = updateTime,
    lastSeenAt = lastSeenAt,
    blocked = blocked,
    dailyLimitMinutes = dailyLimitMinutes
)

private fun AppPolicyDto.toDomain(): AppPolicy = AppPolicy(
    id = id ?: java.util.UUID.randomUUID().toString(), deviceId = deviceId ?: "", packageName = packageName,
    appName = appName ?: packageName, isBlocked = isBlocked, dailyLimitMinutes = dailyLimitMinutes, iconUrl = null
)

private fun WebPolicyDto.toDomain(): com.padguard.domain.model.WebPolicy = com.padguard.domain.model.WebPolicy(
    deviceId = deviceId, blockedUrls = blockedUrls, browserDisabled = browserDisabled,
    smartShutdownEnabled = smartShutdownEnabled, smartShutdownStartTime = smartShutdownStartTime,
    smartShutdownEndTime = smartShutdownEndTime
)

private fun LocationTrackPointDto.toDomain(): LocationInfo = LocationInfo(
    latitude = latitude, longitude = longitude, address = address,
    accuracy = accuracy, timestamp = timestamp
)

private fun PolicyTemplateDto.toDomain(): com.padguard.domain.repository.PolicyTemplate =
    com.padguard.domain.repository.PolicyTemplate(
        id = id, name = name, description = description ?: "", sceneType = safeEnum(sceneType) { SceneType.FAMILY },
        category = category ?: "通用"
    )

private fun UsageStatsDto.toDomain(): UsageStats = UsageStats(
    deviceId = deviceId, date = date, totalUsageMinutes = totalUsageMinutes,
    appUsages = appUsages.orEmpty().map { it.toDomain() }
)

private fun AppUsageDto.toDomain(): AppUsage = AppUsage(
    packageName = packageName, appName = appName, usageMinutes = usageMinutes, iconUrl = iconUrl
)

private fun DailyUsageDto.toDomain(): DailyUsage = DailyUsage(date = date, usageMinutes = usageMinutes, violationCount = violationCount)

private fun StatisticsReportDto.toDomain(): StatisticsReport = StatisticsReport(
    deviceId = deviceId, period = safeEnum(period) { ReportPeriod.DAILY }, startDate = startDate, endDate = endDate,
    totalUsageMinutes = totalUsageMinutes, dailyUsages = dailyUsages.orEmpty().map { it.toDomain() },
    topApps = topApps.orEmpty().map { it.toDomain() }, violationCount = violationCount, alertCount = alertCount
)

private fun WebActivityStatsDto.toDomain(): WebActivityStats = WebActivityStats(
    totalVisits = totalVisits, topDomains = topDomains.orEmpty().map { it.toDomain() }, blockedAttempts = blockedAttempts
)

private fun DomainVisitDto.toDomain(): com.padguard.domain.repository.DomainVisit =
    com.padguard.domain.repository.DomainVisit(domain = domain, visitCount = visitCount, lastVisitTime = lastVisitTime)

private fun ViolationStatsDto.toDomain(): ViolationStats = ViolationStats(
    totalCount = totalCount, byCategory = byCategory ?: emptyMap(),
    trend = trend.orEmpty().map { com.padguard.domain.repository.DailyViolation(it.date, it.count) }
)

private fun PublishedMessageDto.toDomain(): PublishedMessage = PublishedMessage(
    id = id, deviceId = deviceId, contentType = safeEnum(contentType) { MessageContentType.TEXT },
    summary = summary, displaySeconds = displaySeconds, fullScreen = fullScreen, publishedAt = publishedAt
)

private fun MessagePublishRequest.toDto(): MessagePublishRequestDto = MessagePublishRequestDto(
    deviceId = deviceId, contentType = contentType.name, text = text, mediaUrl = mediaUrl, mediaName = mediaName,
    displaySeconds = displaySeconds, fullScreen = fullScreen, playAudio = playAudio
)

private fun AlertDto.toDomain(): Alert = Alert(
    id = id, deviceId = deviceId, deviceName = deviceName,
    level = safeEnum(level) { AlertLevel.WARNING }, title = title, message = message ?: "",
    status = safeEnum(status) { AlertStatus.ACTIVE }, category = safeEnum(category) { AlertCategory.SYSTEM_ANOMALY },
    triggeredAt = triggeredAt, acknowledgedAt = acknowledgedAt, resolvedAt = resolvedAt
)

private inline fun <reified T : Enum<T>> safeEnum(value: String?, default: () -> T): T =
    if (value.isNullOrBlank()) default() else runCatching { enumValueOf<T>(value) }.getOrDefault(default())
