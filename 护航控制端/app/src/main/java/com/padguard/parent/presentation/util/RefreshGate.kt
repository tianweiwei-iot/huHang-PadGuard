package com.padguard.parent.presentation.util

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 刷新闸门：**单飞 + 补一次**。
 *
 * ## 要解决的问题
 * 家长端的三个消息中心（解锁申请 / 延时审批 / 风险告警）都是这样的结构：
 *
 * ```kotlin
 * while (isActive) { refresh(); delay(10_000) }
 * ```
 *
 * `refresh()` 内部自己 `launch`，所以 `delay` 到点时上一轮往往还没回来。
 * 弱网下一次刷新可能挂到 30s 以上（OkHttp 默认超时），于是每一轮都再叠一个请求：
 * - 请求越堆越多，家长端在 OkHttp 同主机并发上限里排队，观感是"点什么都转圈"；
 * - `_uiState.value = ...` 是直接赋值、没有版本号，**后返回的旧响应会把新数据覆盖掉**，
 *   表现为"工单刚处理完又冒出来""未读数来回跳"；
 * - 风险告警那边更具体：`notifiedAlertIds` 是普通 `mutableSetOf`，两个并发刷新
 *   同时做"查 in → 通知 → add"，会**各发一条通知**，家长同一秒收到两条一模一样的提醒。
 *
 * ## 为什么不是简单的"在途就丢弃"
 * 手动刷新（下拉、点"确认"之后的刷新）也会被吞掉：家长确认完一条告警，
 * 若恰好有一次轮询在途，列表最长要等 15s 才反映"已确认"，看起来像没生效。
 * 因此被拒绝的那次会记一个"补跑一次"的意愿，当前这轮结束后立刻再跑一次。
 *
 * ## 为什么不会无限循环
 * [exit] 清掉的是"这一轮期间累积的意愿"，且用 CAS 只兑现一次；
 * 兑现时若又有新请求进来，才可能再补一次。请求源是 10~15s 的轮询与用户手点，
 * 不会形成自激。
 *
 * 用法：
 * ```kotlin
 * fun refresh() {
 *     if (!gate.tryEnter()) return
 *     viewModelScope.launch {
 *         try { /* 拉数据、写 state */ } finally {
 *             if (gate.exit()) refresh()   // 期间有人请求过 → 补一次
 *         }
 *     }
 * }
 * ```
 */
class RefreshGate {

    private val inFlight = AtomicBoolean(false)
    private val pending = AtomicBoolean(false)

    /** @return true 表示获得执行权；false 表示已有刷新在途，本次已记入"补一次" */
    fun tryEnter(): Boolean {
        if (inFlight.compareAndSet(false, true)) return true
        pending.set(true)
        return false
    }

    /** @return true 表示期间有被拒的请求，调用方应立刻再刷新一次 */
    fun exit(): Boolean {
        inFlight.set(false)
        return pending.compareAndSet(true, false)
    }

    /**
     * 会话切换时复位闸门：旧会话的在途刷新若在上锁期间被账号切换打断，
     * inFlight/pending 可能残留，导致新会话的首次刷新被误判为"已有在途"而吞掉。
     * 与 VM 的 resetForSessionChange 配套调用。
     */
    fun reset() {
        pending.set(false)
        inFlight.set(false)
    }
}
