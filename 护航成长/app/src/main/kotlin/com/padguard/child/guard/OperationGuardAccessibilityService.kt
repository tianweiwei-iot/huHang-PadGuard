package com.padguard.child.guard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.padguard.child.capture.AccessibilityScreenshot
import com.padguard.child.capture.CaptureConsentStore
import com.padguard.child.receiver.GuardAlarmReceiver
import com.padguard.child.service.GuardHeartbeat
import com.padguard.child.service.GuardService
import com.padguard.child.di.ApplicationScope
import com.padguard.child.util.isOwnPackage
import com.padguard.child.ui.lock.LockScreenActivity
import com.padguard.child.ui.lock.LockScreenPresenter
import com.padguard.child.ui.lock.NoPermissionOverlay
import com.padguard.child.ui.lock.SystemKeyguardBridge
import com.padguard.core.common.Logger
import com.padguard.core.data.model.RiskLevel
import com.padguard.core.data.model.RiskType
import com.padguard.core.data.repository.AuthRepository
import com.padguard.core.data.repository.LogRepository
import com.padguard.core.engine.admin.Capability
import com.padguard.core.engine.admin.DeviceAdminBridge
import com.padguard.core.engine.guard.GuardMasterSwitch
import com.padguard.core.engine.lock.LockController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 无障碍服务维护的"最近前台包名"对外只读出口。
 *
 * [OperationGuardAccessibilityService] 在每次窗口切换时把当前前台包名写进来；
 * 当主数据源（使用情况访问 / UsageStats）因权限被撤销而不可用时，
 * [com.padguard.child.monitor.ForegroundAppMonitor] 用它做单包兜底，
 * 避免应用级黑名单 / Kiosk 拦截在权限缺失时完全失明（漏管）。
 * 包名可能滞后于真实前台（无障碍只在窗口状态变化时更新），但作为降级源远好于空判定。
 */
object AccessibilityForegroundSource {
    @Volatile var lastForegroundPackage: String? = null
}

