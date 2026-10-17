package com.padguard.child.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.padguard.child.MainActivity
import com.padguard.child.R
import com.padguard.child.capture.CaptureConsentPrompter
import com.padguard.child.capture.CaptureConsent
import com.padguard.child.capture.AccessibilityScreenshot
import com.padguard.child.capture.MediaUploadRetry
import com.padguard.child.capture.PhotoCapture
import com.padguard.child.capture.ScreenCaptureService
import com.padguard.child.time.TimeOffsetPrefs
import com.padguard.child.capture.ScreenCaptureSession
import com.padguard.child.guard.AppGuardPolicyStore
import com.padguard.child.guard.EyeProtectionOverlay
import com.padguard.child.guard.GuardA11y
import com.padguard.child.guard.GuardPasswordManager
import com.padguard.child.maintain.AppInstaller
import com.padguard.child.monitor.AppInventoryCollector
import com.padguard.child.monitor.DeviceSnapshotCollector
import com.padguard.child.AppForeground
import com.padguard.child.monitor.ForegroundAppMonitor
import com.padguard.child.permission.PermissionGranter
import com.padguard.child.monitor.LocationCollector
import com.padguard.child.monitor.LocationOutcome
import com.padguard.child.monitor.LocationAutoReporter
import com.padguard.child.ui.block.AppBlockActivity
import com.padguard.child.ui.lock.LockScreenActivity
import com.padguard.child.usagesession.UsageSessionManager
import com.padguard.child.ui.lock.LockScreenPresenter
import com.padguard.child.ui.lock.SystemKeyguardBridge
import com.padguard.child.ui.message.MessageActivity
import com.padguard.child.ui.overlay.WatermarkOverlay
import com.padguard.core.common.Logger
import com.padguard.core.common.TimeProvider
import com.padguard.core.data.model.Heartbeat
import com.padguard.core.data.model.LogType
import com.padguard.core.data.db.ParentMessageEntity
import com.padguard.core.data.repository.AuthRepository
import com.padguard.core.data.repository.CommandRepository
import com.padguard.core.data.repository.LogRepository
import com.padguard.core.data.repository.MessageRepository
import com.padguard.core.data.repository.PolicyRepository
import com.padguard.core.data.repository.UnlockRequestRepository
import com.padguard.core.data.repository.UsageRepository
import com.padguard.core.engine.PolicyEngine
import com.padguard.core.engine.admin.DeviceAdminBridge
import com.padguard.core.engine.command.EngineEffect
import com.padguard.core.engine.enforcer.LimitVerdict
import com.padguard.core.engine.enforcer.MonitoringRuntime
import com.padguard.core.engine.lock.LockController
import com.padguard.core.transport.Downlink
import com.padguard.core.transport.RemoteDataSource
import com.padguard.core.transport.TransportSettings
import com.padguard.core.transport.http.ApiCode
import com.padguard.core.transport.http.ApiResult
import kotlinx.coroutines.sync.withLock
import com.padguard.core.transport.http.CommandsResponse
import com.padguard.core.transport.http.CredentialStore
import com.padguard.core.transport.http.PolicyFetchResult
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * 常驻守护服务 —— 被管控端的运行时心脏。
 *
 * 它是**唯一**的调度中枢，承担五件事：
 *
 * | 职责 | 触发方式 | 说明 |
 * |---|---|---|
 * | 周期采样与锁定判定 | 基准 15s 定时器 | 调 [PolicyEngine.tick]，决定锁屏/拦截 |
 * | 心跳上报 | 策略下发的心跳间隔 | 断线时静默失败，不阻塞其它节奏 |
 * | 策略自检重压 | 5 分钟 | 把被用户改回去的限制重新压上 |
 * | 篡改自检 | 1 分钟 | 改时间/root/开发者选项，只上报不阻止 |
 * | 副作用落地 | 订阅 [PolicyEngine.effects] | 锁屏页、截屏、定位等需要平台能力的动作 |
 *
 * ## 为什么用"单循环 + 单调到期时间"而不是多个定时协程
 * 心跳 30s、日志 5min、自检 5min、篡改 1min —— 四个独立 `while(true){ delay() }`
 * 看起来更直观，但会带来两个真实问题：
 * 1. 四条协程各自被 Doze 冻结/唤醒，醒来后同时开火，形成周期性的 binder 风暴；
 * 2. 任一条因未捕获异常静默死掉，外部完全看不出来（服务还在前台，通知还挂着）。
 *
 * 单循环把节奏收敛成一处，每个子任务用 [SystemClock.elapsedRealtime] 判断"到期没有"。
 * 用单调时钟而非墙钟是刻意的：学生改系统时间不会让心跳/自检节奏错乱，
 * 这和限额计时用同一套口径。
 *
 * ## 关于指令：这里**不能**再过 CommandGate（重要）
 * 时效/签名/幂等三关已经在 transport 层的 `dispatchCommand` 里走完了，
 * `Downlink.CommandReceived` 拿到手就是可信指令。
 * 若在这里再调一次 `CommandGate.evaluate`，`tryClaim` 会因为"已占位"返回 false，
 * 指令被判成 DUPLICATED 而**永远不执行** —— 现场表现是"管控端点了远程锁屏，设备毫无反应"，
 * 且日志里只有一行 duplicate，极难往回追。同理 [Downlink.CommandRejected] 的回执
 * transport 已经发过，这里只补一条本地日志，不重复 sendAck。
 */
@AndroidEntryPoint
class GuardService : Service() {

    @Inject lateinit var policyEngine: PolicyEngine
    @Inject lateinit var remote: RemoteDataSource
    @Inject lateinit var transportSettings: TransportSettings
    @Inject lateinit var foregroundApp: ForegroundAppMonitor
    @Inject lateinit var snapshot: DeviceSnapshotCollector
    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var policyRepository: PolicyRepository
    @Inject lateinit var logRepository: LogRepository
    @Inject lateinit var admin: DeviceAdminBridge
    @Inject lateinit var lockController: LockController
    @Inject lateinit var usageSessionManager: UsageSessionManager
    @Inject lateinit var permissionGranter: PermissionGranter
    @Inject lateinit var timeProvider: TimeProvider
    @Inject lateinit var unlockRequestRepository: UnlockRequestRepository
    @Inject lateinit var locationCollector: LocationCollector

    @Inject lateinit var locationAutoReporter: LocationAutoReporter
    @Inject lateinit var appInventory: AppInventoryCollector
    @Inject lateinit var commandRepository: CommandRepository
    /** 应用管控策略的本地镜像，供无障碍服务在非 DO 设备上做降级拦截 */
    @Inject lateinit var appGuardStore: AppGuardPolicyStore
    /** 锁屏呈现统一入口：前台走 Activity、后台走悬浮层（后台启动 Activity 会被系统拦截） */
    @Inject lateinit var lockScreenPresenter: LockScreenPresenter
    /** 系统 keyguard 生命周期：解锁自愈（待撤锁标记的追逐） */
    @Inject lateinit var keyguardBridge: SystemKeyguardBridge
    /** 护眼滤镜；与水印同为悬浮层，但互不相关（一个护眼、一个署名），分开管理 */
    @Inject lateinit var eyeProtectionOverlay: EyeProtectionOverlay

    /** 策略 → 镜像的同步协程；随服务生命周期终止 */
    private var appPolicyMirrorJob: Job? = null
    @Inject lateinit var usageRepository: UsageRepository
    @Inject lateinit var messageRepository: MessageRepository
    @Inject lateinit var photoCapture: PhotoCapture
    @Inject lateinit var appInstaller: AppInstaller
    @Inject lateinit var watermark: WatermarkOverlay
    @Inject lateinit var credentials: CredentialStore
    @Inject lateinit var guardPassword: GuardPasswordManager
    /** 媒体上传重试队列；解绑时随采集缓存一并清空 */
    @Inject lateinit var mediaRetry: MediaUploadRetry

    /**
     * 无障碍截屏通道：远程截屏优先走这里 —— 授权（启用无障碍）持久有效，
     * 不再向孩子弹"是否允许录制屏幕"；不可用时退回 MediaProjection 链路。
     */
    @Inject lateinit var accessibilityScreenshot: AccessibilityScreenshot

    /**
     * 当前是否"已绑定"（由 [observeBinding] 维护）。
     *
     * 未绑定时必须停止一切管控执行：没有家长就没有管控依据。
     * 若仍按本地陈旧缓存策略执行，会出现"家长端已看不到这台设备、孩子端却被锁死无法操作"的死锁；
     * 而系统级 lockNow() 一旦落下，应用自身再也解不开，只能靠 adb 救援。
     */
    private val boundNow = AtomicBoolean(false)

    /**
     * 服务自己的作用域，不用 `@ApplicationScope`。
     *
     * 这些循环必须随服务一起结束：若挂在应用级作用域上，服务被停掉后循环还在跑，
     * 重新启动服务就会叠加出第二套心跳与采样，表现为心跳频率莫名翻倍。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var mainLoop: Job? = null
    private var downlinkJob: Job? = null
    private var effectJob: Job? = null
    private var bindingJob: Job? = null
    /** 指令专用拉取循环（见 [startCommandLoop]） */
    private var commandJob: Job? = null

    private val transportStarted = AtomicBoolean(false)
    private val bootstrapped = AtomicBoolean(false)
    /** 安装/卸载完成后请求立即同步一次应用台账 */
    private val appSyncRequested = AtomicBoolean(false)
    /** 动态注册的装机/卸机监听；命中即置 [appSyncRequested]，由主循环在数秒内全量上报 */
    private var appChangeReceiver: BroadcastReceiver? = null

    /**
     * 上次已上报的「累计值」节流记忆（仅用于减少冗余日志，不参与正确性计算）。
     *
     * 孩子端上报的是「当天累计值」，服务端按「当天最大值」聚合，天然幂等：
     * 即使本进程重启丢了这份记忆、多报几条相同值的日志，服务端取到的仍是真实累计值，绝不会高估。
     * 这与旧版"增量 + 已上报量基准台账"有本质区别——旧台账一旦丢失就会把全量当成增量重复累加。
     */
    @Volatile private var lastReportedTotalMs = 0L
    @Volatile private var lastReportedDay = ""
    private val lastReportedAppMs = ConcurrentHashMap<String, Long>()

    /** 锁屏页当前是否已被拉起，避免每轮 tick 重复 startActivity 造成闪屏 */
    private var lockScreenShown = false
    private var lastBlockedPackage: String? = null

    // 通知栏展示用的运行时状态
    @Volatile private var online = false
    @Volatile private var lastHeartbeatOk = false

