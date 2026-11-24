package com.padguard.core.transport

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 孩子端（被管控端）服务端接入点的安全准入，纯 Kotlin / 无 Android 依赖，可直接跑 JVM 单测。
 *
 * ## 为什么孩子端也需要它
 *
 * 家长端 `AndroidManifest` 全局 `usesCleartextTraffic="true"`（家庭/校园内网私有化部署必须），
 * 且「高级设置」允许运行时把地址改成任意 host。孩子端**也**开了同一个开关（同一信任域），
 * 于是只要有人往孩子端「高级设置」里手填一个 `http://<公网 IP 或域名>`，登录令牌、
 * 孩子的位置/截屏/使用记录就会以明文过公网，可被链路上的任意一方截获。
 *
 * 家长端早就有运行时校验 [com.padguard.parent.data.remote.EndpointSafety]，本类是孩子端的对等实现，
 * 下沉到**共用的传输层**（`core/transport`），所有写入地址的代码都经过它：
 *
 * - [com.padguard.core.transport.TransportSettings.setBaseUrl]（高级设置 / 绑定欢迎页手填）
 * - [com.padguard.core.transport.TransportSettings.setMqttBroker]（含服务端绑定响应下发的 broker）
 *
 * 与 `app/build.gradle.kts` 的 release 构建守卫（`validateReleaseEndpoints`）是两道独立防线：
 * 前者管运行时，后者管出厂包。**调整规则时必须同步两处**（Gradle 脚本无法引用 app 源码）。
 *
 * ## 规则（HTTP）
 *
 * - `https://` → 放行（任何 host）。
 * - `http://` + 回环/私网 IP 或 `localhost` → 放行，标记为 [Level.LAN_CLEARTEXT]。
 * - `http://` + 其余（公网 IP、域名） → 拒绝，除非显式 `allowCleartextPublic = true`
 *   （仅用于「公网 IP 但实际走专线/VPN」这类需人工确认的特殊部署，**孩子端默认关死**）。
 * - 域名形式的 http 一律拒绝：域名可能解析到任意地址，静态判定无法保证是内网。
 *
 * ## 规则（MQTT broker URI）
 *
 * - `ssl://` / `tls://` / `wss://` → 放行（链路加密）。
 * - `tcp://` / `ws://` + 回环/私网 → 放行（[Level.LAN_CLEARTEXT]）。
 * - `tcp://` / `ws://` + 公网 → 拒绝，除非显式 `allowCleartextPublic`。
 * - 缺 scheme / 未知 scheme / 带 userinfo → 拒绝。
 *
 * broker URI 是 `tcp://host:port` 形态，OkHttp 的 [toHttpUrlOrNull] 不认，故单独解析。
 *
 * ## 判定基于规范化后的 host
 *
 * 通过 `HttpUrl` 解析后再判 host，避免 `http://2130706433/` 这类非点分十进制写法绕过私网判定；
 * 解析不了一律按「非私网」拒绝（保守）。
 */
/**
 * 安全准入判定结果分级。
 *
 * - [Level.SECURE]：https / ssl / wss，链路加密；
 * - [Level.LAN_CLEARTEXT]：http / tcp / ws，但目标是回环/私网，属私有化部署既定口径；
 * - [Level.EXPLICITLY_INSECURE]：明文且目标非私网，由显式开关放行，必须在运维侧留痕。
 */
enum class Level {
    SECURE,
    LAN_CLEARTEXT,
    EXPLICITLY_INSECURE
}

/** 接入点校验结果：[Allowed] 含规范化后的地址与分级；[Rejected] 含拒绝原因。 */
sealed class Verdict {
    data class Allowed(val level: Level, val normalizedUrl: String) : Verdict()
    data class Rejected(val reason: String) : Verdict()
}

