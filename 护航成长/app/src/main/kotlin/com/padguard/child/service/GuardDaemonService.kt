package com.padguard.child.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.padguard.core.common.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 双进程守护服务（独立 `:daemon` 进程）。
 *
 * ## 它解决什么
 * 「划掉孩子端任务 = 杀整个应用进程」时，进程内的一切自愈手段
 * （[android.app.Service.onTaskRemoved] 的延迟重启、START_STICKY、动态广播）
 * 全部随进程死亡 —— 这是「装了管控却管不住 / 划一下就脱管」的最后缺口。
 * 唯一的出路是**另一个进程**：它由系统侧调度通道（开机广播 / JobScheduler /
 * AlarmManager）拉起，存活期间监视主进程心跳，主进程一死就把它拉回来。
 *
 * ## 为什么极轻（不用 Hilt / 不碰数据库）
 * 它必须在主进程被杀后的**空进程状态**下也能被系统拉起并工作；
 * 任何重量初始化（Hilt 图、Room）都会拖慢甚至拖垮这条救命通道。
 * 全部状态判定走 [GuardHeartbeat] 的文件心跳，两个文件读取即可决策。
 *
 * ## 前台化策略（分版本，2026-10-21 华为 AGS6-W10 实测修正）
 * - API < 26：普通 Service。当年既无前台服务概念，也无后台启动限制；
 * - API 26+：提升为 dataSync 类型前台服务，并经 `startForegroundService` 启动。
 *
 *   **为什么必须全版本前台化（实测根因，勿改回普通服务）**：
 *   该机 Android 10 上，从应用上下文（即便本应用正持有前台服务、有可见 Activity、
 *   亮屏）发起**普通服务启动**会被 EMUI iawise **分类拦截**：
 *   `Service starting has been prevented by iaware or trustsbase ... GuardDaemonService`
 *   —— 无异常抛出、无回调，应用侧只能看到"服务没起来"，且 15s 一跳的重试全部被拦。
 *   同一台机器上，主守护服务走 `startForegroundService` 的 FGS 路径从任何上下文
 *   （开机广播 / 看门狗 Job / 闹钟 / 应用内）都能正常启动 —— 唯一差别就是启动路径。
 *   且普通服务形态的 daemon 连 `am kill` 都扛不住（实测被直接杀掉，而主进程因持有
 *   FGS 幸存）。因此 daemon 与主守护保持同一形态：FGS，才能既有启动豁免又是硬杀不动。
 *   代价是一条 IMPORTANCE_MIN 常驻通知（id 1002），与主守护通知（id 1001）同量级。
 *
 *   （dataSync 在 Android 15 有 6h 上限，到期后由主进程 tick 与看门狗再拉起，
 *    见 [GuardService] 主循环的 daemon 存活检查，双向互保不依赖单点。）
 *
 * ## 绑定态门控
 * 未绑定设备**不拉主进程**（没有家长就没有管控依据，与 GuardService 的 boundNow
 * 语义一致），并自停避免空转；设备重新绑定后由 GuardService 再次拉起本服务。
 */
