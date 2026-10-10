package com.padguard.child.guard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.padguard.child.di.ApplicationScope
import com.padguard.child.ui.lock.LockScreenActivity
import com.padguard.core.common.Logger
import com.padguard.core.data.model.RiskLevel
import com.padguard.core.data.model.RiskType
import com.padguard.core.data.repository.AuthRepository
import com.padguard.core.data.repository.LogRepository
import com.padguard.core.engine.lock.LockController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 操作闸门无障碍服务（软拦截核心）。
 *
 * ## 它现在拦三类界面
 * | 类别 | 判定依据 | 是否可关闭 |
 * |---|---|---|
 * | 改系统设置 | 各 ROM 设置包名 | 不可（否则孩子关掉无障碍即解除管控） |
 * | 安装 / 卸载应用 | `packageinstaller` 子串 + 各 ROM 安全中心 | 可（家长端开关「安装/卸载确认需密码」，默认拦截） |
 * | 打开受管控应用 | [AppGuardPolicyStore] 的白/黑名单 | 可（家长端开关，默认只拦前两类） |
 * | 非 DO 防卸载(自检) | 设置包 + 应用详情页类名，且设备管理员已激活 | 不可（非 DO 设备靠设备管理员 + onDisableRequested 兜底，与操作密码无关） |
 *
 * 命中且未解锁时：先 [performGlobalAction] 把用户顶回去，再拉起 [GuardUnlockActivity] 收密码。
 * 家长输对密码 → 开启 10 分钟解锁窗口 → 期间一律放行。
 *
 * ## 为什么是「软拦截」而不是系统级
 * Android 不允许普通应用对 PackageInstaller / 设置 app 的确认流程插入自定义密码框，
 * 只有设备所有者(DO) 才行。但存量 C 端平板无法在不恢复出厂的前提下成为 DO，
 * 所以用「无障碍 + 顶回 + 自家密码框」的组合实现同等效果。
 *
 * ## 关键边界（务必如实承认，不要在销售话术里含糊）
 * - 服务必须由用户在「设置 → 无障碍」中手动开启，无法随安装自动开启；
 *   未开启时闸门失效，[com.padguard.child.service.GuardService] 会持续提示引导。
 * - 浏览器/应用市场里的「下载」动作发生在第三方 app 进程内，拦不住；
 *   能拦的是**安装确认页**这一步。
 * - 已安装的应用若家长未开启"启动也要密码"，本服务不会拦它打开 ——
 *   这是产品选择，不是缺陷：白名单管控对使用强度影响极大，不能替家长决定。
 * - 不读取任何界面内容（[AccessibilityServiceInfo] 不开启 retrieveWindowContent），
 *   只取 packageName / className，不做隐私采集。
 */
@AndroidEntryPoint
class OperationGuardAccessibilityService : AccessibilityService() {

    @Inject
    lateinit var manager: GuardPasswordManager

    @Inject
    lateinit var appGuard: AppGuardPolicyStore

    @Inject
    lateinit var lockController: LockController

    /** S11：密码未设置提醒走风险告警通道（append 只进行为流水，家长端看不见） */
    @Inject
    lateinit var logRepository: LogRepository

    @Inject
    lateinit var authRepository: AuthRepository

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    /** 去抖：同一受保护窗口 1.5s 内只拦截一次，避免 performGlobalAction 导致事件风暴。 */
    private var lastTriggerAt = 0L
    private var lastTriggerPkg = ""

    /** 锁屏抢占复位去抖：避免每次窗口切换都重拉锁屏造成事件风暴 / 反复重启锁屏页 */
    private var lastLockRelaunchAt = 0L

    /** S11：密码未设置告警的 24h 一次闸 */
    private var lastPasswordNotSetReportedAt = 0L

