package com.padguard.child.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
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
        if (GuardService.isRunning) {
            Logger.v(TAG) { "watchdog: guard service alive" }
        } else {
            Logger.w(TAG) { "watchdog: guard service NOT running, restarting" }
            runCatching { GuardService.start(this, GuardService.REASON_WATCHDOG) }
                .onFailure { Logger.e(TAG, it) { "watchdog restart failed" } }
        }
        // 本次工作已同步完成，不需要持有 wakelock
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    companion object {
        private const val TAG = "GuardWatchdog"
        private const val JOB_ID = 2026_1007

        /**
         * 看门狗周期。5 分钟是权衡结果：太短徒增系统调度与耗电（且国产 ROM 会因此限流），
         * 太长则"设备脱管"的窗口过大 —— 家长最长要等五分钟才恢复控制。
         * 注：看门狗只在「应用进程已死、但系统仍调度 JobScheduler」时起作用（被设置强制停止
         * 进入停止态时它同样失效）。更常见的「后台清理 / 灭屏被杀」已由 [GuardService] 的
         * 屏幕亮起/网络恢复动态广播在秒级内自愈，本周期仅作最后兜底。
         */
        private val INTERVAL_MS = java.util.concurrent.TimeUnit.MINUTES.toMillis(5)

        /** 幂等注册：已排过同 ID 的作业就不再重复排（系统会自动续期）。 */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
                ?: return
            val existing = runCatching { scheduler.getPendingJob(JOB_ID) }.getOrNull()
            if (existing != null) return

            val info = JobInfo.Builder(JOB_ID, ComponentName(context, GuardWatchdogService::class.java))
                .setPeriodic(INTERVAL_MS)
                // 设备重启后自动恢复调度，不需要再依赖开机广播那一跳
                .setPersisted(true)
                .build()
            runCatching { scheduler.schedule(info) }
                .onSuccess { Logger.i(TAG) { "watchdog scheduled" } }
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