class GuardDaemonService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchJob: Job? = null

    /** 上次尝试拉起主进程的时刻（elapsedRealtime），防止主进程反复崩溃时高频重试 */
    @Volatile
    private var lastRestartAttemptAtMs = 0L

    override fun onCreate() {
        super.onCreate()
        isAlive = true
        // API 26+ 先把自己提成前台服务：既拿"后台启动 FGS"的豁免身份，
        // 又获得 FGS 的进程重要性（am kill / 一般内存压力杀不动）。
        // 必须赶在 startForegroundService 的 5s 宽限内完成 —— onCreate 同步调用。
        promoteToForegroundIfNeeded()
        Logger.i(TAG) { "daemon service created (pid=${android.os.Process.myPid()})" }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logger.i(TAG) { "daemon onStartCommand (flags=$flags)" }
        promoteToForegroundIfNeeded()
        if (watchJob == null) {
            watchJob = scope.launch { watchLoop() }
        }
        // START_STICKY：被系统回收后尽力重建（重建时 intent 为 null，逻辑不依赖 intent）
        return START_STICKY
    }

    /**
     * 监视循环：5s 一跳。
     * 1. 写自己的心跳（让主进程 / 看门狗知道 `:daemon` 还活着）；
     * 2. 检查主进程心跳，陈旧且已绑定 → 拉起 GuardService；
     * 3. 陈旧但未绑定 → 自停（没有管控对象，不起无谓的服务）。
     */
    private suspend fun watchLoop() {
        GuardHeartbeat.writeDaemonAlive(this)
        while (scope.isActive) {
            delay(POLL_INTERVAL_MS)
            GuardHeartbeat.writeDaemonAlive(this)
            val guardAge = GuardHeartbeat.readGuardAliveAgeMs(this)
            val stale = guardAge == null || guardAge > GuardHeartbeat.GUARD_STALE_MS
            if (!stale) continue

            if (!GuardHeartbeat.readGuardState(this)) {
                Logger.i(TAG) { "guard stale but device NOT bound; daemon stops itself" }
                stopSelf()
                return
            }
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastRestartAttemptAtMs < MIN_RESTART_INTERVAL_MS) continue
            lastRestartAttemptAtMs = now
            Logger.w(TAG) { "guard heartbeat stale (age=$guardAge) -> restarting GuardService" }
            runCatching { GuardService.start(this, GuardService.REASON_DAEMON) }
                .onFailure { Logger.e(TAG, it) { "restart GuardService from daemon failed" } }
        }
    }

    /**
     * API 26+ 提升为前台服务（dataSync 类型，manifest 已声明对应权限）。
     *
     * 失败不崩：说明启动身份不够（非豁免上下文），看门狗/闹钟层仍会兜底，
     * 本服务继续以普通服务形态做能力范围内的心跳监视。
     * 注意：经 `startForegroundService` 拉起时，系统要求 5s 内必须
     * startForeground 成功，否则以 ANR 形式杀进程 —— 因此这里同步重试一次，
     * 不把希望寄托在"下一次 onStartCommand"。
     */
    private fun promoteToForegroundIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (runCatching { doPromoteToForeground() }.isSuccess) return
        // 同步重试一次：startForegroundService 的 5s 宽限不等人
        val retry = runCatching { doPromoteToForeground() }
        if (retry.isSuccess) return
        Logger.w(TAG, retry.exceptionOrNull()) {
            "daemon promote to foreground failed, running as plain service"
        }
    }

    private fun doPromoteToForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager != null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "系统守护",
                // IMPORTANCE_MIN：不发声、不上锁屏、不在状态栏常驻图标，
                // 仅在展开的通知栏可见 —— 与孩子端整体隐蔽形态保持一致。
                NotificationManager.IMPORTANCE_MIN
            )
            manager.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("护航守护")
            .setContentText("系统守护运行中")
            .setSmallIcon(applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        ServiceCompat2.startForeground(this, NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        isAlive = false
        watchJob?.cancel()
        watchJob = null
        scope.cancel()
        Logger.w(TAG) { "daemon service destroyed" }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "GuardDaemon"

        /** 监视节奏：5s。两次文件读取的开销可忽略，换来的是最坏 ~40s 的主进程恢复窗口 */
        private const val POLL_INTERVAL_MS = 5_000L

        /** 两次拉起主进程的最小间隔：防主进程连环崩溃时高频重试雪上加霜 */
        private const val MIN_RESTART_INTERVAL_MS = 20_000L

        private const val CHANNEL_ID = "padguard_daemon"
        private const val NOTIFICATION_ID = 1002

        /**
         * 守护进程是否在运行（本进程内有效）。
         * 跨进程判定一律以 [GuardHeartbeat.readDaemonAliveAgeMs] 心跳文件为准 ——
         * 静态标志在进程被杀时随之消失，语义正好是"进程不在"。
         */
        @Volatile
        var isAlive: Boolean = false
            private set

        /**
         * 启动守护进程（幂等，可重复调用）。
         *
         * API 26+ 走 `startForegroundService`：服务在 onStartCommand 里同步
         * startForeground（见 [promoteToForegroundIfNeeded]）。**这是华为 EMUI 上
         * 唯一能稳定启动 daemon 的路径** —— 实测普通 startService 会被 iawise
         * 分类拦截（"Service starting has been prevented by iaware or trustsbase"，
         * 无异常无回调），而同应用的 FGS 路径从任何上下文都能通过。
         * 调用方（GuardService/看门狗/开机广播/闹钟）均处于前台或系统豁免上下文；
         * 非豁免上下文下会被系统拒绝，失败仅记日志不抛异常，由看门狗层兜底。
         */
        fun start(context: Context) {
            val intent = Intent(context, GuardDaemonService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Logger.e(TAG, it) { "start GuardDaemonService failed" } }
        }
    }
}

/**
 * [android.app.Service.startForeground] 的版本兼容小包装：
 * API 29+ 需要带类型参数（本服务用 dataSync，manifest 已声明权限）。
 */
private object ServiceCompat2 {
    fun startForeground(service: Service, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            service.startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            service.startForeground(id, notification)
        }
    }
}