    /** 自检保护(防卸载)去抖：避免反复顶回造成事件风暴（同事侧） */
    private var lastSelfProtectAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Logger.i(TAG) { "operation guard accessibility service connected" }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 顶层兜底：无障碍回调运行在本进程主线程，任何未捕获异常都会让守护进程崩溃、
        // 表现为「打开任意应用都闪退」。这里统一吞掉异常，保证单个窗口事件处理失败
        // 不会拖垮整个管控进程（下一个窗口事件仍会正常处理）。
        try {
            handleAccessibilityEvent(event)
        } catch (t: Throwable) {
            Logger.e(TAG, t) { "onAccessibilityEvent crashed, ignored to protect process" }
        }
    }

    private fun handleAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // 只关心前台窗口切换（进入/退出某个 app 的设置、安装、卸载或受管控应用）
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString() ?: return
        // 自家界面永远不拦（避免拦截解锁框/权限引导自身，造成死循环）
        if (pkg.startsWith(OWN_PACKAGE_PREFIX)) return

        // ---- 锁屏抢占复位（不依赖 DO / 设备管理员）----
        // 没有 Device Owner 时 Lock Task 不生效，锁屏只是普通 Activity；
        // 孩子上滑回桌面后，锁屏 Activity 在后台用 startActivity 拉不回来
        // （Android 10+ 限制后台启动 Activity）。无障碍服务不受此限制，
        // 因此由本服务在"锁定时前台变成非本应用"的瞬间把锁屏重新顶回前台。
        // 注意：这一段独立于下方密码闸门，即使密码闸门未启用也必须生效。
        if (lockController.state.value.locked) {
            val now = System.currentTimeMillis()
            if (now - lastLockRelaunchAt > LOCK_RELAUNCH_DEBOUNCE_MS) {
                lastLockRelaunchAt = now
                Logger.i(TAG) { "lock active but foreground escaped to $pkg, re-arming lock screen" }
                // 先回桌面，避免卡在最近任务等中间态；随后把锁屏顶回前台
                performGlobalAction(GLOBAL_ACTION_HOME)
                runCatching {
                    LockScreenActivity.show(this, lockController.state.value.primaryDetail)
                }.onFailure { Logger.e(TAG, it) { "failed to re-show lock screen" } }
            }
            // 锁屏期间任何非本应用前台都视为"试图逃逸"，不再走下方密码闸逻辑
            return
        }

        // 当前前台不在管控范围 → 放行。
        // S11：判定提前到密码闸状态检查之前 —— 拦不拦是「这个界面是否要保护」的问题，
        // 怎么拦是「密码闸处于什么状态」的问题，两者解耦后，密码未设置时设置页仍有兜底拦截。
        val reason = decide(pkg) ?: return

        // ---- 非 DO 防卸载兜底（不依赖操作密码）----
        // 设备管理器已激活时，系统会在卸载本应用前先要求「取消设备管理员」，
        // 而 PadGuardDeviceAdminReceiver.onDisableRequested 返回警告会阻止取消 —— 形成系统级双保险。
        // 这里再挡一层「设置 → 应用信息/卸载」页，让孩子即使没开操作密码也进不去卸载入口。
        // DO 下由 DISALLOW_APPS_CONTROL 接管，不重复处理；应用详情页命中本分支（HOME 顶回，
        // 防止 BACK 后经最近任务回跳），其余设置页走下方 S11 密码未设降级拦截，两者互补。
        if (appGuard.selfProtectApplies() && isAppDetailsPage(event)) {
            val now = System.currentTimeMillis()
            if (now - lastSelfProtectAt > SELF_PROTECT_DEBOUNCE_MS) {
                lastSelfProtectAt = now
                Logger.i(TAG) { "self-protect: blocking settings app-details page (uninstall entry)" }
                performGlobalAction(GLOBAL_ACTION_HOME)
                if (manager.isEnabledNow()) {
                    runCatching {
                        startActivity(
                            Intent(this, GuardUnlockActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }.onFailure { Logger.e(TAG, it) { "failed to launch guard unlock (self-protect)" } }
                }
            }
            return
        }

        // S11 拆层：密码未设置时的降级拦截。
        // 设置页必须拦：它正是关闭本无障碍服务的入口，放行等于允许"先关无障碍、
        // 再解除一切管控"。但密码未设时拉不出验证框（没有可比对的凭据），
        // 所以只顶回 + 提醒家长尽快补设密码（24h 至多一次）。
        // 安装器/受管控应用的拦截依赖密码闸验证身份，未设密码时维持放行。
        if (!manager.isEnabledNow()) {
            if (reason != REASON_SETTINGS) return
            val now = System.currentTimeMillis()
            // 与主路径共用按包名去抖：顶回引发的事件风暴同样需要抑制
            if (pkg == lastTriggerPkg && now - lastTriggerAt < DEBOUNCE_MS) return
            lastTriggerAt = now
            lastTriggerPkg = pkg
            Logger.i(TAG) { "guarded foreground: $pkg reason=$reason, guard password not set, bouncing back" }
            performGlobalAction(GLOBAL_ACTION_BACK)
            reportPasswordNotSet()
            return
        }

        // 处于解锁窗口内 → 放行
        if (manager.isUnlockedNow()) return
        // 是否在「试错锁定」冷却期：锁定期间仍要顶回（否则等于开闸），
        // 但不再拉起密码框——孩子此刻无论如何验不过，弹框只会被狂点。
        val lockedOut = manager.isLockedOutNow()

        val now = System.currentTimeMillis()
        // 去抖按「包名」而非全局：顶回后用户可能立刻点另一个应用，那次要拦
        if (pkg == lastTriggerPkg && now - lastTriggerAt < DEBOUNCE_MS) return
        lastTriggerAt = now
        lastTriggerPkg = pkg

        Logger.i(TAG) { "guarded foreground: $pkg reason=$reason, intercepting (lockedOut=$lockedOut)" }
        // 1) 顶回：阻止这次操作（无论是否锁定都必须执行，否则锁定期反而成了开闸窗口）
        if (reason == REASON_CONTROLLED_APP) {
            // 受管控应用用「回桌面」而不是 BACK：部分应用被 BACK 后会立即重新抢焦点，
            // 表现为"点开游戏闪一下又回来"，像坏了而不是像被管控。
            performGlobalAction(GLOBAL_ACTION_HOME)
        } else {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
        // 2) 仅在未锁定时拉起密码框，让家长可以解锁（锁定态跳过，避免狂点弹框）
        if (!lockedOut) {
            runCatching {
                startActivity(
                    Intent(this, GuardUnlockActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { Logger.e(TAG, it) { "failed to launch guard unlock" } }
        }
    }

    override fun onInterrupt() {
        Logger.w(TAG) { "operation guard accessibility service interrupted" }
    }

    /**
     * S11：密码未设置提醒，24h 至多一次。
     *
     * 这是给**家长**看的告警：无障碍拦截在位、守护基建齐备，密码闸却没启用，
     * 说明初始配置没做完（或被清除后未补设）。走 raise 而非 append 的原因与
     * S15（PadGuardDeviceAdminReceiver）相同：告警通道才会出现在家长端，
     * 行为流水永远无人看。24h 闸与 TamperDetector 的闩锁语义同理 ——
     * 孩子每次进设置页都报，只会把这条提醒刷成噪音。
     */
    private fun reportPasswordNotSet() {
        val now = System.currentTimeMillis()
        if (now - lastPasswordNotSetReportedAt < PASSWORD_NOT_SET_INTERVAL_MS) return
        lastPasswordNotSetReportedAt = now
        scope.launch {
            runCatching {
                logRepository.raise(
                    type = RiskType.GUARD_PASSWORD_NOT_SET,
                    level = RiskLevel.NORMAL,
                    detail = mapOf(
                        "note" to "操作密码尚未设置，设置页仅能顶回无法验证家长身份，请尽快在控制端补设密码"
                    ),
                    deviceId = authRepository.getDeviceId()
                )
            }.onFailure { Logger.w(TAG, it) { "failed to raise password-not-set alert" } }
        }
    }

    /**
     * 当前前台是否是「设置 → 应用信息 / 应用详情」页。
     *
     * 该页是卸载 / 停用本应用的唯一入口。无障碍事件拿不到 intent data
     * （无法精确判断查看的是哪个 app），故按「设置包 + 应用详情类 Activity 类名」匹配。
     * 仅命中应用详情页，不拦设置其他功能（如 WiFi / 蓝牙），把对孩子正常使用的侵扰降到最低。
     */
    private fun isAppDetailsPage(event: AccessibilityEvent): Boolean {
        val pkg = event.packageName?.toString() ?: return false
        if (pkg !in GUARDED_SETTINGS_PACKAGES) return false
        val cls = event.className?.toString().orEmpty()
        return cls.contains("InstalledAppDetails") ||
            cls.contains("AppInfo") ||
            cls.contains("ApplicationDetails") ||
            cls.contains("ApplicationInfo") ||
            cls.endsWith("AppDetailsSettingsActivity")
    }

    /**
     * 判定当前前台包名是否需要拦截。
     *
     * @return 拦截原因；`null` 表示放行。
     */
    private fun decide(pkg: String): String? = runCatching {
        // 1) 改系统设置 —— 不可关闭：这里能禁用本无障碍服务，放开等于管控自毁
        if (pkg in GUARDED_SETTINGS_PACKAGES) return@runCatching REASON_SETTINGS
        // 2) 安装 / 卸载确认 —— 家长可在控制端开关「安装/卸载确认需密码」；
        //    安装或卸载任一开关开启时（默认都开启）拦截安装器/安全中心包，两者都关则放行。
        if (AppGuardPolicyStore.isInstallerPackage(pkg) &&
            (appGuard.installNeedsPassword() || appGuard.uninstallNeedsPassword())
        ) return@runCatching REASON_INSTALLER
        // 3a) 关闭权限的应用（hidden）：无论"打开应用也要密码"开关是否开启，打开都必须输密码。
        //     这是"权限已关闭"的底线语义——它不能依赖全局启动闸门（默认关闭会导致完全失效），
        //     也不能依赖 DO 隐藏（存量设备多为非 DO）。isHiddenControlled 已排除系统关键包。
        if (appGuard.isHiddenControlled(pkg)) return@runCatching REASON_CONTROLLED_APP
        // 3b) 打开受管控应用 —— 家长开关控制
        //    注意：isControlled 已保证「已授权应用一律放行」，此处不会误伤授权应用。
        if (appGuard.launchBlockEnabled() &&
            appGuard.isControlled(pkg) &&
            !appGuard.isSystemCritical(pkg)
        ) {
            return@runCatching REASON_CONTROLLED_APP
        }
        null
    }.getOrNull()

    companion object {
        private const val TAG = "GuardA11y"
        private const val OWN_PACKAGE_PREFIX = "com.padguard.child"
        private const val DEBOUNCE_MS = 1500L

        /** 锁屏抢占复位去抖间隔：窗口切换抖动期间避免反复重拉锁屏页 */
        private const val LOCK_RELAUNCH_DEBOUNCE_MS = 800L

        /** 自检保护(防卸载)去抖间隔 */
        private const val SELF_PROTECT_DEBOUNCE_MS = 1200L

        private const val REASON_SETTINGS = "settings"
        private const val REASON_INSTALLER = "installer"
        private const val REASON_CONTROLLED_APP = "controlled-app"

        /** S11：密码未设置告警的最小间隔：24h */
        private const val PASSWORD_NOT_SET_INTERVAL_MS = 24 * 60 * 60 * 1000L

        /** 受保护的系统设置包名（AOSP + 主流国产 ROM）。 */
        private val GUARDED_SETTINGS_PACKAGES = setOf(
            "com.android.settings",
            "com.miui.settings",
            "com.coloros.settings",
            "com.oppo.settings",
            "com.vivo.settings",
            "com.huawei.android.settings",
            "com.samsung.android.settings",
            "com.oneplus.settings",
            "com.realme.settings"
        )
    }
}
