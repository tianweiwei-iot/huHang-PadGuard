package com.padguard.core.engine.enforcer

import android.os.Build
import android.os.UserManager
import android.provider.Settings
import com.padguard.core.common.Logger
import com.padguard.core.data.model.policy.PolicySwitch
import com.padguard.core.data.model.policy.SystemLockPolicy
import com.padguard.core.engine.admin.Capability
import com.padguard.core.engine.admin.DeviceAdminBridge
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 系统设置锁定 —— 防绕过的主战场。
 *
 * ## 绕过路径与对应封堵（这张表是本类存在的全部理由）
 * | 绕过手法 | 封堵手段 | 缺失后果 |
 * |---|---|---|
 * | 关自动对时 + 手改系统时间 | [DeviceAdminBridge.setAutoTimeEnforced] + DISALLOW_CONFIG_DATE_TIME | 时段锁/时长限制全部失效 |
 * | 开开发者选项 → USB 调试 → adb 卸载 | DISALLOW_DEBUGGING_FEATURES | 管控端可被一键摘除 |
 * | 安全模式启动（第三方应用不加载） | DISALLOW_SAFE_BOOT | 重启进安全模式即完全无管控 |
 * | 恢复出厂设置 | DISALLOW_FACTORY_RESET | 设备脱管 |
 * | 侧载改装版应用 | DISALLOW_INSTALL_UNKNOWN_SOURCES | 白名单形同虚设 |
 * | 添加新用户/访客切换 | DISALLOW_ADD_USER + DISALLOW_USER_SWITCH | 切到访客即绕过全部策略 |
 * | 长按电源关机躲开管控 | 只能靠 Kiosk + 状态栏禁用弱化 | 无法彻底封堵（见下方说明） |
 *
 * ## 无法封堵的部分（必须如实告知，不能给假承诺）
 * - **关机**：Android 没有任何 DO 级 API 能禁止长按电源关机。
 *   缓解手段是关机后重新开机会立即恢复管控（BootReceiver + 防卸载），
 *   并把"异常离线时长"作为告警上报，让家长/老师可感知。
 * - **拔卡断网**：断网只影响实时指令，本地缓存策略仍在执行（离线管控）。
 * - **Recovery 刷机**：属于物理层面，需靠 OEM 锁 + 采购环节控制。
 */
