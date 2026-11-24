package com.padguard.child.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.padguard.child.service.GuardService
import com.padguard.core.common.Logger

/**
 * 时间变化加速器：系统时间类广播到达时立刻做一次日切对账 + 全量管控重铺。
 *
 * ## 为什么需要它
 * [GuardService] 每 15s tick 都会调 `UsageSessionManager.checkDayRollover()` 并 `applyCurrent`
 * （正确性的兜底），但跨零点后最长要等 15s 才重置、同日内手动改时间跨时段边界也要等最长 15s
 * 才重算——这 15s 窗口足够孩子钻空子。系统在日期变化（含时区切换导致的本地日切）时广播
 * `ACTION_DATE_CHANGED` / `ACTION_TIMEZONE_CHANGED`，时间被手动设置时广播 `ACTION_TIME_SET`；
 * 收到后拉起守护服务立即对账 + 重铺管控，把 15s 窗口压到秒级。
 *
 * ## 它如何挡住"改时间绕过管控"
 * 1. 受信钟（`TimeProvider.now()`）回拨不倒退、前拨不超前物理流逝，孩子把墙钟往回拨 2 小时
 *    想逃 21:00 的锁，受信钟仍判为"已锁定"——`applyCurrent` 立刻按受信钟重铺，锁屏页秒级顶回；
 *    把墙钟往前拨想提前进锁同理会被受信钟正确判定。改时间只是让本次广播白跑（幂等无副作用）。
 * 2. `checkDayRollover` 管跨零点、`applyCurrent(force=true)` 管同日内时段边界，两者互补。
 *
 * ## 安全性
 * 1. 它**只是加速器**：receiver 挂了 / 广播被 ROM 吞了，tick 兜底仍在，日切与时段边界照常发生；
 * 2. `exported=false`：这三条都是受保护系统广播（只有系统发得出），无需对外暴露。
 */
class DayRolloverReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Logger.i(TAG) { "time-related broadcast: $action, triggering day rollover check" }
        GuardService.start(context, GuardService.REASON_DAY_ROLLOVER)
    }

    companion object {
        private const val TAG = "DayRolloverReceiver"
    }
}
