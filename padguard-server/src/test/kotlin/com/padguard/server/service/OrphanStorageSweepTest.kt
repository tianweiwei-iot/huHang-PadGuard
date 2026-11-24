package com.padguard.server.service

import com.padguard.server.domain.UploadedFile
import com.padguard.server.repository.UploadedFileRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * 磁盘孤儿清扫（[FileStorageService.deleteOrphanFiles]）。
 *
 * 这条链路的失效后果是**不可逆的数据删除**，所以测试重点不是"能删掉垃圾"，
 * 而是"**不能删**掉任何还有记录的文件"。每个用例都对应一条真实的误删路径：
 * 历史绝对路径、宽限期内的新文件、读库失败、子目录、单次上限。
 */
class OrphanStorageSweepTest {

    private class FakeUploadedFileRepo : FakeJpaRepository<UploadedFile, String>(), UploadedFileRepository {
        override val table = LinkedHashMap<String, UploadedFile>()
        override fun idOf(entity: UploadedFile): String = entity.id

        /** true 时模拟数据库不可用（连接池耗尽/表损坏），清扫必须一个都不删。 */
        var failReads = false

        override fun findByDeviceId(deviceId: String): List<UploadedFile> =
            table.values.filter { it.deviceId == deviceId }

        override fun deleteByDeviceId(deviceId: String): Int {
            val hit = table.values.filter { it.deviceId == deviceId }
            hit.forEach { table.remove(it.id) }
            return hit.size
        }

        override fun findDeviceFilesCreatedBefore(ts: Long): List<UploadedFile> =
            table.values.filter { it.deviceId != null && it.createdAt < ts }

        override fun findAllStoredPaths(): List<String> {
            if (failReads) throw IllegalStateException("simulated db failure")
            return table.values.map { it.storedPath }
        }
    }

    private fun service(dir: File, repo: FakeUploadedFileRepo) =
        FileStorageService(dir.absolutePath, "", repo, null)

    private fun oldFile(dir: File, name: String, size: Int = 8): File =
        File(dir, name).apply {
            writeBytes(ByteArray(size) { 0x41 })
            // 文件系统 mtime 精度可能只到秒，这里多退 1 小时避免边界抖动
            setLastModified(System.currentTimeMillis() - 8 * 86_400_000L - 3_600_000L)
        }

    private fun freshFile(dir: File, name: String, size: Int = 8): File =
        File(dir, name).apply { writeBytes(ByteArray(size) { 0x42 }) }

    private fun row(id: String, path: String) =
        UploadedFile(id = id, storedPath = path, size = 8, createdAt = System.currentTimeMillis())

    @Test
    fun `删除无记录且超宽限期的文件`(@TempDir dir: File) {
        val repo = FakeUploadedFileRepo()
        val orphan = oldFile(dir, "shell.php.png")
        val r = service(dir, repo).deleteOrphanFiles(graceDays = 7)

        assertFalse(orphan.exists(), "无记录 + 超宽限期的文件必须被清掉")
        assertEquals(1, r.deleted)
        assertTrue(r.bytesFreed > 0)
        assertFalse(r.dbReadFailed)
        assertTrue(r.samples.contains("shell.php.png"), "样例要能说清删的是什么")
    }

    @Test
    fun `有记录的文件即使超期也必须保留`(@TempDir dir: File) {
        val repo = FakeUploadedFileRepo()
        val kept = oldFile(dir, "shot_01.png")
        repo.table["f1"] = row("f1", "shot_01.png")

        val r = service(dir, repo).deleteOrphanFiles(graceDays = 7)

        assertTrue(kept.exists(), "库里有记录的文件不得被当作孤儿")
        assertEquals(0, r.deleted)
    }

    @Test
    fun `历史绝对路径记录按文件名匹配仍不误删`(@TempDir dir: File) {
        // 早期版本把绝对路径写进 storedPath，而 resolve()/deleteById() 都只取文件名。
        // 孤儿清扫若拿整条路径比对，会把这类历史文件全判成孤儿 —— 升级即批量误删。
        val repo = FakeUploadedFileRepo()
        val legacy = oldFile(dir, "legacy_avatar.jpg")
        repo.table["f2"] = row("f2", "/opt/padguard/uploads/legacy_avatar.jpg")

        service(dir, repo).deleteOrphanFiles(graceDays = 7)

        assertTrue(legacy.exists(), "绝对路径记录必须按文件名归一化后匹配")
    }