/**
 * 孩子端（被管控端）服务端接入点的安全准入，纯 Kotlin / 无 Android 依赖，可直接跑 JVM 单测。
 *
 * [Level] / [Verdict] 放在文件顶层而非 object 内部，以保证调用处对 `Verdict.Allowed` /
 * `Verdict.Rejected` 的 smart-cast 可靠生效（嵌套在 object 内时 Kotlin 对全限定名的 smart-cast 有已知缺陷）。
 *
 * ## 为什么孩子端也需要它
 *
 * 家长端 `AndroidManifest` 全局 `usesCleartextTraffic="true"`（家庭/校园内网私有化部署必须），
 * 且「高级设置」允许运行时把地址改成任意 host。孩子端**也**开了同一个开关（同一信任域），
 * 于是只要有人往孩子端「高级设置」里手填一个 `http://<公网 IP 或域名>`，登录令牌、
 * 孩子的位置/截屏/使用记录就会以明文过公网，可被链路上的任意一方截获。
 *
 * 家长端早就有运行时校验 [com.padguard.parent.data.remote.EndpointSafety]，本类是孩子的对等实现，
 * 下沉到**共用的传输层**（`core/transport`），所有写入地址的代码都经过它：
 *
 * - [TransportSettings.setBaseUrl]（高级设置 / 绑定欢迎页手填）
 * - [TransportSettings.setMqttBroker]（含服务端绑定响应下发的 broker）
 *
 * 与 `app/build.gradle.kts` 的 release 构建守卫（`validateReleaseEndpoints`）是两道独立防线：
 * 前者管运行时，后者管出厂包。**调整规则时必须同步两处**（Gradle 脚本无法引用 app 源码）。
 *
 * ## 规则（HTTP）
 *
 * - `https://` → 放行（任何 host）。
 * - `http://` + 回环/私网 IP 或 `localhost` → 放行，标记为 [Level.LAN_CLEARTEXT]。
 * - `http://` + 其余（公网 IP、域名） → 拒绝，除非显式 `allowCleartextPublic = true`
 *   （仅用于「公网 IP 但实际走专线/VPN」这类需人工确认的特殊部署，**孩子端默认关死**）。
 * - 域名形式的 http 一律拒绝：域名可能解析到任意地址，静态判定无法保证是内网。
 *
 * ## 规则（MQTT broker URI）
 *
 * - `ssl://` / `tls://` / `wss://` → 放行（链路加密）。
 * - `tcp://` / `ws://` + 回环/私网 → 放行（[Level.LAN_CLEARTEXT]）。
 * - `tcp://` / `ws://` + 公网 → 拒绝，除非显式 `allowCleartextPublic`。
 * - 缺 scheme / 未知 scheme / 带 userinfo → 拒绝。
 *
 * broker URI 是 `tcp://host:port` 形态，OkHttp 的 [okhttp3.HttpUrl.Companion.toHttpUrlOrNull] 不认，故单独解析。
 *
 * ## 判定基于规范化后的 host
 *
 * 通过 `HttpUrl` 解析后再判 host，避免 `http://2130706433/` 这类非点分十进制写法绕过私网判定；
 * 解析不了一律按「非私网」拒绝（保守）。
 */
object EndpointSafety {

    /** 孩子端 REST 接口统一挂在 `/api/v1/` 下；根路径时自愈补这个前缀。 */
    const val CHILD_API_PREFIX = "/api/v1/"

    /** Retrofit baseUrl 必须以 `/` 结尾；这里统一补齐，避免各调用点各写一套。 */
    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return if (trimmed.endsWith('/')) trimmed else "$trimmed/"
    }

    /**
     * 校验一个 HTTP baseUrl。
     *
     * 除安全准入外，还做**地址自愈**：孩子端最常只填 `http://192.168.1.11:8090`，
     * 而 Retrofit 接口注解全是相对 `/api/v1/...` 的 —— 少了前缀后所有请求打到根路径，
     * 服务端一律 404，客户端却报「连不上服务器」，排查极困难。根路径统一补齐 [CHILD_API_PREFIX]；
     * 已有自定义前缀（如 `/v1/`）保持原样。
     */
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

        val effective = if (url.encodedPath == "/") {
            url.newBuilder().encodedPath(CHILD_API_PREFIX).build().toString()
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
     * 校验 MQTT broker URI（[setMqttBroker] 写入前调用）。
     * 详见类注释「规则（MQTT broker URI）」。
     */
    fun checkMqtt(raw: String, allowCleartextPublic: Boolean = false): Verdict {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Verdict.Rejected("MQTT 服务器地址不能为空")

        val schemeSep = trimmed.indexOf("://")
        if (schemeSep < 0) {
            return Verdict.Rejected("MQTT 服务器地址必须包含协议（如 ssl:// 或 tcp://）")
        }
        val scheme = trimmed.substring(0, schemeSep).lowercase()
        val rest = trimmed.substring(schemeSep + 3)

        // 拒绝一切 userinfo：MQTT 凭据（设备令牌）明文出现在 URI 里既是泄漏也是钓鱼载体
        val hostPort = if (rest.contains('@')) {
            val userinfo = rest.substringBeforeLast('@')
            if (userinfo.isNotEmpty()) {
                return Verdict.Rejected("MQTT 服务器地址不能包含用户名或密码")
            }
            rest.substringAfterLast('@')
        } else rest

        if (hostPort.isEmpty()) return Verdict.Rejected("MQTT 服务器地址缺少主机名")

        // 加密链路：任何 host 都放行
        if (scheme == "ssl" || scheme.startsWith("tls") || scheme == "wss") {
            return Verdict.Allowed(Level.SECURE, trimmed)
        }
        // 仅明文链路需要判私网
        if (scheme != "tcp" && scheme != "ws") {
            return Verdict.Rejected("不支持的 MQTT 协议：$scheme（仅支持 ssl/tcp/wss/ws）")
        }
        val host = hostPort.substringBefore(':')
            .trimStart('[').trimEnd(']')
        if (isLanHost(host)) return Verdict.Allowed(Level.LAN_CLEARTEXT, trimmed)
        if (allowCleartextPublic) {
            return Verdict.Allowed(Level.EXPLICITLY_INSECURE, trimmed)
        }
        return Verdict.Rejected(
            "公网 MQTT 必须使用 SSL/TLS（ssl:// 或 wss://）：当前为明文 $scheme，设备令牌可能被截获。" +
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
