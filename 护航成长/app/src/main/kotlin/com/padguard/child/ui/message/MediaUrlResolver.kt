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
 * 1. 已是绝对地址（含 `://`）→ 走 [alignToCurrentOrigin] 校正后返回；
 * 2. 相对路径（带或不带前导 `/`）→ 用 [TransportSettings.baseUrl] 的 origin 拼接；
 * 3. [TransportSettings.baseUrl] 也为空 → 回退到 [lastGoodOrigin]（最近一次成功下载的 origin，自愈）。
 *
 * ## 为什么绝对地址也要校正（真实故障）
 * 服务端若配置了对外 base-url（例如指向另一台服务器 `http://8.148.68.183:8090`），
 * 下发的就是绝对地址；而文件实际落在"孩子端当前连的那个服务端"上。孩子端拿这个地址
 * 去请求另一台机器 → 404 → 表现为「视频加载失败」。
 * 因此对服务端自有文件接口 `/v1/files/{id}` 这类路径，统一改写为当前 origin；
 * 对真正的外部地址（对象存储/CDN，不含 `/v1/files/`）保持原样，不误伤。
 */
class MediaUrlResolver @Inject constructor(
    private val transportSettings: TransportSettings
) {
    fun resolve(raw: String): String {
        if (raw.isBlank()) return raw
        if (raw.contains("://")) return alignToCurrentOrigin(raw)
        // 相对路径：兼容不带前导斜杠的形态（原实现只认 "/" 开头，会把 "v1/files/x" 误判成绝对地址）
        val path = if (raw.startsWith("/")) raw else "/$raw"
        val origin = originOf(transportSettings.baseUrl.value).ifBlank { lastGoodOrigin }
        if (origin.isBlank()) return raw // 无法解析，交由下载层给出明确错误而非静默失败
        return "$origin$path"
    }

    /**
     * 绝对地址校正：仅针对服务端自有文件接口 `/v1/files/{id}`。
     *
     * 地址 host 与孩子端当前连接的 [TransportSettings.baseUrl] 不一致时（服务端迁移、
     * base-url 配错、历史消息残留旧地址），改写为当前 origin，避免整批历史媒体 404。
     * 其他地址（外部 CDN / 对象存储）一律原样返回。
     */
    private fun alignToCurrentOrigin(absolute: String): String {
        val markerIndex = absolute.indexOf(FILE_MARKER)
        if (markerIndex < 0) return absolute
        val currentOrigin = originOf(transportSettings.baseUrl.value)
        if (currentOrigin.isBlank()) return absolute
        val urlOrigin = originOf(absolute)
        if (urlOrigin.equals(currentOrigin, ignoreCase = true)) return absolute
        return currentOrigin + absolute.substring(markerIndex)
    }

    private fun originOf(base: String): String {
        if (base.isBlank()) return ""
        val schemeSlash = base.indexOf("://")
        val start = if (schemeSlash >= 0) schemeSlash + 3 else 0
        val slash = base.indexOf('/', start)
        return if (slash >= 0) base.substring(0, slash) else base
    }

    companion object {
        /** 服务端自有文件接口的路径标记；命中才做 origin 校正，避免误伤外部地址 */
        private const val FILE_MARKER = "/v1/files/"

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
