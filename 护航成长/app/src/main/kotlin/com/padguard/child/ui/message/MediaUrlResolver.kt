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
        // 绝对地址：服务端已基于「当前请求 Host」生成（见 FileStorageService.resolveOriginUrl），
        // 就是孩子端实际能访问的服务端地址，直接使用，绝不再用自身可能错误的 baseUrl 去改写——
        // 旧版 alignToCurrentOrigin 会把正确的服务器地址改成孩子端写死的 origin（如硬编码公网），
        // 反而把文件指向另一台机器 → 整批视频 / 截图 / 录屏 404。这是此前「视频加载失败」的根因之一。
        if (raw.contains("://")) return raw
        // 兼容极旧数据 / 离线重算留下的相对路径：用当前 baseUrl 的 origin 拼接
        // （旧版只认 "/" 开头，会把 "v1/files/x" 误判成绝对地址，这里兼容不带前导斜杠的形态）
        val path = if (raw.startsWith("/")) raw else "/$raw"
        val origin = originOf(transportSettings.baseUrl.value).ifBlank { lastGoodOrigin }
        if (origin.isBlank()) return raw // 无法解析，交由下载层给出明确错误而非静默失败
        return "$origin$path"
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
