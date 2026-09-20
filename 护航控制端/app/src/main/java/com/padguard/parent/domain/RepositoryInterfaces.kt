package com.padguard.domain.repository

import com.padguard.domain.model.*
import kotlinx.coroutines.flow.Flow

/**
 * 认证仓库接口
 * 对应设计文档"账号与权限"章节
 * P0 优先级功能
 */
interface AuthRepository {

    /** 手机号+密码登录 */
    suspend fun loginWithPassword(phone: String, password: String): Result<User>

    /** 手机号+短信验证码登录 */
    suspend fun loginWithSmsCode(phone: String, code: String): Result<User>

    /** 发送短信验证码 */
    suspend fun sendSmsCode(phone: String): Result<Unit>

    /** 退出登录 */
    suspend fun logout(): Result<Unit>

    /** 刷新 Token */
    suspend fun refreshToken(): Result<User>

    /** 获取当前登录用户（本地缓存） */
    fun getCurrentUserFlow(): Flow<User?>

    /** 检查是否已登录 */
    suspend fun isLoggedIn(): Boolean
}

/**
 * 设备管理仓库接口
 * 对应设计文档"设备资源管理"章节
 */
interface DeviceRepository {

    /** 获取设备列表 */
    suspend fun getDeviceList(): Result<List<Device>>

    /** 获取设备详情 */
    suspend fun getDeviceDetail(deviceId: String): Result<Device>

    /** 绑定设备（扫码/手动输入） */
    suspend fun bindDevice(deviceId: String, alias: String?): Result<Device>

    /** 解绑设备 */
    suspend fun unbindDevice(deviceId: String): Result<Unit>

    /** 修改设备别名 */
    suspend fun renameDevice(deviceId: String, newName: String): Result<Unit>

    /** 获取设备分组列表 */
    suspend fun getDeviceGroups(): Result<List<DeviceGroup>>

    /** 创建分组 */
    suspend fun createGroup(name: String, sceneType: SceneType): Result<DeviceGroup>

    /** 将设备移入分组 */
    suspend fun assignDeviceToGroup(deviceId: String, groupId: String): Result<Unit>

    /** 观察设备列表变化（实时更新） */
    fun observeDeviceList(): Flow<List<Device>>

    // === 设备接入（对标 MDM Enrollment：扫码 / 配对码 / 局域网发现 / 批量导入） ===

    /** 生成待扫码绑定的二维码载荷（家长端展示，被控平板扫码后回报） */
    suspend fun generateBindQrPayload(): Result<String>

    /** 解析被控平板二维码载荷并绑定设备 */
    suspend fun bindByQrPayload(payload: String, alias: String?): Result<Device>

    /** 生成 6 位配对码 */
    suspend fun generatePairingCode(): Result<String>

    /** 通过配对码绑定设备 */
    suspend fun bindByPairingCode(code: String, alias: String?): Result<Device>

    /** 发现同一局域网内的待接入平板 */
    suspend fun discoverLanDevices(): Result<List<LanDevice>>

    /** 绑定局域网内发现的设备 */
    suspend fun bindLanDevice(lanDevice: LanDevice, alias: String?): Result<Device>

    /** 批量导入设备台账（Excel/CSV 解析结果） */
    suspend fun importDevices(rows: List<DeviceImportRow>): Result<DeviceImportResult>

    // === 设备管理（对标 MDM：权限 / 功能 / 应用分发 / 远程升级） ===

    /** 获取设备权限配置 */
    suspend fun getDevicePermissions(deviceId: String): Result<DevicePermissionConfig>

    /** 更新设备权限配置并下发至被控平板 */
    suspend fun updateDevicePermissions(config: DevicePermissionConfig): Result<DevicePermissionConfig>

    /** 获取设备功能开关配置 */
    suspend fun getDeviceFunctionConfig(deviceId: String): Result<DeviceFunctionConfig>

    /** 更新设备功能开关并下发至被控平板 */
    suspend fun updateDeviceFunctionConfig(config: DeviceFunctionConfig): Result<DeviceFunctionConfig>

    /** 获取可分发的企业应用库 */
    suspend fun getDistributableApps(deviceId: String): Result<List<DistributableApp>>

    /** 对设备执行应用分发动作（安装/卸载/黑白名单） */
    suspend fun dispatchApp(deviceId: String, packageName: String, action: AppDispatchAction): Result<Unit>

    /** 获取设备升级信息（当前版本 vs 最新版本） */
    suspend fun getDeviceUpgradeInfo(deviceId: String): Result<DeviceUpgradeInfo>

    /** 下发远程升级指令 */
    suspend fun upgradeDevice(deviceId: String): Result<Unit>
}

