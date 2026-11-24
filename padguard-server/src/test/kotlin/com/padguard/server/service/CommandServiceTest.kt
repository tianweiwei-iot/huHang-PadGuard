package com.padguard.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.Command
import com.padguard.server.dto.CommandPacket
import com.padguard.server.mqtt.MqttGateway
import com.padguard.server.repository.CommandRepository
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.security.DeviceOwnershipGuard
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable

/**
 * `CommandService` 是家长指令下发的信任关键服务端（锁屏/解锁/改策略都经此）。
 * 此前**零单测**，且 `pendingForPolling` 的游标时钟偏移处理是历史上"指令永远不生效 /
 * 重复执行"的真实根因——必须在门禁里锁死。
 *
 * 覆盖：
 * - `getCommand`：查不到抛 PARAM_ERROR、归属校验传导、payload 解析、损坏 payload 不抛（降级 null）；
 * - `pendingForPolling` 游标钳制：since∈[1,now] 原样用、since>now 回退 now-grace、since<=0 归零；
 *   以及补发指令合并 + FIFO 升序（distinctBy 后按 createdAt 排序）；
 * - `registerWaiter`：已有待办立即同步触发并返回 false、无待办则挂起返回 true。
 *
 * `afterCommit` 在无事务的单元测试下会立即执行其块（见 [com.padguard.server.service.AfterCommit]），
 * 故 `issueCommand` 的下游副作用会被直接落到 mock 上；本测试不覆盖 `issueCommand`（HMAC 签名 /
 * MQTT 下发属于端到端链路，由集成测试守护），只锁死纯逻辑与查询分支。
 * 不加载 Spring 上下文，沿用既定 Mockito 占位范式（非空参数 matcher 补 `?: 默认值`；
 * `any()` 仅用于可空参数，`Pageable` 用 `any(Pageable::class.java) ?: PageRequest.of(0,1)`）。
 */
class CommandServiceTest {
    private val deviceRepository = mock(DeviceRepository::class.java)
    private val commandRepository = mock(CommandRepository::class.java)
    private val mqttGateway = mock(MqttGateway::class.java)
    private val objectMapper = ObjectMapper()
    private val expiredCommandSweeper = mock(ExpiredCommandSweeper::class.java)
    private val auditService = mock(AuditService::class.java)
    private val ownershipGuard = mock(DeviceOwnershipGuard::class.java)
    private val commandRedelivery = mock(CommandRedeliveryService::class.java)

    private fun svc(): CommandService = CommandService(
        deviceRepository, commandRepository, mqttGateway, objectMapper,
        expiredCommandSweeper, auditService, ownershipGuard, commandRedelivery
    )

    private val deviceId = "d1"
    private val userId = "u1"

    private fun cmd(
        msgId: String, createdAt: Long, payloadJson: String? = null,
        expiresAt: Long = 0, prio: String = "NORMAL", sig: String = "s"
    ): Command = Command().apply {
        this.msgId = msgId
        this.deviceId = deviceId
        this.type = "X"
        this.createdAt = createdAt
        this.payloadJson = payloadJson
        this.expiresAt = expiresAt
        this.priority = prio
        this.signature = sig
        this.status = "PENDING"
    }

    private fun stubPollReturns(list: List<Command>) {
        `when`(commandRedelivery.collectRedeliverable(eq(deviceId) ?: deviceId, anyLong())).thenReturn(emptyList())
        `when`(
            commandRepository.findPendingForPolling(
                eq(deviceId) ?: deviceId, eq("PENDING") ?: "PENDING",
                anyLong(), anyLong(), any(Pageable::class.java) ?: PageRequest.of(0, 1)
            )
        ).thenReturn(list)
    }

    // ---- getCommand ----

    @Test
    fun getCommand_notFound_throws() {
        `when`(commandRepository.findByMsgId(anyString() ?: "")).thenReturn(null)

        assertThrows(BizException::class.java) { svc().getCommand(userId, "m1") }
    }

    @Test
    fun getCommand_ownershipMismatch_propagates() {
        `when`(commandRepository.findByMsgId(anyString() ?: "")).thenReturn(cmd("m1", 1))
        doThrow(BizException(ParentErr.PARAM_ERROR, "无权限", Audience.PARENT))
            .`when`(ownershipGuard).requireOwned(anyString() ?: "", anyString() ?: "")

        assertThrows(BizException::class.java) { svc().getCommand(userId, "m1") }
    }

    @Test
    fun getCommand_found_returnsParsedPayload() {
        `when`(commandRepository.findByMsgId(anyString() ?: "")).thenReturn(
            cmd("m1", 123, payloadJson = """{"k":"v"}""", expiresAt = 999)
        )

        val dto = svc().getCommand(userId, "m1")

        assertEquals("m1", dto.msgId)
        assertEquals("PENDING", dto.status)
        assertEquals(123, dto.createdAt)
        assertEquals(mapOf("k" to "v"), dto.payload)
    }

