package com.padguard.server.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import java.nio.file.Path
import java.sql.DriverManager

/**
 * `/health` 存活探针的行为测试（纯 JVM，不加载 Spring 上下文）。
 *
 * 本测试锁死新增的存储可用性检查：旧实现只探 DB，磁盘满 / uploads 被误删或换成文件
 * 时仍报 UP，上传静默失败而运维无从发现。每个用例用独立 HealthController 实例，
 * 避免 30s 探针缓存跨用例串扰（缓存语义本身由最后一个用例专门锁定）。
 */
class HealthControllerTest {

    @TempDir
    lateinit var tempDir: Path

    private fun dataSource() = SimpleDriverDataSource(
        DriverManager.getDriver(DB_URL), DB_URL, "sa", ""
    )

    @Test
    fun `writable storage and healthy db report all UP and leave no probe file`() {
        val controller = HealthController(dataSource(), tempDir.toString())

        val response = controller.health()

        assertEquals(200, response.statusCode.value())
        val body = response.body!!
        assertEquals("UP", body["status"])
        assertEquals("UP", body["db"])
        assertEquals("UP", body["storage"])
        assertTrue(body.containsKey("ready"), "ready 字段必须在返回体中")
        val leftovers = tempDir.toFile().listFiles()?.filter { it.name.startsWith(".health-") } ?: emptyList()
        assertFalse(leftovers.isNotEmpty(), "探针文件写后必须删除，不给媒体目录留垃圾")
    }

    @Test
    fun `storage path that is a regular file reports storage DOWN with 503`() {
        val blocked = tempDir.resolve("not-a-dir").toFile().apply { writeText("x") }
        val controller = HealthController(dataSource(), blocked.absolutePath)

        val response = controller.health()

        assertEquals(503, response.statusCode.value())
        val body = response.body!!
        assertEquals("DOWN", body["storage"])
        assertEquals("DOWN", body["status"])
        assertEquals("UP", body["db"], "存储故障不应污染 DB 探测结果")
    }

    @Test
    fun `missing storage dir is created just like FileStorageService does`() {
        // FileStorageService 初始化时会自动 mkdirs；目录不存在本身不算故障，
        // 探针必须按同款语义建目录，否则全新部署的第一次探活会误报 DOWN。
        val target = tempDir.resolve("nested").resolve("uploads")
        val controller = HealthController(dataSource(), target.toString())

        val response = controller.health()

        assertEquals("UP", response.body!!["storage"])
        assertTrue(target.toFile().isDirectory, "缺失的存储目录应被自动创建")
    }

    @Test
    fun `storage probe result is cached within ttl`() {
        val target = tempDir.resolve("probe-dir")
        val controller = HealthController(dataSource(), target.toString())

        val first = controller.health()
        assertEquals("UP", first.body!!["storage"])

        // 缓存期内把目录换成普通文件：若真去探测必然 DOWN，缓存生效应仍返回 UP
        target.toFile().deleteRecursively()
        target.toFile().writeText("x")
        val second = controller.health()

        assertEquals("UP", second.body!!["storage"], "30s 缓存期内不应重复落盘探测")
    }

    companion object {
        private const val DB_URL =
            "jdbc:h2:mem:healthprobe;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
    }
}