data class DeviceGroup(
    val id: String,
    val name: String,
    val sceneType: SceneType,
    val deviceCount: Int
)

/**
 * 屏幕监控仓库接口
 * 对应设计文档"实时管控 > 屏幕监控"章节
 * P1 优先级功能
 *
 * 职责：实时截屏、录屏、屏幕监控设置。
 * 设备位置属于定位领域，请使用 [LocationRepository]，勿在本接口中堆叠。
 */
interface MonitorRepository {

    /** 请求实时截屏 */
    suspend fun requestScreenshot(deviceId: String): Result<ScreenshotData>

    /** 获取设备实时信息（电量、网络等） */
    suspend fun getDeviceInfo(deviceId: String): Result<Device>

    /** 获取实时行为截图历史 */
    suspend fun getScreenshotHistory(deviceId: String, limit: Int = 20): Result<List<ScreenshotData>>

    /** 远程拍照 */
    suspend fun takePhoto(deviceId: String): Result<String>  // 返回图片URL

    /** 远程录音 */
    suspend fun startRecording(deviceId: String): Result<String>
    suspend fun stopRecording(deviceId: String): Result<String>  // 返回录音文件URL

    // === 录屏 ===
    /** 开始录屏，返回录屏任务（含任务 ID） */
    suspend fun startScreenRecord(
        deviceId: String,
        resolution: RecordResolution,
        withAudio: Boolean
    ): Result<ScreenRecordTask>

    /** 停止录屏，返回录屏文件 URL */
    suspend fun stopScreenRecord(taskId: String): Result<String>

    // === 屏幕监控设置 ===
    /** 获取屏幕监控设置 */
    suspend fun getScreenMonitorSettings(deviceId: String): Result<ScreenMonitorSettings>

    /** 更新屏幕监控设置并下发至被管控平板 */
    suspend fun updateScreenMonitorSettings(
        settings: ScreenMonitorSettings
    ): Result<ScreenMonitorSettings>
}

/**
 * 信息发布仓库接口
 * 对应设计文档"实时管控 > 信息发布"章节
 */
interface MessageRepository {

    /** 向被管控平板实时发布信息（文字 / 图片 / 视频 / 声音） */
    suspend fun publishMessage(request: MessagePublishRequest): Result<PublishedMessage>

    /** 获取某设备的已发布信息记录（按发布时间倒序） */
    suspend fun getPublishedMessages(deviceId: String, limit: Int = 20): Result<List<PublishedMessage>>
}

/**
 * 定位仓库接口
 * 对应设计文档"实时管控 > 定位"章节
 *
 * 说明：设备位置属定位领域，故不放在 MonitorRepository，避免单仓库职责膨胀。
 */
interface LocationRepository {

    /** 获取设备实时位置 */
    suspend fun getDeviceLocation(deviceId: String): Result<LocationInfo>

    /** 获取电子围栏配置 */
    suspend fun getGeofence(deviceId: String): Result<GeofenceConfig>

    /** 保存电子围栏配置并下发至被管控平板 */
    suspend fun updateGeofence(config: GeofenceConfig): Result<GeofenceConfig>

    /** 获取设备越界后的移动轨迹（按时间正序） */
    suspend fun getTrackHistory(deviceId: String): Result<List<LocationInfo>>
}

/**
 * 管控策略仓库接口
 * 对应设计文档"全维度管控"章节
 */
interface PolicyRepository {

    // === 使用时长 ===
    /** 获取设备的时段限制列表 */
    suspend fun getTimeRestrictions(deviceId: String): Result<List<TimeRestriction>>

    /** 设置/更新时段限制 */
    suspend fun setTimeRestriction(restriction: TimeRestriction): Result<TimeRestriction>

    /** 删除时段限制 */
    suspend fun deleteTimeRestriction(restrictionId: String): Result<Unit>

    /** 设置全局每日总时长限制 */
    suspend fun setGlobalDailyLimit(deviceId: String, minutes: Int): Result<Unit>

    /** 读取设备全局每日总时长限制（分钟）；未配置时由数据源决定默认值 */
    suspend fun getGlobalDailyLimit(deviceId: String): Result<Int>

    // === 应用管控 ===
    /** 获取设备已安装应用列表 */
    suspend fun getInstalledApps(deviceId: String): Result<List<AppPolicy>>

    /** 更新应用黑名单 */
    suspend fun updateAppBlacklist(deviceId: String, blockedPackages: List<String>): Result<Unit>

    /** 设置单应用时长限制 */
    suspend fun setAppTimeLimit(policy: AppPolicy): Result<Unit>

    /** 远程安装应用：下发指令，APK 由被管控端从 url 自行下载 */
    suspend fun installApp(deviceId: String, appUrl: String): Result<Unit>

