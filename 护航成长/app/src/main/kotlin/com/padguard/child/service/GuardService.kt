package com.padguard.child.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DeviceAdminReceiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.Manifest
import android.net.ConnectivityManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.padguard.child.MainActivity
import com.padguard.child.R
import com.padguard.child.capture.CaptureConsentPrompter
import com.padguard.child.capture.CaptureConsentStore
import com.padguard.child.usagesession.UsageStartOverlay
import com.padguard.child.capture.CaptureConsent
import com.padguard.child.capture.AccessibilityScreenshot
import com.padguard.child.capture.MediaUploadRetry
import com.padguard.child.capture.PhotoCapture
import com.padguard.child.capture.ScreenCaptureService
import com.padguard.child.receiver.GuardAlarmReceiver
import com.padguard.child.receiver.BoundaryAlarmClock
import com.padguard.child.receiver.LockBoundaryAlarmReceiver
import com.padguard.child.time.TimeOffsetPrefs
import com.padguard.child.time.TrustedTimePrefs
import com.padguard.child.capture.ScreenCaptureSession
import com.padguard.child.guard.AppGuardPolicyStore
import com.padguard.child.guard.EyeProtectionOverlay
import com.padguard.child.guard.GuardA11y
import com.padguard.child.guard.GuardPasswordManager
import com.padguard.child.guard.GuardTickSignal
import com.padguard.child.hidden.AppHider
import com.padguard.child.maintain.AppInstaller
import com.padguard.child.monitor.AppInventoryCollector
import com.padguard.child.monitor.AppInventoryDeltaTracker
import com.padguard.child.monitor.DeviceSnapshotCollector
import com.padguard.child.AppForeground
import com.padguard.child.monitor.ForegroundAppMonitor
import com.padguard.child.notify.NotificationDelivery
import com.padguard.child.permission.AuthorizationKeeper
import com.padguard.child.permission.PermissionGranter
import com.padguard.child.monitor.LocationCollector
import com.padguard.child.monitor.LocationOutcome
import com.padguard.child.monitor.LocationAutoReporter
import com.padguard.child.ui.block.AppBlockActivity
import com.padguard.core.data.model.policy.BlacklistAction
import com.padguard.child.ui.lock.LockScreenActivity
import com.padguard.child.usagesession.UsageSessionManager
import com.padguard.child.usagesession.TimerPauseSource
import com.padguard.child.ui.lock.LockScreenPresenter
import com.padguard.child.guard.DeviceOwnerEnforcer
import com.padguard.child.guard.PermissionHealthChecker
import com.padguard.child.ui.lock.SystemKeyguardBridge
import com.padguard.child.ui.message.MessageActivity
import com.padguard.child.ui.message.MessageOverlayController
import com.padguard.child.guard.SelfProtectionModule
import com.padguard.child.guard.ShieldNotice
import com.padguard.child.guard.UninstallDefenseModule
import com.padguard.child.ui.overlay.WatermarkOverlay
import com.padguard.core.common.Logger
import com.padguard.core.common.TimeProvider
import com.padguard.core.engine.guard.GuardMasterSwitch
import com.padguard.core.data.model.AckStatus
import com.padguard.core.data.model.CommandAck
import com.padguard.core.data.model.Heartbeat
import com.padguard.core.data.model.LogType
import com.padguard.core.data.model.RiskLevel
import com.padguard.core.data.model.RiskType
import com.padguard.core.data.db.ParentMessageEntity
import com.padguard.core.data.repository.AuthRepository
import com.padguard.core.data.repository.CommandRepository
import com.padguard.core.data.repository.guardActive
import com.padguard.core.data.repository.shouldReleaseControl
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
import com.padguard.core.transport.http.AppInventoryItem
import kotlinx.coroutines.sync.withLock
import com.padguard.core.transport.http.CommandsResponse
import com.padguard.core.transport.http.CredentialStore
import com.padguard.core.transport.http.PolicyFetchResult
import com.padguard.core.transport.EyeCareRestOutbox
import kotlinx.serialization.json.Json
import java.io.File
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
    /**
     * 上网防护（本地 DNS 过滤 VPN）控制器。
     *
     * 第九轮修复：此前该控制器**全工程零调用**，家长端能配的黑名单/白名单/违规分类
     * 在设备侧没有任何执行通道，且不报错（静默失效）。现在每次策略落地与周期自检都走
     * [reconcileWebGuard] 对账一次。
     */
    @Inject lateinit var webGuardVpn: com.padguard.child.guard.WebGuardVpnController
    @Inject lateinit var remote: RemoteDataSource
    @Inject lateinit var transportSettings: TransportSettings
    @Inject lateinit var foregroundApp: ForegroundAppMonitor

    /** 前台切换唤醒信号：让「孩子点开超额应用」不必等到下一个 15 秒采样点才被顶回 */
    @Inject lateinit var tickSignal: GuardTickSignal
    @Inject lateinit var snapshot: DeviceSnapshotCollector
    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var policyRepository: PolicyRepository
    @Inject lateinit var logRepository: LogRepository
    /** A1-P0：WEB_GUARD_DOWN 告警节流计时（单调钟），避免重启失败时每 tick 刷屏 */
    private var lastWebGuardDownAlarmMs = 0L
    /** WEB_GUARD_DOWN 是否已上报：隧道恢复时只关一次，不每 tick 发无意义请求 */
    private var webGuardDownRaised = false
    @Inject lateinit var admin: DeviceAdminBridge
    @Inject lateinit var lockController: LockController
    @Inject lateinit var usageSessionManager: UsageSessionManager
    @Inject lateinit var permissionGranter: PermissionGranter
    @Inject lateinit var cleanKillDetector: com.padguard.child.guard.CleanKillDetector
    @Inject lateinit var timeProvider: TimeProvider
    @Inject lateinit var unlockRequestRepository: UnlockRequestRepository
    @Inject lateinit var locationCollector: LocationCollector

    @Inject lateinit var locationAutoReporter: LocationAutoReporter
    @Inject lateinit var appInventory: AppInventoryCollector
    /** 台账差分器：把"当前装了哪些应用"变成"这次多了谁、少了谁"，用于装卸事件告警（R-P3-10） */
    @Inject lateinit var appInventoryDelta: AppInventoryDeltaTracker
    @Inject lateinit var commandRepository: CommandRepository
    /** 应用管控策略的本地镜像，供无障碍服务在非 DO 设备上做降级拦截 */
    @Inject lateinit var appGuardStore: AppGuardPolicyStore
    /** 锁屏呈现统一入口：前台走 Activity、后台走悬浮层（后台启动 Activity 会被系统拦截） */
    @Inject lateinit var lockScreenPresenter: LockScreenPresenter
    /**
     * P2（DO 治本路径）独立载体：仅当本机持有 Device Owner 时，于受控态真回收应用权限 + 置 Kiosk 标志。
     * 非 DO 设备整体 no-op，不影响 P0/P1 三件套。注入此处以确保 @Singleton 被实例化并开始订阅锁定态。
     */
    @Inject lateinit var deviceOwnerEnforcer: DeviceOwnerEnforcer

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
    /** 绑定后隐藏管理控制台（图标消失），仅留玻璃球给孩子 */
    @Inject lateinit var appHider: AppHider
    /** 玻璃球悬浮控制器（绑定后常驻展示已用/剩余，是隐藏形态下孩子可见、家长可触达的唯一载体） */
    @Inject lateinit var glassBall: com.padguard.child.glassball.GlassBallController
    /** 计时开始确认浮层：needsStartConfirm 为真时以系统浮层弹出，确保后台保活也能提示孩子确认计时 */
    @Inject lateinit var usageStartOverlay: UsageStartOverlay

    /**
     * 无障碍截屏通道：远程截屏优先走这里 —— 授权（启用无障碍）持久有效，
     * 不再向孩子弹"是否允许录制屏幕"；不可用时退回 MediaProjection 链路。
     */
    @Inject lateinit var accessibilityScreenshot: AccessibilityScreenshot

    /** 实时看屏的无障碍推流通道：令牌缺失时的"零弹窗"路径（见 AccessibilityLiveStreamer） */
    @Inject lateinit var a11yLiveStreamer: com.padguard.child.capture.AccessibilityLiveStreamer

    /** 无障碍截屏能力自愈：陈旧元数据（升级后能力位缺失）时静默重切换服务 */
    @Inject lateinit var a11yCapabilityHealer: com.padguard.child.guard.AccessibilityCapabilityHealer

    /** 整机管控总开关（权威真相源）：active=false 时释放设备，锁屏浮层/无障碍拦截/DO 回收入口都来此问询 */
    @Inject lateinit var guardMasterSwitch: GuardMasterSwitch

    /** 权限掉失自愈：设备重启（如国产 ROM 授予特殊权限触发强制重启）后核对无障碍/悬浮窗是否仍在 */
    @Inject lateinit var permissionHealthChecker: PermissionHealthChecker

    /** 授权保持器：装机授权完成后仍全量复检，缺失则 DO 静默补 / 非 DO 拉回引导流程（防回退自愈） */
    @Inject lateinit var authorizationKeeper: AuthorizationKeeper

    /**
     * 信息发布「系统级置顶」渲染控制器：把家长下发的信息置于所有 Activity（含锁屏）之上。
     * 其内部在无悬浮窗权限时自动回退到 [MessageActivity]，故 ShowMessage 一律走它。
     */
    @Inject lateinit var messageOverlay: MessageOverlayController

    /**
     * 卸载防御聚合（问题二·非 DO 加固）：权限活性自检 / 系统级封死 / 密码闸门告警。
     * 在 [refreshPolicy] 中周期性触发，确保「管控悄然失效」能被家长端及时看到。
     */
    @Inject lateinit var uninstallDefense: UninstallDefenseModule

    /**
     * 自身常驻加固基线（永驻后台 / 进程不可被杀 / 关闭卸载需家长密码）。
     * 离线即生效、周期自愈，不依赖服务端策略（见 [SelfProtectionModule]）。
     */
    @Inject lateinit var selfProtection: SelfProtectionModule

    /** 授权闩锁存储：采集权限持久化（见 [CaptureConsentStore]）。 */
    @Inject lateinit var captureConsentStore: CaptureConsentStore

    /**
     * 当前是否处于**可管控态**（由 [observeBinding] 维护）：已绑定 **且** 监护人已明示同意当前版本协议。
     *
     * 任一条件不成立都必须停止一切管控执行：
     * - 未绑定：没有家长就没有管控依据。若仍按本地陈旧缓存策略执行，会出现
     *   "家长端已看不到这台设备、孩子端却被锁死无法操作"的死锁；而系统级 lockNow()
     *   一旦落下，应用自身再也解不开，只能靠 adb 救援。
     * - 未同意协议：采集/管控缺少合法性基础（《隐私政策》承诺"明示同意后方可启用守护能力"）。
     *   权限流水线可能因自愈而跑完，但那不等于有人对协议正文作过明示同意。
     */
    private val boundNow = AtomicBoolean(false)

    /**
     * 上一次本服务被拉起的触发原因（见 [REASON_BOOT]/[REASON_DAEMON]/[REASON_WATCHDOG] 等），
     * 供「守护状态快照」随 15s 心跳上报，使服务端/家长端能区分进程是被谁拉回的。
     */
    private var lastStartReason: String = "unknown"

    /**
     * 权限自愈版本号：每次运行期危险权限被实际重新授予（[PermissionHealthChecker.healRuntimePermissions] 返回值 > 0）
     * 即 +1。服务端据此识别「权限状态是否发生变化」，无需解析具体权限细节。
     */
    private val permissionVersion = AtomicInteger(0)

    /**
     * 服务自己的作用域，不用 `@ApplicationScope`。
     *
     * 这些循环必须随服务一起结束：若挂在应用级作用域上，服务被停掉后循环还在跑，
     * 重新启动服务就会叠加出第二套心跳与采样，表现为心跳频率莫名翻倍。
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, t ->
            // 仅记录：所有周期性任务均已 runSafely 包裹，这里是未预期逃逸异常的兜底，
            // 避免直接落到 JVM 默认 handler 而丢失上下文（如未来新增的 scope.launch 漏包 runSafely）。
            // CancellationException 由 SupervisorJob 正常处理，不会进入此处。
            Logger.e(TAG, t) { "uncaught exception in GuardService scope" }
        }
    )

    private var mainLoop: Job? = null
    private var downlinkJob: Job? = null
    private var effectJob: Job? = null
    private var bindingJob: Job? = null
    /** 指令专用拉取循环（见 [startCommandLoop]） */
    private var commandJob: Job? = null
    /** 计时开始确认浮层观察者（见 [observeUsageStart]），随服务销毁经 [scope.cancel] 一并停止 */
    private var usageStartJob: Job? = null

    private val transportStarted = AtomicBoolean(false)
    private val bootstrapped = AtomicBoolean(false)
    /** 安装/卸载完成后请求立即同步一次应用台账 */
    private val appSyncRequested = AtomicBoolean(false)
    /** 动态注册的装机/卸机监听；命中即置 [appSyncRequested]，由主循环在数秒内全量上报 */
    private var appChangeReceiver: BroadcastReceiver? = null

    /** 设备管理员 / DO 身份变更监听（停用、摘除 DO），命中即立刻核验并告警 */
    private var adminChangeReceiver: BroadcastReceiver? = null

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
     * 服务端 HTTPS 是否可达：由最近一次成功/失败的 HTTPS 请求刷新。
     * 命令轮询(给服务端喂"在线心跳"的 HTTP 长轮询)的节奏只由此门控，
     * 与 MQTT 连接态彻底解耦——MQTT 抖一下(华为 EMUI 杀后台 / WiFi↔流量切换 / Doze)不该停掉
     * 存活信号，否则服务端只剩 30s 心跳、却被 20s 超时判离线，家长端看到"一会儿在线一会儿掉线"。
     */
    @Volatile private var httpReachable = true
    /**
     * 连续 HTTP 失败计数。心跳循环（mainLoop）与指令兜底拉取循环（commandLoop）在不同线程上
     * 都会读改写本计数，普通 Int 的 ++ / 清零交错会丢更新，导致 HTTPS 不可达判定滞后。
     * 与同文件的 permissionVersion 一致用原子量；httpReachable 本身仍是 @Volatile 布尔。
     */
    private val consecutiveHttpFails = AtomicInteger(0)
    private val HTTP_UNREACHABLE_THRESHOLD = 3

    /**
     * 上一次真正投递出去的常驻通知正文。
     *
     * 主循环约 2 秒一拍，正文却几分钟才变一次；用它做去重，避免无意义的跨进程 notify
     * （见 [updateNotification]）。null = 尚未投递过，首拍必然投递。
     */
    private var lastNotificationText: String? = null

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
     * 兜底拉取"连续失败轮次"计数。
     *
     * 某条指令执行/回执持续失败时，游标会被刻意按住不推进（否则该指令永久丢失），
     * 但也不能无限按住：那会让这条通道每 3 秒重试一次、日志刷屏，
     * 并且挡住后面所有新指令。连续 [MAX_STUCK_PULL_ROUNDS] 轮后放弃本批并推进游标。
     */
    @Volatile private var stuckPullRounds: Int = 0

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
            when (action) {
                Intent.ACTION_SCREEN_OFF -> {
                    // 锁屏 / 息屏：系统可能强制回收 MediaProjection（onStop 回调会如实置空，
                    // 届时由无障碍主路接管）。**不要主动 stop() 投影**：Android 14+ 的 consent
                    // 只能换一次投影，主动销毁后下次看屏必然再向孩子弹一次系统授权框。
                    // suspendProjection 现在只表示「停推流、保活投影会话」。
                    runCatching { ScreenCaptureSession.suspendProjection() }
                        .onFailure { Logger.w(TAG) { "suspend projection on screen-off failed: ${it.message}" } }
                    // 熄屏即暂停计时（电源键熄屏 / 系统超时熄屏一视同仁）：
                    // 冻结本地剩余并立即挂 TIMER_PAUSED；锁屏页不会因此点亮屏幕
                    // （LockScreenPresenter.ensureCoverage 有 isScreenOn 守卫）。
                    runCatching { usageSessionManager.pause(TimerPauseSource.SCREEN_OFF) }
                        .onFailure { Logger.w(TAG, it) { "pause timer on screen-off failed" } }
                }
                Intent.ACTION_SCREEN_ON -> {
                    // 亮屏即恢复**熄屏来源**的暂停；手动暂停（长按悬浮球确认）刻意不恢复，
                    // 否则孩子按一下电源键就能绕过手动暂停。
                    runCatching { usageSessionManager.resume(TimerPauseSource.SCREEN_OFF) }
                        .onFailure { Logger.w(TAG, it) { "resume timer on screen-on failed" } }
                    // 屏幕刚点亮：锁定覆盖最容易被撕开的时刻，立即对账一次（见原注释）。
                    // 稍作延迟：广播到达时 PowerManager.isInteractive 可能尚未翻转。
                    Handler(Looper.getMainLooper()).postDelayed({
                        runCatching { lockScreenPresenter.reenforce() }
                            .onFailure { Logger.w(TAG, it) { "lock coverage recheck on screen-on failed" } }
                    }, SCREEN_ON_RECHECK_DELAY_MS)
                    // 亮屏即静默重绑无障碍服务：EMUI 常在息屏/锁屏时把服务杀掉，此刻补一次
                    // ensureEnabled（DO 无弹窗），把"授权钉死"在亮屏瞬间自愈，避免下次看屏
                    // 落到投影授权框（权限丢失根因的主动防御，而非丢失后补救）。
                    scope.launch { runCatching { a11yCapabilityHealer.ensureEnabled() } }
                    // 解锁后抢恢复看屏（见 maybeResumeLiveViewOnUnlock）
                    maybeResumeLiveViewOnUnlock()
                    // 亮屏即刷新计时会话并复位悬浮球：确保「第二天」等新日切后，计时开始确认浮层 /
                    // 剩余额度立即刷新、玻璃球重新可见（后台保活下孩子不在 App 前台，必须主动拉起）。
                    if (boundNow.get()) {
                        scope.launch { runSafely("sessionSyncOnScreenOn") { usageSessionManager.refresh() } }
                        scope.launch { runSafely("showBallOnScreenOn") { glassBall.show() } }
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    // 用户已解锁（亮屏且过了锁屏页）：同样抢一次无障碍静默重绑 + 看屏恢复
                    // 兜底恢复：个别 ROM 不发 ACTION_SCREEN_ON，只发 USER_PRESENT；
                    // 同源守卫保证不会误解除手动暂停。
                    runCatching { usageSessionManager.resume(TimerPauseSource.SCREEN_OFF) }
                        .onFailure { Logger.w(TAG, it) { "resume timer on user-present failed" } }
                    scope.launch { runCatching { a11yCapabilityHealer.ensureEnabled() } }
                    maybeResumeLiveViewOnUnlock()
                    // 解锁即刷新计时会话并复位悬浮球（同上，覆盖无系统锁屏时 USER_PRESENT 即屏幕点亮的情形）
                    if (boundNow.get()) {
                        scope.launch { runSafely("sessionSyncOnUserPresent") { usageSessionManager.refresh() } }
                        scope.launch { runSafely("showBallOnUserPresent") { glassBall.show() } }
                    }
                }
                ConnectivityManager.CONNECTIVITY_ACTION -> {
                    // 网络恢复（非"无连接"广播）：即刻把监管拉回在线，而非苦等下一轮心跳/重连退避。
                    // 孩子开飞行模式 / 关 Wi-Fi 再恢复时，若只靠 15s 心跳 + Paho 重连退避，
                    // 设备会"失联"十几秒，期间锁屏/解锁/看屏等实时指令延迟落地。
                    // 网络一回来就：①标记 HTTPS 骨干可达→指令拉取循环切回快速路径并立即排空；
                    // ②强制重连 MQTT（绕过 Paho 退避）；③立即同步策略，家长改动秒级生效。
                    val noConn = intent?.getBooleanExtra(
                        ConnectivityManager.EXTRA_NO_CONNECTIVITY, false
                    ) ?: false
                    if (!noConn) onConnectivityRestored()
                }
            }
        }
    }

    /**
     * 网络恢复即刻自愈（「永不掉线」的实时性补强，R28）。
     *
     * ## 触发时机
     * [restartReceiver] 收到 `CONNECTIVITY_ACTION` 且 [ConnectivityManager.EXTRA_NO_CONNECTIVITY]
     * 为 false（即网络重新可用）时调用。这是孩子「关网络 → 重新打开」后把设备秒级拉回在线的关键，
     * 不依赖 Paho 自身的重连退避（最坏十几秒）也不等 15s 心跳。
     *
     * ## 三步动作
     * 1. `httpReachable = true`：指令拉取循环据此从 `COMMAND_PULL_IDLE_MS`（慢）切回
     *    `COMMAND_PULL_INTERVAL_MS`（快），且下一跳立即排空待下发指令；
     * 2. [startCommandLoop] 立刻取消旧循环并重启——同一拍就跑一次 `drainPendingCommands()`，
     *    锁屏/解锁/看屏/策略等"只下发一次"的实时指令不再等下一跳（≤3s）；
     * 3. 强制重连传输（`remote.start()` 幂等，[startMutex] 保护）+ 立即 [refreshPolicy]，
     *    绕过 Paho 重连退避、把家长刚改的策略立刻拉下来。
     *
     * ## 边界
     * - 未绑定设备不动作（无管控依据，[startTransport] 亦会拒绝）；
     * - 网络再次断开时由心跳的 `httpReachable` 回落 + 命令循环 idle 分支自然降级，无需在此处理；
     * - 与心跳/命令循环互不冲突：`startCommandLoop` 幂等重启，[remote.start] 仅重连不重复建流。
     */
    private fun onConnectivityRestored() {
        if (!credentials.isBound) {
            Logger.i(TAG) { "connectivity restored but device not bound, skip recovery" }
            return
        }
        // ① HTTPS 骨干立即可达 → 指令拉取循环切回快路径
        httpReachable = true
        // ② 立即排空待下发指令（实时指令秒级落地，不再等 idle 跳）
        startCommandLoop()
        // ③ 强制重连传输（绕过 Paho 退避）+ 立即同步策略
        scope.launch {
            runSafely("connectivityRecover") {
                remote.start()   // 幂等重连（startMutex 保护，重复调用安全）
                refreshPolicy()  // 立即拉取最新策略，家长改动秒级生效
            }
        }
        Logger.i(TAG) { "connectivity restored, kicked immediate recovery (reconnect + policy sync)" }
    }

    /**
     * 解锁后用无障碍通道抢恢复看屏会话。
     *
     * 锁屏会把投影令牌强制回收（见 [restartReceiver] 的 [Intent.ACTION_SCREEN_OFF] 处理），
     * 且 EMUI 常把无障碍服务一并关掉。解锁后若仍处看屏窗口，立刻：
     *   1) DO 静默重启用无障碍服务（EMUI 把它自动关掉时自愈，非 DO 设备则记日志引导）；
     *   2) 走无障碍零弹窗通道推流，不等服务端 reconcile（8s 冷却重发）+ 60s 等待窗口。
     * 这样"锁屏/进程被杀后家长点看屏"能秒级恢复，而不是退回投影授权通知逼孩子确认。
     * 仅当无障碍确实不可用（服务被关且非 DO、或服务重绑失败）时，才由
     * [com.padguard.child.capture.AccessibilityLiveStreamer.onA11yUnavailable] 退回投影引导。
     */
    private fun maybeResumeLiveViewOnUnlock() {
        scope.launch {
            runCatching {
                val live = GuardHeartbeat.readLiveView(applicationContext) ?: return@runCatching
                if (System.currentTimeMillis() > live.deadlineWallMs) {
                    Logger.i(TAG) { "unlock: live view session expired, skip resume" }
                    return@runCatching
                }
                Logger.i(TAG) {
                    "unlock: live view session still active, re-routing to a11y channel (hd=${live.highDefinition})"
                }
                runCatching { a11yCapabilityHealer.ensureEnabled() }
                a11yLiveStreamer.start(live.highDefinition)
            }.onFailure { Logger.w(TAG) { "resume live view on unlock failed: ${it.message}" } }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 无障碍截屏能力自愈：服务启用早于 canTakeScreenshot 能力版本时（覆盖升级场景），
        // 系统不重读元数据，截屏/实时看屏被迫走投影链路（红标+授权框）。
        // Device Owner 有权静默重切换无障碍服务迫使系统重读 —— 启动时检测并自愈一次。
        scope.launch {
            runCatching { a11yCapabilityHealer.healIfNeeded() }
                .onFailure { Logger.w(TAG) { "a11y capability heal failed: ${it.message}" } }
        }
        // 无障碍开关「秒级」自愈监听：订阅 Settings.Secure 的两把键，EMUI 一键清理把本服务
        // 移出启用名单 / 把总开关置 0 的瞬间即静默写回，把恢复窗口从 5 分钟自检压到毫秒级。
        // 注册在抗杀的 GuardService（非无障碍服务自身 —— 后者正是一键清理的第一刀目标）。
        // 进程被 force-stop 时观察器随进程消亡，由 AB 双进程/看门狗/开机广播拉回后 onCreate 重建。
        // 第二参数是"仍在管控"探针：家长解绑时先把主控开关置 false，观察器据此拒绝把
        // 刚被主动禁用的无障碍服务重新拉起（防解绑后"复活"管控）。
        runCatching { a11yCapabilityHealer.startWatching(scope) { guardMasterSwitch.active.value } }
            .onFailure { Logger.w(TAG, it) { "start a11y settings watcher failed" } }
        // 兜底拉取游标从盘上恢复。必须在 startCommandLoop 之前加载：字段初始化器
        // 不能碰 Context（实例化先于 attachBaseContext，见 lastCommandPullAtMs 注释），
        // 故推迟到这里；若懒加载到首次使用，进程重启后第一轮拉取会误用 0 把全部
        // 历史 PENDING 捞一遍。无记录时 getLong 默认 0 = 拉回全部未过期 PENDING，
        // 与首次安装语义一致。
        lastCommandPullAtMs = commandPollPrefs.getLong(KEY_LAST_PULL_AT, 0L)
        // 游标现在统一用「服务端校准时间」写（timeProvider.now()）。若盘上留着旧版本写的
        // 纯本地墙钟值，而本地时钟比服务端快很多，这个游标会落在服务端未来 ——
        // 服务端 pendingForPolling 会 clamp，但代价是这段时间内新指令可能被过滤。
        // 超过 5 分钟容差就判定为不可信，归零重拉（幂等由 tryClaim + msgId 保证）。
        if (lastCommandPullAtMs > timeProvider.now() + COMMAND_CURSOR_FUTURE_TOLERANCE_MS) {
            Logger.w(TAG) {
                "command cursor in the future (cursor=$lastCommandPullAtMs, now=${timeProvider.now()}), reset to 0"
            }
            lastCommandPullAtMs = 0L
            commandPollPrefs.edit().remove(KEY_LAST_PULL_AT).apply()
        }
        // 必须第一时间进入前台：Android 对 startForegroundService 后的宽限期只有 5 秒，
        // 超时直接 ForegroundServiceDidNotStartInTimeException 崩溃。
        // 所以先挂一个"启动中"的通知，真实状态等拿到数据后再刷。
        enterForeground()
        // 实时看屏兜底接线：无障碍通道在等待窗口内始终不可用（用户确未启用/能力缺失）时，
        // 退回投影推流路径，与既有"无障碍不可用→投影"语义一致。
        // 进程刚被系统杀死、无障碍服务重绑有延迟时，[AccessibilityLiveStreamer] 会先等待就绪再推流，
        // 正常情况下远早于超时即恢复，不会走到这里——这是"杀进程重开仍自动恢复看屏"的关键。
        a11yLiveStreamer.onA11yUnavailable = { hd ->
            runCatching {
                val intent = ScreenCaptureService.liveStreamIntent(this@GuardService, hd)
                dispatchCapture(intent)
            }.onFailure { Logger.w(TAG) { "a11y unavailable fallback to projection failed: ${it.message}" } }
        }
        // 监听本机应用装卸：孩子手动安装/卸载是即时管理动作，命中即触发台账同步，
        // 避免家长端在 30 分钟周期内看到"幽灵应用"且对其授权无效。
        registerAppChangeReceiver()
        // 动态重启广播（屏幕亮起 / 网络恢复时立即把服务拉回，缩小脱管窗口）
        registerRestartReceiver()
        // 设备管理员 / DO 身份变更（用户在设置里停用、或 adb 摘除）→ 立刻核验并上报。
        // 没有这条链时，只能等下一轮策略拉取（断网时可能几分钟到几十分钟），
        // 这段时间里"管控已失效"对家长完全是隐形的。
        registerAdminChangeReceiver()
        Logger.i(TAG) { "GuardService created, controlMode=${admin.refreshControlMode()}" }
        // 被清理重启检测：必须在下面 writeGuardAlive() **之前**读旧心跳——那次写入会
        // 覆盖上次进程的最后心跳，之后再判永远只见"刚刚"。判定读一个 KV 文件（微秒级），
        // 同进程内服务重建被进程级闸挡掉，不会重复计数（见 CleanKillDetector）。
        runCatching { cleanKillDetector.judgeOnProcessStart(scope) }
            .onFailure { Logger.w(TAG, it) { "clean-kill judge failed" } }
        // 启动清理残留 PENDING 指令（认领了但未执行完就退出），使其可被服务端重发重新认领，
        // 避免崩溃的指令被 7 天幂等窗口判重丢弃而永久静默丢失。
        scope.launch { runCatching { commandRepository.resetInFlightOnStartup() } }
        isRunning = true
        // 写第一条心跳：守护进程（:daemon）与闹钟/看门狗据此判断主进程死活。
        // 必须在任何可能拖延的启动流程之前落盘，否则进程若在 bootstrap 中途被杀，
        // 心跳文件还是旧的，外部通道会误判"刚写过"而延迟拉起。
        GuardHeartbeat.writeGuardAlive(this)
        // 自身常驻加固基线（永驻后台 / 进程不可被杀 / 关闭卸载需家长密码 的治本落地）：
        // 只要持 DO/PO 即刻系统级封死自身卸载、置灰"强行停止/清除数据"、封住恢复出厂/安全模式，
        // 离线开机也立即生效，不依赖服务端策略。非 DO/PO 自动降级（靠看门狗+无障碍兜底）。
        // 放在 onCreate（每次服务实例创建都会跑），即便进程被杀后被守护/看门狗拉回，也会重新钉死。
        if (selfProtection.applyBaseline()) {
            Logger.i(TAG) { "self-protection baseline applied (anti-force-stop / anti-uninstall / anti-reset)" }
        }
        // 注册看门狗：进程被系统/清理工具杀掉后由系统侧周期拉起（START_STICKY 并不保证重建）。
        // 放在 onCreate 而不是绑定流程里：即便这台设备还没绑定，守护也要在，
        // 否则"家长扫码绑定后立刻下发指令"会因为没有宿主而丢失。
        runCatching { GuardWatchdogService.schedule(this) }
            .onFailure { Logger.w(TAG) { "watchdog schedule failed: ${it.message}" } }
        // 双进程守护：拉起独立 :daemon 进程（幂等）。主进程被整体杀掉时，
        // 进程内的 onTaskRemoved/START_STICKY/动态广播全部失效，只有它能跨进程把主进程拉回。
        //
        // AB 锁（本轮）：优先用 bindService 建立**双向绑定**，而不是只发 start。
        // 绑定既能立即把 daemon 创建出来（目标进程不存在时系统会创建），
        // 又能让 daemon 侧持有指向本服务的 binder —— 本进程被杀时它那边立刻收到
        // onBindingDied，重绑即重建本进程。start 仅作兜底（绑定被系统拒绝的极少数场景）。
        if (!GuardLink.ensureDaemonBound(this)) {
            GuardDaemonService.start(this)
        }
        // 闹钟兜底通道：与 JobScheduler 并行的第二条系统调度路径（已绑定才排班）
        if (GuardHeartbeat.readGuardState(this)) {
            GuardAlarmReceiver.schedule(this)
        }
        // 计时开始确认浮层观察者：needsStartConfirm 为真且亮屏/已绑定时，用系统浮层弹出，
        // 修复「后台保活下计时开始确认框不可见、第二天亮屏无提示、计时不启动」。
        observeUsageStart()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val reason = intent?.getStringExtra(EXTRA_REASON).orEmpty().ifBlank { "unknown" }
        Logger.i(TAG) { "onStartCommand reason=$reason" }
        lastStartReason = reason
        // 每次被唤起都刷心跳：进程重建（START_STICKY intent=null）后外部通道要立刻看到新鲜值
        GuardHeartbeat.writeGuardAlive(this)

        // 前台状态可能因进程重建而丢失，每次进来都确保一次（内部幂等）
        enterForeground()

        if (bootstrapped.compareAndSet(false, true)) {
            bootstrap(reason)
        } else if (reason == REASON_BOOT) {
            // 极少见：进程未死但收到开机广播，仍需走一次开机复位
            scope.launch { runSafely("onDeviceBoot") { policyEngine.onDeviceBoot(); reconcileWatermark(); reconcileWebGuard() } }
        } else if (reason == REASON_DAY_ROLLOVER) {
            // 系统时间/时区/日期变化：立即日切对账 + 全量重铺管控。
            //
            // 受信钟使回拨不倒退、前拨不超前，孩子手动改时间伪造不出"新的一天"、也跳不过时段边界：
            // 把墙钟改回去只是让本次广播白跑（幂等），管控仍以受信钟为准。
            //
            // 但手动改时间最常发生在「同日内跨时段边界」——例如把 21:00 的锁屏往前拨 2 小时想逃锁、
            // 或往后拨想提前进锁。checkDayRollover 只管跨零点、不管同日内边界，
            // 所以这里必须再跑一次 applyCurrent(force=true) 用受信钟立刻重算并落地管控状态，
            // 把反应窗口从最长 15s tick 压到秒级（与 REASON_TASK_REMOVED 同构）。
            // 幂等：真实日切时 checkDayRollover 先重置日 key、applyCurrent 再按新日重铺。
            scope.launch {
                runSafely("dayRollover") {
                    usageSessionManager.checkDayRollover()
                    usageSessionManager.refresh()
                    policyEngine.applyCurrent(force = true)
                    reconcileWatermark()
                    reconcileWebGuard()
                    // 仍处锁定态：立刻把锁屏页重新顶回前台（reenforce 幂等，非锁定态时为空操作）。
                    if (lockController.state.value.locked) lockScreenPresenter.reenforce()
                }
            }
        } else if (reason == REASON_PROCESS_RESTART) {
            // 进程重建后的幂等补拉：服务首次创建时上面的 bootstrap() 已完成全部初始化；
            // 服务本来就在跑时只是多投递一次 intent，**刻意不做策略重压** —— 否则每个
            // Activity 重建都会触发一轮 applyCurrent(force=true)，平白扰动管控状态。
            Unit
        } else if (reason == REASON_FLUSH) {
            // 锁屏页刚提交了解锁申请：这类交互有时效，等下一个 60s 上报窗口太慢，
            // 家长可能要一分多钟后才看到，孩子会以为申请没发出去而反复提交。
            scope.launch { runSafely("flushNow") { uploadPending() } }
        } else if (reason == REASON_PERMISSION_CHANGED) {
            // 授权状态刚变化（装机完成 / 设备管理员激活 / 无障碍重绑 / 操作密码下发）：
            // 上面的 enterForeground() 已按实时状态重建常驻通知，这里再以正文去重刷新一次。
            // 刻意不做两件事：① 不重放策略（与策略无关，applyCurrent(force=true) 会平白
            // 触发一轮管控）；② 不撤采集引导通知——那条只有在"授权确已就位"时才该撤，
            // 否则会把一条正等孩子点击的有效引导误清掉（见 CaptureConsentPrompter.clear 调用点）。
            updateNotification()
        } else if (reason == REASON_TASK_REMOVED) {
            // 任务被划掉 / 一键清理后重启：立即重铺管控（含锁屏），把"清完历史记录就脱管"
            // 的窗口压到最小——不再干等下一轮 15s doTick 自愈。
            scope.launch { runSafely("recoverAfterTaskRemoved") {
                policyEngine.applyCurrent(force = true)
                reconcileWatermark()
                reconcileWebGuard()
                // 仍处锁定态：立刻把锁屏页重新顶回前台（reenforce 幂等，非锁定态时为空操作）。
                if (lockController.state.value.locked) lockScreenPresenter.reenforce()
            } }
        } else {
            // 已在运行，外部只是想催一次策略对齐（如管理器刚激活、Kiosk 被意外退出）
            scope.launch { runSafely("applyOnKick") { policyEngine.applyCurrent(force = true); reconcileWatermark(); reconcileWebGuard() } }
        }

        // START_STICKY：被系统回收后自动重建。
        // 注意重建时 intent 为 null，所以所有初始化逻辑都不能依赖 intent 里的参数。
        return START_STICKY
    }

    /**
     * 孩子从最近任务里把 App 划掉、或点了"清除历史/释放内存"时触发。
     *
     * ## 为什么要主动重启
     * "划掉任务"在很多 ROM 上会连同进程一起杀掉守护服务（不只是 Activity）。
     * 一旦服务没了，命令轮询与 MQTT 全部停摆：家长端看到的设备还"在线"，
     * 但锁屏/截屏/看屏指令全部石沉大海 —— 这正是"装了管控却管不住"的典型成因。
     * EMUI/国产 ROM 的「清除历史应用并释放内存」一键清理尤其激进，会直接把
     * 前台服务所在的进程一并回收，于是锁屏界面被清掉后管控系统彻底失联。
     *
     * ## 为什么必须"立即"重启（本次修复的核心）
     * 旧实现用 1 秒延迟重启：把 `startForegroundService` 包在
     * `postDelayed(..., 1000)` 里投递到主线程消息队列。但 OEM 一键清理会在
     * `onTaskRemoved` 返回后**毫秒级**杀掉本进程，延迟任务根本来不及执行 ——
     * 重启永远不发生，管控永久失联（即用户反馈的"清完历史记录系统就退出管控"）。
     *
     * `startForegroundService` 是发往 `system_server` 的 IPC，**不依赖本进程存活**；
     * 即便随后本进程被回收，系统也会据此拉起一个全新的服务实例并重新铺锁屏。
     * 因此这里**同步立即**发起重启作为主路径，仅在个别 ROM 对"当口同步启动"暂时
     * 拒绝时，再用一个极短的兜底延迟补一次（本进程若已被杀，兜底不会执行，
     * 但主路径的 IPC 已经发出，不影响恢复）。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Logger.w(TAG) { "task removed; restarting guard service immediately (survive recents clear / memory release)" }
        restartAfterTaskRemoved()
        // 兜底 1：部分 ROM 在 onTaskRemoved 当口立即同步启动会被系统暂时拒绝，稍后再补一次。
        android.os.Handler(mainLooper).postDelayed(
            { restartAfterTaskRemoved() },
            TASK_REMOVED_RESTART_FALLBACK_MS
        )
        // 兜底 2（关键）：EMUI 等国产 ROM 的「清理历史应用并释放内存」是整包 kill，
        // 常把上面的 startForegroundService IPC 一并吞掉、或本进程在 IPC 落地前已被回收，
        // 导致"立即自启"根本没机会执行 → 守护永久失联。此处再排一个由 system_server
        // 持有的系统闹钟（~1.2s 后触发），它不依赖本进程存活，杀进程后照样准时拉起守卫。
        // 仅 killBackgroundProcesses（非 force-stop）场景有效；force-stop 由无障碍服务重绑兜底。
        runCatching { GuardAlarmReceiver.scheduleImmediate(applicationContext) }
            .onFailure { Logger.e(TAG, it) { "schedule immediate restart alarm failed" } }
    }

    /** 任务被清除后重启守护服务（独立方法，便于主路径 + 兜底各调用一次）。 */
    private fun restartAfterTaskRemoved() {
        runCatching { start(applicationContext, REASON_TASK_REMOVED) }
            .onFailure { Logger.e(TAG, it) { "restart after task removed failed" } }
    }

    /**
     * AB 锁的 A 端点：把本服务暴露给 `:daemon` 绑定。
     *
     * ## 为什么必须返回非 null（此前返回 null，是本轮修复的核心）
     * 返回 null 时 `bindService` 只会拿到 `onNullBinding`，系统**不会**因此创建或保留进程 ——
     * 所有自恢复就只能靠 `startForegroundService`：API 31+ 从后台（JobService / 广播回调）
     * 启动前台服务直接抛 `ForegroundServiceStartNotAllowedException`，国产 ROM 还会按"自启"
     * 拦截。外部表现就是"一键清理之后再也起不来"。
     *
     * 现在 daemon（独立进程）持有一条指向本服务的 binder 绑定：主进程被杀 → daemon 侧
     * `onBindingDied` 立即触发 → 重绑 → 系统**创建主进程**并回调本方法。
     * 进程被创建即意味着 [onCreate] 里那一整套自愈（前台化、看门狗重排、闹钟重排、
     * 权限复检）都会跑一遍，管控当场恢复。
     *
     * ## 为什么在 onBind 里补一次"唤醒"
     * 被 bind 拉起的服务**不会**收到 `onStartCommand`（那条路径只有 startService/
     * startForegroundService 才有），因此 [bootstrap] 不会自动执行 —— 会出现
     * "进程在、服务在、但主循环与策略都没跑"的半活状态。这里显式补齐，且全部幂等：
     * 若同一实例随后又收到 onStartCommand，`bootstrapped` 的 CAS 会让它只做一次。
     */
    override fun onBind(intent: Intent?): IBinder {
        wakeFromBinder()
        return linkMessenger
    }

    /** 被 binder 唤起时补齐主干（幂等；与 onStartCommand 共用同一套 CAS 守卫） */
    private fun wakeFromBinder() {
        Logger.i(TAG) { "bound by :daemon (AB lock), ensuring main loop" }
        GuardHeartbeat.writeGuardAlive(this)
        runCatching { enterForeground() }
        if (bootstrapped.compareAndSet(false, true)) {
            bootstrap(REASON_BIND)
        }
        startCommandLoop()
        // 反向维持 AB 锁：本侧也要拉住 daemon，避免"daemon 死了没人知道"
        GuardLink.ensureDaemonBound(this)
    }

    private val linkMessenger: IBinder by lazy {
        Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                if (msg.what == GuardLink.MSG_WAKE) {
                    // 对方（daemon）确认连上了：只需确保主干在跑，不必重做 bootstrap
                    GuardHeartbeat.writeGuardAlive(this@GuardService)
                    startCommandLoop()
                }
            }
        }).binder
    }

    override fun onDestroy() {
        isRunning = false
        // 注销无障碍设置观察者：服务销毁通常是解绑/退出管控，继续监听会在无写入通道的
        // 设备上空转，且 HandlerThread 随服务反复重建会泄漏。进程死则随进程回收，此处是
        // 正常销毁路径的显式清理。
        runCatching { a11yCapabilityHealer.stopWatching() }
            .onFailure { Logger.w(TAG, it) { "stop a11y settings watcher failed" } }
        // AB 锁兜底解绑：正常解绑流程已在 observeBinding / unbindDevice 里 releaseAll，
        // 但服务也可能因系统回收 / 外部 stopService 而非解绑流程销毁。绑定不解除时
        // 目标服务（尤其 :daemon）无法被销毁，留下杀不掉的僵尸服务与常驻通知。
        // 计数化 release 是幂等的：正常流程已解过时这里只是空转。
        GuardLink.releaseAll(this)
        runCatching { unregisterReceiver(restartReceiver) }
            .onFailure { Logger.w(TAG) { "unregister restart receiver failed: ${it.message}" } }
        // 第八轮评估 P1-E：应用安装/卸载广播接收器此前只注册不注销，
        // 服务反复销毁重建时每次都会挂一个新的 receiver 到系统上（旧实例连同
        // 其引用的 Service context 一起泄漏），且旧 receiver 仍会置 appSyncRequested。
        appChangeReceiver?.let { r ->
            runCatching { unregisterReceiver(r) }
                .onFailure { Logger.w(TAG) { "unregister app-change receiver failed: ${it.message}" } }
        }
        appChangeReceiver = null
        adminChangeReceiver?.let { r ->
            runCatching { unregisterReceiver(r) }
                .onFailure { Logger.w(TAG) { "unregister admin-change receiver failed: ${it.message}" } }
        }
        adminChangeReceiver = null
        Logger.w(TAG) { "GuardService destroyed" }
        // 周期定位必须跟着守护服务一起停：服务停掉通常意味着解绑 / 退出管控，
        // 此时继续采集位置既没有上报对象，也越过"家长未授权即采集"的边界。
        locationAutoReporter.stop()
        // 滤镜必须跟着服务一起撤：服务销毁意味着退出管控，
        // 留一层黄蒙蒙的遮罩在孩子屏幕上，家长会以为平板坏了。
        eyeProtectionOverlay.remove()
        // 水印与「计时开始」浮层同理：早期只有策略关闭/指令才会收水印、
        // 浮层的 dispose() 全工程零调用，解绑或服务销毁后水印文字继续挂在
        // WindowManager 上，浮层按钮还指向已 cancel 的 scope（点了没反应），
        // 孩子只能重启设备才能清掉。这里统一随服务销毁撤收。
        runCatching { watermark.hide() }
            .onFailure { Logger.w(TAG) { "hide watermark on destroy failed: ${it.message}" } }
        runCatching { usageStartOverlay.dispose() }
            .onFailure { Logger.w(TAG) { "dispose usage overlay on destroy failed: ${it.message}" } }
        // 玻璃球同样随守护服务撤收：服务销毁即退出管控，不应再留悬浮入口。
        glassBall.dispose()
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
        // 普通通知（屏幕采集引导 id=1003）**不随进程死亡消失**：上一次进程留下的
        // "请确认屏幕采集授权"会一直挂在通知栏，哪怕新进程里授权已经办妥、令牌已就位。
        // 开机 / 服务重建时先撤掉；此刻真缺凭据的话，后续采集指令会重新发一条
        // （新进程里 60s 节流高水位已复位，不会把该有的引导一起吞掉）。
        runCatching { CaptureConsentPrompter.clear(this) }
        // 自愈补授必须放在最前：通知权限一旦缺失，屏幕采集的引导通知孩子收不到，
        // 实时看屏就会永久卡在"连接中"，且平板上毫无提示。
        scope.launch { runSafely("ensurePermissions") { permissionGranter.ensureGranted() } }
        // 权限掉失自检：国产 ROM 在系统设置里切换"后台弹出界面/悬浮窗"等权限时会强制重启本应用，
        // 重启后无障碍服务绑定被解除、MediaProjection 录屏授权被清空——表现为"权限全部被取消"。
        // 启动即核对，掉了就 DO 静默重治愈、非 DO 诚实告警并引导重新授权，避免静默处于管控残缺态。
        scope.launch { runSafely("permissionHealth") { permissionHealthChecker.check() } }
        // 授权回退自愈：装机授权完成后仍全量复检（设备管理员 / 运行时 / 全部特殊权限）。
        // 缺失则 DO 静默补回能补的，其余与非 DO 缺失项把家长拉回引导流程重新勾选，
        // 落实"授权不会自动丢失"。
        scope.launch { runSafely("authReconcile") { authorizationKeeper.reconcile() } }
        // 启动即做一轮 DO 静默重授权：进程被"划任务/清理工具"杀掉、再由守护进程拉回这一窗口里，
        // 国产 ROM 常会静默回收权限（尤其切换权限后强制重启应用）。把原本每 5 分钟一次的
        // heal 前置到启动即跑，让"权限失效自动恢复"在重启后零等待生效，落实"权限永远可用"。
        // 二者均带 guardMasterSwitch/绑定态守卫，幂等且对未绑定/非 DO 设备无副作用。
        scope.launch { runSafely("permHealNow") {
            val healed = permissionHealthChecker.healRuntimePermissions()
            if (healed > 0) permissionVersion.incrementAndGet()
        } }
        scope.launch { runSafely("capHealNow") { permissionHealthChecker.recheckSpecialCapabilities() } }
        // 权限钉死恢复：已确立无障碍采集授权（系统级持久授权镜像）则开机即静默重绑服务，
        // 落实"授权一次、除解绑外永不失效"；best-effort 恢复持久化投影凭据，进程内复用零弹窗
        // （IBinder 失效由 obtainProjection 的 try/catch 安全兜底，退回无障碍主路）。
        // 2026-11-17 起：静默写入通道不只是 DO —— 装机时 adb 授权 WRITE_SECURE_SETTINGS 的
        // 非 DO 设备同样能自愈（EMUI 一键清理后无需家长到场），因此门改为 canSilentHeal。
        scope.launch { runSafely("consentStoreRestore") {
            if (captureConsentStore.isA11yGranted() && a11yCapabilityHealer.canSilentHeal()) {
                a11yCapabilityHealer.ensureEnabled()
            }
            captureConsentStore.loadProjectionConsent()?.let { (code, data) ->
                val mgr = getSystemService(MediaProjectionManager::class.java)
                if (mgr != null) ScreenCaptureSession.storeToken(mgr, code, data)
            }
        } }
        // 卸载封锁前置到启动即应用：不依赖策略下发/服务端连通。DO/PO 形态下系统级封死自身卸载，
        // 杜绝"长按图标即可直接卸载、管控系统当场失效"——这是治本手段，先于任何密码门生效。
        // 非 DO 设备 can(BLOCK_UNINSTALL)=false，此处为 no-op，无副作用；幂等，重复调用安全。
        scope.launch { runSafely("uninstallBlock") { uninstallDefense.applyUninstallBlockIfSupported() } }
        // 周期自检：见下方 loadCredentials 协程内的启动点 —— 授权回退对账会发起服务端请求，
        // 必须等凭据加载完成后再启动（此处曾与 678 行各起一份，导致两个 30min 对账循环并行）。
        // 周期性位置上报：策略里 locationIntervalSec 的落地执行者（策略为 0 时自动不采）。
        // 放在这里而不是某个界面：守护服务是唯一长期存活的宿主，
        // 放在界面里孩子一按 Home 就断，轨迹数据会大面积缺失。
        scope.launch { runSafely("startLocationReporter") {
            // 协议硬闸门：监护人未明示同意前不采位置。首帧读不到 DataStore 时按未同意处理；
            // 之后由 observeBinding 的开闸分支补启（start 幂等），不会因首帧竞态永久漏采。
            if (authRepository.isAgreementAccepted.first()) locationAutoReporter.start()
        } }
        scope.launch {
            runSafely("applyCachedPolicy") {
                // 未绑定时不落地本地缓存策略：没有家长就没有管控依据，
                // 否则服务端清库后，孩子端会靠陈旧缓存继续锁屏，形成"家长看不到设备、平板锁死"的死锁。
                // 协议硬闸门：未绑定、或监护人未明示同意当前版本协议，都不落地缓存策略。
                // 否则在协议弹窗尚未确认时就会按旧策略锁屏/限额，构成"先管控、后同意"。
                val boundAtStart = authRepository.isBound.first()
                val agreedAtStart = authRepository.isAgreementAccepted.first()
                if (!boundAtStart || !agreedAtStart) {
                    Logger.w(TAG) {
                        "gate closed on startup (bound=$boundAtStart, agreement=$agreedAtStart): " +
                            "skip cached policy, keep device unlocked"
                    }
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
                reconcileWatermark()
                reconcileWebGuard()
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
            // 授权回退周期自检：独立协程跑，不阻塞后续解锁自愈
            scope.launch { runSafely("authReconcileLoop") { authReconcileLoop() } }
            // 解锁自愈：进程刚启动就可能带着"待撤锁"遗留（上次解锁时进程死掉 /
            // FSI 与直接启动都被 ROM 吞掉）。家长已解锁的意图不允许因为进程重启而丢失 ——
            // 此刻追一次（内部限频 + 幂等），剩下的交给看门狗与周期自检。
            runSafely("chasePendingUnlock") {
                if (!lockController.state.value.locked) {
                    LockScreenActivity.chaseUnlock(this@GuardService)
                }
            }
            // 看屏会话恢复：进程被杀前家长正在观看（live_view 窗口仍有效）→ 主动恢复推流，
            // 不等服务端重发（那是 8s 冷却后的第二保险）。无障碍优先，未就绪时
            // a11yLiveStreamer 内部会等待系统重绑（最长 60s）再自动开始，超时才走投影兜底。
            runSafely("resumeLiveView") { resumeLiveViewIfActive() }
        }
    }

    /**
     * 进程重启后恢复中断的实时看屏。
     *
     * ## 为什么需要主动恢复
     * 服务端 `MonitorService.reconcileLiveSessions` 虽会在家长观看期间重发
     * LIVE_VIEW_START，但有 8s 冷却、且依赖家长端仍在续期；孩子端若能自己识别
     * "死前我正在推流"，就能在进程重启后秒级接回，家长端画面不再苦等。
     *
     * ## 通道选择与既有语义一致
     * 1. 投影令牌还在（进程未被杀的边缘情况）→ 投影推流；
     * 2. 否则走无障碍通道 —— 授权持久有效、零弹窗，正是"杀进程重开仍自动恢复看屏"的关键；
     * 3. 无障碍未就绪 → [AccessibilityLiveStreamer] 挂起等待系统重绑，超时才回调投影兜底
     *    （onA11yUnavailable 已在 onCreate 挂接，走通知引导，不静默失败）。
     */
    private suspend fun resumeLiveViewIfActive() {
        val live = GuardHeartbeat.readLiveView(this) ?: return
        Logger.i(TAG) { "live view session still active (hd=${live.highDefinition}), resuming" }
        // 与 StartLiveView 同口径：API 30+ 一律交给无障碍通道（它自己会等系统重绑、
        // 超时才回调一次投影兜底），不能用瞬时 isAvailable() 抢先降级到授权引导。
        // API < 30 没有 takeScreenshot，只能走投影常驻会话复用。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            a11yLiveStreamer.start(live.highDefinition)
        } else {
            dispatchCapture(ScreenCaptureService.liveStreamIntent(this, live.highDefinition))
        }
    }

    /**
     * 授权回退周期自检。
     *
     * 守护服务是唯一长期存活的宿主，孩子一按 Home 界面侧的复检就失效；
     * 这里用一条独立循环每 [REAUTH_CHECK_INTERVAL_MS] 全量核对一次授权，
     * 发现掉失即交给 [AuthorizationKeeper] 分级处理（DO 静默补 / 非 DO 拉回引导）。
     * 仅在 [scope] 存活期间运行，服务销毁随 [scope.cancel] 一并停掉，不泄漏。
     */
    private suspend fun authReconcileLoop() {
        while (scope.isActive) {
            delay(REAUTH_CHECK_INTERVAL_MS)
            runSafely("authReconcileTick") { authorizationKeeper.reconcile() }
        }
    }

    /**
     * 计时开始确认浮层观察者。
     *
     * ## 修复背景
     * 原「计时开始」确认框只渲染在孩子端**前台 App UI**（UsageSessionHost），而孩子端日常以
     * **后台保活**形态运行、App 不在前台，确认框永远不可见，于是「第二天亮屏无任何提示开始计时、
     * 计时始终不启动」。这里改为：needsStartConfirm 为真且设备已绑定、屏幕亮着时，用系统浮层
     * （TYPE_APPLICATION_OVERLAY，与玻璃球同源）弹出确认框，孩子无论停留哪个 App 都能看到并确认，
     * 计时才会真正开始。确认成功后会话转 active、needsStartConfirm 翻转，浮层自动收起。
     *
     * ## 2026-11-09：删除 3 分钟自动确认
     * 原实现「超时未确认即自动 confirmStart()」与产品口径「未确认则不开始计时」直接冲突：
     * 不点确认就能白拿一天额度之外，自动起表还把"确认"这个仪式从孩子手里拿走了。
     * 现在超时分支只保留提醒语义（浮层常驻、10s 心跳重评估），永不自动起表；
     * 未确认期间由 PolicyEngine 挂 AWAITING_START 等待页兜住"不确认就无限用"的绕过面。
     */
    private fun observeUsageStart() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        fun evaluate() {
            val s = usageSessionManager.state.value
            // 只在"已解绑 / 不需要确认（已确认或家长关了时间管控）"时收起，
            // **不能**因为息屏就收起：孩子每 3 分钟按一次电源键，就能把这条唯一的提示无限推后。
            if (!boundNow.get() || !s.needsStartConfirm) {
                usageStartOverlay.hide()
                return
            }
            // 息屏时系统本就不会展示浮层。
            if (pm?.isInteractive == true) {
                usageStartOverlay.show(
                    confirmAction = { scope.launch { usageSessionManager.confirmStart() } }
                )
            } else {
                usageStartOverlay.hide()
            }
        }
        usageStartJob?.cancel()
        usageStartJob = scope.launch {
            // 与全文件一致：collect / 心跳兜底异常不得静默杀死这条状态跟随协程，
            // 否则「开始计时」确认浮层会对孩子操作不再响应（与类注释「任一条协程不得静默死掉」一致）。
            launch { runSafely("usageStartObserve") { usageSessionManager.state.collect { evaluate() } } }
            // 状态流只在状态**变化**时发射：孩子一直不点确认、状态也不再变时，
            // 光靠 collect 等不到亮屏后的重新展示。低频心跳兜底重评估（幂等）。
            launch {
                runSafely("usageStartTick") {
                    while (isActive) {
                        delay(USAGE_CONFIRM_TICK_MS)
                        evaluate()
                    }
                }
            }
        }
    }

    /**
     * 绑定 + 协议同意双闸门驱动通道启停（法务 v2.0 硬闸门）。
     *
     * 历史上这里只认 isBound：设备一绑上，MQTT、定位、策略落地、锁屏就全开，
     * 哪怕监护人从未对协议正文点过"同意"——与《隐私政策》"明示同意后方可启用守护能力"直接冲突。
     * 现在把 [AuthRepository.isAgreementAccepted] 并入同一个总闸，任一条件为假就走同一条
     * 停机路径（与解绑同构），采集与管控一律不跑。
     *
     * 之所以收敛到这一处、而不是在 20+ 个 GuardService.start 调用点各加判断：
     * 那些调用点多数由开机/闹钟/看门狗触发，进程刚起时 DataStore 还没读到，
     * 用同步快照判断会把已同意设备误判成未同意，直接造成守卫掉线；
     * 这里用 Flow 持续观察，数据恢复或监护人一旦确认就自动重新开闸。
     */
    private fun observeBinding() {
        bindingJob?.cancel()
        bindingJob = scope.launch {
            combine(
                authRepository.isBound.distinctUntilChanged(),
                authRepository.isAgreementAccepted.distinctUntilChanged()
            ) { bound, agreed -> bound to agreed }.collect { (bound, agreed) ->
                if (!guardActive(bound, agreed)) {
                    // 关键：没有绑定关系就绝不继续管控，并主动清空所有锁定原因。
                    // 绑定关系是服务端资产（除非家长手动解绑 / 服务端删除，否则一直存在），
                    // 但服务端清库或设备被解绑后，若本地仍按陈旧缓存策略执行，平板会陷入
                    // "家长端看不到设备、孩子端却锁死无法操作"的死锁 —— 而系统级 lockNow()
                    // 一旦落下，应用自身再也解不开（lockNow 只能锁、不能程序化解）。
                    // 先停采集再清状态：避免 releaseControl 期间还有 MQTT/定位在途数据落库，
                    // 那会让"未同意不采集"出现一段可见的尾巴。
                    if (transportStarted.compareAndSet(true, false)) {
                        runSafely("transportStopOnGateClosed") { remote.stop() }
                    }
                    locationAutoReporter.stop()
                    boundNow.set(false)
                    // 持久化绑定态：守护进程（:daemon）只在"已绑定"时才拉起主进程，
                    // 未绑定即停机不拉，防止解绑后反复重启打扰（与 boundNow 语义一致）。
                    // 注意 this@GuardService：collect  lambda 内 this 是 CoroutineScope。
                    GuardHeartbeat.writeGuardState(this@GuardService, false)
                    // AB 锁：解绑即解除双向绑定。绑定会让目标服务无法销毁（stopSelf 不生效），
                    // 不解绑就会留下两个杀不掉的服务 + 两条常驻通知 —— 那是隐私事故。
                    GuardLink.releaseAll(this@GuardService)
                    // 解绑即取消闹钟兜底排班，停止无谓唤醒
                    GuardAlarmReceiver.cancel(this@GuardService)
                    // 同样取消时段边界精确闹钟（R-P2-6）：解绑后没有策略依据，留着只会白唤醒
                    LockBoundaryAlarmReceiver.cancel(this@GuardService)
                    lastLockBoundaryAt = null
                    // 解绑即结束看屏会话恢复窗口，防止重新绑定后恢复陈旧推流
                    GuardHeartbeat.clearLiveView(this@GuardService)
                    lockController.releaseAll()
                    // 临时解锁必须一并作废：releaseAll() 刻意保留"仍在临时解锁中"的状态
                    // （远程解锁不该顺手把家长的放行取消掉），但解绑是换家庭的边界 ——
                    // 沿用上一家的 8 小时放行，会让新家长的时段锁/远程锁在接下来几小时内
                    // 被静默抑制（requestLock 走 "suppressed by temp unlock" 分支），
                    // 表现为"刚绑上，锁屏命令点了没反应"，日志里只有一行 debug。
                    lockController.cancelTempUnlock()
                    // 解绑即清空会话状态，避免陈旧会话继续驱动 UI 与锁定
                    usageSessionManager.reset()
                    // ⚠️ releaseControl 只能在**真解绑**时执行：它会 relinquish Device Owner /
                    // 设备管理员，而 DO 一经 relinquish 通常必须恢复出厂才能重新预置。
                    // 协议升版导致的"暂未重新同意"不是解绑；若照搬解绑路径，
                    // 一次文案更新就会把硬管控设备永久降级、且再也无法静默自愈权限——
                    // 这比暂时停止采集严重得多。未同意时只停数据面与锁屏，保留设备身份。
                    // 幂等：未持有对应权限的步骤被 guarded 静默收口为 Unsupported，不会抛异常。
                    if (shouldReleaseControl(bound)) {
                        // 解绑后彻底释放系统级管控（挂起/隐藏应用、相机/截屏、用户限制、
                        // 自动对时、自身卸载封锁，最后 relinquish Device Owner / 设备管理员），
                        // 让本机恢复为普通应用、可被家长端或用户在设置里正常卸载。
                        // 同时兜底"历史上已解绑却仍残留 DO/策略"的设备 —— 新版本一启动
                        // （或开机自启）即自动治愈，无需重新绑定再解绑。
                        runCatching { admin.releaseControl() }
                            .onSuccess { Logger.i(TAG) { "control released on unbound observe (${it.size} ops)" } }
                            .onFailure { t -> Logger.e(TAG, t) { "releaseControl failed on unbound observe" } }
                        runSafely("hideBallOnUnbind") { glassBall.hide() }
                    }
                    Logger.w(TAG) {
                        "guard gate closed (bound=$bound, agreementAccepted=$agreed): " +
                            "collection + enforcement stopped; " +
                            if (bound) "device control privileges kept for re-consent"
                            else "device control released"
                    }
                    // 两种关闭原因都要恢复管理控制台入口：
                    // - 解绑后便于重新绑定或正常卸载；
                    // - 仅协议未同意时，玻璃球仍显示但必须能从桌面重新打开 App 完成明示同意，
                    //   否则设备会永久脱管且家长没有重新确认的入口。
                    runSafely("revealOnGateClosed") { appHider.revealManagementUi() }
                    return@collect
                }
                // 绑定与明示同意同时成立，才允许开闸。协议刚被确认/升版重新同意时，
                // bootstrap 里的那次一次性启动早已跑完，这里必须补落地缓存策略，
                // 否则家长端会看到"设备在线但管控规则还是空的"。
                boundNow.set(true)
                runSafely("policyOnGateOpen") { policyEngine.applyCurrent(force = true) }
                // 持久化绑定态并排上闹钟兜底：守护进程据此决定"主进程死了要不要拉"，
                // 闹钟通道与 JobScheduler 看门狗并行，互为 ROM 限制下的备份。
                // 注意 this@GuardService：collect lambda 内 this 是 CoroutineScope。
                GuardHeartbeat.writeGuardState(this@GuardService, true)
                GuardAlarmReceiver.schedule(this@GuardService)
                // AB 锁：绑定已建立，确保双向绑定就位（幂等）。此后任一方被杀，
                // 另一方都会在 binder 死亡回调里立即重绑并把对方进程创建回来。
                GuardLink.ensureDaemonBound(this@GuardService)
                startTransport()
                // 周期定位同样归总闸管：同意前不采，重新同意后由这里补启（start 幂等）。
                locationAutoReporter.start()
                // 绑定成功即拉取会话：让「计时开始」确认弹窗尽快出现（若今日尚未确认）
                runSafely("sessionSyncOnBind") { usageSessionManager.refresh() }
                // 绑定完成：隐藏管理控制台图标（孩子端隐形），仅以玻璃球向孩子展示
                // 已用/剩余等基本信息，并承载「双击申请延时」入口。
                // 隐藏态持久写入系统 PMS，重启/覆盖安装后仍生效；开机自启会再次确认。
                runSafely("hideOnBind") { appHider.hideManagementUi() }
                runSafely("showBallOnBind") { glassBall.show() }
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
                if (credentials.isBound) {
                    // HTTP 长轮询=服务端在线判定的唯一存活信号，必须持续发送，与 MQTT 连接态解耦：
                    // 旧逻辑用 online(MQTT 态)门控，MQTT 一抖就停掉它，是"一会儿在线一会儿掉线"的根因。
                    // 仅当 HTTPS 确不可达时放慢节奏(原 offline 分支的退避意图)，绝不因 MQTT 抖动而停摆。
                    runSafely("drainPendingCommands") { drainPendingCommands() }
                    delay(if (httpReachable) COMMAND_PULL_INTERVAL_MS else COMMAND_PULL_IDLE_MS)
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
            var nextPermHeal = 0L
            // 自身常驻加固周期自愈：已绑定时把 DO/PO 下的系统级限制重新压回，
            // 对抗部分 ROM 在重启/省电策略下悄悄重置用户限制。
            var nextSelfProtect = 0L
            // 卸载防护就绪度自检：把"卸载到底需不需要密码"周期算一遍，
            // 三层（DO / 设备管理员 / 无障碍+密码）全空时报高危告警并通知栏明示。
            // 首次立刻做：设备刚装好、孩子还没动手之前就该知道防护有没有生效。
            var nextShieldCheck = 0L
            // A1-P0：Web 过滤隧道看门狗。策略要求过滤但隧道没在跑时，每 30s 尝试重建，
            // 起不来（如后台 FGS 限制）则按节流上报 WEB_GUARD_DOWN，让家长第一时间知情。
            // 比 5 分钟策略自检更密，是为了把"隧道静默失效"的空窗从 ≤5min 压到 ≤30s。
            var nextWebGuardWatchdog = 0L
            // 看屏中无障碍主路看门狗：会话活跃时高频静默核验，EMUI 杀服务即秒级自愈，
            // 从源头阻止"权限丢失 → 落回投影授权框"。无看屏会话时不触发，零额外开销。
            var nextA11yLiveHeal = 0L
            var nextLogUpload = SystemClock.elapsedRealtime() + LOG_UPLOAD_MIN_INTERVAL_MS
            // 首次台账同步刻意提前：设备刚绑定/服务刚启动时家长往往正盯着应用列表，
            // 等 30 分钟会让「应用」Tab 一直空白；首报完成后回到正常周期。
            var nextAppSync = SystemClock.elapsedRealtime() + APP_SYNC_FIRST_DELAY_MS

            // 使用情况实时同步：独立于日志上报节奏，保证家长端用量数据近实时刷新
            var nextUsageSync = SystemClock.elapsedRealtime() + USAGE_SYNC_FIRST_DELAY_MS

            // 使用计时会话同步（三端一致计时）：拉取服务端权威剩余，驱动「计时开始」确认弹窗
            // 与屏幕正上方 10 分钟倒计时浮层；节奏与采样对齐即可，无需更密。
            var nextSessionSync = SystemClock.elapsedRealtime() + SESSION_SYNC_FIRST_DELAY_MS

            // 受信时间护栏快照落盘：首次立刻写（覆盖旧版单值盘），之后每分钟一次。
            // 刻意与心跳解耦——完全离线运行时也必须持续刷新，否则恢复基线会严重滞后。
            var nextTimePersist = 0L

            // daemon 心跳连续陈旧拍数：binder 还连着但 daemon 内部循环卡死时
            // ensureDaemonBound 会命中「已连接」只 ping，ping 无效必须补 start。
            var daemonStaleStreak = 0
            var lastDaemonStartAtMs = 0L

            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                // 主进程心跳：守护进程（:daemon）与闹钟/看门狗据此判断本进程死活。
                // 放在循环体首行：即使后面某个子任务抛异常（已被 runSafely 收敛），
                // 心跳依然最新 —— 绝不会出现"服务卡死但心跳新鲜"的假活状态被漏掉。
                // 注意 this@GuardService：launch lambda 内 this 是 CoroutineScope。
                GuardHeartbeat.writeGuardAlive(this@GuardService)
                // 双进程互保的另一半：守护进程心跳陈旧 → 就地拉起（本进程是 FGS 上下文，
                // Android 12+ 下启动服务不受后台限制）。
                val daemonAge = GuardHeartbeat.readDaemonAliveAgeMs(this@GuardService)
                if (daemonAge == null || daemonAge > GuardHeartbeat.DAEMON_STALE_MS) {
                    daemonStaleStreak++
                    Logger.w(TAG) { "daemon heartbeat stale (age=$daemonAge, streak=$daemonStaleStreak) -> restarting" }
                    // 先走 binder 绑定（不受后台启动 FGS 限制，且会重建 :daemon 进程）；
                    // 已连接分支会 pingDaemon（对端 MSG_WAKE 重启监视循环）。
                    // 进程在、binder 在、但内部循环卡死时 ping 可能无效 ——
                    // 连续 2 拍心跳仍陈旧就补一次 start（半活态的唯一兜底）。
                    val bound = GuardLink.ensureDaemonBound(this@GuardService)
                    if ((!bound || daemonStaleStreak >= 2) && now - lastDaemonStartAtMs >= 20_000L) {
                        lastDaemonStartAtMs = now
                        GuardDaemonService.start(this@GuardService)
                    }
                } else {
                    daemonStaleStreak = 0
                }
                // 系统调度通道自愈（2026-10-21 华为实测补充）：EMUI 等激进 ROM 会在
                // 杀应用时一并清除已登记的 JobScheduler 任务与 AlarmManager 闹钟
                // （实测整夜无人拉起：Job 无登记、闹钟被清，两条系统通道同时消失）。
                // 应用存活期间每 tick 顺手补登记：Job 用 getPendingJob 探测缺失，
                // 闹钟用同一 PendingIntent 重复 setWindow 即替换（幂等续期）。
                // 仅在已绑定态做——未绑定设备没有管控对象，不空耗系统唤醒。
                if (boundNow.get()) {
                    if (!GuardWatchdogService.isScheduled(this@GuardService)) {
                        Logger.w(TAG) { "watchdog job missing (ROM evicted?) -> rescheduling" }
                        runCatching { GuardWatchdogService.schedule(this@GuardService) }
                    }
                    GuardAlarmReceiver.schedule(this@GuardService)
                }
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
                    // R-P2-6：排一次时段边界精确闹钟。Doze 下 delay() 不唤醒 CPU，
                    // 只靠轮询会把"到点锁机/解锁"拖到下一次系统唤醒。
                    runSafely("lockBoundaryAlarm") { scheduleLockBoundaryAlarm() }
                }

                // ---------- 心跳 ----------
                if (now >= nextHeartbeat) {
                    runSafely("heartbeat") { sendHeartbeat() }
                    nextHeartbeat = now + monitoring.heartbeatIntervalMs.coerceAtLeast(MIN_TICK_MS)
                }

                // ---------- 受信时间护栏快照（本地，不依赖网络） ----------
                // 三元组（受信时刻 / 单调读数 / 裸墙钟）必须同一瞬间由 exportTrustedBaseline
                // 一次性取出，恢复时才能算出"从落盘到现在真实流逝了多久"。
                // 进程被杀 / 设备重启后，光把受信时刻设回锚点会把时间冻结在关机那一刻，
                // 宵禁、日切、每日额度会全部按错误时刻判定，且联网对时之前不会自愈。
                if (now >= nextTimePersist) {
                    runSafely("timePersist") {
                        TrustedTimePrefs.saveIfChanged(
                            this@GuardService,
                            timeProvider.exportTrustedBaseline(),
                        )
                    }
                    nextTimePersist = now + TIME_PERSIST_INTERVAL_MS
                }

                // ---------- 策略自检重压 ----------
                if (now >= nextSelfCheck) {
                    // force = true：这里的目的就是把"被用户绕过去的限制"重新压回来，
                    // 版本没变也必须重下，否则自检等于空转。
                    if (boundNow.get()) {
                        runSafely("selfCheck") { policyEngine.applyCurrent(force = true); reconcileWatermark(); reconcileWebGuard() }
                    }
                    nextSelfCheck = now + SELF_CHECK_INTERVAL_MS
                }

                // ---------- A1-P0：Web 过滤隧道看门狗（30s） ----------
                // 策略要求过滤时若隧道没在跑，尝试重建；起不来则节流上报 WEB_GUARD_DOWN。
                // 仅已绑定时做（未绑定设备无管控对象）。与 5 分钟 reconcileWebGuard 互补：
                // 后者还负责"策略关掉→停隧道/解 always-on"，本看门狗只管"该跑却没跑"。
                if (boundNow.get() && now >= nextWebGuardWatchdog) {
                    runSafely("webGuardWatchdog") { watchdogWebGuard() }
                    nextWebGuardWatchdog = now + WEB_GUARD_WATCHDOG_MS
                }

                // ---------- 篡改自检 ----------
                if (now >= nextTamperScan) {
                    runSafely("tamperScan") { policyEngine.scanTamper() }
                    nextTamperScan = now + TAMPER_SCAN_INTERVAL_MS
                }

                // ---------- 运行期权限自愈（DO 形态无感恢复被系统回收的危险权限） ----------
                if (now >= nextPermHeal) {
                    runSafely("permHeal") {
                        val healed = permissionHealthChecker.healRuntimePermissions()
                        if (healed > 0) permissionVersion.incrementAndGet()
                    }
                    runSafely("capHeal") { permissionHealthChecker.recheckSpecialCapabilities() }
                    // a11y 观察期提速（2026-11-16 EMUI 一键清理事故复盘）：
                    // 「未启用 90s 观察窗 / 开着却没连上 90s(非DO) 观察窗」都要"持续"跨窗
                    // 才确认，若维持 5 分钟周期，观察窗起算到确认最坏要等满整个周期，
                    // 家长端告警与设备端引导页被无谓拖到 ~6.5 分钟之后。观察期内切到
                    // 2 分钟快检，让确认落在下一拍；恢复健康后自然回 5 分钟常规节奏。
                    nextPermHeal = now + if (permissionHealthChecker.isA11yObservationActive()) {
                        CAP_HEAL_FAST_INTERVAL_MS
                    } else {
                        PERM_HEAL_INTERVAL_MS
                    }
                }

                // ---------- 自身常驻加固周期自愈 ----------
                // 仅已绑定时重压：把 DO/PO 下"强行停止置灰 / 卸载封锁 / 防恢复出厂"等系统级限制
                // 重新落地，对抗部分 ROM 在重启 / 省电策略下悄悄重置用户限制。
                // 未绑定时不重压——releaseControl() 已将这些限制解除，设备需恢复普通可用状态。
                if (boundNow.get() && now >= nextSelfProtect) {
                    runSafely("selfProtect") { selfProtection.applyBaseline() }
                    nextSelfProtect = now + SELF_PROTECT_INTERVAL_MS
                }

                // ---------- 「卸载需密码」就绪度自检 ----------
                // 2026-11-02 事故：DO 没有、设备管理员没激活、无障碍没连上，三条通道全空，
                // 孩子长按桌面图标一次就把管控删掉，而家长端/孩子端没有任何一处显示"防护为 0"。
                // 这里周期算一次真实防护强度：弱到需要家长介入时上报告警 + 置位重新授权，
                // 并驱动通知栏文案（见 buildNotification）。只有已绑定时才有意义。
                if (boundNow.get() && now >= nextShieldCheck) {
                    runSafely("uninstallShield") { uninstallDefense.checkShield() }
                    nextShieldCheck = now + UNINSTALL_SHIELD_CHECK_INTERVAL_MS
                    updateNotification()
                }

                // ---------- 看屏中无障碍主路看门狗（权限钉死的根因预防） ----------
                // 仅当存在活跃看屏会话时高频核验：EMUI 杀掉无障碍服务会令 isAvailable() 翻否，
                // 若不抢先静默重绑，下一次取帧/再进入就会落回投影授权框。兜底式自愈，无会话零开销。
                if (now >= nextA11yLiveHeal) {
                    nextA11yLiveHeal = now + A11Y_LIVE_HEAL_INTERVAL_MS
                    runSafely("a11yLiveHeal") {
                        val live = runCatching { GuardHeartbeat.readLiveView(applicationContext) }.getOrNull()
                        if (live != null && System.currentTimeMillis() <= live.deadlineWallMs) {
                            if (!accessibilityScreenshot.isAvailable()) {
                                // 有静默写通道的设备（DO 或装机已授权 WRITE_SECURE_SETTINGS）都在这里
                                // 抢先静默补/重绑，让看屏不落回投影授权框。2026-11-17 修正：旧实现只认
                                // DO（admin.isDeviceOwner()），导致目标华为平板这类「非 DO + 已授权」
                                // 设备明明能自愈却在这里空等 5 分钟自检，看屏被硬生生打断。
                                // 用 canSilentHeal() 闸住还有个好处：无通道设备本就无能为力，避免这两个
                                // 调用每 10 秒刷一条"无法自动开启"日志淹掉有用信息；其缺失由
                                // PermissionHealthChecker 按周期诚实上报（看屏期间已提速到 2 分钟）。
                                if (a11yCapabilityHealer.canSilentHeal()) {
                                    a11yCapabilityHealer.ensureEnabled()
                                    a11yCapabilityHealer.healIfNeeded()
                                }
                            }
                        }
                    }
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

                // 这两句原本游离在 runSafely 之外：一旦抛非取消异常，仅 mainLoop 协程异常结束，
                // 守护核心调度（采样/心跳/自检/日切/边界闹钟续排）整体停摆，要等看门狗 ~40s 才拉回。
                // 包进 runSafely，任何异常只丢一拍而非整轮循环。
                runSafely("notify") { updateNotification() }
                // 等下一轮：正常 15 秒；期间若有应用切到前台（a11y 发来唤醒信号）
                // 就立刻开下一轮 —— 「家长已限额、孩子却还能玩 15 秒」的窗口就此收掉。
                // 必须带超时：漏信号只会退化成原来的 15 秒节奏，绝不能让主循环永久挂起。
                runSafely("awaitTick") { tickSignal.awaitWithin(MIN_TICK_MS) }
            }
        }
    }

    /**
     * 排一次"时段边界"精确闹钟（R-P2-6）。
     *
     * 只在边界时刻**变化**时才重排：本方法每轮 tick（15s）都会调用，
     * 无脑重排等于每 15 秒取消+重设一次系统闹钟，既费电也会让日志失去意义。
     *
     * 无边界（未启用时段管控 / 没有规则）时取消已排的闹钟，
     * 避免策略改完后残留一个"永远不触发意义"的唤醒点。
     *
     * ## 时钟域（第九轮修正）
     * `nextScheduleTransition()` 返回的是**受信时间轴**上的绝对 UTC 毫秒，
     * 早期直接把它交给 `RTC_WAKEUP`（裸墙钟）排班：改系统时间时两者分裂 ——
     * 回拨让闹钟晚到几小时（Doze 下锁机被推迟），前拨则让边界变成"过去时刻"
     * 立即触发、一次性闹钟被消耗，而 [lastLockBoundaryAt] 仍记着该边界不再重排，
     * 真实到点反而没有唤醒。现在改成把"距边界还有多久"搬到单调时钟轴，
     * 用 `ELAPSED_REALTIME_WAKEUP` 排班（换算见 [BoundaryAlarmClock]）。
     */
    private suspend fun scheduleLockBoundaryAlarm() {
        // 一次性闹钟已被消耗：即便边界值没变也必须重排。
        // 系统拒绝后台启动前台服务时进程根本没被拉起，只靠"边界变化"判断会永久漏排。
        if (LockBoundaryAlarmReceiver.consumeFired()) lastLockBoundaryAt = null

        // 三者取同一瞬间：next 用传入的 atMillis 计算，换算用的 trustedNow/elapsedNow
        // 必须与之配对，否则 delay 会带上读盘耗时。
        val trustedNow = timeProvider.now()
        val elapsedNow = SystemClock.elapsedRealtime()
        val computed = runCatching { policyEngine.nextScheduleTransition(trustedNow) }
        if (computed.isFailure) {
            // 读盘异常不能当成"无边界"：getOrNull() 会把它吞成 null 进而 cancel 掉
            // 已经排好的精确闹钟，离线 Doze 下直接失去准点锁机能力（fail-open）。
            // 与 AppGuardPolicyStore 的"读盘异常保留上次已知良好值"同口径。
            Logger.w(TAG, computed.exceptionOrNull()) { "next schedule transition unavailable, keep last alarm" }
            return
        }

        val next = computed.getOrNull()
        if (next == lastLockBoundaryAt) return
        lastLockBoundaryAt = next
        val trigger = BoundaryAlarmClock.triggerAtElapsed(next, trustedNow, elapsedNow)
        LockBoundaryAlarmReceiver.schedule(this, trigger)
        Logger.d(TAG) { "lock boundary alarm -> boundary=$next elapsedTrigger=$trigger" }
    }

    /** 已排的边界时刻（受信时间轴，null = 当前无边界）。用于避免每轮 tick 重复重排。 */
    @Volatile private var lastLockBoundaryAt: Long? = null

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

    /**
     * 设备管理员 / DO 身份变更监听。
     *
     * 覆盖两条系统广播：
     * - [DeviceAdminReceiver.ACTION_DEVICE_ADMIN_DISABLED]：用户在「设置 → 安全 → 设备管理员」
     *   里停用本应用（非 DO 设备上这是最常见的"解除管控"入口）；
     * - [ACTION_DEVICE_OWNER_CHANGED]：DO 身份被设置或被 `adb dpm remove-active-admin` 摘除。
     *
     * 收到即立刻跑一次 [UninstallDefenseModule.verifyLiveness]，把"管控已失效"变成家长端
     * 可见的高危告警 —— 而不是依赖下一轮策略拉取（断网设备上可能永远等不到）。
     */
    private fun registerAdminChangeReceiver() {
        if (adminChangeReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Logger.w(TAG) { "device admin state changed: ${intent?.action}" }
                // 控制权态可能刚降级：先刷新，后续策略重放才会按真实能力走
                runCatching { admin.refreshControlMode() }
                scope.launch {
                    runCatching { uninstallDefense.verifyLiveness() }
                        .onFailure { Logger.w(TAG) { "verifyLiveness after admin change failed: ${it.message}" } }
                }
            }
        }
        adminChangeReceiver = receiver
        runCatching {
            val filter = IntentFilter().apply {
                addAction(DeviceAdminReceiver.ACTION_DEVICE_ADMIN_DISABLED)
                addAction(ACTION_DEVICE_OWNER_CHANGED)
            }
            // 这两条是系统受保护广播，只能由系统发出；用 EXPORTED 注册以保证投递
            // （本接收器只读 action、不执行任何外部输入，不存在被伪造触发的攻击面）。
            ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        }.onFailure {
            adminChangeReceiver = null
            Logger.w(TAG) { "register admin-change receiver failed: ${it.message}" }
        }
    }

    /** 注册屏幕亮起 / 息屏 / 解锁 / 网络恢复的动态重启广播（见 [restartReceiver] 注释）。 */
    private fun registerRestartReceiver() {        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            }
            ContextCompat.registerReceiver(
                this, restartReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }.onFailure { Logger.w(TAG) { "register restart receiver failed: ${it.message}" } }
    }

    /**
     * 护眼强制休息时长上报队列：休息结束后产生的 (restId, 实际休息毫秒) 入队，
     * 由 [flushEyeCareRestReports] 异步送达服务端，从每日总时长扣减（保证三端一致）。
     *
     * 之所以要队列而非"失败即丢弃"：休息结束那一刻设备可能刚好离线（息屏后网络回收），
     * 直接丢弃会让该次休息被误计入额度。队列在后续每次 doTick 重试，离线恢复后即补齐。
     * 上限保护防止异常情况下无限堆积。
     */
    // R-P2-9：护眼休息上报由内存队列改为磁盘落盘队列（仿 AckOutbox），
    // 进程被杀/崩溃后重启仍能补报，闭合离线红线（休息时长扣减三端一致）。
    // 用 lazy：Service 构造期 filesDir 尚未就绪（mBase 为 null），首访发生在 doTick，早已过 onCreate。
    private val eyeCareRestOutbox by lazy {
        EyeCareRestOutbox(
            File(filesDir, "padguard_eyecare_rest_outbox.jsonl"),
            Json { ignoreUnknownKeys = true },
            MAX_EYE_CARE_REST_REPORTS
        )
    }

    /**
     * 把待上报的护眼休息事件送达服务端。
     *
     * 成功即出队；业务校验失败（restId 重复/非法）直接丢弃避免死循环；
     * 网络/解析失败保留待下次重试。入参均来自引擎侧已校验过的值，这里只做传输。
     */
    private suspend fun flushEyeCareRestReports() {
        if (!credentials.isBound) return
        if (authRepository.getDeviceId().isBlank()) return
        // R-P2-9：从磁盘队列取出待上报批次（不删除），逐条上报，
        // 成功 + 业务拒绝（无需重试）的条目上报后从磁盘移除；网络失败保留待下次重试。
        val batch = eyeCareRestOutbox.drain(MAX_EYE_CARE_REST_REPORTS)
        if (batch.isEmpty()) return
        val done = mutableListOf<EyeCareRestOutbox.Entry>()
        for (entry in batch) {
            when (val r = remote.reportEyeCareRest(entry.restId, entry.restMs)) {
                is ApiResult.Success -> done.add(entry)
                is ApiResult.BizError -> {
                    // 服务端判定为非法/重复：无需重试，丢弃避免死循环
                    Logger.w(TAG) { "eye-care rest report rejected (drop): ${r.code} ${r.message}" }
                    done.add(entry)
                }
                is ApiResult.Failure -> {
                    // 网络/解析失败：保留，下次 tick 重试
                    Logger.w(TAG) { "eye-care rest report failed (retry later): ${r.message}" }
                }
            }
        }
        eyeCareRestOutbox.remove(done)
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
        // 先记装卸事件再上传：事件走本地告警表（离线也会留存，联网后随批量上报补发），
        // 不依赖本次上传是否成功 —— 否则断网期间装的应用事后永远查不到（R-P3-10）。
        recordInventoryChanges(apps, deviceId)
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
     * 把台账差分结果落成告警事件（R-P3-10）。
     *
     * ## 为什么走 [LogRepository.raise] 而不是直接推网络
     * 它写的是本地告警表，由既有的批量上报通道在联网时补发 ——
     * 断网期间孩子装的应用，家长事后仍然看得到（离线红线同样适用于"可观测性"）。
     *
     * ## 为什么要限流
     * 换机迁移 / 批量恢复 / 首次装一堆学习软件时，一次可能多出几十个应用；
     * 若全量推成告警，家长端的消息流会被瞬间刷爆，反而把真正重要的告警挤掉。
     * 超出部分只留日志，不静默丢数据（台账本身仍会全量同步）。
     */
    private suspend fun recordInventoryChanges(apps: List<AppInventoryItem>, deviceId: String) {
        // 事件上报经 onEvents 回调交给差分器：上报成功它才推进基线（R37，见
        // AppInventoryDeltaTracker.diff 的时序契约）；取消放行避免半途取消被当普通失败。
        try {
            val change = appInventoryDelta.diff(apps) { delta ->
                for (item in delta.added.take(MAX_INVENTORY_EVENTS_PER_CYCLE)) {
                    logRepository.raise(
                        type = RiskType.APP_INSTALLED,
                        level = RiskLevel.NORMAL,
                        detail = mapOf(
                            "packageName" to item.packageName,
                            "label" to (item.appName ?: item.packageName)
                        ),
                        deviceId = deviceId
                    )
                }
                for (pkg in delta.removed.take(MAX_INVENTORY_EVENTS_PER_CYCLE)) {
                    logRepository.raise(
                        type = RiskType.APP_UNINSTALLED,
                        level = RiskLevel.NORMAL,
                        detail = mapOf("packageName" to pkg, "label" to pkg),
                        deviceId = deviceId
                    )
                }
                val overflow = delta.added.size > MAX_INVENTORY_EVENTS_PER_CYCLE ||
                    delta.removed.size > MAX_INVENTORY_EVENTS_PER_CYCLE
                if (overflow) {
                    Logger.w(TAG) {
                        "inventory changed a lot (added=${delta.added.size}, removed=${delta.removed.size}), " +
                            "reported first $MAX_INVENTORY_EVENTS_PER_CYCLE only"
                    }
                } else if (delta.added.isNotEmpty() || delta.removed.isNotEmpty()) {
                    Logger.i(TAG) {
                        "inventory changed: +${delta.added.map { it.packageName }} -${delta.removed}"
                    }
                }
            }
            if (change.baselineOnly) {
                // 首次观测只建基线：此时"当前全部应用"都会被算成新装，出事件就是刷屏。
                Logger.i(TAG) { "inventory baseline established (${apps.size} apps, no events)" }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Logger.w(TAG, t) { "inventory delta failed" }
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
        val fgCandidates = if (collectForegroundApp) foregroundApp.currentAll() else emptyList()
        // R-P2-10：分屏时同时有两个应用在前台。若其中有一个是"该被拦的"
        // （黑名单 / 超额 / Kiosk 降级白名单外），必须把它当作本轮前台，
        // 否则另一半屏幕上的游戏可以一直玩下去（旧实现只认"最后被点过的那个"）。
        // 预判是只读的，真正的计时/会话推进仍只由下面这一次 tick 完成。
        val fg = if (fgCandidates.size > 1) {
            policyEngine.blockedForegroundAmong(fgCandidates) ?: fgCandidates.first()
        } else {
            fgCandidates.firstOrNull()
        }
        // 日切重置先行（本地独立生效、不等网络）：修「昨天用满额度锁屏 → 今天仍锁屏」——
        // 进程整夜存活跨零点时，内存里昨天的 active=true/remaining=0 会一直被
        // isExpired() 重派生成 DAILY_LIMIT；这里每 tick 用受信钟比对落盘 dayKey，
        // 跨日立即重置为待确认（下一条 awaitingStart 让等待页顶上）。
        usageSessionManager.checkDayRollover()
        // 日总时长是否已由服务端权威会话判定耗尽：三端一致的关键——
        // 本地不再按采样累计判 DAILY_TOTAL，改由服务端会话基线派生剩余。
        val sessionExpired = usageSessionManager.isExpired()
        // 新的一天尚未确认「开始计时」（已绑定 + 时间管控开启 + 会话未 active）：
        // 挂 AWAITING_START 等待页。未绑定时 isAwaitingStart 无意义（没有管控关系）。
        val awaitingStart = boundNow.get() && usageSessionManager.isAwaitingStart()
        val tick = policyEngine.tick(
            foregroundPackage = fg,
            screenOn = screenOn,
            dailyLimitExpired = sessionExpired,
            awaitingStart = awaitingStart,
            // 暂停期间设备必须不可操作；会话已落盘，重启后仍成立
            timerPaused = usageSessionManager.isTimerPaused()
        )

        // 护眼休息结束后，把实际休息时长上报服务端，从每日总时长扣减（保证三端一致）。
        // 即使本次上报时离线，也会进入本地队列在后续 tick 重试，避免该次休息被误计入额度。
        tick.pendingRestReport?.let { (restId, restMs) -> eyeCareRestOutbox.enqueue(restId, restMs) }
        flushEyeCareRestReports()

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
            },
            silentClose = tick.silentClose
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
     * 单应用拦截（限额超额 / 命中黑名单）。
     *
     * 只在"超额的那个应用正处于前台"时才弹拦截页 —— 后台超额没有打扰用户的必要。
     * 同一个包不重复弹，切到别的应用再切回来才会再弹一次。
     *
     * [silentClose] 为真（黑名单 [BlacklistAction.SILENT_CLOSE]）时**不弹拦截页**，
     * 而是静默退回桌面——孩子无感知地被关掉被管控应用，避免"弹个框反而教会孩子怎么绕"。
     */
    private fun reconcileAppBlock(
        blockedPackage: String?,
        foreground: String?,
        reason: String,
        silentClose: Boolean = false
    ) {
        if (blockedPackage == null) {
            // ⚠️ 自家 UI（拦截页/锁屏/消息页）处于前台时，ForegroundAppMonitor 上报的是自家包名，
            // 而判定链对自家包恒放行（无限额配置、不在黑名单、恒在 Kiosk 白名单）
            // → tick.blockedPackage 必为 null。这是探针伪影，不是"家长已放行"。
            // 若此时无条件 dismiss，拦截页会每轮 tick（≤15s）自毁一次、被拦应用随之回到前台可玩，
            // 形成"玩 15 秒弹一下"的闪烁循环（P0：限额/黑名单/Kiosk 降级三道拦截全被击穿）。
            // 正确语义：上一轮存在拦截目标且前台是自家包 → 维持现状，什么都不做；
            // 真正的"家长放行"由 EngineEffect.DismissLockScreen 分支主动收掉拦截页。
            if (foreground == packageName && lastBlockedPackage != null) return
            // 家长放行（或额度跨日重置）后必须主动收掉拦截页：
            // 它是盖在被拦应用之上的 Activity，不 finish 掉就一直停在那儿，
            // 孩子看到的是"同意了也没变化"。
            AppBlockActivity.dismiss(this)
            lastBlockedPackage = null
            return
        }
        if (blockedPackage != foreground) return
        // 同包去重必须同时确认"拦截页还在屏幕上"：
        // 孩子点「回桌面」会 finish 掉拦截页，但 lastBlockedPackage 仍是这个包；
        // 只要他在下一个 15s 采样点前重新点开应用，就会命中这里的相等判断并永久 return，
        // 此后每一轮 tick 都不再弹页 —— 限额/黑名单/Kiosk 降级三道拦截同时失效，
        // 且无障碍兜底不覆盖单应用限额与黑名单。改成"页面不在了就重弹"。
        if (blockedPackage == lastBlockedPackage && AppBlockActivity.isShowing()) return
        lastBlockedPackage = blockedPackage
        if (silentClose) {
            sendToHome()
        } else {
            AppBlockActivity.show(this, blockedPackage, reason)
        }
    }

    /** 静默退回桌面（SILENT_CLOSE 路径用），不拉起任何 UI */
    private fun sendToHome() {
        runCatching {
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_HOME)
                .setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }.onFailure { Logger.w(TAG) { "sendToHome failed: $it" } }
    }

    /**
     * 策略水印桥接（E2 修复）。
     *
     * KioskEnforcer 在 apply 时已把策略包里的 `kiosk.watermark` 写入 [com.padguard.core.engine.enforcer.KioskRuntime]，
     * 但此前没有任何消费点把它推到 [WatermarkOverlay] —— 家长端配了水印、孩子端恒不显示。
     * 这里在每次策略落地后读取 `policyEngine.kioskRuntime().watermark`，渲染占位符并 show/hide 悬浮窗。
     *
     * 占位符渲染需要本端设备信息：deviceId / deviceSn / studentName 来自 [AuthRepository]；
     * className 本端暂无数据源，留空（占位符原样保留，不影响其他占位符替换）。
     */
    private suspend fun reconcileWatermark() {
        // 统一走 syncWatermarkFromPolicy（唯一实现，含 position / opacity）：
        // 此前这里用的是 watermark.show(render(...))，位置与透明度被丢弃，
        // 而传了这两个参数的 syncWatermarkFromPolicy 全工程零调用 —— 家长把水印设成"底部/居中"、
        // 调高透明度，孩子端永远显示在左上 + 固定 0.35。
        runSafely("watermark") { syncWatermarkFromPolicy() }
    }

    // ==================== 心跳与上报 ====================

    private suspend fun sendHeartbeat() {
        // 凭据快照尚未从 DataStore 加载完成时绝不发鉴权请求：否则空令牌被服务端判
        // TOKEN_INVALID(40101)，触发自愈解绑把有效凭据清掉（启动竞态自残）。
        if (!credentials.isBound) return
        val deviceId = authRepository.getDeviceId()
        if (deviceId.isBlank()) return

        // 硬件采集受策略开关门控：家长关掉「采集硬件信息」后，
        // 电量/存储/内存/网络/屏幕一律不再随心跳上传（此前该开关在端上无人读取，形同虚设）。
        // 设备在线判定所需的 deviceId/timestamp/foregroundApp 不受影响。
        val collectHardware = policyEngine.monitoring().collectHardware
        // 守护状态快照（P3 可观测性）：复用既有 15s 心跳通道，不新增网络量级。
        // 数值均为实时状态读取，不触发任何动作；守护进程/无障碍是否存活是系统级事实，
        // 与家长"暂停管控"开关无关，始终如实上报以便观测。
        val daemonAlive = GuardHeartbeat.readDaemonAliveAgeMs(this)
            ?.let { it <= GuardHeartbeat.DAEMON_STALE_MS } ?: false
        val a11yAlive = GuardA11y.isAlive(this)
        // 链路状态四态（不再只有 ONLINE/MQTT_DOWN 两态）：
        // 传输通道（MQTT 已连 / 轮询健康）= transportUp；HTTPS 骨干（心跳往返可达）= httpUp。
        // - ONLINE              ：双通道都在，监管最强；
        // - MQTT_DOWN_HTTP_OK   ：MQTT 断了但 HTTPS 拉取循环仍在工作 —— **设备仍在监管下**，
        //                         家长端不应误报"离线"（此前只能看到 MQTT_DOWN，会被当成脱管）；
        // - HTTP_DOWN           ：传输通道在报"在线"但 HTTPS 骨干连不上（服务端 HTTPS 端点异常），
        //                         仍能收 MQTT 推送，属单点降级；
        // - OFFLINE             ：双通道皆断，设备真正失联。
        // 这层区分直接服务于"孩子端永远不会掉线"：把"假在线 / 单通道降级"与"真离线"分开，
        // 既不漏报真离线，也不误报仍在监管的设备为脱管。
        val transportUp = remote.isOnline()
        val httpUp = httpReachable
        val onlineState = computeDaemonOnlineState(transportUp, httpUp)
        val heartbeat = Heartbeat(
            deviceId = deviceId,
            timestamp = timeProvider.now(),
            policyVersion = policyRepository.currentVersion(),
            transportMode = transportSettings.mode.value.name,
            controlMode = admin.controlMode.value.name,
            network = if (collectHardware) snapshot.network() else null,
            battery = if (collectHardware) snapshot.battery() else null,
            storage = if (collectHardware) snapshot.storage() else null,
            memory = if (collectHardware) snapshot.memory() else null,
            screen = if (collectHardware) snapshot.screen() else null,
            foregroundApp = foregroundApp.current().orEmpty(),
            location = null,
            pendingLogCount = logRepository.pendingCount(),
            daemonAlive = daemonAlive,
            a11yAlive = a11yAlive,
            lastRestartReason = lastStartReason,
            onlineState = onlineState,
            permissionVersion = permissionVersion.get()
        )

        when (val result = remote.sendHeartbeat(heartbeat)) {
            is ApiResult.Success -> {
                lastHeartbeatOk = true
                online = true
                httpReachable = true
                consecutiveHttpFails.set(0)
                val ack = result.value

                // 落盘当前 offset：进程被杀后重启能立刻拿到接近正确的时间基准，
                // 不必等第一个网络样本（详见 TimeOffsetPrefs 的说明）。
                TimeOffsetPrefs.saveIfChanged(this, timeProvider.currentOffsetMs())
                // 注：受信时间护栏快照**不在这里**落盘。挂在心跳成功分支等于
                // "离线设备永远不刷新基线"，与"离线时间管控必须本地独立生效"红线相悖：
                // 设备断网一周后重启，恢复的会是一周前最后一次联网心跳留下的快照。
                // 已移到主循环的本地定时落盘（见 TIME_PERSIST_INTERVAL_MS）。

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
                // 服务端已响应(即便拒绝)，说明 HTTPS 可达
                httpReachable = true
                consecutiveHttpFails.set(0)
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
                if (consecutiveHttpFails.incrementAndGet() >= HTTP_UNREACHABLE_THRESHOLD) httpReachable = false
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
                when {
                    r.isSuccess -> logRepository.markAlertsUploaded(alerts)
                    // 服务端明确拒绝（BizError）必须"认栽丢弃"，不能原样留着重试。
                    // takePendingAlerts 每次取的是**最旧的** 50 条，一批被永久拒绝的坏数据会一直占住队头，
                    // 新告警只能排在队尾、永远进不了批次 —— 结果是家长从此收不到任何告警
                    // （包括「正在被卸载」「检测到 root」这类高危），而且服务端日志里只有一行 warn。
                    // 丢一批坏数据换来整条链路继续流动，是这里唯一正确的取舍。
                    r is ApiResult.BizError -> {
                        Logger.e(TAG) {
                            "alert upload rejected (${r.code} ${r.message}); dropping ${alerts.size} items to unblock queue"
                        }
                        logRepository.markAlertsUploaded(alerts)
                    }
                    else -> Logger.w(TAG) { "alert upload failed, keep ${alerts.size} pending" }
                }
            }

            val logs = logRepository.takePendingLogs()
            if (logs.items.isNotEmpty()) {
                val r = remote.uploadLogs(deviceId, logs.items)
                when {
                    r.isSuccess -> logRepository.markLogsUploaded(logs)
                    // 与告警同理：普通日志同样是"取最旧一批"，被拒的坏批次不丢弃就会堵死后续所有上报
                    // （家长端用量/轨迹永久停在某一刻）。日志不是高危信号，丢一批的代价可以接受。
                    r is ApiResult.BizError -> {
                        Logger.e(TAG) {
                            "log upload rejected (${r.code} ${r.message}); dropping ${logs.size} items to unblock queue"
                        }
                        logRepository.markLogsUploaded(logs)
                    }
                    else -> Logger.w(TAG) { "log upload failed, keep ${logs.size} pending" }
                }
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

        // 日志保留天数（security.logRetentionDays）此前只下发不执行：本地库只增不减。
        // 随上报周期跑，只删已上传记录，未上报数据不受影响。
        runCatching { policyEngine.purgeExpiredLogs() }
            .onFailure { Logger.w(TAG) { "purge expired logs failed: $it" } }

        // 指令幂等账本同样只增不减：7 天时间窗 + 500 条条数窗双裁剪。
        // 不裁剪时，服务端在故障重投窗口里的一次风暴就能写进几万行，
        // 之后每次 tryClaim 都要在这张表上查重，指令通道被自己的审计表拖慢。
        runCatching { commandRepository.purgeExpired() }
            .onFailure { Logger.w(TAG) { "purge command records failed: $it" } }
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
                    reconcileWatermark()
                    reconcileWebGuard()
                    // 操作密码闸门：把服务端下发的密码哈希落到本地（明文永不出网）。
                    // 约定：clear 为真 → 清密码；passwordHash 非空 → 启用并落库；
                    // 两者皆空 → 不改动（避免策略合并时误清空）。
                    applyGuardPassword(saved.guard)
                }
            }
            else -> Logger.w(TAG) { "fetch policy failed: $r" }
        }
        // 策略落地后顺带做卸载防御自检：① 权限活性（DO/管理员被摘除即告警）；
        // ② 持有 DO/PO 时系统级封死自身卸载。两项均为幂等，失败仅记日志不影响主流程。
        runSafely("uninstallDefense") {
            uninstallDefense.verifyLiveness()
            uninstallDefense.applyUninstallBlockIfSupported()
        }
    }

    /**
     * 按策略包的 `kiosk.watermark` 同步悬浮水印（**幂等**，可在任意策略落地后安全重复调用）。
     *
     * ## 为什么需要它
     * 水印此前只有 **指令**（SET_WATERMARK）一条驱动源，而指令是一次性的：
     * 设备重启、服务重建之后，策略里配好的水印不会自动恢复 ——
     * 家长看到的是"我明明开了水印，重启后就没了"。
     * 与此同时策略包里的 `kiosk.watermark.*` 四个字段（enabled / content / position / opacity）
     * 反序列化之后**没有任何一处读取**，位置与透明度更是被硬编码在
     * [com.padguard.child.ui.overlay.WatermarkOverlay] 里 —— 两条证据指向同一条断链。
     *
     * ## 与指令通道的关系（两条驱动源刻意并存）
     * - **策略是持久真相源**：每次策略落地（启动 / 刷新 / 自检）都按它对齐一次，
     *   于是"重启后水印没了"与"调了位置不生效"同时被解决；
     * - **指令是即时覆盖**：家长点一次立即生效，下一次策略对齐时回归策略值。
     *
     * ## 为什么 content 为空时要"不动"
     * 老版本服务端下发的策略包没有 watermark 字段，反序列化后 content 为空、
     * enabled 为 false。若据此一律 hide，就会把指令通道刚打开的水印误关掉 ——
     * 那等于用一个"没配置"当成了"要求关闭"。故以 content 是否为空作为
     * "策略确实配置了水印"的判据，只有配置存在时才由策略接管。
     */
    private suspend fun syncWatermarkFromPolicy() {
        val wm = policyEngine.kioskRuntime().watermark
        if (wm.content.isBlank()) return
        if (!wm.enabled) {
            watermark.hide()
            return
        }
        val deviceId = authRepository.getDeviceId()
        // deviceSn 读失败按空渲染（水印缺序列号），但取消必须放行——runCatching 会把
        // 协程取消当普通失败吞掉，已取消的协程继续 show() 水印（R37）。
        val deviceSn = try {
            authRepository.deviceSn.first()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ""
        }
        // studentName / className 属校园场景字段，孩子端无可靠数据源，留空由模板自行取舍
        val rendered = wm.render(
            deviceSn = deviceSn,
            studentName = "",
            className = "",
            deviceId = deviceId
        )
        watermark.show(rendered, position = wm.position, alpha = wm.opacity)
    }

    /**
     * 按策略对账「上网防护 VPN」通道（**幂等**，可在任意策略落地后安全重复调用）。
     *
     * ## 为什么需要它
     * 见 [webGuardVpn] 字段注释：执行体（[com.padguard.child.guard.WebGuardVpnService]）
     * 与控制器早就写好了，但没有任何调用点，等于家长配的网站过滤全是空转。
     *
     * ## 三种如实状态
     * - `OFF`：策略没要求上网管控 → 通道停掉（并解除 always-on 锁定）；
     * - `RUNNING`：通道在跑 → 域名级拦截生效；
     * - `NEED_CONSENT`：策略要求但系统未授权 → 如实记录，由 UI 引导家长补授权。
     *   绝不"假装在保护"：没授权就是没保护，日志里必须能看见。
     */
    private suspend fun reconcileWebGuard() {
        // 读不到策略（数据库异常）时**什么都不做**：既不能贸然开，也不能贸然关，
        // 保持现状比按默认值 OFF 把正在生效的通道关掉更安全。
        val web = runSafely("readWebPolicy") { policyRepository.getPolicy().web } ?: return
        val state = runSafely("webGuardSync") { webGuardVpn.sync(web) } ?: return
        Logger.i(TAG) {
            "web guard vpn: $state (mode=${web.mode}, black=${web.blacklist.size}, " +
                "white=${web.whitelist.size}, cat=${web.blockedCategories.size})"
        }
        // 策略要求但没授权，是"家长以为在管、实际没管"的典型场景。
        // 这里只记日志、**不抬风险事件**：本方法 5 分钟一轮，抬事件会造成告警刷屏；
        // 家长端可见性由 [watchdogWebGuard] 的 10 分钟节流上报承担（30 秒一轮，
        // 对 START_BLOCKED 与 NEED_CONSENT 一视同仁），孩子端由「我的 → 上网防护」真实状态承担。
        if (state == com.padguard.child.guard.WebGuardVpnController.SyncState.NEED_CONSENT) {
            Logger.w(TAG) { "web filter configured but VPN unauthorized — 域名拦截当前不生效，等待家长补授权" }
        }
    }

    /**
     * A1-P0：Web 过滤隧道看门狗（比 5 分钟策略自检更密）。
     *
     * 非 DO 设备下，DNS-only 的 VpnService 一旦停止，全部域名不再过滤（架构性 fail-open）。
     * 本方法把"该跑却没跑"的空窗从 ≤5 分钟压到 ≤30 秒：
     * - 策略要求过滤且当前未激活 → 调 [com.padguard.child.guard.WebGuardVpnController.sync] 重建；
     * - 两种「防护掉了」都按 10 分钟节流上报 [RiskType.WEB_GUARD_DOWN]，让家长端立刻知情：
     *   `START_BLOCKED`（已授权但本次启动被系统拒绝，如 Android 12+ 后台 FGS 限制）与
     *   `NEED_CONSENT`（VPN 授权被撤/从未授予，隧道压根没起）——后者过去被静默跳过，
     *   孩子可以随意上网而家长端毫无提示；
     * - 只有 `RUNNING`（或家长主动关闭过滤的 `OFF`）才判定恢复并关掉告警。
     *
     * 不替代 [reconcileWebGuard]（后者还负责"策略关→停隧道/解 always-on"），二者互补。
     * fail-closed 注意：任何异常都被 [runSafely] 吞掉，看门狗自身绝不能拖垮主循环。
     */
    private suspend fun watchdogWebGuard() {
        // 读不到策略（数据库异常）时保持现状，不贸然操作隧道
        val web = runSafely("readWebPolicy") { policyRepository.getPolicy().web } ?: return
        if (!webGuardVpn.requiredBy(web)) {
            // 策略已不再要求过滤：若此前报过 WEB_GUARD_DOWN，顺手关掉对应告警
            if (webGuardDownRaised) clearWebGuardDownAlert()
            return
        }

        val state = runSafely("webGuardSync") { webGuardVpn.sync(web) } ?: return
        if (state == com.padguard.child.guard.WebGuardVpnController.SyncState.RUNNING ||
            state == com.padguard.child.guard.WebGuardVpnController.SyncState.OFF
        ) {
            // 只有「通道确实在跑」或「家长主动关掉过滤」才算恢复。
            // NEED_CONSENT 是 VPN 授权被撤/从未授予，隧道根本没起、域名拦截完全失效；
            // 若把它当成恢复态关掉告警，就成了「防护掉了却告诉家长已修好」。
            if (webGuardDownRaised) clearWebGuardDownAlert()
            return
        }

        // START_BLOCKED（系统拒绝重启）与 NEED_CONSENT（VPN 授权缺失）都是「防护掉了」：
        // 前者起不来，后者压根没在跑，两种情况下域名过滤都不生效。
        // 此前只认 START_BLOCKED，NEED_CONSENT 被静默跳过 —— 家长以为在管、实际没管。
        // 显式列出全部失败态而非 else 兜底：以后 SyncState 新增值若忘了接线，
        // 源码契约测试会当场红，不会悄悄变成「新失败态被当成恢复」。
        val reason = when (state) {
            com.padguard.child.guard.WebGuardVpnController.SyncState.NEED_CONSENT -> "vpn_consent_missing"
            com.padguard.child.guard.WebGuardVpnController.SyncState.START_BLOCKED -> "restart_blocked"
            else -> return // 未知状态不猜：既不误报恢复，也不发含义不明的告警
        }

        // 两种失败态共用 10 分钟节流窗，避免 30 秒看门狗每 tick 刷屏挤占关键告警。
        val now = SystemClock.elapsedRealtime()
        if (now - lastWebGuardDownAlarmMs <= WEB_GUARD_DOWN_ALARM_THROTTLE_MS) return
        lastWebGuardDownAlarmMs = now
        webGuardDownRaised = true

        val deviceId = authRepository.getDeviceId()
        if (deviceId.isBlank()) return
        runSafely("raiseWebGuardDown") {
            logRepository.raise(
                RiskType.WEB_GUARD_DOWN, RiskLevel.HIGH,
                mapOf("reason" to reason), deviceId
            )
        }
    }

    /**
     * 隧道恢复后关闭 WEB_GUARD_DOWN 告警（边沿触发，配合 [webGuardDownRaised] 只关一次）。
     * 走 [PermissionHealthChecker.clearWebGuardDown] 统一出口，复用其返回值校验与日志。
     */
    private fun clearWebGuardDownAlert() {
        webGuardDownRaised = false
        scope.launch { runSafely("clearWebGuardDown") { permissionHealthChecker.clearWebGuardDown() } }
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
                        // 操作密码已设置：闭环家长端"操作密码尚未设置"告警（与 S11 上报成对）
                        runSafely("clearGuardPwdNotSet") { permissionHealthChecker.clearGuardPasswordNotSet() }
                    }
                    else -> Logger.v(TAG) { "guard policy present but empty, keep current state" }
                }
                ensureGuardInfra()
                // 操作密码是「卸载软门」能否验身的判据：家长端一设置，常驻通知就应
                // 立刻从"待完善：请设置操作密码"变回"守护中"，不等 10 分钟周期自检。
                updateNotification()
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
                            // 幂等账本也要落一条终态，但**只对真正的拒绝**（时效/签名未过）补写：
                            // 这条路径上 tryClaim 从未执行，账本里查无此令，签名类攻击事件因此
                            // 没有任何本地痕迹可复盘。补一条 REJECTED 就有了。
                            //
                            // DUPLICATED 必须跳过：账本里那条记录存的是**首次执行的真实终态**
                            // （可能是 FAILED），用"已判重"覆盖会把失败洗成成功，还会冲掉真实执行时间。
                            if (event.ack.status != AckStatus.DUPLICATED) {
                                runCatching {
                                    // 事件未携带 command，类型未知，传 null 由仓库写占位类型
                                    commandRepository.recordResult(
                                        msgId = event.ack.msgId,
                                        type = null,
                                        status = event.ack.status,
                                        executedAt = event.ack.executedAt,
                                        errorMessage = event.reason
                                    )
                                }.onFailure { Logger.w(TAG) { "record rejected command failed: ${it.message}" } }
                            }
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
                            if (event.online) {
                                // 重连后主动回拉策略与会话，而不是干等下一轮 tick（最长 15s）：
                                // ① 联网恢复时若服务端已解锁 / 改策略 / 跨日已被家长起表，
                                //    锁屏能更快收起或切换原因，避免「联网了却还卡在旧锁屏」；
                                // ② 离线期间本地「开始计时」起的表（pendingStartSync）能立刻
                                //    补发给服务端（refresh 内置补发通道），堵住「孩子端已起表、
                                //    家长端却看不到今日计时起点」的对账缺口 —— 这正是
                                //    「跨日无法开启第二天计时」在联网瞬间的最后一公里。
                                runSafely("flushOnReconnect") { uploadPending() }
                                // 重连瞬间主动兜底拉取离线期间家长下发的指令（UNLOCK / START_TIMER_CONFIRM 等）：
                                // 推送在重连窗口 / QoS 边界可能漏一条，若只等 [startCommandLoop] 的下一跳（≤3s）
                                // 或心跳提示（≤30s），「家长已点解锁 / 已开始计时」会滞后甚至要等轮询，
                                // 表现为「点了没反应」。这里立刻补拉一次，确保离线指令在联网瞬间落地。
                                scope.launch { runSafely("drainOnReconnect") { drainPendingCommands() } }
                                scope.launch { runSafely("policySyncOnReconnect") { refreshPolicy() } }
                                scope.launch { runSafely("sessionSyncOnReconnect") { usageSessionManager.refresh() } }
                                // 离线/弱网解绑加固：平板在家长解绑时若离线、MQTT 的 unbind=1 因
                                // broker 会话过期等丢失，仅靠 ≤30s 心跳自愈会有延迟。联网瞬间主动
                                // 复核一次绑定状态，服务端已吊销令牌(40101)/解绑(40301)则立即就地
                                // 释放管控并回到绑定页，不必等下一轮心跳。handleRemoteUnbind 自带
                                // "与服务端核实"守卫，正常在绑设备 fetchPolicy 成功即早返回，绝不误伤。
                                scope.launch { runSafely("unbindCheckOnReconnect") { handleRemoteUnbind() } }
                            }
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
                // 新增缺陷 L：策略缓存解不开且本进程没有可信副本时，observePolicy() 只能回落到
                // 不管控默认基线。此时**绝不能**把这份 default 写进 AppGuardPolicyStore ——
                // 那份镜像是跨进程持久化的"上次已知良好应用策略"，被覆盖等于把完好的应用管控
                // 一并清空；也别顺手把整机总开关 / 护眼滤镜按 default 落地。
                // 保住现状（fail-closed 方向），等服务重发策略或密钥恢复后自然恢复。
                if (!policyRepository.hasTrustedPolicy()) {
                    Logger.w(TAG) {
                        "policy cache unreadable, keep last known app policy mirror / master switch"
                    }
                    return@collect
                }
                runSafely("mirrorAppPolicy") { appGuardStore.mirror(pkg.app) }
                // 整机管控总开关随策略落地：guard.active=false 即"暂停管控、释放设备"。
                // 这是"取消应用管控后设备应可正常使用"的正确驱动点——释放设备由总开关决定，
                // 而非只清空应用黑白名单。
                runSafely("applyGuardMaster") { guardMasterSwitch.setActive(pkg.guard.active) }
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

            is EngineEffect.ShowStartTimerConfirm -> {
                // 家长端「开始计时」：force 弹出确认浮层（绕过孩子的"暂不开始"抑制窗口
                // ——家长催办优先），孩子点「开始计时」才真正起表。
                // 先刷一次会话（若服务端已 active，浮层由 needsStartConfirm=false 自动收起），
                // 弹出动作不必等服务端返回——离线时孩子同样可以确认（本地起表）。
                usageStartOverlay.show(
                    confirmAction = { scope.launch { usageSessionManager.confirmStart() } },
                    fromParent = true
                )
                scope.launch { runSafely("sessionSyncOnStartRequest") { usageSessionManager.refresh() } }
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
                // 统一走系统级置顶渲染：MessageOverlayController 现已彻底改为只委托
                // MessageActivity（不再自绘悬浮窗 ComposeView，避免 ViewTreeLifecycleOwner
                // not found 的 FATAL 崩溃）。MessageActivity 内部用 setShowWhenLocked +
                // 全屏高优先级通知(fullScreenIntent) 实现锁屏/后台也能霸屏；在设备所有者(DO)
                // 且持有 SYSTEM_ALERT_WINDOW 时后台自动拉起，非 DO 退化为置顶横幅供孩子点击。
                messageOverlay.show(
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
                if (consentRevoked("screenshot", effect.msgId)) return@handleEffect
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
                if (consentRevoked("photo", effect.msgId)) return@handleEffect
                val error = photoCapture.captureAndUpload(effect.taskId)
                reportEffectResult(effect.msgId, "photo", error == null, error ?: "已上传")
            }

            is EngineEffect.RequestLocation -> {
                if (consentRevoked("locate", effect.msgId)) return@handleEffect
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
                if (consentRevoked("screenRecord", effect.msgId)) return@handleEffect
                // 录屏早期直接走 ScreenCaptureService.startScreenRecord → 后台 startActivity 被 Android 10+ 拦掉，
                // 授权页永远出不来，家长端只看到"录屏启动失败"。统一走派发口后与截屏同一条链路。
                val captureIntent = ScreenCaptureService.screenRecordIntent(
                    this, effect.taskId, effect.resolution, effect.withAudio
                )
                val silent = dispatchCapture(captureIntent)
                reportEffectResult(
                    effect.msgId, "screenRecord", silent,
                    if (silent) "已开始录制" else "未取得屏幕采集授权：需在孩子端完成一次屏幕采集授权（家长密码 → 补授权）"
                )
            }

            EngineEffect.StopScreenRecord -> ScreenCaptureService.stopScreenRecord(this)

            is EngineEffect.StartLiveView -> {
                if (consentRevoked("liveView", effect.msgId)) return@handleEffect
                // 记录看屏会话窗口：进程被杀重启后据此主动恢复推流（服务端
                // reconcileLiveSessions 的 8s 冷却重发是第二保险，双通道不冲突）。
                GuardHeartbeat.writeLiveView(
                    this,
                    effect.highDefinition,
                    System.currentTimeMillis() + GuardHeartbeat.LIVE_VIEW_TTL_MS
                )
                // 画质档位随指令一路带到推流侧：家长改了「高清画面」后，
                // 下一次续期（或重新开流）就按新档位出帧，不必退出看屏页重进。
                // 看屏通道派发（权限钉死架构：一次性授权后除解绑外永不失效）：
                // 主路 = 无障碍零弹窗通道（系统级持久授权，约 2fps）。已通过 [CaptureConsentStore]
                // 钉死标记，启动/亮屏/看屏中看门狗会 DO 静默重绑，从源头阻止"权限丢失"。
                // 投影仅作兜底：StopLiveView 改走 suspendProjection() 保留凭据，同进程内再进入直接复用、
                // 零弹窗；仅当凭据确已失效（IBinder 死亡，由 obtainProjection 安全判定）才退回授权引导。
                // 优先级：① a11y 可用 → 直接 a11y 零弹窗；② a11y 不可用但有令牌 → 投影零弹窗复用；
                // ③ a11y 不可用且无令牌 → 投影授权引导（并行触发 DO 静默重生无障碍服务，自愈）。
                val captureIntent = ScreenCaptureService.liveStreamIntent(this, effect.highDefinition)
                // 关键修复（2026-11-07「看屏还要孩子再确认一次授权」）：
                // 旧实现用 `isAvailable()` 的**瞬时快照**决定是否降级到投影。无障碍服务被
                // 系统重绑 / 国产 ROM 短暂摘除时会有数秒空窗，此刻同步降级会立刻向孩子弹
                // 「请确认屏幕采集授权」；而服务端每 8s 重发一条新 msgId 的 LIVE_VIEW_START，
                // 把这次误判反复重放，孩子端就周期性弹框。
                // [AccessibilityLiveStreamer.start] 本身已内置「等待重绑 + 仅一次投影兜底」，
                // 所以 API 30+ 一律先把开流交给它，绝不在这里抢先判死。
                // 只有 API < 30（无 takeScreenshot，等也等不到）才直接走投影复用。
                val silent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    scope.launch { runCatching { a11yCapabilityHealer.ensureEnabled() } }
                    a11yLiveStreamer.start(effect.highDefinition)
                    // 无障碍主路命中即钉死授权：标记后启动/亮屏会主动静默重绑。
                    runCatching { captureConsentStore.markA11yGranted() }
                    Logger.i(TAG) { "live view routed to a11y channel (rebind wait, no consent dialog)" }
                    true
                } else {
                    // 老系统只能走 MediaProjection：投影常驻保活后 hasToken() 为真即零弹窗复用。
                    dispatchCapture(captureIntent)
                }
                reportEffectResult(
                    effect.msgId, "liveView", silent,
                    if (silent) "已开始实时推流" else "未取得屏幕采集授权：需在孩子端完成一次屏幕采集授权（家长密码 → 补授权）"
                )
            }

            EngineEffect.StopLiveView -> {
                // 家长停止观看：清除恢复窗口，避免进程重启后恢复一条已结束的推流
                GuardHeartbeat.clearLiveView(this)
                a11yLiveStreamer.stop()
                ScreenCaptureService.stopLiveStream(this)
                // 停推流但**保留**投影凭据（suspendProjection 不清 resultIntent）：
                // "返回主页面再进入监控"时同一进程内直接复用凭据，全程零弹窗、不掉线。
                // 仅在设备解绑（handleRemoteUnbind）才调用 revoke() 真正销毁凭据。
                ScreenCaptureSession.suspendProjection()
            }

            is EngineEffect.StartAudioRecord -> {
                if (consentRevoked("audioRecord", effect.msgId)) return@handleEffect
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

            is EngineEffect.UpdateRuntimeConfig -> runSafely("runtimeConfig") {
                // 原实现只打一行日志却回执 SUCCESS：家长端显示"已下发成功"，
                // 设备的轮询间隔/传输模式毫无变化。这里按 MQTT CONFIG 主题的同一套键名落地，
                // 保证两条下发通道语义一致。
                val p = effect.params
                transportSettings.applyRemoteConfig(
                    heartbeatSec = p["heartbeatIntervalSec"]?.toIntOrNull(),
                    pollingSec = p["pollingIntervalSec"]?.toIntOrNull(),
                    logUploadSec = p["logUploadIntervalSec"]?.toIntOrNull()
                )
                p["transportMode"]?.uppercase()?.let { mode ->
                    runCatching { com.padguard.core.transport.TransportMode.valueOf(mode) }
                        .getOrNull()
                        ?.let { transportSettings.setMode(it) }
                }
                Logger.i(TAG) { "runtime config applied from command: ${p.keys}" }
            }

            EngineEffect.RevealManagementUi -> {
                // 远程揭示管理台（家长端指令 REVEAL_MANAGEMENT_UI）：点亮桌面图标并直接拉起
                // 管理台（EXTRA_REVEAL 会话），配置结束销毁时 MainActivity 会自动重新隐藏。
                // 与悬浮卡密码闸的唯一区别是免密码——指令通道已由服务端家长身份鉴权。
                runSafely("remoteReveal") {
                    appHider.revealManagementUi()
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            .putExtra(MainActivity.EXTRA_REVEAL, MainActivity.newRevealToken())
                    )
                }
                Logger.i(TAG) { "management UI revealed via remote command" }
            }
        }
    }

    /**
     * 采集/运维类副作用的真实结果留痕。
     *
     * 指令回执在 [com.padguard.core.engine.command.CommandExecutor] 里就已经发出去了，
     * 语义是"已受理"；这里补的是**最终结果**。两者缺一不可：
     * 只有前者会出现"看起来成功的失败"，只有后者则管控端不知道指令有没有被收到。
     */
    /**
     * 撤回同意后的即时指令闸门。周期采集由 [MonitoringEnforcer] 统一收敛为关闭，
     * 但截图/录屏/看屏/录音/定位是家长指令驱动的旁路，必须在这里逐条挡掉，
     * 否则"撤回同意"只停了定时上报，家长点一次按钮仍能拿到新的个人数据。
     */
    private suspend fun consentRevoked(action: String, msgId: String): Boolean {
        if (!policyEngine.monitoring().consentRevoked) return false
        reportEffectResult(msgId, action, false, "已撤回同意，采集已停止；恢复同意后重试")
        return true
    }

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
            val text = notificationText()
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(text), fgsType())
            // 建立"已投递"基线：否则主循环的第一拍会立刻把同一份内容再投递一次。
            // 与 startForeground 共用同一份正文，保证"实时判定"每次刷新只算一次。
            lastNotificationText = text
        } catch (e: Exception) {
            // API 31+ 从后台启动前台服务会抛 ForegroundServiceStartNotAllowedException。
            // Device Owner 身份下系统会豁免，但降级到普通 DEVICE_ADMIN 时确实可能被拒。
            // 这里不能让它崩：崩了会连带整个管控进程重启，形成崩溃循环。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is ForegroundServiceStartNotAllowedException
            ) {
                Logger.e(TAG, e) { "FGS start not allowed, guard runs in degraded background mode" }
                // 绝不再静默降级：进程已失去前台豁免，随时可被回收，锁屏/截屏/定位指令会石沉大海。
                // 上报告警让家长知道"在线≠健康"，可据此去系统设置里放行自启/关闭电池优化。
                scope.launch {
                    runCatching {
                        logRepository.raise(
                            type = RiskType.GUARD_FOREGROUND_DOWN,
                            level = RiskLevel.HIGH,
                            detail = mapOf(
                                "reason" to "FOREGROUND_SERVICE_START_NOT_ALLOWED",
                                "api" to Build.VERSION.SDK_INT.toString()
                            ),
                            deviceId = runCatching { authRepository.getDeviceId() }.getOrDefault("")
                        )
                    }.onFailure { Logger.w(TAG, it) { "report foreground degraded failed" } }
                }
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
        //
        // ⚠️ 关键约束(API 29+)：启动带 location 类型的前台服务，要求应用此刻持有
        // ACCESS_FINE/COARSE_LOCATION；若该危险权限被孩子在系统设置里撤销，startForeground
        // 会抛 SecurityException，守护进程随即被系统回收 —— 即「孩子撤销定位权限即可杀掉管控」。
        // 因此**仅当定位权限仍被授予时才叠加 location 类型**；权限被撤则退回 base 类型，
        // 守护进程照常存活（后台定位暂不可用，权限恢复后由 LocationAutoReporter 兜底补传）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasLocationPermission()) {
            return base or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        return base
    }

    /** 定位危险权限是否仍被授予（定位权限被撤销时不可再启动带 location 类型的前台服务）。 */
    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * @param text 正文由调用方算好传入：一次刷新只做一次 [notificationText] 实时判定。
     * 若这里内部再调一次，[UninstallDefenseModule.noticeShield] 的"连续两拍"防抖会在
     * 同一次 enterForeground 里被算成两拍，开机瞬时窗口又会立刻误报"未开启"。
     */
    private fun buildNotification(text: String): Notification {
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.guard_notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_guard_notification)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /**
     * 常驻通知的正文（唯一会变化的部分）。
     *
     * 抽成独立函数是为了让 [updateNotification] 能以它作为"内容是否变化"的判据 ——
     * 通知里其余字段（标题 / 图标 / PendingIntent）都是常量。
     */
    private fun notificationText(): String {
        val modeText = getString(
            when {
                online && lastHeartbeatOk -> R.string.guard_state_online
                online -> R.string.guard_state_connecting
                else -> R.string.guard_state_offline
            }
        )

        // 防护未就绪时，通知栏要**明说**，不能让家长以为"守护中"就等于"卸载需要密码"。
        // 事故现场就是这样：通知写着"护航成长守护中"，孩子长按图标一次就全删干净了。
        //
        // 关键修复（"装机授权做完了，通知还喊未开启"）：这里读的是 [UninstallDefenseModule.noticeShield]
        // 的**实时探测**结果，不是 [UninstallDefenseModule.shield] 那份 10 分钟才刷一次的周期快照。
        // 开机首拍常撞上"策略未下发 / 无障碍未重绑"的瞬时窗口，旧实现把那一拍的 NONE 钉在
        // 通知栏整整 10 分钟；现在权限一补齐，下一拍就恢复"守护中"，且变弱带两拍迟滞防抖。
        val noticeText = ShieldNotice.text(
            shield = uninstallDefense.noticeShield(),
            unguardedText = getString(R.string.guard_notification_text_unguarded),
            missingPasswordText = getString(R.string.guard_notification_text_no_password)
        )
        // 文案按真实缺口分支：最常见的"无障碍已开、只差家长端操作密码"不能再被误导成
        // "去开无障碍"，否则家长照做一百遍也修不好，通知永远不消失。
        return noticeText
            ?: getString(R.string.guard_notification_text, modeText, admin.controlMode.value.name)
    }

    /**
     * 刷新常驻通知。
     *
     * ## 为什么要去重
     * 本方法挂在主循环上（约 2 秒一拍），原先**每拍都无条件 notify**：
     * 系统日志里可见 `enqueueNotificationInternal ... id=1001` 每 2 秒一条，
     * 一天近 5 万次跨进程投递；通知内容实际上几分钟才变一次。
     * 这既耗电、又会在部分 ROM 上把本应用标记成"频繁通知"，
     * 还让 logcat 被自己的噪音淹没（排查无障碍掉线时深受其害）。
     *
     * 现在以正文为签名，内容没变直接返回。首次进入前台由 [enterForeground]
     * 用同一份正文建立"已投递"基线，不会重复投递。
     */
    private fun updateNotification() {
        val text = notificationText()
        // 通知总开关被关 / 渠道被调成「不显示」时 notify() 会静默丢弃且不抛异常。
        // 旧实现照样把正文记成"已投递"，等家长重新打开通知后正文没变，去重直接 return，
        // 常驻通知就永远不再出现（R34）。不可投递时作废基线，恢复后强制补发。
        if (!NotificationDelivery.canDeliver(this, CHANNEL_ID)) {
            lastNotificationText = null
            return
        }
        if (text == lastNotificationText) return
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification(text))
            lastNotificationText = text
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
                httpReachable = true
                consecutiveHttpFails.set(0)
                val resp = r.value
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
                // 游标必须在**执行完本批**之后再推进落盘：这条兜底通道的存在意义是"补拉漏推的指令"，
                // 若先推进游标、后执行，进程在执行中崩溃（或断电）时这批指令已被游标跳过、
                // 服务端也因已回执而终态收敛（或下一次轮询不再返回），指令即静默丢失。
                // 执行后再推进：崩溃时游标仍指旧值，重启后重拉 —— 幂等由 tryClaim+msgId 兜底，
                // 重复拉取不会重复执行；已回执的指令在服务端已是终态、pendingForPolling 不会返回。
                if (list.isEmpty()) {
                    // 游标一律取服务端校准时间（timeProvider.now()），不能直接读本地墙钟：
                    // 本地快 30s 就会把 since 写到服务端未来，下一批指令在服务端被判为"旧数据"过滤掉。
                    lastCommandPullAtMs = timeProvider.now()
                    commandPollPrefs.edit().putLong(KEY_LAST_PULL_AT, lastCommandPullAtMs).apply()
                    return
                }
                Logger.w(TAG) {
                    "fallback pull: ${list.size} command(s) missed by push -> ${list.map { it.type.name }}"
                }
                var failed = 0
                val failedIds = mutableListOf<String>()
                list.forEach { cmd ->
                    // 与推送/轮询通道一致：执行前先占幂等标记（DB 账本）。
                    // 兜底补拉通道此前绕过 CommandGate，既无签名/时效校验也无 tryClaim，
                    // 导致"已在本进程执行、仅 ACK 未达服务端"的指令（进程内 markExecutedIfFirst
                    // 标记在重启后即失效）被重复执行。这里先 tryClaim：账本已有终态 → 跳过并补发
                    // DUPLICATED 回执让服务端收敛为终态；账本无记录（含启动 resetInFlightOnStartup
                    // 清掉的崩溃残留 PENDING）→ 认领后正常执行（"宁可重放，不可静默丢失"的有意取舍）。
                    if (!commandRepository.tryClaim(cmd.msgId, cmd.type)) {
                        Logger.d(TAG) { "fallback pull dedup via ledger: ${cmd.type.name} ${cmd.msgId}" }
                        runSafely("fallbackDupAck:${cmd.type.name}") {
                            runCatching {
                                remote.sendAck(
                                    CommandAck(
                                        msgId = cmd.msgId,
                                        deviceId = deviceId,
                                        status = AckStatus.DUPLICATED,
                                        executedAt = timeProvider.now(),
                                        errorCode = AckStatus.DUPLICATED.name,
                                        errorMessage = "already executed, deduped by ledger"
                                    )
                                )
                            }.onFailure { Logger.w(TAG) { "fallback dup-ack ${cmd.msgId} failed: $it" } }
                        }
                        return@forEach
                    }
                    // 三级结果：true=执行且回执成功；false=执行成功但回执失败；null=执行抛异常。
                    // 不能用外层 runSafely 一把包住再无条件推进游标 —— 那样"执行失败"和"回执失败"
                    // 都会被吞成一次日志，而服务端此时仍把指令保持 PENDING；
                    // 游标一旦越过该指令的创建时间，`pendingForPolling(since=新游标)` 就再也不会返回它，
                    // 这条一次性指令（锁屏/截屏/录屏）即永久丢失，重启也补不回来。
                    val executed = runSafely("fallbackCmd:${cmd.type.name}") {
                        // 去重在 CommandExecutor 内做（推送/轮询双通道共用漏斗）；
                        // 无论新执行还是去重忽略，都要回执，让服务端收敛指令状态
                        val ack = policyEngine.execute(cmd, deviceId)
                        runCatching { remote.sendAck(ack) }
                            .onFailure { Logger.w(TAG) { "fallback ack ${cmd.msgId} failed: $it" } }
                            .isSuccess
                    }
                    if (executed != true) {
                        failed++
                        failedIds += cmd.msgId
                    }
                }
                if (failed > 0) stuckPullRounds++
                if (failed == 0 || stuckPullRounds >= MAX_STUCK_PULL_ROUNDS) {
                    // 全部成功 → 正常推进。
                    // 连续 N 轮都执行/回执不掉同一批 → 放弃这批（否则每 3 秒重试一次、日志刷屏，
                    // 且永久卡死的指令会挡住后面所有新指令）。放弃时打 ERROR，便于按 msgId 人工补发。
                    if (failed > 0) {
                        Logger.e(TAG) {
                            "fallback pull stuck for $stuckPullRounds rounds ($failed/${list.size} not acked), " +
                                "skipping batch: $failedIds"
                        }
                    }
                    stuckPullRounds = 0
                    lastCommandPullAtMs = timeProvider.now()
                    // 游标落盘：进程被回收后续接，杜绝"息屏期间下达的指令被永久跳过"
                    commandPollPrefs.edit().putLong(KEY_LAST_PULL_AT, lastCommandPullAtMs).apply()
                } else {
                    // 保留旧游标，下一轮把整批重新拉一遍：重复执行由 tryClaim+msgId 幂等兜底。
                    Logger.w(TAG) {
                        "fallback pull: $failed/${list.size} command(s) not acked, cursor held for retry " +
                            "(round $stuckPullRounds/$MAX_STUCK_PULL_ROUNDS)"
                    }
                }
            }
            is ApiResult.BizError -> {
                // 服务端已响应(即便拒绝)，说明 HTTPS 可达
                httpReachable = true
                consecutiveHttpFails.set(0)
                Logger.w(TAG) { "fallback pull rejected: ${r.code} ${r.message}" }
            }
            is ApiResult.Failure -> {
                // 网络层失败：连续多次才判不可达，避免单次抖动就放慢节奏
                if (consecutiveHttpFails.incrementAndGet() >= HTTP_UNREACHABLE_THRESHOLD) httpReachable = false
                Logger.w(TAG) { "fallback pull failed: ${r.message}" }
            }
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
        // 解绑即暂停整机管控总开关：非 DO 设备无法以代码关闭无障碍服务
        // （a11yCapabilityHealer.disable() 在非 DO 下是空操作），而软门（卸载盾 / 设置拦截 /
        // 隐藏应用门禁）全部以 guardMasterSwitch 为第一道闸门（OperationGuardAccessibilityService
        // 等拦截入口首句即 `if (!guardMasterSwitch.active.value) return`）。若不把它置 false，
        // 解绑后无障碍服务仍在系统里启用、软门照常拦截，平板依旧"闪退、应用管理里卸不掉"。
        // 置 false 立即让软门全部失效，设备回到完全可用；该值持久化落盘，直到重新绑定后
        // 策略刷新（应用策略包时 setActive(guard.active)）自动重新武装，不会误伤重绑设备。
        // 不影响服务端、家长端、其它已绑定设备。
        guardMasterSwitch.setActive(false)
        // 解绑即解除设备管控：必须先清掉 App 内锁定态并释放我们压下的系统 keyguard，
        // 否则「家长端已解绑」但孩子端仍卡在锁屏页——REMOTE 锁的持久化标记会让进程重启后
        // 再次锁上，且我们 lockNow() 压下的系统锁不被释放，孩子端表现为"解绑了却毫无反应、黑屏仍锁着"。
        // 必须在 admin.releaseControl() 之前执行：退出屏幕固定(Lock Task)需要仍持有设备控制能力。
        // lockController 为 @Singleton，releaseAll() 会同步驱动 LockScreenPresenter /
        // LockScreenActivity 的状态收集器收起锁屏；lockScreenPresenter.dismiss() 进一步经
        // SystemKeyguardBridge.ensureUnlocked() 释放系统 keyguard，二者互补、缺一则锁屏残留。
        lockController.releaseAll()
        withContext(Dispatchers.Main) {
            runCatching { lockScreenPresenter.dismiss() }
                .onFailure { t -> Logger.e(TAG, t) { "lock screen dismiss failed on unbind" } }
        }
        // 只清设备绑定、保留家庭账号登录态：解绑后应直接回到绑定页重新绑定，
        // 而不是退回登录页（孩子不知道家长密码，会永久卡死）。
        authRepository.clearDeviceBinding()
        // 绑定镜像同步清除：暖令牌的前置门重新关上，解绑后不再向孩子弹采集确认框。
        CaptureConsent.markDeviceBound(this, false)
        // 解绑即结束看屏会话恢复窗口（家长端此时多半已停止观看，双保险）
        GuardHeartbeat.clearLiveView(this)
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
        // 解绑即撤销全部采集授权（权限钉死架构的唯一撤权出口）：
        // 清掉进程内投影令牌、持久化存储标记与无障碍服务（DO 静默禁用），
        // 确保转手后旧家庭既看不到实时画面、也无需再次向孩子弹系统授权框。
        runCatching { ScreenCaptureSession.revoke() }
            .onFailure { t -> Logger.e(TAG, t) { "screen capture revoke failed on unbind" } }
        // 投影会话保活后必须显式收掉采集前台服务，否则解绑后仍挂着投影芯片。
        runCatching { ScreenCaptureService.stopCaptureService(this) }
        runCatching { captureConsentStore.revoke() }
            .onFailure { t -> Logger.e(TAG, t) { "capture consent store revoke failed on unbind" } }
        // 必须在这里直接 await：disable() 是 suspend 且可能被 opMutex 上的 heal/rebind 挂起，
        // 另起 scope.launch 后紧接着 stopSelf()->onDestroy()->scope.cancel() 会把撤权协程取消，
        // 无障碍开关残留在系统启用名单里（转手设备仍持有本应用的采集/拦截能力）。
        runCatching { a11yCapabilityHealer.disable() }
            .onFailure { t -> Logger.e(TAG, t) { "a11y disable failed on unbind" } }
        // 上网防护通道必须显式收掉：sync() 是唯一会 stop() + disableAlwaysOn() 的入口，
        // 而它只由 refreshPolicy 调用，refreshPolicy 在未绑定态直接 return。
        // 不补这一步，解绑后 VPN 前台服务仍按旧规则拦 DNS、always-on 锁还留在系统里 ——
        // 家长以为已解除管控，孩子却仍上不了被拦网站，且设置里改不了 VPN。
        runCatching { webGuardVpn.sync(com.padguard.core.data.model.policy.WebPolicy()) }
            .onSuccess { Logger.i(TAG) { "web guard vpn synced off on unbind: $it" } }
            .onFailure { t -> Logger.e(TAG, t) { "web guard vpn stop failed on unbind" } }
        // 拉管理台之前先点亮图标：绑定期间 AppHider 已把 MainActivity 置为 disabled，
        // observeBinding 的揭示是另一个协程，存在竞态；不在这里同步揭示，
        // startActivity 可能抛 ActivityNotFoundException 被 runCatching 吞掉，
        // 表现为"解绑后停在桌面、不进绑定页"。
        runCatching { appHider.revealManagementUi() }
            .onFailure { t -> Logger.w(TAG, t) { "reveal management ui before unbind navigation failed" } }
        withContext(Dispatchers.Main) {
            runCatching {
                startActivity(
                    Intent(this@GuardService, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }
                )
            }
        }
        // AB 锁必须在 stopSelf 之前解除：有绑定的服务 stopSelf 不生效，
        // 不解绑会留下一个"已解绑但仍在跑"的守护服务（外加常驻通知）——
        // 那等于退出管控失败，既是隐私问题也会让家长以为解绑没生效。
        GuardLink.releaseAll(this)
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

        /**
         * 兜底拉取连续失败多少轮后放弃本批（见 [stuckPullRounds]）。
         * 指令轮询周期约 3s，20 轮 ≈ 1 分钟 —— 足够覆盖一次短暂断网/服务端抖动，
         * 又不至于让一条永久失败的指令无限期挡住后续指令。
         */
        private const val MAX_STUCK_PULL_ROUNDS = 20

        /**
         * 游标"落在未来"的容忍窗口。
         *
         * 游标写入用的是服务端校准时间，但进程刚启动、时间尚未同步时 `timeProvider.now()`
         * 会短暂退化为本地墙钟。容忍 5 分钟既能覆盖这个窗口，又能识别出"本地时钟被拨快
         * 数小时"这类真异常并归零重拉。
         */
        private const val COMMAND_CURSOR_FUTURE_TOLERANCE_MS = 5 * 60 * 1000L

        /** 临时解锁申请本地保留天数，到期随上报周期安全销毁 */
        private const val UNLOCK_REQUEST_RETENTION_DAYS = 30

        /**
         * 护眼休息上报队列上限：超出丢弃最旧的，防止异常情况下无限堆积。
         * 对应 [eyeCareRestQueue] 的容量保护，原声明于被合并删除的私有 companion（Kotlin 不允许重复 companion）。
         */
        private const val MAX_EYE_CARE_REST_REPORTS = 32

        /**
         * 单轮台账变化最多推几条告警（R-P3-10）。
         *
         * 换机迁移 / 批量恢复会在一次同步里多出几十个应用；全量推成告警会把家长端
         * 消息流刷爆、把真正重要的告警挤掉。超出的只留在本地日志里。
         */
        private const val MAX_INVENTORY_EVENTS_PER_CYCLE = 10

        private const val CHANNEL_ID = "padguard_guard"
        private const val NOTIFICATION_ID = 1001

        /**
         * 「计时开始」确认浮层的重评估节拍（10 秒）。
         *
         * 状态流只在状态变化时发射，孩子一直不点确认、亮灭屏切换时就再也不会有下一次发射；
         * 只靠 collect 的实现在这种情况下不会重新展示/收起浮层。用低频心跳保证兜底一定触发，
         * 代价是每小时 360 次空判断（无 IO、无唤醒锁）。
         */
        private const val USAGE_CONFIRM_TICK_MS = 10_000L

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
        /** 双进程守护进程发现主进程心跳陈旧时拉起（见 GuardDaemonService） */
        const val REASON_DAEMON = "daemon"
        /** 闹钟兜底通道发现心跳陈旧时拉起（见 GuardAlarmReceiver） */
        const val REASON_ALARM = "alarm"
        /**
         * 由 `:daemon` 的 binder 绑定唤起（AB 锁）。
         *
         * 这条路径**不会**经过 onStartCommand，bootstrap 由 [wakeFromBinder] 直接触发。
         * 语义与 [REASON_DAEMON] 相同（都是"被发现死了、重新拉起"），
         * 单独列出来是为了让日志能区分"start 拉起"与"bind 拉起"，
         * 排查"清理后到底靠哪条通道恢复的"时一眼可辨。
         */
        const val REASON_BIND = "bind"
        /** 授权状态变化后只刷新通知，不重放策略（见 refreshPermissionNotice） */
        const val REASON_PERMISSION_CHANGED = "permissionChanged"
        /**
         * 系统时间/时区/日期变化广播拉起（见 DayRolloverReceiver）：
         * 立即做一次日切对账（checkDayRollover + 会话刷新），
         * 不等下一个 15s tick —— 广播只是加速器，正确性由 tick 兜底。
         */
        const val REASON_DAY_ROLLOVER = "dayRollover"
        /**
         * 进程被系统回收后，由 Application / 锁屏页等**前台或进程入口**幂等补拉守护
         * （见 PadGuardApplication.ensureGuardAfterProcessRestart）。与后台 Receiver / Job 的
         * 拉起不同，这条路径不依赖系统调度通道是否还登记、也不受后台启动前台服务限制。
         */
        const val REASON_PROCESS_RESTART = "processRestart"

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

        /**
         * 任务被划掉后重启服务的**兜底**延迟（见 [onTaskRemoved]）。
         * 主路径是同步立即重启（IPC 不依赖本进程存活）；此值仅用于在个别 ROM 对
         * "当口同步启动"暂时拒绝时补一次，刻意压得很短——若本进程已被 OEM 清理回收，
         * 这次延迟不会再执行，但主路径的 IPC 已经发出，不影响恢复。
         */
        private const val TASK_REMOVED_RESTART_FALLBACK_MS = 500L

        /**
         * 授权回退周期自检间隔。
         * 守护服务长期存活但 App 未打开时，靠这条独立循环发现授权掉失并拉回引导；
         * 30 分钟足够及时，又不至于频繁占用 CPU / 电量。
         */
        private const val REAUTH_CHECK_INTERVAL_MS = 30 * 60 * 1000L

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
         *
         * 这条循环现在**同时是服务端的"存活信号源"**：服务端把每次已鉴权的 HTTP 请求
         * 都当作心跳等价信号（见服务端 DevicePresenceRegistry），据此做秒级上下线判定。
         * 因此这个间隔就是家长端"设备离线"的检测粒度 —— 不要随手调大。
         */
        private const val COMMAND_PULL_INTERVAL_MS = 3_000L

        /**
         * 离线时的拉取间隔（放慢，避免断网期间高频失败刷日志）。
         *
         * 为什么是 5s 而不是更长：`online` 标记取自 MQTT 连接态，MQTT 掉线但 HTTP 正常时
         * 会落到这条慢速分支。服务端把每次已鉴权 HTTP 请求当作存活信号，并以约 5.4s
         * 无信号判离线；若这里仍是 10s，设备会被判离线、下一次拉取又拉回在线，
         * 家长端就会看到"离线/在线"反复横跳。5s 保证降级态下存活信号仍然连续。
         */
        private const val COMMAND_PULL_IDLE_MS = 5_000L

        /** 策略自检间隔：把被绕过的限制重新压回来 */
        private const val SELF_CHECK_INTERVAL_MS = 5 * 60_000L

        /**
         * A1-P0：Web 过滤隧道看门狗间隔（30s）。
         * 把"策略要求过滤但隧道没在跑"的空窗从 ≤5 分钟（策略自检）压到 ≤30 秒。
         * 刻意短于 5 分钟自检——隧道静默失效越早重建，无过滤窗口越短。
         */
        private const val WEB_GUARD_WATCHDOG_MS = 30_000L

        /**
         * A1-P0：WEB_GUARD_DOWN 告警节流窗（10 分钟）。
         * 后台 FGS 限制下隧道可能长时间起不来，若每 30s 都报一次会淹没
         * UNINSTALL_ATTEMPT / CONTROL_LOST 等真正紧急的告警；10 分钟内只报一次足够让家长知情。
         */
        private const val WEB_GUARD_DOWN_ALARM_THROTTLE_MS = 10 * 60_000L

        /** 篡改自检间隔 */
        private const val TAMPER_SCAN_INTERVAL_MS = 60_000L

        /**
         * 受信时间护栏快照的本地落盘间隔：1 分钟。
         * 与心跳解耦，纯离线也写；SP 的单值写入很轻，再密只会徒增待机 I/O。
         * 最坏情况是进程被强杀丢掉最近 1 分钟，恢复时由单调/墙钟流逝量补回，无语义损失。
         */
        private const val TIME_PERSIST_INTERVAL_MS = 60_000L
        /** 运行期权限自愈周期：5 分钟一轮，足够及时收复被系统自动回收的权限，又不至于刷屏 */
        private const val PERM_HEAL_INTERVAL_MS = 5 * 60_000L

        /**
         * a11y 观察期（未启用/未连上的容忍窗计时中）的权限自检快节奏。
         *
         * 90 秒观察窗 + 2 分钟快检 = 确认必然落在下一拍（2min ≥ 90s），家长端高危告警与
         * 设备端全屏引导页从最坏 ~6.5 分钟（5 分钟周期 + 90 秒窗）压到 ~2 分钟出头。
         * 仅观察期临时生效，恢复健康即回 [PERM_HEAL_INTERVAL_MS]；期间多跑的
         * healRuntimePermissions / 特殊权限快检均为幂等只读检查，开销可忽略。
         */
        private const val CAP_HEAL_FAST_INTERVAL_MS = 2 * 60_000L
        /**
         * 自身常驻加固周期自愈间隔：5 分钟一轮，与策略自检同量级。
         * 把 DO/PO 下的"强行停止置灰 / 卸载封锁 / 防恢复出厂"等系统级限制重新压回，
         * 对抗部分 ROM 在重启 / 省电策略下悄悄重置用户限制；操作均为幂等 no-op，开销可忽略。
         */
        private const val SELF_PROTECT_INTERVAL_MS = 5 * 60_000L
        /**
         * 卸载防护就绪度自检间隔。
         *
         * 取 10 分钟：家长在设置里停用设备管理员、或 ROM 把无障碍服务关掉这类事件
         * 没有广播可监听，只能靠轮询发现；再密就是无谓的 binder 调用（每次都要问 DPM）。
         */
        private const val UNINSTALL_SHIELD_CHECK_INTERVAL_MS = 10 * 60_000L
        /** 看屏中无障碍主路看门狗节拍：会话活跃时每 10s 静默核验并自愈一次 */
        private const val A11Y_LIVE_HEAL_INTERVAL_MS = 10_000L

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
         * DO 身份变更广播。
         *
         * 系统常量 `DevicePolicyManager.ACTION_DEVICE_OWNER_CHANGED` 未暴露在公开 SDK 中
         * （属于 @SystemApi），这里用字面量；action 值是 AOSP 稳定契约，不会随版本变化。
         */
        private const val ACTION_DEVICE_OWNER_CHANGED = "android.app.action.DEVICE_OWNER_CHANGED"

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

        /**
         * 装机授权 / 设备管理员 / 无障碍 / 操作密码等状态办妥后，立刻刷新常驻通知。
         *
         * 借一次 onStartCommand → enterForeground() 用**实时**防护状态重建正文，
         * 不必苦等 10 分钟周期自检，也不必等主循环下一拍（15s）。
         *
         * 这里**刻意不动**屏幕采集引导通知：那条通知只有在"授权确已就位"时才该撤
         * （见 [CaptureConsentPrompter.dispatch] 的已授权分支、授权页结果回调，以及
         * 服务冷启动时 [bootstrap] 对跨进程僵尸通知的清理）。无条件清会把一条正等
         * 孩子点击的有效引导误撤，家长端就一直停在"连接中"。
         *
         * 服务没在跑时不主动冷启动守护服务：那时既没有常驻通知，也不该为一个通知刷新
         * 连带拉起整条策略/网络链路。真需要时守护服务自会按既有保活机制启动。
         */
        fun refreshPermissionNotice(context: Context) {
            if (isRunning) start(context, REASON_PERMISSION_CHANGED)
        }
    }
}

