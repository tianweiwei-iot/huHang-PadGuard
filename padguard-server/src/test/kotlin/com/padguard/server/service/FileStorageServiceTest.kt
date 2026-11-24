package com.padguard.server.service

import com.padguard.server.domain.UploadedFile
import com.padguard.server.repository.UploadedFileRepository
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Optional

/**
 * [FileStorageService] 自身的安全边界。
 *
 * 与既有测试的分工（刻意不重复）：
 * - `OrphanStorageSweepTest` 覆盖 deleteOrphanFiles 的误删防护；
 * - `RetentionCleanupTest` 覆盖 idFromUrl 的 URL 解析；
 * - `MediaUrlSignatureTest` 覆盖 urlSigner 签名；
 * - 本类覆盖：文件名净化、只存文件名、流式写入的 size 口径与失败清理、
 *   resolve 的目录穿越与历史绝对路径兼容、deleteById 的先删记录顺序、
 *   referencedFileNames 读失败绝不退化成空集。
 */
class FileStorageServiceTest {

    private lateinit var repo: UploadedFileRepository

    @BeforeEach
    fun setUp() {
        repo = mock(UploadedFileRepository::class.java)
    }

    private fun svc(dir: File, baseUrl: String = "") = FileStorageService(dir.absolutePath, baseUrl, repo)

    /** 抓取 save 落库的实体（泛型 save 用 thenAnswer 比 ArgumentCaptor 稳）。 */
    private fun captureSaved(): () -> UploadedFile? {
        var saved: UploadedFile? = null
        `when`(repo.save(any(UploadedFile::class.java))).thenAnswer { a ->
            saved = a.arguments[0] as UploadedFile
            a.arguments[0]
        }
        return { saved }
    }

    // ----------------------------- 落盘 / 文件名 -----------------------------

    @Test
    fun `storeDetailed 只把文件名写进 storedPath 绝不存绝对路径`(@TempDir dir: File) {
        val saved = captureSaved()
        val r = svc(dir).storeDetailed("image/png", "abc".toByteArray(), "shot.png", "u1", "d1")

        val meta = saved()!!
        assertFalse(meta.storedPath.contains("/"), "路径分隔符不得入库，否则目录搬家后全部失效")
        assertFalse(meta.storedPath.contains("\\"), "Windows 分隔符同样不得入库")
        assertFalse(meta.storedPath.contains(dir.absolutePath), "绝对路径会让发布包搬目录后历史文件全丢")
        assertTrue(meta.storedPath.endsWith("shot.png"))
        assertEquals(r.id, meta.id)
        assertEquals("image/png", meta.contentType)
    }

    @Test
    fun `storeDetailed 落库归属 ownerUserId 与 deviceId`(@TempDir dir: File) {
        val saved = captureSaved()
        svc(dir).storeDetailed("video/mp4", "v".toByteArray(), "rec.mp4", ownerUserId = "u9", deviceId = "d9")

        val meta = saved()!!
        assertEquals("u9", meta.ownerUserId)
        assertEquals("d9", meta.deviceId)
    }

    @Test
    fun `storeDetailed 净化路径穿越与非法字符`(@TempDir dir: File) {
        val saved = captureSaved()
        val r = svc(dir).storeDetailed("image/png", "x".toByteArray(), "../../evil name?.png")

        assertEquals("evil_name_.png", r.name, "路径段、空格、问号都必须被净化")
        assertFalse(r.name.contains("/") || r.name.contains("\\"))
        assertTrue(saved()!!.storedPath.endsWith("evil_name_.png"))
        // 文件必须真的落在存储根目录内，不能跑到父目录
        assertEquals(1, dir.listFiles()!!.count { it.isFile })
    }

    @Test
    fun `storeDetailed 反斜杠路径同样只取末段`(@TempDir dir: File) {
        val saved = captureSaved()
        val r = svc(dir).storeDetailed("application/octet-stream", "x".toByteArray(), "C:\\tmp\\payload.bin")

        assertEquals("payload.bin", r.name)
        assertTrue(saved()!!.storedPath.endsWith("payload.bin"))
    }