    /**
     * 上次兜底拉取指令的时刻（见 [drainPendingCommands]）。
     *
     * ## 为什么必须持久化
     * 旧实现初值取 `System.currentTimeMillis()`（进程内内存）：进程一旦被回收
     * （息屏后被 ROM 杀掉是常态），游标重置为"当前时刻"，**进程死亡期间下达的指令
     * 会被 `createdAt >= cursor` 永久过滤掉** —— 家长在孩子端息屏时点「解锁」，
     * 指令入库后轮询永远取不到，正是"解锁毫无反应"的隐蔽成因之一。
     * 现改为落盘：重启后从上次成功拉取处续接，漏网的指令自然被补拉。
     * 首次安装（无记录）取 0：拉回全部未过期 PENDING —— 服务端只返回 PENDING
     * 且指令有 expiresAt，量有界；去重在 CommandExecutor 内按 msgId 收敛。
     *
     * ## 为什么在 onCreate 里加载而不是字段初始化器
     * Android 组件（Service/Activity/Receiver）的实例化走
     * `AppComponentFactory.instantiateService → Class.newInstance()`，**发生在
     * `attachBaseContext()` 之前** —— 字段初始化器里碰任何 Context 方法都是
     * NPE。2026-10-17 线上事故：游标加载写在字段初始化器里，导致 GuardService
     * 每次启动都在 `<init>` 崩溃（进程连环重启），表现为"绑定成功但设备永远
     * 上不了线、守护进程没生效"。**所有 Context 相关初始化必须推迟到 onCreate
     * 或首次访问（by lazy）**，这条纪律对全部 Android 组件生效。
     */
    @Volatile private var lastCommandPullAtMs: Long = 0L

    /**
     * 指令轮询游标的落盘文件。
     *
     * 必须 by lazy：实例化期 Context 尚不可用（见 [lastCommandPullAtMs] 的事故说明），
     * 首次被访问时（drainPendingCommands / onCreate）服务已 attach，此刻才安全。
     */
    private val commandPollPrefs: SharedPreferences by lazy {
        applicationContext.getSharedPreferences(PREFS_COMMAND_POLL, Context.MODE_PRIVATE)
    }