/**
 * 守护链路状态四态（纯函数，由单测锁定）。
 *
 * @param transportUp 传输通道在线：MQTT 已连 或 HTTPS 轮询健康（[com.padguard.core.transport.RealRemoteDataSource.isOnline]）
 * @param httpUp      HTTPS 骨干可达：[GuardService.httpReachable]，心跳往返成功即置 true
 * @return 上送给服务端的 [com.padguard.core.data.model.Event.onlineState]
 *
 * 四态语义（直接服务"孩子端永远不会掉线"的可观测性）：
 * - ONLINE            ：双通道都在，监管最强；
 * - MQTT_DOWN_HTTP_OK ：MQTT 断了但 HTTPS 拉取循环仍工作 —— **设备仍在监管下**，家长端不应误报离线；
 * - HTTP_DOWN         ：传输通道报"在线"但 HTTPS 骨干连不上（服务端 HTTPS 端点异常），仍能收 MQTT 推送；
 * - OFFLINE           ：双通道皆断，设备真正失联。
 *
 * 不变量：只要 [httpUp] 为真（HTTPS 拉取循环在跑），就绝不返回 OFFLINE —— 这是"假在线 /
 * 单通道降级"与"真离线"的分水岭，避免把仍在监管的设备误判为脱管。
 */
fun computeDaemonOnlineState(transportUp: Boolean, httpUp: Boolean): String = when {
    transportUp && httpUp -> "ONLINE"
    transportUp && !httpUp -> "HTTP_DOWN"
    !transportUp && httpUp -> "MQTT_DOWN_HTTP_OK"
    else -> "OFFLINE"
}
