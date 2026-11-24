package com.padguard.server.service

import com.padguard.server.domain.InstalledApp
import com.padguard.server.domain.LocationTrack
import com.padguard.server.domain.MediaTask
import com.padguard.server.domain.Screenshot
import com.padguard.server.domain.UploadedFile
import com.padguard.server.repository.InstalledAppRepository
import com.padguard.server.repository.LocationTrackRepository
import com.padguard.server.repository.MediaTaskRepository
import com.padguard.server.repository.ScreenshotRepository
import com.padguard.server.repository.UploadedFileRepository
import com.padguard.server.support.TaskExecutionLock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager

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
    private lateinit var installedApps: FakeInstalledAppRepo
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
        installedApps = FakeInstalledAppRepo()
        cleanup = RetentionCleanup(locations, screenshots, mediaTasks, uploadedFiles, storage, installedApps)
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

    /**
     * 回归防线：exclusively 曾写成 `taskLock?.runExclusively(...) ?: body()`，
     * "锁存在但抢锁失败"时 Elvis 兜底又把 body 执行一遍 → 多副本同时清理。
     * 这里占住 retention-cleanup 任务锁，验证输家副本跳过本轮。
     */
    @Test
    fun `cleanup skipped when another replica holds the lock`() {
        val url = "jdbc:h2:mem:retentionlock;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        val ds = SimpleDriverDataSource(DriverManager.getDriver(url), url, "sa", "")
        val jdbc = JdbcTemplate(ds)
        jdbc.execute(
            "CREATE TABLE IF NOT EXISTS task_locks (task_name VARCHAR(64) PRIMARY KEY, " +
                "holder VARCHAR(128) NOT NULL, acquired_at BIGINT NOT NULL, lease_until BIGINT NOT NULL)"
        )
        jdbc.update(
            "INSERT INTO task_locks (task_name, holder, acquired_at, lease_until) VALUES (?, ?, ?, ?)",
            "retention-cleanup", "other-replica", System.currentTimeMillis(), System.currentTimeMillis() + 60_000L
        )
        val locked = RetentionCleanup(
            locations, screenshots, mediaTasks, uploadedFiles, storage, installedApps, TaskExecutionLock(jdbc)
        )
        val now = System.currentTimeMillis()
        locations.table["old"] = LocationTrack(id = "old", deviceId = "D1", ts = now - 100 * day, receivedAt = now - 100 * day)

        locked.cleanup()

        assertTrue(locations.table.containsKey("old"), "抢锁失败的副本不得执行清理")
        jdbc.execute("DROP TABLE IF EXISTS task_locks")
    }

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
    fun `位置轨迹清理只认服务端落库时刻 不认设备上报的 ts`() {
        val now = System.currentTimeMillis()
        // 设备把时间调到未来：ts 看似"刚采集"，但落库已久，必须照常清掉。
        locations.table["futureTsOldRow"] = LocationTrack(
            id = "futureTsOldRow", deviceId = "D1", ts = now + 365 * day, receivedAt = now - 100 * day
        )
        // 设备把时间调到过去：ts 看似很古老，实际刚落库，绝不能误删今天的真实行踪。
        locations.table["pastTsFreshRow"] = LocationTrack(
            id = "pastTsFreshRow", deviceId = "D1", ts = now - 365 * day, receivedAt = now
        )

        cleanup.cleanup()

        assertEquals(
            setOf("pastTsFreshRow"), locations.table.keys,
            "客户端 ts 被篡改时不得影响保留期；只看服务端 receivedAt"
        )
    }

    @Test
    fun `轨迹保留期 SQL 必须按 receivedAt 判定`() {
        val query = LocationTrackRepository::class.java
            .getMethod("deleteByTsBefore", Long::class.javaPrimitiveType)
            .getAnnotation(org.springframework.data.jpa.repository.Query::class.java)
            ?.value.orEmpty()
        assertTrue(query.contains("t.receivedAt < :ts"), "清理判据必须是服务端落库时刻：$query")
        assertFalse(query.contains("coalesce(t.ts"), "不得回落到客户端上报的 ts：$query")
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
    fun `cleanup keeps icon files even when they are older than retention`() {
        val now = System.currentTimeMillis()
        // 图标也是"设备上传文件"（带 device_id），但它是**在用**的：装了一年的应用，
        // 图标文件必然超过 90 天保留期。删掉它 = installed_apps.icon_url 指向 404，
        // 而且 hash 没变不会重新落盘，家长端从此一片空白图标。
        val icon = storage.storeDetailed("image/png", byteArrayOf(9), "com.a.png", deviceId = "D1")
        val staleShot = storage.storeDetailed("image/jpeg", byteArrayOf(1), "shot.jpg", deviceId = "D1")
        uploadedFiles.table[icon.id]!!.createdAt = now - 400 * day
        uploadedFiles.table[staleShot.id]!!.createdAt = now - 400 * day
        installedApps.save(
            InstalledApp(id = "IA1", deviceId = "D1", packageName = "com.a", iconUrl = "/v1/files/${icon.id}")
        )

        cleanup.cleanup()

        assertTrue(uploadedFiles.table.containsKey(icon.id), "在用图标文件必须保留（与截图/录屏不同，它没有生命周期终点）")
        assertFalse(uploadedFiles.table.containsKey(staleShot.id), "超期截图仍要照常清理")
    }

    @Test
    fun `cleanup skips device files entirely when icon references cannot be read`() {
        val now = System.currentTimeMillis()
        val staleShot = storage.storeDetailed("image/jpeg", byteArrayOf(1), "shot.jpg", deviceId = "D1")
        uploadedFiles.table[staleShot.id]!!.createdAt = now - 400 * day
        // 模拟数据库抖动：读不到图标引用时**一个都不能删**，
        // 否则一次抖动就会把所有在用图标当成超期垃圾清光（与离线队列 readOrNull 同一约定）。
        installedApps.failReads = true

        cleanup.cleanup()

        assertTrue(uploadedFiles.table.containsKey(staleShot.id), "图标引用读失败 → 整轮跳过，不删任何设备文件")
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
            val victims = table.values.filter { it.receivedAt < ts }
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

        override fun markExpiredIfPending(deviceId: String, shotId: String, pending: String, expired: String): Int {
            val row = table.values.firstOrNull {
                it.deviceId == deviceId && it.shotId == shotId && it.status == pending
            } ?: return 0
            row.status = expired
            return 1
        }
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

        override fun findAllStoredPaths(): List<String> = table.values.map { it.storedPath }

        override fun findByDeviceId(deviceId: String): List<UploadedFile> =
            table.values.filter { it.deviceId == deviceId }
    }

    /**
     * 已装应用台账假仓储：本轮的用例只需要"读过图标引用"。
     * [failReads] 模拟数据库不可用 —— 此时保留期清理必须整轮跳过（宁可不删）。
     */
    private class FakeInstalledAppRepo :
        FakeJpaRepository<InstalledApp, String>(), InstalledAppRepository {
        override val table = LinkedHashMap<String, InstalledApp>()
        override fun idOf(entity: InstalledApp): String = entity.id

        var failReads = false

        override fun findByDeviceId(deviceId: String): List<InstalledApp> =
            table.values.filter { it.deviceId == deviceId }

        override fun findByDeviceIdAndPackageName(deviceId: String, packageName: String): InstalledApp? =
            table.values.firstOrNull { it.deviceId == deviceId && it.packageName == packageName }

        override fun findByDeviceIdAndInstalledTrue(deviceId: String): List<InstalledApp> =
            table.values.filter { it.deviceId == deviceId && it.installed }

        override fun deleteByDeviceId(deviceId: String): Int {
            val victims = table.values.filter { it.deviceId == deviceId }
            victims.forEach { table.remove(it.id) }
            return victims.size
        }

        override fun findAllIconUrls(): List<String> {
            if (failReads) throw IllegalStateException("simulated db failure")
            return table.values.mapNotNull { it.iconUrl }
        }
    }
}
