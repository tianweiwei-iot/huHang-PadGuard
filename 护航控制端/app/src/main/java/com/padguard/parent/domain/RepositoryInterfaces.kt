package com.padguard.domain.repository

import android.graphics.Bitmap
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

    /**
     * 实时看屏的视频流。
     *
     * 与 [requestScreenshot] 的本质区别：那条链路是"请求一张静态图"，
     * 这张是**连续的画面**——孩子屏幕上滑动一下，家长端同一秒就能看到。
     * 每 emit 一个 Bitmap 就是一帧，调用方直接上屏即可。
     */
    fun liveFrames(deviceId: String): Flow<Bitmap>

    /** 让孩子端开始推流（观看期间需周期性重发做续期） */
    suspend fun startLiveView(deviceId: String): Result<Unit>

    /** 停止孩子端推流 */
    suspend fun stopLiveView(deviceId: String): Result<Unit>

    /** 获取设备实时信息（电量、网络等） */
    suspend fun getDeviceInfo(deviceId: String): Result<Device>

    /** 获取实时行为截图历史 */
    suspend fun getScreenshotHistory(deviceId: String, limit: Int = 20): Result<List<ScreenshotData>>

    /**
     * 下载截图原图。
     *
     * [imageUrl] 为服务端返回的路径（如 /v1/files/{id}，可为绝对 URL），
     * 返回原始 JPEG 字节，由调用方决定渲染或落相册。
     */
    suspend fun downloadImage(imageUrl: String): Result<ByteArray>

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

    /**
     * 停止录屏。
     * 成功仅代表"停止指令已下发"；mp4 由孩子端编码后单独上传入库，不走本返回值。
     */
    suspend fun stopScreenRecord(deviceId: String, taskId: String): Result<Unit>

    /**
     * 查询媒体任务（录屏 / 录音）状态与文件地址。
     *
     * 停止是异步的：孩子端收尾编码器并上传 mp4 后，服务端才置 READY 并填 url。
     * 家长端停止录屏后轮询这里，拿到 url 才能给出"回放"入口。
     */
    suspend fun getMediaTask(
        deviceId: String,
        taskId: String
    ): Result<com.padguard.data.model.MediaTaskStatusDto>

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

    /**
     * 上传信息发布素材（图片 / 视频 / 音频），返回可直接访问的地址。
     *
     * 家长手里的素材都在本机相册或文件里，孩子端访问不到家长的手机，
     * 必须先落到服务端才能随消息下发。
     */
    suspend fun uploadMedia(
        fileName: String,
        contentType: String,
        bytes: ByteArray
    ): Result<UploadedMedia>
}

/** 上传完成的素材：url 直接下发给孩子端，fileName 用于界面回显与"撤掉重选" */
data class UploadedMedia(
    val fileId: String,
    val url: String,
    val fileName: String,
    val contentType: String,
    val size: Long
)

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

    /**
     * 使用权限开关。
     * @param hidden true = 关闭权限（孩子端桌面不再显示）；false = 开启权限。
     * 隐藏不等于卸载：应用与数据都还在，重新打开权限即可恢复显示。
     */
    suspend fun setAppHidden(deviceId: String, packageName: String, hidden: Boolean): Result<Unit>

    /**
     * 批量设置使用权限（隐藏 / 显示）。
     * @param all true 时作用于全部已安装应用（一键授权全部 / 一键取消全部），此时 [packages] 可空。
     */
    suspend fun setAppsHiddenBatch(
        deviceId: String,
        packages: List<String>,
        hidden: Boolean,
        all: Boolean = false
    ): Result<Int>

    /** 设置单个应用的每日使用时长上限（分钟，0 = 不限制） */
    suspend fun setAppLimit(deviceId: String, packageName: String, minutes: Int): Result<Unit>

    /** 远程安装本地选取的 APK：先上传到服务端拿到地址，再下发安装指令 */
    suspend fun installLocalApk(
        deviceId: String,
        fileName: String,
        contentType: String,
        bytes: ByteArray,
        packageName: String,
        appName: String? = null
    ): Result<Unit>

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

    /** 一键解锁：解除家长发起的远程锁屏，按钮回到"锁屏" */
    suspend fun unlockScreen(deviceId: String): Result<Unit>

    /** 设置护眼参数 */
    suspend fun setEyeProtection(deviceId: String, enabled: Boolean, filterLevel: Int): Result<Unit>

    // === 解锁 / 解锁申请闭环 ===
    /** 一键解锁：解除远程锁屏、时段锁与限额锁 */
    suspend fun unlock(deviceId: String): Result<Unit>

    /** 限时解锁：durationMinutes 到点后自动恢复管控 */
    suspend fun tempUnlock(deviceId: String, durationMinutes: Int): Result<Unit>

    /** 孩子端提交上来的待处理解锁申请 */
    suspend fun getUnlockTickets(deviceId: String): Result<List<UnlockTicket>>

    /** 同意申请（按申请时长放行） */
    suspend fun approveUnlockTicket(deviceId: String, ticketId: String, durationMinutes: Int?): Result<Unit>

    /** 拒绝申请：关闭工单并给孩子下发"申请未通过" */
    suspend fun ignoreUnlockTicket(deviceId: String, ticketId: String): Result<Unit>

    /**
     * 忽略（归档）申请：弹窗不再打扰，记录留在消息中心稍后可继续处理。
     * 与 [ignoreUnlockTicket] 的区别：不下发任何消息给孩子，且工单仍可被批准。
     */
    suspend fun dismissUnlockTicket(deviceId: String, ticketId: String): Result<Unit>

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

    // === 未成年人模式（P1 合规底座） ===
    /** 读取未成年人模式当前配置 */
    suspend fun getMinorMode(deviceId: String): Result<MinorMode>

    /**
     * 一键开启 / 关闭 / 切档。
     * @param exemptMinutes >0 时为"家长临时豁免"，只压过宵禁与护眼，不突破每日总额度
     */
    suspend fun setMinorMode(mode: MinorMode, exemptMinutes: Int? = null): Result<Unit>

    /** 各龄档的合规默认值，供选档界面在下发前展示后果 */
    suspend fun getAgeBandDefaults(): Result<Map<AgeBand, AgeBandDefault>>
}