    @Test
    fun `storeDetailed 空文件名回落 file 且全非法字符被替换`(@TempDir dir: File) {
        // 注意 store() 返回的是访问 URL，文件名要看 storeDetailed().name
        val r1 = svc(dir).storeDetailed("image/png", "x".toByteArray(), null)
        val r2 = svc(dir).storeDetailed("image/png", "x".toByteArray(), "!!!")
        assertEquals("file", r1.name, "缺省文件名必须回落成一个安全名")
        assertFalse(r2.name.contains("!"), "非法字符不得原样进入文件名")
    }

    // ----------------------------- 流式写入 -----------------------------

    @Test
    fun `storeStream 以实际写入字节数为准且内容一致`(@TempDir dir: File) {
        val saved = captureSaved()
        val payload = ByteArray(7) { it.toByte() }
        val r = svc(dir).storeStream("application/octet-stream", payload.inputStream(), "bin.dat")

        val meta = saved()!!
        assertEquals(7L, meta.size, "size 必须采信实际写入量，不能信客户端声明")
        val onDisk = File(dir, meta.storedPath)
        assertTrue(onDisk.isFile)
        assertArrayEquals(payload, onDisk.readBytes())
        assertEquals("bin.dat", r.name)
        assertEquals(meta.id, r.id)
    }

    @Test
    fun `storeStream 大缓冲区之外的流也按真实长度落库`(@TempDir dir: File) {
        val saved = captureSaved()
        val payload = ByteArray(64 * 1024 + 13) { (it % 251).toByte() }
        svc(dir).storeStream("application/octet-stream", payload.inputStream(), "big.dat", ownerUserId = "u1")

        val meta = saved()!!
        assertEquals(payload.size.toLong(), meta.size)
        assertArrayEquals(payload, File(dir, meta.storedPath).readBytes())
        assertEquals("u1", meta.ownerUserId)
    }

    @Test
    fun `storeStream 写入中途失败删半截文件且不落库`(@TempDir dir: File) {
        val broken = object : InputStream() {
            var reads = 0
            override fun read(): Int = throw IOException("client disconnected")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                reads++
                if (reads > 2) throw IOException("client disconnected")
                b[off] = 0x41
                return 1
            }
        }