    @Test
    fun getCommand_corruptedPayload_degradesToNullWithoutThrow() {
        `when`(commandRepository.findByMsgId(anyString() ?: "")).thenReturn(
            cmd("m1", 1, payloadJson = "{not-json")
        )

        val dto = svc().getCommand(userId, "m1")

        assertNull(dto.payload)
    }

    // ---- pendingForPolling 游标钳制 ----

    @Test
    fun pendingForPolling_cursorUsesSinceWhenInRange() {
        var cursor: Long? = null
        `when`(commandRedelivery.collectRedeliverable(eq(deviceId) ?: deviceId, anyLong())).thenReturn(emptyList())
        `when`(
            commandRepository.findPendingForPolling(
                eq(deviceId) ?: deviceId, eq("PENDING") ?: "PENDING",
                anyLong(), anyLong(), any(Pageable::class.java) ?: PageRequest.of(0, 1)
            )
        ).thenAnswer { inv -> cursor = inv.arguments[3] as Long; emptyList<Command>() }

        svc().pendingForPolling(deviceId, 1000L)

        assertEquals(1000L, cursor)
    }

    @Test
    fun pendingForPolling_cursorClampsFutureSinceToNowMinusGrace() {
        var now: Long? = null
        var cursor: Long? = null
        `when`(commandRedelivery.collectRedeliverable(eq(deviceId) ?: deviceId, anyLong())).thenReturn(emptyList())
        `when`(
            commandRepository.findPendingForPolling(
                eq(deviceId) ?: deviceId, eq("PENDING") ?: "PENDING",
                anyLong(), anyLong(), any(Pageable::class.java) ?: PageRequest.of(0, 1)
            )
        ).thenAnswer { inv ->
            now = inv.arguments[2] as Long
            cursor = inv.arguments[3] as Long
            emptyList<Command>()
        }

        // since 落在未来：应回退到 now - CLOCK_SKEW_GRACE_MS（避免漏发间隙内的新指令）
        svc().pendingForPolling(deviceId, System.currentTimeMillis() + 1_000_000L)

        assertNotNull(now)
        assertEquals(now!! - CommandService.CLOCK_SKEW_GRACE_MS, cursor)
    }

    @Test
    fun pendingForPolling_cursorZeroWhenSinceNonPositive() {
        var cursor: Long? = null
        `when`(commandRedelivery.collectRedeliverable(eq(deviceId) ?: deviceId, anyLong())).thenReturn(emptyList())
        `when`(
            commandRepository.findPendingForPolling(
                eq(deviceId) ?: deviceId, eq("PENDING") ?: "PENDING",
                anyLong(), anyLong(), any(Pageable::class.java) ?: PageRequest.of(0, 1)
            )
        ).thenAnswer { inv -> cursor = inv.arguments[3] as Long; emptyList<Command>() }

        svc().pendingForPolling(deviceId, 0L)

        assertEquals(0L, cursor)
    }

    // ---- pendingForPolling 补发合并 + FIFO ----

    @Test
    fun pendingForPolling_mergesRedeliveryAndSortsByCreatedAtFifo() {
        val older = cmd("older", 100, expiresAt = 500)
        val newer = cmd("newer", 200, expiresAt = 800)
        // 补发的是更早创建的指令，刷新后的有效期应原样带入下发包
        `when`(commandRedelivery.collectRedeliverable(eq(deviceId) ?: deviceId, anyLong()))
            .thenReturn(listOf(RedeliveredCommand(older, 9999L)))
        `when`(
            commandRepository.findPendingForPolling(
                eq(deviceId) ?: deviceId, eq("PENDING") ?: "PENDING",
                anyLong(), anyLong(), any(Pageable::class.java) ?: PageRequest.of(0, 1)
            )
        ).thenReturn(listOf(newer))

        val packets: List<CommandPacket> = svc().pendingForPolling(deviceId, 1L)

        assertEquals(2, packets.size)
        // FIFO：更早创建的（补发）指令在前面
        assertEquals("older", packets[0].msgId)
        assertEquals(9999L, packets[0].expiresAt)
        assertEquals("newer", packets[1].msgId)
        assertEquals(800L, packets[1].expiresAt)
    }

    // ---- registerWaiter ----

    @Test
    fun registerWaiter_firesImmediatelyWhenPendingExists() {
        stubPollReturns(listOf(cmd("m1", 1)))
        var invoked = false

        val suspended = svc().registerWaiter(deviceId, 1L) { invoked = true }

        assertTrue(invoked)            // 有待办：同步触发 onReady
        assertFalse(suspended)         // 返回 false = 未挂起
    }

    @Test
    fun registerWaiter_suspendsWhenNoPending() {
        stubPollReturns(emptyList())
        var invoked = false

        val suspended = svc().registerWaiter(deviceId, 1L) { invoked = true }

        assertFalse(invoked)           // 无待办：不触发 onReady
        assertTrue(suspended)          // 返回 true = 已挂起
    }
}
