package com.padguard.child.ui.lock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.padguard.core.common.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 锁屏页的「系统代拉」通道：高优先级全屏通知（full-screen intent）。
 *
 * ## 为什么必须有这一层
 * Android 10（API 29）起系统禁止后台启动 Activity，而家长下发锁屏时孩子**几乎一定**
 * 停留在别的应用或桌面上 —— 直接 `startActivity` 会被系统**静默丢弃**（不抛异常、
 * 日志里什么都看不到），表现就是"家长端点了锁屏、回执还是成功，平板上却毫无变化"。
 *
 * 全屏 intent 由**系统**代为拉起 Activity，不属于应用自启，因此不受这条限制；
 * AOSP 官方文档《Full-screen intent limits》的行为矩阵明确：在权限已授予的前提下，
 * 无论屏幕是解锁、锁屏还是**息屏**状态，都会直接 Launch FSI。
 * 本仓库的霸屏消息（[com.padguard.child.ui.message.MessageActivity]）走的就是这条路，
 * 这里沿用同一机制，保证口径一致。
 *
 * ## 解锁方向同样需要它
 * 家长解锁时，若锁屏页正处于息屏 stopped（或被回收），直接 startActivity 拉不回来。
 * [postUnlock] 复用同一通道，由系统把 [LockScreenActivity] 的 MODE_UNLOCKING 过渡页
 * 代拉到前台——点亮屏幕、撤掉我们压的系统 keyguard、自行退出。
 *
 * ## Android 14 的权限边界（必须如实处理，不能假装一定能用）
 * Android 14（API 34）起 `USE_FULL_SCREEN_INTENT` 变为"特殊应用访问"权限：
 * 安装时默认开启，但 Play 商店会撤销不具备通话/闹钟属性的应用的该权限。
 * 本产品是家庭平板侧载部署（adb / 设备所有者预置），不经 Play 分发，权限保持授予；
 * 但仍用 [canUseFullScreenIntent] 如实探测，不可用时不假装成功 ——
 * 上层据此走系统级 `lockNow()` 兜底，而不是留下一个"以为锁了其实没锁"的洞。
 */
