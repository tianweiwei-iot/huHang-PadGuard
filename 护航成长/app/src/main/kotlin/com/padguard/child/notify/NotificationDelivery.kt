package com.padguard.child.notify

import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * 通知「是否真的能送达」的统一判定（R34）。
 *
 * ## 为什么不能只看 `notify()` 有没有抛异常
 * Android 在下列三种情况下会**静默丢弃**通知：`notify()` 正常返回、不抛异常、
 * 也没有任何回调：
 * 1. 用户关掉本应用的通知总开关（Android 13+ 拒绝 `POST_NOTIFICATIONS`，或更早版本
 *    在应用详情页关闭「显示通知」）；
 * 2. 用户把对应通知渠道的重要性调成「不显示 / 无声音」（API 26+）；
 * 3. 系统通知服务暂不可用。
 *
 * 于是所有「发成功了才记账」的写法都会记成假账：
 * - [com.padguard.child.service.GuardService] 的 `updateNotification()` 把正文写进
 *   `lastNotificationText`，权限恢复后正文没变 → 常驻通知**永不重发**；
 * - [com.padguard.child.guard.A11yRebindNotifier.post] 置活跃态并让上层落 30 分钟
 *   节流戳，操作闸门已失效的本地全屏引导被静音半小时；
 * - [com.padguard.child.capture.CaptureConsentPrompter] 占用 60 秒引导节流额度，
 *   家长端一直停在「连接中」而平板上什么都没有。
 *
 * 这三类都表现为「权限明明重新开了，功能却没回来」，所以判定口径必须只有一份：
 * 发之前先问 [canDeliver]，不可投递时**不落任何账本**；投递能力恢复后由既有周期
 * 自检自然补发（常驻通知侧还需显式作废去重基线，见 GuardService）。
 */
object NotificationDelivery {

    /**
     * 生产入口：按本机真实状态判断某个渠道此刻能否把通知送到用户眼前。
     *
     * 渠道尚未创建时视为「可投递」——调用方紧接着会 `createNotificationChannel`，
     * 把「还没建」误判成「被关掉」会让首次装机永远发不出引导通知。
     */
    fun canDeliver(context: Context, channelId: String): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return runCatching {
            decide(
                hasManager = true,
                appNotificationsEnabled = manager.areNotificationsEnabled(),
                channelImportance = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    manager.getNotificationChannel(channelId)?.importance
                } else {
                    null
                }
            )
        }.getOrDefault(false) // 查询本身异常时按「不可投递」：宁可下次重试，也不记假账
    }

    /**
     * 纯判定（零 Android 运行时依赖，便于 JVM 单测锁定四条分支）。
     *
     * @param channelImportance null = 渠道尚未创建 / 低版本无渠道概念，均不视为被关闭
     */
    @JvmStatic
    fun decide(
        hasManager: Boolean,
        appNotificationsEnabled: Boolean,
        channelImportance: Int?
    ): Boolean {
        if (!hasManager || !appNotificationsEnabled) return false
        // 值等于 NotificationManager.IMPORTANCE_NONE；写成常量避免 JVM 单测触碰 framework stub
        return channelImportance == null || channelImportance != IMPORTANCE_NONE
    }

    /** 与 [NotificationManager.IMPORTANCE_NONE] 对齐；用户把渠道调成「不显示」即为此值。 */
    private const val IMPORTANCE_NONE = 0
}
