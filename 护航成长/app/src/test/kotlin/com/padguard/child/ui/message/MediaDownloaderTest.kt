package com.padguard.child.ui.message

import android.content.Context
import com.padguard.core.common.Logger
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * [MediaDownloader] 的下载与兜底行为（纯 JVM，用拦截器模拟服务端，不占端口）。
 *
 * 守护两条真实故障链：
 * 1. 家长发布的视频/图片下载 404（`/api/v1/v1/files/{id}` 重复前缀）——由
 *    [com.padguard.core.transport.http.HostSelectionInterceptorTest] 守；
 * 2. 签名 URL 过期（服务端 TTL 默认 7 天）后历史消息里的视频 403 —— 本类守：
 *    去掉 `exp`/`sig` 重试一次即可恢复，且请求仍携带设备令牌走归属校验。
 */
class MediaDownloaderTest {

    private lateinit var cacheDir: File
    private lateinit var context: Context
    private lateinit var downloader: MediaDownloader
    private val signedHits = AtomicInteger()
    private val unsignedHits = AtomicInteger()
    private val body = "FAKE-VIDEO-BYTES-CONTENT".toByteArray()

    private var originalLoggerEnabled = true

    @Before
    fun setUp() {
        originalLoggerEnabled = Logger.enabled
        Logger.enabled = false
        cacheDir = Files.createTempDirectory("media-cache").toFile()

        context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.cacheDir).thenReturn(cacheDir)
        val resolver = Mockito.mock(MediaUrlResolver::class.java)
        Mockito.`when`(resolver.resolve(Mockito.anyString()))
            .thenAnswer { it.arguments[0] as String }
        downloader = MediaDownloader(fakeServer(), resolver)
    }

    @After
    fun tearDown() {
        cacheDir.deleteRecursively()
        Logger.enabled = originalLoggerEnabled
    }

    /**
     * 模拟服务端：
     * - `/v1/files/missing` → 404（文件真的不存在）
     * - 带 `sig` 参数 → 403（签名已过期）
     * - 其余 → 200 + 视频字节
     */
    private fun fakeServer(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            val signed = request.url.queryParameter("sig") != null
            when {
                path.endsWith("/missing") -> {
                    signedHits.incrementAndGet()
                    response(request, 404)
                }
                signed -> {
                    signedHits.incrementAndGet()
                    response(request, 403)
                }
                else -> {
                    unsignedHits.incrementAndGet()
                    response(request, 200, body)
                }
            }
        }
        .build()

    private fun response(request: okhttp3.Request, code: Int, payload: ByteArray? = null): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "ERR")
            .body((payload ?: ByteArray(0)).toResponseBody("video/mp4".toMediaType()))
            .build()

    private fun url(path: String): String = "http://127.0.0.1:8090$path"

    @Test
    fun `签名过期返回 403 时去掉 exp 与 sig 重试并成功`() = runBlocking {
        val target = url("/v1/files/expired") + "?exp=1&sig=deadbeef"

        val file = downloader.download(context, target)

        assertEquals(String(body), file.readText())
        assertEquals("带签名请求应恰好一次", 1, signedHits.get())
        assertEquals("去签名兜底应恰好一次", 1, unsignedHits.get())
        assertTrue(file.name.startsWith("msg_"))
    }

    @Test
    fun `文件不存在返回 404 时直接失败且不做签名兜底`() = runBlocking {
        val target = url("/v1/files/missing") + "?exp=1&sig=deadbeef"
        try {
            downloader.download(context, target)
            fail("404 必须抛 IOException，绝不能静默返回半截文件")
        } catch (e: IOException) {
            assertTrue("错误信息应带状态码：" + e.message, e.message.orEmpty().contains("404"))
        }
        // 失败后不得把 .part 残留成正式缓存文件
        val leftovers = cacheDir.walkTopDown().filter { it.isFile }.toList()
        assertTrue("失败后不应留下缓存文件：" + leftovers, leftovers.isEmpty())
    }

    @Test
    fun `无签名地址直接下载成功`() = runBlocking {
        val file = downloader.download(context, url("/v1/files/expired"))

        assertEquals(String(body), file.readText())
        assertEquals(0, signedHits.get())
        assertEquals(1, unsignedHits.get())
    }
}
