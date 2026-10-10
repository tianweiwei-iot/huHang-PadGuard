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
    /**
     * 护眼滤镜（蓝光过滤）。
     *
     * 与 [eyeCare] 是两件事：**eyeCare 管"用多久要休息"（时长），这里管"屏幕暖不暖"（强度）**。
     * 家长端「护眼」开关对应后者，孩子端以悬浮暖色滤镜实现，不依赖 Device Owner。
     */
    @SerialName("eyeProtection") val eyeProtection: EyeProtectionPolicy = EyeProtectionPolicy(),
    @SerialName("kiosk") val kiosk: KioskPolicy = KioskPolicy(),
    @SerialName("monitoring") val monitoring: MonitoringPolicy = MonitoringPolicy(),
    @SerialName("security") val security: SecurityPolicy = SecurityPolicy(),
    /**
     * 未成年人模式（分龄合规底座）。
     *
     * 开启后由 [com.padguard.core.engine.enforcer.MinorModeEnforcer]
     * 把分龄基线派生到 [appLimit] / [eyeCare] / [schedule] 上，并承担夜间宵禁判定。
     * 关闭时这些对象回归家长自行配置的值。
     */
    @SerialName("minorMode") val minorMode: MinorModePolicy = MinorModePolicy(),
    /**
     * 操作密码闸门配置（C 端软拦截方案）。
     *
     * 家长在家长端设置操作密码后，服务端把 `SHA-256(明文)` 的 Base64 哈希随策略包下发，
     * 孩子端 [com.padguard.child.guard.GuardPasswordManager] 只存哈希、明文永不出网。
     * 受保护界面（系统设置 / 卸载 / 未知来源安装）的拦截由
     * [com.padguard.child.guard.OperationGuardAccessibilityService] 实施。
     *
     * 约定：
     * - `passwordHash` 非空 → 启用密码并落库该哈希；
     * - `clear` 为真 → 清除密码（设备解绑 / 家长主动关闭）；
     * - 两者皆空/假 → 不改动当前状态（避免策略合并时误清空）。
     */
    @SerialName("guard") val guard: GuardPolicy = GuardPolicy()
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
            eyeProtection = incoming.eyeProtection,
            kiosk = incoming.kiosk,
            monitoring = incoming.monitoring,
            security = incoming.security,
            minorMode = incoming.minorMode,
            guard = incoming.guard
        )
    }

    /**
     * 「时间管控」页的休息设置 → [EyeCarePolicy] 的兜底派生。
     *
     * 统一在**读取策略**时做（见 `PolicyRepository`），而不是在某条执行链路上做：
     * 端上有多处消费 `eyeCare`（引擎周期判定、首页倒计时、护眼页开关回显），
     * 只在一处派生会出现"引擎提醒了、UI 却显示未开启"的口径不一致。
     */
    fun deriveTimeControl(): PolicyPackage {
        val derived = appLimit.deriveEyeCare(eyeCare)
        return if (derived == eyeCare) this else copy(eyeCare = derived)
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
    @SerialName("installPolicy") val installPolicy: InstallPolicy = InstallPolicy(),
    /**
     * 非 DO 降级模式下，打开任意应用是否要求输入管理密码（全量启动闸门，不仅限于受管控应用）。
     *
     * ## 为什么单独一个开关
     * 有 Device Owner 时管控由系统完成（隐藏/挂起），根本碰不到"打开"这一步；
     * 只有在**没有 DO** 的存量平板上，本产品才退化为"无障碍服务 + 家长密码"来兜底。
     * 而"拦安装/卸载"与"拦启动"的代价完全不同：
     * - 拦安装/卸载：只在孩子主动改动系统时触发，拦截面小、几乎不打扰；
     * - 拦启动：白名单模式下**每开一个不在名单里的应用都要输密码**，
     *   对是"管控孩子平板"还是"只挡游戏"两种需求差别巨大。
     *
     * 因此默认关闭（只拦安装/卸载），由家长按需开启 —— 不能替家长决定使用强度。
     */
    @SerialName("requirePasswordOnLaunch") val requirePasswordOnLaunch: Boolean = false
)

