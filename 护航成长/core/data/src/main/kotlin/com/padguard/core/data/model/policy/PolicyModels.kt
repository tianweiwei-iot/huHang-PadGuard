package com.padguard.core.data.model.policy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 远程下发的全量策略包（对应接口契约 §6）。
 *
 * 设计约定（重要）：
 * 1. 采用「全量下发 + 版本号比对」机制，便于离线缓存与版本回滚。
 * 2. 支持**局部更新语义**：新策略包中未出现的对象字段沿用旧值，
 *    而数组/映射字段（如黑白名单）一旦出现即整体覆盖。
 *    这让服务端可以只下发变更项以减小包体，见 [mergeWith]。
 */
@Serializable
data class PolicyPackage(
    @SerialName("version") val version: Int = 0,
    @SerialName("deviceId") val deviceId: String = "",
    @SerialName("updatedAt") val updatedAt: Long = 0L,
    @SerialName("scene") val scene: SceneType = SceneType.FAMILY,
    @SerialName("effectiveFrom") val effectiveFrom: Long = 0L,
    @SerialName("effectiveTo") val effectiveTo: Long = 0L,
    @SerialName("peripheral") val peripheral: PeripheralPolicy = PeripheralPolicy(),
    @SerialName("systemLock") val systemLock: SystemLockPolicy = SystemLockPolicy(),
    @SerialName("app") val app: AppPolicy = AppPolicy(),
    @SerialName("appLimit") val appLimit: AppLimitPolicy = AppLimitPolicy(),
    @SerialName("web") val web: WebPolicy = WebPolicy(),
    @SerialName("schedule") val schedule: SchedulePolicy = SchedulePolicy(),
    @SerialName("eyeCare") val eyeCare: EyeCarePolicy = EyeCarePolicy(),
    @SerialName("kiosk") val kiosk: KioskPolicy = KioskPolicy(),
    @SerialName("monitoring") val monitoring: MonitoringPolicy = MonitoringPolicy(),
    @SerialName("security") val security: SecurityPolicy = SecurityPolicy()
) {

    /**
     * 局部合并：以 [incoming] 中显式提供的字段为准。
     *
     * 由于 kotlinx.serialization 在无默认值区分时无法判断"字段是否出现"，
     * 这里约定：服务端若要清空某对象策略，需下发显式的空对象（如 `"web": {}`），
     * 此时按默认值处理；若要沿用旧值，则整个字段不下发。
     */
    fun mergeWith(incoming: PolicyPackage): PolicyPackage {
        if (incoming.version <= version) return this
        return copy(
            version = incoming.version,
            deviceId = incoming.deviceId.ifBlank { deviceId },
            updatedAt = incoming.updatedAt,
            scene = incoming.scene,
            effectiveFrom = incoming.effectiveFrom,
            effectiveTo = incoming.effectiveTo,
            peripheral = incoming.peripheral,
            systemLock = incoming.systemLock,
            app = incoming.app,
            appLimit = incoming.appLimit,
            web = incoming.web,
            schedule = incoming.schedule,
            eyeCare = incoming.eyeCare,
            kiosk = incoming.kiosk,
            monitoring = incoming.monitoring,
            security = incoming.security
        )
    }

    companion object {
        /** 出厂默认策略：未绑定时的兜底，避免空指针与"未绑定即失控" */
        fun default() = PolicyPackage()
    }
}

// region ====== 枚举 ======

@Serializable
enum class SceneType { FAMILY, SCHOOL }

/** 通用开关态。FORCE_* 表示强制并锁定，用户无法自行修改。 */
@Serializable
enum class PolicySwitch { ALLOW, DISABLE, FORCE_ON, FORCE_OFF, LOCKED }

@Serializable
enum class AppListMode { BLACKLIST, WHITELIST }

@Serializable
enum class BlacklistAction { BLOCK_DIALOG, SILENT_CLOSE }

@Serializable
enum class DayType { WEEKDAY, WEEKEND, HOLIDAY }

@Serializable
enum class ScheduleAction { LOCK, UNLOCK }

@Serializable
enum class WebFilterMode { BLACKLIST, WHITELIST, OFF }

@Serializable
enum class KeywordLevel { LOW, MIDDLE, HIGH }

@Serializable
enum class KioskMode { SINGLE_APP, MULTI_APP }

@Serializable
enum class WatermarkPosition { TOP, BOTTOM, CENTER }

@Serializable
enum class TransportMode { MQTT, POLLING }

/** 终端当前管控权限等级，决定哪些策略能真正落地。 */
@Serializable
enum class ControlMode {
    /** 设备属主：能力最完整，可防卸载、静默安装、锁定系统设置 */
    DEVICE_OWNER,

    /** 工作资料属主（BYOD 场景） */
    PROFILE_OWNER,

    /** 仅激活了设备管理器：可锁屏、禁用相机（旧 API），能力有限 */
    DEVICE_ADMIN,

