package com.padguard.child.monitor

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import com.padguard.child.guard.AccessibilityForegroundSource
import com.padguard.child.permission.SpecialPermission
import com.padguard.child.util.isOwnPackage
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
 * ## 采样窗口为什么取 20 秒
 * queryEvents 需要一个时间区间。窗口太窄（如 1s）在系统事件延迟写入时会查不到任何事件，
 * 表现为前台应用间歇性变 null，限额计时随之抖动；窗口太宽（如 60s）则会把已经切走的应用
 * 当成前台。这里取 20s：既宽到能容下 UsageStats 的写入延迟与「无障碍补采」的 1.5s 时差，
 * 又因为 [ForegroundResolver] 会消费 PAUSED 事件，不会把已切走的应用误判为前台。
 * 20s > 守护 15s 的采样间隔，保证相邻两次采样窗口**重叠**，杜绝「落在两次采样之间的盲区」漏检。
 */
@Singleton
class ForegroundAppMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val usageStatsManager: UsageStatsManager? by lazy {
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
    }

    /** 上一次成功解析到的前台集合。事件偶发缺失时用它兜底，避免计时抖动 */
    @Volatile
    private var lastKnown: List<String> = emptyList()

    /**
     * 是否已获得「使用情况访问」授权 —— 只读 [SpecialPermission] 这一份目录判定。
     *
     * 旧实现是本项目第三套 AppOps 手写口径（Q 分叉正确，但 uid 取 `Process.myUid()`，
     * 且没有 `runCatching`）：查询抛异常时会直接冒泡进前台探测循环，
     * 而同一时刻「我的」页与篡改自检可能读到相反结论。收口后一次系统查询只有一个答案，
     * 异常也统一 fail-closed（探测暂停，不谎报有权限）。
     */
    fun hasPermission(): Boolean = SpecialPermission.USAGE_STATS.isGranted(context)

    /**
     * 当前前台应用包名；null 表示未授权或确实取不到。
     *
     * 注意返回 null 与返回自己的包名是两种完全不同的情况：
     * 返回自身包名说明锁屏页/拦截页正在前台，属于正常状态，不该被当成"探测失败"。
     *
     * 单窗口场景等价于 [currentAll] 的第一个；分屏场景下"顺手点了哪个"决定它返回谁，
     * 需要完整集合（分屏/PiP 判定、拦截目标选择）时必须用 [currentAll]。
     */
    fun current(): String? = currentAll().firstOrNull()

    /**
     * 当前**所有**处于前台的应用（分屏可能不止一个），按最近一次 resumed 时间倒序。
     *
     * 为什么需要集合（R-P2-10）：分屏下两个应用同时处于 resumed，
     * 只取"最后一条 RESUMED"会让另一半屏幕上的应用完全不被计时、也不参与拦截判定。
     * 画中画的浮层应用最后一条事件是 PAUSED（可见但不可交互），因此不在集合里 ——
     * 这正是我们要的语义：它是"浮层"，不是家长理解的前台应用。
     */
    fun currentAll(): List<String> {
        val manager = usageStatsManager
        // 主数据源（使用情况访问）可用：走原路径
        if (manager != null && hasPermission()) {
            val end = System.currentTimeMillis()
            val begin = end - QUERY_WINDOW_MS

            val resolved = runCatching {
                val events = manager.queryEvents(begin, end)
                val event = UsageEvents.Event()
                val lifecycle = ArrayList<ForegroundLifecycleEvent>()
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    val resumed = when (event.eventType) {
                        resumedEventType() -> true
                        pausedEventType() -> false
                        else -> continue
                    }
                    lifecycle.add(ForegroundLifecycleEvent(event.packageName.orEmpty(), resumed, event.timeStamp))
                }
                ForegroundResolver.resolve(lifecycle)
            }.onFailure {
                Logger.w(TAG, it) { "queryEvents failed" }
            }.getOrNull()

            // 窗口内一条生命周期事件都没有是常态（用户一直停在同一个应用/屏幕未切换），
            // 此时沿用上次结果，避免前台判定在每个采样点"闪一下 null"导致限额计时抖动。
            if (resolved.isNullOrEmpty()) return lastKnown
            lastKnown = resolved
            return resolved
        }

        // 主数据源不可用（权限被撤销 / 系统服务缺失）：若直接返回空，单应用时长限额、
        // 黑名单事后拦截、Kiosk 应用拦截会全部静默失效（漏管），而家长端毫无感知。
        // 用无障碍服务维护的"最近前台包名"做单包兜底，至少让黑名单 / Kiosk 仍能拦住
        // 当前最前台的应用；分屏场景会退化成单包，但远好过全盲。自家界面不计入前台。
        val a11y = AccessibilityForegroundSource.lastForegroundPackage
        if (!a11y.isNullOrBlank() && !isOwnPackage(a11y)) {
            Logger.d(TAG) { "usage-stats unavailable, fall back to accessibility foreground: $a11y" }
            return listOf(a11y)
        }
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

    /**
     * 对应的"离开前台"事件：ACTIVITY_PAUSED（旧 MOVE_TO_BACKGROUND）。
     *
     * 必须同时消费 PAUSED，否则无法区分"两个应用都在前台（分屏）"与
     * "一个在前台、另一个只是上次切换时留下的事件"。
     */
    private fun pausedEventType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            UsageEvents.Event.ACTIVITY_PAUSED
        } else {
            @Suppress("DEPRECATION")
            UsageEvents.Event.MOVE_TO_BACKGROUND
        }

    fun reset() {
        lastKnown = emptyList()
    }

    companion object {
        private const val TAG = "ForegroundAppMonitor"
        private const val QUERY_WINDOW_MS = 20_000L
    }
}