/**
 * 分龄档位，对齐《移动互联网未成年人模式建设指南》的五档划分。
 *
 * [label] 用于选档界面文案，[key] 与服务端 / 孩子端 `AgeBand` 枚举名严格一致 ——
 * 三端共用同一套字符串，避免"家长端显示 8–12 岁、设备实际按 12–16 岁执行"的错位。
 */
enum class AgeBand(val key: String, val label: String, val hint: String) {
    UNDER_3("UNDER_3", "不满3岁", "以儿歌、启蒙内容为主，不提供游戏与短视频"),
    BAND_3_8("BAND_3_8", "3–8岁", "启蒙教育与兴趣培养，不提供直播与充值"),
    BAND_8_12("BAND_8_12", "8–12岁", "通识教育与知识科普，限制短视频，不提供直播"),
    BAND_12_16("BAND_12_16", "12–16岁", "每日不超过1小时，不提供直播，充值需限额"),
    BAND_16_18("BAND_16_18", "16–18岁", "每日不超过2小时，按成年边界过渡");

    companion object {
        /** 未知值回落 8–12 岁档（覆盖大多数在管设备），与服务端口径一致 */
        fun fromKey(key: String?): AgeBand =
            entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) } ?: BAND_8_12

        fun fromAge(age: Int): AgeBand = when {
            age < 3 -> UNDER_3
            age < 8 -> BAND_3_8
            age < 12 -> BAND_8_12
            age < 16 -> BAND_12_16
            else -> BAND_16_18
        }
    }
}

/** 某一龄档的合规默认值 */
data class AgeBandDefault(
    val dailyLimitMinutes: Int = 60,
    val continuousMinutes: Int = 30,
    val restMinutes: Int = 10,
    val curfewStart: String = "22:00",
    val curfewEnd: String = "06:00"
)

/**
 * 未成年人模式配置。
 *
 * 时长类字段为 0 表示"沿用档位默认值"，展示层要用 [AgeBandDefault] 补齐；
 * 只有家长显式设置过的值才是非 0，这也是合规审计区分"产品默认"与"家长自定义"的依据。
 */
data class MinorMode(
    val deviceId: String = "",
    val enabled: Boolean = false,
    val ageBand: AgeBand = AgeBand.BAND_8_12,
    val curfewEnabled: Boolean = true,
    val curfewStart: String = "22:00",
    val curfewEnd: String = "06:00",
    val dailyLimitMinutes: Int = 0,
    val weekendLimitMinutes: Int = 0,
    val continuousMinutes: Int = 0,
    val restMinutes: Int = 0,
    /** 家长临时豁免到期时间戳；0 = 无豁免 */
    val parentExemptUntil: Long = 0L
) {
    val exemptActive: Boolean get() = parentExemptUntil > System.currentTimeMillis()
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
 * 孩子端提交的临时解锁申请（服务端工单）。
 *
 * 闭环：孩子端锁屏页填理由 → 上行日志 → 服务端建工单 → 管控端展示 →
 * 家长「去处理」进入管控策略页解锁/改时长，或「忽略」关闭工单。
 */
data class UnlockTicket(
    val id: String,
    val deviceId: String,
    val packageName: String? = null,
    val appLabel: String? = null,
    val durationMinutes: Int? = null,
    val reason: String? = null,
    val status: String = "PENDING",
    val createdAt: Long = 0L
) {
    val isPending: Boolean get() = status == "PENDING"

    /**
     * 是否仍可被家长处理。
     *
     * 注意 `IGNORED` 也算可处理：忽略只是"暂时不打扰"，不是终态。
     * 早期若把这里写成 `status == "PENDING"`，家长点了忽略之后
     * 消息中心里的那条记录就永远点不动了 —— 与"忽略后还能处理"的产品约定直接冲突。
     */
    val isActionable: Boolean get() = status == "PENDING" || status == "IGNORED"

    /** 展示标题：单应用申请显示应用名，整机申请显示「整台设备」 */
    val targetLabel: String get() = appLabel?.takeIf { it.isNotBlank() } ?: "整台设备"
}

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
