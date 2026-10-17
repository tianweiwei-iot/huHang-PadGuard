package com.padguard.core.engine.enforcer

import com.padguard.core.common.Logger
import com.padguard.core.data.model.policy.WebPolicy
import com.padguard.core.common.TimeProvider
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SmartShutdownEnforcer"

/**
 * 定时锁机判定（家长端「上网管控 → 定时锁机」开关的执行端）。
 *
 * ## 为什么单独一个原因而不并入 [ScheduleRule] 的时段锁
 * 时段锁是一张规则表（可多条、可按周几分天），定时锁机是一个**单区间开关**，
 * 两者在家长端是两个互不相关的 UI。合并实现会让其中一个去改写另一个的数据源，
 * 结果就是"改了这边那边变了"。
 *
 * ## 时区以策略为准
 * 与 [SchedulePolicy] / 未成年人模式宵禁同一口径：用服务端下发的策略时区而非设备本地时区，
 * 否则孩子改一次系统时区就能把锁机时间整体平移。
 */
@Singleton
class SmartShutdownEnforcer @Inject constructor(
    private val timeProvider: TimeProvider
) {

    /**
     * @param atMillis 判定时刻（服务端校准后的墙钟）
     * @param timezone 策略时区
     * @return 落在锁机区间内时返回解除时刻的绝对毫秒数，否则返回 null
     */
    fun evaluate(web: WebPolicy, atMillis: Long = timeProvider.now(), timezone: String = "Asia/Shanghai"): Long? {
        if (!web.smartShutdownEnabled) return null

        val startText = web.smartShutdownStartTime
        val endText = web.smartShutdownEndTime
        if (startText.isNullOrBlank() || endText.isNullOrBlank()) {
            // 开了开关却没有区间：这是家长端允许保存的状态（UI 允许先开后填时间），
            // 此时必须"不锁"而不是"一直锁"—— 后者会让设备再也用不了。
            Logger.w(TAG) { "smart shutdown enabled but range incomplete: start=$startText end=$endText" }
            return null
        }

        val startMinutes = parse(startText) ?: return null
        val endMinutes = parse(endText) ?: return null
        if (startMinutes == endMinutes) return null

        val zone = runCatching { ZoneId.of(timezone) }.getOrElse {
            Logger.w(TAG) { "invalid timezone $timezone, fallback to system default" }
            ZoneId.systemDefault()
        }
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(atMillis), zone)
        val nowMinutes = now.hour * 60 + now.minute

        // 跨零点区间（如 22:30–06:30）：命中判定为 [start, 24:00) ∪ [00:00, end)
        val inRange = if (startMinutes < endMinutes) {
            nowMinutes in startMinutes until endMinutes
        } else {
            nowMinutes >= startMinutes || nowMinutes < endMinutes
        }
        if (!inRange) return null

        // 解除时刻：跨零点且当前在前半夜时，结束时间落在次日
        val endDateTime = if (endMinutes <= startMinutes && nowMinutes >= startMinutes) {
            now.toLocalDate().plusDays(1).atTime(endMinutes / 60, endMinutes % 60)
        } else {
            now.toLocalDate().atTime(endMinutes / 60, endMinutes % 60)
        }
        return endDateTime.atZone(zone).toInstant().toEpochMilli()
    }

    /** 解析 "HH:mm"。非法值返回 null 而非 0 —— 0 会被当成"00:00 开始"，直接锁死一整天。 */
    private fun parse(hhmm: String): Int? {
        val parts = hhmm.split(":")
        if (parts.size != 2) return null
        val hour = parts[0].trim().toIntOrNull() ?: return null
        val minute = parts[1].trim().toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }
}
