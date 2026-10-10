package com.padguard.child.consent

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.padguard.child.MainActivity
import com.padguard.core.common.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 授权自动回前台通道：高优先级全屏通知（full-screen intent）。
 *
 * ## 解决什么问题
 * 装机授权时，App 会把家长带到系统设置页（悬浮窗 / 使用情况 / 无障碍 / 未知来源）去拨开关。
 * 拨完开关后，Android 默认**停在设置页**，家长必须手动按返回 —— 这正是用户反馈的
 * "选择了还要手动返回，太不合理"。
 *
 * 根因与锁屏一致：Android 10+ 禁止后台启动 Activity。我们没法在家长拨完开关的那一刻
 * 直接把自家界面拉回来，除非借系统之力。这里复用 [com.padguard.child.ui.lock.LockScreenNotifier]
 * 验证过的全屏 Intent 机制：当 [ConsentReturnPresenter] 后台检测到目标权限已被授予，
 * 由系统代我们把 [MainActivity]（授权页宿主）拉回前台。授权页 onResume 后自动复检并跳下一项，
 * 于是整条授权链"拨一个开关就自动前进一个"，家长全程无需按返回。
 *
 * ## 与锁屏共用同一套可靠底座
 * 全屏 Intent 由系统代拉，不受后台启动限制；AOSP《Full-screen intent limits》矩阵表明
 * 权限授予前提下无论解锁/锁屏/息屏都会 Launch。本产品侧载/DO 预置部署，FSI 权限保持授予；
 * 仍用 [canUseFullScreenIntent] 如实探测，不可用时上层退化为"直接 startActivity 兜底"
 * （已持有 SYSTEM_ALERT_WINDOW 时该豁免同样生效），再不行则退回需手动返回的旧行为。
 */
@Singleton
class ConsentReturnNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val manager: NotificationManager? =
        context.getSystemService(NotificationManager::class.java)

    /**
     * 本机是否允许发送全屏通知（Android 14 起变为"特殊应用访问"权限，需如实探测）。
     */
    fun canUseFullScreenIntent(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val mgr = manager ?: return false
        return runCatching { mgr.canUseFullScreenIntent() }.getOrDefault(false)
    }

    /**
     * 发一条全屏通知，把授权页宿主（[MainActivity]）拉回前台。
     *
     * @return 是否真的发出（权限缺失时返回 false，由上层降级）
     */
    fun postReturnToConsent(): Boolean {
        val mgr = manager ?: return false
        if (!canUseFullScreenIntent()) {
            Logger.w(TAG) { "full screen intent not permitted: consent auto-return degrades to manual back" }
            return false
        }
        ensureChannel(mgr)

        val pi = runCatching {
            PendingIntent.getActivity(
                context,
                REQUEST_CODE,
                Intent(context, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                },
                PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )
        }.getOrNull() ?: return false

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("授权已开启")
            .setContentText("正在返回护航守护，继续完成剩余授权…")
            .setSmallIcon(
                context.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info
            )
            .setFullScreenIntent(pi, true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOngoing(false)
            .setAutoCancel(true)
            .setShowWhen(false)
            .build()

        return runCatching { mgr.notify(NOTIFICATION_ID, notification) }
            .onSuccess { Logger.i(TAG) { "consent return full-screen intent posted" } }
            .onFailure { Logger.e(TAG, it) { "failed to post consent return notification" } }
            .isSuccess
    }

    /** 授权页已可见 / 流程结束：撤掉回前台通知，避免通知栏留一条过期提醒 */
    fun cancel() {
        runCatching { manager?.cancel(NOTIFICATION_ID) }
            .onFailure { Logger.w(TAG) { "cancel consent return notification failed: ${it.message}" } }
    }

    private fun ensureChannel(mgr: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = android.app.NotificationChannel(
            CHANNEL_ID, "授权返回引导", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setShowBadge(false)
            // 授权引导不能被勿扰吞掉：家长把平板调成免打扰就收不到返回提示，授权链会卡住
            setBypassDnd(true)
        }
        runCatching { mgr.createNotificationChannel(channel) }
            .onFailure { Logger.w(TAG) { "create consent return channel failed: ${it.message}" } }
    }

    private companion object {
        const val TAG = "ConsentReturnNotifier"
        const val CHANNEL_ID = "padguard_consent_return"
        const val NOTIFICATION_ID = 9103
        const val REQUEST_CODE = 9103
    }
}
