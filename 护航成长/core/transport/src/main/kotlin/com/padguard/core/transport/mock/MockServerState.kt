package com.padguard.core.transport.mock

import com.padguard.core.data.model.policy.AppLimitPolicy
import com.padguard.core.data.model.policy.AppLimitRule
import com.padguard.core.data.model.policy.AppListMode
import com.padguard.core.data.model.policy.AppPolicy
import com.padguard.core.data.model.policy.BlacklistAction
import com.padguard.core.data.model.policy.DayType
import com.padguard.core.data.model.policy.EyeCarePolicy
import com.padguard.core.data.model.policy.InstallPolicy
import com.padguard.core.data.model.policy.KeywordLevel
import com.padguard.core.data.model.policy.KeywordPolicy
import com.padguard.core.data.model.policy.KioskMode
import com.padguard.core.data.model.policy.KioskPolicy
import com.padguard.core.data.model.policy.MonitoringPolicy
import com.padguard.core.data.model.policy.PeripheralPolicy
import com.padguard.core.data.model.policy.PolicyPackage
import com.padguard.core.data.model.policy.PolicySwitch
import com.padguard.core.data.model.policy.SceneType
import com.padguard.core.data.model.policy.ScheduleAction
import com.padguard.core.data.model.policy.SchedulePolicy
import com.padguard.core.data.model.policy.ScheduleRule
import com.padguard.core.data.model.policy.SecurityPolicy
import com.padguard.core.data.model.policy.SystemLockPolicy
import com.padguard.core.data.model.policy.WatermarkPolicy
import com.padguard.core.data.model.policy.WatermarkPosition
import com.padguard.core.data.model.policy.WebFilterMode
import com.padguard.core.data.model.policy.WebPolicy

/**
 * Mock 服务端的固定资产：预置策略包与常量。
 *
 * 之所以把 Mock 策略写得"很满"而不是全用默认值：
 * 默认值下大部分开关都是 ALLOW / 空列表，策略引擎的绝大多数分支根本不会被执行，
 * Mock 联调就只能验证"链路通不通"，验证不了"策略落地对不对"。
 * 这里的两套预置策略（家庭场景 / 校园场景）覆盖了 P0 需要落地的全部管控项。
 */
internal object MockServerState {

    /**
     * Mock 服务端与终端共享的 HMAC 密钥。
     *
     * 关键设计：Mock 不跳过签名，而是用这把密钥**真实签发**每条指令，
     * 终端侧 [com.padguard.core.transport.CommandGate] 走的是与生产完全相同的校验代码。
     * 这样"服务端签名字段顺序与终端 signingPayload 不一致"这类协议级 BUG
     * 会在 Mock 阶段就暴露，而不是等真服务端上线才发现指令全部被拒。
     */
    const val HMAC_SECRET = "padguard-mock-hmac-secret-do-not-ship"

    const val DEVICE_TOKEN_PREFIX = "mock-token-"
    const val TENANT_ID = "mock-tenant"

    /** 触发"绑定码已过期"错误分支的测试码，便于验证 UI 错误提示 */
    const val BIND_CODE_EXPIRED = "000000"

    /** 触发"绑定码已被其他设备占用"错误分支的测试码 */
    const val BIND_CODE_CONFLICT = "111111"

