package com.padguard.core.transport

import com.padguard.core.common.Logger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * [JsonLinesFile] 的落盘可靠性回归（新增缺陷 J）。
 *
 * 说明：桌面 JVM（Windows）上 `File.renameTo` 到**已存在**目标会失败，因此这些用例
 * 天然覆盖了「降级覆写 + tmp 残留恢复」路径；Android/Linux 的 POSIX 原子 rename 覆盖
 * 语义相反（目标存在也成功），那条路径纯 JVM 测不到，需真机回归，见评审报告边界说明。
 */
class JsonLinesFileTest {

    // android.util.Log 在 JVM 单测里是未 mock 的桩；本类会走降级/恢复/失败分支并打日志。
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

    private fun fixture(): File {
        val dir = File.createTempFile("jsonlines", "").apply { delete(); mkdirs() }
        return File(dir, "queue.jsonl")
    }

    private fun tmpOf(file: File) = File(file.parentFile, file.name + ".tmp")
    private fun okOf(file: File) = File(file.parentFile, file.name + ".tmp.ok")

    @Test
    fun `write then read returns committed lines`() {
        val store = JsonLinesFile(fixture())
        assertTrue(store.write(listOf("A", "B", "C")))
        assertEquals(listOf("A", "B", "C"), store.read())
    }

    @Test
    fun `overwrite does not keep stale lines`() {
        val store = JsonLinesFile(fixture())
        assertTrue(store.write(listOf("OLD-1", "OLD-2", "OLD-3")))
        assertTrue(store.write(listOf("NEW")))
        assertEquals(listOf("NEW"), store.read())
    }

    @Test
    fun `repeated writes keep the latest snapshot`() {
        val store = JsonLinesFile(fixture())
        assertTrue(store.write(listOf("1")))
        assertTrue(store.write(listOf("1", "2")))
        assertTrue(store.write(listOf("1", "2", "3")))
        assertEquals(listOf("1", "2", "3"), store.read())
    }

    @Test
    fun `writing an empty list clears the queue`() {
        val store = JsonLinesFile(fixture())
        assertTrue(store.write(listOf("X")))
        assertTrue(store.write(emptyList()))
        assertTrue(store.read().isEmpty())
    }

    @Test
    fun `blank lines are filtered on read`() {
        val file = fixture()
        file.writeText("A\n\nB\n\n")
        assertEquals(listOf("A", "B"), JsonLinesFile(file).read())
    }

    @Test
    fun `missing file reads as empty`() {
        assertTrue(JsonLinesFile(fixture()).read().isEmpty())
    }

    @Test
    fun `data survives a new instance simulating process restart`() {
        val file = fixture()
        assertTrue(JsonLinesFile(file).write(listOf("P1", "P2")))
        assertEquals(listOf("P1", "P2"), JsonLinesFile(file).read())
    }

    /**
     * 核心回归：降级覆写中途被杀会留下「半行的 file + 完整的 tmp」。
     * 旧实现只读 file，等于把最后一次完整快照丢掉；现在必须优先恢复 tmp。
     */
    @Test
    fun `read recovers an uncommitted tmp snapshot`() {
        val file = fixture()
        file.writeText("HALF-BROKEN")
        tmpOf(file).writeText("FULL-1\nFULL-2")
        okOf(file).createNewFile()   // 标记 = 内容已完整写入，仅提交未完成
        val store = JsonLinesFile(file)
        assertEquals(listOf("FULL-1", "FULL-2"), store.read())
        assertFalse("恢复后 tmp 应已被消费", tmpOf(file).exists())
        assertFalse("恢复后完成标记应被清理", okOf(file).exists())
        assertEquals(listOf("FULL-1", "FULL-2"), JsonLinesFile(file).read())
    }

    /** file 从未创建、tmp 是唯一完整副本时也必须能恢复。 */
    @Test
    fun `read recovers tmp when the main file is absent`() {
        val file = fixture()
        tmpOf(file).writeText("ONLY-1\nONLY-2")
        okOf(file).createNewFile()
        assertEquals(listOf("ONLY-1", "ONLY-2"), JsonLinesFile(file).read())
        assertTrue(file.exists())
    }

    /**
     * 新增缺陷 K 的核心回归：tmp 写一半被 SIGKILL / 磁盘满截断时没有完成标记，
     * 必须丢弃半行 tmp 并保住完好 file，否则整队离线数据被一次清空。
     */
    @Test
    fun `read discards incomplete tmp without commit marker and keeps the intact file`() {
        val file = fixture()
        file.writeText("INTACT-1\nINTACT-2")
        tmpOf(file).writeText("TORN-")   // 半行，且无 ok 标记
        assertFalse(okOf(file).exists())
        assertEquals(listOf("INTACT-1", "INTACT-2"), JsonLinesFile(file).read())
        assertFalse("半行 tmp 应已丢弃", tmpOf(file).exists())
        assertEquals(listOf("INTACT-1", "INTACT-2"), JsonLinesFile(file).read())
    }

    /** 上次写到一半被杀、本轮重新完整写入时，旧标记不得让半行 tmp 误恢复。 */
    @Test
    fun `write clears stale commit marker before writing`() {
        val file = fixture()
        okOf(file).createNewFile()     // 上轮残留的标记
        tmpOf(file).writeText("STALE-HALF")
        val store = JsonLinesFile(file)
        assertTrue(store.write(listOf("FRESH")))
        assertEquals(listOf("FRESH"), store.read())
        assertTrue("完整写入后标记应被清理", !okOf(file).exists())
    }

    /**
     * 读失败必须与"队列为空"可区分：否则调用方的 remove 会顺势写一份空文件，
     * 把整队离线数据一次清空（磁盘瞬时异常时的静默丢失）。
     */
    @Test
    fun `readOrNull distinguishes an unreadable file from an empty queue`() {
        val file = fixture()
        val store = JsonLinesFile(file)
        assertEquals(emptyList<String>(), store.readOrNull())   // 文件不存在 = 真空队列
        assertTrue(store.write(listOf("A")))
        assertEquals(listOf("A"), store.readOrNull())
        // 入口变成目录 → readLines 抛异常 → 必须是 null，绝不能折叠成 emptyList
        assertTrue(file.delete())
        assertTrue(file.mkdirs())
        assertEquals(null, store.readOrNull())
        assertEquals(emptyList<String>(), store.read())          // 便利重载仍折叠为空
    }

    /**
     * 写失败必须可见（返回 false），且**提交前的旧数据一步都不许动**：
     * 这正是「绝不预先 delete」的意义——最坏只丢最后一次写，而不是整队丢失。
     */
    @Test
    fun `failed write is reported and leaves previous data intact`() {
        val file = fixture()
        val store = JsonLinesFile(file)
        assertTrue(store.write(listOf("OLD-1", "OLD-2")))
        // 把 tmp 变成目录，使下一次 tmp.writeText 抛 IOException
        tmpOf(file).delete()
        assertTrue(tmpOf(file).mkdirs())
        assertFalse(store.write(listOf("NEW")))
        assertEquals(
            listOf("OLD-1", "OLD-2"),
            file.readLines().filter { it.isNotBlank() }
        )
    }
}
