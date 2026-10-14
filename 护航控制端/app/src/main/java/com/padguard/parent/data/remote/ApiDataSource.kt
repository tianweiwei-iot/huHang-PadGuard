package com.padguard.parent.data.remote

import com.padguard.parent.data.api.BillingApi
import com.padguard.parent.domain.repository.BillingRepository
import com.padguard.parent.data.model.CreateOrderRequest
import com.padguard.parent.data.model.CreateOrderResponse
import com.padguard.parent.data.model.AppListPolicyDto
import com.padguard.parent.data.model.AppListPolicyRequest
import com.padguard.parent.data.model.DeviceImportRequestDto
import com.padguard.parent.data.model.DeviceImportRowDto
import com.padguard.parent.data.model.EyeProtectionRequest
import com.padguard.parent.data.model.LanBindRequestDto
import com.padguard.parent.data.model.OrderDto
import com.padguard.parent.data.model.PayChannelDto
import com.padguard.parent.data.model.PlanDto
import com.padguard.parent.data.model.SubscriptionDto
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import com.padguard.parent.data.api.AlertApi
import com.padguard.parent.data.api.AppManageApi
import com.padguard.parent.data.api.AuthApi
import com.padguard.parent.data.api.DeviceApi
import com.padguard.parent.data.api.FileApi
import com.padguard.parent.data.api.LocationApi
import com.padguard.parent.data.api.MessageApi
import com.padguard.parent.data.api.MonitorApi
import com.padguard.parent.data.api.PolicyApi
import com.padguard.parent.data.api.StatisticsApi
import com.padguard.parent.data.api.AppCatalogItemDto
import com.padguard.parent.data.api.AppDistributionItemDto
import com.padguard.parent.data.api.DistributeAppDto
import com.padguard.parent.data.api.FeaturePolicyDto
import com.padguard.parent.data.api.PermissionPolicyDto
import com.padguard.parent.data.api.UpgradeCheckItemDto
import com.padguard.parent.data.auth.TokenManager
import com.padguard.parent.data.model.AlertDto
import com.padguard.parent.data.model.ApiResponse
import com.padguard.parent.data.model.AppHiddenRequest
import com.padguard.parent.data.model.AppHiddenBatchRequest
import com.padguard.parent.data.model.AppLimitRequest
import com.padguard.parent.domain.repository.UploadedMedia
import com.padguard.parent.data.model.AppInstallRequest
import com.padguard.parent.data.model.AppPolicyDto
import com.padguard.parent.data.model.BatchSuspendRequest
import com.padguard.parent.data.model.InstalledAppDto
import com.padguard.parent.data.model.SuspendRequest
import com.padguard.parent.data.model.AppUsageDto
import com.padguard.parent.data.model.AssignGroupRequest
import com.padguard.parent.data.model.EnrollmentDto
import com.padguard.parent.domain.model.Enrollment
import com.padguard.parent.domain.model.EnrollmentCompleted
import com.padguard.parent.domain.model.EnrollmentState
import com.padguard.parent.data.model.BrowserDisableRequest
import com.padguard.parent.data.model.CloseAlertRequest
import com.padguard.parent.data.model.CreateGroupRequest
import com.padguard.parent.data.model.DailyLimitRequest
import com.padguard.parent.data.model.DailyUsageDto
import com.padguard.parent.data.model.DailyViolationDto
import com.padguard.parent.data.model.DeviceDto
import com.padguard.parent.data.model.DeviceGroupDto
import com.padguard.parent.data.model.DomainVisitDto
import com.padguard.parent.data.model.GeofenceZoneDto
import com.padguard.parent.data.model.LocationDto
import com.padguard.parent.data.model.LocationTrackPointDto
import com.padguard.parent.data.model.LoginPasswordRequest
import com.padguard.parent.data.model.LoginResponse
import com.padguard.parent.data.model.RegisterRequest
import com.padguard.parent.data.model.LoginSmsRequest
import com.padguard.parent.data.model.MessagePublishRequestDto
import com.padguard.parent.data.model.PolicyTemplateDto
import com.padguard.parent.data.model.PublishedMessageDto
import com.padguard.parent.data.model.RenameDeviceRequest
import com.padguard.parent.data.model.UpdateBlacklistRequest
import com.padguard.parent.data.model.ScreenMonitorSettingsDto
import com.padguard.parent.data.model.ScreenStatusDto
import com.padguard.parent.data.model.SendSmsRequest
import com.padguard.parent.data.model.ScreenshotDto
import com.padguard.parent.data.model.StartRecordRequest
import com.padguard.parent.data.model.StatisticsReportDto
import com.padguard.parent.data.model.TimeRestrictionDto
import com.padguard.parent.data.model.UsageStatsDto
import com.padguard.parent.data.model.UrlBlacklistRequest
import com.padguard.parent.data.model.ViolationStatsDto
import com.padguard.parent.data.model.WebActivityStatsDto
import com.padguard.parent.data.model.WebPolicyDto
import com.padguard.parent.data.model.TabletUsageSettingsDto
import com.padguard.parent.data.model.UsageSessionDto
import com.padguard.parent.data.model.TimeRangeDto
import com.padguard.parent.data.model.MinorModeDto
import com.padguard.parent.data.model.MinorModeRequestDto
import com.padguard.parent.data.model.AgeBandDefaultDto
import com.padguard.parent.data.model.CommandResultDto
import com.padguard.parent.data.model.WatermarkRequestDto
import com.padguard.parent.data.model.GuardPasswordRequestDto
import com.padguard.parent.domain.model.Alert
import com.padguard.parent.domain.model.AlertCategory
import com.padguard.parent.domain.model.AlertLevel
import com.padguard.parent.domain.model.AlertStatus
import com.padguard.parent.domain.model.AppPolicy
import com.padguard.parent.domain.model.AppUsage
import com.padguard.parent.domain.model.ControlMode
import com.padguard.parent.domain.model.DailyUsage
import com.padguard.parent.domain.model.Device
import com.padguard.parent.domain.repository.DeviceGroup
import com.padguard.parent.domain.model.DeviceOnlineStatus
import com.padguard.parent.domain.model.DevicePermissionConfig
import com.padguard.parent.domain.model.DeviceFunctionConfig
import com.padguard.parent.domain.model.DistributableApp
import com.padguard.parent.domain.model.AppUpgradeItem
import com.padguard.parent.domain.model.AppUploadMeta
import com.padguard.parent.domain.model.GeofenceConfig
import com.padguard.parent.domain.model.InstalledApp
import com.padguard.parent.domain.model.LocationInfo
import com.padguard.parent.domain.model.MessageContentType
import com.padguard.parent.domain.model.MessagePublishRequest
import com.padguard.parent.domain.model.PublishedMessage
import com.padguard.parent.domain.model.RecordResolution
import com.padguard.parent.domain.model.ReportPeriod
import com.padguard.parent.domain.model.SceneType
import com.padguard.parent.domain.model.ScreenMonitorSettings
import com.padguard.parent.domain.model.ScreenLiveStatus
import com.padguard.parent.domain.model.ScreenshotData
import com.padguard.parent.domain.model.StatisticsReport
import com.padguard.parent.domain.model.TimeRestriction
import com.padguard.parent.domain.model.UsageStats
import com.padguard.parent.domain.model.User
import com.padguard.parent.domain.model.UserRole
import com.padguard.parent.domain.model.TabletUsageSettings
import com.padguard.parent.domain.model.UsageSession
import com.padguard.parent.domain.model.TimeRange
import com.padguard.parent.domain.repository.ViolationStats
import com.padguard.parent.domain.repository.WebActivityStats
import com.padguard.parent.domain.repository.AgeBand
import com.padguard.parent.domain.repository.AgeBandDefault
import com.padguard.parent.domain.repository.MinorMode
import com.padguard.parent.domain.repository.CommandResult
import com.padguard.parent.domain.repository.AlertRepository
import com.padguard.parent.domain.repository.AuthRepository
import com.padguard.parent.domain.repository.DeviceRepository
import com.padguard.parent.domain.repository.LocationRepository
import com.padguard.parent.domain.repository.MessageRepository
import com.padguard.parent.domain.repository.MonitorRepository
import com.padguard.parent.domain.repository.PolicyRepository
import com.padguard.parent.domain.repository.StatisticsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 真实后端数据源（接真线）。
 *
 * 取代 Mock 实现的 [com.padguard.parent.data.local.LocalDataSource]，将 8 个领域仓储接口映射到
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
    private val fileApi: FileApi,
    private val billingApi: BillingApi,
    private val tokenManager: TokenManager,
    private val authenticatedClient: OkHttpClient,
    private val retrofit: Retrofit,
    private val serverPrefs: com.padguard.parent.di.ServerPrefs,
    private val staticConfigCache: StaticConfigCache,
    /** 服务端实时推送通道：接入完成等事件由它驱动，查询接口退化为兜底 */
    private val parentPushClient: ParentPushClient
) : AuthRepository, DeviceRepository, MonitorRepository, PolicyRepository,
    StatisticsRepository, AlertRepository, MessageRepository, LocationRepository,
    BillingRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 设备台账变化必须驱动内存列表流刷新。
     *
     * 孩子端完成接入后，家长端列表若不刷新，家长就会看到"孩子端明明绑上了，
     * 设备列表里却没有这台设备"。台账变化的源头是接入完成事件，
     * 因此在这里订阅它并刷新列表，而不是让每个页面各想办法。
     */
    init {
        scope.launch {
            parentPushClient.messages
                .filterIsInstance<ParentPushMessage.DeviceEnrolled>()
                .collect { refreshDeviceList() }
        }
    }

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
                Result.failure(
                    Exception("HTTP ${resp.code()}: ${err?.let(::readableError) ?: resp.message()}")
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 把服务端错误响应体还原成人能读的一句话。
     *
     * 服务端对鉴权类失败会带 HTTP 401/403 + `{code,message}` 响应体（例如冻结账号的 1004）。
     * 直接把整个 JSON 抛给上层，家长看到的是一长串花括号，等于没有提示；
     * 这里取出 message（带错误码），让"账号已被冻结，无法登录"这类可执行的提示真正显示出来。
     */
    private fun readableError(raw: String): String = try {
        val o = org.json.JSONObject(raw)
        val message = o.optString("message").orEmpty()
        if (message.isBlank()) raw
        else {
            val code = o.optInt("code", -1)
            if (code > 0) "[$code] $message" else message
        }
    } catch (e: Exception) {
        raw
    }

    /**
     * 只关心"指令有没有被服务端接受"的写操作。
     *
     * 早期此处仅判 [Response.isSuccessful]，而服务端对业务失败
     * （2001 设备不存在 / 2003 不归属 / 4001 订阅过期 …）回落为 HTTP 200 + `{code,message}` 信封，
     * 于是"订阅过期点同意放行""设备已解绑点锁屏"等全部被误判成功。
     * 现额外解析信封里的业务 code：非 0 一律按失败上抛，覆盖 11 个指令链路
     * （unbind/lock/unlock/tempUnlock/审批工单/setControlMode/setMinorMode…）。
     */
    private suspend fun execRaw(call: suspend () -> Response<okhttp3.ResponseBody>): Result<Unit> {
        return try {
            val resp = call()
            if (!resp.isSuccessful) {
                val err = runCatching { resp.errorBody()?.string() }.getOrNull()
                Result.failure(Exception("HTTP ${resp.code()}: ${err?.let(::readableError) ?: resp.message()}"))
            } else {
                val rawBody = runCatching { resp.body()?.string() }.getOrNull()
                val bizError = rawBody?.takeIf { it.isNotBlank() }?.let { parseBizError(it) }
                if (bizError != null) Result.failure(Exception(bizError))
                else Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 解析业务信封里的 code；非 0 返回可读错误文案，0 / 缺 code 返回 null（视为成功）。
     * 仅对"明确带非 0 code"的响应判失败，避免对空响应或无关 JSON 响应体误伤。
     */
    private fun parseBizError(raw: String): String? = try {
        val o = org.json.JSONObject(raw)
        val code = o.optInt("code", 0)
        if (code != 0) {
            val message = o.optString("message").orEmpty()
            "[$code] ${if (message.isBlank()) "操作未被服务端接受" else message}"
        } else null
    } catch (e: Exception) {
        null
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

    override suspend fun register(phone: String, password: String, nickname: String?): Result<User> {
        return exec { authApi.register(RegisterRequest(phone, password, nickname)) }.map { resp ->
            val lr = resp.data ?: throw Exception("注册响应缺少数据")
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
        // 解绑接口响应体为空，走 execRaw（ResponseBody 原始接收）：
        // 用 exec { ... ApiResponse<Unit> } 会让 Moshi 在构建 converter 时就抛
        // "Unable to create converter for kotlin.Unit"，请求根本发不出去。
        val result = execRaw { deviceApi.unbindDevice(deviceId) }
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

    /**
     * 打开（或复用）接入会话：一次拿到二维码与接入码。
     *
     * 不做"二维码和配对码各请求一次"这种事 —— 两者同源是服务端保证的，
     * 端上只需要渲染，不需要（也不应该）自己拼装或二次请求。
     */
    override suspend fun openEnrollment(): Result<Enrollment> =
        exec { deviceApi.openEnrollment() }.mapCatching { resp ->
            resp.data?.toDomain() ?: throw Exception("服务端未返回接入信息")
        }

    /** 查询接入进展（推送丢失时的兜底，永远以服务端状态为准） */
    override suspend fun currentEnrollment(): Result<Enrollment?> =
        exec { deviceApi.currentEnrollment() }.map { resp -> resp.data?.toDomain() }

    override suspend fun refreshEnrollment(): Result<Enrollment> =
        exec { deviceApi.refreshEnrollment() }.mapCatching { resp ->
            resp.data?.toDomain() ?: throw Exception("服务端未返回接入信息")
        }

    override suspend fun cancelEnrollment(): Result<Unit> =
        execRaw { deviceApi.cancelEnrollment() }

    /**
     * 接入完成的实时事件。
     *
     * 家长端不必轮询"孩子绑上没"：服务端在孩子端完成绑定时主动推 `device.enrolled`。
     * 推送丢失时由 [currentEnrollment] 兜底 —— 快路径与兜底路径职责分明。
     */
    override fun observeEnrollmentCompleted(): Flow<EnrollmentCompleted> =
        parentPushClient.messages
            .filterIsInstance<ParentPushMessage.DeviceEnrolled>()
            .map { EnrollmentCompleted(sessionId = it.sessionId, deviceId = it.deviceId) }

    override suspend fun discoverLanDevices(): Result<List<com.padguard.parent.domain.model.LanDevice>> =
        exec { deviceApi.discoverLanDevices() }.map { it.data.orEmpty().map { d -> d.toDomain() } }

    override suspend fun bindLanDevice(
        lanDevice: com.padguard.parent.domain.model.LanDevice,
        alias: String?
    ): Result<Device> =
        exec { deviceApi.bindLanDevice(LanBindRequestDto(lanDevice.deviceId, alias)) }
            .map { it.data?.toDomain() ?: throw Exception("接入设备失败：服务端未返回设备信息") }

    override suspend fun importDevices(
        rows: List<com.padguard.parent.domain.model.DeviceImportRow>
    ): Result<com.padguard.parent.domain.model.DeviceImportResult> =
        exec { deviceApi.importDevices(DeviceImportRequestDto(rows.map { DeviceImportRowDto(it.hardwareId, it.alias, it.model, it.groupName) })) }
            .map { it.data?.toDomain() ?: throw Exception("批量导入失败：服务端未返回结果") }

    // ----- 设备权限 / 功能 / 分发 / 升级（对接服务端 /v1/devices/{deviceId}/mgmt） -----

    override suspend fun getDevicePermissions(deviceId: String): Result<DevicePermissionConfig> =
        exec { deviceApi.getDevicePermissions(deviceId) }
            .map { resp -> resp.data?.let { DevicePermissionConfig(deviceId, it.permissions.toSet()) }
                ?: DevicePermissionConfig(deviceId) }

    override suspend fun updateDevicePermissions(config: DevicePermissionConfig): Result<DevicePermissionConfig> =
        exec { deviceApi.setDevicePermissions(config.deviceId, PermissionPolicyDto(permissions = config.permissions.toList())) }
            .map { resp -> resp.data?.let { DevicePermissionConfig(config.deviceId, it.permissions.toSet()) } ?: config }

    override suspend fun getDeviceFunctionConfig(deviceId: String): Result<DeviceFunctionConfig> =
        exec { deviceApi.getDeviceFeatures(deviceId) }
            .map { resp -> resp.data?.let { DeviceFunctionConfig(deviceId, it.features) } ?: DeviceFunctionConfig(deviceId) }

    override suspend fun updateDeviceFunctionConfig(config: DeviceFunctionConfig): Result<DeviceFunctionConfig> =
        exec { deviceApi.setDeviceFeatures(config.deviceId, FeaturePolicyDto(features = config.features)) }
            .map { resp -> resp.data?.let { DeviceFunctionConfig(config.deviceId, it.features) } ?: config }

    override suspend fun getDistributableApps(deviceId: String): Result<List<DistributableApp>> =
        exec { deviceApi.getAppCatalog(deviceId) }
            .map { resp -> resp.data.orEmpty().map { d ->
                DistributableApp(
                    id = d.id,
                    packageName = d.packageName,
                    appName = d.appName ?: d.packageName,
                    versionCode = d.versionCode,
                    versionName = d.versionName ?: "",
                    apkUrl = absoluteUrl(d.apkUrl) ?: d.apkUrl,
                    sizeBytes = d.sizeBytes,
                    iconUrl = absoluteUrl(d.iconUrl),
                    description = d.description
                )
            } }

    override suspend fun dispatchApp(deviceId: String, catalogId: String, force: Boolean): Result<Unit> =
        exec { deviceApi.distributeApp(deviceId, catalogId, DistributeAppDto(catalogId = catalogId, force = force)) }
            .map { Unit }

    override suspend fun getDeviceUpgradeInfo(deviceId: String): Result<List<AppUpgradeItem>> =
        exec { deviceApi.upgradeCheck(deviceId) }
            .map { resp -> resp.data.orEmpty().map { d ->
                AppUpgradeItem(
                    catalogId = d.catalogId,
                    packageName = d.packageName,
                    appName = d.appName ?: d.packageName,
                    catalogVersionCode = d.catalogVersionCode,
                    catalogVersionName = d.catalogVersionName,
                    installedVersionCode = d.installedVersionCode,
                    installedVersionName = d.installedVersionName,
                    upgradeAvailable = d.upgradeAvailable
                )
            } }

    override suspend fun upgradeDevice(deviceId: String, catalogId: String): Result<Unit> =
        exec { deviceApi.upgradeApp(deviceId, catalogId) }.map { Unit }

    override suspend fun uploadAppCatalog(
        deviceId: String,
        bytes: ByteArray,
        meta: AppUploadMeta,
        fileName: String
    ): Result<DistributableApp> {
        val filePart = okhttp3.MultipartBody.Part.createFormData(
            "file", fileName, bytes.toRequestBody("application/octet-stream".toMediaTypeOrNull())
        )
        val packageNameBody = meta.packageName.toRequestBody("text/plain".toMediaTypeOrNull())
        val versionCodeBody = meta.versionCode.toString().toRequestBody("text/plain".toMediaTypeOrNull())
        val versionNameBody = meta.versionName.takeIf { it.isNotBlank() }?.toRequestBody("text/plain".toMediaTypeOrNull())
        val appNameBody = meta.appName.takeIf { it.isNotBlank() }?.toRequestBody("text/plain".toMediaTypeOrNull())
        return exec { deviceApi.uploadAppCatalog(deviceId, filePart, packageNameBody, versionCodeBody, versionNameBody, appNameBody) }
            .map { resp ->
                val d = resp.data ?: throw Exception("上传失败：服务端未返回应用信息")
                DistributableApp(
                    id = d.id,
                    packageName = d.packageName,
                    appName = d.appName ?: d.packageName,
                    versionCode = d.versionCode,
                    versionName = d.versionName ?: "",
                    apkUrl = absoluteUrl(d.apkUrl) ?: d.apkUrl,
                    sizeBytes = d.sizeBytes,
                    iconUrl = absoluteUrl(d.iconUrl),
                    description = d.description
                )
            }
    }

    override suspend fun deleteAppCatalog(deviceId: String, catalogId: String): Result<Unit> =
        execRaw { deviceApi.deleteAppCatalog(deviceId, catalogId) }

    // ==================== MonitorRepository ====================

    override suspend fun requestScreenshot(deviceId: String): Result<ScreenshotData> =
        // 服务端只回执"指令已受理"，画面要等孩子端上传后从截屏历史里取。
        // 这里返回一个只带 taskId 的占位对象供上层判成败，真正的图走 getScreenshotHistory。
        exec { monitorApi.requestScreenshot(deviceId) }.map { ack ->
            val d = ack.data ?: throw Exception("截屏请求失败")
            com.padguard.parent.domain.model.ScreenshotData(
                deviceId = d.deviceId,
                imageUrl = null,
                thumbnailUrl = null,
                capturedAt = d.requestedAt ?: System.currentTimeMillis(),
                width = 0,
                height = 0,
                status = d.status ?: "PENDING"
            )
        }

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

    // 拍照 / 录音：服务端只回执任务（TaskAcceptedDto），文件由孩子端上传后另查。
    // 返回值语义改为**任务 ID**（不再是文件 url），调用方据此轮询 getMediaTask。
    override suspend fun takePhoto(deviceId: String): Result<String> =
        exec { monitorApi.takePhoto(deviceId) }.map { it.data?.taskId ?: throw Exception("拍照请求失败") }

    override suspend fun startRecording(deviceId: String): Result<String> =
        exec { monitorApi.startRecording(deviceId) }.map { it.data?.taskId ?: throw Exception("录音请求失败") }

    override suspend fun stopRecording(deviceId: String): Result<String> =
        exec { monitorApi.stopRecording(deviceId) }.map { it.data?.taskId ?: throw Exception("停止录音失败") }

    override suspend fun startScreenRecord(
        deviceId: String,
        resolution: RecordResolution,
        withAudio: Boolean
    ): Result<com.padguard.parent.domain.model.ScreenRecordTask> =
        exec { monitorApi.startScreenRecord(deviceId, StartRecordRequest(resolution.name, withAudio)) }
            .map { ack ->
                val d = ack.data ?: throw Exception("录屏启动失败")
                // 服务端回执里没有分辨率/开始时间，这些以家长端本次选择的为准
                com.padguard.parent.domain.model.ScreenRecordTask(
                    taskId = d.taskId,
                    deviceId = deviceId,
                    startedAt = d.requestedAt ?: System.currentTimeMillis(),
                    resolution = resolution,
                    withAudio = withAudio
                )
            }

    override suspend fun stopScreenRecord(deviceId: String, taskId: String): Result<Unit> =
        exec { monitorApi.stopScreenRecord(deviceId, taskId) }.map { }

    override suspend fun getMediaTask(
        deviceId: String,
        taskId: String
    ): Result<com.padguard.parent.data.model.MediaTaskStatusDto> =
        exec { monitorApi.getMediaTask(deviceId, taskId) }
            .map { dto ->
                val d = dto.data ?: throw Exception("媒体任务不存在")
                // 服务端返回的是相对路径（/v1/files/{id}），这里解析成绝对地址再交给上层：
                // 上层要拿它直接起 Intent 交给系统播放器，相对路径没法用。
                // 占位 baseUrl 的 host 由 HostSelectionInterceptor 改写为真实服务器，与 downloadImage 同一套做法。
                val abs = d.url?.let { retrofit.baseUrl().resolve(it)?.toString() }
                if (abs != null) d.copy(url = abs) else d
            }

    override suspend fun startLiveView(deviceId: String): Result<Unit> = execRaw {
        monitorApi.startLiveView(deviceId)
    }

    override suspend fun stopLiveView(deviceId: String): Result<Unit> = execRaw {
        monitorApi.stopLiveView(deviceId)
    }

    /**
     * 拉实时画面流。
     *
     * 帧格式与服务端约定为 `4 字节大端长度 + JPEG`；长度为 0 是心跳帧（用来保活、
     * 也用来区分"孩子端没画面"和"连接断了"），直接跳过。
     *
     * 读超时必须为 0：这是**一条长连接**，帧与帧之间本来就可能隔几百毫秒，
     * 沿用默认读超时会在正常观看途中被判定超时掐断。
     *
     * **必须摘掉 HttpLoggingInterceptor**：全局 client 配的是 `Level.BODY`，
     * 对普通 JSON 接口没问题，但拉流是**永不结束**的响应体 —— 拦截器会一直把流读进缓冲区
     * 等着凑齐"完整 body"再打印，结果业务代码一帧也拿不到（界面永远停在"连接中"），
     * 还会白白吃掉几百 MB 内存。这是"服务端 200、服务端也有帧，家长端却黑屏"的元凶。
     */
    override fun liveFrames(deviceId: String): Flow<Bitmap> = flow {
        val url = retrofit.baseUrl().resolve("stream/$deviceId/live")
            ?: throw IllegalStateException("非法拉流地址: $deviceId")
        val client = authenticatedClient.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .apply {
                interceptors().removeAll { it is HttpLoggingInterceptor }
                networkInterceptors().removeAll { it is HttpLoggingInterceptor }
            }
            .build()
        // 显式声明接受任意类型：统一拦截器会给所有请求加 `Accept: application/json`，
        // 而拉流返回的是二进制流，服务端一旦按 Accept 做内容协商就会判 406/500。
        // 服务端已改为不声明 produces，这里再兜一道，避免任何一边回退就重现"一直连接中"。
        val call = client.newCall(
            Request.Builder().url(url).get().header("Accept", "*/*").build()
        )
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("拉流失败: HTTP ${resp.code}")
                val source = resp.body?.source() ?: throw IllegalStateException("拉流失败: 空响应")
                while (currentCoroutineContext().isActive) {
                    val len = try {
                        source.readInt()
                    } catch (t: Throwable) {
                        break // 流正常结束（孩子端停止推流 / 家长端关页面）
                    }
                    if (len <= 0) continue
                    if (len > MAX_FRAME_BYTES) break
                    val bytes = source.readByteArray(len.toLong())
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { emit(it) }
                }
            }
        } finally {
            runCatching { call.cancel() }
        }
    }
        // conflate = 背压保护：孩子端按 4fps 推帧，家长端一旦解码或渲染跟不上
        // （低端机、界面切换、整图缩放），无背压的 Flow 会把 Bitmap 一帧不落地全缓存，
        // 表现为内存持续上涨甚至 OOM，且画面越来越延迟（"看到的总是几十秒前"）。
        // conflate 只保留最新一帧：丢帧可以接受，延迟累积不可接受。
        .conflate()
        .flowOn(Dispatchers.IO)

    override suspend fun getScreenMonitorSettings(deviceId: String): Result<ScreenMonitorSettings> =
        exec { monitorApi.getScreenMonitorSettings(deviceId) }.map { it.data?.toDomain() ?: throw Exception("读取监控设置失败") }

    override suspend fun getLiveStatus(deviceId: String): Result<ScreenLiveStatus> =
        exec { monitorApi.getLiveStatus(deviceId) }.map { it.data?.toDomain() ?: throw Exception("读取实时带宽失败") }

    override suspend fun updateScreenMonitorSettings(settings: ScreenMonitorSettings): Result<ScreenMonitorSettings> =
        exec { monitorApi.updateScreenMonitorSettings(settings.deviceId, settings.toDto()) }
            .map { it.data?.toDomain() ?: throw Exception("更新监控设置失败") }

    // ==================== MessageRepository ====================

    override suspend fun publishMessage(request: MessagePublishRequest): Result<PublishedMessage> =
        exec { messageApi.publishMessage(request.deviceId, request.toDto()) }
            .map { it.data?.toDomain() ?: throw Exception("信息发布失败") }

    override suspend fun getPublishedMessages(deviceId: String, limit: Int): Result<List<PublishedMessage>> =
        exec { messageApi.getPublishedMessages(deviceId, limit) }.map { it.data.orEmpty().map { m -> m.toDomain() } }

    override suspend fun uploadMedia(
        fileName: String,
        contentType: String,
        bytes: ByteArray
    ): Result<UploadedMedia> {
        val part = okhttp3.MultipartBody.Part.createFormData(
            "file", fileName, bytes.toRequestBody(contentType.toMediaTypeOrNull())
        )
        return exec { fileApi.upload(part) }.map {
            val dto = it.data ?: throw Exception("素材上传失败：服务端未返回文件信息")
            UploadedMedia(dto.fileId, dto.url, dto.fileName, dto.contentType, dto.size)
        }
    }

    // ==================== LocationRepository ====================

    override suspend fun getDeviceLocation(deviceId: String): Result<LocationInfo> =
        exec { locationApi.getDeviceLocation(deviceId) }.map { it.data?.toDomain() ?: throw Exception("获取位置失败") }

    override suspend fun getGeofences(deviceId: String): Result<List<com.padguard.parent.domain.model.GeofenceConfig>> =
        exec { locationApi.getGeofences(deviceId) }.map { it.data.orEmpty().map { z -> z.toDomain() } }

    override suspend fun createGeofence(zone: com.padguard.parent.domain.model.GeofenceConfig): Result<com.padguard.parent.domain.model.GeofenceConfig> =
        exec { locationApi.createGeofence(zone.deviceId, zone.toDto()) }
            .map { it.data?.toDomain() ?: throw Exception("创建围栏失败") }

    override suspend fun updateGeofence(zone: com.padguard.parent.domain.model.GeofenceConfig): Result<com.padguard.parent.domain.model.GeofenceConfig> =
        exec { locationApi.updateGeofence(zone.deviceId, zone.id, zone.toDto()) }
            .map { it.data?.toDomain() ?: throw Exception("更新围栏失败") }

    override suspend fun deleteGeofence(deviceId: String, id: String): Result<Unit> =
        exec { locationApi.deleteGeofence(deviceId, id) }.map { Unit }

    override suspend fun getTrackHistory(deviceId: String, days: Int, limit: Int): Result<List<LocationInfo>> =
        exec { locationApi.getTrackHistory(deviceId, days, limit) }.map { it.data.orEmpty().map { p -> p.toDomain() } }

    // 电量经设备详情心跳上报的 batteryLevel 字段暴露；取不到时返回 null（页面显示"未知"）。
    override suspend fun getDeviceBattery(deviceId: String): Result<Int?> =
        exec { deviceApi.getDeviceDetail(deviceId) }.map { it.data?.toDomain()?.batteryLevel }

    // 只回"指令已受理"，位置要等孩子端上报后由 getDeviceLocation 拉取。
    // 端点挂在 monitor 前缀下（与服务端 /v1/monitor/{deviceId}/locate 对齐），故走 monitorApi 而非 locationApi。
    override suspend fun requestLocate(deviceId: String): Result<Unit> =
        exec { monitorApi.requestLocate(deviceId) }.map { Unit }

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
            .map { list ->
                list.data.orEmpty().map { dto ->
                    // 台账图标是相对路径，这里统一拼成可加载的绝对地址（见 [absoluteUrl]）
                    dto.toDomain().copy(iconUrl = absoluteUrl(dto.iconUrl))
                }
            }

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

    override suspend fun setAppHidden(deviceId: String, packageName: String, hidden: Boolean): Result<Unit> =
        execRaw { appManageApi.setAppHidden(deviceId, AppHiddenRequest(packageName, hidden)) }

    override suspend fun setAppListPolicy(
        deviceId: String,
        mode: String,
        requirePasswordOnLaunch: Boolean,
        requirePasswordOnInstall: Boolean,
        requirePasswordOnUninstall: Boolean
    ): Result<Unit> = exec {
        policyApi.updateAppListPolicy(
            deviceId,
            AppListPolicyRequest(
                mode,
                requirePasswordOnLaunch,
                requirePasswordOnInstall,
                requirePasswordOnUninstall
            )
        )
    }.map { Unit }

    override suspend fun getAppListPolicy(deviceId: String): Result<AppListPolicyDto> =
        exec { policyApi.getAppListPolicy(deviceId) }
            .map { it.data ?: throw Exception("读取应用管控模式失败") }

    override suspend fun setAppsHiddenBatch(
        deviceId: String,
        packages: List<String>,
        hidden: Boolean,
        all: Boolean
    ): Result<Int> =
        exec { appManageApi.setAppsHiddenBatch(deviceId, AppHiddenBatchRequest(packages, hidden, all)) }
            .map { it.data?.get("sent") ?: 0 }

    override suspend fun setAppLimit(
        deviceId: String,
        packageName: String,
        minutes: Int
    ): Result<Unit> =
        exec { appManageApi.setAppLimit(deviceId, packageName, AppLimitRequest(minutes)) }.map { Unit }

    /**
     * 本地 APK 远程安装：上传 → 拿地址 → 下发 INSTALL_APP。
     *
     * 两步都必须有：服务端只签发"去这个地址下载并安装"的指令，
     * 不会替家长托管安装包。家长手里的 APK 在手机上，孩子端根本访问不到，
     * 必须先落到服务端。
     */
    override suspend fun installLocalApk(
        deviceId: String,
        fileName: String,
        contentType: String,
        bytes: ByteArray,
        packageName: String,
        appName: String?
    ): Result<Unit> {
        val part = okhttp3.MultipartBody.Part.createFormData(
            "file", fileName,
            bytes.toRequestBody(contentType.toMediaTypeOrNull())
        )
        val uploaded = exec { fileApi.upload(part) }
            .map { it.data ?: throw Exception("上传失败：服务端未返回文件信息") }
            .getOrElse { return Result.failure(it) }

        return exec {
            appManageApi.installApp(
                deviceId,
                AppInstallRequest(
                    apkUrl = uploaded.url,
                    packageName = packageName,
                    appName = appName ?: fileName.removeSuffix(".apk")
                )
            )
        }.map { Unit }
    }

    override suspend fun getWebPolicy(deviceId: String): Result<com.padguard.parent.domain.model.WebPolicy> =
        exec { policyApi.getWebPolicy(deviceId) }.map { it.data?.toDomain() ?: throw Exception("读取上网策略失败") }

    override suspend fun updateUrlBlacklist(deviceId: String, urls: List<String>): Result<Unit> =
        exec { policyApi.updateUrlBlacklist(deviceId, UrlBlacklistRequest(urls)) }.map { Unit }

    override suspend fun setBrowserDisabled(deviceId: String, disabled: Boolean): Result<Unit> =
        exec { policyApi.setBrowserDisabled(deviceId, BrowserDisableRequest(disabled)) }.map { Unit }

    override suspend fun lockScreen(deviceId: String): Result<CommandResult> =
        exec { policyApi.lockScreen(deviceId) }
            .map { it.data?.toDomain() ?: throw Exception("锁屏指令未返回指令号，无法追踪执行结果") }

    override suspend fun unlockScreen(deviceId: String): Result<CommandResult> =
        exec { policyApi.unlockScreen(deviceId) }
            .map { it.data?.toDomain() ?: throw Exception("解锁指令未返回指令号，无法追踪执行结果") }

    override suspend fun getCommand(msgId: String): Result<CommandResult> =
        exec { policyApi.getCommand(msgId) }
            .map { it.data?.toDomain() ?: throw Exception("查询指令结果失败") }

    /**
     * 水印开关。
     *
     * ## 这里必须说清一件事：我们关不掉那个红点
     * 家长常提的诉求是"录屏时别让孩子看到红点"。那个红色 REC 指示器由
     * **Android 系统**在 MediaProjection 会话期间直接绘制（Android 12+ 起是常驻胶囊，
     * 更早是状态栏红点），它不进入应用画面、也不受应用窗口层级影响。
     * 这是 Android 的隐私设计红线：任何应用 —— 包括 Device Owner、系统签名应用 ——
     * 都没有关闭它的接口。市面上声称能去掉的方案，要么需要 root 改框架，
     * 要么只能隐藏自己应用内的提示，对系统指示器一律无效。
     *
     * 因此这里提供的是**本应用自己的水印**：叠加一个半透明悬浮文字，
     * 家长可自定义文案、可随时关闭。它不"去掉红点"，
     * 但给了家长一个可控、且孩子能看见的受管控提示。
     */
    override suspend fun setWatermark(
        deviceId: String,
        enabled: Boolean,
        content: String?
    ): Result<CommandResult> =
        exec { policyApi.setWatermark(deviceId, WatermarkRequestDto(enabled, content)) }
            .map { it.data?.toDomain() ?: throw Exception("水印指令未返回指令号，无法追踪执行结果") }

    override suspend fun setGuardPassword(
        deviceId: String,
        password: String?,
        clear: Boolean
    ): Result<Unit> =
        exec { policyApi.setGuardPassword(deviceId, GuardPasswordRequestDto(password, clear)) }
            .map { }

    override suspend fun setEyeProtection(deviceId: String, enabled: Boolean, filterLevel: Int): Result<Unit> =
        exec { policyApi.setEyeProtection(deviceId, EyeProtectionRequest(enabled, filterLevel)) }.map { Unit }

    override suspend fun unlock(deviceId: String): Result<Unit> =
        execRaw { policyApi.unlock(deviceId) }

    override suspend fun tempUnlock(deviceId: String, durationMinutes: Int): Result<Unit> =
        execRaw {
            policyApi.tempUnlock(
                deviceId,
                com.padguard.parent.data.model.TempUnlockRequest(durationMinutes = durationMinutes)
            )
        }

    override suspend fun getUnlockTickets(deviceId: String): Result<List<com.padguard.parent.domain.repository.UnlockTicket>> =
        exec { policyApi.getUnlockTickets(deviceId) }.map { list ->
            list.data.orEmpty().map { dto ->
                com.padguard.parent.domain.repository.UnlockTicket(
                    id = dto.id, deviceId = dto.deviceId, packageName = dto.packageName,
                    appLabel = dto.appLabel, durationMinutes = dto.durationMinutes,
                    reason = dto.reason, status = dto.status, createdAt = dto.createdAt
                )
            }
        }

    override suspend fun approveUnlockTicket(
        deviceId: String, ticketId: String, durationMinutes: Int?
    ): Result<Unit> =
        execRaw {
            policyApi.approveUnlockTicket(
                deviceId, ticketId,
                com.padguard.parent.data.model.UnlockApproveRequest(durationMinutes = durationMinutes)
            )
        }

    override suspend fun ignoreUnlockTicket(deviceId: String, ticketId: String): Result<Unit> =
        execRaw {
            policyApi.rejectUnlockTicket(
                deviceId, ticketId, com.padguard.parent.data.model.UnlockRejectRequest(reason = "家长已忽略")
            )
        }

    override suspend fun dismissUnlockTicket(deviceId: String, ticketId: String): Result<Unit> =
        execRaw { policyApi.dismissUnlockTicket(deviceId, ticketId) }

    override suspend fun setControlMode(deviceId: String, mode: ControlMode): Result<Unit> =
        execRaw { policyApi.setControlMode(deviceId, com.padguard.parent.data.model.ModeChangeRequest(mode.name)) }

    override suspend fun getPolicyTemplates(sceneType: SceneType): Result<List<com.padguard.parent.domain.repository.PolicyTemplate>> =
        exec { policyApi.getTemplates(sceneType.name) }.map { it.data.orEmpty().map { t -> t.toDomain() } }

    override suspend fun applyPolicyTemplate(deviceId: String, templateId: String): Result<Unit> =
        exec { policyApi.applyTemplate(deviceId, com.padguard.parent.data.model.ApplyTemplateRequest(templateId)) }.map { Unit }

    override suspend fun getTabletUsageSettings(deviceId: String): Result<TabletUsageSettings> =
        exec { policyApi.getTabletUsageSettings(deviceId) }
            .map { resp -> resp.data?.toDomain() ?: throw Exception("读取时间管控设置失败") }

    override suspend fun updateTabletUsageSettings(settings: TabletUsageSettings): Result<TabletUsageSettings> =
        exec { policyApi.updateTabletUsageSettings(settings.deviceId, settings.toDto()) }
            .map { resp -> resp.data?.toDomain() ?: throw Exception("保存时间管控设置失败") }

    /**
     * 平板使用计时会话：拉取服务端锚定的剩余时长（三端一致来源）。
     * 返回 null data 视为读取失败，让上层给出可执行的提示。
     */
    override suspend fun getUsageSession(deviceId: String): Result<UsageSession> =
        exec { policyApi.getUsageSession(deviceId) }
            .map { resp -> resp.data?.toDomain() ?: throw Exception("读取使用会话失败") }

    override suspend fun getMinorMode(deviceId: String): Result<MinorMode> =
        exec { policyApi.getMinorMode(deviceId) }
            .map { resp -> resp.data?.toDomain(deviceId) ?: throw Exception("读取未成年人模式失败") }

    override suspend fun setMinorMode(mode: MinorMode, exemptMinutes: Int?): Result<Unit> =
        execRaw { policyApi.setMinorMode(mode.deviceId, mode.toRequest(exemptMinutes)) }

    /**
     * 分龄合规默认值。
     *
     * 走 [StaticConfigCache]：这是服务端常量级配置，却被首页 10 秒一次的轮询反复拉取，
     * 是家长端"请求风暴"的主要来源。缓存后该接口在 App 生命周期内基本只发一次。
     */
    override suspend fun getAgeBandDefaults(): Result<Map<AgeBand, AgeBandDefault>> =
        staticConfigCache.ageBandDefaults {
            exec { policyApi.getAgeBandDefaults() }.map { resp ->
                resp.data.orEmpty().mapNotNull { (key, v) ->
                    // 未知档位直接丢弃而不是回落：默认值要如实反映服务端能力，
                    // 回落会让家长看到"某个档位每天 60 分钟"但服务端其实没这个档。
                    val band = AgeBand.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
                        ?: return@mapNotNull null
                    band to v.toDomain()
                }.toMap()
            }
        }

    // ==================== StatisticsRepository ====================

    override suspend fun getUsageStats(deviceId: String, period: ReportPeriod): Result<StatisticsReport> =
        exec { statisticsApi.getUsageStats(deviceId, period.name.lowercase()) }
            .map { it.data?.toDomain() ?: throw Exception("获取统计失败") }

    override suspend fun getTodayUsage(deviceId: String): Result<UsageStats> =
        exec { statisticsApi.getTodayUsage(deviceId) }
            .map { (it.data?.toDomain() ?: throw Exception("获取今日使用失败")).withAbsoluteIcons() }

    /** 用量条目里的图标同样是相对路径，统一补成绝对地址（见 [absoluteUrl]） */
    private fun UsageStats.withAbsoluteIcons(): UsageStats =
        copy(appUsages = appUsages.map { it.copy(iconUrl = absoluteUrl(it.iconUrl)) })

    override suspend fun getAppUsageHistory(
        deviceId: String,
        packageName: String,
        period: ReportPeriod
    ): Result<List<com.padguard.parent.domain.model.AppUsageHistoryEntry>> =
        exec { statisticsApi.getAppUsageHistory(deviceId, packageName, period.name.lowercase()) }
            .map { it.data.orEmpty().map { e -> e.toDomain() } }

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
        exec { alertApi.getUnreadCount() }.map { it.data?.count ?: 0 }

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

    /**
     * 把服务端返回的相对图片地址拼成可加载的绝对地址。
     *
     * 服务端存的是 `/v1/files/<id>` 这种不带 host 的相对路径（同一份数据可能在
     * 家庭电脑、云服务器之间迁移，存绝对路径必然失效）；
     * 但图片加载器（Coil）只认完整 URL，不拼就是一片空白。
     * 这里在**取数时**拼接而不是落库：服务器地址是运行时可改的（换网络/换电脑都变），
     * 写死在任何一层都会导致改完地址后图标集体裂开。
     */
    private fun absoluteUrl(path: String?): String? {
        val p = path?.takeIf { it.isNotBlank() } ?: return null
        if (p.startsWith("http://", ignoreCase = true) || p.startsWith("https://", ignoreCase = true)) return p
        val base = serverPrefs.getBaseUrl().trim().trimEnd('/')
        return withoutOverlap(base, p) + if (p.startsWith("/")) p else "/$p"
    }

    /**
     * 去掉服务器地址末尾与资源路径开头重复的路径段。
     *
     * 服务端返回的文件地址（`/v1/files/{id}`）已经是**服务端根路径**，
     * 而家长端填的服务器地址往往同样带着 `/v1` 这层接口前缀。
     * 直接拼接会得到 `/v1/v1/files/{id}` → 500，
     * 表现为"图标 / 截图明明上传成功了，家长端却永远加载不出来"。
     * 这里按段去重，服务器地址带不带接口前缀都能拼对。
     */
    private fun withoutOverlap(base: String, path: String): String {
        var b = base.trimEnd('/')
        val normalized = if (path.startsWith("/")) path else "/$path"
        val firstSeg = normalized.trimStart('/').substringBefore('/')
        if (firstSeg.isEmpty()) return b
        val suffix = "/$firstSeg"
        // 保护条件：削完之后必须仍是合法 URL，避免把 "http://v1" 这类地址削坏
        while (b.endsWith(suffix) && b.removeSuffix(suffix).contains("://")) {
            b = b.removeSuffix(suffix)
        }
        return b
    }

    companion object {
        /** 单帧大小上限（5MB）：异常大帧只可能是协议错乱，直接断流重连而不是 OOM */
        private const val MAX_FRAME_BYTES = 5 * 1024 * 1024

        private const val POLL_INTERVAL_MS = 10_000L
        private const val ALERT_POLL_INTERVAL_MS = 15_000L
    }

    // ------------------------------------------------------------------ 订阅与计费

    override suspend fun getPlans(): Result<List<PlanDto>> =
        exec { billingApi.getPlans() }.mapCatching { it.data.orEmpty() }

    override suspend fun getSubscription(): Result<SubscriptionDto> =
        exec { billingApi.getSubscription() }.mapCatching {
            it.data ?: throw Exception("服务端未返回订阅状态")
        }

    override suspend fun createOrder(plan: String, channel: String): Result<CreateOrderResponse> =
        exec { billingApi.createOrder(CreateOrderRequest(plan = plan, channel = channel)) }
            .mapCatching { it.data ?: throw Exception("下单失败：服务端未返回订单") }

    override suspend fun getOrders(limit: Int): Result<List<OrderDto>> =
        exec { billingApi.getOrders(limit) }.mapCatching { it.data.orEmpty() }

    override suspend fun getChannels(): Result<List<PayChannelDto>> =
        exec { billingApi.getChannels() }.mapCatching { it.data.orEmpty() }

    override suspend fun mockPay(orderNo: String): Result<SubscriptionDto> =
        exec { billingApi.mockPay(orderNo) }.mapCatching {
            it.data ?: throw Exception("支付失败：服务端未返回订阅状态")
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
    controlMode = safeEnum(controlMode) { ControlMode.NORMAL },
    sceneMode = safeEnum(sceneMode) { ControlMode.NORMAL }, groupId = groupId, groupName = groupName,
    sceneType = safeEnum(sceneType) { SceneType.FAMILY }, latitude = latitude, longitude = longitude,
    remoteLocked = remoteLocked ?: false,
    childName = childName,
    childNickname = childNickname,
    childAvatar = childAvatar,
    guardPasswordSet = guardPasswordSet ?: false
)

private fun DeviceGroupDto.toDomain(): DeviceGroup = DeviceGroup(
    id = id, name = name, sceneType = safeEnum(sceneType) { SceneType.FAMILY }, deviceCount = deviceCount
)

private fun ScreenshotDto.toDomain(): ScreenshotData = ScreenshotData(
    deviceId = deviceId, imageUrl = imageUrl, thumbnailUrl = thumbnailUrl, status = status,
    capturedAt = capturedAt ?: 0, width = width ?: 0, height = height ?: 0
)

private fun com.padguard.parent.data.model.ScreenRecordTaskDto.toDomain(): com.padguard.parent.domain.model.ScreenRecordTask =
    com.padguard.parent.domain.model.ScreenRecordTask(
        taskId = taskId, deviceId = deviceId, startedAt = startedAt,
        resolution = safeEnum(resolution) { RecordResolution.HD_720P }, withAudio = withAudio
    )

private fun ScreenMonitorSettingsDto.toDomain(): ScreenMonitorSettings = ScreenMonitorSettings(
    deviceId = deviceId, autoRefreshSeconds = autoRefreshSeconds, highDefinition = highDefinition,
    recordResolution = safeEnum(recordResolution) { RecordResolution.HD_720P },
    recordWithAudio = recordWithAudio, allowRemoteLock = allowRemoteLock
)

private fun ScreenStatusDto.toDomain(): ScreenLiveStatus = ScreenLiveStatus(
    deviceId = deviceId, live = live, frameAgeMs = frameAgeMs,
    upstreamBps = upstreamBps, downstreamBps = downstreamBps, totalBytes = totalBytes
)

private fun ScreenMonitorSettings.toDto(): ScreenMonitorSettingsDto = ScreenMonitorSettingsDto(
    deviceId = deviceId, autoRefreshSeconds = autoRefreshSeconds, highDefinition = highDefinition,
    recordResolution = recordResolution.name, recordWithAudio = recordWithAudio, allowRemoteLock = allowRemoteLock
)

private fun LocationDto.toDomain(): LocationInfo = LocationInfo(
    latitude = latitude, longitude = longitude, address = address, accuracy = accuracy, timestamp = timestamp, fresh = fresh
)

private fun GeofenceZoneDto.toDomain(): GeofenceConfig = GeofenceConfig(
    id = id ?: "", deviceId = deviceId, enabled = enabled, name = name ?: "安全区域",
    centerLatitude = centerLatitude, centerLongitude = centerLongitude, radiusMeters = radiusMeters,
    alertOnExit = alertOnExit, alertOnEnter = alertOnEnter
)

private fun GeofenceConfig.toDto(): GeofenceZoneDto = GeofenceZoneDto(
    id = id.ifEmpty { null }, deviceId = deviceId, enabled = enabled, name = name,
    centerLatitude = centerLatitude, centerLongitude = centerLongitude, radiusMeters = radiusMeters,
    alertOnExit = alertOnExit, alertOnEnter = alertOnEnter
)

private fun TimeRestrictionDto.toDomain(): TimeRestriction = TimeRestriction(
    id = id ?: "", deviceId = deviceId ?: "", dayOfWeek = dayOfWeek, startTime = startTime, endTime = endTime,
    maxMinutes = maxMinutes, isEnabled = isEnabled
)

private fun TimeRestriction.toDto(): TimeRestrictionDto = TimeRestrictionDto(
    id = id, deviceId = deviceId, dayOfWeek = dayOfWeek, startTime = startTime, endTime = endTime,
    maxMinutes = maxMinutes, isEnabled = isEnabled
)

private fun TabletUsageSettingsDto.toDomain(): TabletUsageSettings = TabletUsageSettings(
    deviceId = deviceId,
    enabled = enabled,
    enabledTimeRanges = enabledTimeRanges?.map { TimeRange(it.startTime, it.endTime) }
        ?: listOf(TimeRange("08:00", "18:00")),
    weekdayLimitMinutes = weekdayLimitMinutes,
    weekendLimitMinutes = weekendLimitMinutes,
    restAfterMinutes = restAfterMinutes,
    restDurationMinutes = restDurationMinutes,
    // 回读时不再带 syncToDevice（那是下发瞬间的动作标志），保持页面状态干净
    syncToDevice = false
)

private fun CommandResultDto.toDomain(): CommandResult = CommandResult(
    msgId = msgId,
    type = type,
    status = status,
    createdAt = createdAt,
    executedAt = executedAt,
    errorCode = errorCode,
    errorMessage = errorMessage
)

private fun MinorModeDto.toDomain(deviceId: String): MinorMode = MinorMode(
    deviceId = deviceId,
    enabled = enabled,
    ageBand = AgeBand.fromKey(ageBand),
    curfewEnabled = curfewEnabled,
    // 服务端在缺省时也会把档位默认值补上，这里只对异常空串做兜底
    curfewStart = curfewStart?.takeIf { it.isNotBlank() } ?: "22:00",
    curfewEnd = curfewEnd?.takeIf { it.isNotBlank() } ?: "06:00",
    dailyLimitMinutes = dailyLimitMinutes,
    weekendLimitMinutes = weekendLimitMinutes,
    continuousMinutes = continuousMinutes,
    restMinutes = restMinutes,
    parentExemptUntil = parentExemptUntil
)

/**
 * 转请求体。
 *
 * 只下发家长**显式设置过**的字段（非 0），其余留空让服务端按档位默认值补齐 ——
 * 这样"切档"时旧档位的自定义时长不会跟着带过去，
 * 表现为"从 12–16 岁切到 8–12 岁，时长却还是 2 小时"。
 */
private fun MinorMode.toRequest(exemptMinutes: Int?): MinorModeRequestDto = MinorModeRequestDto(
    enabled = enabled,
    ageBand = ageBand.key,
    curfewEnabled = curfewEnabled,
    curfewStart = curfewStart,
    curfewEnd = curfewEnd,
    dailyLimitMinutes = dailyLimitMinutes.takeIf { it > 0 },
    weekendLimitMinutes = weekendLimitMinutes.takeIf { it > 0 },
    continuousMinutes = continuousMinutes.takeIf { it > 0 },
    restMinutes = restMinutes.takeIf { it > 0 },
    exemptMinutes = exemptMinutes?.takeIf { it > 0 }
)

private fun AgeBandDefaultDto.toDomain(): AgeBandDefault = AgeBandDefault(
    dailyLimitMinutes = dailyLimitMinutes,
    continuousMinutes = continuousMinutes,
    restMinutes = restMinutes,
    curfewStart = curfewStart,
    curfewEnd = curfewEnd
)

private fun TabletUsageSettings.toDto(): TabletUsageSettingsDto = TabletUsageSettingsDto(
    deviceId = deviceId,
    enabled = enabled,
    enabledTimeRanges = enabledTimeRanges.map { TimeRangeDto(it.startTime, it.endTime) },
    weekdayLimitMinutes = weekdayLimitMinutes,
    weekendLimitMinutes = weekendLimitMinutes,
    restAfterMinutes = restAfterMinutes,
    restDurationMinutes = restDurationMinutes,
    syncToDevice = true
)

private fun UsageSessionDto.toDomain(): UsageSession = UsageSession(
    deviceId = deviceId,
    serverNow = serverNow,
    active = active,
    dayKey = dayKey,
    startEpoch = startEpoch,
    quotaMinutes = quotaMinutes,
    extendedMinutes = extendedMinutes,
    remainingMinutes = remainingMinutes,
    remainingMs = remainingMs,
    countdownThresholdMs = countdownThresholdMs,
    delayOptions = delayOptions,
    maxDailyExtendMinutes = maxDailyExtendMinutes,
    lastDelayEpoch = lastDelayEpoch
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
    dailyLimitMinutes = dailyLimitMinutes,
    iconUrl = iconUrl,
    hidden = hidden
)

private fun AppPolicyDto.toDomain(): AppPolicy = AppPolicy(
    id = id ?: java.util.UUID.randomUUID().toString(), deviceId = deviceId ?: "", packageName = packageName,
    appName = appName ?: packageName, isBlocked = isBlocked, dailyLimitMinutes = dailyLimitMinutes, iconUrl = null
)

private fun WebPolicyDto.toDomain(): com.padguard.parent.domain.model.WebPolicy = com.padguard.parent.domain.model.WebPolicy(
    deviceId = deviceId, blockedUrls = blockedUrls, browserDisabled = browserDisabled,
    smartShutdownEnabled = smartShutdownEnabled, smartShutdownStartTime = smartShutdownStartTime,
    smartShutdownEndTime = smartShutdownEndTime
)

private fun LocationTrackPointDto.toDomain(): LocationInfo = LocationInfo(
    latitude = latitude, longitude = longitude, address = address,
    accuracy = accuracy, timestamp = timestamp, fresh = fresh
)

private fun PolicyTemplateDto.toDomain(): com.padguard.parent.domain.repository.PolicyTemplate =
    com.padguard.parent.domain.repository.PolicyTemplate(
        id = id, name = name, description = description ?: "", sceneType = safeEnum(sceneType) { SceneType.FAMILY },
        category = category ?: "通用"
    )

private fun UsageStatsDto.toDomain(): UsageStats = UsageStats(
    deviceId = deviceId, date = date, totalUsageMinutes = totalUsageMinutes,
    appUsages = appUsages.orEmpty().map { it.toDomain() }
)

private fun AppUsageDto.toDomain(): AppUsage = AppUsage(
    packageName = packageName,
    appName = appName,
    usageMinutes = usageMinutes,
    iconUrl = iconUrl,
    // 起止时间：服务端按天聚合出的真实区间（毫秒），转成界面直接可显示的 HH:mm:ss
    startTime = startAt?.takeIf { it > 0 }?.let { clockOf(it) },
    endTime = endAt?.takeIf { it > 0 }?.let { clockOf(it) },
    startAt = startAt,
    endAt = endAt,
    launchCount = launchCount
)

private fun clockOf(millis: Long): String =
    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA)
        .apply { timeZone = com.padguard.parent.util.DISPLAY_TIME_ZONE }
        .format(java.util.Date(millis))

private fun DailyUsageDto.toDomain(): DailyUsage = DailyUsage(date = date, usageMinutes = usageMinutes, violationCount = violationCount)

private fun StatisticsReportDto.toDomain(): StatisticsReport = StatisticsReport(
    deviceId = deviceId, period = safeEnum(period) { ReportPeriod.DAILY }, startDate = startDate, endDate = endDate,
    totalUsageMinutes = totalUsageMinutes, dailyUsages = dailyUsages.orEmpty().map { it.toDomain() },
    topApps = topApps.orEmpty().map { it.toDomain() }, violationCount = violationCount, alertCount = alertCount
)

private fun WebActivityStatsDto.toDomain(): WebActivityStats = WebActivityStats(
    totalVisits = totalVisits, topDomains = topDomains.orEmpty().map { it.toDomain() }, blockedAttempts = blockedAttempts
)

private fun DomainVisitDto.toDomain(): com.padguard.parent.domain.repository.DomainVisit =
    com.padguard.parent.domain.repository.DomainVisit(domain = domain, visitCount = visitCount, lastVisitTime = lastVisitTime)

private fun ViolationStatsDto.toDomain(): ViolationStats = ViolationStats(
    totalCount = totalCount, byCategory = byCategory ?: emptyMap(),
    trend = trend.orEmpty().map { com.padguard.parent.domain.repository.DailyViolation(it.date, it.count) }
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
    triggeredAt = triggeredAt, acknowledgedAt = acknowledgedAt, resolvedAt = resolvedAt,
    // 保留服务端原始事件类型，见 Alert.categoryRaw 的注释
    categoryRaw = category
)

private inline fun <reified T : Enum<T>> safeEnum(value: String?, default: () -> T): T =
    if (value.isNullOrBlank()) default() else runCatching { enumValueOf<T>(value) }.getOrDefault(default())
