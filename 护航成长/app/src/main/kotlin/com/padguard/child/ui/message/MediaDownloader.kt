package com.padguard.child.ui.message

import android.content.Context
import com.padguard.core.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
        var url = resolver.resolve(rawUrl)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw IOException("媒体地址无法解析为可访问 URL：原始=$rawUrl 解析后=$url")
        }
        val dir = File(context.cacheDir, CACHE_SUBDIR).also { it.mkdirs() }
        val out = File(dir, "msg_" + url.hashCode().toString().replace("-", "n"))
        if (out.exists() && out.length() > 0) return@withContext out

        // 先写 .part 临时文件、成功后才原子改名：
        // 直接写目标文件的话，进程被杀 / 网络中断会留下半截文件，而上面的缓存命中只判断
        // "存在且非空"，会把损坏文件永久复用 → 视频每次都走到播放失败。原子落盘杜绝该隐患。
        val tmp = File(dir, out.name + ".part")
        runCatching { if (tmp.exists()) tmp.delete() }

        val callHolder = arrayOfNulls<okhttp3.Call>(1)
        coroutineContext[Job]?.invokeOnCompletion { callHolder[0]?.cancel() }

        var lastErr: Throwable? = null
        var signedFallbackTried = false
        var attempt = 0
        while (attempt < MAX_ATTEMPTS) {
            if (!coroutineContext.isActive) throw IOException("下载已取消")
            val call = client.newCall(Request.Builder().url(url).get().build())
            callHolder[0] = call
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) throw HttpStatusException(resp.code)
                    resp.body?.byteStream()?.use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    } ?: throw IOException("响应体为空")
                }
                if (tmp.length() <= 0) throw IOException("下载内容为空")
                if (!tmp.renameTo(out)) {
                    // 极少数文件系统 rename 失败（跨挂载点等）：退化为拷贝写入
                    tmp.inputStream().use { input -> out.outputStream().use { input.copyTo(it) } }
                    runCatching { tmp.delete() }
                }
                MediaUrlResolver.noteSuccess(url)
                return@withContext out
            } catch (e: Exception) {
                lastErr = e
                runCatching { if (tmp.exists()) tmp.delete() }
                if (!coroutineContext.isActive) throw e
                // 签名 URL 过期兜底（只做一次）。
                // 服务端下发的 /v1/files/{id} 带 ?exp=&sig=，TTL 默认 7 天；而家长发布的消息
                // 会永久留存在孩子端本地库里 —— 孩子隔几天再看历史视频，签名必然过期，
                // 服务端验签 fail-closed 直接 403，表现就是「之前能看的视频现在加载失败」。
                // 去掉签名重下一次即可：请求仍带设备令牌，服务端走归属校验（家长发布的素材
                // 对任何设备凭证放行），并未放宽任何访问边界；require-signature 闸门开启的
                // 部署同样按「有凭证」分支正常放行。
                val fallback = if (!signedFallbackTried && e is HttpStatusException && e.code == 403) {
                    stripSignature(url)
                } else null
                if (fallback != null) {
                    signedFallbackTried = true
                    Logger.w(TAG) { "media url rejected (signature expired?), retry without exp/sig" }
                    url = fallback
                    continue
                }
                attempt++
                if (coroutineContext.isActive && attempt < MAX_ATTEMPTS) {
                    delay(attempt * BACKOFF_MS)
                }
            }
        }
        throw lastErr ?: IOException("媒体下载失败")
    }

    /** 带 HTTP 状态码的失败，供上层区分「签名过期 403」与普通网络异常。 */
    private class HttpStatusException(val code: Int) : IOException("HTTP $code")

    /**
     * 去掉 `exp`/`sig` 查询参数；没有签名参数时返回 null（表示无需兜底）。
     *
     * 重复签名会留下多份 `exp`/`sig`（服务端 `MediaUrlSigner.sign` 直接在已有 query 后追加），
     * 因此用 `removeAllQueryParameters` 一次性清干净。
     */
    private fun stripSignature(url: String): String? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        val names = parsed.queryParameterNames.filter { it == "exp" || it == "sig" }
        if (names.isEmpty()) return null
        return parsed.newBuilder().apply {
            names.forEach { removeAllQueryParameters(it) }
        }.build().toString()
    }

    companion object {
        private const val TAG = "MediaDownloader"
        private const val CACHE_SUBDIR = "msg_media"
        private const val MAX_ATTEMPTS = 2
        private const val BACKOFF_MS = 800L
    }
}
