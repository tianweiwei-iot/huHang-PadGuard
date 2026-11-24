package com.padguard.parent.data.model

import com.padguard.parent.util.DISPLAY_TIME_ZONE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * [UsageStatsDto.toDomain] / [AppUsageDto.toDomain] 行为测试。
 *
 * 关键口径：起止时间的显示格式化固定走 [DISPLAY_TIME_ZONE]（Asia/Shanghai）——
 * 服务端按北京时间聚合，若按设备时区格式化，海外时区设备会"服务端算今天、界面显示昨天"。
 */
class UsageMappersTest {

    /** 在展示时区里构造指定时分秒的墙钟毫秒，避免测试对运行环境时区的依赖 */
    private fun millisAt(hour: Int, minute: Int, second: Int): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai")).apply {
            clear()
            set(2026, Calendar.NOVEMBER, 18, hour, minute, second)
        }
        return cal.timeInMillis
    }

    @Test
    fun `appUsages 为 null 时映射为空列表`() {
        val domain = UsageStatsDto("dev-1", "2026-11-18", 30, appUsages = null).toDomain()
        assertEquals(0, domain.appUsages.size)
        assertEquals("dev-1", domain.deviceId)
        assertEquals("2026-11-18", domain.date)
        assertEquals(30, domain.totalUsageMinutes)
    }

    @Test
    fun `起止毫秒按展示时区格式化为时分秒`() {
        val dto = AppUsageDto(
            packageName = "com.example.game",
            appName = "游戏",
            usageMinutes = 25,
            startAt = millisAt(9, 30, 15),
            endAt = millisAt(10, 5, 20)
        )
        val domain = dto.toDomain()
        assertEquals("09:30:15", domain.startTime)
        assertEquals("10:05:20", domain.endTime)
    }

    @Test
    fun `起止毫秒缺省或非正时不产出显示时间 原始值仍透传`() {
        val domain = AppUsageDto("p", "n", 10, startAt = null, endAt = 0L).toDomain()
        assertNull(domain.startTime)
        assertNull(domain.endTime)
        assertNull(domain.startAt)
        // endAt 原样透传（takeIf 只作用于显示时间），消费方按 startAt/endAt>0 自行判空
        assertEquals(0L, domain.endAt)
    }

    @Test
    fun `其余字段原样搬运`() {
        val dto = AppUsageDto(
            packageName = "com.x.y",
            appName = "应用",
            usageMinutes = 42,
            iconUrl = "/v1/files/i1",
            launchCount = 7
        )
        val domain = dto.toDomain()
        assertEquals("com.x.y", domain.packageName)
        assertEquals("应用", domain.appName)
        assertEquals(42, domain.usageMinutes)
        assertEquals("/v1/files/i1", domain.iconUrl)
        assertEquals(7, domain.launchCount)
    }
}
