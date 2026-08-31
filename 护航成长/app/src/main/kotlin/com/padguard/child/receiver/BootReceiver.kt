package com.padguard.child.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.padguard.child.service.GuardService
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
        val reason = if (action == Intent.ACTION_MY_PACKAGE_REPLACED) "packageReplaced" else "boot"
        GuardService.start(context, reason = reason)
    }

    companion object {
        private const val TAG = "BootReceiver"

        /**
         * QUICKBOOT_POWERON 是部分国产 ROM（MIUI、EMUI 等）在"快速开机"时
         * 替代 BOOT_COMPLETED 发出的私有广播。不监听它，这些设备开机后服务不会起，
         * 表现为"重启一次就脱管"，而日志里什么都看不到。
         */
        private val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
    }
}
