package com.padguard.child.notify

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.padguard.child.BuildConfig

/**
 * 通知使用权（NotificationListenerService 的系统授权）引导。
 *
 * 与无障碍/使用情况权限不同，通知监听不是普通运行时权限，而是「设置 → 通知使用权」里的
 * 特殊授权；只能引导用户跳转到系统页手动开启，无法代码代为开启。
 */
object NotifyPermission {

    /** 本应用是否已获得系统通知监听授权 */
    fun isNotificationListenerEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        val cn = ComponentName(context, ChildNotificationListenerService::class.java)
        return enabled.split(":").any { it.equals(cn.flattenToString(), ignoreCase = true) || it.equals("${BuildConfig.APPLICATION_ID}/${cn.className}", ignoreCase = true) }
    }

    /** 跳转到系统「通知使用权」设置页 */
    fun settingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
