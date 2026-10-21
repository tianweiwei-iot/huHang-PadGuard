package com.padguard.child.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.padguard.child.service.GuardDaemonService
import com.padguard.child.service.GuardHeartbeat
import com.padguard.child.service.GuardService
import com.padguard.child.ui.lock.SystemKeyguardBridge
import com.padguard.core.common.Logger

/**
 * 开机 / 应用被覆盖安装后重新拉起守护服务。
 *
 * ## 为什么这里几乎不做事
 * 开机广播的执行窗口只有 10 秒左右，且开机瞬间 IO 竞争最激烈。
 * 在这里做"重新下发策略"（几十次 binder 调用 + 数据库读取）极易 ANR，
 * 而 ANR 会让系统在后续开机中降低我们的启动优先级 —— 越修越糟。
 *
 * 所以这里只负责一件事：把服务拉起来，并把"这是开机"这个上下文传过去。
 * 真正的重活（清临时解锁、重置篡改基准、强制重下策略）由
 * [GuardService] 在自己的协程里做，见 `PolicyEngine.onDeviceBoot`。
 *
 * ## 关于 startForegroundService 的合法性
 * Android 12+ 禁止后台启动前台服务，但 `BOOT_COMPLETED` 属于系统豁免场景，
 * 从这个广播里启动是允许的。若改成先启普通服务再提升，反而会踩到限制。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED_ACTIONS) {
            Logger.v(TAG) { "ignore action=$action" }
            return
        }

        Logger.i(TAG) { "received $action, starting guard service" }
        val reason = when (action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> "packageReplaced"
            Intent.ACTION_USER_PRESENT -> "userPresent"
            else -> "boot"
        }
        GuardService.start(context, reason = reason)
        // 双进程守护：拉起独立 :daemon 进程的守护服务（幂等）。
        // 主进程被"划任务/清理工具"整体杀掉时，进程内的 onTaskRemoved 重启、
        // START_STICKY、动态广播全部随进程死亡 —— 只有另一个进程能把主进程拉回来。
        GuardDaemonService.start(context)
        // 闹钟兜底通道：与 JobScheduler 看门狗并行的第二条系统调度路径。
        // 已绑定才排班；未绑定设备由 GuardService 绑定成功时补排。
        if (GuardHeartbeat.readGuardState(context)) {
            GuardAlarmReceiver.schedule(context)
        }
        // 注意：这里**不做**暖令牌（历史遗留已移除）。此前解锁/开机广播会主动补齐
        // MediaProjection 凭据，结果孩子每次解锁都可能看到一次投屏授权框，
        // 与"装机时确认一次、日常无感"的产品预期相悖。令牌的重新取得只剩两条路：
        // 装机向导的一次性授权，与投影类功能真正下发时的按需引导（通知/前台弹框）。
        // 解锁自愈的双保险：USER_PRESENT 意味着孩子自己滑开了系统锁屏。
        // 若那时"待撤锁"标记还在（家长已解锁、撤锁却没追上），系统锁既已不在，
        // 标记与压锁记账都该清掉 —— 否则残留标记会让自愈触发点追一个不存在的锁。
        // keyguard 仍锁着时不动，交给带 activity 的触发点去撤。
        SystemKeyguardBridge.clearIfKeyguardGone(context)
    }

    companion object {
        private const val TAG = "BootReceiver"

        /**
         * QUICKBOOT_POWERON 是部分国产 ROM（MIUI、EMUI 等）在"快速开机"时
         * 替代 BOOT_COMPLETED 发出的私有广播。不监听它，这些设备开机后服务不会起，
         * 表现为"重启一次就脱管"，而日志里什么都看不到。
         *
         * USER_PRESENT 在设备解锁（有锁屏）时发出，是暖令牌最合适的触发点：
         * 此刻孩子本就在操作平板，应用处于前台，可合法地把系统确认框顶起来。
         */
        private val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_USER_PRESENT,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
    }
}
