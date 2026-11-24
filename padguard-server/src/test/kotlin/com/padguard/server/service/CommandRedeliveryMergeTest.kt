package com.padguard.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.domain.Command
import com.padguard.server.mqtt.MqttGateway
import com.padguard.server.repository.CommandRepository
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.security.DeviceOwnershipGuard
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable

/**
 * W2：轮询下发**合并层**回归。
 *
 * [CommandService.pendingForPolling] 是所有下行通道的公共出口（HTTP 长轮询、
 * notifyWaiters 唤醒、MQTT 上线后补拉）。补发指令在这里与新生 PENDING 指令合流，
 * 有三条极易改坏且现象隐蔽的语义：
 * 1. FIFO —— 补发的都是更早创建的指令，必须排在新生指令前，否则有序操作被倒序重放；
 * 2. 去重 —— cursor=0（notifyWaiters）时常规查询也会捞到刚刷新的指令，
 *    同一 msgId 出现两次会污染 ack 与幂等统计；
 * 3. 新有效期 —— 补发条目的实体快照里仍是**旧的过期** expiresAt，
 *    必须用刷新后的值，否则终端 CommandGate 第一关就判 expired 拒收。
 */
class CommandRedeliveryMergeTest {

    private lateinit var commandRepo: CommandRepository
    private lateinit var redelivery: CommandRedeliveryService
    private lateinit var service: CommandService

    private companion object {
        const val REFRESHED_EXPIRY = 9_900_000L
        const val FRESH_EXPIRY = 9_800_000L
    }

    @BeforeEach
    fun setUp() {
        commandRepo = Mockito.mock(CommandRepository::class.java)
        redelivery = Mockito.mock(CommandRedeliveryService::class.java)
        service = CommandService(
            Mockito.mock(DeviceRepository::class.java),
            commandRepo,
            Mockito.mock(MqttGateway::class.java),
            ObjectMapper(),
            Mockito.mock(ExpiredCommandSweeper::class.java),
            Mockito.mock(AuditService::class.java),
            Mockito.mock(DeviceOwnershipGuard::class.java),
            redelivery
        )
    }

    private fun cmd(
        msgId: String, createdAt: Long, expiresAt: Long, type: String = "REFRESH_POLICY"
    ) = Command(
        id = "id-$msgId", deviceId = "dev-1", msgId = msgId, type = type,
        priority = "HIGH", payloadJson = null, signature = "sig-$msgId",
        status = "PENDING", expiresAt = expiresAt, createdAt = createdAt
    )

    private fun stub(
        redelivered: List<RedeliveredCommand> = emptyList(),
        fresh: List<Command> = emptyList()
    ) {
        // 注意：Kotlin 非空参数下 Mockito.eq()/any(Class) 会返回 null 并触发参数 checkNotNull NPE，
        // 残留的 matcher 还会污染同线程后续用例（表现为 setUp 报 InvalidUseOfMatchersException）。
        // 因此这里全用返回非空值的 anyString()/anyLong()；Pageable 用 Elvis 兜一个非空占位，
        // matcher 已在参数求值期注册，传入值本身不参与匹配。
        Mockito.`when`(redelivery.collectRedeliverable(Mockito.anyString(), Mockito.anyLong()))
            .thenReturn(redelivered)
        Mockito.`when`(
            commandRepo.findPendingForPolling(
                Mockito.anyString(), Mockito.anyString(),
                Mockito.anyLong(), Mockito.anyLong(),
                Mockito.any(Pageable::class.java) ?: PageRequest.of(0, 50)
            )
        ).thenReturn(fresh)
    }

    @Test
    fun `redelivered commands come first and carry the refreshed expiry`() {
        val old = cmd("msg-old", createdAt = 1_000L, expiresAt = 2_000L /* 实体仍是旧过期值 */)
        val fresh = cmd("msg-new", createdAt = 5_000L, expiresAt = FRESH_EXPIRY)
        stub(
            redelivered = listOf(RedeliveredCommand(old, REFRESHED_EXPIRY)),
            fresh = listOf(fresh)
        )

        val packets = service.pendingForPolling("dev-1", 1_000L)

        assertEquals(listOf("msg-old", "msg-new"), packets.map { it.msgId }, "补发的老指令必须排在前")
        assertEquals(REFRESHED_EXPIRY, packets[0].expiresAt, "绝不能把实体里的旧过期值下发")
        assertEquals(FRESH_EXPIRY, packets[1].expiresAt)
    }

    @Test
    fun `same msgId seen by both channels is delivered once with the winning refreshed expiry`() {
        // notifyWaiters 用 cursor=0，常规查询会把刚被刷新的那条也捞出来。
        val redelivered = cmd("msg-a", createdAt = 1_000L, expiresAt = 2_000L)
        val freshDuplicate = cmd("msg-a", createdAt = 1_000L, expiresAt = FRESH_EXPIRY)
        stub(
            redelivered = listOf(RedeliveredCommand(redelivered, REFRESHED_EXPIRY)),
            fresh = listOf(freshDuplicate)
        )

        val packets = service.pendingForPolling("dev-1", 0L)

        assertEquals(1, packets.size, "同一 msgId 不能在单批里下发两次")
        assertEquals(REFRESHED_EXPIRY, packets[0].expiresAt, "去重应保留带确定新有效期的补发条目")
    }

    @Test
    fun `merge does not alter any terminal-verifiable contract field`() {
        val c = cmd("msg-a", createdAt = 1_234L, expiresAt = 2_000L)
        stub(redelivered = listOf(RedeliveredCommand(c, REFRESHED_EXPIRY)))

        val p = service.pendingForPolling("dev-1", 0L).single()

        assertEquals("msg-a", p.msgId)
        assertEquals("REFRESH_POLICY", p.type)
        assertEquals(1_234L, p.timestamp, "timestamp 必须仍是 createdAt，改了签名就对不上")
        assertEquals("sig-msg-a", p.signature)
        assertEquals("HIGH", p.priority)
        assertEquals(1, p.version)
    }

    @Test
    fun `merge is capped at MAX_POLLING_BATCH even when both channels are full`() {
        val many = (1..60).map { i -> cmd("msg-$i", createdAt = i.toLong(), expiresAt = FRESH_EXPIRY) }
        stub(fresh = many)

        val packets = service.pendingForPolling("dev-1", 0L)

        assertEquals(CommandService.MAX_POLLING_BATCH, packets.size)
    }

    @Test
    fun `no redelivery candidates means byte-for-byte old behaviour`() {
        val fresh = listOf(
            cmd("msg-1", createdAt = 1_000L, expiresAt = FRESH_EXPIRY),
            cmd("msg-2", createdAt = 2_000L, expiresAt = FRESH_EXPIRY)
        )
        stub(redelivered = emptyList(), fresh = fresh)

        val packets = service.pendingForPolling("dev-1", 0L)

        assertEquals(listOf("msg-1", "msg-2"), packets.map { it.msgId })
        assertEquals(FRESH_EXPIRY, packets[0].expiresAt)
    }
}
