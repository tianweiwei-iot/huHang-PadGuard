package com.padguard.child.monitor

/**
 * 前台应用集合解析（纯函数，便于 JVM 单测）。
 *
 * ## 为什么不能"取最后一个 RESUMED 事件"（R-P2-10）
 * 旧实现只看窗口内时间最晚的那条 RESUMED，等价于假设"同一时刻只有一个前台应用"。
 * 这个假设在两种最常见的场景下都不成立：
 *
 * 1. **分屏**：两个应用同时 resumed。取最后一条只能命中"刚被点过的那一个"，
 *    另一半屏幕上的应用（往往正是被限额/被禁的那个）完全不被计时，也不参与拦截判定 ——
 *    孩子把游戏和计算器分屏，游戏就能一直玩下去。
 * 2. **画中画**：进入 PiP 的 Activity 会收到 PAUSED（它仍可见但不再可交互），
 *    而全屏应用 RESUMED。按"最后一条 RESUMED"取，结果是浮层视频应用被当成前台，
 *    家长看到的计时全部记在视频上，横屏游戏反而不计。
 *
 * ## 解析规则
 * 逐包取**最新一条**生命周期事件，仍为 RESUMED 的包即为"当前前台集合"。
 * - 分屏 → 两个包都在集合里（两个都在真实消耗屏幕时间）；
 * - 画中画 → 浮层包最后事件是 PAUSED，因此不在集合里（它不可交互，不计为前台）；
 * - 切桌面 → 桌面 launcher RESUMED，原应用 PAUSED，集合只剩 launcher。
 *
 * 返回值按"最近一次 resumed 的时间"倒序，第一个即为最直觉意义上的"当前应用"。
 */
internal data class ForegroundLifecycleEvent(
    val packageName: String,
    /** true = RESUMED（进入前台），false = PAUSED/STOPPED（离开前台） */
    val resumed: Boolean,
    val at: Long
)

internal object ForegroundResolver {

    fun resolve(events: List<ForegroundLifecycleEvent>): List<String> {
        if (events.isEmpty()) return emptyList()
        // 每包只保留最新一条：同一窗口内可能反复 resume/pause（如快速切来切去），
        // 只有最后一条能代表此刻的状态。
        val latest = HashMap<String, ForegroundLifecycleEvent>()
        for (e in events) {
            if (e.packageName.isBlank()) continue
            val current = latest[e.packageName]
            if (current == null || e.at >= current.at) latest[e.packageName] = e
        }
        return latest.values
            .asSequence()
            .filter { it.resumed }
            .sortedByDescending { it.at }
            .map { it.packageName }
            .toList()
    }
}
