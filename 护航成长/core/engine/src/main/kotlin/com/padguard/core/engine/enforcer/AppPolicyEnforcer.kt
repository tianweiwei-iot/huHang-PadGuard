package com.padguard.core.engine.enforcer

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import com.padguard.core.common.Logger
import com.padguard.core.data.model.policy.AppListMode
import com.padguard.core.data.model.policy.AppPolicy
import com.padguard.core.engine.admin.Capability
import com.padguard.core.engine.admin.DeviceAdminBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用黑白名单管控。
 *
 * ## 手段选择：为什么用「挂起」而不是「隐藏」或「杀进程」
 * | 手段 | 效果 | 问题 |
 * |---|---|---|
 * | setPackagesSuspended（采用） | 图标变灰、点击弹系统对话框、后台被冻结 | 需 DO/PO |
 * | setApplicationHidden | 应用彻底消失 | 孩子以为应用被删了、更新会失败；仅用于系统应用 |
 * | killBackgroundProcesses | 只杀后台 | 前台立刻能重开，形同虚设 |
 * | 无障碍服务检测后返回桌面 | 任何权限都能用 | 有可见的启动窗口，且能被"快速切换"绕过；仅降级模式兜底 |
 *
 * ## 最危险的坑：白名单模式把设备变砖
 * 白名单模式要挂起"所有不在名单里的应用"。如果不做保护，
 * 输入法、拨号器、系统 UI 会一起被挂起 —— 结果是**打不了字、拨不了急救电话、设备不可用**，
 * 而且因为管控端也在这台设备上，往往连解除操作都做不了。
 *
 * 因此本类维护三层保护：
 * 1. 静态保护名单 [PROTECTED_PACKAGES]（系统 UI、电话、设置等）；
 * 2. **动态解析**当前默认输入法与默认拨号器（不同 ROM 包名不同，硬编码必然漏）；
 * 3. 只处理「有启动入口的应用」，不动无 Launcher Activity 的后台组件与系统服务。
 */
