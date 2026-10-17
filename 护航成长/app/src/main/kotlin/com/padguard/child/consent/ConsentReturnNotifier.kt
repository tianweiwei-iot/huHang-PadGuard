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
 * 首选方案是 [ConsentReturnPresenter] 的直接 `startActivity`（设置页落在本 App 任务栈内，
 * 命中后台启动豁免，绝大多数机型上这条路直接生效）；本通知是**兜底通道**：
 * 当直接启动不成立时（ROM 把设置页开到独立任务栈、豁免暂未生效等），由系统代我们
 * 把 [MainActivity]（授权页宿主）拉回前台。
 *
 * ## 全屏 Intent 的能力边界（必须如实，不能假装一定能用）
 * AOSP《Full-screen intent limits》明确：**用户正在使用设备期间，系统 UI 可以选择
 * 只展示一条悬浮通知而不 Launch 该 Intent**；息屏 / 锁屏态才会直接 Launch。
 * 也就是说授权场景（家长正盯着平板操作）下，它大概率以"可点的悬浮通知"形态出现 ——
 * 这依然比"按物理返回键"轻：点一下通知同样回到授权页并自动接续下一项。
 * Android 14+ 还需 `USE_FULL_SCREEN_INTENT` 特殊权限，用 [canUseFullScreenIntent]
 * 如实探测，不可用时由上层走通知点击 / 手动返回，不假装成功。
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
     * @param label 刚被开启的权限名（如"悬浮窗"）。带上它，家长在通知栏能一眼看出
     *   "哪一项开好了、点下去继续哪一项"，而不是一条看不懂的通用提示。
     * @return 是否真的发出（权限缺失时返回 false，由上层降级）
     */
    fun postReturnToConsent(label: String? = null): Boolean {
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
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                },
                PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )
        }.getOrNull() ?: return false

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(label?.let { "「$it」已开启" } ?: "授权已开启")
            .setContentText("点此返回护航守护，继续完成剩余授权…")
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
