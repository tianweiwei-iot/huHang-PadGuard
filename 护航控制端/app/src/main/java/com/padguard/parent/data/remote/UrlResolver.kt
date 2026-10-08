package com.padguard.parent.data.remote

/**
 * 相对路径补成绝对地址（与 [com.padguard.parent.data.remote.ApiDataSource.absoluteUrl] 同款逻辑）。
 *
 * 服务端返回的文件/图标地址（如 `/v1/files/{id}`）是相对服务端根的，必须在取数时按运行时服务器地址拼成绝对 URL，
 * 否则换网络/换电脑后图标集体裂开。WS 实时推送路径也用同一个函数，保证推送来的图标和 HTTP 拉来的图标解析一致。
 */
fun resolveAbsoluteUrl(baseUrl: String, path: String?): String? {
    val p = path?.takeIf { it.isNotBlank() } ?: return null
    if (p.startsWith("http://", ignoreCase = true) || p.startsWith("https://", ignoreCase = true)) return p
    val base = baseUrl.trim().trimEnd('/')
    return withoutOverlap(base, p) + if (p.startsWith("/")) p else "/$p"
}

/**
 * 去掉服务器地址末尾与资源路径开头重复的路径段。
 * 服务端地址往往带 `/v1` 接口前缀，而资源路径也是 `/v1/...`；直接拼接会得到 `/v1/v1/...` → 500。
 * 按段去重，服务器地址带不带前缀都能拼对（保护条件避免把 `http://v1` 这类地址削坏）。
 */
private fun withoutOverlap(base: String, path: String): String {
    var b = base.trimEnd('/')
    val normalized = if (path.startsWith("/")) path else "/$path"
    val firstSeg = normalized.trimStart('/').substringBefore('/')
    if (firstSeg.isEmpty()) return b
    val suffix = "/$firstSeg"
    while (b.endsWith(suffix) && b.removeSuffix(suffix).contains("://")) {
        b = b.removeSuffix(suffix)
    }
    return b
}
