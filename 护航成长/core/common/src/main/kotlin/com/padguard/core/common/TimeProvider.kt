package com.padguard.core.common

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 防时间篡改的计时基准。
 *
 * 设计要点（对应说明书「防时间篡改落地逻辑」）：
 * 1. 所有时长统计一律基于 [android.os.SystemClock.elapsedRealtime] —— 该时钟单调递增，
 *    包含深度睡眠时间，且**无法通过修改系统时间伪造**，是本地计时的唯一可信来源。
 * 2. [android.os.SystemClock.uptimeMillis] 不含深度睡眠，用于「屏幕亮起时长」类统计。
 * 3. 系统墙钟时间（System.currentTimeMillis）仅用于日志展示与上报，
 *    通过 [serverOffsetMs] 与服务端标准时间对齐；一旦检测到用户改时间，
 *    墙钟会跳变，但 [elapsedRealtime] 不受影响，因此时长管控无法被绕过。
 */
@Singleton
class TimeProvider @Inject constructor() {

    private val _serverOffsetMs = MutableStateFlow(0L)
    /** 服务端标准时间 = 本地墙钟 + offset */
    val serverOffsetMs: StateFlow<Long> = _serverOffsetMs.asStateFlow()

    /** 单调递增时钟（含休眠），时长统计与时段判定的唯一基准 */
    fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()

    /** 单调递增时钟（不含深度休眠），用于亮屏时长统计 */
    fun uptimeMillis(): Long = SystemClock.uptimeMillis()

    /** 设备本次开机至今的运行时长 */
    fun sinceBootMs(): Long = SystemClock.elapsedRealtime()

    /**
     * 对齐服务端标准时间。
     * @param serverTimeMs 服务端下发的时间戳（UTC 毫秒）
     * @param roundTripMs  本次请求的往返耗时，用于折半补偿网络延迟
     */
    fun syncServerTime(serverTimeMs: Long, roundTripMs: Long = 0L) {
        val localNow = System.currentTimeMillis()
        val estimatedServerNow = serverTimeMs + roundTripMs / 2
        _serverOffsetMs.value = estimatedServerNow - localNow
        Logger.d("TimeProvider") { "server time synced, offset=${_serverOffsetMs.value}ms" }
    }

    /** 校准后的当前时间（UTC 毫秒）；未同步前退化为本地墙钟 */
    fun now(): Long = System.currentTimeMillis() + _serverOffsetMs.value

    /**
     * 检测本地墙钟是否被篡改。
     *
     * 判定依据：上次同步后落地的偏移量在短时间内出现显著漂移。
     * 一旦命中，调用方应强制重新拉取服务端时间并上报高危告警。
     */
    fun detectClockTampering(previousOffset: Long, toleranceMs: Long = DRIFT_TOLERANCE_MS): Boolean {
        val drift = kotlin.math.abs(_serverOffsetMs.value - previousOffset)
        if (drift > toleranceMs) {
            Logger.w("TimeProvider") { "clock tampering detected, drift=${drift}ms" }
            return true
        }
        return false
    }

    companion object {
        /** 允许的自然时钟漂移阈值：15 分钟 */
        const val DRIFT_TOLERANCE_MS = 15 * 60 * 1000L
    }
}