    /** 家庭场景：允许列表宽松，主要靠时段锁 + 时长限制 */
    fun familyPolicy(deviceId: String, version: Int, now: Long): PolicyPackage = PolicyPackage(
        version = version,
        deviceId = deviceId,
        updatedAt = now,
        scene = SceneType.FAMILY,
        peripheral = PeripheralPolicy(
            wifi = PolicySwitch.ALLOW,
            bluetooth = PolicySwitch.ALLOW,
            mobileData = PolicySwitch.ALLOW,
            gps = PolicySwitch.FORCE_ON,
            camera = PolicySwitch.ALLOW,
            microphone = PolicySwitch.ALLOW,
            // 家庭场景禁 USB 传输：防止把管控 APK 拷出去分析或侧载绕过应用
            usbFileTransfer = PolicySwitch.DISABLE,
            nfc = PolicySwitch.ALLOW,
            screenCapture = PolicySwitch.ALLOW,
            screenRecord = PolicySwitch.ALLOW,
            castScreen = PolicySwitch.ALLOW,
            hotspot = PolicySwitch.DISABLE
        ),
        systemLock = SystemLockPolicy(
            // 强制自动对时是防时间篡改的第一道闸：关掉自动对时就能手改系统时间绕过时段锁
            autoTime = PolicySwitch.FORCE_ON,
            autoTimeZone = PolicySwitch.FORCE_ON,
            developerOptions = PolicySwitch.DISABLE,
            usbDebug = PolicySwitch.DISABLE,
            unknownSourceInstall = PolicySwitch.DISABLE,
            safeBoot = PolicySwitch.DISABLE,
            factoryReset = PolicySwitch.DISABLE,
            statusBar = PolicySwitch.ALLOW,
            notificationPanel = PolicySwitch.ALLOW,
            screenBrightness = PolicySwitch.ALLOW,
            screenOffTimeoutSec = 300
        ),
        app = AppPolicy(
            mode = AppListMode.BLACKLIST,
            blacklist = listOf(
                "com.tencent.tmgp.sgame",       // 王者荣耀
                "com.miHoYo.Yuanshen",          // 原神
                "com.ss.android.ugc.aweme",     // 抖音
                "com.tencent.tmgp.pubgmhd"      // 和平精英
            ),
            blacklistAction = BlacklistAction.BLOCK_DIALOG,
            systemAppRules = mapOf(
                "com.android.settings" to "ALLOW",
                "com.android.vending" to "BLOCK"
            ),
            installPolicy = InstallPolicy(
                allowUnknownSource = false,
                allowUserInstall = false,
                allowUserUninstall = false
            )
        ),
        appLimit = AppLimitPolicy(
            dailyTotalMinutes = 180,
            rules = listOf(
                AppLimitRule(
                    packageName = "com.tencent.mm",
                    dailyMinutes = 30,
                    singleMinutes = 10,
                    dailyLaunchCount = 20,
                    dayTypes = listOf(DayType.WEEKDAY, DayType.WEEKEND)
                ),
                AppLimitRule(
                    packageName = "com.bilibili.app.in",
                    dailyMinutes = 40,
                    singleMinutes = 20,
                    dayTypes = listOf(DayType.WEEKEND)
                )
            ),
            reminderMinutes = listOf(10, 5, 1)
        ),
        web = WebPolicy(
            mode = WebFilterMode.BLACKLIST,
            blacklist = listOf("*.gamble.com", "*.adult-example.com"),
            keywords = KeywordPolicy(level = KeywordLevel.MIDDLE, custom = listOf("外挂", "刷钻")),
            allowBrowsers = listOf("com.android.chrome"),
            blockPopup = true,
            blockDownload = true
        ),
        schedule = SchedulePolicy(
            timezone = "Asia/Shanghai",
            rules = listOf(
                // 就寝时段跨零点，用于验证 crossesMidnight 分支
                ScheduleRule(
                    dayTypes = listOf(DayType.WEEKDAY),
                    start = "21:30",
                    end = "06:30",
                    action = ScheduleAction.LOCK
                ),
                ScheduleRule(
                    dayTypes = listOf(DayType.WEEKDAY),
                    start = "08:00",
                    end = "16:30",
                    action = ScheduleAction.LOCK
                ),
                ScheduleRule(
                    dayTypes = listOf(DayType.WEEKEND),
                    start = "22:30",
                    end = "07:30",
                    action = ScheduleAction.LOCK
                )
            )
        ),
        eyeCare = EyeCarePolicy(
            enabled = true,
            continuousMinutes = 40,
            restMinutes = 10,
            forceLockDuringRest = true
        ),
        kiosk = KioskPolicy(enabled = false),
        monitoring = MonitoringPolicy(
            heartbeatIntervalSec = 30,
            logUploadIntervalSec = 120,
            screenshotIntervalSec = 0,
            locationIntervalSec = 300,
            collectAppUsage = true,
            collectUrlHistory = true,
            collectHardware = true
        ),
        security = SecurityPolicy(
            antiUninstall = true,
            antiForceStop = true,
            antiClockTamper = true,
            blockRoot = true,
            logRetentionDays = 30
        )
    )

