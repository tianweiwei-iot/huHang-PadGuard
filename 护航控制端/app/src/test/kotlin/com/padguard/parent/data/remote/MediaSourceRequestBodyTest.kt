package com.padguard.parent.data.remote

import com.padguard.parent.domain.model.MediaSource
import com.padguard.parent.domain.model.MediaTooLargeException
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.Buffer
import okio.Sink
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * 上传请求体的流式契约。
 *
 * 背景：家长端选中视频后用 `readBytes()` 整份读进堆，再交给 multipart 写第二遍，
 * 峰值是「文件大小 × 2」—— 一部 4K 视频就能把进程 OOM 掉，且没有任何业务报错。
 * 这里锁死三件事，避免以后被人"顺手改回去"：
 * 1. 写出的字节与源**完全一致**，且长度可预知时如实上报 Content-Length；
 * 2. 同一个请求体可以**重复写出**（OkHttp 401 重试 / 重定向会重放 body）；
 * 3. 搬运 64MB 数据时堆占用不随体积增长（分段流式，而不是整块读）。
 */
class MediaSourceRequestBodyTest {

    /** 可重复打开的全新流：模拟 ContentResolver 每次 openInputStream 都从头开始 */
    private fun patternSource(
        total: Long,
        contentType: String = "video/mp4",
        onOpen: () -> Unit = {}
    ): MediaSource = MediaSource(
        fileName = "clip.mp4",
        contentType = contentType,
        sizeBytes = total,
        opener = {
            onOpen()
            PatternStream(total)
        }
    )

    @Test
    fun `长度已知时上报 Content-Length 并带上 MIME`() {
        val body = MediaSourceRequestBody(patternSource(1024))

        assertEquals(1024L, body.contentLength())
        assertEquals("video/mp4", body.contentType()?.toString())
    }

    @Test
    fun `长度未知时返回 -1 由 OkHttp 走 chunked`() {
        val body = MediaSourceRequestBody(
            MediaSource("voice.amr", "audio/amr", MediaSource.UNKNOWN_SIZE, { PatternStream(10) })
        )

        assertEquals(-1L, body.contentLength())
    }

    @Test
    fun `写出的字节与源完全一致`() {
        val size = 1L shl 20 // 1MB
        val body = MediaSourceRequestBody(patternSource(size))

        val buffer = Buffer()
        body.writeTo(buffer)

        assertEquals(size, buffer.size)
        assertEquals(patternSum(size), sumOf(buffer.readByteArray()))
    }

    @Test
    fun `同一请求体可重复写出且内容一致（OkHttp 重试重放 body）`() {
        val size = 64L * 1024
        val opens = AtomicInteger()
        val body = MediaSourceRequestBody(patternSource(size) { opens.incrementAndGet() })

        val first = Buffer().also { body.writeTo(it) }.readByteArray()
        val second = Buffer().also { body.writeTo(it) }.readByteArray()

        assertEquals("每次写出都必须重新打开源流", 2, opens.get())
        assertEquals(size, first.size.toLong())
        assertEquals(sha256(first), sha256(second))
    }

    @Test
    fun `搬运 64MB 时堆占用不随体积增长（分段流式而非整块读入）`() {
        val size = 64L * 1024 * 1024
        // 先回到稳定基线，避免上一条用例的垃圾拉高起点
        System.gc()
        Thread.sleep(80)
        val baseline = usedHeap()

        var peak = baseline
        var received = 0L
        // 丢弃型 sink：内容不累积在内存里，只留下"收到了多少字节"
        val discard = object : Sink {
            override fun write(source: Buffer, byteCount: Long) {
                val now = usedHeap()
                if (now > peak) peak = now
                source.skip(byteCount)
                received += byteCount
            }

            override fun flush() = Unit

            override fun close() = Unit

            override fun timeout(): Timeout = Timeout.NONE
        }
        val sink = discard.buffer()

        MediaSourceRequestBody(patternSource(size)).writeTo(sink)
        sink.flush()

        assertEquals(size, received)
        // 整块 readBytes 会让峰值凭空多出 64MB；流式搬运则只有 Okio 的 8KB 段在堆里。
        // 阈值放到 24MB，给 GC 时机留足余量，但远小于 64MB，能稳定区分两种实现。
        val growthMb = (peak - baseline) / 1024 / 1024
        assertTrue(
            "搬运 64MB 素材时堆增长 ${growthMb}MB，疑似整块读入内存",
            growthMb < 24
        )
    }

    @Test
    fun `超过 200MB 的素材在构造阶段就被拒绝`() {
        try {
            patternSource(MediaSource.MAX_UPLOAD_BYTES + 1)
            fail("应抛 MediaTooLargeException")
        } catch (e: MediaTooLargeException) {
            assertEquals(MediaSource.MAX_UPLOAD_BYTES + 1, e.sizeBytes)
        }
    }

    @Test
    fun `ofFile 在 close 时删除临时文件且重复 close 安全`() {
        val tmp = File.createTempFile("apk-upload", ".apk")
        tmp.writeBytes(ByteArray(16) { it.toByte() })
        val source = MediaSource.ofFile(tmp, "application/vnd.android.package-archive", deleteWhenClosed = true)

        assertEquals(16L, source.contentLengthForTest())
        assertEquals(16, source.open().use { it.readBytes().size })
        assertTrue(tmp.exists())

        source.close()
        source.close() // 幂等：第二次不能抛
        assertTrue("临时副本必须在上传结束后删除", !tmp.exists())
    }

    @Test
    fun `未声明 deleteWhenClosed 时不删调用方的文件`() {
        val tmp = File.createTempFile("apk-keep", ".apk")
        tmp.writeBytes(ByteArray(8))
        val source = MediaSource.ofFile(tmp, "application/vnd.android.package-archive")

        source.close()

        assertTrue(tmp.exists())
        tmp.delete()
    }

    private fun MediaSource.contentLengthForTest(): Long =
        MediaSourceRequestBody(this).contentLength()

    private fun usedHeap(): Long {
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }

    private fun sumOf(bytes: ByteArray): Long {
        var s = 0L
        for (b in bytes) s += (b.toInt() and 0xFF)
        return s
    }

    /** 与 [PatternStream] 的生成规则一致，用于校验内容没有被改写 */
    private fun patternSum(total: Long): Long {
        var s = 0L
        for (i in 0 until total) s += (i % 251)
        return s
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** 纯生成流：不预分配内存，取多少给多少 */
    private class PatternStream(private val total: Long) : InputStream() {
        private var pos = 0L

        override fun read(): Int =
            if (pos >= total) -1 else ((pos++ % 251).toInt())

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= total) return -1
            val n = minOf(len.toLong(), total - pos).toInt()
            for (i in 0 until n) b[off + i] = ((pos + i) % 251).toByte()
            pos += n
            return n
        }
    }
}