    @Test
    fun `宽限期内的新文件不得删除`(@TempDir dir: File) {
        // 写盘成功但 save 失败/被杀，是孤儿文件最主要的真实来源。
        // 这类文件刚写完就是"无记录"状态，靠宽限期保护。
        val repo = FakeUploadedFileRepo()
        val fresh = freshFile(dir, "just_written.bin")

        val r = service(dir, repo).deleteOrphanFiles(graceDays = 7)

        assertTrue(fresh.exists())
        assertEquals(0, r.deleted)
    }

    @Test
    fun `宽限期至少一天 - 传0也保护刚写入的文件`(@TempDir dir: File) {
        val repo = FakeUploadedFileRepo()
        val fresh = freshFile(dir, "racing.bin")

        service(dir, repo).deleteOrphanFiles(graceDays = 0)

        assertTrue(fresh.exists(), "graceDays=0 不得绕过宽限期，否则写盘/落库竞态会直接删用户数据")
    }

    @Test
    fun `dryRun 只统计不删除`(@TempDir dir: File) {
        val repo = FakeUploadedFileRepo()
        val orphan = oldFile(dir, "evil.png")

        val r = service(dir, repo).deleteOrphanFiles(graceDays = 7, dryRun = true)

        assertTrue(orphan.exists(), "预演模式不得改动磁盘")
        assertEquals(1, r.deleted, "预演也要回报将删数量，否则运维没有判断依据")
    }

    @Test
    fun `读库失败时一个都不删`(@TempDir dir: File) {
        val repo = FakeUploadedFileRepo().apply { failReads = true }
        val a = oldFile(dir, "a.png")
        val b = oldFile(dir, "b.png")

        val r = service(dir, repo).deleteOrphanFiles(graceDays = 7)

        assertTrue(a.exists() && b.exists(), "读库失败被当成空集合 = 删空整个 uploads")
        assertEquals(0, r.deleted)
        assertTrue(r.dbReadFailed)
    }

    @Test
    fun `不递归 - 子目录原样保留`(@TempDir dir: File) {
        val repo = FakeUploadedFileRepo()
        val sub = File(dir, "nested").apply { mkdirs() }
        val inner = oldFile(sub, "inner.png")

        service(dir, repo).deleteOrphanFiles(graceDays = 7)

        assertTrue(inner.exists(), "清扫只处理存储根目录下的普通文件")
        assertTrue(sub.isDirectory)
    }

    @Test
    fun `保护名单里的文件永不删除`(@TempDir dir: File) {
        // 两个初始口令文件就写在 uploads/ 根目录且不进数据库，是登录锁死时唯一的恢复入口。
        // 照"无记录即孤儿"的规则它们会被清掉 —— 必须靠保护名单拦住。
        val repo = FakeUploadedFileRepo()
        val adminPwd = oldFile(dir, "管理员初始密码-请登录后修改.txt")
        val parentPwd = oldFile(dir, "初始密码-请登录后修改.txt")
        val orphan = oldFile(dir, "shell.php.png")

        val r = service(dir, repo).deleteOrphanFiles(
            graceDays = 7,
            protectedNames = setOf("管理员初始密码-请登录后修改.txt", "初始密码-请登录后修改.txt")
        )

        assertTrue(adminPwd.exists() && parentPwd.exists(), "口令文件不得被当作孤儿清理")
        assertFalse(orphan.exists(), "保护名单之外的真孤儿仍要清掉")
        assertEquals(1, r.deleted)
    }

    @Test
    fun `单次删除上限生效并标记截断`(@TempDir dir: File) {
        val repo = FakeUploadedFileRepo()
        repeat(5) { oldFile(dir, "orphan_$it.png") }

        val r = service(dir, repo).deleteOrphanFiles(graceDays = 7, maxDelete = 2)

        assertEquals(2, r.deleted)
        assertTrue(r.truncated, "被上限截断必须显式回报，否则会误以为已经清完")
        assertEquals(3, dir.listFiles()!!.count { it.isFile })
    }
}
