package com.padguard.child.ui.message

import com.padguard.core.transport.TransportSettings
import javax.inject.Inject

/**
 * 媒体地址统一解析。
 *
 * 服务端 [com.padguard.server.service.FileStorageService] 在 base-url 留空时下发相对路径
 * （如 `/v1/files/{id}`），孩子端必须拼成绝对地址才能下载。全链路只此一处解析，
 * 杜绝原 [com.padguard.child.ui.message.MediaHelpers.downloadToCache] 内"空 baseUrl 二次解析"
 * 导致相对路径原样返回、HttpURLConnection 抛 MalformedURLException 静默失败的隐患。
 *
 * 解析优先级：
 * 1. 已是绝对地址（含 `://`）→ 原样返回；
 * 2. 相对路径 → 用 [TransportSettings.baseUrl] 的 origin 拼接；
 * 3. [TransportSettings.baseUrl] 也为空 → 回退到 [lastGoodOrigin]（最近一次成功下载的 origin，自愈）。
 */
class MediaUrlResolver @Inject constructor(
    private val transportSettings: TransportSettings
) {
    fun resolve(raw: String): String {
        if (raw.isBlank()) return raw
        if (!raw.startsWith("/")) return raw // 已是绝对地址
        val base = transportSettings.baseUrl.value.trimEnd('/')
        val origin = originOf(base).ifBlank { lastGoodOrigin }
        if (origin.isBlank()) return raw // 无法解析，交由下载层给出明确错误而非静默失败
        return "$origin$raw"
    }

    private fun originOf(base: String): String {
        if (base.isBlank()) return ""
        val schemeSlash = base.indexOf("://")
        val start = if (schemeSlash >= 0) schemeSlash + 3 else 0
        val slash = base.indexOf('/', start)
        return if (slash >= 0) base.substring(0, slash) else base
    }

    companion object {
        /** 自愈：最近一次成功下载的 origin，作为 baseUrl 为空时的兜底 */
        @Volatile
        var lastGoodOrigin: String = ""
            private set

        fun noteSuccess(absoluteUrl: String) {
            val schemeSlash = absoluteUrl.indexOf("://")
            if (schemeSlash < 0) return
            val start = schemeSlash + 3
            val slash = absoluteUrl.indexOf('/', start)
            lastGoodOrigin = if (slash >= 0) absoluteUrl.substring(0, slash) else absoluteUrl
        }
    }
}