/**
 * 操作闸门无障碍服务（软拦截核心）。
 *
 * ## 它现在拦四类界面
 * | 类别 | 判定依据 | 是否可关闭 |
 * |---|---|---|
 * | 改系统设置 | 各 ROM 设置包名 | 不可（否则孩子关掉无障碍即解除管控） |
 * | 安装 / 卸载应用 | `packageinstaller` 子串 + 各 ROM 安全中心 | 可（家长端开关「安装/卸载确认需密码」，默认拦截） |
 * | 桌面长按卸载入口 | 默认桌面包名 + 非主页窗口类名（[UninstallEntryDetector]） | 可（同上开关） |
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

    /** 前台切换唤醒信号：把「额度已耗尽却还能玩 15 秒」的窗口收掉（见 [GuardTickSignal]） */
    @Inject
    lateinit var tickSignal: GuardTickSignal

    /** 前台切换后的"补一次唤醒"任务；新切换发生时取消旧的，避免任务堆叠 */
    private var tickFollowUpJob: Job? = null

    /** 装机向导豁免：向导带家长去设置页办理授权期间，闸门对设置页暂时放行（见类文档）。 */
    @Inject
    lateinit var gateSuspension: GuardGateSuspension

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    /** 锁屏呈现入口：看门狗与窗口抢占统一委托它，避免重复实现锁定层逻辑（含非 DO 设备的悬浮窗地板）。 */
    @Inject
    lateinit var lockScreenPresenter: LockScreenPresenter

    /** 设备管理能力桥接：锁屏被划走时借设备管理员 lockNow() 重新唤起系统锁屏（keyguard）作为兜底。 */
    @Inject
    lateinit var admin: DeviceAdminBridge

    /** "您当前无权限操作"瞬时提示覆盖层：受控态连点达阈值时弹出。 */
    @Inject
    lateinit var noPermissionOverlay: NoPermissionOverlay

    /** 系统 keyguard 生命周期：解锁自愈追逐要读"待撤锁"持久标记 */
    @Inject
    lateinit var keyguardBridge: SystemKeyguardBridge

    /** 整机管控总开关：active=false 时本服务所有拦截全部解除，设备回到完全可用 */
    @Inject
    lateinit var guardMasterSwitch: GuardMasterSwitch

    /** 卸载防御聚合（问题二·非 DO 加固）：设备管理员停用页拦截 + 卸载闸门告警升级 */
    @Inject
    lateinit var uninstallDefense: UninstallDefenseModule

    /**
     * 无障碍截屏通道：远程截屏优先走这里（授权持久、无 MediaProjection 弹窗）。
     * 实例在本服务连接期间有效，见 [AccessibilityScreenshot]。
     */
    @Inject
    lateinit var accessibilityScreenshot: AccessibilityScreenshot

    /** 授权闩锁存储：无障碍通道授权持久化（见 [CaptureConsentStore]）。 */
    @Inject
    lateinit var captureConsentStore: CaptureConsentStore

    // 以下三个时间戳一律使用 SystemClock.elapsedRealtime()（单调轴，新增缺陷 I）。
    // 用裸墙钟时孩子回拨系统时间会让 `now - last` 变负：去抖分支恒为"刚拦过"直接 return，
    // 闸门不再顶回受管控应用；24h 告警闸与防卸载同样被永久压制。进程内内存态，重启归零无影响。

    /** 去抖：同一受保护窗口 1.5s 内只拦截一次，避免 performGlobalAction 导致事件风暴。 */
    private var lastTriggerAt = 0L
    private var lastTriggerPkg = ""

    /** 受控态（锁定/时间到）期间的连点计数器：达阈值后由 NoPermissionOverlay 给出明确提示。 */
    private val blockCounter = BlockCounter()

    /** S11：密码未设置告警的 24h 一次闸 */
    private var lastPasswordNotSetReportedAt = 0L

    /** 自检保护(防卸载)去抖：避免反复顶回造成事件风暴（同事侧） */
    private var lastSelfProtectAt = 0L

    /** 最近一次前台包名（由无障碍窗口事件维护），供锁屏看门狗判断"是否已逃逸到非本应用"。 */
    private var lastForegroundPkg: String? = null

    /** 锁屏看门狗 Job 与其启动标志都在 companion（见文件末尾）：
     *  它们跑在 @ApplicationScope 上，生命周期与进程一致而与 Service 实例无关，
     *  放实例字段会导致服务重建后旧循环永不退出。 */

    /**
     * 设备上所有"能当桌面"的包 → 其 HOME Activity 类名（用于识别「桌面长按弹出的卸载菜单」）。
     *
     * 动态解析而不是硬编码包名：华为/小米/OPPO 的桌面包名各不相同，硬编码必然漏，
     * 而漏一个就等于"这台机器上长按卸载可以绕过密码"（2026-11-02 事故正是这条）。
     *
     * 为什么是**集合**而不是单个默认桌面（R-P2-8）：
     * 装了多个桌面（系统桌面 + 第三方/儿童桌面）时，只 arm 默认那一个，
     * 孩子把默认桌面切到另一个就能走通长按卸载；而且默认桌面在运行期被切换后，
     * 服务里缓存的值会一直是旧的。现在把所有 CATEGORY_HOME 的包都 arm 进来，
     * 并定期重解析（见 [maybeRefreshLauncherInfo]）。
     *
     * 解析不到时保持空集合，[UninstallEntryDetector] 会退化为只按类名判，绝不误拦整个桌面。
     */
    private var launcherHomeClasses: Map<String, String> = emptyMap()

    /** 上次解析桌面的单调时刻：用于定期重解析（默认桌面可能在运行期被切换） */
    private var lastLauncherResolveElapsed = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Logger.i(TAG) { "operation guard accessibility service connected" }
        // 解析默认桌面：长按图标弹出的"卸载"菜单属于桌面包的另一个窗口，
        // 闸门必须能认出"这是桌面在弹浮层"，否则这条最常用的卸载通路会被直接放行。
        refreshLauncherInfo()
        // 写入存活心跳：供 GuardA11y.isAlive / TamperDetector 区分「进程被系统杀掉」与「用户手动关闭」，
        // 避免误报「操作闸门被人为关闭」。
        GuardA11y.markAlive(this)
        // 注册截屏通道：远程截屏优先走 takeScreenshot，避免每次进程重建都向孩子弹
        // "是否允许录制屏幕"（该通道的授权 = 启用本服务，系统持久记录）。
        accessibilityScreenshot.attach(this)
        // 服务已连接即代表无障碍采集能力已授权（系统级持久授权）：钉死标记，
        // 供启动/亮屏时主动静默重绑，落实"授权一次、除解绑外永不失效"。
        runCatching { captureConsentStore.markA11yGranted() }
        // 服务重绑后，卸载软门的"活性"由假变真：常驻通知不能再挂着 10 分钟前
        // "请开启无障碍服务"的快照，主动按实时状态刷新一次，并撤掉残留的采集引导通知。
        runCatching { GuardService.refreshPermissionNotice(this) }
        // 能力缺失的唯一常见原因：用户在服务已启用之后才升级到带 canTakeScreenshot 的版本，
        // 系统不会重读元数据。此时如实留痕，便于排查"为什么截屏还在走投影弹窗"。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val caps = runCatching { serviceInfo?.capabilities ?: 0 }.getOrDefault(0)
            if (caps and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT == 0) {
                Logger.w(TAG) { "screenshot capability absent: re-toggle this service in system a11y settings to pick up canTakeScreenshot" }
            }
        }
        startLockWatchdog()
        // 系统会在进程被杀后自动重启已启用的无障碍服务——这是非 DO 设备「进程被杀→自愈」最可靠的通道。
        // 借此刻把守护服务一并拉起，闭合「进程被杀 → 脱管 → 卸载闸门（无障碍软门）失效 → 可被卸载」的断链：
        // 只要无障碍服务还启用，孩子端即便被清理工具/系统杀掉也能源头重新拉起守护，保持永驻与管控生效。
        if (!GuardService.isRunning) {
            runCatching { GuardService.start(this, "a11yReconnect") }
                .onFailure { Logger.w(TAG, it) { "guard restart from a11y failed" } }
        }
        // 顺带重排「闹钟兜底」系统通道：清理工具可能把 JobScheduler 作业与旧闹钟一并清掉，
        // 这里借系统重绑的时刻补排一次，确保即便上面那次 startForegroundService 被 ROM 吞掉、
        // 哪怕本无障碍服务自身也一度失联，仍有系统计时器在 ~1~3s 内把守护拉回。
        runCatching { GuardAlarmReceiver.schedule(this) }
            .onFailure { Logger.w(TAG, it) { "re-arm guard alarm from a11y failed" } }
    }

    override fun onDestroy() {
        // 存活状态立刻置死：解绑/被关闭的**那一秒**就要反映到心跳、桌面门禁与卸载防护，
        // 不能等 2 分钟心跳过期。服务重建时 onServiceConnected 会重新置活。
        GuardA11y.markDead()
        // 与 attach 严格配对：服务销毁后通道必须立即失效，否则弱引用之外的
        // 任何残留都会让调用方误判"通道可用"而在投影链路上多等一轮超时。
        accessibilityScreenshot.detach(this)
        // 看门狗跑在进程级 ApplicationScope 上，不会随 Service 销毁自动停止；
        // 不在这里取消，旧循环会继续用已销毁的 Service 上下文调 LockScreenActivity.show，
        // 服务每重建一次就多一条 500ms 循环（泄漏 + 加剧锁屏重拉竞态）。
        watchdogJob?.cancel()
        watchdogJob = null
        super.onDestroy()
    }

    /**
     * EMUI「一键清理」的第一刀是清掉无障碍开关：系统随即**解绑**本服务（走到本回调），
     * 紧接着整包杀进程（SIGKILL，onDestroy 都未必有机会跑）。
     * 趁进程还活着，把「即时兜底闹钟」排上 —— 闹钟由 system_server 持有、与进程生死无关，
     * 进程死后 ~1.2s 必然触发，把守护拉回；守护再经静默写入通道
     * （WRITE_SECURE_SETTINGS，见 AccessibilityCapabilityHealer）把开关写回，
     * 完成"清理 → 秒级拉起 → 自动重启用"的闭环，而不是等 60s 周期闹钟 + 家长到场。
     *
     * 正常解绑（家长端解绑设备）也会经过这里：此时立即闹钟 1.2s 后触发，
     * [GuardAlarmReceiver.handleImmediate] 会按既有口径校验绑定态（未绑定不拉起），
     * 不会与解绑流程打架。
     */
    override fun onUnbind(intent: Intent?): Boolean {
        runCatching { GuardAlarmReceiver.scheduleImmediate(this) }
            .onFailure { Logger.w(TAG, it) { "schedule immediate alarm from onUnbind failed" } }
        return super.onUnbind(intent)
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
        // 进程级防重：服务被系统重建时 onServiceConnected 会再次调用，
        // 只要旧 Job 还活着就复用它，绝不再起第二条循环。
        if (watchdogJob?.isActive == true) return
        watchdogJob = scope.launch {
            while (isActive) {
                try {
                    delay(LOCK_WATCH_INTERVAL_MS)
                    // 心跳续约（内部按 30s 节流，不产生额外磁盘压力）。
                    // 这里是"服务还活着"最稳定的证据来源：只要本循环在跑，
                    // GuardA11y.isAlive 就不该过期 —— 缺了这一行，服务连接满 2 分钟后
                    // 全端都会认为无障碍已死亡（2026-11-05 线上误判的根因）。
                    GuardA11y.markAliveTick(this@OperationGuardAccessibilityService)
                    // 守护进程自救：整包被杀后，本无障碍服务由系统重绑是唯一活路。
                    // 若此时守护尚未拉起（onServiceConnected 那次 startForegroundService 被 ROM 吞掉，
                    // 或守护自身崩溃后还没冷启完），这里每 5s 持续重试 GuardService.start，
                    // 直到守护重新常驻 —— 闭合「清完内存 → 管控永久失联」的断链。
                    // 仅已绑定设备重试（无管控依据不拉起，与全链路语义一致）。
                    if (!GuardService.isRunning &&
                        GuardHeartbeat.readGuardState(this@OperationGuardAccessibilityService)
                    ) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastGuardRelaunchAtMs > GUARD_RELAUNCH_INTERVAL_MS) {
                            lastGuardRelaunchAtMs = now
                            Logger.w(TAG) { "lock watchdog: guard not running, relaunching" }
                            runCatching {
                                GuardService.start(this@OperationGuardAccessibilityService, "a11yWatchdogRelaunch")
                            }.onFailure { Logger.w(TAG, it) { "guard relaunch from a11y watchdog failed" } }
                        }
                    }
                    // 整机管控暂停时不重拉锁屏、不续系统锁：设备应完全可用
                    if (!guardMasterSwitch.active.value) continue
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
                    // 分屏 / 悬浮窗（自由窗口）形态：锁屏页此刻可能**就是**焦点窗口，
                    // 但它只占半屏，另一半是桌面或别的应用，孩子照常能点开应用、能拖分隔条、
                    // 能关掉锁屏那一格 —— 这同样是逃逸，绝不能因为前台包名是自己就放过。
                    val windowed = LockScreenActivity.inMultiWindow
                    // 判定口径：**只有锁屏流程自己的界面在前台**才算"没逃逸"。
                    //
                    // 旧实现是「前台包名 == 本包就放过」，漏洞在于本包不止锁屏一个页面：
                    // 家长把本应用设为默认桌面后，孩子按 HOME 就回到 [KidHomeActivity]（儿童桌面），
                    // 包名依旧是自己 —— 看门狗因此 continue，锁屏再也不重拉，孩子可以在桌面上
                    // 自由活动（点白名单应用、翻列表），锁屏等于被按 HOME 一下撕掉。
                    // 这是「锁屏后无法退出」这条保底的真实突破口（本类注释里"自家界面永远不拦"
                    // 的初衷只是避免重复拉起锁屏页，被包名口径放大成了整类放行）。
                    //
                    // 现在只认两个页面：
                    //   - [LockScreenActivity] 自己在前台：重复拉起无意义；
                    //   - [GuardUnlockActivity] 在前台：家长正在输密码，重拉会把输入框踢掉，
                    //     等于把解锁流程锁死（必须放过，否则家长永远解不开）。
                    // 其余本包页面（儿童桌面 / 消息 / 授权引导）在前台 → 继续往下重拉锁屏。
                    val onLockFlow = LockScreenActivity.visible || GuardUnlockActivity.visible
                    if (!windowed && onLockFlow) continue
                    // 已逃逸到非本应用（桌面/其它 app），或锁屏页被挤成了半屏：
                    // 1) 无障碍服务可后台启动 Activity（豁免华为后台限制），把锁屏 Activity 顶回前台 ——
                    //    这是非 DO 设备后台锁屏唯一可靠的前台化手段；硬锁设备同样受益。
                    // 2) 设备管理员已激活时，借系统级 lockNow() 重新唤起系统锁屏（keyguard），
                    //    物理封死手势导航/最近任务，是「非 DO 设备」上锁屏被划走后最可靠的兜底。
                    //    仅当设备管理员可用时调用；LEGACY 设备无此能力，交由悬浮窗地板兜底。
                    // 3) 委托 presenter.reenforce() 确保悬浮窗地板在位（非硬锁设备的真正锁定层）。
                    // 不再 performGlobalAction(HOME)：它反复把用户顶回桌面、且在非硬锁设备上无效，
                    // 只会和窗口切换事件叠加成日志风暴（这正是此前「锁屏崩 + 刷屏」的诱因之一）。
                    if (windowed) {
                        Logger.w(TAG) { "lock watchdog: lock screen windowed (split/freeform), re-arming fullscreen" }
                    }
                    if (!LockScreenActivity.visible || windowed) {
                        runCatching {
                            LockScreenActivity.show(
                                this@OperationGuardAccessibilityService,
                                lockController.state.value.primaryDetail
                            )
                        }.onFailure { Logger.w(TAG, it) { "lock watchdog re-show activity failed" } }
                    }
                    // 借系统级 lockNow() 重新唤起系统锁屏（keyguard）作为兜底，但必须经
                    // SystemKeyguardBridge 记账与守卫：安全锁（PIN/图案/密码）设备一律不压，
                    // 否则解锁会陷入「应用撤不掉系统锁」的永久死锁
                    // （见 SystemKeyguardBridge.systemLockAllowed 说明）。直接调 admin.lockNow()
                    // 会绕过守卫与记账，是此前死锁的旁路来源，必须收口到 bridge。
                    if (admin.can(Capability.LOCK_NOW)) {
                        keyguardBridge.pushSystemLock()
                    }
                    runCatching { lockScreenPresenter.reenforce() }
                        .onFailure { Logger.w(TAG, it) { "lock watchdog reenforce failed" } }
                } catch (t: Throwable) {
                    // 单拍异常只丢一跳而非整轮死掉：否则看门狗静默死亡 → 解锁逃逸无法自愈、全端误判无障碍已失活。
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    Logger.e(TAG, t) { "lock watchdog tick failed" }
                }
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
        // 收到窗口事件本身就是"服务确实在工作"的最强证据。
        // 这里是看门狗之外的第二条续约通道：即便看门狗循环因故停止，
        // 只要还有窗口事件在被分发，存活判定就不会误翻转为"已死亡"。
        GuardA11y.markAliveTick(this)
        // 整机管控暂停：无障碍拦截全部解除，设备回到完全可用（家长/操作员可自由进设置）。
        // 这是"取消应用管控后设备应可正常使用"的正确落点——释放设备由总开关驱动，
        // 而非只清空应用黑白名单（名单与拦截是两套独立机制）。
        if (!guardMasterSwitch.active.value) return
        // 只关心前台窗口切换（进入/退出某个 app 的设置、安装、卸载或受管控应用）
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString() ?: return
        val previousPkg = lastForegroundPkg
        // 维护最近前台包名（供应用管控采样与前台判定使用）。
        // 注意：锁屏看门狗**已不再**用它对账 —— "本包在前台"这个口径会把儿童桌面
        // 误当成锁屏页而放过，正是按 HOME 即可退出锁屏的根因。
        lastForegroundPkg = pkg
        // 同步给对外只读出口：主数据源不可用时供前台判定兜底（见 AccessibilityForegroundSource）。
        AccessibilityForegroundSource.lastForegroundPackage = pkg
        // 自家界面永远不拦（避免拦截解锁框/权限引导自身，造成死循环）
        if (isOwnPackage(pkg)) return

        // 前台真的换了应用 → 立刻叫醒守护服务跑一轮采样。
        //
        // 为什么必须在这里叫：主循环是 15 秒定时采样，孩子点开一个「额度已用完」的应用时，
        // 最多要等 15 秒才会被判定并顶回 —— 这 15 秒他能照玩，家长端却显示已限额。
        // 弱网把主循环拖慢时窗口还会更长。这里在窗口切换的瞬间拿到前台包名，
        // 交给 [GuardTickSignal]（自带 1 秒节流，避免连点图标触发风暴）。
        //
        // 判定本身仍由 GuardService 统一执行：本服务只负责「该醒了」，不复制一套限额逻辑，
        // 否则两处口径迟早分叉（黑名单/Kiosk/限额的优先级只在 PolicyEngine.tick 里定义）。
        if (pkg != previousPkg) {
            tickSignal.request("foreground:$pkg")
            // 再补一次：UsageStats（queryEvents）对「刚切过来的应用」有几秒写延迟，
            // 立刻那一轮很可能还解析出**上一个**前台包名（ForegroundAppMonitor 在
            // 窗口内无事件时沿用 lastKnown），于是判定落空。
            // 1.5 秒后补一发即可覆盖绝大多数机型；每次切换只保留最新一个补发任务，
            // 孩子连续切应用不会堆出几十个协程。
            tickFollowUpJob?.cancel()
            tickFollowUpJob = scope.launch {
                delay(FOLLOW_UP_TICK_MS)
                tickSignal.request("foreground:$pkg:followUp")
            }
        }

        // 应用访问放行到此为止：前台一旦不是被放行的那个包，上次授权即结束。
        // 这是"每次打开都要家长验证"的执行点，必须先于下方一切判定 —— 否则孩子
        // 回到桌面后再点同一个应用就能免密进去，门禁形同虚设。
        manager.revokeAppAccessIfNot(pkg)

        // ---- 受控态（锁定 / 时间到）拦截：双击即闪退，连点弹提示 ----
        // 双层防御的第二层：即便孩子绕开锁屏落到自家桌面（KidHome），只要点开任意第三方应用
        // 就立即顶回桌面（"闪退"），从源头实现"退出锁屏仍无权使用应用"。
        // 不再在窗口回调里反复重拉锁屏 Activity —— 那会把孩子从受控桌面又拽回锁屏页，
        // 与"受控桌面"的设计相悖；真正的解锁由家长在控制端操作，而非本地重拉锁屏。
        // 锁屏被划走后的系统级兜底（lockNow / 悬浮窗地板）由 [startLockWatchdog] 周期负责。
        if (lockController.state.value.locked) {
            // 自家界面（锁屏页 / 桌面 / 管理页）永远安全：不拦不顶，否则会拦截自身造成死循环。
            if (isOwnPackage(pkg)) return
            // 系统关键包（输入法 / 拨号 / SystemUI / 默认 Launcher 等）永不顶回，防砖。
            if (appGuard.isSystemCritical(pkg)) return
            // 临时解锁窗口内的目标应用放行（孩子端申请、家长已批准的那个应用）。
            if (lockController.isTempUnlockActive(pkg)) return
            // 其余任何第三方应用被拉到前台 = 试图在受控态下使用应用 → 顶回（闪退）+ 连点提示。
            handleControlledModeBounce(pkg)
            return
        }

        // 当前前台不在管控范围 → 放行。
        // S11：判定提前到密码闸状态检查之前 —— 拦不拦是「这个界面是否要保护」的问题，
        // 怎么拦是「密码闸处于什么状态」的问题，两者解耦后，密码未设置时设置页仍有兜底拦截。
        // R-P2-8：顺手做一次"桌面列表"的定期重解析（默认为 10 分钟一次，开销一次时钟比较），
        // 孩子运行期切换默认桌面后不会长期漏掉"长按图标 → 卸载"这条入口。
        maybeRefreshLauncherInfo()
        val className = event.className?.toString().orEmpty()
        val reason = decide(pkg, className) ?: return

        // 拦截到「装/卸载入口」时立刻给家长留痕：孩子尝试卸载是最需要家长介入的信号之一，
        // 只在行为流水里记一笔等于没人看得见（与 PadGuardDeviceAdminReceiver 同口径）。
        if (reason == REASON_INSTALLER || reason == REASON_UNINSTALL_ENTRY) {
            uninstallDefense.reportUninstallAttempt(
                if (reason == REASON_UNINSTALL_ENTRY) "launcher:$pkg/$className" else "installer:$pkg"
            )
        }

        // ---- 非 DO 防卸载兜底（不依赖操作密码）----
        // 设备管理器已激活时，系统会在卸载本应用前先要求「取消设备管理员」，
        // 而 PadGuardDeviceAdminReceiver.onDisableRequested 返回警告会阻止取消 —— 形成系统级双保险。
        // 这里再挡一层「设置 → 应用信息/卸载」页，让孩子即使没开操作密码也进不去卸载入口。
        // DO 下由 DISALLOW_APPS_CONTROL 接管，不重复处理；应用详情页命中本分支（HOME 顶回，
        // 防止 BACK 后经最近任务回跳），其余设置页走下方 S11 密码未设降级拦截，两者互补。
        if (appGuard.selfProtectApplies() && isAppDetailsPage(event)) {
            val now = SystemClock.elapsedRealtime()
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

        // ---- 设备管理员停用页拦截（非 DO 防卸载加固）----
        // 即便应用详情页被挡，孩子仍可经「设置 → 安全/设备管理员」直接停用本应用，
        // 而停用设备管理员后系统即可正常卸载——这正是"密码没生效"的根路径。
        // 当家长要求「卸载需密码」且本地尚未设密码时，进入即顶回并升级为高危告警（节流）。
        // 装机向导豁免期内放行，避免阻断家长的授权办理流程。
        if (appGuard.uninstallNeedsPassword() && !manager.isEnabledNow() &&
            isDeviceAdminSettingsPage(event)
        ) {
            if (gateSuspension.isActive()) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastSelfProtectAt > SELF_PROTECT_DEBOUNCE_MS) {
                lastSelfProtectAt = now
                Logger.i(TAG) { "uninstall defense: blocking device-admin settings page (deactivation entry)" }
                performGlobalAction(GLOBAL_ACTION_HOME)
                uninstallDefense.reportUninstallGateMissing()
            }
            return
        }

        // 3a) 取消授权的应用：硬拦截（最严），与全局启动闸门、是否设了家长密码都无关。
        // 门禁浮层**直接盖在被管控应用之上**：应用留在前台但被浮层全屏遮蔽、不可交互，
        // 孩子看到的是"应用上面浮了一层密码框"；输对只放行这一个包并收起浮层，
        // 取消 / 点框外则由 [AppAccessGateActivity.exitAndFinish] 先 HOME 压回后台、
        // 再延迟 kill 该应用（退出应用）。
        // 这里不做时间去抖：浮层是 singleTask，重复 show 只是更新、不会风暴；
        // 去抖反而会压制"关框后再次打开"的拦截，重新制造绕过窗口。
        if (reason == REASON_HIDDEN_APP) {
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
        // 判定已在 decide() 里完成（这个界面是否需要保护），此处只决定"没凭据时怎么拦"。
        // 没有可比对的家长密码，无法拉起验证框，但**绝不能静默放行**——
        // 那等于"家长没设密码，孩子就能随便开受管控应用 / 装卸载"，正是"管控形同虚设"的成因。
        // 一律顶回（设置页用 BACK、其余用 HOME），并提醒家长尽快补设密码（24h 至多一次）。
        // 取消授权的应用（REASON_HIDDEN_APP）在此之前的专属分支已先行处理，不会走到这里。
        if (!manager.isEnabledNow()) {
            val now = SystemClock.elapsedRealtime()
            if (pkg == lastTriggerPkg && now - lastTriggerAt < DEBOUNCE_MS) return
            lastTriggerAt = now
            lastTriggerPkg = pkg
            Logger.i(TAG) { "guarded foreground: $pkg reason=$reason, guard password not set, bouncing back" }
            // 设置页 / 桌面卸载浮层用 BACK（关掉浮层本身；HOME 会留下那个弹窗）；
            // 受管控应用 / 安装器用 HOME。
            performGlobalAction(bounceActionFor(reason))
            // 家长要求「卸载需密码」却尚未设密码时，把这条"仅顶回、不告警"的盲区升级为高危告警，
            // 让"密码形同虚设"这一最危险状态在家长端可见（节流 1h，避免刷屏）。
            if (appGuard.uninstallNeedsPassword()) uninstallDefense.reportUninstallGateMissing()
            reportPasswordNotSet()
            return
        }

        // 处于解锁窗口内 → 放行**设备操作类**界面（安装/卸载/设置）。
        //
        // 但绝不能把"打开受管控应用"一起放行：家长刚才输密码是为了装软件 / 改设置，
        // 若把 10 分钟窗口当整机开闸，孩子在窗口内就能随便打开任何被禁应用 ——
        // 这正是"权限放大"型绕过。打开单个应用的授权由 isAppAccessGrantedNow
        // 在 decide() 里按包名单独判定（见下方注释），与本窗口互不推导。
        if (reason != REASON_CONTROLLED_APP && manager.isUnlockedNow()) return
        // 是否在「试错锁定」冷却期：锁定期间仍要顶回（否则等于开闸），
        // 但不再拉起密码框——孩子此刻无论如何验不过，弹框只会被狂点。
        val lockedOut = manager.isLockedOutNow()

        Logger.i(TAG) { "guarded foreground: $pkg reason=$reason, intercepting (lockedOut=$lockedOut)" }
        // 1) 顶回当前操作：
        //    - 设置页用 BACK（部分 ROM 用 HOME 会卡在最近任务）；
        //    - 安装器/卸载用 HOME（BACK 后会经最近任务回跳）；
        //    - 受管控应用**不顶回**：门禁浮层直接盖在应用之上（应用被全屏遮蔽、不可交互）。
        //      输对密码只放行这一个包；取消 / 点框外由 exitAndFinish 先 HOME 再延迟 kill，
        //      不会出现"关掉框应用原地复活"（kill 前留出的延迟保证 HOME 已把应用压回后台）。
        //      若 kill 因极端时序失败，本服务在下一个窗口事件会再次拦截并重弹门禁，闭环兜底。
        if (reason == REASON_CONTROLLED_APP) {
            // 锁定态没有可弹的验证框，此时才需要把应用顶回桌面，避免"浮层下应用仍可用"的错觉
            if (lockedOut) performGlobalAction(GLOBAL_ACTION_HOME)
        } else {
            performGlobalAction(bounceActionFor(reason))
        }
        // 2) 仅在未锁定时拉起验证界面，让家长可以放行（锁定态跳过，避免狂点弹框）
        if (!lockedOut) {
            runCatching {
                if (reason == REASON_CONTROLLED_APP) {
                    // 打开受管控应用 → 盖在应用之上的门禁浮层（输对只放行这一个包）
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
        val now = SystemClock.elapsedRealtime()
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
     * 当前前台是否是「设置 → 设备管理员 / 安全 → 设备管理员」页。
     *
     * 这是停用本应用设备管理员、进而解除卸载防护的唯一切口。无障碍事件拿不到 intent data，
     * 故按「设置包 + 设备管理员相关类名」匹配；仅命中设备管理员专属页，不误伤其它设置项
     * （如 WiFi / 蓝牙），把对孩子正常使用的侵扰降到最低。
     */
    private fun isDeviceAdminSettingsPage(event: AccessibilityEvent): Boolean {
        val pkg = event.packageName?.toString() ?: return false
        if (pkg !in GUARDED_SETTINGS_PACKAGES) return false
        val cls = event.className?.toString().orEmpty()
        return cls.contains("DeviceAdmin") ||
            cls.contains("ManageDeviceAdmin") ||
            cls.contains("DeviceAdminSettings")
    }

    /**
     * 各类被拦界面该用哪个全局动作"顶回"。
     *
     * 设置页与桌面浮层用 BACK：前者部分 ROM 用 HOME 会卡在最近任务，
     * 后者需要把弹窗本身收起来（HOME 只是回到桌面，弹窗可能还在）。
     * 安装器与受管控应用用 HOME：BACK 之后能经最近任务回跳。
     */
    private fun bounceActionFor(reason: String): Int =
        if (reason == REASON_SETTINGS || reason == REASON_UNINSTALL_ENTRY) {
            GLOBAL_ACTION_BACK
        } else {
            GLOBAL_ACTION_HOME
        }

    /**
     * 解析设备上**所有**能当桌面的包（包名 → HOME Activity 类名）。
     *
     * 为什么不能用 `getHomeActivities`：API 33 已移除。改用 `queryIntentActivities(HOME, MATCH_ALL)`，
     * 它能把系统桌面、第三方桌面、儿童桌面一并拿到（R-P2-8：只 arm 默认桌面时，
     * 孩子把默认桌面切到另一个就能用"长按图标 → 卸载"绕过密码）。
     *
     * 解析失败或结果为空时置空集合 —— 此时判定器只按类名识别卸载界面，
     * 宁可少拦一层浮层，也绝不把整个桌面当成卸载入口反复弹密码框（那会让设备没法用）。
     */
    private fun refreshLauncherInfo() {
        runCatching {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolved = packageManager
                .queryIntentActivities(homeIntent, PackageManager.MATCH_ALL)
                .mapNotNull { it.activityInfo }
                .filter { it.packageName.isNotBlank() && !isOwnPackage(it.packageName) }
                .associate { it.packageName to it.name }
            launcherHomeClasses = resolved
            lastLauncherResolveElapsed = SystemClock.elapsedRealtime()
            if (resolved.isEmpty()) {
                Logger.w(TAG) { "no launcher resolved: uninstall-entry detection degrades to class-name only" }
            } else {
                Logger.i(TAG) { "uninstall-entry detection armed for launchers=$resolved" }
            }
        }.onFailure { Logger.w(TAG, it) { "resolve launchers failed" } }
    }

    /**
     * 定期重解析桌面列表（R-P2-8）。
     *
     * 默认桌面可能在运行期被切换（孩子改设置即可，无需任何权限），
     * 只在 `onServiceConnected` 解析一次会一直指向旧包名。
     * 10 分钟一次足够：切换桌面后最多 10 分钟内补齐 arm 集合，
     * 而每次窗口事件只多一次 `elapsedRealtime()` 比较，开销可忽略。
     */
    private fun maybeRefreshLauncherInfo() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLauncherResolveElapsed < LAUNCHER_RESOLVE_INTERVAL_MS) return
        lastLauncherResolveElapsed = now
        refreshLauncherInfo()
    }

    /**
     * 判定当前前台包名是否需要拦截。
     *
     * @param pkg 前台包名
     * @param className 前台窗口类名（仅用于识别「桌面弹出的卸载浮层」，不读取任何界面内容）
     * @return 拦截原因；`null` 表示放行。
     */
    private fun decide(pkg: String, className: String): String? {
        // 不要再对整段判定做 runCatching{...}.getOrNull()：一旦任意分支抛异常，getOrNull 会把异常
        // 吞成 null，而调用方把 null 当作「放行」—— 这是 fail-open 架构缺陷，会让最关键的
        // 设置/安装器/卸载/取消授权分支在异常时静默失守。
        // 改为 fail-closed：异常时宁可拦下当前前台应用（等状态读取恢复），也绝不静默放行。
        return try {
            // 1) 改系统设置 —— 不可关闭：这里能禁用本无障碍服务，放开等于管控自毁
            if (pkg in GUARDED_SETTINGS_PACKAGES) return REASON_SETTINGS
            // 2) 安装 / 卸载确认 —— 家长可在控制端开关「安装/卸载确认需密码」；
            //    安装或卸载任一开关开启时（默认都开启）拦截安装器/安全中心包，两者都关则放行。
            if (AppGuardPolicyStore.isInstallerPackage(pkg) &&
                (appGuard.installNeedsPassword() || appGuard.uninstallNeedsPassword())
            ) return REASON_INSTALLER
            // 2b) 「桌面长按图标 → 卸载」—— 2026-11-02 事故的真实入口。
            //     这条路径的窗口事件包名是**桌面启动器**，既不是设置也不是安装器，
            //     旧判定直接放行 → 孩子长按一次就把管控删干净，全程没有密码框。
            //     判定器只比对包名与窗口类名（见 UninstallEntryDetector 的类注释），
            //     不读界面内容，因此不违反无障碍设置项里对外承诺的隐私口径。
            if (appGuard.uninstallNeedsPassword() &&
                UninstallEntryDetector.isUninstallSurface(pkg, className, launcherHomeClasses)
            ) return REASON_UNINSTALL_ENTRY
            // 3) 应用访问放行：家长刚在门禁里针对**这个包**验证通过 → 放行。
            //    这是"打开这一个应用"的授权，与设备操作解锁窗口（isUnlockedNow）无关：
            //    它不放行安装/卸载/改设置，也不放行其它应用，到期即失效。
            //    缺了这一步，应用会在启动瞬间被本服务再次顶回 —— 表现为"密码输对了却打不开"。
            if (manager.isAppAccessGrantedNow(pkg)) return null
            // 3a) 取消授权的应用（hidden）：无论"打开应用也要密码"开关是否开启、无论是否已设家长密码，
            //     打开都必须先经过门禁（输对家长密码才放行）。这是"授权已取消"的底线语义——
            //     它不依赖全局启动闸门（默认关闭会完全失效），也不因未设密码而漏放（最严）。
            //     isHiddenControlled 已排除系统关键包（防砖名单）。
            if (appGuard.isHiddenControlled(pkg)) return REASON_HIDDEN_APP
            // 3b) 「打开应用也要密码」全量启动闸门（非 DO 软拦截形态）。
            //    非 DO 设备上打开任意非系统应用都要先输管理密码，否则默认黑名单模式下
            //    新装应用天然"已授权"、完全不受闸门约束 → 非 DO "装完即玩"（R-P2-19）。
            //    DO 设备由系统隐藏/挂起负责，此处仅对 isControlled 应用施加闸门（保留原口径）。
            //    注意：解锁窗口（isUnlockedNow）对本分支不绕过——受控/全量闸门本就要求逐包授权，
            //    与 R-P1-3「解锁窗口仅放行设备操作类」一致。
            if (appGuard.launchBlockEnabled() && !appGuard.isSystemCritical(pkg)) {
                val fullGate = !appGuard.isDeviceOwner()
                if (fullGate || appGuard.isControlled(pkg)) {
                    return REASON_CONTROLLED_APP
                }
            }
            null
        } catch (t: Throwable) {
            // fail-closed：决策逻辑异常时默认拦下，绝不静默放行（闸门安全默认）。
            Logger.e(TAG, t) { "decide() threw, fail-closed to intercept: $pkg" }
            REASON_CONTROLLED_APP
        }
    }

    /**
     * 受控态期间的"应用使用拦截"统一处理：闪退（顶回桌面）+ 连点升级提示。
     *
     * 判定入口见上方锁定时分支：凡是"锁定态下的第三方应用前台"都走到这里，不再区分名单/黑白，
     * 因为受控态（时间到 / 家长锁）语义就是"整机不可用"，连白名单应用也不该被打开
     * （即需求里的"退出锁屏仍无权使用应用"）。
     *
     * - 未达连点阈值：直接 [performGlobalAction]`GLOBAL_ACTION_HOME` 顶回桌面（"闪退"），
     *   不依赖去抖——去抖会压制"关掉提示框后再点"的拦截，重新制造绕过窗口；
     * - 达到阈值：弹"您当前无权限操作"覆盖层（明确反馈、避免盲目闪烁），同时仍把应用顶回桌面，
     *   确保应用不会停留在前台被使用，随后复位计数，避免弹窗期间反复触发。
     */
    private fun handleControlledModeBounce(pkg: String) {
        // 连点计数窗口同样走单调轴：回拨墙钟会让"距上次点击"变负，计数永远清不掉（新增缺陷 I）。
        val elapsedNow = SystemClock.elapsedRealtime()
        val hitThreshold = runCatching { blockCounter.record(pkg, elapsedNow) }.getOrDefault(false)
        if (hitThreshold) {
            blockCounter.reset(pkg, elapsedNow)
            runCatching { noPermissionOverlay.show() }
                .onFailure { Logger.w(TAG, it) { "no-permission overlay show failed" } }
            Logger.i(TAG) { "controlled-mode: $pkg hit bounce threshold, showing no-permission overlay" }
        }
        // 无论是否弹窗，都把应用顶回桌面，保证"无权使用"落到实处。
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    companion object {
        private const val TAG = "GuardA11y"

        /**
         * 锁屏看门狗 Job（进程级单例）。
         *
         * 必须放在伴生对象：循环跑在 `@ApplicationScope` 上，生命周期等于进程，
         * 而 Service 实例会被系统反复重建（设置页开关、ROM 重绑、崩溃重启）。
         * 放实例字段时新实例看不到旧 Job，既取消不掉也判定不到"已在运行"，
         * 每重建一次就多一条 500ms 循环，且旧循环持有已销毁的 Service 继续拉锁屏页。
         */
        private var watchdogJob: Job? = null

        /**
         * 看门狗内「重试拉起守护」的节流时间戳（单调轴）。
         * 守护持续未起时才每 [GUARD_RELAUNCH_INTERVAL_MS] 重试一次，
         * 避免 `startForegroundService` 被 ROM 持续拒绝时每 500ms 刷一次日志/IPC。
         */
        private var lastGuardRelaunchAtMs = 0L

        /** 看门狗重试拉起守护的最小间隔：5s（守护起不来时不过度刷 IPC） */
        private const val GUARD_RELAUNCH_INTERVAL_MS = 5_000L

        /**
         * 前台切换后的补唤醒延迟：1.5 秒。
         *
         * 取这个数的依据：`UsageStatsManager.queryEvents` 对刚发生的 ACTIVITY_RESUMED
         * 普遍在 1 秒内可见，1.5 秒足够跨过写延迟，又远小于原来的 15 秒采样周期。
         * 再长就会让"点开超额应用"的可玩窗口重新变大。
         */
        private const val FOLLOW_UP_TICK_MS = 1_500L
        private const val DEBOUNCE_MS = 1500L

        /** 锁屏看门狗轮询间隔：锁定态下每隔这么久核对一次前台，逃逸即拉回（快滑最多 500ms 内回锁屏）。 */
        private const val LOCK_WATCH_INTERVAL_MS = 500L

        /** 自检保护(防卸载)去抖间隔 */
        private const val SELF_PROTECT_DEBOUNCE_MS = 1200L

        /**
         * 桌面列表重解析间隔（R-P2-8）。
         *
         * 默认桌面可在运行期被切换，只在服务连接时解析一次会长期指向旧包名，
         * 等于"长按图标 → 卸载"这条入口在切换后重新敞开。10 分钟足够兜住，
         * 且每次窗口事件只多一次时钟比较，没有性能代价。
         */
        private const val LAUNCHER_RESOLVE_INTERVAL_MS = 10 * 60 * 1000L

        private const val REASON_SETTINGS = "settings"
        private const val REASON_INSTALLER = "installer"
        /** 桌面长按图标弹出的卸载浮层（包名是桌面，不是安装器）——事故入口，见 decide() 2b */
        private const val REASON_UNINSTALL_ENTRY = "uninstall-entry"
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
