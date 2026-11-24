package com.padguard.child.notify

import android.app.Notification
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.padguard.child.BuildConfig
import com.padguard.core.common.Logger
import com.padguard.core.data.model.LogType
import com.padguard.core.data.repository.LogRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * 通知代收监听服务（说明书 §6.9，默认关闭）。
 *
 * ## 隐私最小化
 * 仅采集「来源包名 + 通知标题」，**明确不含正文/内容**（[Notification.EXTRA_TEXT] 等一律不读）。
 * 这是设计契约，也是合规底线：家长可见「孩子设备收到了哪些 App 的什么通知」，
 * 但看不到聊天/验证码等敏感正文。
 *
 * ## fail-closed
 * - 未获系统「通知使用权」时服务不会收到回调，天然不采集；
 * - 即便已授权，[NotificationCollectionPrefs.isEnabled] 为假时[onNotificationPosted]直接返回，
 *   不记录任何通知；
 * - 自身（护航成长）与系统/电话等噪声包被过滤，避免污染采集结果；
 * - 每个来源包 5 秒内至多采集一次，抑制音乐/导航类高频通知刷屏。
 *
 * ## 上报通道
 * 走既有 `LogRepository.append(LogType.NOTIFICATION_COLLECTED, ...)` —— 复用日志批量上行链路，
 * 离线堆积、幂等、批上报三件事已由该链路解决，无需新开渠道。服务端按 type 落库后家长端可展示。
 */
@AndroidEntryPoint
class ChildNotificationListenerService : NotificationListenerService() {

    @Inject lateinit var logRepository: LogRepository
    @Inject lateinit var prefs: NotificationCollectionPrefs

    /** 每个来源包最近一次采集的单调时间，用于节流 */
    private val lastByPackage = ConcurrentHashMap<String, Long>()
    private val mainHandler = Handler(Looper.getMainLooper())
    /** 上报走既有日志批量通道（suspend），用独立作用域异步提交，不阻塞回调线程 */
    private val reportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        Logger.i(TAG) { "notification listener connected" }
    }

    override fun onListenerDisconnected() {
        Logger.w(TAG) { "notification listener disconnected" }
    }

    override fun onDestroy() {
        reportScope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // 顶层防护：第三方通知的 notification / extras 反序列化可能抛 BadParcelableException /
        // RuntimeException，一旦逃逸会令部分 ROM 判定监听异常、静默停用通知代收
        // （采集能力 fail-closed，会丢失采集而非放行屏幕时间，但隐患隐蔽，不易察觉）。
        runCatching { handlePosted(sbn) }
            .onFailure { Logger.e(TAG, it) { "onNotificationPosted crashed on ${sbn.packageName}" } }
    }

    /**
     * 处理单条通知（由 [onNotificationPosted] 在 runCatching 内调用）。
     * 仅采集「来源包名 + 通知标题」，绝不取正文（隐私最小化，见类注释）。
     */
    private fun handlePosted(sbn: StatusBarNotification) {
        // 1) 开关未开 → 静默放行（fail-closed，不采集）
        if (!prefs.isEnabled()) return
        val pkg = sbn.packageName ?: return
        // 2) 过滤自身与系统/电话等噪声来源
        if (pkg == BuildConfig.APPLICATION_ID) return
        if (pkg in IGNORED_PACKAGES) return

        // 节流用单调时钟：孩子改系统时间（墙钟回拨）不会让 now-prev 变负而漏采
        val now = SystemClock.elapsedRealtime()
        val prev = lastByPackage.putIfAbsent(pkg, now) ?: 0L
        if (now - prev < THROTTLE_MS) return // 节流：同包 5s 内只采一次
        lastByPackage[pkg] = now

        // 3) 仅取标题，绝不取正文
        val extras = sbn.notification?.extras ?: return
        val title = (extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return)
            .take(MAX_TITLE_LEN)
            .trim()
        if (title.isEmpty()) return

        // 4) 异步落库上报（走既有日志批量通道），异常吞掉不阻塞监听回调
        val postedAt = sbn.postTime
        reportScope.launch {
            runCatching {
                logRepository.append(
                    LogType.NOTIFICATION_COLLECTED,
                    mapOf(
                        "packageName" to pkg,
                        "title" to title,
                        "postedAt" to postedAt.toString()
                    )
                )
            }.onFailure { Logger.e(TAG, it) { "append notification-collected failed" } }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // 移除事件无需采集
    }

    companion object {
        private const val TAG = "ChildNotificationListener"
        private const val THROTTLE_MS = 5_000L
        private const val MAX_TITLE_LEN = 120
        private val IGNORED_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.dialer",
            "com.google.android.dialer",
            "com.android.phone",
            "com.android.providers.telephony"
        )
    }
}