    /**
     * 动态重启广播：监听「屏幕亮起 / 网络恢复」。
     *
     * ## 为什么用动态而非 Manifest 静态注册
     * `ACTION_SCREEN_ON` / `CONNECTIVITY_ACTION` 在 Android 8+ 已被禁止 Manifest 静态接收，
     * 只能动态注册——而动态接收器随进程存活：进程被强杀时它一起没。
     * 所以它的角色是**保活增强**而非「防强停」：
     * 覆盖「进程还在、但守护服务被 stopSelf / 异常退出」这类边缘场景，
     * 让「孩子一碰平板（亮屏）/ 一连网」就把服务拉回来，而非苦等看门狗的下一周期。
     *
     * ## 真正的「防强停」不在应用层
     * 设备被设置里「强制停止」会进入系统停止态，动态接收器、看门狗 JobScheduler、
     * START_STICKY、所有广播全部失效——这是 Android 安全模型，应用代码绕不过。
     * 唯一正解是设备成为 Device Owner（见 [com.padguard.core.engine.enforcer.SecurityEnforcer]
     * 的 DISALLOW_APPS_CONTROL，会把「强制停止」按钮置灰）。
     */
    private val restartReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val action = intent?.action ?: return
            if (!isRunning) {
                Logger.i(TAG) { "restart trigger ($action), guard not running -> restarting" }
                runCatching { start(applicationContext, "restart:$action") }
                    .onFailure { Logger.e(TAG, it) { "restart via broadcast failed" } }
                return
            }
            // 屏幕刚点亮：这是锁定覆盖最容易被撕开的时刻。
            //
            // 息屏期间按设计不铺悬浮地板（它带 FLAG_KEEP_SCREEN_ON，会把屏幕唤醒、
            // 破坏「锁屏并熄屏」），而锁屏 Activity 的后台抢占在 Android 10+ 上会被 ROM
            // 静默丢弃。于是孩子按下电源键的这一瞬间，屏幕上很可能什么都没有 ——
            // 等到下一个 15s 自检周期才补，已经够他点开一个应用了。
            // 这里在亮屏后立刻对账一次，把窗口从 15 秒压到百毫秒级。
            if (action == Intent.ACTION_SCREEN_ON) {
                // 稍作延迟：广播到达时 PowerManager.isInteractive 可能尚未翻转，
                // 立刻判定会误以为是息屏而跳过铺地板。
                Handler(Looper.getMainLooper()).postDelayed({
                    runCatching { lockScreenPresenter.reenforce() }
                        .onFailure { Logger.w(TAG, it) { "lock coverage recheck on screen-on failed" } }
                }, SCREEN_ON_RECHECK_DELAY_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 兜底拉取游标从盘上恢复。必须在 startCommandLoop 之前加载：字段初始化器
        // 不能碰 Context（实例化先于 attachBaseContext，见 lastCommandPullAtMs 注释），
        // 故推迟到这里；若懒加载到首次使用，进程重启后第一轮拉取会误用 0 把全部
        // 历史 PENDING 捞一遍。无记录时 getLong 默认 0 = 拉回全部未过期 PENDING，
        // 与首次安装语义一致。
        lastCommandPullAtMs = commandPollPrefs.getLong(KEY_LAST_PULL_AT, 0L)
        // 必须第一时间进入前台：Android 对 startForegroundService 后的宽限期只有 5 秒，
        // 超时直接 ForegroundServiceDidNotStartInTimeException 崩溃。
        // 所以先挂一个"启动中"的通知，真实状态等拿到数据后再刷。
        enterForeground()
        // 监听本机应用装卸：孩子手动安装/卸载是即时管理动作，命中即触发台账同步，
        // 避免家长端在 30 分钟周期内看到"幽灵应用"且对其授权无效。
        registerAppChangeReceiver()
        // 动态重启广播（屏幕亮起 / 网络恢复时立即把服务拉回，缩小脱管窗口）
        registerRestartReceiver()
        Logger.i(TAG) { "GuardService created, controlMode=${admin.refreshControlMode()}" }
        // 启动清理残留 PENDING 指令（认领了但未执行完就退出），使其可被服务端重发重新认领，
        // 避免崩溃的指令被 7 天幂等窗口判重丢弃而永久静默丢失。
        scope.launch { runCatching { commandRepository.resetInFlightOnStartup() } }
        isRunning = true
        // 注册看门狗：进程被系统/清理工具杀掉后由系统侧周期拉起（START_STICKY 并不保证重建）。
        // 放在 onCreate 而不是绑定流程里：即便这台设备还没绑定，守护也要在，
        // 否则"家长扫码绑定后立刻下发指令"会因为没有宿主而丢失。
        runCatching { GuardWatchdogService.schedule(this) }
            .onFailure { Logger.w(TAG) { "watchdog schedule failed: ${it.message}" } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val reason = intent?.getStringExtra(EXTRA_REASON).orEmpty().ifBlank { "unknown" }
        Logger.i(TAG) { "onStartCommand reason=$reason" }

        // 前台状态可能因进程重建而丢失，每次进来都确保一次（内部幂等）
        enterForeground()

        if (bootstrapped.compareAndSet(false, true)) {
            bootstrap(reason)
        } else if (reason == REASON_BOOT) {
            // 极少见：进程未死但收到开机广播，仍需走一次开机复位
            scope.launch { runSafely("onDeviceBoot") { policyEngine.onDeviceBoot() } }
        } else if (reason == REASON_FLUSH) {
            // 锁屏页刚提交了解锁申请：这类交互有时效，等下一个 60s 上报窗口太慢，
            // 家长可能要一分多钟后才看到，孩子会以为申请没发出去而反复提交。
            scope.launch { runSafely("flushNow") { uploadPending() } }
        } else {
            // 已在运行，外部只是想催一次策略对齐（如管理器刚激活、Kiosk 被意外退出）
            scope.launch { runSafely("applyOnKick") { policyEngine.applyCurrent(force = true) } }
        }

        // START_STICKY：被系统回收后自动重建。
        // 注意重建时 intent 为 null，所以所有初始化逻辑都不能依赖 intent 里的参数。
        return START_STICKY
    }

    /**
     * 孩子从最近任务里把 App 划掉时触发。
     *
     * ## 为什么要主动重启
     * "划掉任务"在很多 ROM 上会连同进程一起杀掉守护服务（不只是 Activity）。
     * 一旦服务没了，命令轮询与 MQTT 全部停摆：家长端看到的设备还"在线"，
     * 但锁屏/截屏/看屏指令全部石沉大海 —— 这正是"装了管控却管不住"的典型成因。
     *
     * ## 为什么要延后一秒
     * 系统清理任务栈与我们重启服务存在竞态：立刻 startForegroundService 时，
     * 进程往往正在被回收，启动会被拒或刚起又被收。稍作延迟让清理先完成，成功率明显更高。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Logger.w(TAG) { "task removed, restarting guard service" }
        android.os.Handler(mainLooper).postDelayed({
            runCatching { start(applicationContext, REASON_TASK_REMOVED) }
                .onFailure { Logger.e(TAG, it) { "restart after task removed failed" } }
        }, RESTART_DELAY_MS)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        runCatching { unregisterReceiver(restartReceiver) }
            .onFailure { Logger.w(TAG) { "unregister restart receiver failed: ${it.message}" } }
        Logger.w(TAG) { "GuardService destroyed" }
        // 周期定位必须跟着守护服务一起停：服务停掉通常意味着解绑 / 退出管控，
        // 此时继续采集位置既没有上报对象，也越过"家长未授权即采集"的边界。
        locationAutoReporter.stop()
        // 滤镜必须跟着服务一起撤：服务销毁意味着退出管控，
        // 留一层黄蒙蒙的遮罩在孩子屏幕上，家长会以为平板坏了。
        eyeProtectionOverlay.remove()
        scope.cancel()
        super.onDestroy()
    }

    // ==================== 启动流程 ====================

    /**
     * 启动顺序是有讲究的：**先落地本地策略，再连服务端**。
     *
     * 离线场景（换网、欠费、服务端故障）下设备必须仍然受管控，
     * 若反过来先等连接成功再 apply，一次断网就等于全面失管，这是不可接受的。
     */
    private fun bootstrap(reason: String) {
        // 自愈补授必须放在最前：通知权限一旦缺失，屏幕采集的引导通知孩子收不到，
        // 实时看屏就会永久卡在"连接中"，且平板上毫无提示。
        scope.launch { runSafely("ensurePermissions") { permissionGranter.ensureGranted() } }
        // 周期性位置上报：策略里 locationIntervalSec 的落地执行者（策略为 0 时自动不采）。
        // 放在这里而不是某个界面：守护服务是唯一长期存活的宿主，
        // 放在界面里孩子一按 Home 就断，轨迹数据会大面积缺失。
        scope.launch { runSafely("startLocationReporter") { locationAutoReporter.start() } }
        scope.launch {
            runSafely("applyCachedPolicy") {
                // 未绑定时不落地本地缓存策略：没有家长就没有管控依据，
                // 否则服务端清库后，孩子端会靠陈旧缓存继续锁屏，形成"家长看不到设备、平板锁死"的死锁。
                if (!authRepository.isBound.first()) {
                    Logger.w(TAG) { "not bound: skip cached policy, keep device unlocked" }
                    return@runSafely
                }
                if (reason == REASON_BOOT) {
                    policyEngine.onDeviceBoot()
                    logRepository.append(
                        type = LogType.DEVICE_BOOT,
                        payload = mapOf("controlMode" to admin.controlMode.value.name)
                    )
                } else {
                    policyEngine.applyCurrent(force = true)
                }
            }
        }

        observeEffects()
        observeDownlink()
        observeBinding()
        observeAppPolicyMirror()
        // 必须先等凭据内存快照从 DataStore 加载完成，再启动会发起鉴权请求的循环。
        // 否则首帧心跳 / 指令拉取会在 CredentialStore 尚未填充 deviceToken 时就发出，
        // 服务端判 TOKEN_INVALID(40101)，进而触发自愈解绑把有效凭据清掉（启动竞态自残）。
        scope.launch {
            runSafely("loadCredentials") { credentials.refresh() }
            // 绑定镜像自愈：暖令牌的前置门（CaptureConsent.isDeviceBound）在绑定成功时写入，
            // 但"旧版本升级上来、早已绑定却没写过镜像"的设备只有在这里能补上。
            // 缺省不写 false —— 未绑定设备的镜像本就是 false，无需擦写。
            if (credentials.isBound) {
                CaptureConsent.markDeviceBound(this@GuardService, true)
            }
            startMainLoop()
            startCommandLoop()
            // 解锁自愈：进程刚启动就可能带着"待撤锁"遗留（上次解锁时进程死掉 /
            // FSI 与直接启动都被 ROM 吞掉）。家长已解锁的意图不允许因为进程重启而丢失 ——
            // 此刻追一次（内部限频 + 幂等），剩下的交给看门狗与周期自检。
            runSafely("chasePendingUnlock") {
                if (!lockController.state.value.locked) {
                    LockScreenActivity.chaseUnlock(this@GuardService)
                }
            }
        }
    }

    /**
     * 绑定状态驱动通道启停。
     *
     * 未绑定时不该去连 MQTT：没有 deviceId 与凭据，连上也只会被 Broker 反复踢，
     * 徒增耗电和日志噪音。绑定完成的那一刻由这里自动接管，
     * 绑定页不需要知道"还要记得启动通道"。
     */
    private fun observeBinding() {
        bindingJob?.cancel()
        bindingJob = scope.launch {
            authRepository.isBound.distinctUntilChanged().collect { bound ->
                if (!bound) {
                    // 关键：没有绑定关系就绝不继续管控，并主动清空所有锁定原因。
                    // 绑定关系是服务端资产（除非家长手动解绑 / 服务端删除，否则一直存在），
                    // 但服务端清库或设备被解绑后，若本地仍按陈旧缓存策略执行，平板会陷入
                    // "家长端看不到设备、孩子端却锁死无法操作"的死锁 —— 而系统级 lockNow()
                    // 一旦落下，应用自身再也解不开（lockNow 只能锁、不能程序化解）。
                    boundNow.set(false)
                    lockController.releaseAll()
                    // 解绑即清空会话状态，避免陈旧会话继续驱动 UI 与锁定
                    usageSessionManager.reset()
                    // 解绑后彻底释放系统级管控（挂起/隐藏应用、相机/截屏、用户限制、
                    // 自动对时、自身卸载封锁，最后 relinquish Device Owner / 设备管理员），
                    // 让本机恢复为普通应用、可被家长端或用户在设置里正常卸载。
                    // 幂等：未持有对应权限的步骤被 guarded 静默收口为 Unsupported，不会抛异常。
                    // 这一步同时兜底"历史上已解绑却仍残留 DO/策略"的设备 —— 新版本一启动
                    // （或开机自启）即自动治愈，无需重新绑定再解绑。
                    runCatching { admin.releaseControl() }
                        .onSuccess { Logger.i(TAG) { "control released on unbound observe (${it.size} ops)" } }
                        .onFailure { t -> Logger.e(TAG, t) { "releaseControl failed on unbound observe" } }
                    Logger.w(TAG) { "device not bound: all app locks + DPM control released" }
                    return@collect
                }
                boundNow.set(true)
                startTransport()
                // 绑定成功即拉取会话：让「计时开始」确认弹窗尽快出现（若今日尚未确认）
                runSafely("sessionSyncOnBind") { usageSessionManager.refresh() }
            }
        }
    }

    private suspend fun startTransport() {
        if (!transportStarted.compareAndSet(false, true)) return
        runSafely("syncTime") {
            // 先校时：指令时效校验、限额日切都依赖校准后的墙钟，
            // 顺序颠倒会导致刚上线的一瞬间把正常指令误判成"已过期"。
            when (val r = remote.syncTime()) {
                is ApiResult.Success -> Logger.i(TAG) { "time synced, server=${r.value}" }
                else -> Logger.w(TAG) { "time sync failed, fall back to local clock" }
            }
        }
        runSafely("transportStart") { remote.start() }
        runSafely("policyRefreshOnConnect") { refreshPolicy() }
    }

    // ==================== 指令拉取循环 ====================

    /**
     * 独立的指令拉取循环 —— 实时性优化的关键。
     *
     * ## 为什么必须独立出来
     * 原先兜底拉取挂在心跳成功分支里，且被 `COMMAND_PULL_INTERVAL_MS = 60s` 限流，
     * 而心跳本身是 30s 一次。两条节奏叠加后，LOCK_SCREEN / UNLOCK / 截屏 /
     * 录屏这类"只下发一次"的指令最坏要 **60 秒**才落地 ——
     * 现场表现就是"家长点了锁屏，平板半天没反应"，这正是实时性差的主因。
     *
     * 更关键的是：MQTT 未启用（或 Broker 不可达）时，HTTP 拉取是**唯一**的指令通道，
     * 这条通道慢 = 所有远程操作都慢。家长端发起实时看屏时首帧也要等这条通道，
     * 所以"获取实时屏幕慢"和"锁屏慢"其实是同一个根因。
     *
     * ## 为什么用固定节奏轮询而不是长轮询
     * 长轮询（服务端挂起请求直到有指令）延迟更低，但会让 Tomcat 每设备常驻一个线程，
     * 规模化部署时代价高，且反向代理的空闲超时会把长连接掐断，排查成本极高。
     * 短轮询的请求体/响应体都是几十字节，局域网 RTT 通常 <5ms、外网 <100ms，
     * 3s 一跳即可把最坏延迟从 60s 压到 ~3s，是"可靠 + 够快"的折中。
     *
     * 在线时按 [COMMAND_PULL_INTERVAL_MS] 快拉；离线时放慢到 [COMMAND_PULL_IDLE_MS]，
     * 避免断网期间无意义地高频打日志。
     */
    private fun startCommandLoop() {
        commandJob?.cancel()
        commandJob = scope.launch {
            while (isActive) {
                // 只有确认在线才高频拉：离线时 pull 必然失败，快拉只会刷屏日志
                if (online) {
                    runSafely("drainPendingCommands") { drainPendingCommands() }
                    delay(COMMAND_PULL_INTERVAL_MS)
                } else {
                    delay(COMMAND_PULL_IDLE_MS)
                }
            }
        }
    }

    // ==================== 主循环 ====================

    private fun startMainLoop() {
        mainLoop?.cancel()
        mainLoop = scope.launch {
            var nextHeartbeat = 0L
            var nextSelfCheck = SystemClock.elapsedRealtime() + SELF_CHECK_INTERVAL_MS
            var nextTamperScan = SystemClock.elapsedRealtime() + TAMPER_SCAN_INTERVAL_MS
            var nextLogUpload = SystemClock.elapsedRealtime() + LOG_UPLOAD_MIN_INTERVAL_MS
            // 首次台账同步刻意提前：设备刚绑定/服务刚启动时家长往往正盯着应用列表，
            // 等 30 分钟会让「应用」Tab 一直空白；首报完成后回到正常周期。
            var nextAppSync = SystemClock.elapsedRealtime() + APP_SYNC_FIRST_DELAY_MS

            // 使用情况实时同步：独立于日志上报节奏，保证家长端用量数据近实时刷新
            var nextUsageSync = SystemClock.elapsedRealtime() + USAGE_SYNC_FIRST_DELAY_MS

            // 使用计时会话同步（三端一致计时）：拉取服务端权威剩余，驱动「计时开始」确认弹窗
            // 与屏幕正上方 10 分钟倒计时浮层；节奏与采样对齐即可，无需更密。
            var nextSessionSync = SystemClock.elapsedRealtime() + SESSION_SYNC_FIRST_DELAY_MS

            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                // C1'：monitoring() 与其余 7 个子任务同列，必须 runSafely 包裹。
                // 它读的是 MonitoringEnforcer 的内存快照，正常不会抛 —— 但主循环的死法
                // 永远来自"现在不会抛的代码将来会抛"，一次异常 = 服务活着但
                // 采样/心跳/自检/上报全停摆（类注释自述的最危险失效形态）。
                // 降级值用 MonitoringRuntime() 本身：它与 enforcer 的初始 runtime 同一构造，默认值单源。
                val monitoring = runSafely("monitoring") { policyEngine.monitoring() }
                    ?: MonitoringRuntime()

                // ---------- 每轮必做：采样 + 锁定判定 ----------
                // 未绑定时跳过：没有家长就没有管控依据，
                // 否则本地陈旧缓存策略会把已无归属的设备锁死（见 [boundNow] 的说明）。
                if (boundNow.get()) {
                    runSafely("tick") { doTick(collectForegroundApp = monitoring.collectAppUsage) }
                }

                // ---------- 心跳 ----------
                if (now >= nextHeartbeat) {
                    runSafely("heartbeat") { sendHeartbeat() }
                    nextHeartbeat = now + monitoring.heartbeatIntervalMs.coerceAtLeast(MIN_TICK_MS)
                }

                // ---------- 策略自检重压 ----------
                if (now >= nextSelfCheck) {
                    // force = true：这里的目的就是把"被用户绕过去的限制"重新压回来，
                    // 版本没变也必须重下，否则自检等于空转。
                    if (boundNow.get()) {
                        runSafely("selfCheck") { policyEngine.applyCurrent(force = true) }
                    }
                    nextSelfCheck = now + SELF_CHECK_INTERVAL_MS
                }

                // ---------- 篡改自检 ----------
                if (now >= nextTamperScan) {
                    runSafely("tamperScan") { policyEngine.scanTamper() }
                    nextTamperScan = now + TAMPER_SCAN_INTERVAL_MS
                }

                // ---------- 日志批量上报 ----------
                if (now >= nextLogUpload) {
                    runSafely("uploadPending") { uploadPending() }
                    nextLogUpload = now + monitoring.logUploadIntervalMs
                        .coerceAtLeast(LOG_UPLOAD_MIN_INTERVAL_MS)
                }

                // ---------- 应用台账全量同步 ----------
                // 安装/卸载指令执行完后会置 [appSyncRequested]，
                // 让家长在几秒内看到清单变化，而不是等满 30 分钟。
                if (now >= nextAppSync || appSyncRequested.getAndSet(false)) {
                    // 失败要快重试：开机后 15 秒那一次几乎必然撞上"网络还没就绪"，
                    // 若按 30 分钟周期重试，家长端会有半小时看不到应用列表、以为设备没连上。
                    val synced = runSafely("appSync") { syncAppInventory() } == true
                    nextAppSync = now + if (synced) APP_SYNC_INTERVAL_MS else APP_SYNC_RETRY_MS
                }

                // ---------- 使用情况实时同步 ----------
                // 独立于日志上报节奏，保证家长端「今日使用情况」近实时刷新
                if (now >= nextUsageSync) {
                    runSafely("usageSync") { syncUsageNow() }
                    nextUsageSync = now + USAGE_SYNC_INTERVAL_MS
                }

                // ---------- 使用计时会话同步（三端一致计时） ----------
                // 拉取服务端权威剩余：未激活 → 驱动「计时开始」确认弹窗；
                // 剩余 ≤ 10 分钟 → 驱动屏幕正上方倒计时浮层；到期未延时 → 经 PolicyEngine 锁屏。
                if (now >= nextSessionSync) {
                    if (boundNow.get()) runSafely("sessionSync") { usageSessionManager.refresh() }
                    nextSessionSync = now + SESSION_SYNC_INTERVAL_MS
                }

                updateNotification()
                delay(MIN_TICK_MS)
            }
        }
    }

    /**
     * 预约一次台账补同步。
     *
     * 延迟 10 秒再置位：PackageInstaller 会话提交后系统还要走拷贝→校验→提交，
     * 立刻同步的话新包很可能还没落地，家长端刷新一次看不到又会再刷一次。
     */
    private fun scheduleAppSync() {
        scope.launch {
            delay(APP_SYNC_AFTER_INSTALL_DELAY_MS)
            appSyncRequested.set(true)
        }
    }

    /**
     * 动态注册本机应用装卸监听（规避 Android 12+ 禁止后台 BroadcastReceiver 启动前台服务的限制）。
     *
     * 孩子手动安装 / 卸载应用是高频且即时的管理动作，若只依赖 30 分钟周期同步，
     * 家长端会长时间显示"已卸载却仍在列表"的幽灵应用，且对其点授权会因台账与设备不符而无效。
     * 这里只置 [appSyncRequested]，由主循环在数秒内完成全量上报，
     * 卸载的应用随即被服务端标 `installed=false` 并从家长端列表消失。
     *
     * 之所以在 [onCreate] 里运行时注册而非清单静态注册：Android 12+ 禁止后台 Receiver
     * 启动前台服务，而我们的服务正是前台服务；动态注册则不受此限，且能直接落到内存中的标志位。
     */
    private fun registerAppChangeReceiver() {
        if (appChangeReceiver != null) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            // 包变化广播携带的是 `package:xxx` 形式的数据，必须声明 scheme 才能收到
            addDataScheme("package")
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                // 自身被替换（更新）时也会收到 REPLACED，排除自身避免无谓同步
                val pkg = intent.data?.schemeSpecificPart.orEmpty()
                if (pkg == packageName) return
                Logger.i(TAG) { "package change detected: $action pkg=$pkg -> request appSync" }
                appSyncRequested.set(true)
            }
        }
        appChangeReceiver = receiver
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** 注册屏幕亮起 / 网络恢复的动态重启广播（见 [restartReceiver] 注释）。 */
    private fun registerRestartReceiver() {
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            }
            ContextCompat.registerReceiver(
                this, restartReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }.onFailure { Logger.w(TAG) { "register restart receiver failed: ${it.message}" } }
    }

    /**
     * 上报已安装应用台账。
     *
     * 频率刻意压到 30 分钟：清单上百条、单次请求几十 KB，
     * 频繁上报既费流量又会让孩子端看起来"在偷传数据"。
     * 应用装卸本身就是低频事件，30 分钟的滞后对家长的判断没有实质影响。
     */
    /** @return 是否上报成功，用于决定下一次重试的时间 */
    private suspend fun syncAppInventory(): Boolean {
        if (!credentials.isBound) return false
        val deviceId = authRepository.getDeviceId()
        if (deviceId.isBlank()) return false
        val apps = appInventory.collect()
        if (apps.isEmpty()) return false
        return when (val r = remote.uploadApps(deviceId, apps)) {
            is ApiResult.Success -> {
                val ack = r.value
                Logger.d(TAG) { "app inventory synced: ${apps.size} (+${ack.inserted} ~${ack.updated} -${ack.removed})" }
                true
            }
            else -> {
                Logger.w(TAG) { "app inventory sync failed: $r" }
                false
            }
        }
    }

    /**
     * 一次采样。
     *
     * `collectAppUsage` 关闭时不去查前台应用：省电，也避免在没有使用情况访问权限的设备上
     * 每 15 秒白跑一次 UsageStats 查询。
     */
    private suspend fun doTick(collectForegroundApp: Boolean) {
        val screenOn = snapshot.isScreenOn()
        val fg = if (collectForegroundApp) foregroundApp.current() else null
        // 日总时长是否已由服务端权威会话判定耗尽：三端一致的关键——
        // 本地不再按采样累计判 DAILY_TOTAL，改由服务端会话基线派生剩余。
        val sessionExpired = usageSessionManager.isExpired()
        val tick = policyEngine.tick(
            foregroundPackage = fg,
            screenOn = screenOn,
            dailyLimitExpired = sessionExpired
        )

        // 息屏时不弹任何界面：此刻拉起 Activity 只会点亮屏幕、白耗电，
        // 而且部分 ROM 会把它记成"后台弹窗"从而限制我们后续的启动权限。
        //
        // 唯一例外是**解锁追逐**：家长已解锁（App 侧未锁定）但系统 keyguard 还被
        // 我们压着时，点屏+拉过渡页正是目的 —— 那片刻意点亮的屏幕就是"解锁生效"的
        // 可见信号。chaseUnlock 内部限频（5s）且幂等，不会每 15s 都亮一次屏。
        if (!screenOn) {
            if (!tick.lockState.locked) LockScreenActivity.chaseUnlock(this)
            return
        }

        // 锁定态自愈：升级链可能因 ROM 限制没能把锁定页一次顶到前台，
        // 这里随自检重新确认一次，杜绝"家长已锁屏、平板却还能正常使用"的空洞。
        // 已经锁好时是空操作（见 LockScreenPresenter.reenforce）。
        lockScreenPresenter.reenforce()
        reconcileLockScreen(tick.shouldShowLockScreen, tick.lockState.primaryDetail)
        reconcileAppBlock(
            blockedPackage = tick.blockedPackage,
            foreground = fg,
            // 把限额判定/黑名单命中的具体原因一并带给拦截页。
            // 只说"被限制"会让孩子以为是故障并反复重试，
            // 说清是"时长用完"还是"已被管控"才能形成预期。
            reason = tick.blockedReason.ifBlank {
                (tick.limit as? LimitVerdict.Blocked)?.message().orEmpty()
            }
        )
    }

    /** 锁屏页只在"状态翻转"时操作，避免每 15s 重复 startActivity 导致画面闪烁 */
    private fun reconcileLockScreen(shouldLock: Boolean, detail: String) {
        if (shouldLock && !lockScreenShown) {
            lockScreenShown = true
            lockScreenPresenter.show(detail)
        } else if (!shouldLock && lockScreenShown) {
            lockScreenShown = false
            lockScreenPresenter.dismiss()
        }
    }

    /**
     * 单应用超额拦截。
     *
     * 只在"超额的那个应用正处于前台"时才弹拦截页 —— 后台超额没有打扰用户的必要。
     * 同一个包不重复弹，切到别的应用再切回来才会再弹一次。
     */
    private fun reconcileAppBlock(
        blockedPackage: String?,
        foreground: String?,
        reason: String
    ) {
        if (blockedPackage == null) {
            // 家长放行（或额度跨日重置）后必须主动收掉拦截页：
            // 它是盖在被拦应用之上的 Activity，不 finish 掉就一直停在那儿，
            // 孩子看到的是"同意了也没变化"。
            AppBlockActivity.dismiss(this)
            lastBlockedPackage = null
            return
        }
        if (blockedPackage != foreground) return
        if (blockedPackage == lastBlockedPackage) return
        lastBlockedPackage = blockedPackage
        AppBlockActivity.show(this, blockedPackage, reason)
    }

    // ==================== 心跳与上报 ====================

    private suspend fun sendHeartbeat() {
        // 凭据快照尚未从 DataStore 加载完成时绝不发鉴权请求：否则空令牌被服务端判
        // TOKEN_INVALID(40101)，触发自愈解绑把有效凭据清掉（启动竞态自残）。
        if (!credentials.isBound) return
        val deviceId = authRepository.getDeviceId()
        if (deviceId.isBlank()) return

        val heartbeat = Heartbeat(
            deviceId = deviceId,
            timestamp = timeProvider.now(),
            policyVersion = policyRepository.currentVersion(),
            transportMode = transportSettings.mode.value.name,
            controlMode = admin.controlMode.value.name,
            network = snapshot.network(),
            battery = snapshot.battery(),
            storage = snapshot.storage(),
            memory = snapshot.memory(),
            screen = snapshot.screen(),
            foregroundApp = foregroundApp.current().orEmpty(),
            location = null,
            pendingLogCount = logRepository.pendingCount()
        )

        when (val result = remote.sendHeartbeat(heartbeat)) {
            is ApiResult.Success -> {
                lastHeartbeatOk = true
                online = true
                val ack = result.value

                // 落盘当前 offset：进程被杀后重启能立刻拿到接近正确的时间基准，
                // 不必等第一个网络样本（详见 TimeOffsetPrefs 的说明）。
                TimeOffsetPrefs.saveIfChanged(this, timeProvider.currentOffsetMs())

                // 服务端说它那边的版本更高，说明我们漏了一次策略推送（MQTT 抖动很常见），
                // 心跳兼作策略版本对账通道，这条兜底比重试推送更可靠。
                if (ack.policyVersion > 0 && ack.policyVersion != heartbeat.policyVersion) {
                    Logger.i(TAG) {
                        "policy version drift: local=${heartbeat.policyVersion}, server=${ack.policyVersion}"
                    }
                    runSafely("policyRefreshOnDrift") { refreshPolicy() }
                }
                if (ack.flushLogs) runSafely("flushOnAck") { uploadPending() }
                if (ack.pendingCommandCount > 0) {
                    // 服务端明确告知有指令在等：这是"推送确实漏了"的强信号，
                    // 立刻补拉一次，不必再等 [COMMAND_PULL_INTERVAL_MS] 的下一跳。
                    // 心跳 30s 一次，这条路径把最坏延迟从"等轮询"再削一大截。
                    Logger.w(TAG) { "server reports ${ack.pendingCommandCount} pending commands, pulling now" }
                    runSafely("pullOnHeartbeatHint") { drainPendingCommands() }
                }
                // 注：兜底拉取已移到 [startCommandLoop] 的独立循环，不再挂在这里。
                // 原因：心跳 30s 一次，把拉取挂在心跳上会让 LOCK_SCREEN / 解锁这类
                // 只下发一次的指令最坏要 30~60s 才落地，表现为"点了没反应 / 延迟很高"。
            }
            is ApiResult.BizError -> {
                lastHeartbeatOk = false
                online = remote.isOnline()
                Logger.w(TAG) { "heartbeat rejected: ${result.code} ${result.message}" }
                // 自愈兜底（关键）：服务端判定"设备已解绑"(40301) 或"令牌无效"(40101) 时，
                // 本端必须就地清空凭据并回到绑定流程。
                // 不能只依赖解绑那一刻的 MQTT 推送 —— 平板当时可能离线、推送收不到，
                // 结果就是本地仍显示"已绑定"、再也进不去绑定页（僵尸绑定态），
                // 表现为"扫码、配对码都试过了怎么都绑不上"。
                if (result.code == ApiCode.DEVICE_UNBOUND || result.code == ApiCode.TOKEN_INVALID) {
                    Logger.w(TAG) { "device no longer bound on server -> self-heal to bind flow" }
                    runSafely("selfHealUnbind") { handleRemoteUnbind() }
                }
            }
            else -> {
                lastHeartbeatOk = false
                online = remote.isOnline()
                Logger.w(TAG) { "heartbeat failed: $result" }
            }
        }
    }

    /** 上行串行闸（C2'）：uploadPending 有多条触发路径（周期/flushNow/回执/重连），并发跑会重复 take 同一批。 */
    private val uploadMutex = kotlinx.coroutines.sync.Mutex()

    /** 日志与告警分开上报：告警是高危信号，不能被大批量普通日志的失败拖住 */
    private suspend fun uploadPending() {
        if (!credentials.isBound) return
        val deviceId = authRepository.getDeviceId()
        if (deviceId.isBlank()) return

        // 上报前先把本地累计的应用用量落成日志：
        // 服务端统计接口只读 usage_log 表，本地 UsageRepository 的累计值若不上行，
        // 家长端的"使用时长统计"永远是 0 —— 这是最容易被忽略的一条断链。
        runSafely("appUsageReport") { reportAppUsage() }

        // Mutex 串行化是双保险（C2'）：即使并发进入，损失也只是重复发送同批数据
        // （服务端按 logId/eventId 幂等去重），精确标记本身已保证不错标。
        uploadMutex.withLock {
            val alerts = logRepository.takePendingAlerts()
            if (alerts.items.isNotEmpty()) {
                val r = remote.uploadEvents(deviceId, alerts.items.map { it.copy(deviceId = deviceId) })
                if (r.isSuccess) logRepository.markAlertsUploaded(alerts)
                else Logger.w(TAG) { "alert upload failed, keep ${alerts.size} pending" }
            }

            val logs = logRepository.takePendingLogs()
            if (logs.items.isNotEmpty()) {
                val r = remote.uploadLogs(deviceId, logs.items)
                if (r.isSuccess) logRepository.markLogsUploaded(logs)
                else Logger.w(TAG) { "log upload failed, keep ${logs.size} pending" }
            }
        }

        // 兜底：任何"已落库但没入队上行"的临时解锁申请，在每次上报后补一次入队，
        // 避免窄窗口崩溃导致申请永远到不了家长、孩子端一直卡在"申请中"。
        runCatching { unlockRequestRepository.requeueUnsent(20) }
            .onFailure { Logger.w(TAG) { "requeue unsent unlock requests failed: $it" } }

        // 历史申请保留 30 天后安全销毁（与日志保留策略一致），避免 unlock_request 表无限增长。
        // DELETE WHERE createdAt < cutoff 幂等且廉价，随上报周期跑即可。
        runCatching { unlockRequestRepository.purge(UNLOCK_REQUEST_RETENTION_DAYS) }
            .onFailure { Logger.w(TAG) { "purge unlock requests failed: $it" } }
    }

    /**
     * 使用情况实时同步：把本地累计的用量落成日志并立即上行。
     *
     * 与 [uploadPending] 的区别：后者受服务端 logUploadIntervalMs 节制（可能很长），
     * 这条 30s 节奏只服务"今日使用情况"这一项，让家长端看到的用量尽量接近实时，
     * 而不是等几分钟才刷新一次。uploadPending 本身幂等（只上行未上报的日志），
     * 与日志节奏叠跑不会重复上报。
     */
    private suspend fun syncUsageNow() {
        if (!credentials.isBound) return
        runSafely("appUsageReport") { reportAppUsage() }
        runSafely("uploadPending") { uploadPending() }
    }

    /**
     * 把本地累计用量落成日志上行。
     *
     * ## 上报「累计值」而非「增量」
     * 每条 USAGE_TOTAL / APP_USAGE 携带的是"截至当前的当天累计时长"（秒），并带 `cumulative=true` 标记。
     * 服务端聚合时按「当天取最大值」还原真实累计——无论本进程重启多少次、同一份数据重复上报多少次、
     * 到达顺序如何，取到的都是当天真实累计值，天然幂等。
     *
     * 这彻底取代了旧的「增量 + 已上报量基准台账」模型：旧模型在重启/日切时若基准未落盘
     * （SharedPreferences 的 apply() 是异步的，进程被杀即丢失），会把全量累计当成增量重复累加，
     * 导致家长端"今日已用"被高估数倍（现场表现：孩子端 3 分钟，家长端 1 小时 40 分）。
     *
     * ## 节流
     * [lastReportedTotalMs] / [lastReportedAppMs] 只用于控制"累计变化达到阈值才落一条新日志"，
     * 减少冗余日志与上行流量；它们不参与正确性计算，丢失也不影响结果（最多多一条相同值的日志）。
     *
     * ## 跨天
     * 累计值本就按 dayKey 分表，跨天自然归零；这里在 dayKey 变化时清空节流记忆，
     * 让新一天的首条日志正常发出（否则会拿昨天的累计值当基准，算出负数被跳过）。
     */
    private suspend fun reportAppUsage() {
        val key = usageRepository.dayKey()
        val now = System.currentTimeMillis()

        // 跨天：累计值本就按 dayKey 分表，新的一天从 0 开始；
        // 清空节流记忆让新一天首条日志正常发出（否则拿昨天累计值当基准会算出负数被跳过）。
        if (key != lastReportedDay) {
            lastReportedTotalMs = 0L
            lastReportedAppMs.clear()
            lastReportedDay = key
        }

        // ---------- 全局总时长（不依赖使用情况访问权限，必须无条件上报） ----------
        // 只报 APP_USAGE（按应用）的话，未授予 UsageStats 权限的设备一条都发不出，
        // 服务端"今日总时长"就永远是 0。daily_usage 的累计只依赖亮屏，任何设备都有。
        var emitted = 0
        val totalMs = usageRepository.getTotalUsage(key)
        if (totalMs - lastReportedTotalMs >= USAGE_REPORT_MIN_DELTA_MS) {
            lastReportedTotalMs = totalMs
            logRepository.append(
                type = LogType.USAGE_TOTAL,
                payload = mapOf(
                    // 上报「累计值」而非「增量」：服务端按"当天最大值"聚合，天然幂等，
                    // 彻底杜绝旧增量模型"重启一次多一次"的高估（详见类注释）。
                    "durationSec" to (totalMs / 1000).toString(),
                    "dayKey" to key,
                    "startAt" to now.toString(),
                    "endAt" to now.toString(),
                    "cumulative" to "true"
                )
            )
            emitted++
        }

        // ---------- 按应用累计值（前台探测可用时才有数据） ----------
        val snapshot = usageRepository.snapshotAppUsage(key)
        if (snapshot.isEmpty()) {
            if (emitted > 0) Logger.d(TAG) { "app usage reported: $emitted item(s) for day=$key" }
            return
        }

        for (stat in snapshot) {
            val last = lastReportedAppMs[stat.packageName] ?: 0L
            if (stat.usedMs - last < USAGE_REPORT_MIN_DELTA_MS) continue
            lastReportedAppMs[stat.packageName] = stat.usedMs
            logRepository.append(
                type = LogType.APP_USAGE,
                payload = mapOf(
                    "packageName" to stat.packageName,
                    "appName" to appLabel(stat.packageName),
                    // 报「累计值」(stat.usedMs/1000) 而非增量，与服务端 MAX 聚合配合，幂等防高估。
                    "durationSec" to (stat.usedMs / 1000).toString(),
                    "dayKey" to key,
                    // 起点取当日首次使用时刻，终点取当下；服务端按天取 min/max 还原全天区间。
                    "startAt" to stat.firstUsedAt.toString(),
                    "endAt" to now.toString(),
                    "cumulative" to "true"
                )
            )
            emitted++
        }
        if (emitted > 0) Logger.d(TAG) { "app usage reported: $emitted app(s) for day=$key" }
    }

    /** 取应用显示名；取不到（已卸载）时退回包名，保证统计里不会出现空名称 */
    private fun appLabel(packageName: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    private suspend fun refreshPolicy() {
        if (!credentials.isBound) return
        val current = policyRepository.currentVersion()
        when (val r = remote.fetchPolicy(current)) {
            is ApiResult.Success -> when (val body = r.value) {
                is PolicyFetchResult.UpToDate ->
                    Logger.v(TAG) { "policy already up to date (v$current)" }

                is PolicyFetchResult.Updated -> {
                    // 传 rawJson：本地重新序列化会丢掉服务端新增的未知字段，
                    // 一旦回滚到旧客户端就会静默丢配置。
                    val saved = policyRepository.save(body.policy, body.rawJson)
                    val report = policyEngine.applyAll(saved, force = true)
                    Logger.i(TAG) { "policy v${saved.version} applied: ${report.summary()}" }
                    // 操作密码闸门：把服务端下发的密码哈希落到本地（明文永不出网）。
                    // 约定：clear 为真 → 清密码；passwordHash 非空 → 启用并落库；
                    // 两者皆空 → 不改动（避免策略合并时误清空）。
                    applyGuardPassword(saved.guard)
                }
            }
            else -> Logger.w(TAG) { "fetch policy failed: $r" }
        }
    }

    /**
     * 把策略包里的操作密码配置落到本地 [GuardPasswordManager]。
     *
     * 三种情况：
     * - [com.padguard.core.data.model.policy.GuardPolicy.clear] 为真 → 清除密码（解绑 / 家长关闭）；
     * - `passwordHash` 非空 → 启用密码并落库哈希（明文只在本机算了一次 SHA-256，不出网）；
     * - 两者皆空 → 不改动，避免策略合并（旧客户端/局部更新）时把已设密码误清空。
     */
    private fun applyGuardPassword(guard: com.padguard.core.data.model.policy.GuardPolicy) {
        scope.launch {
            runCatching {
                when {
                    guard.clear -> {
                        guardPassword.clear()
                        Logger.i(TAG) { "guard password cleared via policy" }
                    }
                    guard.passwordHash.isNotBlank() -> {
                        guardPassword.setPasswordHash(guard.passwordHash)
                        Logger.i(TAG) { "guard password enabled via policy (hash cached locally)" }
                    }
                    else -> Logger.v(TAG) { "guard policy present but empty, keep current state" }
                }
                ensureGuardInfra()
            }.onFailure { Logger.e(TAG, it) { "applyGuardPassword failed" } }
        }
    }

    /**
     * 操作密码启用后，无障碍服务是拦截的硬依赖：未开启则闸门形同虚设。
     *
     * 此处只做检测与日志（无法用代码开启，必须用户去「设置 → 无障碍」手动开）。
     * 引导 UI 由「我的」页的状态横幅承载：[com.padguard.child.ui.profile.ProfileViewModel]
     * 直接复用 [com.padguard.child.guard.GuardA11y.isEnabled] 派生 [guardInfraWarning]，
     * 与服务端解耦、UI 进程重启后依然能正确显示，无需本服务持久化标记。
     */
    private fun ensureGuardInfra() {
        if (!guardPassword.isEnabledNow()) return
        if (!GuardA11y.isEnabled(this)) {
            Logger.w(TAG) { "guard password enabled but accessibility service NOT enabled; prompt via 我的 page banner" }
        } else {
            Logger.i(TAG) { "guard infra ok: accessibility service enabled" }
        }
    }

    // ==================== 下行事件 ====================

    private fun observeDownlink() {
        downlinkJob?.cancel()
        downlinkJob = scope.launch {
            remote.downlink.collect { event ->
                runSafely("downlink:${event::class.simpleName}") {
                    when (event) {
                        is Downlink.CommandReceived -> {
                            // 已过三关，直接执行；不要再过 CommandGate（见类注释）
                            val deviceId = authRepository.getDeviceId()
                            val ack = policyEngine.execute(event.command, deviceId)
                            remote.sendAck(ack)
                            logRepository.append(
                                type = LogType.COMMAND_EXEC,
                                payload = mapOf(
                                    "msgId" to event.command.msgId,
                                    "type" to event.command.type.name,
                                    "status" to ack.status.name,
                                    "errorCode" to ack.errorCode.orEmpty()
                                )
                            )
                        }

                        is Downlink.CommandRejected -> {
                            // transport 已经回执过，这里只补本地留痕
                            logRepository.append(
                                type = LogType.COMMAND_EXEC,
                                payload = mapOf(
                                    "msgId" to event.ack.msgId,
                                    "status" to event.ack.status.name,
                                    "rejectReason" to event.reason
                                )
                            )
                        }

                        is Downlink.PolicyChanged -> refreshPolicy()

                        is Downlink.ConfigChanged -> {
                            // transport 内部已把节奏参数写进 TransportSettings，
                            // 主循环下一轮读 monitoring() 时自然生效，这里无需额外动作
                            Logger.i(TAG) { "runtime config updated: ${event.values.keys}" }
                            // 设备名下发：家长端重命名后通过 downConfig(deviceName) 下发，
                            // 这里持久化到本地，驱动「我的」页实时刷新
                            event.values["deviceName"]?.takeIf { it.isNotBlank() }?.let { name ->
                                runSafely("saveDeviceName") { authRepository.saveDeviceName(name) }
                            }
                            // 解绑下发：家长端点击解绑后，服务端经 downConfig(unbind=1) 通知本端。
                            // 必须就地清除本地凭据并回到绑定流程，否则平板仍持旧令牌继续上报，
                            // 表现为"家长端解绑了，孩子端却还在受控 / 再也绑不上别的账号"。
                            if (event.values["unbind"] == "1") {
                                Logger.w(TAG) { "unbind command received, clearing local credentials" }
                                runSafely("clearGuardPassword") { guardPassword.clear() }
                                runSafely("handleRemoteUnbind") { handleRemoteUnbind() }
                            }
                        }

                        is Downlink.ConnectivityChanged -> {
                            online = event.online
                            Logger.i(TAG) { "connectivity=${event.online}, mode=${event.mode}" }
                            if (event.online) runSafely("flushOnReconnect") { uploadPending() }
                            updateNotification()
                        }
                    }
                }
            }
        }
    }

    /**
     * 把应用管控策略镜像到 [AppGuardPolicyStore]，供无障碍服务同步读取。
     *
     * ## 为什么需要这一层
     * [com.padguard.core.engine.enforcer.AppPolicyEnforcer] 在非 DO 设备上只能把
     * "隐藏/挂起"上报为 UNSUPPORTED —— 家长配的名单在设备上不起作用。
     * 真正能落地的是无障碍服务，但无障碍回调在主线程、不能挂起读 Room，
     * 所以这里把同一份 [com.padguard.core.data.model.policy.AppPolicy] 同步一份到 DataStore。
     *
     * 监听 Room 而不是只在 refreshPolicy 里写：策略可能来自首屏拉取、指令下发、周期自愈等
     * 任意一条路径，监听数据源能保证"只要策略变了，镜像就跟着变"，不会漏。
     */
    private fun observeAppPolicyMirror() {
        appPolicyMirrorJob?.cancel()
        appPolicyMirrorJob = scope.launch {
            policyRepository.observePolicy().collect { pkg ->
                runSafely("mirrorAppPolicy") { appGuardStore.mirror(pkg.app) }
                // 护眼滤镜走同一条数据源：策略可能来自首屏拉取 / 指令下发 / 周期自愈任意一条路径，
                // 监听保证"策略变了 → 滤镜跟着变"，不会出现"家长关了开关但平板还在发黄"。
                runSafely("applyEyeProtection") { eyeProtectionOverlay.apply(pkg.eyeProtection) }
            }
        }
    }

    // ==================== 引擎副作用 ====================

    private fun observeEffects() {
        effectJob?.cancel()
        effectJob = scope.launch {
            policyEngine.effects.collect { effect ->
                runSafely("effect:${effect::class.simpleName}") { handleEffect(effect) }
            }
        }
    }

    private suspend fun handleEffect(effect: EngineEffect) {
        when (effect) {
            is EngineEffect.ShowLockScreen -> {
                lockScreenShown = true
                lockScreenPresenter.show(effect.reason)
            }

            EngineEffect.DismissLockScreen -> {
                lockScreenShown = false
                lockScreenPresenter.dismiss()
                // 家长放行最常发生在"孩子从拦截页提交申请"的场景，
                // 这里必须同步收掉拦截页，否则要等下一轮自检（最长 15s）才关，
                // 孩子会认为家长同意了却没生效。
                AppBlockActivity.dismiss(this)
                lastBlockedPackage = null
            }

            is EngineEffect.ShowMessage -> {
                // 落地到本地消息页：家长发布的信息进入「消息」持久存储，孩子随时可查看。
                // msgId 为空（理论上不会发生，指令必带）时退化为时间戳，保证唯一约束不冲突。
                runSafely("persistParentMessage") {
                    val key = effect.msgId.ifBlank { System.currentTimeMillis().toString() }
                    messageRepository.upsert(
                        ParentMessageEntity(
                            serverMsgId = key,
                            title = effect.title,
                            body = effect.body,
                            contentType = effect.contentType,
                            mediaUrl = effect.mediaUrl,
                            mediaName = effect.mediaName,
                            durationSec = effect.durationSec,
                            blocking = effect.blocking,
                            timestamp = timeProvider.now(),
                            isRead = false
                        )
                    )
                }
                MessageActivity.show(
                    context = this,
                    title = effect.title,
                    body = effect.body,
                    durationSec = effect.durationSec,
                    blocking = effect.blocking,
                    contentType = effect.contentType,
                    mediaUrl = effect.mediaUrl,
                    mediaName = effect.mediaName
                )
            }

            EngineEffect.FlushLogs, EngineEffect.FlushBeforeShutdown -> uploadPending()

            // 应用被隐藏/显示后立刻重报台账，让家长端马上看到开关生效
            EngineEffect.SyncAppInventory -> syncAppInventory()

            is EngineEffect.RefreshPolicy -> refreshPolicy()

            // ---------- 采集类：真实执行，结果单独回传 ----------
            // 这些动作的共同点是"指令回执已先行返回 SUCCESS（任务已受理）"，
            // 真实产物（图片/音频/视频）走独立上传通道。
            // 因此这里必须把最终成败写进日志流，否则管控端只会看到"受理成功"却永远等不到产物。
            is EngineEffect.CaptureScreenshot -> {
                val captureIntent = ScreenCaptureService.screenshotIntent(this, effect.shotId)
                // 投影令牌缺失时，原有路径只有两种结果：直接弹"是否允许录制屏幕"系统框，
                // 或发一条通知等孩子来点。先试无障碍通道：它的授权（启用无障碍服务）持久有效，
                // 截屏全程无弹窗、无令牌、不起前台服务；任一环节不可用都如实退回原路径。
                if (!ScreenCaptureSession.hasToken() &&
                    accessibilityScreenshot.captureAndUpload(effect.shotId)
                ) {
                    reportEffectResult(
                        effect.msgId, "screenshot", true,
                        "已静默采集并上传（无障碍通道，无授权弹窗）"
                    )
                } else {
                    // 已授权 → 静默执行；未授权 → 高优先级通知引导一次（后台不能直接弹 Activity）
                    val silent = dispatchCapture(captureIntent)
                    reportEffectResult(
                        effect.msgId, "screenshot", true,
                        if (silent) "已静默采集并上传" else "已下发（需孩子在通知中点一次开启屏幕采集）"
                    )
                }
            }

            is EngineEffect.CapturePhoto -> {
                val error = photoCapture.captureAndUpload(effect.taskId)
                reportEffectResult(effect.msgId, "photo", error == null, error ?: "已上传")
            }

            is EngineEffect.RequestLocation -> {
                // 失败原因必须区分：过去统一回一句"未授权定位权限或无位置缓存"，
                // 而真实失败绝大多数是"App 在后台，Android 10+ 禁止后台读位置"，
                // 文案指向错误，家长只会反复去系统里开权限，永远修不好。
                when (val outcome = locationCollector.collectAndUpload(
                    // 家长点了"立即定位"就是要新鲜的：缓存点超过 15 秒就主动向系统要一个新的，
                    // 最长等 12 秒（室内 GPS 冷启动常常更久，超时后降级为缓存点并如实带回时间戳）。
                    freshnessMs = 15_000L,
                    requestTimeoutMs = 12_000L,
                    // 后台（屏幕熄灭但未退出）时：守护服务已声明 foregroundServiceType=location，
                    // 借"定位类型前台服务"上下文采集，Android 视为"使用中"，无需 ACCESS_BACKGROUND_LOCATION（合规）。
                    // 真正退出/被杀则收不到本指令，自然走不到这里；此处不再返回 BACKGROUND_RESTRICTED 而空转。
                    allowBackground = !AppForeground.isForeground
                )) {
                    LocationOutcome.Success ->
                        reportEffectResult(effect.msgId, "locate", true, "已上报")
                    LocationOutcome.Skipped ->
                        reportEffectResult(effect.msgId, "locate", true, "位置未变化，沿用上次上报")
                    is LocationOutcome.Failure ->
                        reportEffectResult(effect.msgId, "locate", false, outcome.kind.hint)
                }
            }

            is EngineEffect.StartScreenRecord -> {
                // 录屏早期直接走 ScreenCaptureService.startScreenRecord → 后台 startActivity 被 Android 10+ 拦掉，
                // 授权页永远出不来，家长端只看到"录屏启动失败"。统一走派发口后与截屏同一条链路。
                val captureIntent = ScreenCaptureService.screenRecordIntent(
                    this, effect.taskId, effect.resolution, effect.withAudio
                )
                val silent = dispatchCapture(captureIntent)
                reportEffectResult(
                    effect.msgId, "screenRecord", silent,
                    if (silent) "已开始录制" else "未取得屏幕采集授权：已发送引导通知，待孩子点击开启"
                )
            }

            EngineEffect.StopScreenRecord -> ScreenCaptureService.stopScreenRecord(this)

            is EngineEffect.StartLiveView -> {
                // 画质档位随指令一路带到推流侧：家长改了「高清画面」后，
                // 下一次续期（或重新开流）就按新档位出帧，不必退出看屏页重进。
                val captureIntent = ScreenCaptureService.liveStreamIntent(this, effect.highDefinition)
                val silent = dispatchCapture(captureIntent)
                reportEffectResult(
                    effect.msgId, "liveView", silent,
                    if (silent) "已开始实时推流" else "未取得屏幕采集授权：已发送引导通知，待孩子点击开启"
                )
            }

            EngineEffect.StopLiveView -> ScreenCaptureService.stopLiveStream(this)

            is EngineEffect.StartAudioRecord -> {
                ScreenCaptureService.startAudioRecord(this, effect.taskId)
                reportEffectResult(effect.msgId, "audioRecord", true, "已开始录音")
            }

            EngineEffect.StopAudioRecord -> ScreenCaptureService.stopAudioRecord(this)

            // ---------- 远程运维：静默安装 / 卸载 ----------
            is EngineEffect.InstallApp -> {
                val error = appInstaller.install(effect.apkUrl, effect.packageName)
                // 安装是异步完成的（PackageInstaller 回调），这里给一个延迟后的补同步，
                // 让家长端能在十几秒内看到新应用出现在台账里，而不是等下一个 30 分钟周期。
                if (error == null) scheduleAppSync()
                reportEffectResult(effect.msgId, "installApp", error == null, error ?: "安装会话已提交")
            }

            is EngineEffect.UninstallApp -> {
                val error = appInstaller.uninstall(effect.packageName)
                if (error == null) scheduleAppSync()
                reportEffectResult(effect.msgId, "uninstallApp", error == null, error ?: "卸载已提交")
            }

            is EngineEffect.SetWatermark -> {
                val ok = if (effect.enabled) {
                    watermark.show(effect.content)
                } else {
                    watermark.hide()
                    true
                }
                reportEffectResult(
                    "", "setWatermark", ok,
                    if (ok) "enabled=${effect.enabled}" else "悬浮窗权限未授予，水印未生效"
                )
            }

            is EngineEffect.UpdateRuntimeConfig ->
                Logger.i(TAG) { "runtime config from command: ${effect.params.keys}" }
        }
    }

    /**
     * 采集/运维类副作用的真实结果留痕。
     *
     * 指令回执在 [com.padguard.core.engine.command.CommandExecutor] 里就已经发出去了，
     * 语义是"已受理"；这里补的是**最终结果**。两者缺一不可：
     * 只有前者会出现"看起来成功的失败"，只有后者则管控端不知道指令有没有被收到。
     */
    private suspend fun reportEffectResult(msgId: String, action: String, ok: Boolean, detail: String) {
        logRepository.append(
            type = LogType.COMMAND_EXEC,
            payload = mapOf(
                "msgId" to msgId,
                "action" to action,
                "result" to if (ok) "DONE" else "FAILED",
                "reason" to detail
            )
        )
        // 结果随下一次上报窗口走即可；这里额外触发一次是为了让实时类动作（截屏/定位）
        // 在秒级内到达家长端 —— 家长点了"看看孩子在干嘛"却要等一个上报周期，体验上是断的。
        if (ok) uploadPending()
    }


    // ==================== 前台通知 ====================

    private fun enterForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            // IMPORTANCE_LOW：常驻通知不该有声音和横幅，否则每次重建都打扰一次
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.guard_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.guard_channel_desc)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), fgsType())
        } catch (e: Exception) {
            // API 31+ 从后台启动前台服务会抛 ForegroundServiceStartNotAllowedException。
            // Device Owner 身份下系统会豁免，但降级到普通 DEVICE_ADMIN 时确实可能被拒。
            // 这里不能让它崩：崩了会连带整个管控进程重启，形成崩溃循环。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is ForegroundServiceStartNotAllowedException
            ) {
                Logger.e(TAG, e) { "FGS start not allowed, guard runs in degraded background mode" }
            } else {
                Logger.e(TAG, e) { "startForeground failed" }
            }
        }
    }

    /**
     * 前台服务类型。
     *
     * API 34+ 用 `specialUse`：`dataSync` 在 Android 15 上有 6 小时运行上限，
     * 而管控服务必须 7×24 常驻，用 dataSync 会在半天后被系统掐掉。
     * API 29–33 没有 specialUse，用 dataSync 且当年无时长限制，是安全的。
     */
    private fun fgsType(): Int {
        val base = when {
            Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        // 定位类型：让孩子端在后台（屏幕熄灭但未退出）收到"立即定位"指令时，
        // 借由常驻前台服务读取位置——Android 将此视为"使用中"，从而豁免 ACCESS_BACKGROUND_LOCATION
        // （该权限对儿童向 App 受限）。这是合规且主流的家长管控定位方案。
        // API 29 以下无前台服务类型概念，且后台定位限制本身从 API 29 才引入，故仅 Q+ 叠加。
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            base or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            base
        }
    }

    private fun buildNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val modeText = getString(
            when {
                online && lastHeartbeatOk -> R.string.guard_state_online
                online -> R.string.guard_state_connecting
                else -> R.string.guard_state_offline
            }
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.guard_notification_title))
            .setContentText(getString(R.string.guard_notification_text, modeText, admin.controlMode.value.name))
            .setSmallIcon(R.drawable.ic_guard_notification)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification() {
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    /**
     * 派发一条屏幕采集请求。
     *
     * @return true = 已持有授权，请求静默下发（孩子完全无感）；
     *         false = 尚无授权，已发高优先级通知引导，需孩子点一次。
     *
     * 注意：绝不能在这里直接 `startActivity` 拉授权页 —— 守护服务是后台上下文，
     * Android 10+ 会静默拦掉，孩子永远看不到系统授权框，家长端就卡在"等待授权"。
     */
    private fun dispatchCapture(captureIntent: Intent): Boolean =
        CaptureConsentPrompter.dispatch(this, captureIntent)

    /**
     * 指令兜底拉取（触发点见心跳对账处）。
     *
     * MQTT 模式下指令走推送通道，但推送并非绝对可靠：重连窗口、QoS 边界都可能丢一条。
     * 高频重复的指令丢了无所谓，一次性指令（锁屏 / 录屏 / 截屏）丢了就永远补不回来。
     *
     * 服务端 `pendingForPolling` 只返回 PENDING 指令 —— **前提是执行后必须回执**：
     * 之前这里执行完就完事，从不上报 ack，指令在服务端永远是 PENDING；
     * 叠加终端时钟偏移导致服务端把游标判非法归零后，同一条指令每个轮询周期
     * 都会被重发重执行一遍（实测录屏每 3 秒被丢弃重启一次，永远录不出完整视频）。
     * 现在与推送通道保持一致：执行 → 回执，让服务端把指令收敛为终态。
     */
    private suspend fun drainPendingCommands() {
        if (!credentials.isBound) return
        val deviceId = authRepository.getDeviceId()
        if (deviceId.isBlank()) return
        when (val r = remote.pullCommands(lastCommandPullAtMs)) {
            is ApiResult.Success -> {
                val resp = r.value
                lastCommandPullAtMs = System.currentTimeMillis()
                // 游标落盘：进程被回收后续接，杜绝"息屏期间下达的指令被永久跳过"
                commandPollPrefs.edit().putLong(KEY_LAST_PULL_AT, lastCommandPullAtMs).apply()
                // 指令轮询响应顺带回传最新策略版本号：这是家长改策略后最坏 3s 内生效的关键。
                // 即便 MQTT 未启用、policy 推送必然丢失，3s 高频轮询也能立刻感知版本漂移并全量拉新，
                // 不必苦等 30s 心跳（且长轮询挂起时本地往往拿不到心跳真实版本，对账会退化）。
                val localVersion = policyRepository.currentVersion()
                if (resp.policyVersion > 0 && resp.policyVersion != localVersion) {
                    Logger.i(TAG) {
                        "policy version drift via command poll: local=$localVersion, server=${resp.policyVersion}"
                    }
                    runSafely("policyRefreshOnDrift") { refreshPolicy() }
                }
                val list = resp.commands
                if (list.isEmpty()) return
                Logger.w(TAG) {
                    "fallback pull: ${list.size} command(s) missed by push -> ${list.map { it.type.name }}"
                }
                list.forEach { cmd ->
                    runSafely("fallbackCmd:${cmd.type.name}") {
                        // 去重在 CommandExecutor 内做（推送/轮询双通道共用漏斗）；
                        // 无论新执行还是去重忽略，都要回执，让服务端收敛指令状态
                        val ack = policyEngine.execute(cmd, deviceId)
                        runCatching { remote.sendAck(ack) }
                            .onFailure { Logger.w(TAG) { "fallback ack ${cmd.msgId} failed: $it" } }
                    }
                }
            }
            is ApiResult.BizError ->
                Logger.w(TAG) { "fallback pull rejected: ${r.code} ${r.message}" }
            is ApiResult.Failure ->
                Logger.w(TAG) { "fallback pull failed: ${r.message}" }
        }
    }

    /**
     * 收到服务端解绑指令后的本地清理。
     *
     * 清理顺序很重要：先清凭据（阻止后续上报再拿旧令牌），再停采集，最后把用户
     * 送回绑定页。若只清不跳转，孩子会停在一个"看起来还在管控中"的空壳界面上。
     */
    private suspend fun handleRemoteUnbind() {
        // 防御：本端凭据快照尚未从 DataStore 加载完成（deviceToken 为空）时，
        // 此刻的 40101/40301 只是"还没准备好"而非"服务端已解绑"；
        // 若用空令牌去 re-verify，服务端照样判 40101，会误把有效凭据清掉。
        if (!credentials.isBound) {
            Logger.w(TAG) { "credentials not loaded yet, skip unbind self-heal" }
            return
        }
        // ---- 解绑必须与服务端核实，不能照单全收 ----
        //
        // MQTT 侧是 cleanSession=false + QoS1，服务端历史上 publish 过的 unbind=1 会
        // 在设备**每次重连时被重复投递**（Paho 本地持久化 + Broker 会话队列）。
        // 照单全收的后果极其隐蔽：绑定成功 → 一重启就收到陈旧 unbind → 立刻自清凭据
        // → 回到绑定页，而服务端那边设备仍是已绑定且在线，日志里找不到任何解绑操作。
        // 表现为「绑定态莫名其妙丢失、监控功能全线失效」，排查时极易误判为存储故障。
        //
        // 判据：用当前设备令牌打一次「需鉴权」的服务端接口核实。
        // 令牌仍然有效 = 服务端并未真正解绑 = 这是一条陈旧指令，忽略；
        // 只有服务端明确判定令牌失效(40101)/设备未绑定(40301)才真的清。
        //
        // 注意：绝不能复用 syncTime()（底层是 /api/v1/device/time）——该接口在
        // WebConfig 中被排除在设备鉴权之外（免鉴权、无副作用，仅用于绑定前的连通自检），
        // 任何令牌（含已被服务端吊销为 "revoked_..." 的令牌）都能拿到 serverTime，
        // 用它判断"是否仍绑定"会永远返回 true，导致解绑指令被当成陈旧指令忽略、
        // 本地凭据永不清除（表现：家长端解绑了、孩子端却还在受控 / 再也绑不上别的账号）。
        //
        // 改用 fetchPolicy()：已解绑设备的令牌与服务端 deviceTokenHash 不匹配，
        // ChildAuthInterceptor 返回 401(TOKEN_INVALID)/403(DEVICE_UNBOUND)，
        // 由 ApiCaller 归一为 ApiResult.BizError，命中 UNBOUND_CODES 即确认解绑。
        // 另：鉴权失败经 ApiCaller 映射为 BizError 而非 Failure，故两个错误分支都要判断。
        val stillBound = when (val r = remote.fetchPolicy(0)) {
            is ApiResult.Success -> true
            is ApiResult.BizError -> r.code !in UNBOUND_CODES
            is ApiResult.Failure -> r.code !in UNBOUND_CODES
        }
        if (stillBound) {
            Logger.w(TAG) { "stale unbind ignored: device credentials still valid on server" }
            return
        }
        Logger.w(TAG) { "server confirmed unbind, clearing local credentials" }
        // 只清设备绑定、保留家庭账号登录态：解绑后应直接回到绑定页重新绑定，
        // 而不是退回登录页（孩子不知道家长密码，会永久卡死）。
        authRepository.clearDeviceBinding()
        // 绑定镜像同步清除：暖令牌的前置门重新关上，解绑后不再向孩子弹采集确认框。
        CaptureConsent.markDeviceBound(this, false)
        // 解绑即释放操作闸门密码与本地软拦截策略缓存。
        // 若不清，平板转手后旧家庭的操作密码（GuardPasswordManager）与 AppGuardPolicyStore
        // 仍残留在新家庭设备上——孩子可凭旧密码绕过软拦截，隐私/管控承诺从第一天即不可信。
        // 这两者在 :app 模块（core:data 拿不到），故在此显式补清；Room 私有表已由
        // clearDeviceBinding 内部 wipePrivateDb 一并清掉。失败不阻断解绑主流程。
        runCatching { guardPassword.clear() }
            .onFailure { t -> Logger.e(TAG, t) { "guard password clear failed on unbind" } }
        runCatching { appGuardStore.clear() }
            .onFailure { t -> Logger.e(TAG, t) { "app guard policy store clear failed on unbind" } }
        // 解绑即释放全部管控痕迹 + 释放 Device Owner / 设备管理员：
        // 使孩子端真正"不受管控"，且管控系统/用户可常规卸载（清除 DO → 卸载）。
        // 必须放在 stopSelf 之前、且晚于 clearDeviceBinding（此时仍持 DO 权限，可正常撤销策略）。
        runCatching { admin.releaseControl() }
            .onSuccess { Logger.i(TAG) { "control released (${it.size} ops) on unbind" } }
            .onFailure { t -> Logger.e(TAG, t) { "releaseControl failed on unbind" } }
        runCatching { ScreenCaptureService.stopScreenRecord(this) }
        runCatching { ScreenCaptureService.stopAudioRecord(this) }
        // 清本机采集媒体残留。Room/DataStore/guard 密码之上，
        // cacheDir 里的截屏/录屏/录音临时文件与未上传成功的重试队列同样是
        // "旧家庭在本设备上的管控痕迹"——平板转手后旧家庭录音录像不可残留。
        // 时序在 stop*Record 之后：确保没有正在写入的句柄再删文件。
        runCatching { ScreenCaptureService.wipeLocalMedia(this) }
            .onFailure { t -> Logger.e(TAG, t) { "capture cache wipe failed on unbind" } }
        runCatching { mediaRetry.clearAll() }
            .onFailure { t -> Logger.e(TAG, t) { "media retry queue clear failed on unbind" } }
        withContext(Dispatchers.Main) {
            runCatching {
                startActivity(
                    Intent(this@GuardService, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }
                )
            }
        }
        stopSelf()
    }

    // ==================== 工具 ====================

    /**
     * 统一异常兜底。
     *
     * 主循环里任何一个子任务抛异常都不能让循环退出 —— 循环一停，
     * 服务还在前台、通知还挂着，但采样、心跳、自检全部停摆，
     * 从外部看完全正常，这是最危险的失效形态。
     */
    /**
     * 兜底执行一个周期性任务。
     *
     * 单个任务抛异常不能拖垮整个守护循环（否则一次 NPE 就等于管控全停），
     * 因此这里只记录不扩散；返回值供调用方决定重试节奏（null 表示本次抛异常）。
     */
    private suspend inline fun <T> runSafely(tag: String, crossinline block: suspend () -> T): T? =
        try {
            block()
        } catch (t: Throwable) {
            // CancellationException 必须放行，否则协程取消会被吞掉，服务停不下来
            if (t is kotlinx.coroutines.CancellationException) throw t
            Logger.e(TAG, t) { "task '$tag' failed" }
            null
        }

    companion object {
        private const val TAG = "GuardService"

        /** 指令轮询游标的持久化文件（见 [lastCommandPullAtMs]） */
        private const val PREFS_COMMAND_POLL = "padguard_command_poll"
        private const val KEY_LAST_PULL_AT = "last_pull_at"

        /** 临时解锁申请本地保留天数，到期随上报周期安全销毁 */
        private const val UNLOCK_REQUEST_RETENTION_DAYS = 30

        private const val CHANNEL_ID = "padguard_guard"
        private const val NOTIFICATION_ID = 1001

        /**
         * 亮屏后重新对账锁定覆盖的延迟：200ms。
         *
         * 取这个值有两个理由：① `ACTION_SCREEN_ON` 广播到达时 `PowerManager.isInteractive`
         * 可能还没翻转，立刻判定会误当息屏而跳过铺地板；② 200ms 对"孩子按下电源键 →
         * 手指移到屏幕开始上滑"这段时间来说仍然是瞬时的，覆盖缺口几乎不可感知。
         */
        private const val SCREEN_ON_RECHECK_DELAY_MS = 200L

        const val EXTRA_REASON = "reason"
        const val REASON_BOOT = "boot"
        /** 立即上报一次本地待发数据（解锁申请等时效性交互使用） */
        const val REASON_FLUSH = "flush"
        /** 任务被划掉后的自启（见 onTaskRemoved） */
        const val REASON_TASK_REMOVED = "taskRemoved"
        /** 看门狗发现服务不在时拉起（见 GuardWatchdogService） */
        const val REASON_WATCHDOG = "watchdog"

        /**
         * 守护服务是否在运行。
         *
         * 供 [GuardWatchdogService] 判断是否需要拉起。用静态标志而不是
         * `ActivityManager.getRunningServices`：后者从 Android 8 起只能查到自己进程、
         * 且部分 ROM 干脆返回空，判定会失真。
         * 进程被杀时静态变量随之消失（读回来就是 false），正好是我们要的语义。
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** 采样基准间隔。15s 是权衡结果：更短会明显增加耗电，更长会让"时段锁"生效延迟到肉眼可见 */
        private const val MIN_TICK_MS = 15_000L

        /** 任务被划掉后重启服务的延迟（见 onTaskRemoved）：避开系统清理任务栈的竞态 */
        private const val RESTART_DELAY_MS = 1_000L

        /**
         * 指令兜底拉取间隔。
         * 太短会与 MQTT 推送重复争抢，太长则一次性指令（锁屏/录屏）丢失后补偿过慢；
         * 60s 对"家长点了没反应"是可接受的补偿延迟，请求开销也可忽略。
         */
        /**
         * 指令兜底拉取间隔。
         *
         * 原为 60s：远程锁屏 / 解锁 / 截屏 / 实时看屏首帧都要等这么久，
         * 是"实时性差"的第一根因。压到 3s：单次请求/响应仅几十字节，
         * 局域网 RTT <5ms、外网 <100ms，代价可忽略，最坏延迟从 60s 降到 ~3s。
         */
        private const val COMMAND_PULL_INTERVAL_MS = 3_000L

        /** 离线时的拉取间隔（放慢，避免断网期间高频失败刷日志） */
        private const val COMMAND_PULL_IDLE_MS = 10_000L

        /** 策略自检间隔：把被绕过的限制重新压回来 */
        private const val SELF_CHECK_INTERVAL_MS = 5 * 60_000L

        /** 篡改自检间隔 */
        private const val TAMPER_SCAN_INTERVAL_MS = 60_000L

        /**
         * 服务端判定"这台设备确实已不在绑定关系里"的错误码。
         * 只有命中这些码才允许执行本地解绑清理，见 [handleRemoteUnbind]。
         */
        private val UNBOUND_CODES = setOf(ApiCode.TOKEN_INVALID, ApiCode.DEVICE_UNBOUND)

        /** 日志上报最小间隔，防止服务端把 logUploadIntervalSec 配成 0 导致刷接口 */
        private const val LOG_UPLOAD_MIN_INTERVAL_MS = 60_000L

        /**
         * 使用情况实时同步间隔：**独立于**日志上报节奏（logUploadIntervalMs 可能远大于此值）。
         * 家长端"今日使用情况"依赖 APP_USAGE / USAGE_TOTAL 日志上行，
         * 原本 30s 节奏会让两端差异最多累积到 30s+；压到 10s 让服务端聚合值与孩子端本地实时值
         * 基本对齐，看板不再"越攒越偏"（用户反馈看板已用时与孩子端不一致的根因之一）。
         */
        private const val USAGE_SYNC_INTERVAL_MS = 10_000L
        private const val USAGE_SYNC_FIRST_DELAY_MS = 5_000L

        /**
         * 使用计时会话同步间隔（三端一致计时）。
         * 与采样对齐为 15s：服务端权威剩余经此刷新到本地，驱动「计时开始」确认弹窗
         * 与屏幕正上方 10 分钟倒计时浮层；更密意义不大，更疏则浮层与锁定延迟变大。
         */
        private const val SESSION_SYNC_INTERVAL_MS = 15_000L
        private const val SESSION_SYNC_FIRST_DELAY_MS = 5_000L

        /** 应用台账全量同步间隔：装卸是低频事件，没必要跟着 15s 的采样节奏跑 */
        private const val APP_SYNC_INTERVAL_MS = 30 * 60_000L

        /** 服务启动后的首次台账同步延迟：让刚绑定的设备尽快把应用清单送到家长端 */
        private const val APP_SYNC_FIRST_DELAY_MS = 15_000L

        /** 安装/卸载指令后补同步的等待时间，等 PackageInstaller 会话真正落地 */
        private const val APP_SYNC_AFTER_INSTALL_DELAY_MS = 10_000L

        /**
         * 台账同步失败后的重试间隔。
         * 开机后 15 秒那一次经常撞上"Wi-Fi 刚连上、HTTP 还没通"，
         * 若直接退回 30 分钟周期，家长端会长时间空着。
         */
        private const val APP_SYNC_RETRY_MS = 60_000L

        /**
         * 应用用量上报的最小增量。
         * 与 [USAGE_SYNC_INTERVAL_MS] 对齐为 10s：每 10s 同步一次、增量满 10s 才落一条日志，
         * 既保证服务端聚合值近实时刷新（看板与孩子端一致），又把每天日志条数压在合理量级
         * （2 小时重度使用约 720 条/应用，远小于不设阈值的上万条）。
         */
        private const val USAGE_REPORT_MIN_DELTA_MS = 10_000L

        /**
         * 启动守护服务。
         *
         * 用 `startForegroundService`（API 26+）而非 `startService`：
         * 后者在后台调用会直接抛 IllegalStateException。
         * 调用方只管调，失败与否由服务内部处理，不向外抛。
         */
        fun start(context: Context, reason: String) {
            val intent = Intent(context, GuardService::class.java).apply {
                putExtra(EXTRA_REASON, reason)
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure {
                Logger.e(TAG, it) { "failed to start GuardService (reason=$reason)" }
            }
        }
    }
}