        assertThrows(IOException::class.java) {
            svc(dir).storeStream("application/octet-stream", broken, "half.dat")
        }
        assertEquals(0, dir.listFiles()!!.count { it.isFile }, "半截文件必须删掉，否则成为永远扫不到的占盘孤儿")
        verify(repo, never()).save(any(UploadedFile::class.java))
    }

    // ----------------------------- URL -----------------------------

    @Test
    fun `url 未配 baseUrl 返回同源相对路径`(@TempDir dir: File) {
        assertEquals("/v1/files/abc123", svc(dir).url("abc123"))
    }

    @Test
    fun `url 配了 baseUrl 生成绝对地址并去掉尾部斜杠`(@TempDir dir: File) {
        assertEquals(
            "https://files.example.com/v1/files/abc123",
            svc(dir, "https://files.example.com///").url("abc123")
        )
        assertEquals(
            "https://files.example.com/v1/files/x",
            svc(dir, "https://files.example.com").url("x")
        )
    }

    // ----------------------------- resolve -----------------------------

    @Test
    fun `resolve 历史绝对路径按文件名归一化后仍可下载`(@TempDir dir: File) {
        val onDisk = File(dir, "legacy_avatar.jpg").apply { writeBytes("img".toByteArray()) }
        val meta = UploadedFile(id = "f-old", storedPath = "/opt/padguard/uploads/legacy_avatar.jpg")
        `when`(repo.findById("f-old")).thenReturn(Optional.of(meta))

        val got = svc(dir).resolve("f-old")
        assertNotNull(got, "升级后历史截图/录屏不能集体变成文件不存在")
        assertEquals(onDisk.name, got!!.second.name)
    }

    @Test
    fun `resolve 带穿越片段的 storedPath 不得读到存储目录外的文件`(@TempDir dir: File) {
        // 即便有人往 storedPath 里塞了 ../../，也只允许按「文件名」在根内解析
        val outside = File(dir.parentFile, "outside_secret_${System.nanoTime()}.txt")
        outside.writeBytes("SECRET".toByteArray())
        try {
            val meta = UploadedFile(id = "f-escape", storedPath = "../../${outside.name}")
            `when`(repo.findById("f-escape")).thenReturn(Optional.of(meta))

            val got = svc(dir).resolve("f-escape")
            assertNull(got, "根目录下没有同名文件时应返回 null，绝不回落到目录外")
        } finally {
            outside.delete()
        }
    }

    @Test
    fun `resolve 记录存在但磁盘文件缺失返回 null`(@TempDir dir: File) {
        `when`(repo.findById("f-miss")).thenReturn(
            Optional.of(UploadedFile(id = "f-miss", storedPath = "gone.png"))
        )
        assertNull(svc(dir).resolve("f-miss"))
    }

    @Test
    fun `resolve 无记录返回 null`(@TempDir dir: File) {
        `when`(repo.findById("nope")).thenReturn(Optional.empty())
        assertNull(svc(dir).resolve("nope"))
    }

    // ----------------------------- deleteById -----------------------------

    @Test
    fun `deleteById 先删记录再删磁盘文件`(@TempDir dir: File) {
        val onDisk = File(dir, "shot.png").apply { writeBytes("x".toByteArray()) }
        val meta = UploadedFile(id = "f1", storedPath = "shot.png")
        `when`(repo.findById("f1")).thenReturn(Optional.of(meta))
        val fileStillThereWhenRecordDeleted = booleanArrayOf(false)
        doAnswer {
            fileStillThereWhenRecordDeleted[0] = onDisk.exists()
            null
        }.`when`(repo).delete(meta)

        assertTrue(svc(dir).deleteById("f1"))
        verify(repo).delete(meta)
        assertTrue(fileStillThereWhenRecordDeleted[0], "记录先失效，下载立刻 404；文件残留只是可清扫的孤儿")
        assertFalse(onDisk.exists())
    }

    @Test
    fun `deleteById 记录不存在返回 false 且不碰磁盘`(@TempDir dir: File) {
        val onDisk = File(dir, "keep.png").apply { writeBytes("x".toByteArray()) }
        `when`(repo.findById("absent")).thenReturn(Optional.empty())

        assertFalse(svc(dir).deleteById("absent"))
        assertTrue(onDisk.exists())
        verify(repo, never()).delete(any(UploadedFile::class.java))
    }

    // ----------------------------- referencedFileNames -----------------------------

    @Test
    fun `referencedFileNames 读库失败必须抛出绝不返回空集`(@TempDir dir: File) {
        `when`(repo.findAllStoredPaths()).thenThrow(IllegalStateException("db unavailable"))

        assertThrows(IllegalStateException::class.java) { svc(dir).referencedFileNames() }
    }

    @Test
    fun `referencedFileNames 把历史绝对路径归一化为文件名`(@TempDir dir: File) {
        `when`(repo.findAllStoredPaths()).thenReturn(listOf("/opt/old/a.png", "b.png", "sub/c.png"))

        val names = svc(dir).referencedFileNames()
        assertEquals(setOf("a.png", "b.png", "c.png"), names)
    }

    // ----------------------------- 上传类型单一来源 -----------------------------

    @Test
    fun `所有入口共用的黑名单必须拦住 SVG 与脚本类型`() {
        assertNotNull(FileStorageService.unsafeUploadReason("payload.svg", "image/png"))
        assertNotNull(FileStorageService.unsafeUploadReason("payload.png", "image/svg+xml"))
        assertNotNull(FileStorageService.unsafeUploadReason("page.html", "application/octet-stream"))
        assertNotNull(FileStorageService.unsafeUploadReason("app.js", "application/octet-stream"))
        assertNull(FileStorageService.unsafeUploadReason("shot.png", "image/png"))
        assertNull(FileStorageService.unsafeUploadReason("rec.mp4", "video/mp4"))
    }

    @Test
    fun `resolveMime 统一处理通配声明与分号参数`() {
        assertEquals("image/svg+xml", FileStorageService.resolveMime("x.png", "IMAGE/SVG+XML; charset=utf-8"))
        assertEquals("image/png", FileStorageService.resolveMime("x.png", "*/*"))
        assertEquals("video/mp4", FileStorageService.resolveMime("x.mp4", null))
    }
}
