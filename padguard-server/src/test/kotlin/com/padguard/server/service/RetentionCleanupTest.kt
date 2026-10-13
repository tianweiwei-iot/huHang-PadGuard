package com.padguard.server.service

import com.padguard.server.domain.LocationTrack
import com.padguard.server.domain.MediaTask
import com.padguard.server.domain.Screenshot
import com.padguard.server.domain.UploadedFile
import com.padguard.server.repository.LocationTrackRepository
import com.padguard.server.repository.MediaTaskRepository
import com.padguard.server.repository.ScreenshotRepository
import com.padguard.server.repository.UploadedFileRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * E11（L-OPS-06）保留期清理与磁盘防护的 JVM 行为测试。
 *
 * 钉住三条命脉：
 * 1. **记录与文件必须成对删除** —— 只删记录留下永远躺着的孤儿文件（uploads 涨满的根因），
 *    只删文件留下点开 404 的记录；两条路都是"界面说成功、实际坏了"。
 * 2. **家长上传素材不在清理范围**（device_id 为空）—— 信息发布图片/APK 被引用，误删即功能损坏。
 * 3. idFromUrl 对相对/绝对 URL 都要能解析，脏数据返回 null 而不是抛异常打断整个清理任务。
 */
class RetentionCleanupTest {

    private lateinit var tempDir: File
    private lateinit var storage: FileStorageService
    private lateinit var uploadedFiles: FakeUploadedFileRepo
    private lateinit var locations: FakeLocationTrackRepo
    private lateinit var screenshots: FakeScreenshotRepo
    private lateinit var mediaTasks: FakeMediaTaskRepo
    private lateinit var cleanup: RetentionCleanup