    /**
     * 校园场景：白名单 + Kiosk 纯净桌面 + 水印。
     * 用于验证"白名单模式 + LockTask"这条与家庭场景完全不同的执行分支。
     */
    fun schoolPolicy(deviceId: String, version: Int, now: Long): PolicyPackage = PolicyPackage(
        version = version,
        deviceId = deviceId,
        updatedAt = now,
        scene = SceneType.SCHOOL,
        peripheral = PeripheralPolicy(
            wifi = PolicySwitch.LOCKED,
            bluetooth = PolicySwitch.DISABLE,
            mobileData = PolicySwitch.DISABLE,
            gps = PolicySwitch.FORCE_ON,
            camera = PolicySwitch.DISABLE,
            microphone = PolicySwitch.DISABLE,
            usbFileTransfer = PolicySwitch.DISABLE,
            nfc = PolicySwitch.DISABLE,
            screenCapture = PolicySwitch.DISABLE,
            screenRecord = PolicySwitch.DISABLE,
            castScreen = PolicySwitch.DISABLE,
            hotspot = PolicySwitch.DISABLE
        ),
        systemLock = SystemLockPolicy(
            autoTime = PolicySwitch.FORCE_ON,
            autoTimeZone = PolicySwitch.FORCE_ON,
            developerOptions = PolicySwitch.DISABLE,
            usbDebug = PolicySwitch.DISABLE,
            unknownSourceInstall = PolicySwitch.DISABLE,
            safeBoot = PolicySwitch.DISABLE,
            factoryReset = PolicySwitch.DISABLE,
            statusBar = PolicySwitch.DISABLE,
            notificationPanel = PolicySwitch.DISABLE,
            screenBrightness = PolicySwitch.LOCKED,
            screenBrightnessValue = 160,
            screenOffTimeoutSec = 120
        ),
        app = AppPolicy(
            mode = AppListMode.WHITELIST,
            whitelist = listOf(
                "com.padguard.child",
                "com.android.settings",
                "com.example.classroom",
                "com.youdao.dict"
            ),
            blacklistAction = BlacklistAction.SILENT_CLOSE,
            installPolicy = InstallPolicy(
                allowUnknownSource = false,
                allowUserInstall = false,
                allowUserUninstall = false
            )
        ),
        appLimit = AppLimitPolicy(dailyTotalMinutes = 0),
        web = WebPolicy(
            mode = WebFilterMode.WHITELIST,
            whitelist = listOf("*.edu.cn", "classroom.example.com"),
            allowBrowsers = listOf("com.android.chrome"),
            blockPopup = true,
            blockDownload = true
        ),
        schedule = SchedulePolicy(timezone = "Asia/Shanghai"),
        eyeCare = EyeCarePolicy(enabled = false),
        kiosk = KioskPolicy(
            enabled = true,
            mode = KioskMode.MULTI_APP,
            allowedPackages = listOf("com.padguard.child", "com.example.classroom", "com.youdao.dict"),
            homePackage = "com.padguard.child",
            watermark = WatermarkPolicy(
                enabled = true,
                content = "{className} {studentName} {deviceSn}",
                position = WatermarkPosition.BOTTOM,
                opacity = 0.3f
            )
        ),
        monitoring = MonitoringPolicy(
            heartbeatIntervalSec = 20,
            logUploadIntervalSec = 60,
            screenshotIntervalSec = 600,
            locationIntervalSec = 0
        ),
        security = SecurityPolicy(logRetentionDays = 15)
    )
}
