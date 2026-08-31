package com.padguard.child.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.padguard.child.MainActivity
import com.padguard.child.R
import com.padguard.child.monitor.DeviceSnapshotCollector
import com.padguard.child.monitor.ForegroundAppMonitor
import com.padguard.child.ui.block.AppBlockActivity
import com.padguard.child.ui.lock.LockScreenActivity
import com.padguard.child.ui.message.MessageActivity
import com.padguard.core.common.Logger
import com.padguard.core.common.TimeProvider
import com.padguard.core.data.model.Heartbeat
import com.padguard.core.data.model.LogType
import com.padguard.core.data.repository.AuthRepository
import com.padguard.core.data.repository.LogRepository
import com.padguard.core.data.repository.PolicyRepository
import com.padguard.core.data.repository.UnlockRequestRepository
import com.padguard.core.engine.PolicyEngine
import com.padguard.core.engine.admin.DeviceAdminBridge
import com.padguard.core.engine.command.EngineEffect
import com.padguard.core.engine.enforcer.LimitVerdict
import com.padguard.core.transport.Downlink
import com.padguard.core.transport.RemoteDataSource
import com.padguard.core.transport.TransportSettings
import com.padguard.core.transport.http.ApiResult
import com.padguard.core.transport.http.PolicyFetchResult
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    @Inject lateinit var timeProvider: TimeProvider
    @Inject lateinit var unlockRequestRepository: UnlockRequestRepository

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

    private val transportStarted = AtomicBoolean(false)
    private val bootstrapped = AtomicBoolean(false)

    /** 锁屏页当前是否已被拉起，避免每轮 tick 重复 startActivity 造成闪屏 */
    private var lockScreenShown = false
    private var lastBlockedPackage: String? = null

    // 通知栏展示用的运行时状态
    @Volatile private var online = false
    @Volatile private var lastHeartbeatOk = false

    override fun onCreate() {
        super.onCreate()
        // 必须第一时间进入前台：Android 对 startForegroundService 后的宽限期只有 5 秒，
        // 超时直接 ForegroundServiceDidNotStartInTimeException 崩溃。
        // 所以先挂一个"启动中"的通知，真实状态等拿到数据后再刷。
        enterForeground()
        Logger.i(TAG) { "GuardService created, controlMode=${admin.refreshControlMode()}" }
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
        } else {
            // 已在运行，外部只是想催一次策略对齐（如管理器刚激活、Kiosk 被意外退出）
            scope.launch { runSafely("applyOnKick") { policyEngine.applyCurrent(force = true) } }
        }

        // START_STICKY：被系统回收后自动重建。
        // 注意重建时 intent 为 null，所以所有初始化逻辑都不能依赖 intent 里的参数。
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Logger.w(TAG) { "GuardService destroyed" }
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
        scope.launch {
            runSafely("applyCachedPolicy") {
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
        startMainLoop()
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
                    Logger.w(TAG) { "device not bound yet, transport stays offline" }
                    return@collect
                }
                startTransport()
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

    // ==================== 主循环 ====================

    private fun startMainLoop() {
        mainLoop?.cancel()
        mainLoop = scope.launch {
            var nextHeartbeat = 0L
            var nextSelfCheck = SystemClock.elapsedRealtime() + SELF_CHECK_INTERVAL_MS
            var nextTamperScan = SystemClock.elapsedRealtime() + TAMPER_SCAN_INTERVAL_MS
            var nextLogUpload = SystemClock.elapsedRealtime() + LOG_UPLOAD_MIN_INTERVAL_MS

            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val monitoring = policyEngine.monitoring()

                // ---------- 每轮必做：采样 + 锁定判定 ----------
                runSafely("tick") { doTick(collectForegroundApp = monitoring.collectAppUsage) }

                // ---------- 心跳 ----------
                if (now >= nextHeartbeat) {
                    runSafely("heartbeat") { sendHeartbeat() }
                    nextHeartbeat = now + monitoring.heartbeatIntervalMs.coerceAtLeast(MIN_TICK_MS)
                }

                // ---------- 策略自检重压 ----------
                if (now >= nextSelfCheck) {
                    // force = true：这里的目的就是把"被用户绕过去的限制"重新压回来，
                    // 版本没变也必须重下，否则自检等于空转。
                    runSafely("selfCheck") { policyEngine.applyCurrent(force = true) }
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

                updateNotification()
                delay(MIN_TICK_MS)
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
        val tick = policyEngine.tick(foregroundPackage = fg, screenOn = screenOn)

        // 息屏时不弹任何界面：此刻拉起 Activity 只会点亮屏幕、白耗电，
        // 而且部分 ROM 会把它记成"后台弹窗"从而限制我们后续的启动权限。
        if (!screenOn) return

        reconcileLockScreen(tick.shouldShowLockScreen, tick.lockState.primaryDetail)
        reconcileAppBlock(
            blockedPackage = tick.blockedPackage,
            foreground = fg,
            // 把限额判定的具体原因一并带给拦截页。只说"被限制"会让孩子以为是故障并反复重试，
            // 说清是"时长用完"还是"打开次数超了"才能形成预期。
            reason = (tick.limit as? LimitVerdict.Blocked)?.message().orEmpty()
        )
    }

    /** 锁屏页只在"状态翻转"时操作，避免每 15s 重复 startActivity 导致画面闪烁 */
    private fun reconcileLockScreen(shouldLock: Boolean, detail: String) {
        if (shouldLock && !lockScreenShown) {
            lockScreenShown = true
            LockScreenActivity.show(this, detail)
        } else if (!shouldLock && lockScreenShown) {
            lockScreenShown = false
            LockScreenActivity.dismiss(this)
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
                    // 不在这里主动 pullCommands：MQTT 模式下拉取会和推送产生双通道竞争，
                    // 而 transport 层的健康检查本就会在通道异常时降级到轮询。
                    // 这里只留一条可观测线索，便于事后判断"是否发生过推送丢失"。
                    Logger.w(TAG) { "server reports ${ack.pendingCommandCount} pending commands" }
                }
            }
            else -> {
                lastHeartbeatOk = false
                online = remote.isOnline()
                Logger.w(TAG) { "heartbeat failed: $result" }
            }
        }
    }

    /** 日志与告警分开上报：告警是高危信号，不能被大批量普通日志的失败拖住 */
    private suspend fun uploadPending() {
        val deviceId = authRepository.getDeviceId()
        if (deviceId.isBlank()) return

        val alerts = logRepository.takePendingAlerts()
        if (alerts.isNotEmpty()) {
            val r = remote.uploadEvents(deviceId, alerts.map { it.copy(deviceId = deviceId) })
            if (r.isSuccess) logRepository.markAlertsUploaded(alerts)
            else Logger.w(TAG) { "alert upload failed, keep ${alerts.size} pending" }
        }

        val logs = logRepository.takePendingLogs()
        if (logs.isNotEmpty()) {
            val r = remote.uploadLogs(deviceId, logs)
            if (r.isSuccess) logRepository.markLogsUploaded(logs)
            else Logger.w(TAG) { "log upload failed, keep ${logs.size} pending" }
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

    private suspend fun refreshPolicy() {
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
                }
            }
            else -> Logger.w(TAG) { "fetch policy failed: $r" }
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
                LockScreenActivity.show(this, effect.reason)
            }

            EngineEffect.DismissLockScreen -> {
                lockScreenShown = false
                LockScreenActivity.dismiss(this)
            }

            is EngineEffect.ShowMessage -> MessageActivity.show(
                context = this,
                title = effect.title,
                body = effect.body,
                durationSec = effect.durationSec,
                blocking = effect.blocking
            )

            EngineEffect.FlushLogs, EngineEffect.FlushBeforeShutdown -> uploadPending()

            is EngineEffect.RefreshPolicy -> refreshPolicy()

            // ---------- 以下为"受理了但端上目前做不到"的动作 ----------
            // CommandExecutor 回执的 SUCCESS 语义是"任务已受理"，不是"已完成"，
            // 所以真实结果必须由这里如实补一条日志。
            // 若这里什么都不做，管控端会看到 SUCCESS 却永远收不到产物，
            // 那就是最糟的一种失败：看起来成功的失败。
            is EngineEffect.CaptureScreenshot -> reportUnsupported(
                msgId = effect.msgId,
                action = "screenshot",
                reason = "Android 未向 Device Owner 开放静默截屏 API；" +
                    "需接入无障碍服务 takeScreenshot(API 30+) 或 MediaProjection 用户授权，当前版本未启用"
            )

            is EngineEffect.RequestLocation -> reportUnsupported(
                msgId = effect.msgId,
                action = "locate",
                reason = "定位采集模块未在本版本启用（P0 范围外）"
            )

            is EngineEffect.InstallApp -> reportUnsupported(
                msgId = effect.msgId,
                action = "installApp",
                reason = "静默安装需 DO + PackageInstaller 会话，本版本未启用；包名=${effect.packageName}"
            )

            is EngineEffect.UninstallApp -> reportUnsupported(
                msgId = effect.msgId,
                action = "uninstallApp",
                reason = "静默卸载需 DO + PackageInstaller 会话，本版本未启用；包名=${effect.packageName}"
            )

            is EngineEffect.SetWatermark -> reportUnsupported(
                msgId = "",
                action = "setWatermark",
                reason = "全局水印需系统级悬浮窗覆盖，本版本未启用；enabled=${effect.enabled}"
            )

            is EngineEffect.UpdateRuntimeConfig ->
                Logger.i(TAG) { "runtime config from command: ${effect.params.keys}" }
        }
    }

    /** 把"做不到"写进日志流，让管控端能看见降级，而不是以为成功了 */
    private suspend fun reportUnsupported(msgId: String, action: String, reason: String) {
        Logger.w(TAG) { "$action unsupported: $reason" }
        logRepository.append(
            type = LogType.COMMAND_EXEC,
            payload = mapOf(
                "msgId" to msgId,
                "action" to action,
                "result" to "UNSUPPORTED",
                "reason" to reason
            )
        )
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
    private fun fgsType(): Int = when {
        Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        else -> 0
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

    // ==================== 工具 ====================

    /**
     * 统一异常兜底。
     *
     * 主循环里任何一个子任务抛异常都不能让循环退出 —— 循环一停，
     * 服务还在前台、通知还挂着，但采样、心跳、自检全部停摆，
     * 从外部看完全正常，这是最危险的失效形态。
     */
    private suspend inline fun runSafely(tag: String, crossinline block: suspend () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            // CancellationException 必须放行，否则协程取消会被吞掉，服务停不下来
            if (t is kotlinx.coroutines.CancellationException) throw t
            Logger.e(TAG, t) { "task '$tag' failed" }
        }
    }

    companion object {
        private const val TAG = "GuardService"

        /** 临时解锁申请本地保留天数，到期随上报周期安全销毁 */
        private const val UNLOCK_REQUEST_RETENTION_DAYS = 30

        private const val CHANNEL_ID = "padguard_guard"
        private const val NOTIFICATION_ID = 1001

        const val EXTRA_REASON = "reason"
        const val REASON_BOOT = "boot"

        /** 采样基准间隔。15s 是权衡结果：更短会明显增加耗电，更长会让"时段锁"生效延迟到肉眼可见 */
        private const val MIN_TICK_MS = 15_000L

        /** 策略自检间隔：把被绕过的限制重新压回来 */
        private const val SELF_CHECK_INTERVAL_MS = 5 * 60_000L

        /** 篡改自检间隔 */
        private const val TAMPER_SCAN_INTERVAL_MS = 60_000L

        /** 日志上报最小间隔，防止服务端把 logUploadIntervalSec 配成 0 导致刷接口 */
        private const val LOG_UPLOAD_MIN_INTERVAL_MS = 60_000L

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
