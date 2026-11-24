package com.padguard.child.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.padguard.child.service.GuardService
import com.padguard.core.common.Logger

/**
 * 时段边界精确闹钟（R-P2-6）。
 *
 * ## 为什么必须要有它
 * 守护服务靠协程 `delay()` 每 15s 采样一次。息屏进入 Doze（或国产 ROM 的深度省电）后，
 * **协程定时器不会唤醒 CPU** —— 采样会一直停到下一次系统唤醒为止。
 * 后果是"说好 21:00 锁机，实际 21:20 才锁上"、"说好 06:30 解锁，孩子 07:00 才拿到平板"。
 * 轮询间隔再短也救不了这件事：问题不是间隔，是**进程根本没被唤醒**。
 *
 * 因此这里在"下一个状态翻转时刻"排一次 [AlarmManager.setExactAndAllowWhileIdle]：
 * 精确、且能在 Doze 白名单窗口里唤醒进程，醒来后立刻跑一次完整复查。
 *
 * ## 时钟域：必须用 ELAPSED_REALTIME 而不是 RTC
 * 边界来自 `ScheduleEvaluator.nextTransitionAt()`，那是**受信时间轴**上的绝对时刻
 * （`TimeProvider.now()` 经护栏钳制，不随改系统时间跳变）。
 * 早期用 [AlarmManager.RTC_WAKEUP] 按裸墙钟排班，两者在改时间时直接分裂：
 * 回拨 → 闹钟晚到几小时（Doze 下锁机被推迟）；前拨 → 边界已成"过去"，
 * 闹钟立即触发被白白消耗，而 `lastLockBoundaryAt` 去重键仍记着该边界不再重排，
 * 真实到点反而没有任何唤醒。现改为把"距边界还有多久"搬到单调时钟轴上
 * （换算见 [BoundaryAlarmClock]），用 [AlarmManager.ELAPSED_REALTIME_WAKEUP] 排班，
 * 改时间 / NTP 校时 / 时区切换都不影响触发。
 *
 * ## 权限与降级（如实声明）
 * - Android 12+ 需要 SCHEDULE_EXACT_ALARM（本应用已在 manifest 声明）。
 *   用户可在设置里撤销「闹钟和提醒」，此时 [AlarmManager.canScheduleExactAlarms] 为 false，
 *   降级为 [AlarmManager.setAndAllowWhileIdle]：仍是 Doze 可唤醒，但系统可前后挪动几分钟。
 * - Android 6.0 以下没有 Doze，普通 `set` 即可。
 * - 任何一步失败都只记日志：轮询仍是兜底通道，不会因为闹钟失败而完全失效。
 *
 * ## 为什么不用 setAlarmClock
 * 那是"用户可见的闹钟"，会在状态栏显示图标并参与"下一个闹钟"提示 ——
 * 对管控应用是噪音，也会让家长误以为孩子设了闹钟。
 */
class LockBoundaryAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION) return
        Logger.i(TAG) { "lock boundary alarm fired -> immediate re-check" }
        // 一次性闹钟已被消耗：必须让主循环强制重排下一次，不能只靠
        // "边界值变了"这个条件。若系统拒绝后台启动前台服务（Android 12+ 限制），
        // 进程没被拉起、lastLockBoundaryAt 又没变，闹钟会永久不再排班 ——
        // 表现为"这一天之后 Doze 下再也不会准点锁机"。
        firedSinceLastSchedule.set(true)
        // 直接拉起守护服务跑一轮：doTick 会重新判定锁/解锁并重排下一次闹钟。
        // 系统若拒绝后台启动前台服务（Android 12+ 限制），失败仅记日志，
        // 进程下次被唤醒时消费上面的标记后仍会重排。
        runCatching { GuardService.start(context, GuardService.REASON_ALARM) }
            .onFailure { Logger.w(TAG, it) { "start guard from boundary alarm failed" } }
    }

    companion object {
        private const val TAG = "LockBoundaryAlarm"
        const val ACTION = "com.padguard.child.action.LOCK_BOUNDARY"

        private const val REQUEST_CODE = 20310

        /** 闹钟已触发但主循环尚未据此重排。用 AtomicBoolean：receiver 与主线程并发读写。 */
        private val firedSinceLastSchedule = java.util.concurrent.atomic.AtomicBoolean(false)

        /** 取走并清掉"闹钟已消耗"标记；返回 true 时调用方必须强制重排。 */
        fun consumeFired(): Boolean = firedSinceLastSchedule.getAndSet(false)

        /**
         * 排下一次边界闹钟。
         *
         * @param triggerAtElapsedMs [AlarmManager.ELAPSED_REALTIME_WAKEUP] 的触发点
         *        （`SystemClock.elapsedRealtime()` + 距边界的时长，由 [BoundaryAlarmClock]
         *        换算）；传 null 表示"当前不该排班"（无边界 / 边界已过），取消已排闹钟。
         *        **绝不能传受信时间轴上的绝对 UTC 毫秒** —— 那是另一个时钟域。
         */
        fun schedule(context: Context, triggerAtElapsedMs: Long?) {
            val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            if (triggerAtElapsedMs == null) {
                cancel(context)
                return
            }
            val pi = pendingIntent(context)
            val ok = runCatching {
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        manager.canScheduleExactAlarms() ->
                        manager.setExactAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtElapsedMs, pi
                        )

                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                        manager.setAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtElapsedMs, pi
                        )

                    else -> @Suppress("DEPRECATION")
                    manager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtElapsedMs, pi)
                }
                true
            }.onFailure {
                Logger.w(TAG, it) { "schedule boundary alarm failed (polling still covers it)" }
            }.getOrDefault(false)
            if (ok) {
                Logger.d(TAG) { "boundary alarm scheduled at elapsed=$triggerAtElapsedMs" }
            }
        }

        fun cancel(context: Context) {
            val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            runCatching { manager.cancel(pendingIntent(context)) }
                .onFailure { Logger.w(TAG, it) { "cancel boundary alarm failed" } }
        }

        private fun pendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, LockBoundaryAlarmReceiver::class.java).apply { action = ACTION }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
        }
    }
}
