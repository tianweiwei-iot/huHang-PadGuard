package com.padguard.core.transport

import com.padguard.core.common.Logger
import kotlinx.serialization.json.Json
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EyeCareRestOutboxTest {

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
        val dir = File.createTempFile("eyecaretest", "").apply { delete(); mkdirs() }
        return File(dir, "eyecare.jsonl")
    }

    private fun entry(id: String, ms: Long = 600_000L) = EyeCareRestOutbox.Entry(id, ms)

    @Test
    fun `enqueue then drain returns persisted entries`() {
        val box = EyeCareRestOutbox(tmpFile(), Json { ignoreUnknownKeys = true })
        box.enqueue("r1", 600_000)
        box.enqueue("r2", 300_000)
        val drained = box.drain(10)
        assertEquals(2, drained.size)
        assertEquals(listOf("r1", "r2"), drained.map { it.restId })
        assertEquals(listOf(600_000L, 300_000L), drained.map { it.restMs })
    }

    @Test
    fun `remove deletes by restId and keeps the rest`() {
        val box = EyeCareRestOutbox(tmpFile(), Json { ignoreUnknownKeys = true })
        box.enqueue("r1", 600_000)
        box.enqueue("r2", 300_000)
        box.remove(listOf(entry("r1")))
        val drained = box.drain(10)
        assertEquals(1, drained.size)
        assertEquals("r2", drained.first().restId)
    }

    @Test
    fun `data survives file reopen simulating process restart`() {
        val file = tmpFile()
        val json = Json { ignoreUnknownKeys = true }
        EyeCareRestOutbox(file, json).enqueue("r1", 600_000)
        // 重新打开（模拟进程被杀重启）：磁盘上的上报必须还能读出来补报
        val reopened = EyeCareRestOutbox(file, json)
        val drained = reopened.drain(10)
        assertEquals(1, drained.size)
        assertEquals("r1", drained.first().restId)
        assertEquals(600_000L, drained.first().restMs)
    }

    @Test
    fun `drain respects batch size and does not delete until remove`() {
        val box = EyeCareRestOutbox(tmpFile(), Json { ignoreUnknownKeys = true })
        repeat(5) { box.enqueue("r$it", 1000L * it) }
        val first = box.drain(2)
        assertEquals(2, first.size)
        // 未调用 remove，磁盘仍在，下一轮仍能全部取出
        val second = box.drain(10)
        assertEquals(5, second.size)
    }

    @Test
    fun `empty outbox drain returns nothing`() {
        val box = EyeCareRestOutbox(tmpFile(), Json { ignoreUnknownKeys = true })
        assertTrue(box.drain(10).isEmpty())
    }

    @Test
    fun `exceeding maxLines drops oldest`() {
        val box = EyeCareRestOutbox(tmpFile(), Json { ignoreUnknownKeys = true }, maxLines = 3)
        repeat(5) { box.enqueue("r$it", 1000L) }
        val drained = box.drain(10)
        assertEquals(3, drained.size)
        // 最旧的 r0/r1 被丢弃，保留 r2/r3/r4
        assertEquals(listOf("r2", "r3", "r4"), drained.map { it.restId })
    }

    // ---------- 新增缺陷 J：坏行不得占用有界队列名额 ----------

    private fun encoded(json: Json, id: String) =
        json.encodeToString(EyeCareRestOutbox.Entry.serializer(), entry(id))

    /**
     * 本队列上限与批次同为 32，坏行虽不至于让 drain 恒空，却会占满名额把有效的
     * 休息扣减挤出去 → 孩子被多计额度。清理坏行后必须腾出完整名额。
     */
    @Test
    fun `corrupt lines do not consume the bounded queue`() {
        val file = tmpFile()
        val json = Json { ignoreUnknownKeys = true }
        file.writeText(
            (listOf("BAD-1", "BAD-2", "BAD-3", "BAD-4") +
                listOf(encoded(json, "r1"), encoded(json, "r2"))).joinToString("\n")
        )
        val box = EyeCareRestOutbox(file, json, maxLines = 6)
        assertEquals(listOf("r1", "r2"), box.drain(6).map { it.restId })
        assertFalse("坏行应从磁盘剔除", file.readText().contains("BAD-"))
        box.enqueue("r3", 1000L)
        assertEquals(listOf("r1", "r2", "r3"), box.drain(10).map { it.restId })
    }

    @Test
    fun `remove purges corrupt lines`() {
        val file = tmpFile()
        val json = Json { ignoreUnknownKeys = true }
        file.writeText(listOf("BROKEN", encoded(json, "r1"), encoded(json, "r2")).joinToString("\n"))
        val box = EyeCareRestOutbox(file, json)
        box.remove(listOf(entry("r1")))
        assertEquals(listOf("r2"), box.drain(10).map { it.restId })
        assertFalse(file.readText().contains("BROKEN"))
    }
}