    /** 卸载应用 */
    suspend fun uninstallApp(deviceId: String, packageName: String): Result<Unit>

    /** 读取设备已安装应用台账（服务端合并了黑名单/限额策略） */
    suspend fun getAppInventory(deviceId: String): Result<List<InstalledApp>>

    /** 远程安装（带包名，服务端据此预置台账记录） */
    suspend fun installRemoteApp(deviceId: String, app: DistributableApp): Result<Unit>

    /** 单应用挂起 / 恢复 */
    suspend fun setAppSuspended(deviceId: String, packageName: String, suspended: Boolean): Result<Unit>

    /** 批量挂起 / 恢复 */
    suspend fun setAppsSuspended(deviceId: String, packages: List<String>, suspended: Boolean): Result<Unit>

    // === 上网管控 ===
    /** 获取上网策略 */
    suspend fun getWebPolicy(deviceId: String): Result<WebPolicy>

    /** 更新网址黑名单 */
    suspend fun updateUrlBlacklist(deviceId: String, urls: List<String>): Result<Unit>

    /** 设置浏览器禁用 */
    suspend fun setBrowserDisabled(deviceId: String, disabled: Boolean): Result<Unit>

    // === 系统与安全 ===
    /** 一键锁屏 */
    suspend fun lockScreen(deviceId: String): Result<Unit>

    /** 设置护眼参数 */
    suspend fun setEyeProtection(deviceId: String, enabled: Boolean, filterLevel: Int): Result<Unit>

    // === 模式切换 ===
    /** 切换管控模式 */
    suspend fun setControlMode(deviceId: String, mode: ControlMode): Result<Unit>

    // === 策略模板 ===
    /** 获取可用策略模板列表 */
    suspend fun getPolicyTemplates(sceneType: SceneType): Result<List<PolicyTemplate>>

    /** 应用策略模板到设备 */
    suspend fun applyPolicyTemplate(deviceId: String, templateId: String): Result<Unit>

    // === 平板使用时间设置 ===
    /** 获取平板使用时间设置 */
    suspend fun getTabletUsageSettings(deviceId: String): Result<TabletUsageSettings>

    /**
     * 更新平板使用时间设置，并同步至成长端。
     * @return 同步后的设置对象
     */
    suspend fun updateTabletUsageSettings(settings: TabletUsageSettings): Result<TabletUsageSettings>
}

data class PolicyTemplate(
    val id: String,
    val name: String,
    val description: String,
    val sceneType: SceneType,
    val category: String  // 如 "学习模式", "防沉迷", "考试模式"
)

/**
 * 数据统计仓库接口
 * 对应设计文档"数据统计"章节
 */
interface StatisticsRepository {

    /** 获取设备使用统计（日/周/月） */
    suspend fun getUsageStats(deviceId: String, period: ReportPeriod): Result<StatisticsReport>

    /** 获取今日使用时长 */
    suspend fun getTodayUsage(deviceId: String): Result<UsageStats>

    /** 获取指定应用在指定时间范围内的使用记录（按时间倒序） */
    suspend fun getAppUsageHistory(
        deviceId: String,
        packageName: String,
        period: ReportPeriod
    ): Result<List<AppUsageHistoryEntry>>

    /** 获取上网行为统计 */
    suspend fun getWebActivityStats(deviceId: String, period: ReportPeriod): Result<WebActivityStats>

    /** 获取违规与告警统计 */
    suspend fun getViolationStats(deviceId: String, period: ReportPeriod): Result<ViolationStats>

    /** 导出报告 */
    suspend fun exportReport(deviceId: String, period: ReportPeriod): Result<String>  // 返回文件URL
}

data class WebActivityStats(
    val totalVisits: Int,
    val topDomains: List<DomainVisit>,
    val blockedAttempts: Int
)

data class DomainVisit(
    val domain: String,
    val visitCount: Int,
    val lastVisitTime: Long
)

data class ViolationStats(
    val totalCount: Int,
    val byCategory: Map<String, Int>,
    val trend: List<DailyViolation>
)

data class DailyViolation(
    val date: String,
    val count: Int
)

/**
 * 风险预警仓库接口
 * 对应设计文档"风险预警"章节
 */
interface AlertRepository {

    /** 获取告警列表 */
    suspend fun getAlerts(deviceId: String? = null, status: AlertStatus? = null): Result<List<Alert>>

    /** 获取未读告警数量 */
    suspend fun getUnreadAlertCount(): Result<Int>

    /** 确认告警 */
    suspend fun acknowledgeAlert(alertId: String): Result<Unit>

    /** 关闭告警 */
    suspend fun closeAlert(alertId: String, reason: String?): Result<Unit>

    /** 观察新告警（实时推送） */
    fun observeAlerts(): Flow<Alert>
}