@Singleton
class AppPolicyEnforcer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val admin: DeviceAdminBridge
) {

    private val packageManager: PackageManager get() = context.packageManager

    /** 上一轮被挂起的包，用于策略变更时精准解挂（避免留下永久变灰的应用） */
    private var lastSuspended: Set<String> = emptySet()

    fun apply(policy: AppPolicy): EnforceReport {
        val report = EnforceReport()

        applyInstallPolicy(policy, report)
        protectSelf(report)

        if (!admin.can(Capability.SUSPEND_PACKAGES)) {
            report.markUnsupported(
                "app.list",
                "需要 Device Owner / Profile Owner；当前将退化为无障碍服务事后拦截"
            )
            return report
        }

        val protectedSet = buildProtectedSet()
        val launchable = launchablePackages()

        val target = when (policy.mode) {
            AppListMode.BLACKLIST -> policy.blacklist.toSet() - protectedSet
            AppListMode.WHITELIST -> (launchable - policy.whitelist.toSet()) - protectedSet
        }.toMutableSet()

        // systemAppRules 做精细化覆盖：显式 BLOCK 的加入、显式 ALLOW 的移除
        policy.systemAppRules.forEach { (pkg, rule) ->
            when (rule.uppercase()) {
                "BLOCK" -> if (pkg !in protectedSet) target += pkg
                "ALLOW" -> target -= pkg
            }
        }

        // 只处理已安装的包，未安装的挂起调用会白白失败并污染报告
        val installedTarget = target.filter { isInstalled(it) }

        // 先解挂"上一轮挂起、本轮不该挂"的，再挂本轮的 —— 顺序反了会有一瞬间全部可用
        val toRelease = (lastSuspended - installedTarget.toSet()).filter { isInstalled(it) }
        if (toRelease.isNotEmpty()) {
            val result = admin.setPackagesSuspended(toRelease, false)
            report.note("app.release=${result.succeeded.size}")
            if (result.failed.isNotEmpty()) {
                report.markFailed("app.release", "解挂失败: ${result.failed.take(5)}")
            }
        }

        if (installedTarget.isNotEmpty()) {
            val result = admin.setPackagesSuspended(installedTarget, true)
            report.note("app.suspend(${policy.mode})=${result.succeeded.size}")
            if (result.failed.isNotEmpty()) {
                // 系统保护的包无法挂起属于正常现象，记为降级而非失败
                report.markUnsupported("app.suspend", "系统拒绝挂起: ${result.failed.take(5)}")
            }
            lastSuspended = result.succeeded.toSet()
        } else {
            lastSuspended = emptySet()
        }

        Logger.i(TAG) {
            "app policy applied: mode=${policy.mode}, suspended=${lastSuspended.size}, " +
                "released=${toRelease.size}, protected=${protectedSet.size}"
        }
        return report
    }

    /** 安装/卸载权限。防卸载的第一道锁就在这里。 */
    private fun applyInstallPolicy(policy: AppPolicy, report: EnforceReport) {
        if (!admin.can(Capability.USER_RESTRICTIONS)) {
            report.markUnsupported("app.installPolicy", "需要 Device Owner / Profile Owner")
            return
        }
        val install = policy.installPolicy
        report.record(
            "app.allowUserInstall=${install.allowUserInstall}",
            admin.setUserRestriction(UserManager.DISALLOW_INSTALL_APPS, !install.allowUserInstall)
        )
        report.record(
            "app.allowUserUninstall=${install.allowUserUninstall}",
            admin.setUserRestriction(UserManager.DISALLOW_UNINSTALL_APPS, !install.allowUserUninstall)
        )
        report.record(
            "app.allowUnknownSource=${install.allowUnknownSource}",
            admin.setUserRestriction(
                UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
                !install.allowUnknownSource
            )
        )
    }

    /**
     * 防卸载自身。
     *
     * 三重保护，缺一不可：
     * 1. `setUninstallBlocked(self)` —— 拦住设置里的卸载入口；
     * 2. DISALLOW_UNINSTALL_APPS —— 拦住全局卸载能力（上一步已处理）；
     * 3. Device Owner 身份本身 —— DO 应用在系统层面无法被卸载，
     *    这是最硬的一层，也是必须走 DO 部署的核心原因。
     *
     * 即使策略里 `antiUninstall=false`，也**不解除**对自身的卸载保护：
     * 解绑必须由服务端授权流程完成，不能因为一次策略下发失误就让终端可被随意卸载。
     */
    private fun protectSelf(report: EnforceReport) {
        if (!admin.can(Capability.BLOCK_UNINSTALL)) {
            report.markUnsupported("app.antiUninstall", "需要 Device Owner / Profile Owner")
            return
        }
        report.record("app.antiUninstall(self)", admin.setUninstallBlocked(context.packageName, true))
    }

    /** 拿到所有"桌面上能点开"的应用，白名单模式据此计算差集 */
    private fun launchablePackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
        } else {
            null
        }
        val resolved = runCatching {
            if (flags != null) {
                packageManager.queryIntentActivities(intent, flags)
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            }
        }.getOrNull().orEmpty()
        return resolved.mapNotNull { it.activityInfo?.packageName }.toSet()
    }

    /**
     * 构建保护名单。
     *
     * 动态解析默认输入法与拨号器是关键：小米/华为/OPPO 的输入法包名各不相同，
     * 硬编码列表在换机型时必然遗漏，而遗漏的后果是设备无法输入 —— 直接不可用。
     */
    private fun buildProtectedSet(): Set<String> {
        val set = PROTECTED_PACKAGES.toMutableSet()
        set += context.packageName

        // 默认输入法（丢了就打不了字）
        runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')
                ?.takeIf { it.isNotBlank() }
                ?.let { set += it }
        }

        // 已启用的全部输入法一并保护，避免默认输入法被系统切换后无可用键盘
        runCatching {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
            imm?.enabledInputMethodList?.forEach { set += it.packageName }
        }

        // 默认拨号器（急救电话通道，任何情况下不得挂起）
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
                if (roleManager?.isRoleAvailable(RoleManager.ROLE_DIALER) == true) {
                    // RoleManager 没有公开 API 直接取持有者，退回 telecom 默认拨号器
                    val tm = context.getSystemService(Context.TELECOM_SERVICE)
                        as? android.telecom.TelecomManager
                    tm?.defaultDialerPackage?.let { set += it }
                }
            } else {
                val tm = context.getSystemService(Context.TELECOM_SERVICE)
                    as? android.telecom.TelecomManager
                tm?.defaultDialerPackage?.let { set += it }
            }
        }

        // 当前 Launcher：本应用尚未成为默认桌面时，挂起系统桌面会导致无处可回
        runCatching {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            @Suppress("DEPRECATION")
            packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
                ?.let { if (it != context.packageName) set += it }
        }

        return set
    }

    private fun isInstalled(packageName: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getApplicationInfo(
                packageName,
                PackageManager.ApplicationInfoFlags.of(0L)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(packageName, 0)
        }
        true
    }.getOrDefault(false)

    /** 是否为系统应用（用于 UI 展示与差异化处理） */
    fun isSystemApp(packageName: String): Boolean = runCatching {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(packageName, 0)
        }
        info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }.getOrDefault(false)

    companion object {
        private const val TAG = "AppPolicyEnforcer"

        /**
         * 永不挂起的包。
         *
         * 判断原则：挂起后会导致「设备不可用」或「无法取回控制权」的一律保护。
         * 注意 `com.android.settings` 也在名单里 —— 虽然管控上希望封掉设置，
         * 但封设置要靠用户限制（精准封住具体开关），而不是把整个设置挂起，
         * 否则连家长都无法进入设置做任何补救操作。
         */
        private val PROTECTED_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.emergency",
            "com.android.providers.settings",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.keychain",
            "com.android.inputmethod.latin",
            "com.google.android.inputmethod.latin",
            // 国内主流 ROM 的关键组件
            "com.miui.securitycenter",
            "com.huawei.systemmanager",
            "com.coloros.safecenter",
            "com.vivo.permissionmanager"
        )
    }
}
