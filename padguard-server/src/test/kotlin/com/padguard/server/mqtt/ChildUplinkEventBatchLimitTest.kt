package com.padguard.server.mqtt

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.padguard.server.common.BizException
import com.padguard.server.dto.EventPacket
import com.padguard.server.repository.CommandRepository
import com.padguard.server.repository.DeviceEventRepository
import com.padguard.server.service.AlertService
import com.padguard.server.service.AppDistributionConverger
import com.padguard.server.service.DeviceService
import com.padguard.server.ws.WebSocketPushService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito

/**
 * R45：事件批硬上限（S-4 同款）的行为回归。
 *
 * 每条 fresh 事件 = 1 行 device_events + 1 条告警 + 1 条 alert.new WS 推送，
 * 恶意/异常设备的超大批次可单请求打爆 alerts 表与推送（孩子端正常单批仅个位数条）。
 * 上限在入口整批拒绝，MQTT 单发通道（恒 1 条）不受影响。
 */
class ChildUplinkEventBatchLimitTest {

    private fun newHandler(repo: DeviceEventRepository): ChildUplinkHandler = ChildUplinkHandler(
        null,
        jacksonObjectMapper(),
        Mockito.mock(CommandRepository::class.java),
        repo,
        Mockito.mock(DeviceService::class.java),
        Mockito.mock(WebSocketPushService::class.java),
        Mockito.mock(AlertService::class.java),
        Mockito.mock(AppDistributionConverger::class.java),
        true
    )

    private fun packet(i: Int) = EventPacket(
        eventId = "ev-$i", deviceId = "dev-1", type = "RISK",
        level = "HIGH", timestamp = 1000L + i
    )

    @Test
    fun `事件批超过上限整批拒绝且不落库不建告警`() {
        val repo = Mockito.mock(DeviceEventRepository::class.java)
        val handler = newHandler(repo)
        val events = (1..ChildUplinkHandler.MAX_EVENTS_PER_BATCH + 1).map { packet(it) }
        assertThrows(BizException::class.java) {
            handler.handleEventPackets("dev-1", events)
        }
        Mockito.verifyNoInteractions(repo)
    }

    @Test
    fun `单条事件通道不受上限影响`() {
        val repo = Mockito.mock(DeviceEventRepository::class.java)
        Mockito.`when`<Set<String>>(repo.findExistingEventIds(Mockito.anyList())).thenReturn(emptySet())
        val handler = newHandler(repo)
        val (accepted, duplicated) = handler.handleEventPackets("dev-1", listOf(packet(1)))
        assertEquals(1, accepted)
        assertEquals(0, duplicated)
    }
}