    /** 纯用户态：无障碍服务 + 使用情况访问，防绕过能力最弱 */
    LEGACY
}

// endregion

// region ====== 外设管控 ======

@Serializable
data class PeripheralPolicy(
    @SerialName("wifi") val wifi: PolicySwitch = PolicySwitch.ALLOW,
    @SerialName("bluetooth") val bluetooth: PolicySwitch = PolicySwitch.ALLOW,
    @SerialName("mobileData") val mobileData: PolicySwitch = PolicySwitch.ALLOW,
    @SerialName("gps") val gps: PolicySwitch = PolicySwitch.ALLOW,
    @SerialName("camera") val camera: PolicySwitch = PolicySwitch.ALLOW,
    @SerialName("microphone") val microphone: PolicySwitch = PolicySwitch.ALLOW,
    @SerialName("usbFileTransfer") val usbFileTransfer: PolicySwitch = PolicySwitch.ALLOW,
    @SerialName("nfc") val nfc: PolicySwitch = PolicySwitch.ALLOW,
    @SerialName("screenCapture") val screenCapture: PolicySwitch = PolicySwitch.ALLOW,
    @SerialName("screenRecord") val screenRecord: PolicySwitch = PolicySwitch.ALLOW,
    @SerialName("castScreen") val castScreen: PolicySwitch = PolicySwitch.ALLOW,
    @SerialName("hotspot") val hotspot: PolicySwitch = PolicySwitch.ALLOW
)

// endregion

// region ====== 系统设置锁定 ======

@Serializable
data class SystemLockPolicy(
    @SerialName("autoTime") val autoTime: PolicySwitch = PolicySwitch.FORCE_ON,
    @SerialName("autoTimeZone") val autoTimeZone: PolicySwitch = PolicySwitch.FORCE_ON,
    @SerialName("developerOptions") val developerOptions: PolicySwitch = PolicySwitch.DISABLE,
    @SerialName("usbDebug") val usbDebug: PolicySwitch = PolicySwitch.DISABLE,
    @SerialName("unknownSourceInstall") val unknownSourceInstall: PolicySwitch = PolicySwitch.DISABLE,
    @SerialName("safeBoot") val safeBoot: PolicySwitch = PolicySwitch.DISABLE,
    @SerialName("factoryReset") val factoryReset: PolicySwitch = PolicySwitch.DISABLE,
    @SerialName("statusBar") val statusBar: PolicySwitch = PolicySwitch.ALLOW,
    @SerialName("notificationPanel") val notificationPanel: PolicySwitch = PolicySwitch.ALLOW,
    @SerialName("screenBrightness") val screenBrightness: PolicySwitch = PolicySwitch.ALLOW,
    @SerialName("screenBrightnessValue") val screenBrightnessValue: Int = -1,
    @SerialName("screenOffTimeoutSec") val screenOffTimeoutSec: Int = 300
)

// endregion

// region ====== 应用管控 ======

@Serializable
data class AppPolicy(
    @SerialName("mode") val mode: AppListMode = AppListMode.BLACKLIST,
    @SerialName("whitelist") val whitelist: List<String> = emptyList(),
    @SerialName("blacklist") val blacklist: List<String> = emptyList(),
    @SerialName("blacklistAction") val blacklistAction: BlacklistAction = BlacklistAction.BLOCK_DIALOG,
    /** 系统应用的精细化管控：包名 -> 处置方式（ALLOW / BLOCK） */
    @SerialName("systemAppRules") val systemAppRules: Map<String, String> = emptyMap(),
    @SerialName("installPolicy") val installPolicy: InstallPolicy = InstallPolicy()
)

@Serializable
data class InstallPolicy(
    @SerialName("allowUnknownSource") val allowUnknownSource: Boolean = false,
    @SerialName("allowUserInstall") val allowUserInstall: Boolean = true,
    @SerialName("allowUserUninstall") val allowUserUninstall: Boolean = true
)

@Serializable
data class AppLimitPolicy(
    @SerialName("dailyTotalMinutes") val dailyTotalMinutes: Int = 0,
    @SerialName("rules") val rules: List<AppLimitRule> = emptyList(),
    /** 临近耗尽时提醒的剩余分钟数阈值 */
    @SerialName("reminderMinutes") val reminderMinutes: List<Int> = listOf(5, 1)
)

@Serializable
data class AppLimitRule(
    @SerialName("packageName") val packageName: String,
    @SerialName("dailyMinutes") val dailyMinutes: Int = 0,
    @SerialName("singleMinutes") val singleMinutes: Int = 0,
    @SerialName("dailyLaunchCount") val dailyLaunchCount: Int = 0,
    @SerialName("dayTypes") val dayTypes: List<DayType> = listOf(DayType.WEEKDAY, DayType.WEEKEND)
)

