package com.padguard.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.domain.UsageLog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 协议签署凭证解析（说明书 §5.4）：合规结论必须 fail-closed。
 */
class AgreementParseTest {
    private val mapper = ObjectMapper()

    private fun log(payload: String?, timestamp: Long? = 1_700_000_000_000L) = UsageLog(
        id = "a", deviceId = "dev-1", logId = "log-a", type = "AGREEMENT",
        timestamp = timestamp, payloadJson = payload, receivedAt = timestamp ?: 0L
    )

    @Test
    fun `valid payload restores the signature`() {
        val dto = AgreementService.parse(
            log("""{"version":"1.2.0","signedAt":"1700000000500","snMask":"9f3c"}"""), mapper
        )
        assertTrue(dto.signed)
        assertEquals("1.2.0", dto.version)
        assertEquals(1_700_000_000_500L, dto.signedAt)
        assertEquals("9f3c", dto.snMask)
    }

    @Test
    fun `missing version is not a signature`() {
        val dto = AgreementService.parse(log("""{"signedAt":"1700000000500"}"""), mapper)
        assertFalse(dto.signed, "缺版本号不能当合规凭证")
    }

    @Test
    fun `corrupt payload degrades to not-found instead of throwing`() {
        val dto = AgreementService.parse(log("{not json"), mapper)
        assertFalse(dto.signed, "payload 损坏只应退化为未查到，不能打断设备详情页")
    }

    @Test
    fun `blank payload falls back to log timestamp when version exists`() {
        val dto = AgreementService.parse(
            log("""{"version":"1.0.0","signedAt":"oops"}""", timestamp = 1_700_000_123_000L), mapper
        )
        assertTrue(dto.signed)
        assertEquals(1_700_000_123_000L, dto.signedAt, "签署时刻无效时回退日志事件时间戳")
    }

    @Test
    fun `non-positive timestamp is not a signature`() {
        val dto = AgreementService.parse(
            log("""{"version":"1.0.0","signedAt":"0"}""", timestamp = null), mapper
        )
        assertFalse(dto.signed)
    }
}
