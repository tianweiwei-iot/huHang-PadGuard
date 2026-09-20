package com.padguard.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.padguard.domain.model.*
import com.padguard.domain.repository.*
import com.padguard.domain.KnownAppIcons
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "padguard_prefs")

/**
 * 本地数据源
 * 
 * 当前阶段：使用 Mock 数据驱动前端开发
 * 后续对接真实后端 API 时，替换为 ApiDataSource 实现
 *
 * 参考实现：
 * - MDMesh Agent: 使用 DataStore 做本地配置缓存
 * - SecureGuard/A-Bloq: Room + DataStore 双存储策略
 */
@Singleton
class LocalDataSource @Inject constructor(
    @ApplicationContext private val context: Context
) : 
    com.padguard.domain.repository.AuthRepository,
    com.padguard.domain.repository.DeviceRepository,
    com.padguard.domain.repository.MonitorRepository,
    com.padguard.domain.repository.PolicyRepository,
    com.padguard.domain.repository.StatisticsRepository,
    com.padguard.domain.repository.AlertRepository,
    com.padguard.domain.repository.MessageRepository,
    com.padguard.domain.repository.LocationRepository {

    companion object {
        private val KEY_TOKEN = stringPreferencesKey("auth_token")
        private val KEY_REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        private val KEY_USER_ID = stringPreferencesKey("user_id")
        private val KEY_USER_PHONE = stringPreferencesKey("user_phone")
        private val KEY_USER_ROLE = stringPreferencesKey("user_role")
        private val KEY_SCENE_TYPE = stringPreferencesKey("scene_type")

        /** Mock 阶段每日总时长默认上限（分钟）。真实后端接入后由服务端返回。 */
        private const val DEFAULT_DAILY_LIMIT_MINUTES = 120
    }

    // ==================== Mock 数据工厂 ====================

    // 设备台账内存存储：增删改查均作用于此，保证 CRUD 在 Mock 阶段真实生效
    private val deviceStore = linkedMapOf(
        "dev_001" to Device(
            id = "dev_001",
            name = "小明的平板",
            deviceId = "PAD_XIAOMI_001",
            model = "Xiaomi Pad 6",
            osVersion = "14",
            appVersion = "1.0.0",
            onlineStatus = DeviceOnlineStatus.ONLINE,
            lastOnlineTime = System.currentTimeMillis(),
            batteryLevel = 85,
            controlMode = ControlMode.NORMAL,
            groupId = "group_001",
            groupName = "家庭设备",
            sceneType = SceneType.FAMILY,
            latitude = 22.5431,
            longitude = 114.0579
        ),
        "dev_002" to Device(
            id = "dev_002",
            name = "教室平板-A03",
            deviceId = "PAD_HUAWEI_A03",
            model = "Huawei MatePad Pro",
            osVersion = "13",
            appVersion = "1.0.0",
            onlineStatus = DeviceOnlineStatus.OFFLINE,
            lastOnlineTime = System.currentTimeMillis() - 3600000,
            batteryLevel = null,
            controlMode = ControlMode.LEARNING,
            groupId = "group_002",
            groupName = "三年级二班",
            sceneType = SceneType.SCHOOL,
            latitude = null,
            longitude = null
        )
    )

    /** 设备列表实时流（bind/unbind/rename/import 后向所有观察者推送） */
    private val deviceListFlow = MutableStateFlow<List<Device>>(deviceStore.values.toList())

    private fun refreshDeviceListFlow() {
        deviceListFlow.value = deviceStore.values.toList()
    }

    // 待绑定的局域网设备（Mock，模拟 mDNS/UDP 扫描结果）
    private val mockLanDevices = listOf(
        LanDevice("PAD_LENOVO_L01", "Lenovo 小新 Pad", "192.168.1.105", "13"),
        LanDevice("PAD_SEEWO_S22", "希沃学习平板 S22", "192.168.1.108", "12")
    )

    private fun createMockUser(phone: String): User = User(
        id = "user_001",
        phone = phone,
        nickname = "家长用户",
        avatar = null,
        role = UserRole.PARENT,
        sceneType = SceneType.FAMILY,
        token = "mock_token_${System.currentTimeMillis()}"
    )

    // ==================== AuthRepository 实现 ====================

    override suspend fun loginWithPassword(phone: String, password: String): Result<User> {
        // Mock: 任意手机号+密码即可登录
        val user = createMockUser(phone)
        saveUserToDataStore(user)
        return Result.success(user)
    }

    override suspend fun loginWithSmsCode(phone: String, code: String): Result<User> {
        // Mock: 任意6位验证码即可
        if (code.length != 6) {
            return Result.failure(IllegalArgumentException("验证码格式错误"))
        }
        val user = createMockUser(phone)
        saveUserToDataStore(user)
        return Result.success(user)
    }

    override suspend fun sendSmsCode(phone: String): Result<Unit> {
        // Mock: 直接成功
        if (phone.length < 11) {
            return Result.failure(IllegalArgumentException("手机号格式错误"))
        }
        return Result.success(Unit)
    }

    override suspend fun logout(): Result<Unit> {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_TOKEN)
            prefs.remove(KEY_REFRESH_TOKEN)
            prefs.remove(KEY_USER_ID)
            prefs.remove(KEY_USER_PHONE)
            prefs.remove(KEY_USER_ROLE)
            prefs.remove(KEY_SCENE_TYPE)
        }
        return Result.success(Unit)
    }

    override suspend fun refreshToken(): Result<User> {
        // Mock: 返回当前缓存的用户
        val user = getCurrentUserFlow().first() ?: return Result.failure(Exception("未登录"))
        return Result.success(user.copy(token = "refreshed_token_${System.currentTimeMillis()}"))
    }

    override fun getCurrentUserFlow(): Flow<User?> = context.dataStore.data.map { prefs ->
        val userId = prefs[KEY_USER_ID] ?: return@map null
        User(
            id = userId,
            phone = prefs[KEY_USER_PHONE] ?: "",
            nickname = null,
            avatar = null,
            role = try { UserRole.valueOf(prefs[KEY_USER_ROLE] ?: "PARENT") } catch (e: Exception) { UserRole.PARENT },
            sceneType = try { SceneType.valueOf(prefs[KEY_SCENE_TYPE] ?: "FAMILY") } catch (e: Exception) { SceneType.FAMILY },
            token = prefs[KEY_TOKEN]
        )
    }

    override suspend fun isLoggedIn(): Boolean {
        return context.dataStore.data.first()[KEY_TOKEN] != null
    }

    private suspend fun saveUserToDataStore(user: User) {
        context.dataStore.edit { prefs ->
            prefs[KEY_TOKEN] = user.token ?: ""
            prefs[KEY_USER_ID] = user.id
            prefs[KEY_USER_PHONE] = user.phone
            prefs[KEY_USER_ROLE] = user.role.name
            prefs[KEY_SCENE_TYPE] = user.sceneType.name
        }
    }

    // ==================== DeviceRepository 实现 ====================

    override suspend fun getDeviceList(): Result<List<Device>> =
        Result.success(deviceStore.values.toList())

    override suspend fun getDeviceDetail(deviceId: String): Result<Device> {
        val device = deviceStore[deviceId]
            ?: return Result.failure(Exception("设备不存在"))
        return Result.success(device)
    }

    private fun newBoundDevice(
        hardwareId: String,
        alias: String?,
        model: String?,
        groupName: String?
    ): Device {
        // UUID 避免批量导入在同一毫秒内产生主键冲突
        val id = "dev_${java.util.UUID.randomUUID()}"
        return Device(
            id = id,
            name = alias ?: "平板-$hardwareId",
            deviceId = hardwareId,
            model = model ?: "Unknown",
            osVersion = "13",
            appVersion = "1.0.0",
            onlineStatus = DeviceOnlineStatus.ONLINE,
            lastOnlineTime = System.currentTimeMillis(),
            batteryLevel = 100,
            controlMode = ControlMode.NORMAL,
            groupId = null,
            groupName = groupName,
            sceneType = SceneType.FAMILY,
            latitude = null,
            longitude = null
        ).also {
            deviceStore[id] = it
            refreshDeviceListFlow()
        }
    }

    private fun hardwareIdExists(hardwareId: String): Boolean =
        deviceStore.values.any { it.deviceId == hardwareId }

    override suspend fun bindDevice(deviceId: String, alias: String?): Result<Device> {
        if (hardwareIdExists(deviceId)) {
            return Result.failure(IllegalStateException("设备 $deviceId 已接入"))
        }
        return Result.success(newBoundDevice(deviceId, alias, null, null))
    }

    override suspend fun unbindDevice(deviceId: String): Result<Unit> {
        deviceStore.remove(deviceId)
        refreshDeviceListFlow()
        return Result.success(Unit)
    }

    override suspend fun renameDevice(deviceId: String, newName: String): Result<Unit> {
        val device = deviceStore[deviceId]
            ?: return Result.failure(Exception("设备不存在"))
        deviceStore[deviceId] = device.copy(name = newName)
        refreshDeviceListFlow()
        return Result.success(Unit)
    }

    override suspend fun getDeviceGroups(): Result<List<DeviceGroup>> = Result.success(
        listOf(
            DeviceGroup("group_001", "家庭设备", SceneType.FAMILY, 1),
            DeviceGroup("group_002", "三年级二班", SceneType.SCHOOL, 25)
        )
    )

    override suspend fun createGroup(name: String, sceneType: SceneType): Result<DeviceGroup> =
        Result.success(DeviceGroup("group_new", name, sceneType, 0))

    override suspend fun assignDeviceToGroup(deviceId: String, groupId: String): Result<Unit> =
        Result.success(Unit)

    override fun observeDeviceList(): Flow<List<Device>> = deviceListFlow

    // ==================== DeviceRepository：设备接入（Mock） ====================

    override suspend fun generateBindQrPayload(): Result<String> {
        val token = (100000..999999).random().toString()
        return Result.success("$BIND_PAYLOAD_SCHEME?token=$token&t=${System.currentTimeMillis()}")
    }

    override suspend fun bindByQrPayload(payload: String, alias: String?): Result<Device> {
        val hardwareId = parseBindPayload(payload)
            ?: return Result.failure(IllegalArgumentException("二维码无效，请扫描被控平板展示的接入码"))
        if (hardwareIdExists(hardwareId)) {
            return Result.failure(IllegalStateException("设备 $hardwareId 已接入"))
        }
        return Result.success(newBoundDevice(hardwareId, alias, null, null))
    }

    /** 解析 padguard://bind?deviceId=...&token=... 载荷，返回硬件设备 ID */
    private fun parseBindPayload(payload: String): String? {
        if (!payload.startsWith(BIND_PAYLOAD_SCHEME)) return null
        val query = payload.substringAfter("?", "")
        val params = query.split("&").mapNotNull { pair ->
            val kv = pair.split("=", limit = 2)
            if (kv.size == 2) kv[0] to kv[1] else null
        }.toMap()
        return params["deviceId"]?.takeIf { it.isNotBlank() }
            // 家长端自生成的待扫码载荷只有 token，此时用 Mock 硬件 ID 模拟被控端回报
            ?: params["token"]?.let { "PAD_SCAN_$it" }
    }

    override suspend fun generatePairingCode(): Result<String> =
        Result.success((100000..999999).random().toString())

    override suspend fun bindByPairingCode(code: String, alias: String?): Result<Device> {
        if (!code.matches(Regex("\\d{6}"))) {
            return Result.failure(IllegalArgumentException("配对码为 6 位数字，请重新输入"))
        }
        val hardwareId = "PAD_PAIR_$code"
        if (hardwareIdExists(hardwareId)) {
            return Result.failure(IllegalStateException("该配对码对应设备已接入"))
        }
        return Result.success(newBoundDevice(hardwareId, alias, null, null))
    }

    override suspend fun discoverLanDevices(): Result<List<LanDevice>> =
        Result.success(mockLanDevices.filter { !hardwareIdExists(it.deviceId) })

    override suspend fun bindLanDevice(lanDevice: LanDevice, alias: String?): Result<Device> {
        if (hardwareIdExists(lanDevice.deviceId)) {
            return Result.failure(IllegalStateException("设备 ${lanDevice.deviceId} 已接入"))
        }
        return Result.success(newBoundDevice(lanDevice.deviceId, alias, lanDevice.model, null))
    }

    override suspend fun importDevices(rows: List<DeviceImportRow>): Result<DeviceImportResult> {
        var success = 0
        var skipped = 0
        val failedRows = mutableListOf<String>()
        rows.forEachIndexed { index, row ->
            val lineNo = index + 2 // 第 1 行为表头
            when {
                row.hardwareId.isBlank() ->
                    failedRows.add("第${lineNo}行：设备编号为空")
                hardwareIdExists(row.hardwareId) -> skipped++
                else -> {
                    newBoundDevice(row.hardwareId, row.alias, row.model, row.groupName)
                    success++
                }
            }
        }
        return Result.success(
            DeviceImportResult(
                totalCount = rows.size,
                successCount = success,
                skippedCount = skipped,
                failedRows = failedRows
            )
        )
    }

    // ==================== DeviceRepository：权限 / 功能 / 分发 / 升级（Mock） ====================

    private val devicePermissionStore = mutableMapOf<String, DevicePermissionConfig>()
    private val deviceFunctionStore = mutableMapOf<String, DeviceFunctionConfig>()

    override suspend fun getDevicePermissions(deviceId: String): Result<DevicePermissionConfig> =
        Result.success(devicePermissionStore.getOrPut(deviceId) { DevicePermissionConfig(deviceId = deviceId) })

    override suspend fun updateDevicePermissions(config: DevicePermissionConfig): Result<DevicePermissionConfig> {
        devicePermissionStore[config.deviceId] = config
        return Result.success(config)
    }

    override suspend fun getDeviceFunctionConfig(deviceId: String): Result<DeviceFunctionConfig> =
        Result.success(deviceFunctionStore.getOrPut(deviceId) { DeviceFunctionConfig(deviceId = deviceId) })

    override suspend fun updateDeviceFunctionConfig(config: DeviceFunctionConfig): Result<DeviceFunctionConfig> {
        deviceFunctionStore[config.deviceId] = config
        return Result.success(config)
    }

    override suspend fun getDistributableApps(deviceId: String): Result<List<DistributableApp>> =
        Result.success(
            listOf(
                DistributableApp("com.xueersi.classroom", "学而思课堂", "3.2.1", null, "https://mock.padguard.com/apks/xes.apk"),
                DistributableApp("com.iflytek.listen", "讯飞听听", "2.8.0", null, "https://mock.padguard.com/apks/ifly.apk"),
                DistributableApp("com.baidu.input.pad", "百度输入法 Pad 版", "9.1.0", null, "https://mock.padguard.com/apks/baidu_input.apk")
            )
        )

    override suspend fun dispatchApp(deviceId: String, packageName: String, action: AppDispatchAction): Result<Unit> {
        if (!deviceStore.containsKey(deviceId)) {
            return Result.failure(Exception("设备不存在"))
        }
        return Result.success(Unit)
    }

    override suspend fun getDeviceUpgradeInfo(deviceId: String): Result<DeviceUpgradeInfo> {
        val device = deviceStore[deviceId]
            ?: return Result.failure(Exception("设备不存在"))
        return Result.success(
            DeviceUpgradeInfo(
                deviceId = deviceId,
                currentAppVersion = device.appVersion ?: "1.0.0",
                latestAppVersion = "1.2.0",
                releaseNote = "新增学习报告、修复已知问题"
            )
        )
    }

    override suspend fun upgradeDevice(deviceId: String): Result<Unit> {
        val device = deviceStore[deviceId]
            ?: return Result.failure(Exception("设备不存在"))
        deviceStore[deviceId] = device.copy(appVersion = "1.2.0")
        refreshDeviceListFlow()
        return Result.success(Unit)
    }

    // ==================== MonitorRepository 实现 (P1 - Mock) ====================

    override suspend fun requestScreenshot(deviceId: String): Result<ScreenshotData> =
        Result.success(ScreenshotData(
            deviceId = deviceId,
            imageBase64 = null,
            thumbnailUrl = null,
            capturedAt = System.currentTimeMillis(),
            width = 1920,
            height = 1080
        ))

    override suspend fun getDeviceInfo(deviceId: String): Result<Device> =
        getDeviceDetail(deviceId)

    override suspend fun getScreenshotHistory(deviceId: String, limit: Int): Result<List<ScreenshotData>> =
        Result.success(emptyList())

    override suspend fun takePhoto(deviceId: String): Result<String> =
        Result.success("https://mock.padguard.com/photos/${deviceId}_${System.currentTimeMillis()}.jpg")

    override suspend fun startRecording(deviceId: String): Result<String> =
        Result.success("recording_${System.currentTimeMillis()}")

    override suspend fun stopRecording(deviceId: String): Result<String> =
        Result.success("https://mock.padguard.com/recordings/${deviceId}_${System.currentTimeMillis()}.m4a")

    // ---- 录屏 ----

    private val activeRecordTasks = mutableMapOf<String, ScreenRecordTask>()
    private val screenMonitorSettingsStore = mutableMapOf<String, ScreenMonitorSettings>()

    override suspend fun startScreenRecord(
        deviceId: String,
        resolution: RecordResolution,
        withAudio: Boolean
    ): Result<ScreenRecordTask> {
        val task = ScreenRecordTask(
            taskId = "rec_${System.currentTimeMillis()}",
            deviceId = deviceId,
            startedAt = System.currentTimeMillis(),
            resolution = resolution,
            withAudio = withAudio
        )
        activeRecordTasks[task.taskId] = task
        return Result.success(task)
    }

    override suspend fun stopScreenRecord(taskId: String): Result<String> {
        val task = activeRecordTasks.remove(taskId)
            ?: return Result.failure(IllegalStateException("录屏任务不存在或已结束"))
        return Result.success("https://mock.padguard.com/screen-records/${task.deviceId}_${task.startedAt}.mp4")
    }

    // ---- 屏幕监控设置 ----

    override suspend fun getScreenMonitorSettings(deviceId: String): Result<ScreenMonitorSettings> =
        Result.success(screenMonitorSettingsStore[deviceId] ?: ScreenMonitorSettings(deviceId = deviceId))

    override suspend fun updateScreenMonitorSettings(
        settings: ScreenMonitorSettings
    ): Result<ScreenMonitorSettings> {
        screenMonitorSettingsStore[settings.deviceId] = settings
        return Result.success(settings)
    }

    // ==================== MessageRepository 实现 (Mock) ====================

    private val publishedMessagesStore = mutableMapOf<String, MutableList<PublishedMessage>>()

    override suspend fun publishMessage(request: MessagePublishRequest): Result<PublishedMessage> {
        if (request.contentType == MessageContentType.TEXT && request.text.isNullOrBlank()) {
            return Result.failure(IllegalArgumentException("文字内容不能为空"))
        }
        if (request.contentType != MessageContentType.TEXT && request.mediaUrl.isNullOrBlank()) {
            return Result.failure(IllegalArgumentException("请先选择待发布的素材"))
        }
        val record = PublishedMessage(
            deviceId = request.deviceId,
            contentType = request.contentType,
            summary = summarizeMessage(request),
            displaySeconds = request.displaySeconds,
            fullScreen = request.fullScreen,
            publishedAt = System.currentTimeMillis()
        )
        publishedMessagesStore.getOrPut(request.deviceId) { mutableListOf() }.add(0, record)
        return Result.success(record)
    }

    override suspend fun getPublishedMessages(
        deviceId: String,
        limit: Int
    ): Result<List<PublishedMessage>> =
        Result.success(publishedMessagesStore[deviceId].orEmpty().take(limit))

    /** 发布记录摘要：文字取内容，媒体类取「素材名 · 文字说明」。 */
    private fun summarizeMessage(request: MessagePublishRequest): String = when (request.contentType) {
        MessageContentType.TEXT -> request.text.orEmpty()
        else -> listOfNotNull(request.mediaName, request.text?.takeIf { it.isNotBlank() })
            .joinToString(" · ")
            .ifBlank { request.contentType.label }
    }

    // ==================== LocationRepository 实现 (Mock) ====================

    private val geofenceStore = mutableMapOf<String, GeofenceConfig>()

    override suspend fun getDeviceLocation(deviceId: String): Result<LocationInfo> =
        Result.success(LocationInfo(
            latitude = 22.5431,
            longitude = 114.0579,
            address = "深圳市南山区科技园",
            accuracy = 10f,
            timestamp = System.currentTimeMillis()
        ))

    override suspend fun getGeofence(deviceId: String): Result<GeofenceConfig> =
        Result.success(
            geofenceStore[deviceId] ?: GeofenceConfig(
                deviceId = deviceId,
                enabled = true,
                name = "学校周边安全区域",
                centerLatitude = 22.5431,
                centerLongitude = 114.0579,
                radiusMeters = 800
            )
        )

    override suspend fun updateGeofence(config: GeofenceConfig): Result<GeofenceConfig> {
        geofenceStore[config.deviceId] = config
        return Result.success(config)
    }

    override suspend fun getTrackHistory(deviceId: String): Result<List<LocationInfo>> {
        val now = System.currentTimeMillis()
        val minute = 60_000L
        // Mock：模拟设备越界后的一段移动轨迹（按时间正序）
        val track = listOf(
            LocationInfo(22.5431, 114.0579, "深圳市南山区科技园", 10f, now - 40 * minute),
            LocationInfo(22.5452, 114.0605, "科苑南路辅路", 12f, now - 30 * minute),
            LocationInfo(22.5487, 114.0642, "深南大道辅道", 15f, now - 20 * minute),
            LocationInfo(22.5520, 114.0688, "粤海街道办附近", 18f, now - 10 * minute),
            LocationInfo(22.5556, 114.0731, "高新南地铁站", 20f, now)
        )
        return Result.success(track)
    }

    // ==================== PolicyRepository 实现 (Mock) ====================

    override suspend fun getTimeRestrictions(deviceId: String): Result<List<TimeRestriction>> =
        Result.success(listOf(
            TimeRestriction(deviceId = deviceId, dayOfWeek = 1, startTime = "08:00", endTime = "18:00", maxMinutes = 120),
            TimeRestriction(deviceId = deviceId, dayOfWeek = 2, startTime = "08:00", endTime = "18:00", maxMinutes = 120),
            TimeRestriction(deviceId = deviceId, dayOfWeek = 6, startTime = "09:00", endTime = "21:00", maxMinutes = 180),
            TimeRestriction(deviceId = deviceId, dayOfWeek = 7, startTime = "09:00", endTime = "21:00", maxMinutes = 180)
        ))

    override suspend fun setTimeRestriction(restriction: TimeRestriction): Result<TimeRestriction> =
        Result.success(restriction)

    override suspend fun deleteTimeRestriction(restrictionId: String): Result<Unit> =
        Result.success(Unit)

    override suspend fun setGlobalDailyLimit(deviceId: String, minutes: Int): Result<Unit> =
        Result.success(Unit)

    override suspend fun getGlobalDailyLimit(deviceId: String): Result<Int> =
        Result.success(DEFAULT_DAILY_LIMIT_MINUTES)

    override suspend fun getInstalledApps(deviceId: String): Result<List<AppPolicy>> =
        Result.success(listOf(
            AppPolicy(deviceId = deviceId, packageName = "com.tencent.mm", appName = "微信", isBlocked = false, iconUrl = null),
            AppPolicy(deviceId = deviceId, packageName = "com.tencent.mobileqq", appName = "QQ", isBlocked = false, dailyLimitMinutes = 60, iconUrl = null),
            AppPolicy(deviceId = deviceId, packageName = "com.kuaiya.player", appName = "快影", isBlocked = true, iconUrl = null),
            AppPolicy(deviceId = deviceId, packageName = "com.netease.dwrg", appName = "荒野行动", isBlocked = true, iconUrl = null),
            AppPolicy(deviceId = deviceId, packageName = "com.ss.android.ugc.aweme", appName = "抖音", isBlocked = true, dailyLimitMinutes = 30, iconUrl = null)
        ))

    override suspend fun updateAppBlacklist(deviceId: String, blockedPackages: List<String>): Result<Unit> =
        Result.success(Unit)

    override suspend fun setAppTimeLimit(policy: AppPolicy): Result<Unit> =
        Result.success(Unit)

    override suspend fun installApp(deviceId: String, appUrl: String): Result<Unit> =
        Result.success(Unit)

    override suspend fun uninstallApp(deviceId: String, packageName: String): Result<Unit> =
        Result.success(Unit)

    override suspend fun getAppInventory(deviceId: String): Result<List<InstalledApp>> =
        Result.success(emptyList())

    override suspend fun installRemoteApp(deviceId: String, app: DistributableApp): Result<Unit> =
        Result.success(Unit)

    override suspend fun setAppSuspended(deviceId: String, packageName: String, suspended: Boolean): Result<Unit> =
        Result.success(Unit)

    override suspend fun setAppsSuspended(
        deviceId: String,
        packages: List<String>,
        suspended: Boolean
    ): Result<Unit> = Result.success(Unit)

    override suspend fun getWebPolicy(deviceId: String): Result<WebPolicy> =
        Result.success(WebPolicy(deviceId = deviceId))

    override suspend fun updateUrlBlacklist(deviceId: String, urls: List<String>): Result<Unit> =
        Result.success(Unit)

    override suspend fun setBrowserDisabled(deviceId: String, disabled: Boolean): Result<Unit> =
        Result.success(Unit)

    override suspend fun lockScreen(deviceId: String): Result<Unit> =
        Result.success(Unit)

    override suspend fun setEyeProtection(deviceId: String, enabled: Boolean, filterLevel: Int): Result<Unit> =
        Result.success(Unit)

    override suspend fun setControlMode(deviceId: String, mode: ControlMode): Result<Unit> =
        Result.success(Unit)

    override suspend fun getPolicyTemplates(sceneType: SceneType): Result<List<PolicyTemplate>> =
        Result.success(listOf(
            PolicyTemplate(id = "tpl_001", name = "学习模式", description = "仅允许学习类应用，屏蔽娱乐和社交", sceneType = sceneType, category = "学习"),
            PolicyTemplate(id = "tpl_002", name = "防沉迷模式", description = "严格限制游戏和视频时长", sceneType = sceneType, category = "防沉迷"),
            PolicyTemplate(id = "tpl_003", name = "考试模式", description = "除考试必需应用外全部禁用", sceneType = SceneType.SCHOOL, category = "考试"),
            PolicyTemplate(id = "tpl_004", name = "休息模式", description = "放宽所有限制，允许正常使用", sceneType = sceneType, category = "休息")
        ))

    override suspend fun applyPolicyTemplate(deviceId: String, templateId: String): Result<Unit> =
        Result.success(Unit)

    // ==================== TabletUsageSettingsRepository 实现 (Mock) ====================

    private val tabletUsageSettingsStore = mutableMapOf<String, TabletUsageSettings>()

    override suspend fun getTabletUsageSettings(deviceId: String): Result<TabletUsageSettings> {
        val cached = tabletUsageSettingsStore[deviceId]
        return if (cached != null) {
            Result.success(cached)
        } else {
            Result.success(TabletUsageSettings(deviceId = deviceId))
        }
    }

    override suspend fun updateTabletUsageSettings(
        settings: TabletUsageSettings
    ): Result<TabletUsageSettings> {
        // Mock：保存设置，并模拟同步至成长端
        val synced = settings.copy(syncToDevice = true)
        tabletUsageSettingsStore[settings.deviceId] = synced
        return Result.success(synced)
    }

    // ==================== StatisticsRepository 实现 (Mock) ====================

    override suspend fun getUsageStats(deviceId: String, period: ReportPeriod): Result<StatisticsReport> {
        val calendar = java.util.Calendar.getInstance()
        val format = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
        val endDate = format.format(calendar.time)

        val dayCount = when (period) {
            ReportPeriod.DAILY -> 1
            ReportPeriod.WEEKLY -> 7
            ReportPeriod.MONTHLY -> 30
        }
        calendar.add(java.util.Calendar.DAY_OF_YEAR, -(dayCount - 1))
        val startDate = format.format(calendar.time)

        val dailyUsages = (0 until dayCount).map { offset ->
            val c = java.util.Calendar.getInstance()
            c.add(java.util.Calendar.DAY_OF_YEAR, -(dayCount - 1 - offset))
            val date = format.format(c.time)
            val minutes = listOf(30, 60, 90, 45, 120, 80, 100, 70, 55, 85)[offset % 10]
            DailyUsage(date, minutes, violationCount = if (minutes > 120) 1 else 0)
        }

        val totalUsageMinutes = dailyUsages.sumOf { it.usageMinutes }
        val topApps = when (period) {
            ReportPeriod.DAILY -> listOf(
                AppUsage(packageName = "com.tencent.qqlive", appName = "腾讯视频", usageMinutes = 30, iconUrl = KnownAppIcons.iconFor("com.tencent.qqlive"), startTime = "20:15:20", endTime = "23:30:30", usageSeconds = 3 * 3600 + 15 * 60 + 10),
                AppUsage(packageName = "com.tencent.mm", appName = "微信", usageMinutes = 25, iconUrl = KnownAppIcons.iconFor("com.tencent.mm"), startTime = "08:30:00", endTime = "08:55:30", usageSeconds = 25 * 60 + 30),
                AppUsage(packageName = "com.tencent.mobileqq", appName = "QQ", usageMinutes = 20, iconUrl = KnownAppIcons.iconFor("com.tencent.mobileqq"), startTime = "09:10:00", endTime = "09:30:00", usageSeconds = 20 * 60),
                AppUsage(packageName = "com.ss.android.ugc.aweme", appName = "抖音", usageMinutes = 15, iconUrl = KnownAppIcons.iconFor("com.ss.android.ugc.aweme"), startTime = "10:00:00", endTime = "10:15:00", usageSeconds = 15 * 60)
            )
            ReportPeriod.WEEKLY -> listOf(
                AppUsage(packageName = "com.tencent.mm", appName = "微信", usageMinutes = 180, iconUrl = KnownAppIcons.iconFor("com.tencent.mm"), startTime = "08:00:00", endTime = "11:00:00", usageSeconds = 180 * 60),
                AppUsage(packageName = "com.tencent.qqlive", appName = "腾讯视频", usageMinutes = 150, iconUrl = KnownAppIcons.iconFor("com.tencent.qqlive"), startTime = "20:00:00", endTime = "22:30:00", usageSeconds = 150 * 60),
                AppUsage(packageName = "com.ss.android.ugc.aweme", appName = "抖音", usageMinutes = 120, iconUrl = KnownAppIcons.iconFor("com.ss.android.ugc.aweme"), startTime = "19:00:00", endTime = "21:00:00", usageSeconds = 120 * 60),
                AppUsage(packageName = "com.tencent.mobileqq", appName = "QQ", usageMinutes = 90, iconUrl = KnownAppIcons.iconFor("com.tencent.mobileqq"), startTime = "12:00:00", endTime = "13:30:00", usageSeconds = 90 * 60),
                AppUsage(packageName = "com.netease.dwrg", appName = "荒野行动", usageMinutes = 60, iconUrl = KnownAppIcons.iconFor("com.netease.dwrg"), startTime = "15:00:00", endTime = "16:00:00", usageSeconds = 60 * 60)
            )
            ReportPeriod.MONTHLY -> listOf(
                AppUsage(packageName = "com.tencent.mm", appName = "微信", usageMinutes = 720, iconUrl = KnownAppIcons.iconFor("com.tencent.mm"), startTime = "08:00:00", endTime = "20:00:00", usageSeconds = 720 * 60),
                AppUsage(packageName = "com.tencent.qqlive", appName = "腾讯视频", usageMinutes = 600, iconUrl = KnownAppIcons.iconFor("com.tencent.qqlive"), startTime = "20:00:00", endTime = "23:30:00", usageSeconds = 600 * 60),
                AppUsage(packageName = "com.ss.android.ugc.aweme", appName = "抖音", usageMinutes = 480, iconUrl = KnownAppIcons.iconFor("com.ss.android.ugc.aweme"), startTime = "19:00:00", endTime = "22:00:00", usageSeconds = 480 * 60),
                AppUsage(packageName = "com.tencent.mobileqq", appName = "QQ", usageMinutes = 360, iconUrl = KnownAppIcons.iconFor("com.tencent.mobileqq"), startTime = "12:00:00", endTime = "18:00:00", usageSeconds = 360 * 60),
                AppUsage(packageName = "com.kuaiya.player", appName = "快影", usageMinutes = 240, iconUrl = KnownAppIcons.iconFor("com.kuaiya.player"), startTime = "10:00:00", endTime = "14:00:00", usageSeconds = 240 * 60)
            )
        }

        return Result.success(StatisticsReport(
            deviceId = deviceId,
            period = period,
            startDate = startDate,
            endDate = endDate,
            totalUsageMinutes = totalUsageMinutes,
            dailyUsages = dailyUsages,
            topApps = topApps,
            violationCount = dailyUsages.count { it.violationCount > 0 },
            alertCount = 1
        ))
    }

    override suspend fun getTodayUsage(deviceId: String): Result<UsageStats> =
        Result.success(UsageStats(
            deviceId = deviceId,
            date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(java.util.Date()),
            totalUsageMinutes = 90,
            appUsages = listOf(
                AppUsage(
                    packageName = "com.tencent.qqlive",
                    appName = "腾讯视频",
                    usageMinutes = 30,
                    iconUrl = KnownAppIcons.iconFor("com.tencent.qqlive"),
                    startTime = "20:15:20",
                    endTime = "23:30:30",
                    usageSeconds = 3 * 3600 + 15 * 60 + 10
                ),
                AppUsage(
                    packageName = "com.tencent.mm",
                    appName = "微信",
                    usageMinutes = 25,
                    iconUrl = KnownAppIcons.iconFor("com.tencent.mm"),
                    startTime = "08:30:10",
                    endTime = "08:55:30",
                    usageSeconds = 25 * 60 + 20
                ),
                AppUsage(
                    packageName = "com.tencent.mobileqq",
                    appName = "QQ",
                    usageMinutes = 20,
                    iconUrl = KnownAppIcons.iconFor("com.tencent.mobileqq"),
                    startTime = "09:10:00",
                    endTime = "09:30:00",
                    usageSeconds = 20 * 60
                ),
                AppUsage(
                    packageName = "com.ss.android.ugc.aweme",
                    appName = "抖音",
                    usageMinutes = 15,
                    iconUrl = KnownAppIcons.iconFor("com.ss.android.ugc.aweme"),
                    startTime = "10:00:00",
                    endTime = "10:15:00",
                    usageSeconds = 15 * 60
                )
            )
        ))

    override suspend fun getAppUsageHistory(
        deviceId: String,
        packageName: String,
        period: ReportPeriod
    ): Result<List<AppUsageHistoryEntry>> {
        val format = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
        val dayCount = when (period) {
            ReportPeriod.DAILY -> 1
            ReportPeriod.WEEKLY -> 7
            ReportPeriod.MONTHLY -> 30
        }
        // Mock：根据应用包名生成不同节奏的使用记录
        val (openCount, secRange) = when (packageName) {
            "com.tencent.qqlive" -> 2 to (30 * 60..3 * 3600 + 30 * 60)
            "com.tencent.mm" -> 4 to (3 * 60..30 * 60)
            "com.tencent.mobileqq" -> 3 to (5 * 60..25 * 60)
            "com.ss.android.ugc.aweme" -> 3 to (4 * 60..20 * 60)
            else -> 3 to (2 * 60..15 * 60)
        }
        val rnd = java.util.Random(packageName.hashCode().toLong())
        val entries = mutableListOf<AppUsageHistoryEntry>()
        for (d in 0 until dayCount) {
            val c = java.util.Calendar.getInstance()
            c.add(java.util.Calendar.DAY_OF_YEAR, -(dayCount - 1 - d))
            val date = format.format(c.time)
            val opens = (openCount + rnd.nextInt(2)).coerceAtLeast(1)
            for (i in 0 until opens) {
                val startHour = 7 + rnd.nextInt(14)            // 7 ~ 20
                val startMin = rnd.nextInt(60)
                val startSec = rnd.nextInt(60)
                val seconds = secRange.first + rnd.nextInt((secRange.last - secRange.first).coerceAtLeast(1))
                val endTotalSec = (startHour * 3600 + startMin * 60 + startSec) + seconds
                val endHour = (endTotalSec / 3600).coerceAtMost(23)
                val endMin = (endTotalSec % 3600) / 60
                val endSec = endTotalSec % 60
                entries += AppUsageHistoryEntry(
                    date = date,
                    startTime = "%02d:%02d:%02d".format(startHour, startMin, startSec),
                    endTime = "%02d:%02d:%02d".format(endHour, endMin, endSec),
                    usageSeconds = seconds
                )
            }
        }
        return Result.success(entries.sortedByDescending { it.date + " " + it.startTime })
    }

    override suspend fun getWebActivityStats(deviceId: String, period: ReportPeriod): Result<WebActivityStats> =
        Result.success(WebActivityStats(
            totalVisits = 45,
            topDomains = listOf(
                DomainVisit("baidu.com", 15, System.currentTimeMillis()),
                DomainVisit("zhihu.com", 8, System.currentTimeMillis() - 7200000),
                DomainVisit("bilibili.com", 12, System.currentTimeMillis() - 3600000)
            ),
            blockedAttempts = 3
        ))

    override suspend fun getViolationStats(deviceId: String, period: ReportPeriod): Result<ViolationStats> =
        Result.success(ViolationStats(
            totalCount = 5,
            byCategory = mapOf("APP_VIOLATION" to 2, "TIME_LIMIT_EXCEEDED" to 2, "URL_VIOLATION" to 1),
            trend = listOf(
                DailyViolation("2026-08-23", 1),
                DailyViolation("2026-08-24", 0),
                DailyViolation("2026-08-25", 2),
                DailyViolation("2026-08-26", 0),
                DailyViolation("2026-08-27", 1),
                DailyViolation("2026-08-28", 1),
                DailyViolation("2026-08-29", 0)
            )
        ))

    override suspend fun exportReport(deviceId: String, period: ReportPeriod): Result<String> =
        Result.success("https://mock.padguard.com/reports/${deviceId}_${period.name}_${System.currentTimeMillis()}.pdf")

    // ==================== AlertRepository 实现 (Mock) ====================

    override suspend fun getAlerts(deviceId: String?, status: AlertStatus?): Result<List<Alert>> =
        Result.success(listOf(
            Alert(
                id = "alert_001",
                deviceId = "dev_001",
                deviceName = "小明的平板",
                level = AlertLevel.WARNING,
                title = "使用超时",
                message = "今日使用时长已超过限制（120分钟）",
                status = AlertStatus.ACTIVE,
                category = AlertCategory.TIME_LIMIT_EXCEEDED,
                triggeredAt = System.currentTimeMillis() - 1800000
            ),
            Alert(
                id = "alert_002",
                deviceId = "dev_002",
                deviceName = "教室平板-A03",
                level = AlertLevel.CRITICAL,
                title = "设备离线",
                message = "设备已离线超过 1 小时",
                status = AlertStatus.ACTIVE,
                category = AlertCategory.DEVICE_OFFLINE,
                triggeredAt = System.currentTimeMillis() - 3600000
            )
        ))

    override suspend fun getUnreadAlertCount(): Result<Int> = Result.success(2)

    override suspend fun acknowledgeAlert(alertId: String): Result<Unit> = Result.success(Unit)

    override suspend fun closeAlert(alertId: String, reason: String?): Result<Unit> = Result.success(Unit)

    override fun observeAlerts(): Flow<Alert> = kotlinx.coroutines.flow.emptyFlow()

}
