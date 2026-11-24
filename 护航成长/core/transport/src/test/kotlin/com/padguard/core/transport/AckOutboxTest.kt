package com.padguard.core.transport

import com.padguard.core.common.Logger
import com.padguard.core.data.model.AckStatus
import com.padguard.core.data.model.CommandAck
import kotlinx.serialization.json.Json
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AckOutboxTest {

    // 坏行清理会走 Logger.w；android.util.Log 在 JVM 单测里是未 mock 的桩。
    private var originalLoggerEnabled = true

    @Before
    fun disableLogger() {
        originalLoggerEnabled = Logger.enabled
        Logger.enabled = false
    }

    @After
    fun restoreLogger() {
        Logger.enabled = originalLoggerEnabled
    }

    private fun tmpFile(): File {
        val dir = File.createTempFile("acktest", "").apply { delete(); mkdirs() }
        return File(dir, "ack.jsonl")
    }

    private fun ack(id: String) =
        CommandAck(msgId = id, deviceId = "d1", status = AckStatus.SUCCESS, executedAt = 1L)

    @Test
    fun `enqueue then drain returns persisted acks`() {
        val box = AckOutbox(tmpFile(), Json { ignoreUnknownKeys = true })
        box.enqueue(ack("m1"))
        box.enqueue(ack("m2"))
        val drained = box.drain(10)
        assertEquals(2, drained.size)
        assertEquals(listOf("m1", "m2"), drained.map { it.msgId })
    }

    @Test
    fun `remove deletes by msgId and keeps the rest`() {
        val box = AckOutbox(tmpFile(), Json { ignoreUnknownKeys = true })
        box.enqueue(ack("m1"))
        box.enqueue(ack("m2"))
        box.remove(listOf(ack("m1")))
        val drained = box.drain(10)
        assertEquals(1, drained.size)
        assertEquals("m2", drained.first().msgId)
    }

    @Test
    fun `data survives file reopen simulating process restart`() {
        val file = tmpFile()
        val json = Json { ignoreUnknownKeys = true }
        AckOutbox(file, json).enqueue(ack("m1"))
        // 重新打开（模拟进程被杀重启）：磁盘上的回执必须还能读出来补发
        val reopened = AckOutbox(file, json)
        val drained = reopened.drain(10)
        assertEquals(1, drained.size)
        assertEquals("m1", drained.first().msgId)
    }

    @Test
    fun `drain respects batch size and does not delete until remove`() {
        val box = AckOutbox(tmpFile(), Json { ignoreUnknownKeys = true })
        repeat(5) { box.enqueue(ack("m$it")) }
        val first = box.drain(2)
        assertEquals(2, first.size)
        // 未调用 remove，磁盘仍在，下一轮仍能全部取出
        val second = box.drain(10)
        assertEquals(5, second.size)
    }

    @Test
    fun `empty outbox drain returns nothing`() {
        val box = AckOutbox(tmpFile(), Json { ignoreUnknownKeys = true })
        assertTrue(box.drain(10).isEmpty())
    }

    // ---------- 新增缺陷 J：坏行不得阻塞补发 ----------

    private fun encoded(json: Json, id: String) =
        json.encodeToString(CommandAck.serializer(), ack(id))

    /**
     * 核心回归：队首坏行数 >= batch 时，旧实现 `take(batch).mapNotNull{}` 恒返回空，
     * `flushAckRetryQueue` 直接 return，后面的好回执被永久饿死。
     */
    @Test
    fun `corrupt head lines do not starve valid acks`() {
        val file = tmpFile()
        val json = Json { ignoreUnknownKeys = true }
        file.writeText(
            listOf("BROKEN-1", "BROKEN-2", "BROKEN-3", encoded(json, "m1"), encoded(json, "m2"))
                .joinToString("\n")
        )
        val box = AckOutbox(file, json)
        assertEquals(listOf("m1", "m2"), box.drain(2).map { it.msgId })
    }

    @Test
    fun `drain purges corrupt lines from disk`() {
        val file = tmpFile()
        val json = Json { ignoreUnknownKeys = true }
        file.writeText(listOf("BROKEN-1", encoded(json, "m1"), "BROKEN-2").joinToString("\n"))
        val box = AckOutbox(file, json)
        assertEquals(listOf("m1"), box.drain(10).map { it.msgId })
        assertFalse("坏行应从磁盘剔除", file.readText().contains("BROKEN"))
        // 跨实例重启后仍只有好行
        assertEquals(listOf("m1"), AckOutbox(file, json).drain(10).map { it.msgId })
    }

    @Test
    fun `remove also purges corrupt lines`() {
        val file = tmpFile()
        val json = Json { ignoreUnknownKeys = true }
        file.writeText(listOf("BROKEN", encoded(json, "m1"), encoded(json, "m2")).joinToString("\n"))
        val box = AckOutbox(file, json)
        box.remove(listOf(ack("m1")))
        assertEquals(listOf("m2"), box.drain(10).map { it.msgId })
        assertFalse(file.readText().contains("BROKEN"))
    }

    /** 坏行不应占满上限把新回执挤出去。 */
    @Test
    fun `corrupt lines do not consume the maxLines budget`() {
        val file = tmpFile()
        val json = Json { ignoreUnknownKeys = true }
        file.writeText((1..3).joinToString("\n") { "BAD-$it" })
        val box = AckOutbox(file, json, maxLines = 3)
        box.enqueue(ack("new"))
        assertEquals(listOf("new"), box.drain(10).map { it.msgId })
        assertFalse(file.readText().contains("BAD-"))
    }
}