@Singleton
class SystemLockEnforcer @Inject constructor(
    private val admin: DeviceAdminBridge
) {

    fun apply(policy: SystemLockPolicy): EnforceReport {
        val report = EnforceReport()

        // ---------- 1. 时间篡改（最高优先级） ----------
        val enforceAutoTime = policy.autoTime == PolicySwitch.FORCE_ON
        if (admin.can(Capability.GLOBAL_SETTINGS)) {
            report.record("systemLock.autoTime=${policy.autoTime}", admin.setAutoTimeEnforced(enforceAutoTime))
        } else {
            // 没有 DO 就锁不住时间，这是降级模式下最严重的能力缺口，必须显式标注
            report.markUnsupported(
                "systemLock.autoTime",
                "需要 Device Owner；当前无法阻止手改系统时间，时段管控可被绕过"
            )
            // 退一步：DO/PO 都能加这条限制，虽然不如强制自动对时彻底
            if (admin.can(Capability.USER_RESTRICTIONS)) {
                report.record(
                    "systemLock.autoTime.fallback",
                    admin.setUserRestriction(UserManager.DISALLOW_CONFIG_DATE_TIME, enforceAutoTime)
                )
            }
        }

        if (!admin.can(Capability.USER_RESTRICTIONS)) {
            report.markUnsupported("systemLock.*", "需要 Device Owner / Profile Owner，系统设置锁定整体不可用")
            return report
        }

        // ---------- 2. 调试通道 ----------
        val blockDebug = policy.developerOptions.isBlocking() || policy.usbDebug.isBlocking()
        report.record(
            "systemLock.developerOptions/usbDebug=$blockDebug",
            admin.setUserRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES, blockDebug)
        )
        if (blockDebug && admin.can(Capability.GLOBAL_SETTINGS)) {
            // 已经打开的开发者选项不会因为加限制而自动关闭，需要主动清零
            report.record(
                "systemLock.adbDisabled",
                admin.setGlobalSetting(Settings.Global.ADB_ENABLED, "0")
            )
            report.record(
                "systemLock.devSettingsDisabled",
                admin.setGlobalSetting(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, "0")
            )
        }

        // ---------- 3. 安装来源 ----------
        val blockUnknownSource = policy.unknownSourceInstall.isBlocking()
        report.record(
            "systemLock.unknownSourceInstall=$blockUnknownSource",
            admin.setUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, blockUnknownSource)
        )
        if (blockUnknownSource && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ 的"全局"版本，覆盖所有用户；漏掉它则副用户仍可侧载
            report.record(
                "systemLock.unknownSourceGlobally",
                admin.setUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY, true)
            )
        }

        // ---------- 4. 安全模式 & 恢复出厂 ----------
        report.record(
            "systemLock.safeBoot=${policy.safeBoot}",
            admin.setUserRestriction(UserManager.DISALLOW_SAFE_BOOT, policy.safeBoot.isBlocking())
        )
        report.record(
            "systemLock.factoryReset=${policy.factoryReset}",
            admin.setUserRestriction(UserManager.DISALLOW_FACTORY_RESET, policy.factoryReset.isBlocking())
        )

        // ---------- 5. 多用户绕过（策略里没有对应字段，但必须默认封死） ----------
        // 这不是"额外功能"，而是补一个协议漏洞：切换到访客账户后本应用根本不运行，
        // 所有策略瞬间归零。因此只要拿到了权限，就无条件封掉。
        report.record("systemLock.addUser", admin.addUserRestriction(UserManager.DISALLOW_ADD_USER))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            report.record("systemLock.userSwitch", admin.addUserRestriction(UserManager.DISALLOW_USER_SWITCH))
        }

        // ---------- 6. 状态栏 / 通知栏 ----------
        val disableStatusBar = policy.statusBar.isBlocking() || policy.notificationPanel.isBlocking()
        if (admin.can(Capability.STATUS_BAR)) {
            report.record(
                "systemLock.statusBar=$disableStatusBar",
                admin.setStatusBarDisabled(disableStatusBar)
            )
            if (policy.statusBar.isBlocking() != policy.notificationPanel.isBlocking()) {
                report.markUnsupported(
                    "systemLock.statusBar.granularity",
                    "系统不支持分别禁用状态栏与通知面板，已按最严格项统一处理"
                )
            }
        } else if (disableStatusBar) {
            report.markUnsupported("systemLock.statusBar", "需要 Device Owner")
        }

        // ---------- 7. 亮度与息屏 ----------
        applyBrightness(policy, report)
        if (policy.screenOffTimeoutSec > 0) {
            report.record(
                "systemLock.screenOffTimeout=${policy.screenOffTimeoutSec}s",
                admin.setMaximumTimeToLock(policy.screenOffTimeoutSec * 1000L)
            )
        }

        Logger.i(TAG) { "system lock policy applied: $report" }
        return report
    }

    private fun applyBrightness(policy: SystemLockPolicy, report: EnforceReport) {
        if (policy.screenBrightness == PolicySwitch.ALLOW) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                admin.clearUserRestriction(UserManager.DISALLOW_CONFIG_BRIGHTNESS)
            }
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            report.markUnsupported("systemLock.screenBrightness", "需要 Android 9 及以上")
            return
        }
        // 先设定目标亮度，再上锁；顺序反了会因为限制生效而改不动
        if (policy.screenBrightnessValue in 0..255 && admin.can(Capability.GLOBAL_SETTINGS)) {
            // 固定亮度前必须先关自动亮度，否则传感器会立刻把值改回去
            admin.setSystemSetting(
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL.toString()
            )
            report.record(
                "systemLock.brightnessValue=${policy.screenBrightnessValue}",
                admin.setSystemSetting(Settings.System.SCREEN_BRIGHTNESS, policy.screenBrightnessValue.toString())
            )
        }
        report.record(
            "systemLock.screenBrightness=${policy.screenBrightness}",
            admin.addUserRestriction(UserManager.DISALLOW_CONFIG_BRIGHTNESS)
        )
    }

    companion object {
        private const val TAG = "SystemLockEnforcer"
    }
}
