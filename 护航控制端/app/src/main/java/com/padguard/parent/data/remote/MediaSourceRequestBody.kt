package com.padguard.parent.data.remote

import com.padguard.parent.domain.model.MediaSource
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source

/**
 * 把 [MediaSource] 适配成 OkHttp 请求体（流式，不把素材读进堆）。
 *
 * 关键点：
 * - [writeTo] 里 `writeAll` 按 Okio 分段（8KB）从源流搬到 socket，峰值内存与文件大小无关；
 * - 每次 [writeTo] 都重新 [MediaSource.open]，因此 OkHttp 重写同一请求体（鉴权 401 重试、
 *   重定向）时内容依然完整 —— 这是 `isOneShot` 保持默认 `false` 的前提；
 * - `contentLength` 未知时返回 -1，OkHttp 自动改用 chunked 编码，服务端照常解析。
 */
class MediaSourceRequestBody(private val source: MediaSource) : RequestBody() {

    override fun contentType() = source.contentType.toMediaTypeOrNull()

    override fun contentLength(): Long =
        if (source.sizeBytes >= 0) source.sizeBytes else -1L

    override fun writeTo(sink: BufferedSink) {
        source.open().use { input -> sink.writeAll(input.source()) }
    }
}