// endregion

// region ====== 上网管控 ======

@Serializable
data class WebPolicy(
    @SerialName("mode") val mode: WebFilterMode = WebFilterMode.OFF,
    @SerialName("blacklist") val blacklist: List<String> = emptyList(),
    @SerialName("whitelist") val whitelist: List<String> = emptyList(),
    @SerialName("keywords") val keywords: KeywordPolicy = KeywordPolicy(),
    @SerialName("allowBrowsers") val allowBrowsers: List<String> = emptyList(),
    @SerialName("blockPopup") val blockPopup: Boolean = false,
    @SerialName("blockDownload") val blockDownload: Boolean = false
)

@Serializable
data class KeywordPolicy(
    @SerialName("level") val level: KeywordLevel = KeywordLevel.MIDDLE,
    @SerialName("custom") val custom: List<String> = emptyList()
)

// endregion

// region ====== 时段锁机 ======

@Serializable
data class SchedulePolicy(
    @SerialName("timezone") val timezone: String = "Asia/Shanghai",
    @SerialName("rules") val rules: List<ScheduleRule> = emptyList()
)

@Serializable
data class ScheduleRule(
    @SerialName("dayTypes") val dayTypes: List<DayType> = listOf(DayType.WEEKDAY),
    /** "HH:mm" 24 小时制 */
    @SerialName("start") val start: String = "00:00",
    @SerialName("end") val end: String = "23:59",
    @SerialName("action") val action: ScheduleAction = ScheduleAction.UNLOCK
) {
    /** 起止时间换算为"当日 0 点起的分钟数"，便于比较 */
    fun startMinutes(): Int = toMinutes(start)
    fun endMinutes(): Int = toMinutes(end)

    /** 是否为跨零点区间（如 21:30–07:00 就寝时段） */
    fun crossesMidnight(): Boolean = endMinutes() <= startMinutes()

    companion object {
        fun toMinutes(hhmm: String): Int {
            val parts = hhmm.split(":")
            if (parts.size != 2) return 0
            val hour = parts[0].trim().toIntOrNull() ?: 0
            val minute = parts[1].trim().toIntOrNull() ?: 0
            return hour * 60 + minute
        }
    }
}

// endregion

// region ====== 护眼休息 ======

@Serializable
data class EyeCarePolicy(
    @SerialName("enabled") val enabled: Boolean = false,
    /** 连续使用多少分钟后强制休息 */
    @SerialName("continuousMinutes") val continuousMinutes: Int = 40,
    @SerialName("restMinutes") val restMinutes: Int = 10,
    @SerialName("forceLockDuringRest") val forceLockDuringRest: Boolean = true
)

// endregion

// region ====== Kiosk 纯净桌面 ======

@Serializable
data class KioskPolicy(
    @SerialName("enabled") val enabled: Boolean = false,
    @SerialName("mode") val mode: KioskMode = KioskMode.MULTI_APP,
    @SerialName("allowedPackages") val allowedPackages: List<String> = emptyList(),
    @SerialName("homePackage") val homePackage: String = "com.padguard.child",
    @SerialName("watermark") val watermark: WatermarkPolicy = WatermarkPolicy()
)

@Serializable
data class WatermarkPolicy(
    @SerialName("enabled") val enabled: Boolean = false,
    /** 支持占位符：{deviceSn} {studentName} {className} {deviceId} */
    @SerialName("content") val content: String = "",
    @SerialName("position") val position: WatermarkPosition = WatermarkPosition.TOP,
    @SerialName("opacity") val opacity: Float = 0.35f
)

// endregion

// region ====== 采集与监控 ======

@Serializable
data class MonitoringPolicy(
    @SerialName("heartbeatIntervalSec") val heartbeatIntervalSec: Int = 30,
    @SerialName("logUploadIntervalSec") val logUploadIntervalSec: Int = 300,
    /** 0 表示关闭定时截屏，仅响应远程指令 */
    @SerialName("screenshotIntervalSec") val screenshotIntervalSec: Int = 0,
    /** 0 表示关闭定时定位上报 */
    @SerialName("locationIntervalSec") val locationIntervalSec: Int = 0,
    @SerialName("collectAppUsage") val collectAppUsage: Boolean = true,
    @SerialName("collectUrlHistory") val collectUrlHistory: Boolean = true,
    @SerialName("collectHardware") val collectHardware: Boolean = true
)

// endregion

// region ====== 安全策略 ======

@Serializable
data class SecurityPolicy(
    @SerialName("antiUninstall") val antiUninstall: Boolean = true,
    @SerialName("antiForceStop") val antiForceStop: Boolean = true,
    @SerialName("antiClockTamper") val antiClockTamper: Boolean = true,
    @SerialName("blockRoot") val blockRoot: Boolean = true,
    @SerialName("logRetentionDays") val logRetentionDays: Int = 30
)

// endregion
