package com.padguard.server.mqtt

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.padguard.server.domain.Command
import com.padguard.server.repository.CommandRepository
import com.padguard.server.repository.DeviceEventRepository
import com.padguard.server.service.AlertService
import com.padguard.server.service.AppDistributionConverger
import com.padguard.server.service.DeviceService
import com.padguard.server.service.FakeJpaRepository
import com.padguard.server.ws.WebSocketPushService
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.data.domain.Pageable

/**
 * 指令回执与 sweeper 的两侧抢占护栏。
 *
 * `applyAck` 原实现是「读实体 → 改字段 → save(整行)」。MQTT 与 HTTP 降级是双通道，
 * 设备重连时回执与过期收敛常并发：两边都读到 PENDING，sweeper 先写 EXPIRED，
 * 回执随后 merge 旧快照，把 **EXPIRED 复活成 SUCCESS**，家长端看到「已过期又执行成功」。
 *
 * 假仓储的 `findByMsgIdAndDeviceId` 刻意返回**独立快照**，并允许在「读之后」注入
 * sweeper 的抢占，把这个原本靠时序偶发的竞态变成确定性可复现。
 */
class ChildUplinkAckCasTest {

    private val DEVICE = "dev-ack-1"
    private val USER = "user-ack-1"
    private val MSG = "msg-ack-1"

    private class FakeCommandRepo : FakeJpaRepository<Command, String>(), CommandRepository {
        override val table = LinkedHashMap<String, Command>()

        private fun Command.snapshot() = Command(
            id = id, deviceId = deviceId, msgId = msgId, type = type, priority = priority,
            payloadJson = payloadJson, signature = signature, status = status,
            expiresAt = expiresAt, createdAt = createdAt, executedAt = executedAt,
            errorCode = errorCode, errorMessage = errorMessage,
            deliveryCount = deliveryCount, redeliveryDeadlineAt = redeliveryDeadlineAt,
            lastDeliveryAt = lastDeliveryAt
        )
        override fun idOf(e: Command) = e.id

        /** 在「回执读到旧快照之后」执行，用来模拟 sweeper 抢先提交 EXPIRED。 */
        var onReadSnapshot: ((Command) -> Unit)? = null

        override fun findByMsgId(msgId: String): Command? = table.values.firstOrNull { it.msgId == msgId }
        override fun findByMsgIdAndDeviceId(msgId: String, deviceId: String): Command? {
            val row = table.values.firstOrNull { it.msgId == msgId && it.deviceId == deviceId } ?: return null
            val snap = row.snapshot()
            onReadSnapshot?.invoke(row)
            return snap
        }
        override fun findByDeviceIdOrderByCreatedAtDesc(deviceId: String) =
            table.values.filter { it.deviceId == deviceId }.sortedByDescending { it.createdAt }
        override fun findByDeviceIdOrderByCreatedAtAsc(deviceId: String) =
            table.values.filter { it.deviceId == deviceId }.sortedBy { it.createdAt }
        override fun findPendingForPolling(deviceId: String, status: String, now: Long, cursor: Long, pageable: Pageable) =
            emptyList<Command>()
        override fun findExpiredPending(deviceId: String, status: String, now: Long) = emptyList<Command>()
        override fun markExpiredIfPending(id: String, pending: String, expired: String): Int {
            val cmd = table[id] ?: return 0
            if (cmd.status != pending) return 0
            cmd.status = expired
            return 1
        }
        override fun applyAckIfOpen(
            msgId: String, deviceId: String, terminal: Collection<String>, status: String,
            executedAt: Long?, errorCode: String?, errorMessage: String?
        ): Int {
            val cmd = table.values.firstOrNull { it.msgId == msgId && it.deviceId == deviceId } ?: return 0
            if (cmd.status in terminal) return 0
            cmd.status = status
            cmd.executedAt = executedAt
            cmd.errorCode = errorCode
            cmd.errorMessage = errorMessage
            return 1
        }
        override fun findRedeliverablePending(deviceId: String, status: String, now: Long, maxAttempts: Int, types: Collection<String>, pageable: Pageable) =
            emptyList<Command>()
        override fun tryRefreshForRedelivery(id: String, status: String, now: Long, newExpiresAt: Long): Int = 0
        override fun deleteByDeviceId(deviceId: String): Int {
            val before = table.size
            table.values.removeIf { it.deviceId == deviceId }
            return before - table.size
        }
    }

