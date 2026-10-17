package com.padguard.child.guard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import com.padguard.child.capture.AccessibilityScreenshot
import com.padguard.child.di.ApplicationScope
import com.padguard.child.ui.lock.LockScreenActivity
import com.padguard.child.ui.lock.LockScreenPresenter
import com.padguard.child.ui.lock.SystemKeyguardBridge
import com.padguard.core.common.Logger
import com.padguard.core.data.model.RiskLevel
import com.padguard.core.data.model.RiskType
import com.padguard.core.data.repository.AuthRepository
import com.padguard.core.data.repository.LogRepository
import com.padguard.core.engine.lock.LockController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
 * 命中时：先 [performGlobalAction] 把用户顶回去，再按类别拉起对应的验证界面：
 * - **打开应用**（取消授权 / 受管控应用）→ [AppAccessGateActivity]，以透明浮层的形式
 *   在桌面上弹出密码框；输对后由 [GuardPasswordManager.grantAppAccess] 只放行这一个包。
 * - **改设置 / 安装卸载** → [GuardUnlockActivity]（家长设备操作入口）；
 *   输对后由 [GuardPasswordManager.unlock] 开启 10 分钟解锁窗口，期间放行这类设备操作。
 *
 * 这两条链**不可互换**：用解锁窗口去放行"打开应用"，等于孩子知道一次密码就能
 * 在 10 分钟内自由安装/卸载/改设置（权限放大）；反之用单包放行去管安装卸载则根本生效不了。
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

    /** 装机向导豁免：向导带家长去设置页办理授权期间，闸门对设置页暂时放行（见类文档）。 */
    @Inject
    lateinit var gateSuspension: GuardGateSuspension

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    /** 锁屏呈现入口：看门狗与窗口抢占统一委托它，避免重复实现锁定层逻辑（含非 DO 设备的悬浮窗地板）。 */
    @Inject
    lateinit var lockScreenPresenter: LockScreenPresenter

    /** 系统 keyguard 生命周期：解锁自愈追逐要读"待撤锁"持久标记 */
    @Inject
    lateinit var keyguardBridge: SystemKeyguardBridge

    /**
     * 无障碍截屏通道：远程截屏优先走这里（授权持久、无 MediaProjection 弹窗）。
     * 实例在本服务连接期间有效，见 [AccessibilityScreenshot]。
     */
    @Inject
    lateinit var accessibilityScreenshot: AccessibilityScreenshot

    /** 去抖：同一受保护窗口 1.5s 内只拦截一次，避免 performGlobalAction 导致事件风暴。 */
    private var lastTriggerAt = 0L
    private var lastTriggerPkg = ""

    /** 锁屏抢占复位去抖：避免每次窗口切换都重拉锁屏造成事件风暴 / 反复重启锁屏页 */
    private var lastLockRelaunchAt = 0L

    /** S11：密码未设置告警的 24h 一次闸 */
    private var lastPasswordNotSetReportedAt = 0L

    /** 自检保护(防卸载)去抖：避免反复顶回造成事件风暴（同事侧） */
    private var lastSelfProtectAt = 0L

    /** 最近一次前台包名（由无障碍窗口事件维护），供锁屏看门狗判断"是否已逃逸到非本应用"。 */
    private var lastForegroundPkg: String? = null

    /** 锁屏看门狗是否已启动（onServiceConnected 可能多次调用，防重复起协程）。 */
    private var watchdogStarted = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        Logger.i(TAG) { "operation guard accessibility service connected" }
        // 注册截屏通道：远程截屏优先走 takeScreenshot，避免每次进程重建都向孩子弹
        // "是否允许录制屏幕"（该通道的授权 = 启用本服务，系统持久记录）。
        accessibilityScreenshot.attach(this)
        // 能力缺失的唯一常见原因：用户在服务已启用之后才升级到带 canTakeScreenshot 的版本，
        // 系统不会重读元数据。此时如实留痕，便于排查"为什么截屏还在走投影弹窗"。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val caps = runCatching { serviceInfo?.capabilities ?: 0 }.getOrDefault(0)
            if (caps and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT == 0) {
                Logger.w(TAG) { "screenshot capability absent: re-toggle this service in system a11y settings to pick up canTakeScreenshot" }
            }
        }
        startLockWatchdog()
    }

    override fun onDestroy() {
        // 与 attach 严格配对：服务销毁后通道必须立即失效，否则弱引用之外的
        // 任何残留都会让调用方误判"通道可用"而在投影链路上多等一轮超时。
        accessibilityScreenshot.detach(this)
        super.onDestroy()
    }

    /**
     * 锁屏看门狗：Android 10+ 会**静默丢弃后台启动的 Activity**，
     * 因此 [LockScreenActivity] 自身的 onPause 抢占复位（Handler.postDelayed 里再 startActivity）
     * 在多数 ROM 上根本不生效；而本无障碍服务启动的 Activity 不受此限制，这里用它做可靠兜底。
     *
     * 仅当处于锁定态、且最近一次前台包名不是本应用时，周期性把锁屏重新顶回前台。
     * 这样即使孩子连续快速上滑回到桌面（手势导航 HOME），锁屏也会在至多
     * [LOCK_WATCH_INTERVAL_MS] 内被拉回，无法"停"在桌面上——从根本上堵死
     * "快速滑动退出锁屏"的绕过。
     *
     * ## 解锁方向的自愈（与锁定对称）
     * 看门狗同时是**解锁追逐**的可靠通道：家长解锁后，若撤锁失败、页面被回收、
     * 或进程刚重启，"待撤锁"标记（[SystemKeyguardBridge.isUnlockPending]）会留在
     * 磁盘上。此时 App 侧已解锁、系统 keyguard 却仍被我们压着 —— 孩子端就是
     * "解锁了却始终黑屏"。无障碍服务可后台启动 Activity（豁免 ROM 限制），
     * 由它拉一次性过渡页去撤锁，是这条自愈链里最不容易被吞掉的一环。
     * 过渡页到前台后自行完成撤锁并清标记，本循环随之静默。
     */
    private fun startLockWatchdog() {
        if (watchdogStarted) return
        watchdogStarted = true
        scope.launch {
            while (isActive) {
                delay(LOCK_WATCH_INTERVAL_MS)
                // ---- 解锁自愈：App 侧已解锁，但系统锁还没撤成 ----
                if (!lockController.state.value.locked && keyguardBridge.isUnlockPending()) {
                    // 过渡页不在前台才追：它活着的时候自己会完成撤锁，避免重复拉起。
                    // chaseUnlock 内部限频（5s）并自带点屏，不会每 500ms 亮一次屏。
                    if (!LockScreenActivity.visible) {
                        Logger.i(TAG) { "lock watchdog: chasing pending system-keyguard unlock" }
                        // FSI 优先，失败降级直接 startActivity —— 无障碍上下文里
                        // 这条降级路径同样豁免后台启动限制，是最不易被 ROM 吞掉的一环。
                        runCatching { LockScreenActivity.chaseUnlock(this@OperationGuardAccessibilityService) }
                            .onFailure { Logger.w(TAG, it) { "unlock chase via accessibility failed" } }
                    }
                    continue
                }
                if (!lockController.state.value.locked) continue
                val fg = lastForegroundPkg
                if (fg != null && fg.startsWith(OWN_PACKAGE_PREFIX)) continue
                // 已逃逸到非本应用（桌面/其它 app）：
                // 1) 无障碍服务可后台启动 Activity（豁免华为后台限制），把锁屏 Activity 顶回前台 ——
                //    这是非 DO 设备后台锁屏唯一可靠的前台化手段；硬锁设备同样受益。
                // 2) 委托 presenter.reenforce() 确保悬浮窗地板在位（非硬锁设备的真正锁定层）。
                // 不再 performGlobalAction(HOME)：它反复把用户顶回桌面、且在非硬锁设备上无效，
                // 只会和窗口切换事件叠加成日志风暴（这正是此前「锁屏崩 + 刷屏」的诱因之一）。
                if (!LockScreenActivity.visible) {
                    runCatching {
                        LockScreenActivity.show(
                            this@OperationGuardAccessibilityService,
                            lockController.state.value.primaryDetail
                        )
                    }.onFailure { Logger.w(TAG, it) { "lock watchdog re-show activity failed" } }
                }
                runCatching { lockScreenPresenter.reenforce() }
                    .onFailure { Logger.w(TAG, it) { "lock watchdog reenforce failed" } }
            }
        }
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
        // 维护最近前台包名（供锁屏看门狗判断锁屏是否已被滑走）；自家界面也不例外，
        // 否则锁屏页自身在前台时 lastForegroundPkg 仍是旧值，看门狗会误以为已逃逸而反复重拉。
        lastForegroundPkg = pkg
        // 自家界面永远不拦（避免拦截解锁框/权限引导自身，造成死循环）
        if (pkg.startsWith(OWN_PACKAGE_PREFIX)) return

        // 应用访问放行到此为止：前台一旦不是被放行的那个包，上次授权即结束。
        // 这是"每次打开都要家长验证"的执行点，必须先于下方一切判定 —— 否则孩子
        // 回到桌面后再点同一个应用就能免密进去，门禁形同虚设。
        manager.revokeAppAccessIfNot(pkg)

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
                // 无障碍服务可后台启动 Activity（豁免华为后台限制），把锁屏重新顶回前台；
                // 同时 reenforce 确保悬浮窗地板在位（非硬锁设备的真正锁定层）。
                if (!LockScreenActivity.visible) {
                    runCatching {
                        LockScreenActivity.show(
                            this@OperationGuardAccessibilityService,
                            lockController.state.value.primaryDetail
                        )
                    }.onFailure { Logger.e(TAG, it) { "failed to re-show lock screen" } }
                }
                runCatching { lockScreenPresenter.reenforce() }
                    .onFailure { Logger.e(TAG, it) { "failed to re-arm lock screen" } }
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

        // 3a) 取消授权的应用：硬拦截（最严），与全局启动闸门、是否设了家长密码都无关。
        // 不再把人顶回桌面再在桌面上弹框——那样孩子能在桌面上再次点同一图标，
        // 而 1.5s 去抖会压制下一次拦截，等于"关掉框就能进应用"（绕过）。
        // 改为：把密码框作为透明浮层**直接盖在被管控应用之上**（应用留在后台、可见），
        // 输对只放行这一个包并把它带回前台；取消 / 点框外则 kill 该应用（退出应用）。
        // 这里不做时间去抖：浮层是 singleTask，重复 show 只是更新、不会风暴；
        // 去抖反而会压制"关框后再次打开"的拦截，重新制造绕过窗口。
        if (reason == REASON_HIDDEN_APP) {
            // 先把被管控应用压回桌面（无论是否锁定都必须执行），确保它不进入可用前台；
            // 再在**桌面上**弹门禁浮层（盖在桌面而非盖在应用之上）。
            // 绝不能"浮层透明盖在应用上"——那样应用仍在前台、可见且可交互，
            // 孩子点屏幕任意处触发浮层取消后，被压回后台的应用会因时序竞态杀不掉而原地复活，
            // 表现为"管控了还能继续打开"（c72f4cd 引入的回归）。
            // 注意：此处不按包名去抖——去抖会压制"关框后再次点开"的拦截，
            // 重新制造绕过窗口；每次前台事件都即时压回+弹框，反而更严格。
            performGlobalAction(GLOBAL_ACTION_HOME)
            runCatching { AppAccessGateActivity.show(this, pkg) }
                .onFailure { Logger.e(TAG, it) { "failed to launch app access gate (hidden app)" } }
            return
        }

        // 装机向导豁免（务必放在防卸载自检之后、S11 之前）：
        // 向导正带着家长去设置页办理「安装未知应用」等授权，此刻照常顶回会把设置页
        // 1 秒内弹回，装机流程卡死（实测日志："guarded foreground: com.android.settings
        // reason=settings, guard password not set, bouncing back" 每次跳转后必现）。
        // 仅豁免设置页顶回：上方防卸载（应用详情页）、隐藏应用、下方安装器拦截均不受影响，
        // 孩子无法借豁免窗口卸载本应用或绕过应用门禁。
        if (reason == REASON_SETTINGS && gateSuspension.isActive()) return

        // S11 拆层：密码未设置时的降级拦截。
        // 设置页必须拦：它正是关闭本无障碍服务的入口，放行等于允许"先关无障碍、
        // 再解除一切管控"。但密码未设时拉不出验证框（没有可比对的凭据），
        // 所以只顶回 + 提醒家长尽快补设密码（24h 至多一次）。
        // 安装器/受管控应用的拦截依赖密码闸验证身份，未设密码时维持放行。
        if (!manager.isEnabledNow()) {
            if (reason != REASON_SETTINGS) return
            val now = System.currentTimeMillis()
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

        Logger.i(TAG) { "guarded foreground: $pkg reason=$reason, intercepting (lockedOut=$lockedOut)" }
        // 1) 顶回当前操作：
        //    - 设置页用 BACK（部分 ROM 用 HOME 会卡在最近任务）；
        //    - 安装器/卸载用 HOME（BACK 后会经最近任务回跳）；
        //    - 受管控应用**必须先压回桌面**：门禁浮层盖在桌面上（而非盖在应用之上），
        //      否则应用仍在前台可见可交互，孩子触发浮层取消后应用会因时序竞态复活，管控形同虚设。
        if (reason == REASON_CONTROLLED_APP) {
            performGlobalAction(GLOBAL_ACTION_HOME)
        } else {
            performGlobalAction(
                if (reason == REASON_SETTINGS) GLOBAL_ACTION_BACK else GLOBAL_ACTION_HOME
            )
        }
        // 2) 仅在未锁定时拉起验证界面，让家长可以放行（锁定态跳过，避免狂点弹框）
        if (!lockedOut) {
            runCatching {
                if (reason == REASON_CONTROLLED_APP) {
                    // 打开受管控应用 → 盖在桌面之上的门禁浮层（输对只放行这一个包）
                    AppAccessGateActivity.show(this, pkg)
                } else {
                    // 改设置 / 安装卸载 → 家长设备操作解锁，输对开 10 分钟全局窗口
                    startActivity(
                        Intent(this, GuardUnlockActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }.onFailure { Logger.e(TAG, it) { "failed to launch gate/unlock for reason=$reason" } }
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
        // 3) 应用访问放行：家长刚在门禁里针对**这个包**验证通过 → 放行。
        //    这是"打开这一个应用"的授权，与设备操作解锁窗口（isUnlockedNow）无关：
        //    它不放行安装/卸载/改设置，也不放行其它应用，到期即失效。
        //    缺了这一步，应用会在启动瞬间被本服务再次顶回 —— 表现为"密码输对了却打不开"。
        if (manager.isAppAccessGrantedNow(pkg)) return@runCatching null
        // 3a) 取消授权的应用（hidden）：无论"打开应用也要密码"开关是否开启、无论是否已设家长密码，
        //     打开都必须先经过门禁（输对家长密码才放行）。这是"授权已取消"的底线语义——
        //     它不依赖全局启动闸门（默认关闭会完全失效），也不因未设密码而漏放（最严）。
        //     isHiddenControlled 已排除系统关键包（防砖名单）。
        if (appGuard.isHiddenControlled(pkg)) return@runCatching REASON_HIDDEN_APP
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

        /** 锁屏看门狗轮询间隔：锁定态下每隔这么久核对一次前台，逃逸即拉回（快滑最多 500ms 内回锁屏）。 */
        private const val LOCK_WATCH_INTERVAL_MS = 500L

        /** 锁屏抢占复位去抖间隔：窗口切换抖动期间避免反复重拉锁屏页 */
        private const val LOCK_RELAUNCH_DEBOUNCE_MS = 800L

        /** 自检保护(防卸载)去抖间隔 */
        private const val SELF_PROTECT_DEBOUNCE_MS = 1200L

        private const val REASON_SETTINGS = "settings"
        private const val REASON_INSTALLER = "installer"
        private const val REASON_CONTROLLED_APP = "controlled-app"
        /** 取消授权的应用（hidden）：无条件硬拦截，需家长密码门禁（最严） */
        private const val REASON_HIDDEN_APP = "hidden-app"

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
