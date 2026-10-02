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
import com.padguard.core.engine.admin.OpResult
import com.padguard.core.engine.lock.LockController
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用黑白名单管控。
 *
 * ## 手段选择：为什么以「隐藏」为主、「挂起」兜底
 * | 手段 | 效果 | 问题 |
 * |---|---|---|
 * | setApplicationHidden（采用） | 图标从桌面消失、无法启动 —— 满足"被管控应用孩子端不可见" | 需 DO/PO；被隐藏期间应用无法更新 |
 * | setPackagesSuspended（降级） | 图标变灰、点击弹系统对话框、后台被冻结 | 需 DO/PO；仍看得见，不满足"不可见" |
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
    private val admin: DeviceAdminBridge,
    private val lockController: LockController
) {

    private val packageManager: PackageManager get() = context.packageManager

    /** 上一轮被挂起的包，用于策略变更时精准解挂（避免留下永久变灰的应用） */
    private var lastSuspended: Set<String> = emptySet()

    fun apply(policy: AppPolicy): EnforceReport {
        val report = EnforceReport()

        applyInstallPolicy(policy, report)
        protectSelf(report)

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

        // 家长临时放行的应用不纳管。
        // 周期自检（默认 5 分钟）会重新 apply 一次策略，若不排除这些包，
        // 家长刚同意放行、应用几分钟后又被管控 —— 与"单应用拦截页不认放行"
        // 是同一类"同意了却没生效"。到期后 isTempUnlockActive 转 false，下一轮自动纳回。
        val installedTarget = target
            .filter { isPresentOnDevice(it) }
            .filterNot { lockController.isTempUnlockActive(it) }
            .toSet()

        // 产品要求：被管控的应用在孩子端**不可见**，因此首选「隐藏」（图标从桌面消失）。
        // 设备不具备隐藏能力（非 DO/PO）时退化为「挂起」（图标变灰、点击拦截），
        // 保证管控能力本身不因权限缺失而丢失。
        when {
            admin.can(Capability.HIDE_PACKAGES) -> {
                // 历史遗留：旧版本用挂起实现管控，升级后先清掉残留的挂起状态，
                // 否则应用会同时"变灰"和"消失"，解除管控时也可能清理不干净。
                releaseSuspension(installedTarget, launchable, report)
                applyHidden(installedTarget, report)
            }
            admin.can(Capability.SUSPEND_PACKAGES) -> {
                applySuspended(installedTarget, launchable, report, policy.mode)
            }
            else -> report.markUnsupported(
                "app.list",
                "需要 Device Owner / Profile Owner；当前将退化为无障碍服务事后拦截"
            )
        }

        Logger.i(TAG) {
            "app policy applied: mode=${policy.mode}, controlled=${installedTarget.size}, " +
                "protected=${protectedSet.size}"
        }
        return report
    }

    /**
     * 隐藏被管控的应用：图标从桌面消失、无法启动 —— 即产品要求的"孩子端不可见"。
     *
     * 差集以 [DeviceAdminBridge.hiddenPackages] 的本地记账为准，而不是进程内变量：
     * 系统没有"列出已隐藏应用"的接口，记账持久化在 SharedPreferences，
     * 进程重启后依然算得出"该放出来的应用"，不会像内存变量那样失忆导致应用永久消失。
     */
    private fun applyHidden(target: Set<String>, report: EnforceReport) {
        // 判定"本应用隐藏过且当前确实处于隐藏态"的包：必须用 DPM 接口（isApplicationHidden），
        // 不能用 getApplicationInfo —— 被隐藏的包在应用可见性层面会被视为"不存在"，
        // getApplicationInfo 直接抛 NameNotFoundException，导致 isInstalled 返回 false，
        // 进而被过滤出 toUnhide，取消隐藏的循环根本不会执行：表现就是"把应用移出黑名单后，
        // 它永久从桌面消失、再也放不回来"（复现：隐藏 WPS → 解除黑名单 → WPS 永远隐藏）。
        val ledger = admin.hiddenPackages()
        val actuallyHidden = ledger.filter { admin.isApplicationHidden(it) }.toSet()
        val toUnhide = actuallyHidden - target
        val toHide = target - actuallyHidden

        var unhidden = 0
        for (pkg in toUnhide) {
            when (val r = admin.setApplicationHidden(pkg, false)) {
                is OpResult.Ok -> unhidden++
                is OpResult.Unsupported -> report.markUnsupported("app.unhide", r.reason)
                is OpResult.Failed -> report.markFailed("app.unhide", "取消隐藏失败: $pkg（${r.reason}）")
            }
        }

        // 清理残留台账：曾经记录隐藏、但当前既不在隐藏态也不在管控目标的包
        //（典型场景：应用已被卸载）。不清理会让失效记录越积越多，且下次 apply 仍会误判。
        for (pkg in ledger) {
            if (pkg !in actuallyHidden && pkg !in target) admin.forgetHidden(pkg)
        }

        var hidden = 0
        for (pkg in toHide) {
            when (val r = admin.setApplicationHidden(pkg, true)) {
                is OpResult.Ok -> hidden++
                is OpResult.Unsupported -> report.markUnsupported("app.hide", r.reason)
                is OpResult.Failed -> report.markFailed("app.hide", "隐藏失败: $pkg（${r.reason}）")
            }
        }
        report.note("app.hide=$hidden, app.unhide=$unhidden")
    }

    /** 降级路径：不具备隐藏能力时用挂起（图标变灰、点击拦截）兜住管控能力。 */
    private fun applySuspended(
        target: Set<String>,
        launchable: Set<String>,
        report: EnforceReport,
        mode: AppListMode
    ) {
        // 先解挂"上一轮挂起、本轮不该挂"的，再挂本轮的 —— 顺序反了会有一瞬间全部可用
        //
        // 解挂集合**不能只依赖内存里的 lastSuspended**：它是进程内变量，
        // 而 DPM 的挂起状态是写进系统的、重启后依然生效。
        // 于是只要孩子端进程重启过一次（杀进程、OTA、断电重启都算），
        // 内存记录清空 → 本轮算出的解挂集合为空 → 上一轮挂起的应用**永远放不出来**，
        // 家长看到的是"一批应用莫名其妙变灰、点开关也恢复不了"。
        // 这里以设备的真实挂起状态为准：凡当前还挂着、且本轮不该挂的，一律放出。
        val suspendedOnDevice = launchable.filter { admin.isPackageSuspended(it) }
        val toRelease = ((lastSuspended + suspendedOnDevice).toSet() - target).filter { isInstalled(it) }
        if (toRelease.isNotEmpty()) {
            val result = admin.setPackagesSuspended(toRelease, false)
            report.note("app.release=${result.succeeded.size}")
            if (result.failed.isNotEmpty()) {
                report.markFailed("app.release", "解挂失败: ${result.failed.take(5)}")
            }
        }

        if (target.isNotEmpty()) {
            val result = admin.setPackagesSuspended(target.toList(), true)
            report.note("app.suspend($mode)=${result.succeeded.size}")
            if (result.failed.isNotEmpty()) {
                // 系统保护的包无法挂起属于正常现象，记为降级而非失败
                report.markUnsupported("app.suspend", "系统拒绝挂起: ${result.failed.take(5)}")
            }
            lastSuspended = result.succeeded.toSet()
        } else {
            lastSuspended = emptySet()
        }
    }

    /** 清理历史遗留的挂起状态（升级到"隐藏"方案后，避免应用既变灰又消失）。 */
    private fun releaseSuspension(target: Set<String>, launchable: Set<String>, report: EnforceReport) {
        val suspendedOnDevice = launchable.filter { admin.isPackageSuspended(it) }
        val toRelease = ((lastSuspended + suspendedOnDevice).toSet() - target).filter { isInstalled(it) }
        if (toRelease.isNotEmpty()) {
            val result = admin.setPackagesSuspended(toRelease, false)
            report.note("app.release=${result.succeeded.size}")
        }
        lastSuspended = emptySet()
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

    /**
     * 该包是否存在于设备上（**含"被本应用隐藏"的情况**）。
     *
     * ## 为什么不能只用 [isInstalled]
     * Device Owner 隐藏应用后，`getApplicationInfo` 会抛 NameNotFoundException，
     * [isInstalled] 返回 false。于是每一轮自检都会上演同一个循环：
     *   本轮隐藏成功 → 下一轮判定"未安装" → 被排除出 `installedTarget`
     *   → 落到 `toUnhide` 被取消隐藏 → 再下一轮又能查到 → 又被隐藏。
     * 现场表现是**桌面图标时隐时现**，家长端看起来就是
     * "应用管控时好时坏 / 明明禁了却还能打开" —— 这正是"应用管控没生效"投诉的一条真实成因。
     *
     * 因此这里额外认"被我们自己隐藏且当前确实处于隐藏态"的包：它只是被管控藏起来了，
     * 不是没装，必须继续留在管控目标里，否则隐藏态永远稳不下来。
     *
     * 判定顺序刻意让 [isInstalled] 在前：绝大多数包（未隐藏的）一次命中就返回，
     * 只有少数被隐藏的包才需要多查一次 DPM 记账。
     */
    private fun isPresentOnDevice(packageName: String): Boolean {
        if (isInstalled(packageName)) return true
        return packageName in admin.hiddenPackages() && admin.isApplicationHidden(packageName)
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