@Serializable
data class InstallPolicy(
    @SerialName("allowUnknownSource") val allowUnknownSource: Boolean = false,
    @SerialName("allowUserInstall") val allowUserInstall: Boolean = true,
    @SerialName("allowUserUninstall") val allowUserUninstall: Boolean = true,
    /**
     * 无 DO 降级模式下，安装确认页是否需要管理密码（默认 true = 必须）。
     *
     * ## 与 [allowUserInstall] 的关系
     * 两个字段语义**完全不同**、服务于两条独立路径，切勿混用：
     * - [allowUserInstall]：设备所有者(DO/PO) 路径，经系统 `DISALLOW_INSTALL_APPS` 硬性限制；
     * - 本字段：无 DO 存量平板，经「无障碍服务 + 自家密码框」软拦截安装确认页。
     *
     * 二者默认都允许管控生效（[allowUserInstall]=true 表示「不禁用安装」，
     * requirePasswordOnInstall=true 表示「确认页要密码」），但绝不能互相替代。
     * 服务端已把本字段在 [AppPolicy] 策略包中单独下发，家长端开关也只对它生效。
     */
    @SerialName("requirePasswordOnInstall") val requirePasswordOnInstall: Boolean = true,
    @SerialName("requirePasswordOnUninstall") val requirePasswordOnUninstall: Boolean = true
)

@Serializable
data class AppLimitPolicy(
    @SerialName("enabled") val enabled: Boolean = true,
    @SerialName("dailyTotalMinutes") val dailyTotalMinutes: Int = 0,
    /** 工作日（周一~周五）每日总额度，分钟；>0 时优先于 [dailyTotalMinutes] */
    @SerialName("weekdayTotalMinutes") val weekdayTotalMinutes: Int = 0,
    /** 周末（周六、周日）每日总额度，分钟；>0 时优先于 [dailyTotalMinutes] */
    @SerialName("weekendTotalMinutes") val weekendTotalMinutes: Int = 0,
    @SerialName("rules") val rules: List<AppLimitRule> = emptyList(),
    /** 临近耗尽时提醒的剩余分钟数阈值 */
    @SerialName("reminderMinutes") val reminderMinutes: List<Int> = listOf(5, 1),
    /**
     * 连续使用多少分钟后提醒休息（家长端「时间管控」页的「休息提醒」）。
     * 0 = 不提醒。
     */
    @SerialName("restAfterMinutes") val restAfterMinutes: Int = 0,
    /** 每次休息多少分钟（家长端「每次休息时长」）。0 = 不提醒。 */
    @SerialName("restDurationMinutes") val restDurationMinutes: Int = 0
) {
    /**
     * 当日真正生效的总时长上限。
     *
     * 服务端策略包会同时下发 `dailyTotalMinutes` / `weekdayTotalMinutes` / `weekendTotalMinutes`
     * 三项，而家长端「平板使用时间设置」保存的正是**工作日/周末**这两项。
     * 若这里只认 dailyTotalMinutes，家长改的额度就永远落不到设备上 ——
     * 表现为"时间管控设置不了 / 改了没反应"，设备还会按旧额度继续锁屏。
     * 未配置对应档位（0）时回退到 dailyTotalMinutes，保持与老策略包兼容。
     */
    fun quotaFor(isWeekend: Boolean): Int {
        // 家长端「启用时间管控」总开关关闭时，任何时长额度都不生效，
        // 孩子端按"无限制"处理；否则家长在控制端关掉开关后设备仍按旧额度锁屏，
        // 表现为"时间管控关不掉"，与开关语义直接冲突。
        if (!enabled) return 0
        return when {
            isWeekend && weekendTotalMinutes > 0 -> weekendTotalMinutes
            !isWeekend && weekdayTotalMinutes > 0 -> weekdayTotalMinutes
            else -> dailyTotalMinutes
        }
    }

    /**
     * 由「休息提醒 / 每次休息时长」派生护眼休息策略（[EyeCarePolicy]）。
     *
     * ## 为什么要派生而不是各自独立
     * 这两项在家长端属于「时间管控」页，随 `appLimit` 段下发；而端上真正执行
     * "连续用满 N 分钟 → 强制休息 M 分钟（可锁屏）"的是 [EyeCarePolicy] +
     * `EyeCareEnforcer`，它由 `eyeCare` 段驱动。两条链路不同源，
     * 于是这两项长期"下发成功但设备无感"。
     * 派生把两者接起来：服务端已同步写入 eyeCare 段时二者一致（不重复生效），
     * 老策略包 / 服务端未同步时由这里兜底，保证升级后立刻生效。
     *
     * ## 何时不覆盖既有 eyeCare
     * `eyeCare.enabled` 为真且 `continuousMinutes > 0` 时视为家长已有明确配置，
     * 原样返回 —— 避免把分龄基线（未成年人模式派生的更严格值）覆盖掉。
     */
    fun deriveEyeCare(current: EyeCarePolicy): EyeCarePolicy {
        if (restAfterMinutes <= 0 || restDurationMinutes <= 0) return current
        if (current.enabled && current.continuousMinutes > 0) return current
        return EyeCarePolicy(
            enabled = true,
            continuousMinutes = restAfterMinutes,
            restMinutes = restDurationMinutes,
            // 休息期间强制锁屏：否则提醒可以被立刻划掉，家长要的强制休息落空
            forceLockDuringRest = true
        )
    }
}

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
    @SerialName("blockDownload") val blockDownload: Boolean = false,
    /**
     * 搜索引擎强制安全搜索（服务端 P2 内容管控下发，对应 `web.safeSearch`）。
     * 开启后由 URL 重写通道（无障碍读地址栏 / VpnService）对主流搜索引擎注入 safe 参数；
     * 设备侧无法独立验证命中与否，仅在报告中如实标注，不宣称拦截页面正文。
     */
    @SerialName("safeSearch") val safeSearch: Boolean = false,
    /**
     * 按分类屏蔽的 url_categories.id 列表（服务端下发，已含默认屏蔽项）。
     * 设备端据此对命中分类的已知域名做域名级封锁（本地分类→域名映射为兜底，
     * 与云端分类 API 配合可覆盖更广；实际拦截见 [com.padguard.core.engine.enforcer.WebEnforcer]）。
     */
    @SerialName("blockedCategories") val blockedCategories: List<String> = emptyList()
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
    @SerialName("enabled") val enabled: Boolean = true,
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

