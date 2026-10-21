package com.padguard.child.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.padguard.child.service.GuardDaemonService
import com.padguard.child.service.GuardHeartbeat
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
        if (intent?.action != ACTION) return

        val bound = GuardHeartbeat.readGuardState(context)

        // 主进程心跳陈旧且已绑定 → 拉起
        val guardAge = GuardHeartbeat.readGuardAliveAgeMs(context)
        if (bound && (guardAge == null || guardAge > GuardHeartbeat.GUARD_STALE_MS)) {
            Logger.w(TAG) { "alarm: guard heartbeat stale (age=$guardAge) -> restarting" }
            runCatching { GuardService.start(context, GuardService.REASON_ALARM) }
                .onFailure { Logger.e(TAG, it) { "alarm restart guard failed" } }
        }

        // 守护进程心跳陈旧且已绑定 → 拉起
        val daemonAge = GuardHeartbeat.readDaemonAliveAgeMs(context)
        if (bound && (daemonAge == null || daemonAge > GuardHeartbeat.DAEMON_STALE_MS)) {
            Logger.w(TAG) { "alarm: daemon heartbeat stale (age=$daemonAge) -> restarting" }
            GuardDaemonService.start(context)
        }

        // 自续期：已绑定才继续排班，未绑定时停机不空转（重新绑定由 GuardService 排班）
        if (bound) {
            schedule(context)
        } else {
            cancel(context)
        }
    }

    companion object {
        private const val TAG = "GuardAlarm"
        const val ACTION = "com.padguard.child.action.GUARD_ALARM"

        private const val REQUEST_CODE = 20260

        /**
         * 排下一次探测。用 `setWindow`（非精确闹钟，系统可批处理）而非
         * `setExactAndAllowWhileIdle`：保活探测不需要精确时刻，批处理更省电，
         * 也避免 SCHEDULE_EXACT_ALARM 在 Android 12+ 被用户撤销后整条通道失效。
         */
        fun schedule(context: Context) {
            val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val triggerAt = System.currentTimeMillis() + INTERVAL_MS
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    manager.setWindow(
                        AlarmManager.RTC_WAKEUP,
                        triggerAt,
                        WINDOW_MS,
                        pendingIntent(context)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    manager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent(context))
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

        /** 探测间隔：60s。非精确闹钟下系统会合并唤醒，实际耗电远低于字面值 */
        private const val INTERVAL_MS = 60_000L

        /** 投递窗口：60s。窗口内系统可自由选择时刻批处理 */
        private const val WINDOW_MS = 60_000L
    }
}
