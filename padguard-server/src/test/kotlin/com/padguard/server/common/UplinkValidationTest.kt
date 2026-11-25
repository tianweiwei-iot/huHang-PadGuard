package com.padguard.server.common

import com.padguard.server.dto.EventPacket
import com.padguard.server.dto.LogItem
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * 上行结构边界的纯函数测试：挡的是"数据库列宽/内存"级别的问题，
 * 不是业务白名单（孩子端新增枚举不应被旧服务端整批拒绝）。
 */
class UplinkValidationTest {

    private fun event(
        id: String = "evt_123",
        type: String = "CLOCK_TAMPERING",
        level: String = "HIGH",
        ts: Long = 1_700_000_000_000L
    ) = EventPacket(eventId = id, deviceId = "d1", type = type, level = level, timestamp = ts)

    @Test
    fun `valid event passes`() {
        assertNull(UplinkValidation.eventError(event(), 1024))
    }

    @Test
    fun `event id must be bounded ascii`() {
        assertNotNull(UplinkValidation.eventError(event(id = ""), 0))
        assertNotNull(UplinkValidation.eventError(event(id = "a".repeat(65)), 0))
        assertNotNull(UplinkValidation.eventError(event(id = "evt/../../x"), 0))
    }

    @Test
    fun `event type must fit varchar 32 and be uppercase enum`() {
        assertNotNull(UplinkValidation.eventError(event(type = "A".repeat(33)), 0))
        assertNotNull(UplinkValidation.eventError(event(type = "clock_tampering"), 0))
        assertNotNull(UplinkValidation.eventError(event(type = ""), 0))
    }

    @Test
    fun `event level and timestamp are bounded`() {
        assertNotNull(UplinkValidation.eventError(event(level = ""), 0))
        assertNotNull(UplinkValidation.eventError(event(level = "H".repeat(17)), 0))
        assertNotNull(UplinkValidation.eventError(event(ts = 0), 0))
        assertNotNull(UplinkValidation.eventError(event(ts = -1), 0))
    }

    @Test
    fun `event detail over 16KB is rejected`() {
        assertNull(UplinkValidation.eventError(event(), UplinkValidation.MAX_DETAIL_BYTES))
        assertNotNull(UplinkValidation.eventError(event(), UplinkValidation.MAX_DETAIL_BYTES + 1))
    }

    @Test
    fun `log allows null type but enforces id type and payload`() {
        assertNull(UplinkValidation.logError(LogItem(logId = "log-1", type = null), 0))
        assertNull(UplinkValidation.logError(LogItem(logId = "log-1", type = "APP_USAGE"), 4096))
        assertNotNull(UplinkValidation.logError(LogItem(logId = "log-1", type = "app_usage"), 0))
        assertNotNull(UplinkValidation.logError(LogItem(logId = "log-1", type = "A".repeat(33)), 0))
        assertNotNull(UplinkValidation.logError(LogItem(logId = ""), 0))
        assertNotNull(UplinkValidation.logError(LogItem(logId = "log-1"), UplinkValidation.MAX_PAYLOAD_BYTES + 1))
    }
}
