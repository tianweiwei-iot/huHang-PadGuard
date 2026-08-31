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
}

data class DeviceGroup(
    val id: String,
    val name: String,
    val sceneType: SceneType,
    val deviceCount: Int
)

/**
 * 实时监控仓库接口
 * 对应设计文档"实时监控"章节
 * P1 优先级功能
 */
interface MonitorRepository {

    /** 请求实时截屏 */
    suspend fun requestScreenshot(deviceId: String): Result<ScreenshotData>

    /** 获取设备实时信息（电量、网络等） */
    suspend fun getDeviceInfo(deviceId: String): Result<Device>

    /** 获取设备实时位置 */
    suspend fun getDeviceLocation(deviceId: String): Result<LocationInfo>

    /** 获取实时行为截图历史 */
    suspend fun getScreenshotHistory(deviceId: String, limit: Int = 20): Result<List<ScreenshotData>>

    /** 远程拍照 */
    suspend fun takePhoto(deviceId: String): Result<String>  // 返回图片URL

    /** 远程录音 */
    suspend fun startRecording(deviceId: String): Result<String>
    suspend fun stopRecording(deviceId: String): Result<String>  // 返回录音文件URL
}

data class LocationInfo(
    val latitude: Double,
    val longitude: Double,
    val address: String?,
    val accuracy: Float?,
    val timestamp: Long
)

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

    /** 远程安装应用 */
    suspend fun installApp(deviceId: String, appUrl: String): Result<Unit>

    /** 卸载应用 */
    suspend fun uninstallApp(deviceId: String, packageName: String): Result<Unit>

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
