package com.padguard.parent.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 服务端地址（baseUrl）的安全准入，纯 Kotlin / 无 Android 依赖，可直接跑 JVM 单测。
 *
 * ## 为什么需要它
 *
 * 家长端 `AndroidManifest` 全局 `usesCleartextTraffic="true"`，并且登录页「高级设置」
 * 允许运行时把地址改成任意 host。这是**家庭/校园内网私有化部署**的必要能力
 * （服务端跑在家长电脑/校内服务器上，只有 http），不能一刀切关掉。
 *
 * 但同一套机制也意味着：只要有人把地址写成 `http://<公网 IP 或域名>`，登录令牌、
 * 孩子的位置/截屏/使用记录就会以明文过公网，可被链路上的任意一方截获。
 * 历史 `build.gradle.kts` 甚至把 `http://8.148.68.183:8090/v1/` 同时写进了
 * defaultConfig 与 debug —— 这不是"用户配置错误"，而是代码主动默认了不安全链路。
 *
 * Manifest 里原有的注释把这种情况归为「配置错误而非本开关造成」。本类的作用就是
 * 把这类配置错误变成**代码层面不可能发生**，同时不牺牲内网部署的可用性。
 *
 * ## 规则
 *
 * - `https://` → 放行（任何 host）。
 * - `http://` + 回环/私网 IP 或 `localhost` → 放行，标记为 [Level.LAN_CLEARTEXT]。
 * - `http://` + 其余（公网 IP、域名） → 拒绝，除非调用方显式传入
 *   `allowCleartextPublic = true`（由 `BuildConfig.ALLOW_CLEARTEXT_PUBLIC` 注入，
 *   仅用于「公网 IP 但实际走专线/VPN」这类需要人工确认的特殊部署）。
 * - 域名形式的 http 一律拒绝：域名可能解析到任意地址，静态判定无法保证是内网。
 *   若学校内网使用 mDNS 域名且确实没有 TLS，应走 HTTPS 或显式开关，不在此静默放行。
 *
 * ## 判定基于规范化后的 host
 *
 * 通过 OkHttp 的 [toHttpUrlOrNull] 解析后再判 host，避免 `http://2130706433/`
 * 这类非点分十进制写法绕过私网判定；解析不了一律按"非私网"拒绝（保守）。
 *
 * 本类与 `app/build.gradle.kts` 的 release 构建守卫是两道独立的防线：前者管运行时，
 * 后者管出厂包。**调整规则时必须同步两处**（Gradle 脚本无法引用 app 源码）。
 */
object EndpointSafety {

    enum class Level {
        /** https，链路加密 */
        SECURE,

        /** http，但目标是回环/私网，属私有化部署的既定口径 */
        LAN_CLEARTEXT,

        /** http 且目标非私网，由构建期显式开关放行；必须在运维侧留痕 */
        EXPLICITLY_INSECURE
    }

    sealed class Verdict {
        data class Allowed(val level: Level, val normalizedUrl: String) : Verdict()
        data class Rejected(val reason: String) : Verdict()
    }

    /** Retrofit baseUrl 必须以 `/` 结尾；这里统一补齐，避免各调用点各写一套。 */
    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return if (trimmed.endsWith('/')) trimmed else "$trimmed/"
    }

    fun check(raw: String, allowCleartextPublic: Boolean = false): Verdict {
        val normalized = normalize(raw)
            ?: return Verdict.Rejected("服务器地址不能为空")

        val url = normalized.toHttpUrlOrNull()
            ?: return Verdict.Rejected("地址格式不合法，请填写完整的 http:// 或 https:// 地址")

        // userinfo 既可能是凭据泄漏，也可能是「@」钓鱼（http://safe@evil.com/）
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            return Verdict.Rejected("服务器地址不能包含用户名或密码")
        }
        if (!url.query.isNullOrEmpty() || !url.fragment.isNullOrEmpty()) {
            return Verdict.Rejected("服务器地址不能包含查询参数或锚点")
        }

        // 地址自愈（2026-11-07「连不上服务器」）：家长在「高级设置」里最常只填
        // `http://192.168.1.11:8090`，而 Retrofit 接口注解全是相对 `v1/` 的 ——
        // 少了前缀后所有请求都打到 `/devices`、`/auth/login/password` 等根路径，
        // 服务端一律 404，客户端却报「连不上服务器」，排查极困难。
        // 根路径时统一补齐 `/v1/`；已有自定义前缀（如 `/api/v1/`）保持原样。
        val effective = if (url.encodedPath == "/") {
            url.newBuilder().encodedPath("/v1/").build().toString()
        } else normalized

        if (url.scheme == "https") return Verdict.Allowed(Level.SECURE, effective)

        // 到这里只可能是 http（OkHttp 的 HttpUrl 仅支持 http/https）
        if (isLanHost(url.host)) return Verdict.Allowed(Level.LAN_CLEARTEXT, effective)
        if (allowCleartextPublic) {
            return Verdict.Allowed(Level.EXPLICITLY_INSECURE, effective)
        }
        return Verdict.Rejected(
            "公网地址必须使用 HTTPS：当前为明文 HTTP，登录令牌与儿童数据可能被截获。" +
                "家庭/校园内网请填写 10.x / 172.16~31.x / 192.168.x / 127.x 等内网 IP。"
        )
    }

    /**
     * 只有能**明确**判定为回环/私网/链路本地的 host 才返回 true；其余一律 false。
     * 判定偏保守：漏放只影响个别内网写法（可显式开关），误放则直接造成明文公网传输。
     */
    fun isLanHost(host: String): Boolean {
        val h = host.trim().trimStart('[').trimEnd(']').lowercase()
        if (h.isEmpty()) return false
        if (h == "localhost") return true

        parseIpv4(h)?.let { (a, b, _, _) ->
            return when {
                a == 127 -> true                       // 127.0.0.0/8 回环
                a == 10 -> true                        // 10.0.0.0/8
                a == 172 && b in 16..31 -> true        // 172.16.0.0/12
                a == 192 && b == 168 -> true           // 192.168.0.0/16
                a == 169 && b == 254 -> true           // 169.254.0.0/16 链路本地（USB 直连常见）
                else -> false
            }
        }

        if (h.contains(':')) {
            if (h == "::1" || h == "0:0:0:0:0:0:0:1") return true
            // IPv4-mapped IPv6（::ffff:192.168.1.10）：按尾部 IPv4 再判一次
            if (h.startsWith("::ffff:")) {
                val mapped = h.substringAfterLast(':')
                if (parseIpv4(mapped) != null) return isLanHost(mapped)
            }
            // fc00::/7 唯一本地地址
            if (h.startsWith("fc") || h.startsWith("fd")) return true
            // fe80::/10 链路本地
            if (h.startsWith("fe8") || h.startsWith("fe9") ||
                h.startsWith("fea") || h.startsWith("feb")
            ) return true
        }
        return false
    }

    /** 只接受严格的点分十进制；`2130706433`、`0x7f000001` 等写法一律返回 null → 按非私网处理。 */
    private fun parseIpv4(value: String): IntArray? {
        val parts = value.split('.')
        if (parts.size != 4) return null
        val result = IntArray(4)
        for (i in 0..3) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
            val v = part.toIntOrNull() ?: return null
            if (v !in 0..255) return null
            result[i] = v
        }
        return result
    }
}
