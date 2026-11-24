package com.padguard.child.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.padguard.child.service.GuardDaemonService
import com.padguard.child.service.GuardHeartbeat
import com.padguard.child.service.GuardLink
import com.padguard.child.service.GuardService
import com.padguard.core.common.Logger

/**
 * 闹钟兜底接收器：与 JobScheduler 看门狗**并行的第二条系统调度通道**。
 *
 * ## 为什么要两条系统通道
 * 部分国产 ROM 会限制 JobScheduler 的投递（尤其"一次性任务 + 自调度"形态），
 * 而 JobScheduler 对周期任务又有 15 分钟硬性下限。AlarmManager 的
 * `setWindow`（非精确、可批处理）在多数 ROM 上投递更稳定，且不受 15 分钟钳制。
 * 两条通道互为备份：任一被 ROM 限制，另一条仍能在约一分钟量级把守护拉回。
 *
 * ## 边界（如实声明）
 * Android 12+ 禁止从后台启动前台服务，而本接收器由闹钟唤醒（非豁免上下文），
 * 此时拉起 GuardService 可能被系统拒绝（失败仅记日志，由看门狗/双进程层兜底）。
 * Android 11 及以下、以及宽松 ROM 上本通道完全有效。
 */
class GuardAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION -> handlePeriodic(context)
            ACTION_IMMEDIATE -> handleImmediate(context)
            else -> return
        }
    }

    /**
     * 周期探测（与 JobScheduler 看门狗并行的第二条系统通道）。
     * 仅当家护心跳陈旧时才拉起；未绑定设备不拉（无管控依据）。
     */
    private fun handlePeriodic(context: Context) {
        val bound = GuardHeartbeat.readGuardState(context)

        // 主进程心跳陈旧且已绑定 → 拉起
        val guardAge = GuardHeartbeat.readGuardAliveAgeMs(context)
        if (bound && (guardAge == null || guardAge > GuardHeartbeat.GUARD_STALE_MS)) {
            Logger.w(TAG) { "alarm: guard heartbeat stale (age=$guardAge) -> restarting" }
            // AB 锁优先：本接收器跑在主进程内，bind 自己进程的服务会创建 Service 实例
            // （onCreate + onBind），不受"后台启动前台服务"限制 —— 这正是清理后
            // 闹钟回调能真正把管控拉起来的关键。绑定被拒时才退回 startForegroundService。
            if (!GuardLink.wakeGuard(context)) {
                runCatching { GuardService.start(context, GuardService.REASON_ALARM) }
                    .onFailure { Logger.e(TAG, it) { "alarm restart guard failed" } }
            }
        }

        // 守护进程心跳陈旧且已绑定 → 拉起
        val daemonAge = GuardHeartbeat.readDaemonAliveAgeMs(context)
        if (bound && (daemonAge == null || daemonAge > GuardHeartbeat.DAEMON_STALE_MS)) {
            Logger.w(TAG) { "alarm: daemon heartbeat stale (age=$daemonAge) -> restarting" }
            if (!GuardLink.wakeDaemon(context)) GuardDaemonService.start(context)
        }

        // 自续期：已绑定才继续排班，未绑定时停机不空转（重新绑定由 GuardService 排班）
        if (bound) {
            schedule(context)
        } else {
            cancel(context)
        }
    }

    /**
     * 即时兜底复活（onTaskRemoved 备援通道）。
     *
     * ## 为什么需要它
     * [GuardService.onTaskRemoved] 在任务被划掉/被清理工具杀掉时会同步调用
     * `startForegroundService` 立即重启。但 EMUI 等国产 ROM 的「清理历史应用并释放内存」
     * 是**整包 kill**（killBackgroundProcesses / forceStopPackage），往往连那个 IPC 一并吞掉，
     * 或本进程在 IPC 落地前已被回收 —— 于是"立即自启"根本没机会执行，守护永久失联。
     *
     * 这里由 system_server 持有闹钟、与进程生死无关：即便本进程已被回收，
     * 闹钟仍会准时触发并`无条件`拉起守卫（已运行则跳过，避免冗余）。
     * 仅 killBackgroundProcesses（非 force-stop）场景有效；force-stop 由无障碍服务重绑兜底。
     */
    private fun handleImmediate(context: Context) {
        // 守卫还活着 → 无需复活（避免 onTaskRemoved 的立即自启成功后又被闹钟冗余重启）
        if (GuardService.isRunning) return
        // 未绑定设备不拉起（没有家长就没有管控依据，与全链路语义一致）
        if (!GuardHeartbeat.readGuardState(context)) return
        Logger.w(TAG) { "immediate alarm: guard not running after task-removed/clear -> restarting" }
        // AB 锁优先：绑定方式能把 Service 实例真正创建出来（且不受后台启动 FGS 限制），
        // 这是"清理后 ~1.2s 闹钟把管控拉回"最可靠的一条路。
        if (!GuardLink.wakeGuard(context)) {
            runCatching { GuardService.start(context, GuardService.REASON_ALARM) }
                .onFailure { Logger.e(TAG, it) { "immediate alarm restart guard failed" } }
        }
    }

    companion object {
        private const val TAG = "GuardAlarm"
        const val ACTION = "com.padguard.child.action.GUARD_ALARM"
        const val ACTION_IMMEDIATE = "com.padguard.child.action.GUARD_ALARM_IMMEDIATE"

        private const val REQUEST_CODE = 20260
        private const val REQUEST_CODE_IMMEDIATE = 20261

        /**
         * 排下一次探测。用 `setWindow`（非精确闹钟，系统可批处理）而非
         * `setExactAndAllowWhileIdle`：保活探测不需要精确时刻，批处理更省电，
         * 也避免 SCHEDULE_EXACT_ALARM 在 Android 12+ 被用户撤销后整条通道失效。
         *
         * ## 时钟域：相对间隔必须挂在单调时钟上
         * 这里是"从现在起 INTERVAL_MS 之后"的**相对**排班，与任何绝对时间轴无关。
         * 早期用 `System.currentTimeMillis() + INTERVAL` 配 RTC_WAKEUP（裸墙钟）：
         * 服务活着时主循环每 tick 会重排，跳变影响有限；但**服务已被杀、正要靠这条
         * 闹钟拉起**时（恰恰是它存在的意义），孩子把系统时间回拨 2 小时，
         * 已排的闹钟就跟着推迟 2 小时 —— 管控中断两小时且无人重排。
         * 改用 `elapsedRealtime()` + ELAPSED_REALTIME_WAKEUP 后，改时间 / NTP 校时
         * 都不会挪动触发点（与时段边界闹钟同一口径，见 BoundaryAlarmClock）。
         */
        fun schedule(context: Context) {
            val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val triggerAt = SystemClock.elapsedRealtime() + INTERVAL_MS
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    manager.setWindow(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        WINDOW_MS,
                        pendingIntent(context)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    manager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent(context))
                }
            }.onFailure { Logger.e(TAG, it) { "alarm schedule failed" } }
        }

        /** 取消排班（解绑 / 退出管控时调用，停止无谓唤醒） */
        fun cancel(context: Context) {
            val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            runCatching { manager.cancel(pendingIntent(context)) }
                .onFailure { Logger.w(TAG, it) { "alarm cancel failed" } }
        }

        private fun pendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, GuardAlarmReceiver::class.java).apply {
                action = ACTION
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
        }

        /**
         * 即时兜底排班（onTaskRemoved 调用）。
         *
         * 用 `setWindow`（非精确闹钟）而非 `setExactAndAllowWhileIdle`：
         * 复用周期通道已验证的"非精确、避免 SCHEDULE_EXACT_ALARM 权限问题"口径，
         * 同时把目标定在 ~1.2s、窗口 2s，使清理工具杀进程后约 1~3 秒内必被拉起。
         * 该闹钟由系统持有，进程被杀不影响其触发。
         */
        fun scheduleImmediate(context: Context) {
            val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val triggerAt = SystemClock.elapsedRealtime() + IMMEDIATE_DELAY_MS
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    manager.setWindow(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        IMMEDIATE_WINDOW_MS,
                        pendingIntentImmediate(context)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    manager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntentImmediate(context))
                }
            }.onFailure { Logger.e(TAG, it) { "immediate alarm schedule failed" } }
        }

        private fun pendingIntentImmediate(context: Context): PendingIntent {
            val intent = Intent(context, GuardAlarmReceiver::class.java).apply {
                action = ACTION_IMMEDIATE
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            return PendingIntent.getBroadcast(context, REQUEST_CODE_IMMEDIATE, intent, flags)
        }

        /** 探测间隔：60s。非精确闹钟下系统会合并唤醒，实际耗电远低于字面值 */
        private const val INTERVAL_MS = 60_000L

        /** 投递窗口：60s。窗口内系统可自由选择时刻批处理 */
        private const val WINDOW_MS = 60_000L

        /** 即时兜底：目标 ~1.2s 后触发 */
        private const val IMMEDIATE_DELAY_MS = 1_200L

        /** 即时兜底：触发窗口 2s（实际落点在 1.2s~3.2s 之间） */
        private const val IMMEDIATE_WINDOW_MS = 2_000L
    }
}
