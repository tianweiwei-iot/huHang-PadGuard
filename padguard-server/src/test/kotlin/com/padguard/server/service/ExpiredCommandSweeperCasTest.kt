package com.padguard.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.domain.Command
import com.padguard.server.repository.CommandRepository
import com.padguard.server.repository.ScreenshotRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.mockito.Mockito

/**
 * 过期指令收敛的并发护栏（阶段 1 体检 F01）。
 *
 * 旧实现把 findExpiredPending 查到的实体改 status 后 saveAll：
 * 若两次查询之间回执（MQTT / HTTP 双通道）已把指令置为 SUCCESS，
 * 清扫器会拿旧快照把它覆盖回 EXPIRED，家长端看到「执行成功又变过期」。
 *
 * 这里锁死新语义：状态只能由带 WHERE status='PENDING' 的条件 UPDATE 决定，
 * 且只有 CAS 成功的指令才允许连带收敛截图记录。
 */
class ExpiredCommandSweeperCasTest {

    private val commands = Mockito.mock(CommandRepository::class.java)
    private val screenshots = Mockito.mock(ScreenshotRepository::class.java)
    private val redelivery = Mockito.mock(CommandRedeliveryService::class.java)
    private val sweeper = ExpiredCommandSweeper(commands, screenshots, ObjectMapper(), redelivery)

    private fun command(id: String, msgId: String, type: String = "LOCK") =
        Command(id = id, deviceId = "dev-1", msgId = msgId, type = type, status = "PENDING")

    @Test
    fun `只用条件 UPDATE 收敛，绝不 saveAll 覆盖已 SUCCESS 的指令`() {
        val a = command("c1", "m1")
        val b = command("c2", "m2")
        Mockito.`when`(commands.findExpiredPending("dev-1", "PENDING", 1000L)).thenReturn(listOf(a, b))
        Mockito.`when`(redelivery.isEligible(a, 1000L)).thenReturn(false)
        Mockito.`when`(redelivery.isEligible(b, 1000L)).thenReturn(false)
        // c2 在查询之后被并发回执改成 SUCCESS：条件 UPDATE 影响 0 行
        Mockito.`when`(commands.markExpiredIfPending("c1", "PENDING", "EXPIRED")).thenReturn(1)
        Mockito.`when`(commands.markExpiredIfPending("c2", "PENDING", "EXPIRED")).thenReturn(0)

        sweeper.sweep("dev-1", 1000L)

        val usedSaveAll = Mockito.mockingDetails(commands).invocations.any { it.method.name == "saveAll" }
        assertFalse(usedSaveAll, "saveAll 会用旧快照把 SUCCESS 覆盖回 EXPIRED")
        assertEquals("PENDING", a.status, "内存快照不应被改写，状态由条件 UPDATE 决定")
        assertEquals("PENDING", b.status)
        Mockito.verify(commands).markExpiredIfPending("c1", "PENDING", "EXPIRED")
        Mockito.verify(commands).markExpiredIfPending("c2", "PENDING", "EXPIRED")
    }

    @Test
    fun `CAS 全部失败时不收敛关联截图记录`() {
        val shot = command("c3", "m3", type = "SCREENSHOT").apply {
            payloadJson = "{\"shotId\":\"shot-1\"}"
        }
        Mockito.`when`(commands.findExpiredPending("dev-1", "PENDING", 2000L)).thenReturn(listOf(shot))
        Mockito.`when`(redelivery.isEligible(shot, 2000L)).thenReturn(false)
        Mockito.`when`(commands.markExpiredIfPending("c3", "PENDING", "EXPIRED")).thenReturn(0)

        sweeper.sweep("dev-1", 2000L)

        Mockito.verifyNoInteractions(screenshots)
    }
}
