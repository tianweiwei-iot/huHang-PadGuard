package com.padguard.presentation.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 时间 / 时长 / 日期格式化工具（全项目唯一时间格式化入口）
 *
 * 规范（禁止在界面处自行拼接 `h`/`m` 或 `分`/`分钟`）：
 * - 时钟（时刻）：24 小时制，固定三段 `XX时YY分ZZ秒`
 * - 时长（累计）：固定三段 `X小时Y分钟Z秒`
 *
 * 关键约束：**固定三段、不做零值省略**
 * 同一数值在任何页面形态完全一致（如 1800 秒恒为 `0小时30分钟0秒`），
 * 避免「有的页面显示 30分钟、有的显示 0小时30分钟0秒」这类不一致。
 *
 * 调用约定：
 * - 显示某个时间点（开始 / 结束时间、更新时间、时段） -> [formatClock]
 * - 显示一段累计时长（已用 / 剩余 / 限额 / 配置项数值） -> [formatDuration] / [formatDurationFromMinutes]
 */
object TimeFormat {

    private const val UNIT_HOUR = "小时"
    private const val UNIT_MINUTE = "分钟"
    private const val UNIT_SECOND = "秒"

    private const val SECONDS_PER_MINUTE = 60
    private const val SECONDS_PER_HOUR = 60 * SECONDS_PER_MINUTE

    /** 2 位补零，保持时钟的 `XX` 形态。 */
    private fun pad2(n: Int): String = n.toString().padStart(2, '0')

    /**
     * 时钟（时刻）格式化：把时间点转成固定三段的 `XX时YY分ZZ秒`（24 小时制）。
     * 支持 `HH:mm:ss` 与 `HH:mm`；秒缺失时按 `00` 处理。
     * 空 / 非法输入返回空串。
     */
    fun formatClock(input: String?): String {
        if (input.isNullOrBlank()) return ""
        val parts = input.split(":")
        if (parts.size < 2) return input
        val hour = parts[0].toIntOrNull() ?: return input
        val minute = parts[1].toIntOrNull() ?: return input
        val second = if (parts.size >= 3) parts[2].toIntOrNull() ?: 0 else 0
        return buildString {
            append(pad2(hour)).append("时")
            append(pad2(minute)).append("分")
            append(pad2(second)).append("秒")
        }
    }

    /**
     * 时长格式化：把秒数转成固定三段的 `X小时Y分钟Z秒`。
     * 为 0 的段同样显示（1800 -> `0小时30分钟0秒`，0 -> `0小时0分钟0秒`）；
     * 负数按 0 处理。
     */
    fun formatDuration(totalSeconds: Int): String {
        val seconds = totalSeconds.coerceAtLeast(0)
        val hour = seconds / SECONDS_PER_HOUR
        val minute = (seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        val second = seconds % SECONDS_PER_MINUTE
        return "$hour$UNIT_HOUR$minute$UNIT_MINUTE$second$UNIT_SECOND"
    }

    /**
     * 时长兜底：分钟数转固定三段 `X小时Y分钟Z秒`。
     * 用于数据源仅提供分钟精度的场景，与 [formatDuration] 输出形态完全一致。
     */
    fun formatDurationFromMinutes(minutes: Int): String =
        formatDuration(minutes.coerceAtLeast(0) * SECONDS_PER_MINUTE)

    /** 应用使用时长：秒优先，否则用分钟兜底。 */
    fun formatAppUsageDuration(seconds: Int, minutes: Int): String =
        if (seconds > 0) formatDuration(seconds) else formatDurationFromMinutes(minutes)

    // ===================== 时间戳（毫秒） =====================

    /** 时刻：毫秒时间戳 -> 固定三段 `XX时YY分ZZ秒`（24 小时制）。 */
    fun formatClockFromMillis(millis: Long): String =
        formatClock(SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(millis)))

    /** 日期 + 时刻：毫秒时间戳 -> `MM月dd日 XX时YY分ZZ秒`。 */
    fun formatDateTimeFromMillis(millis: Long): String =
        SimpleDateFormat("MM月dd日 ", Locale.CHINA).format(Date(millis)) + formatClockFromMillis(millis)

    // ===================== 日期（保持原有行为） =====================

    fun formatFullDate(date: String): String {
        return try {
            val input = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).parse(date)
            SimpleDateFormat("MM月dd日", Locale.CHINA).format(input ?: return date)
        } catch (e: Exception) {
            date
        }
    }

    fun formatShortDate(date: String): String {
        return try {
            val input = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).parse(date)
            SimpleDateFormat("MM/dd", Locale.CHINA).format(input ?: return date)
        } catch (e: Exception) {
            date
        }
    }

    fun formatWeekday(date: String): String {
        return try {
            val input = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).parse(date)
            SimpleDateFormat("EEEE", Locale.CHINA).format(input ?: return date)
        } catch (e: Exception) {
            date
        }
    }
}
