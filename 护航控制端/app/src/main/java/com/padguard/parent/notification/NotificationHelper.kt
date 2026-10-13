package com.padguard.parent.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.padguard.parent.presentation.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 家长端本地通知能力。
 *
 * 负责三件事：
 * 1) 创建高优先级通知渠道（Android 8+ 必须）；
 * 2) 在权限已授予的前提下本地发送通知（解锁申请、风险告警、订阅到期等）；
 * 3) 点击通知回到 App，由全局解锁申请弹窗继续处理。
 *
 * 未授予 POST_NOTIFICATIONS 时 [canPost] 返回 false，调用方静默跳过，
 * 不抛异常（targetSdk 33+ 未授权发通知会被系统丢弃，无需额外兜底）。
 */
@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val CHANNEL_ID = "padguard_alerts"
        const val CHANNEL_NAME = "护航管控通知"
        private const val NOTIFICATION_ID_UNLOCK = 1001

        /**
         * 高危告警（CRITICAL）本地通知。
         * 与解锁工单（[NOTIFICATION_ID_UNLOCK]）分开 ID 与 tag：两类通知语义不同、
         * 生命周期也不同（告警要留到家长确认，工单在处理即取消），共用一个 ID 会互相取消。
         */
        private const val NOTIFICATION_ID_RISK = 1002

        /**
         * 通知点击 Intent 的 extra 键。
         * 点击通知 → MainActivity（SINGLE_TOP）→ PadGuardNavHost 读到该 extra 后
         * 导航到消息中心（Screen.AlertList）。用 extra 而非 deep link：
         * 本项目是 Compose Navigation 字符串路由，没有 XML nav graph 可挂 NavDeepLink。
         */
        const val EXTRA_OPEN_ALERTS = "padguard.open_alerts"
    }

    private val manager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** 创建通知渠道（幂等；Android 8+ 必须，否则通知不显示） */
    fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "孩子解锁申请、设备告警、订阅到期等提醒"
            }
            manager.createNotificationChannel(channel)
        }
    }

    /** 当前是否已具备发送通知的权限（Android 13+ 需用户授予 POST_NOTIFICATIONS） */
    fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    /**
     * 孩子发来解锁申请时本地通知家长。
     * 用 ticketId 作 tag，避免同一条申请反复轮询时堆叠多条通知。
     *
     * 点击通知打开 App 并直达消息中心（[EXTRA_OPEN_ALERTS]）——
     * 家长看到通知时往往没开 App，只回到首页还得自己找铃铛，多一步就多一分流失。
     */
    fun notifyUnlockRequest(deviceName: String, ticketId: String) {
        if (!canPost()) return
        val intent = Intent(context, MainActivity::class.java).apply {
            // SINGLE_TOP：若 App 已在前台，不会重建 Activity，由全局弹窗接管
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_ALERTS, true)
        }
        val pi = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID_UNLOCK,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle("孩子请求解锁")
            .setContentText("${deviceName} 的孩子发来解锁申请，请及时处理")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        manager.notify("unlock_$ticketId", NOTIFICATION_ID_UNLOCK, notification)
    }

    /**
     * 工单已被处理（同意/拒绝/忽略）时取消对应通知。
     * 不清的后果：家长处理完，通知栏还挂着"孩子请求解锁"，
     * 过一会儿点开来孩子早已在用平板 —— 通知与事实不符，比没有通知更糟。
     */
    fun cancelUnlockRequest(ticketId: String) {
        runCatching { manager.cancel("unlock_$ticketId", NOTIFICATION_ID_UNLOCK) }
    }

    /**
     * 孩子端上报高危事件（CRITICAL：权限被撤销 / 已 Root / 时间被篡改 /
     * 无障碍被关闭 / 尝试卸载）时本地通知家长。
     *
     * 标题与正文直接用服务端 [AlertService.EVENT_TITLES] 映射好的中文（经告警列表带回来），
     * 不在端上再维护一份事件码表 —— 两份表迟早漂移，家长看到的就是错的说法。
     *
     * 点击同样直达消息中心（[EXTRA_OPEN_ALERTS]）：这类通知出现时家长通常没开 App，
     * 只回到首页还得自己找铃铛，多一步就多一分"看一眼算了"的流失。
     */
    fun notifyRiskAlert(alertId: String, title: String, message: String) {
        if (!canPost()) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_ALERTS, true)
        }
        val pi = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID_RISK,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        manager.notify("risk_$alertId", NOTIFICATION_ID_RISK, notification)
    }

    /**
     * 家长在消息中心确认（acknowledge）某条告警后取消其通知。
     * 与工单同理：不取消则家长已处理的事项还挂在通知栏，稀释真正需要处理的那些。
     */
    fun cancelRiskAlert(alertId: String) {
        runCatching { manager.cancel("risk_$alertId", NOTIFICATION_ID_RISK) }
    }
}
