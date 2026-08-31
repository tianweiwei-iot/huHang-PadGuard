package com.padguard.child.monitor

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import com.padguard.core.common.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 前台应用探测。
 *
 * ## 为什么用 UsageStatsManager 而不是别的
 * | 方案 | 可用性 | 结论 |
 * |---|---|---|
 * | `getRunningTasks` | Android 5.0 起对第三方失效 | 不可用 |
 * | `getRunningAppProcesses` | 只能拿到自己的进程 | 不可用 |
 * | 无障碍服务事件 | 可用且实时，但需用户手动开无障碍 | 作为补充 |
 * | `UsageStatsManager.queryEvents` | 可用，需"使用情况访问"授权 | **主方案** |
 *
 * ## 必须如实承认的边界
 * `PACKAGE_USAGE_STATS` 是特殊权限，**Device Owner 也无法代为授予**
 * （它是 AppOps 而非运行时权限，没有对应的 DPM API）。
 * 只能引导用户在「设置 → 应用 → 特殊应用权限 → 使用情况访问」手动打开。
 *
 * 未授权时 [current] 返回 null，其后果必须写进部署文档：
 * - 单应用时长限额、启动次数限额 **失效**（拿不到是哪个应用在前台）；
 * - 应用黑名单的"事后拦截"失效（但 DO 的 `setPackagesSuspended` 仍有效，
 *   所以黑名单本身不受影响 —— 这点区别很重要，别一并说成"黑名单失效"）；
 * - 每日总时长限额 **仍然有效**（只依赖亮屏时长）。
 *
 * ## 采样窗口为什么取 10 秒
 * queryEvents 需要一个时间区间。窗口太窄（如 1s）在系统事件延迟写入时会查不到任何事件，
 * 表现为前台应用间歇性变 null，限额计时随之抖动；
 * 窗口太宽（如 60s）则会把已经切走的应用当成前台。
 * 10s 与 15s 的采样间隔配合，能保证每次采样至少覆盖上一次采样点。
 */
@Singleton
class ForegroundAppMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val usageStatsManager: UsageStatsManager? by lazy {
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
    }

    /** 上一次成功探测到的包名。事件偶发缺失时用它兜底，避免计时抖动 */
    @Volatile
    private var lastKnown: String? = null

    /** 是否已获得"使用情况访问"授权 */
    fun hasPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * 当前前台应用包名；null 表示未授权或确实取不到。
     *
     * 注意返回 null 与返回自己的包名是两种完全不同的情况：
     * 返回自身包名说明锁屏页/拦截页正在前台，属于正常状态，不该被当成"探测失败"。
     */
    fun current(): String? {
        val manager = usageStatsManager ?: return null
        if (!hasPermission()) return null

        val end = System.currentTimeMillis()
        val begin = end - QUERY_WINDOW_MS

        val latest = runCatching {
            val events = manager.queryEvents(begin, end)
            val event = UsageEvents.Event()
            var candidate: String? = null
            var candidateAt = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType != resumedEventType()) continue
                // 事件不保证严格有序，用时间戳比较而不是"取最后一个"
                if (event.timeStamp >= candidateAt) {
                    candidateAt = event.timeStamp
                    candidate = event.packageName
                }
            }
            candidate
        }.onFailure {
            Logger.w(TAG, it) { "queryEvents failed" }
        }.getOrNull()

        if (latest != null) {
            lastKnown = latest
            return latest
        }
        // 窗口内没有切换事件是常态（用户一直停在同一个应用），沿用上次结果
        return lastKnown
    }

    /**
     * ACTIVITY_RESUMED（API 29+）与 MOVE_TO_FOREGROUND（旧）常量值相同（都是 1），
     * 但语义在文档上被重新定义过。这里显式分叉，避免哪天常量值不再相等时静默失效。
     */
    private fun resumedEventType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            UsageEvents.Event.ACTIVITY_RESUMED
        } else {
            @Suppress("DEPRECATION")
            UsageEvents.Event.MOVE_TO_FOREGROUND
        }

    fun reset() {
        lastKnown = null
    }

    companion object {
        private const val TAG = "ForegroundAppMonitor"
        private const val QUERY_WINDOW_MS = 10_000L
    }
}