    private val day = 86_400_000L

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("retention-test").toFile()
        uploadedFiles = FakeUploadedFileRepo()
        storage = FileStorageService(tempDir.absolutePath, "", uploadedFiles)
        locations = FakeLocationTrackRepo()
        screenshots = FakeScreenshotRepo()
        mediaTasks = FakeMediaTaskRepo()
        cleanup = RetentionCleanup(locations, screenshots, mediaTasks, uploadedFiles, storage)
    }

    @AfterEach
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // ==================== FileStorageService 辅助能力 ====================

    @Test
    fun `idFromUrl parses relative and absolute urls`() {
        assertEquals("abc123", storage.idFromUrl("/v1/files/abc123"), "相对路径 URL")
        assertEquals("abc123", storage.idFromUrl("http://8.148.68.183:8090/v1/files/abc123"), "绝对路径 URL")
        assertEquals("abc123", storage.idFromUrl("/v1/files/abc123?token=x"), "带查询参数")
        assertNull(storage.idFromUrl(null), "null 输入")
        assertNull(storage.idFromUrl(""), "空串输入")
        assertNull(storage.idFromUrl("/some/other/path.jpg"), "非文件 URL 应返回 null 而非抛异常")
    }

    @Test
    fun `deleteById removes both disk file and record`() {
        val stored = storage.storeDetailed("image/jpeg", byteArrayOf(1, 2, 3), "a.jpg", deviceId = "D1")
        val onDisk = File(tempDir, uploadedFiles.table[stored.id]!!.storedPath)
        assertTrue(onDisk.exists(), "落盘文件应存在")

        assertTrue(storage.deleteById(stored.id), "删除应返回 true")

        assertFalse(onDisk.exists(), "磁盘文件应已删除")
        assertFalse(uploadedFiles.table.containsKey(stored.id), "库记录应已删除")
        assertFalse(storage.deleteById(stored.id), "重复删除返回 false")
    }

    // ==================== 保留期清理 ====================

    @Test
    fun `cleanup deletes stale location tracks and keeps recent`() {
        val now = System.currentTimeMillis()
        locations.table["old"] = LocationTrack(id = "old", deviceId = "D1", ts = now - 100 * day, receivedAt = now - 100 * day)
        locations.table["nullTsOld"] = LocationTrack(id = "nullTsOld", deviceId = "D1", ts = null, receivedAt = now - 100 * day)
        locations.table["recent"] = LocationTrack(id = "recent", deviceId = "D1", ts = now - 10 * day, receivedAt = now - 10 * day)

        cleanup.cleanup()

        assertEquals(setOf("recent"), locations.table.keys, "超期轨迹（含 ts 缺省回落 receivedAt）应被清除")
    }

    @Test
    fun `cleanup deletes stale screenshot records together with their files`() {
        val now = System.currentTimeMillis()
        val oldFile = storage.storeDetailed("image/jpeg", byteArrayOf(9), "old.jpg", deviceId = "D1")
        val recentFile = storage.storeDetailed("image/jpeg", byteArrayOf(9), "new.jpg", deviceId = "D1")

        screenshots.table["old"] = Screenshot(
            id = "old", deviceId = "D1", fileUrl = oldFile.url,
            receivedAt = now - 100 * day
        )
        screenshots.table["recent"] = Screenshot(
            id = "recent", deviceId = "D1", fileUrl = recentFile.url,
            receivedAt = now - 1 * day
        )

        cleanup.cleanup()

        assertEquals(setOf("recent"), screenshots.table.keys, "超期截图记录应删除")
        assertFalse(uploadedFiles.table.containsKey(oldFile.id), "超期截图的文件应一并删除")
        assertTrue(uploadedFiles.table.containsKey(recentFile.id), "未超期截图的文件必须保留")
    }

    @Test
    fun `cleanup deletes stale media tasks together with their files`() {
        val now = System.currentTimeMillis()
        val oldRec = storage.storeDetailed("video/mp4", byteArrayOf(7), "old.mp4", deviceId = "D1")
        val recentRec = storage.storeDetailed("video/mp4", byteArrayOf(7), "new.mp4", deviceId = "D1")

        mediaTasks.table["old"] = MediaTask(id = "old", deviceId = "D1", kind = "SCREEN_RECORD", url = oldRec.url, startedAt = now - 100 * day)
        mediaTasks.table["recent"] = MediaTask(id = "recent", deviceId = "D1", kind = "SCREEN_RECORD", url = recentRec.url, startedAt = now - 2 * day)

        cleanup.cleanup()

        assertEquals(setOf("recent"), mediaTasks.table.keys, "超期媒体任务记录应删除")
        assertFalse(uploadedFiles.table.containsKey(oldRec.id), "超期录屏文件应一并删除")
        assertTrue(uploadedFiles.table.containsKey(recentRec.id), "未超期录屏文件必须保留")
    }

    @Test
    fun `cleanup deletes orphan device files but keeps parent-owned materials`() {
        val now = System.currentTimeMillis()
        val staleDeviceFile = storage.storeDetailed("image/jpeg", byteArrayOf(1), "shot.jpg", deviceId = "D1")
        val parentMaterial = storage.storeDetailed("image/jpeg", byteArrayOf(1), "poster.jpg", ownerUserId = "U1")
        val legacyNoOwner = storage.storeDetailed("image/jpeg", byteArrayOf(1), "legacy.jpg")

        // UploadedFile 是普通类（非 data class，无 copy()），直接改字段模拟超期创建时间
        uploadedFiles.table[staleDeviceFile.id]!!.createdAt = now - 100 * day
        uploadedFiles.table[parentMaterial.id]!!.createdAt = now - 100 * day
        uploadedFiles.table[legacyNoOwner.id]!!.createdAt = now - 100 * day

        cleanup.cleanup()

        assertFalse(uploadedFiles.table.containsKey(staleDeviceFile.id), "超期设备上传文件（孤儿兜底）应删除")
        assertTrue(uploadedFiles.table.containsKey(parentMaterial.id), "家长发布素材（owner 有值、device 为空）必须保留")
        assertTrue(uploadedFiles.table.containsKey(legacyNoOwner.id), "无归属历史文件（device 也为空）不在清理范围，保留")
    }

    @Test
    fun `checkDiskUsage runs against real filesystem without throwing`() {
        // 真实磁盘水位：CI/开发机通常远低于阈值，这里只钉"不抛异常、可安全调度"
        cleanup.checkDiskUsage()
    }

    // ==================== 内存假仓储 ====================

    private class FakeLocationTrackRepo :
        FakeJpaRepository<LocationTrack, String>(), LocationTrackRepository {
        override val table = LinkedHashMap<String, LocationTrack>()
        override fun idOf(entity: LocationTrack): String = entity.id

        override fun findByDeviceIdOrderByTsDesc(deviceId: String, pageable: org.springframework.data.domain.Pageable): List<LocationTrack> =
            table.values.filter { it.deviceId == deviceId }.sortedByDescending { it.ts ?: 0L }

        override fun findByDeviceIdAndTsGreaterThanEqualOrderByTsDesc(deviceId: String, since: Long, pageable: org.springframework.data.domain.Pageable): List<LocationTrack> =
            table.values.filter { it.deviceId == deviceId && (it.ts ?: 0L) >= since }
                .sortedByDescending { it.ts ?: 0L }

        override fun deleteByDeviceId(deviceId: String): Int {
            val victims = table.values.filter { it.deviceId == deviceId }
            victims.forEach { table.remove(it.id) }
            return victims.size
        }

        override fun updateAddressById(id: String, address: String) {
            table[id]?.address = address
        }

        override fun deleteByTsBefore(ts: Long): Int {
            val victims = table.values.filter { (it.ts ?: it.receivedAt) < ts }
            victims.forEach { table.remove(it.id) }
            return victims.size
        }
    }

    private class FakeScreenshotRepo :
        FakeJpaRepository<Screenshot, String>(), ScreenshotRepository {
        override val table = LinkedHashMap<String, Screenshot>()
        override fun idOf(entity: Screenshot): String = entity.id

        override fun findByDeviceIdOrderByCapturedAtDesc(deviceId: String, pageable: org.springframework.data.domain.Pageable): List<Screenshot> =
            table.values.filter { it.deviceId == deviceId }.sortedByDescending { it.capturedAt ?: 0L }

        override fun findFirstByDeviceIdAndShotId(deviceId: String, shotId: String): Screenshot? =
            table.values.firstOrNull { it.deviceId == deviceId && it.shotId == shotId }

        override fun deleteByDeviceId(deviceId: String): Int {
            val victims = table.values.filter { it.deviceId == deviceId }
            victims.forEach { table.remove(it.id) }
            return victims.size
        }

        override fun findReceivedAtBefore(ts: Long): List<Screenshot> =
            table.values.filter { it.receivedAt < ts }
    }

    private class FakeMediaTaskRepo :
        FakeJpaRepository<MediaTask, String>(), MediaTaskRepository {
        override val table = LinkedHashMap<String, MediaTask>()
        override fun idOf(entity: MediaTask): String = entity.id

        override fun findByDeviceIdAndKindOrderByStartedAtDesc(deviceId: String, kind: String, pageable: org.springframework.data.domain.Pageable): List<MediaTask> =
            table.values.filter { it.deviceId == deviceId && it.kind == kind }
                .sortedByDescending { it.startedAt }

        override fun deleteByDeviceId(deviceId: String): Int {
            val victims = table.values.filter { it.deviceId == deviceId }
            victims.forEach { table.remove(it.id) }
            return victims.size
        }

        override fun findStartedAtBefore(ts: Long): List<MediaTask> =
            table.values.filter { it.startedAt < ts }
    }

    private class FakeUploadedFileRepo :
        FakeJpaRepository<UploadedFile, String>(), UploadedFileRepository {
        override val table = LinkedHashMap<String, UploadedFile>()
        override fun idOf(entity: UploadedFile): String = entity.id

        override fun deleteByDeviceId(deviceId: String): Int {
            val victims = table.values.filter { it.deviceId == deviceId }
            victims.forEach { table.remove(it.id) }
            return victims.size
        }

        override fun findDeviceFilesCreatedBefore(ts: Long): List<UploadedFile> =
            table.values.filter { it.deviceId != null && it.createdAt < ts }
    }
}