/**
 * 护眼滤镜（蓝光过滤）策略。
 *
 * 实现方式是孩子端加一层**悬浮暖色遮罩**（见 `EyeProtectionOverlay`）：
 * - 不需要 Device Owner —— 本项目绝大多数设备跑在无 DO 的降级态；
 * - 需要 SYSTEM_ALERT_WINDOW（已在一次性授权的 AppOps 清单里收走）；
 * - 系统级的"护眼模式/色温"在 Android 上没有公开 API 可调，第三方 ROM 各不相同，
 *   悬浮滤镜是唯一跨机型一致的稳妥做法。
 *
 * `level` 1~5 两端同源（服务端 coerceIn(1,5)），越界值会被服务端夹住，
 * 端上再兜一层是为了兼容老版本服务端 / 本地缓存被改写的极端情况。
 */
@Serializable
data class EyeProtectionPolicy(
    @SerialName("enabled") val enabled: Boolean = false,
    @SerialName("level") val level: Int = 3
) {
    /**
     * 滤镜浓度：0f（无）~ 0.5f（最深）。
     *
     * 上限刻意停在 0.5 —— 遮罩再深就会明显压暗屏幕、影响正常阅读，
     * 家长抱怨"开了护眼孩子看不清"多半是这个原因，不是功能没生效。
     */
    val tintAlpha: Float
        get() = if (!enabled) 0f else (level.coerceIn(1, 5) - 1) / 8f + 0.12f
}

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

// region ====== 操作密码闸门 ======

/**
 * 操作密码闸门配置（随策略包下发的子对象）。
 *
 * 字段语义见 [PolicyPackage.guard] 注释。哈希口径与服务端一致：`SHA-256(明文)` 的 Base64，
 * 不引入盐（否则跨设备校验不一致）。
 */
@Serializable
data class GuardPolicy(
    @SerialName("passwordHash") val passwordHash: String = "",
    @SerialName("clear") val clear: Boolean = false
)

// endregion
