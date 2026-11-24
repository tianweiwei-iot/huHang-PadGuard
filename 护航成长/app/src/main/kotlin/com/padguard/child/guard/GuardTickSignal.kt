package com.padguard.child.guard

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「立刻跑一轮采样」的唤醒信号（前台切换 → 主循环提前 tick）。
 *
 * ## 解决什么问题
 * 主循环是 15 秒一轮的定时采样（见 `GuardService` 的 `delay(MIN_TICK_MS)`）。
 * 孩子点开一个**额度已耗尽**的应用时，采样点最多要等 15 秒才会到来 ——
 * 这 15 秒里他能正常玩，家长端显示"已限额"，孩子端却毫无反应。
 * 弱网长轮询把主循环拖慢时这个窗口还会更长。
 *
 * 无障碍服务在窗口切换的**那一刻**就知道前台变成谁了（[OperationGuardAccessibilityService]），
 * 由它发一个唤醒信号，主循环不必等到下一个 15 秒采样点即可完成
 * 「限额 / 黑名单 / Kiosk 白名单」判定并立刻顶回。
 *
 * ## 三条设计约束
 * 1. **不是广播，是信号**：主循环只关心"有没有被叫醒"，不关心叫醒几次 → [Channel.CONFLATED]。
 * 2. **必须节流**：[MIN_INTERVAL_MS] 内重复请求直接丢弃。孩子连续点五个应用图标
 *    不该触发五次完整采样（每个 tick 都要查 UsageStats、算判定、写用量）。
 * 3. **不能被 await 卡死**：调用方一律用 [awaitWithin] 带超时等待，超时即按原节奏继续，
 *    否则一次遗漏信号就会让主循环永久挂起 —— 那比 15 秒延迟严重得多。
 *
 * 刻意不做成"回调执行 tick"：tick 需要 `GuardService` 里的十几处依赖与生命周期状态，
 * 由服务自己的协程串行执行才是唯一安全的执行位置。
 */
@Singleton
class GuardTickSignal @Inject constructor() {

    /** CONFLATED：只保留"有一次待处理"这个事实，重复请求天然合并 */
    private val channel = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var lastRequestElapsed: Long = 0L

    /**
     * 节流用的单调读数缝（单测注入可控值，避免用例真的 sleep 1 秒）。
     *
     * 默认 `nanoTime`：Android 与 JVM 都单调递增、不受改系统时间影响 ——
     * 不用 `SystemClock` 是因为它在纯 JVM 单测里是 stub，一调就抛。
     */
    internal var elapsedNow: () -> Long = { System.nanoTime() / 1_000_000L }

    /**
     * 请求尽快跑一轮采样。可在任意线程调用（a11y 回调在主线程）。
     *
     * @param reason 仅用于排查"这次为什么提前 tick"，不参与逻辑。
     */
    fun request(reason: String) {
        // 单调钟：改系统时间既不能伪造"刚请求过"，也不能让节流窗口永不过期
        val now = elapsedNow()
        if (now - lastRequestElapsed < MIN_INTERVAL_MS) return
        lastRequestElapsed = now
        channel.trySend(Unit)
    }

    /**
     * 等待一次唤醒，最多等 [timeoutMs]。
     *
     * @return true = 被唤醒（应立刻 tick）；false = 超时（按正常节奏 tick）。
     *         两种情况的调用方行为一致（都要 tick），返回值仅用于日志/诊断。
     */
    suspend fun awaitWithin(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { channel.receive() } != null

    companion object {
        /**
         * 最大唤醒频率：1 秒 1 次。
         *
         * 一次完整 tick 要查 UsageStats、跑限额判定、按需写用量与落盘，
         * 比 1 秒更密没有收益（人眼切换应用的极限也就这个量级），只会白白耗电。
         */
        private const val MIN_INTERVAL_MS = 1_000L
    }
}
