package com.padguard.child.ui.message

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

/**
 * 媒体下载器。
 *
 * 替代原 [com.padguard.child.ui.message.MediaHelpers.downloadToCache] 的裸 HttpURLConnection，
 * 复用传输层共享 [OkHttpClient]（自带设备鉴权 / Host 选择 / 重试拦截器）：
 *
 * - 鉴权透传：与业务请求同源，自动携带设备令牌，规避文件接口因缺鉴权返回 401/403；
 * - 生命周期感知取消：调用方在 DisposableEffect.onDispose 取消协程时，通过
 *   [Job] 的完成回调中断阻塞中的 [okhttp3.Call]，真正停止下载而非后台空转；
 * - 重试 + 退避：弱网下最多重试 [MAX_ATTEMPTS] 次，避免一次抖动即"加载失败"；
 * - 明确终态：成功返回 [File]，失败抛 [IOException]；调用方据此切到"加载失败/重试"，
 *   绝不陷入无限转圈。
 */
class MediaDownloader @Inject constructor(
    private val client: OkHttpClient,
    private val resolver: MediaUrlResolver
) {
    /**
     * 下载媒体到缓存目录。
     * @param rawUrl 可能为相对路径（边界处通常已解析为绝对，这里再兜底一次）
     * @throws IOException 解析失败或下载失败（含非 2xx / 网络异常）
     */
    @Throws(IOException::class)
    suspend fun download(context: Context, rawUrl: String): File = withContext(Dispatchers.IO) {
        val url = resolver.resolve(rawUrl)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw IOException("媒体地址无法解析为可访问 URL：原始=$rawUrl 解析后=$url")
        }
        val dir = File(context.cacheDir, CACHE_SUBDIR).also { it.mkdirs() }
        val out = File(dir, "msg_" + url.hashCode().toString().replace("-", "n"))
        if (out.exists() && out.length() > 0) return@withContext out

        val callHolder = arrayOfNulls<okhttp3.Call>(1)
        coroutineContext[Job]?.invokeOnCompletion { callHolder[0]?.cancel() }

        var lastErr: Throwable? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            if (!coroutineContext.isActive) throw IOException("下载已取消")
            val call = client.newCall(Request.Builder().url(url).get().build())
            callHolder[0] = call
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    resp.body?.byteStream()?.use { input ->
                        out.outputStream().use { input.copyTo(it) }
                    } ?: throw IOException("响应体为空")
                }
                MediaUrlResolver.noteSuccess(url)
                return@withContext out
            } catch (e: Exception) {
                lastErr = e
                if (coroutineContext.isActive && attempt < MAX_ATTEMPTS - 1) {
                    delay((attempt + 1) * BACKOFF_MS)
                }
            }
        }
        throw lastErr ?: IOException("媒体下载失败")
    }

    companion object {
        private const val CACHE_SUBDIR = "msg_media"
        private const val MAX_ATTEMPTS = 2
        private const val BACKOFF_MS = 800L
    }
}
