package com.padguard.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.padguard.domain.model.*
import com.padguard.domain.repository.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
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
    com.padguard.domain.repository.AlertRepository {

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

    private fun createMockDevices(): List<Device> = listOf(
        Device(
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
        Device(
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
        Result.success(createMockDevices())

    override suspend fun getDeviceDetail(deviceId: String): Result<Device> {
        val device = createMockDevices().find { it.id == deviceId }
            ?: return Result.failure(Exception("设备不存在"))
        return Result.success(device)
    }

    override suspend fun bindDevice(deviceId: String, alias: String?): Result<Device> {
        val newDevice = Device(
            id = "dev_new_${System.currentTimeMillis()}",
            name = alias ?: "新设备",
            deviceId = deviceId,
            model = "Unknown",
            osVersion = "Unknown",
            appVersion = "1.0.0",
            onlineStatus = DeviceOnlineStatus.UNKNOWN,
            lastOnlineTime = null,
            batteryLevel = null,
            controlMode = ControlMode.NORMAL,
            groupId = null,
            groupName = null,
            sceneType = SceneType.FAMILY,
            latitude = null,
            longitude = null
        )
        return Result.success(newDevice)
    }

    override suspend fun unbindDevice(deviceId: String): Result<Unit> = Result.success(Unit)

    override suspend fun renameDevice(deviceId: String, newName: String): Result<Unit> = Result.success(Unit)

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

    override fun observeDeviceList(): Flow<List<Device>> =
        kotlinx.coroutines.flow.flowOf(createMockDevices())

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

    override suspend fun getDeviceLocation(deviceId: String): Result<LocationInfo> =
        Result.success(LocationInfo(
            latitude = 22.5431,
            longitude = 114.0579,
            address = "深圳市南山区科技园",
            accuracy = 10f,
            timestamp = System.currentTimeMillis()
        ))

    override suspend fun getScreenshotHistory(deviceId: String, limit: Int): Result<List<ScreenshotData>> =
        Result.success(emptyList())

    override suspend fun takePhoto(deviceId: String): Result<String> =
        Result.success("https://mock.padguard.com/photos/${deviceId}_${System.currentTimeMillis()}.jpg")

    override suspend fun startRecording(deviceId: String): Result<String> =
        Result.success("recording_${System.currentTimeMillis()}")

    override suspend fun stopRecording(deviceId: String): Result<String> =
        Result.success("https://mock.padguard.com/recordings/${deviceId}_${System.currentTimeMillis()}.m4a")

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

    // ==================== StatisticsRepository 实现 (Mock) ====================

    override suspend fun getUsageStats(deviceId: String, period: ReportPeriod): Result<StatisticsReport> =
        Result.success(StatisticsReport(
            deviceId = deviceId,
            period = period,
            startDate = "2026-08-22",
            endDate = "2026-08-29",
            totalUsageMinutes = 720,
            dailyUsages = listOf(
                DailyUsage("2026-08-23", 120),
                DailyUsage("2026-08-24", 90),
                DailyUsage("2026-08-25", 150),
                DailyUsage("2026-08-26", 80),
                DailyUsage("2026-08-27", 110),
                DailyUsage("2026-08-28", 100),
                DailyUsage("2026-08-29", 70)
            ),
            topApps = listOf(
                AppUsage("com.tencent.mm", "微信", 180, null),
                AppUsage("com.tencent.mobileqq", "QQ", 120, null),
                AppUsage("com.ss.android.ugc.aweme", "抖音", 90, null),
                AppUsage("com.netease.dwrg", "荒野行动", 60, null)
            ),
            violationCount = 3,
            alertCount = 1
        ))

    override suspend fun getTodayUsage(deviceId: String): Result<UsageStats> =
        Result.success(UsageStats(
            deviceId = deviceId,
            date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(java.util.Date()),
            totalUsageMinutes = 70,
            appUsages = listOf(
                AppUsage("com.tencent.mm", "微信", 25, null),
                AppUsage("com.tencent.mobileqq", "QQ", 20, null),
                AppUsage("com.ss.android.ugc.aweme", "抖音", 15, null)
            )
        ))

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