    private class RecordingPush : WebSocketPushService(ObjectMapper(), users = com.padguard.server.support.PushTestUsers.permissive()) {
        val events = mutableListOf<Map<String, Any?>>()
        override fun pushToUser(userId: String?, payload: Map<String, Any?>) {
            if (userId != null) events += payload
        }
    }

    private val repo = FakeCommandRepo()
    private val push = RecordingPush()
    private val deviceService = Mockito.mock(DeviceService::class.java)

    private val handler: ChildUplinkHandler by lazy {
        Mockito.`when`(deviceService.getUserId(DEVICE)).thenReturn(USER)
        ChildUplinkHandler(
            null,
            jacksonObjectMapper(),
            repo,
            Mockito.mock(DeviceEventRepository::class.java),
            deviceService,
            push,
            Mockito.mock(AlertService::class.java),
            Mockito.mock(AppDistributionConverger::class.java),
            true
        )
    }

    private fun seed(status: String = "PENDING", errorCode: String? = null, errorMessage: String? = null) {
        repo.table["cmd-1"] = Command(
            id = "cmd-1", deviceId = DEVICE, msgId = MSG, type = "LOCK",
            status = status, errorCode = errorCode, errorMessage = errorMessage
        )
        push.events.clear()
    }

    private fun ack(status: String = "SUCCESS", error: String? = null) {
        val err = if (error == null) "" else ""","errorCode":"$error""""
        handler.handleAck(DEVICE, """{"msgId":"$MSG","status":"$status","executedAt":12345$err}""")
    }

    private fun current() = repo.table["cmd-1"]!!

    @Test
    @DisplayName("EXPIRED 不得被迟到回执复活成 SUCCESS（并发抢占）")
    fun `expired command must not be resurrected by a late ack`() {
        seed("PENDING")
        // 回执读到 PENDING 快照之后，sweeper 抢先把库里的行收敛为 EXPIRED
        repo.onReadSnapshot = { row -> row.status = "EXPIRED" }

        ack("SUCCESS")

        assertEquals("EXPIRED", current().status, "整行 save 会把旧快照的 SUCCESS 覆盖回来，复活已过期指令")
        assertTrue(push.events.isEmpty(), "没抢到写入权的回执不得推 command.result")
    }

    @Test
    @DisplayName("正常路径：SUCCESS 落地一次并推一次 command.result")
    fun `successful ack is applied and pushed once`() {
        seed("PENDING")
        ack("SUCCESS")
        assertEquals("SUCCESS", current().status)
        assertEquals(12345L, current().executedAt)
        assertEquals(1, push.events.size)
        assertEquals("command.result", push.events.single()["type"])
    }

    @Test
    @DisplayName("终态指令忽略迟到回执")
    fun `terminal status rejects late ack`() {
        seed("EXPIRED")
        ack("SUCCESS")
        assertEquals("EXPIRED", current().status)
        assertTrue(push.events.isEmpty())
    }

    @Test
    @DisplayName("回执不带错误时保留既有错误码（旧规则不得回归）")
    fun `ack without error preserves existing error code`() {
        seed("FAILED", errorCode = "E_LOCK", errorMessage = "锁屏失败")
        ack("SUCCESS")
        assertEquals("SUCCESS", current().status)
        assertEquals("E_LOCK", current().errorCode)
        assertEquals("锁屏失败", current().errorMessage)
    }

    @Test
    @DisplayName("回执带错误时覆盖错误码")
    fun `ack with error overwrites error code`() {
        seed("PENDING")
        ack("FAILED", error = "E_NEW")
        assertEquals("FAILED", current().status)
        assertEquals("E_NEW", current().errorCode)
    }
}
