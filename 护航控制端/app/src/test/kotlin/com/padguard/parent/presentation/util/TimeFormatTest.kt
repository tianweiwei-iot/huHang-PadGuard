package com.padguard.parent.presentation.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * [TimeFormat.describeStamp] 的行为锁定。
 *
 * 这个函数决定"刷新失败时，家长看到的数据是哪个时刻的"——说错比不说更糟
 * （把三小时前的快照说成"刚刚"）。所以三档分界必须钉死。
 *
 * 全部用固定时间戳 + 固定时区构造，不依赖运行机器时区。
 */
class TimeFormatTest {

    private val shanghai = ZoneId.of("Asia/Shanghai")

    private fun ts(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute)
            .atZone(shanghai)
            .toInstant()
            .toEpochMilli()

    @Test
    fun `same day shows today plus hour and minute`() {
        val now = ts(2026, 11, 6, 16, 30)
        val stamp = ts(2026, 11, 6, 9, 5)
        assertEquals("今天 09:05", TimeFormat.describeStamp(stamp, now))
    }

    @Test
    fun `same year different day shows month day and hour minute`() {
        val now = ts(2026, 11, 6, 16, 30)
        val stamp = ts(2026, 10, 3, 20, 15)
        assertEquals("10月03日 20:15", TimeFormat.describeStamp(stamp, now))
    }

    @Test
    fun `different year shows full date`() {
        val now = ts(2026, 11, 6, 16, 30)
        val stamp = ts(2025, 12, 31, 23, 59)
        assertEquals("2025年12月31日 23:59", TimeFormat.describeStamp(stamp, now))
    }

    @Test
    fun `midnight boundary is judged by calendar day not by 24h distance`() {
        // 相差仅 1 分钟，但已跨日：必须切到日期档，不能继续显示"今天 23:59"
        val now = ts(2026, 11, 6, 0, 0)
        val stamp = ts(2026, 11, 5, 23, 59)
        assertEquals("11月05日 23:59", TimeFormat.describeStamp(stamp, now))
    }

    @Test
    fun `future stamp does not crash and still renders absolute time`() {
        // 设备时钟被回拨等异常场景：不做相对计算，因此不会出现负数或异常
        val now = ts(2026, 11, 6, 10, 0)
        val stamp = ts(2026, 11, 6, 10, 30)
        assertEquals("今天 10:30", TimeFormat.describeStamp(stamp, now))
    }

    @Test
    fun `duration keeps fixed three segments`() {
        assertEquals("0小时30分钟0秒", TimeFormat.formatDuration(1800))
        assertEquals("0小时0分钟0秒", TimeFormat.formatDuration(0))
        assertEquals("1小时40分钟0秒", TimeFormat.formatDuration(6000))
    }
}
