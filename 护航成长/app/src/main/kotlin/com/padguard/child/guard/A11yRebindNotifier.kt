package com.padguard.child.guard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.padguard.child.notify.NotificationDelivery
import com.padguard.core.common.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 无障碍（操作闸门）失效时的「本地即时引导」通道。
 *
 * ## 解决什么问题（R-P0-2 缓解项）
 * 非 DO 形态下，孩子可在系统设置里关闭/停用无障碍服务，使「操作密码闸门的系统级拦截」整体瘫痪
 * （应用管控、卸载防护软门全部失效）。[PermissionHealthChecker] 已能检测这一状态并向家长端发高危告警，
 * 但家长/操作员**在设备本地**往往要等到打开「我的」页才看到横幅 —— 设备脱管期间毫无本地提示。
 *
 * 本通道在检测到的那一刻，用高优先级全屏通知（full-screen intent）由**系统**代拉起
 * [A11yRebindGuideActivity]，把"操作闸门已失效、请重新开启"直接顶到当前界面，
 * 让脱管状态在设备端立即可见，并给出一键前往设置开启的入口。
 *
 * ## 与 [LockScreenNotifier] 同口径
 * Android 10+ 禁止后台直接启动 Activity，全屏 intent 由系统代拉，不受该限制；
 * 权限缺失（Android 14 起 `USE_FULL_SCREEN_INTENT` 需特殊应用访问）时如实降级为普通高优通知，
 * 不假装成功。
 */
@Singleton
class A11yRebindNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val manager: NotificationManager? =
        context.getSystemService(NotificationManager::class.java)

    /**
     * 「本地引导是否处于活跃态」——供 [com.padguard.child.guard.PermissionHealthChecker]
     * 在权限**恢复**时边沿触发撤销。
     *
     * 这条常驻通知（`setOngoing(true)` + `setAutoCancel(false)`）原先只有引导页被打开时
     * 才撤：家长直接在系统设置里重新开启无障碍、或 ROM 自动重绑成功后，通知栏仍挂着
     * 「操作闸门已失效 / 应用管控被绕过」且划不掉 —— 真机表现就是「明明开了还显示未开」。
     * 记一个持久化布尔，恢复侧才能"只在确实弹过时才撤"，不必每轮自检都写盘。
     */
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isGuideActive(): Boolean = prefs.getBoolean(KEY_GUIDE_ACTIVE, false)

    fun canUseFullScreenIntent(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val mgr = manager ?: return false
        return runCatching { mgr.canUseFullScreenIntent() }.getOrDefault(false)
    }

    /**
     * 发布「操作闸门失效」通知：有全屏权限时系统代拉引导页；无权限时降级为
     * 普通高优可点通知（2026-11-23 补齐，此前直接 return false 导致设备端零提示）。
     * @return 是否真的发了出去（无通知服务 / 通知被用户关闭时返回 false，不消耗节流窗口）
     */
    fun post(): Boolean {
        val mgr = manager ?: return false
        // Android 14 未授予 USE_FULL_SCREEN_INTENT 时不能"直接放弃"：那样设备端完全没提示，
        // 上层还会因为 post()=false 而不落 30 分钟节流，每轮自检静默重试。
        // 按类注释承诺降级为普通高优可点通知——仍把脱管状态顶到通知栏，并把引导页作为 contentIntent。
        val fullScreenIntentAllowed = canUseFullScreenIntent()
        if (!fullScreenIntentAllowed) {
            Logger.w(TAG) { "full screen intent not permitted; posting high-priority notification instead" }
        }
        // 通知总开关被关 / 渠道被调成「不显示」时 notify() 静默丢弃却不抛异常：
        // 旧实现仍返回 true，于是置活跃态、上层落 30 分钟节流戳，而平板上一片空白。
        // 这里如实返回 false，权限恢复后下一轮自检即可补发（R34）。
        if (!NotificationDelivery.canDeliver(context, CHANNEL_ID)) {
            Logger.w(TAG) { "notifications disabled; a11y rebind guide withheld (no cooldown consumed)" }
            return false
        }
        ensureChannel(mgr)

        val intent = Intent(context, A11yRebindGuideActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = runCatching {
            PendingIntent.getActivity(context, REQUEST_CODE, intent, flags)
        }.getOrNull() ?: return false

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("操作闸门已失效")
            .setContentText("无障碍服务已关闭，应用管控被绕过。点此重新开启。")
            .setSmallIcon(
                context.applicationInfo.icon.takeIf { it != 0 }
                    ?: android.R.drawable.ic_dialog_alert
            )
            .setContentIntent(pi)
            .apply { if (fullScreenIntentAllowed) setFullScreenIntent(pi, true) }
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setOngoing(true)
            .setAutoCancel(false)
            .setShowWhen(false)
            .build()

        return runCatching { mgr.notify(NOTIFICATION_ID, notification) }
            .onSuccess {
                // 只有确实发出去了才置活跃态：权限不足 / notify 失败时不能凭空造一个
                // "待撤销"标记，否则恢复侧会去做无意义的清理并误复位节流窗口。
                prefs.edit().putBoolean(KEY_GUIDE_ACTIVE, true).apply()
                Logger.i(TAG) {
                    if (fullScreenIntentAllowed) "a11y rebind guide full-screen intent posted"
                    else "a11y rebind guide high-priority fallback posted"
                }
            }
            .onFailure { Logger.e(TAG, it) { "failed to post a11y rebind guide notification" } }
            .isSuccess
    }

    /** 引导页被人工关闭 / 权限恢复时撤掉常驻通知，避免栏上留痕 */
    fun cancel() {
        runCatching { manager?.cancel(NOTIFICATION_ID) }
            .onFailure { Logger.w(TAG) { "cancel a11y rebind guide notification failed" } }
        clearActiveFlag()
    }

    private fun clearActiveFlag() {
        if (!prefs.getBoolean(KEY_GUIDE_ACTIVE, false)) return
        prefs.edit().remove(KEY_GUIDE_ACTIVE).apply()
    }

    private fun ensureChannel(mgr: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID, "操作闸门引导", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setShowBadge(false)
            // 管控失效提示不能被勿扰吞掉：孩子把平板调成免打扰就躲掉提示是不可接受的
            setBypassDnd(true)
        }
        runCatching { mgr.createNotificationChannel(channel) }
            .onFailure { Logger.w(TAG) { "create a11y guide channel failed: ${it.message}" } }
    }

    companion object {
        const val TAG = "A11yRebindNotifier"
        const val CHANNEL_ID = "padguard_a11y_guide"
        const val NOTIFICATION_ID = 9103
        const val REQUEST_CODE = 9103

        /** 与实例侧共用同一份账本，保证 `cancelFrom` 与 `cancel()` 口径一致；与通知渠道 ID 刻意不同名 */
        private const val PREFS_NAME = "padguard_a11y_guide_state"
        private const val KEY_GUIDE_ACTIVE = "guide_active"

        /**
         * 供引导页在无注入点处静态撤通知（引导页已在前台，无需常驻通知重复留痕）。
         * 同步清活跃态：否则恢复侧会一直以为"还有一条没撤的引导"，每轮自检重复清理。
         */
        @JvmStatic
        fun cancelFrom(context: Context) {
            runCatching {
                context.getSystemService(NotificationManager::class.java)
                    ?.cancel(NOTIFICATION_ID)
            }.onFailure { Logger.w(TAG) { "cancel a11y guide notification failed" } }
            runCatching {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().remove(KEY_GUIDE_ACTIVE).apply()
            }.onFailure { Logger.w(TAG) { "clear a11y guide active flag failed" } }
        }
    }
}
