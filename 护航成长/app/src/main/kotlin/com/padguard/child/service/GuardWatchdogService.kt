package com.padguard.child.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import com.padguard.child.receiver.GuardAlarmReceiver
import com.padguard.core.common.Logger

/**
 * 守护服务看门狗：周期检查 [GuardService] 是否还活着，死了就拉起来。
 *
 * ## 为什么需要它
 * START_STICKY 只是"系统回收后尽量重建"，并不保证：
 * - 多数国产 ROM 会在灭屏 / 内存紧张时直接杀掉后台服务，且**不会**按 STICKY 重建；
 * - 孩子（或家长清理工具）手动杀进程后同样不会重建。
 * 服务一旦没了，命令轮询与 MQTT 全部停摆：家长端看到的设备还"在线"，
 * 锁屏/截屏/看屏指令全部石沉大海 —— 这就是"装了管控却管不住"。
 *
 * ## 为什么用 JobScheduler 而不是自己起轮询线程
 * 在应用自己的进程里起线程做"拉活"，进程一死连线程一起没，等于什么都没做。
 * JobScheduler 由系统进程调度，应用进程不在时照样回调，这是唯一靠谱的拉活通道。
 *
 * ## 已知边界（如实记录，不要误以为万能）
 * Android 12+ 禁止后台启动前台服务，从本回调里 [GuardService.start] 可能抛
 * ForegroundServiceStartNotAllowedException（部分 ROM 不限制）。
 * 这里捕获并记录，不做掩盖：至少在 Android 11 及以下、以及宽松 ROM 上能有效自愈。
 */
class GuardWatchdogService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        val bound = GuardHeartbeat.readGuardState(this)
        if (GuardService.isRunning) {
            Logger.v(TAG) { "watchdog: guard service alive" }
        } else if (bound) {
            Logger.w(TAG) { "watchdog: guard service NOT running, restarting" }
            runCatching { GuardService.start(this, GuardService.REASON_WATCHDOG) }
                .onFailure { Logger.e(TAG, it) { "watchdog restart failed" } }
        } else {
            Logger.v(TAG) { "watchdog: guard not running but device not bound, skip" }
        }
        // 双进程互保的另一半：守护进程（:daemon）心跳陈旧且已绑定 → 拉起。
        // 它自身也是 START_STICKY + 开机广播保活，这里是第三层。
        val daemonAge = GuardHeartbeat.readDaemonAliveAgeMs(this)
        if (bound && (daemonAge == null || daemonAge > GuardHeartbeat.DAEMON_STALE_MS)) {
            Logger.w(TAG) { "watchdog: daemon NOT alive (age=$daemonAge), restarting" }
            GuardDaemonService.start(this)
        }
        // 顺带排上闹钟兜底通道（与 JobScheduler 并行的第二条系统调度路径，幂等）
        if (bound) GuardAlarmReceiver.schedule(this)
        // 自调度下一次探测：用「一次性任务 + 自唤起」替代 setPeriodic，
        // 绕开 JobScheduler 对周期任务最低 15 分钟的硬性下限（见 schedule 注释），
        // 把进程被杀后的恢复窗口从 ≤15 分钟压到 ≤ WATCHDOG_DEADLINE_MS。
        runCatching { schedule(this) }
            .onFailure { Logger.e(TAG, it) { "watchdog reschedule failed" } }
        // 本次工作已同步完成，不需要持有 wakelock
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    companion object {
        private const val TAG = "GuardWatchdog"
        private const val JOB_ID = 2026_1007

        /**
         * 看门狗探测节奏。
         *
         * ## 关键修正：弃用 setPeriodic
         * 早期用 `setPeriodic(5min)` 设定周期，但 Android 对所有周期任务有**最低 15 分钟**
         * 的硬性下限（[JobInfo.getMinPeriodMillis]），传入 5 分钟会被系统**静默钳制为 15 分钟**。
         * 后果：守护进程被杀后，最长要等 15 分钟才被拉起 —— 这正是「孩子端掉线很久才恢复 /
         * 守护进程没生效」的根因之一（离线期间指令轮询退化为慢速且 pull 必然失败）。
         *
         * 改用「一次性任务 + 自唤起」：每次作业执行完后由 [onStartJob] 再排下一跳，
         * 最低延迟可取 60s、最迟 90s（[setOverrideDeadline] 保证即使空闲也按时跑）。
         * 这样把恢复窗口从 ≤15 分钟压到 ≤90 秒，且不触发国产 ROM 对高频周期任务的限流。
         *
         * 说明：看门狗只在「应用进程已死、但系统仍调度 JobScheduler」时起作用（被设置强制停止
         * 进入停止态时它同样失效，这是 Android 安全模型，应用层绕不过，唯一正解是 Device Owner）。
         * 更常见的「后台清理 / 灭屏被杀」已由 [GuardService] 的屏幕亮起/网络恢复动态广播在秒级自愈，
         * 本机制作为最后兜底，把最坏恢复时间从 15 分钟降到约 1 分钟。
         */
        private val WATCHDOG_LATENCY_MS = java.util.concurrent.TimeUnit.SECONDS.toMillis(60)
        private val WATCHDOG_DEADLINE_MS = java.util.concurrent.TimeUnit.SECONDS.toMillis(90)

        /** 幂等注册：已排过同 ID 的作业（尚未触发）就不再重复排（系统会自动续期）。 */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
                ?: return
            val existing = runCatching { scheduler.getPendingJob(JOB_ID) }.getOrNull()
            if (existing != null) return

            // 一次性任务：setMinimumLatency 不受 15 分钟下限约束；setOverrideDeadline 兜底保证最坏 90s 必跑。
            // setPersisted 让作业在设备重启后依然生效，不依赖开机广播那一跳。
            val info = JobInfo.Builder(JOB_ID, ComponentName(context, GuardWatchdogService::class.java))
                .setMinimumLatency(WATCHDOG_LATENCY_MS)
                .setOverrideDeadline(WATCHDOG_DEADLINE_MS)
                .setPersisted(true)
                .build()
            runCatching { scheduler.schedule(info) }
                .onSuccess { Logger.i(TAG) { "watchdog scheduled (self-rescheduling one-shot, ~${WATCHDOG_DEADLINE_MS / 1000}s)" } }
                .onFailure { Logger.e(TAG, it) { "watchdog schedule failed" } }
        }

        /** 仅用于排障：确认作业是否真的排上了（国产 ROM 上常被限流）。 */
        fun isScheduled(context: Context): Boolean {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
                ?: return false
            return runCatching { scheduler.getPendingJob(JOB_ID) != null }.getOrDefault(false)
        }

        /** API 26+ 才有 getPendingJob；低版本保守返回 true 以免重复排程被打断。 */
        fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
    }
}
