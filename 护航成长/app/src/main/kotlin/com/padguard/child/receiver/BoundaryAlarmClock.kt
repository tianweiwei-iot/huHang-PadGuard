package com.padguard.child.receiver

/**
 * 时段边界闹钟的时钟域换算（R-P2-6 配套，纯函数便于 JVM 单测）。
 *
 * ## 为什么需要换算
 * `ScheduleEvaluator.nextTransitionAt()` 返回的是**受信时间轴**上的绝对 UTC 毫秒
 * （基准是 `TimeProvider.now()` —— 护栏钳制过、不受改系统时间影响的时钟）。
 * 而 `AlarmManager.RTC_WAKEUP` 是按**裸墙钟**触发的。拿受信时刻去排裸墙钟闹钟，
 * 正常时不显眼（两者只差一个 offset），一旦孩子改系统时间就彻底分裂：
 *
 * - **回拨 2 小时**：受信轴仍认为现在是 19:00、边界 21:00；裸墙钟却显示 19:00
 *   其实是真实 21:00，闹钟要等到裸墙钟走到 21:00（真实 23:00）才响 →
 *   Doze 下锁机被推迟 2 小时，正是防篡改本来要防的绕过。
 * - **前拨 2 小时**：边界 21:00 相对裸墙钟已经是"过去"，`setExact...` 会**立即触发**，
 *   一次性闹钟被白白消耗掉；而 `lastLockBoundaryAt` 去重键仍记着 21:00 不再重排，
 *   于是真实的 21:00 到来时**没有任何闹钟唤醒**，精确锁机整个失效。
 *
 * 正确做法是把"距离边界还有多久"这个**时长**搬到单调时钟轴上，用
 * `ELAPSED_REALTIME_WAKEUP` 排班：`SystemClock.elapsedRealtime()` 与护栏同为单调源，
 * 改系统时间、NTP 校时、时区切换都不影响它，也不存在"过去时刻"这种概念。
 *
 * 该类刻意不 import 任何 Android 类，保证纯 JVM 单测可直接跑。
 */
object BoundaryAlarmClock {

    /**
     * 比 `ScheduleEvaluator.LOOKAHEAD_DAYS`(8 天) 略宽的上限。
     * 超过它说明算出的边界是脏值（例如策略里落了个离谱时间戳），宁可不排也不能
     * 把 `elapsedRealtime` 加法撑到溢出后变成一个负数触发点。
     */
    const val MAX_BOUNDARY_DELAY_MS = 9L * 24 * 60 * 60 * 1000

    /**
     * @param nextBoundaryMs 受信时间轴上的下一次边界绝对时刻；null = 当前无边界
     * @param trustedNowMs   同一瞬间取自 `TimeProvider.now()` 的受信当前时刻
     * @param elapsedNowMs   同一瞬间取自 `SystemClock.elapsedRealtime()` 的单调读数
     * @return `ELAPSED_REALTIME_WAKEUP` 的触发点；null 表示"不该排闹钟"（无边界 /
     *         边界已到或已过 / 脏值）。调用方在 null 时取消已排闹钟。
     */
    fun triggerAtElapsed(
        nextBoundaryMs: Long?,
        trustedNowMs: Long,
        elapsedNowMs: Long,
    ): Long? {
        if (nextBoundaryMs == null || nextBoundaryMs <= 0L || trustedNowMs <= 0L) return null
        val delayMs = nextBoundaryMs - trustedNowMs
        // 边界已到/已过：主循环本轮 tick 已按当前时刻判定过，
        // 排一个过去时刻只会让闹钟立刻响、被消耗后还挡住下一次重排。
        if (delayMs <= 0L || delayMs > MAX_BOUNDARY_DELAY_MS) return null
        if (elapsedNowMs < 0L || elapsedNowMs > Long.MAX_VALUE - delayMs) return null
        return elapsedNowMs + delayMs
    }
}
