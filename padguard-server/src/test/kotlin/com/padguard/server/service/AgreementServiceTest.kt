package com.padguard.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.domain.UsageLog
import com.padguard.server.dto.AgreementStatusDto
import com.padguard.server.repository.UsageLogRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*

/**
 * `AgreementService` 是「孩子端已阅已同意」授权凭证的**服务端唯一读取方**（说明书 §5.4 要求管控端可查）。
 * 凭证混在行为日志里、且会被 [com.padguard.server.service.UsageLogCleanup] 按 30 天 TTL 清掉，
 * 此前服务端**零读取方、零单测**——家长端无从查询「已同意」，且 fail-closed 语义（脏数据一律判未签）未被守护。
 *
 * 这里覆盖：纯函数 [AgreementService.parse] 的 fail-closed 全部分支（缺版本/缺时间/损坏 payload/空 SN 一律 signed=false），
 * 以及 [AgreementService.status] 的归属校验（调用链含 DEVICE_NOT_OWNED 守卫）与「无凭证→signed=false」。
 * 不加载 Spring 上下文，沿用既定 Mockito 占位范式（非空参数 matcher 补 `?: 默认值`）。
 */
class AgreementServiceTest {
    private val objectMapper = ObjectMapper()
    private val deviceService = mock(DeviceService::class.java)
    private val usageLogRepository = mock(UsageLogRepository::class.java)

    private fun svc(): AgreementService = AgreementService(deviceService, usageLogRepository, objectMapper)

    private fun logWith(payloadJson: String?, timestamp: Long = 1_700_000_000_000L): UsageLog =
        UsageLog(id = "l1", deviceId = "d1", logId = "l1", type = "AGREEMENT", timestamp = timestamp, payloadJson = payloadJson)

    // ---- parse 纯函数（fail-closed 分支，无需 mock）----

    @Test
    fun parse_validPayload_returnsSigned() {
        val log = logWith("""{"version":"1.0","signedAt":1700000000000,"snMask":"SN****1234"}""")
        val dto = AgreementService.parse(log, objectMapper)
        assertTrue(dto.signed)
        assertEquals("1.0", dto.version)
        assertEquals(1_700_000_000_000L, dto.signedAt)
        assertEquals("SN****1234", dto.snMask)
    }

    @Test
    fun parse_missingVersion_isUnsigned() {
        // 缺版本：结论只能是「没查到」，绝不能把脏数据当凭证
        val log = logWith("""{"signedAt":1700000000000}""")
        val dto = AgreementService.parse(log, objectMapper)
        assertFalse(dto.signed)
        assertNull(dto.version)
    }

    @Test
    fun parse_signedAtZeroOrNegative_isUnsigned() {
        val log = logWith("""{"version":"1.0","signedAt":0}""")
        assertFalse(AgreementService.parse(log, objectMapper).signed)
        val logNeg = logWith("""{"version":"1.0","signedAt":-5}""")
        assertFalse(AgreementService.parse(logNeg, objectMapper).signed)
    }

    @Test
    fun parse_corruptPayload_doesNotThrow_andIsUnsigned() {
        // payload 损坏：宁可返回「未查到」，绝不抛异常打断家长端设备详情页
        val log = logWith("{this is not json")
        val dto = AgreementService.parse(log, objectMapper)
        assertFalse(dto.signed)
    }

    @Test
    fun parse_blankSnMask_stillSignedButNoSn() {
        val log = logWith("""{"version":"1.0","signedAt":1700000000000,"snMask":"   "}""")
        val dto = AgreementService.parse(log, objectMapper)
        assertTrue(dto.signed)
        assertNull(dto.snMask)
    }

    @Test
    fun parse_missingSignedAtFallsBackToLogTimestamp() {
        // 孩子端未写签署时刻时，回退到日志自身事件时间戳，仍判为已签
        val log = logWith("""{"version":"1.0"}""", timestamp = 1_690_000_000_000L)
        val dto = AgreementService.parse(log, objectMapper)
        assertTrue(dto.signed)
        assertEquals(1_690_000_000_000L, dto.signedAt)
    }

    // ---- status（归属校验 + 读取）----

    @Test
    fun status_noCredential_returnsUnsigned() {
        `when`(
            usageLogRepository.findFirstByDeviceIdAndTypeOrderByTimestampDesc(anyString() ?: "", anyString() ?: "")
        ).thenReturn(null)

        val dto = svc().status("u1", "d1")

        assertFalse(dto.signed)
        // 归属守卫必须被调用：协议凭证带脱敏 SN，属设备隐私，不能裸查
        verify(deviceService).getDevice("u1", "d1")
    }

    @Test
    fun status_existingCredential_returnsSigned() {
        `when`(
            usageLogRepository.findFirstByDeviceIdAndTypeOrderByTimestampDesc(anyString() ?: "", anyString() ?: "")
        ).thenReturn(logWith("""{"version":"2.1","signedAt":1700000000000,"snMask":"SN****9"}"""))

        val dto = svc().status("u1", "d1")

        assertTrue(dto.signed)
        assertEquals("2.1", dto.version)
        assertEquals("SN****9", dto.snMask)
        verify(deviceService).getDevice("u1", "d1")
    }

    @Test
    fun status_ownershipFailure_propagates() {
        // 越权查别人设备的协议记录：归属校验抛错必须向上传导，不静默返回 signed=false
        `when`(deviceService.getDevice(anyString() ?: "", anyString() ?: "")).thenThrow(RuntimeException("DEVICE_NOT_OWNED"))

        assertThrows(RuntimeException::class.java) { svc().status("attacker", "d1") }
    }
}