@Singleton
class LockScreenNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val manager: NotificationManager? =
        context.getSystemService(NotificationManager::class.java)

    /**
     * 本机是否允许发送全屏通知。
     *
     * API 34 以下一律视为可用：那时 `USE_FULL_SCREEN_INTENT` 是随安装授予的普通权限。
     */
    fun canUseFullScreenIntent(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val mgr = manager ?: return false
        return runCatching { mgr.canUseFullScreenIntent() }.getOrDefault(false)
    }

    /**
     * 发布锁屏全屏通知。
     *
     * @return 是否真的发了出去（权限缺失 / 无通知服务时返回 false，由上层降级）
     */
    fun post(detail: String): Boolean =
        postInternal(
            detail = detail,
            title = "设备已被家长锁定",
            mode = LockScreenActivity.MODE_LOCK
        )

    /** 锁屏页已就位 / 已解锁：撤掉常驻通知，避免通知栏里留一条过期提醒 */
    fun cancel() {
        runCatching { manager?.cancel(NOTIFICATION_ID) }
            .onFailure { Logger.w(TAG) { "cancel lock notification failed: ${it.message}" } }
    }

    private fun postInternal(detail: String, title: String, mode: Int): Boolean {
        val mgr = manager ?: return false
        if (!canUseFullScreenIntent()) {
            Logger.w(TAG) { "full screen intent not permitted on this device, lock screen cannot be pulled by system" }
            return false
        }
        ensureChannel(mgr)

        val intent = LockScreenActivity.intent(context, detail, mode)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = runCatching { PendingIntent.getActivity(context, REQUEST_CODE, intent, flags) }
            .getOrNull() ?: return false

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(detail.ifBlank { "需解锁后才能继续使用" })
            .setSmallIcon(context.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_lock_idle_lock)
            .setFullScreenIntent(pi, true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setOngoing(true)
            .setAutoCancel(false)
            .setShowWhen(false)
            .build()

        return runCatching { mgr.notify(NOTIFICATION_ID, notification) }
            .onSuccess { Logger.i(TAG) { "lock screen full-screen intent posted" } }
            .onFailure { Logger.e(TAG, it) { "failed to post lock screen notification" } }
            .isSuccess
    }

    private fun ensureChannel(mgr: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val existing = mgr.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            CHANNEL_ID, "设备锁定", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setShowBadge(false)
            // 锁屏指令不能输给勿扰模式：孩子把平板调成免打扰就能躲掉锁定是不可接受的
            setBypassDnd(true)
        }
        runCatching { mgr.createNotificationChannel(channel) }
            .onFailure { Logger.w(TAG) { "create lock channel failed: ${it.message}" } }
    }

    companion object {
        const val TAG = "LockScreenNotifier"
        const val CHANNEL_ID = "padguard_lock"
        const val NOTIFICATION_ID = 9101
        const val REQUEST_CODE = 9101

        /** 解锁过渡通知的标题（与锁屏通知区分，便于日志与栏上识别） */
        private const val UNLOCK_TITLE = "设备解锁中"

        /**
         * 供锁屏页自身在"回到前台"时直接撤通知。
         *
         * 升级链可能在锁屏页真正可见之前就已经结束（重试上限到达），
         * 那时通知还在栏里挂着；锁屏页后来被孩子点亮屏幕带起来时，
         * 已经没有任何协程会再去撤它 —— 留一条"设备已被家长锁定"的常驻通知很刺眼。
         * 因此这里给一个无需注入的静态入口。
         */
        @JvmStatic
        fun cancelFrom(context: Context) {
            runCatching {
                context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
            }.onFailure { Logger.w(TAG) { "cancel lock notification failed: ${it.message}" } }
        }

        /**
         * 发布**解锁过渡**全屏通知：由系统把 MODE_UNLOCKING 过渡页代拉到前台。
         *
         * 免注入静态版，供 [LockScreenActivity.dismiss] 在无 Hilt 注入点的伴生对象里使用
         * （进程刚被回收、presenter 尚未注入完成时也要能撤系统锁）。
         * 过渡页到前台后会立即 [cancelFrom] 自撤，栏上不会留痕。
         *
         * @param detail 透传给过渡页的锁屏说明（stopped 实例复用时的展示文案）
         * @param mode   [LockScreenActivity.MODE_UNLOCKING] 或复用实例的既有模式
         * @return 是否真的发了出去；false 时由调用方降级为直接 startActivity
         */
        @JvmStatic
        fun postUnlock(context: Context, detail: String, mode: Int): Boolean {
            val mgr = context.getSystemService(NotificationManager::class.java) ?: return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
                runCatching { mgr.canUseFullScreenIntent() }.getOrDefault(false)
            ) {
                // 通道与锁屏通知共用：同一 ID，天然替换而非叠加
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    mgr.getNotificationChannel(CHANNEL_ID) == null
                ) {
                    val channel = NotificationChannel(
                        CHANNEL_ID, "设备锁定", NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        setShowBadge(false)
                        setBypassDnd(true)
                    }
                    runCatching { mgr.createNotificationChannel(channel) }
                }
                val intent = LockScreenActivity.intent(context, detail, mode)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                        PendingIntent.FLAG_IMMUTABLE else 0)
                val pi = runCatching {
                    PendingIntent.getActivity(context, REQUEST_CODE, intent, flags)
                }.getOrNull() ?: return false
                val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle(UNLOCK_TITLE)
                    .setContentText(detail.ifBlank { "家长已解锁，正在恢复" })
                    .setSmallIcon(
                        context.applicationInfo.icon.takeIf { it != 0 }
                            ?: android.R.drawable.ic_lock_idle_lock
                    )
                    .setFullScreenIntent(pi, true)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setShowWhen(false)
                    .build()
                return runCatching { mgr.notify(NOTIFICATION_ID, notification) }
                    .onSuccess { Logger.i(TAG) { "unlock transition full-screen intent posted" } }
                    .onFailure { Logger.e(TAG, it) { "failed to post unlock transition notification" } }
                    .isSuccess
            }
            Logger.w(TAG) { "full screen intent not permitted, unlock transition cannot be pulled by system" }
            return false
        }
    }
}
