package com.padguard.parent.data.remote

import com.padguard.parent.BuildConfig
import com.padguard.parent.data.remote.EndpointSafety.Verdict
import com.padguard.parent.di.ServerPrefs
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 动态 BaseUrl 拦截器（Retrofit 官方推荐的 HostSelectionInterceptor 模式）。
 *
 * Retrofit 的 baseUrl 是构建期固定的，这里用占位地址 `http://placeholder.padguard/placeholder/`
 * 占位，每次请求时把 scheme/host/port 改写成 [ServerPrefs] 里当前生效的地址，
 * 保留接口注解里声明的相对路径（如 `auth/login/password`、`devices`）。
 *
 * 因为读的是 [ServerPrefs.baseUrl] 这个 StateFlow 的实时值，
 * 用户在登录页改完地址后下一次请求立即生效，无需重启 App。
 *
 * 同时承担「明文公网」的最后一道防线：地址在保存时已被 [EndpointSafety] 校验过，
 * 但 SharedPreferences 可能来自旧版本、备份恢复或未来新增的写入点，故发出请求前再复核一次；
 * 校验不通过直接抛 [IOException]（上层能看到可读原因），绝不把请求发到明文公网链路上。
 * 注意 WebSocket 通道不走本拦截器，由 `ParentPushClient.buildWsUrl()` 独立复核。
 */
@Singleton
class HostSelectionInterceptor @Inject constructor(
    private val serverPrefs: ServerPrefs
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val raw = serverPrefs.getBaseUrl()

        // 发请求前的最后一道防线：配置保存时已校验过，但 SharedPreferences 可能被旧版本写入、
        // 被备份恢复、或由未来新增的写入点绕过。这里拦下「公网明文」，并抛 IOException 让
        // OkHttp 走失败回调（上层能看到可读原因），而不是把请求发到明文链路上。
        val verdict = EndpointSafety.check(raw, BuildConfig.ALLOW_CLEARTEXT_PUBLIC)
        if (verdict is Verdict.Rejected) {
            throw IOException("服务器地址不安全：${verdict.reason}")
        }
        val target = (verdict as Verdict.Allowed).normalizedUrl.toHttpUrlOrNull()
            // 不再 `return chain.proceed(request)` 回落到占位域名：那只会造成一次必然失败的
            // DNS 查询和长时间转圈，掩盖真实原因。
            ?: throw IOException("服务器地址无法解析：$raw")

        val rewritten = rewrite(request.url, target)
        return try {
            chain.proceed(request.newBuilder().url(rewritten).build())
        } catch (e: IOException) {
            // 局域网部署下服务端 IP 常因 DHCP / 换 Wi-Fi / USB 直连而变化，出厂地址只可能是
            // 占位示例 IP。连接级失败时先做一次**有界**的局域网自发现：命中就把新地址写回配置，
            // 后续所有请求（含轮询、WS 重连）自动恢复，家长不必手填 IP。
            val recovered = recoverLanAddress(target, e)
            if (recovered != null) {
                // **绝不重放原请求**：它带着 Authorization / Refresh-Token，而新地址只是
                // 「同网段上一个对 /health 应答正常的主机」，明文 HTTP 下没有服务端身份认证；
                // 静默重放等于把家长令牌主动送到刚扫到的陌生机器。只写回地址并明确告知，
                // 由用户知情地重试一次，后续轮询与 WS 重连自动走新地址。
                throw IOException(
                    "已自动把服务器地址修正为 $recovered（同网段发现到服务端），请重试刚才的操作；" +
                        "若这不是你的服务器，请在登录页「⚙ 高级设置」改回正确地址。",
                    e
                )
            }
            // 「连不上服务器」时家长最需要知道的是**当前实际连的是哪台机器**：
            // 设备可能仍存着旧版公网地址（会被 ServerPrefs 升级成 https）、
            // 可能是 debug 出厂的示例 IP 192.168.1.10、也可能是模拟器里的 127.0.0.1。
            // 只说「请检查网络」等于没给原因，所以把 scheme/host/port 与失败分类一起带出来，
            // 家长在登录页「⚙ 高级设置」里照着改即可，改完立即生效、无需重打包。
            throw IOException(connectionFailureMessage(target, e), e)
        }
    }

    /** 探测专用短超时客户端：正常请求客户端的 30s 超时不能用在「扫几个候选 IP」上。 */
    private val probeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(700, TimeUnit.MILLISECONDS)
            .readTimeout(700, TimeUnit.MILLISECONDS)
            .callTimeout(1_200, TimeUnit.MILLISECONDS)
            .build()
    }

    /** 自发现冷却：服务端短暂重启时不该让每个并发请求都各扫一遍局域网。 */
    private val lastRecoveryAtNs = AtomicLong(Long.MIN_VALUE)

    /** @return 已写回配置的新 baseUrl；未发现可用服务端返回 null。 */
    private fun recoverLanAddress(target: HttpUrl, failure: IOException): String? {
        if (!isConnectionLevelFailure(failure)) return null
        val now = System.nanoTime()
        val previous = lastRecoveryAtNs.get()
        // 单调钟不能拿 Long.MIN_VALUE 直接做减法（会溢出成负数，首次永远进不来）
        if (previous != Long.MIN_VALUE && now - previous < RECOVERY_COOLDOWN_NS) return null
        if (!lastRecoveryAtNs.compareAndSet(previous, now)) return null

        for (candidate in lanCandidates(target)) {
            val health = candidate.newBuilder().encodedPath("/health").build()
            val reachable = runCatching {
                // 探测请求刻意不带任何凭据（独立 Request），且必须返回 PadGuard 自己的
                // health 结构：「HTTP 200」太容易被路由器管理页 / 同网段任意 HTTP 服务满足。
                probeClient.newCall(Request.Builder().url(health).get().build())
                    .execute().use { resp ->
                        resp.isSuccessful &&
                            resp.header("Content-Type")?.contains("application/json", true) == true &&
                            isPadGuardHealth(resp.body?.string().orEmpty())
                    }
            }.getOrDefault(false)
            if (!reachable) continue
            val base = candidate.toString()
            // 写回前再过一次 EndpointSafety（setBaseUrl 内部校验），公网/畸形地址一律不采纳。
            if (!serverPrefs.setBaseUrl(base)) continue
            return base
        }
        return null
    }

    private fun rewrite(original: HttpUrl, target: HttpUrl): HttpUrl {
        // 服务端返回的图片地址形如 "/v1/files/{id}"，它已经是**服务端根路径**，
        // 不带占位前缀（接口注解里的相对路径才带）。
        // 若继续按"拼到 target 基路径之后"处理，就会变成 /v1/v1/files/{id} → 404/500，
        // 表现为"截屏明明拍到了，家长端却永远加载不出来"。这种情形只能换 host、保留原路径。
        if (!original.encodedPath.startsWith(PLACEHOLDER_PREFIX)) {
            return original.newBuilder()
                .scheme(target.scheme)
                .host(target.host)
                .port(target.port)
                .encodedQuery(original.encodedQuery)
                .build()
        }

        // 剥掉占位前缀，只保留接口自身的相对路径
        val relative = original.encodedPath.removePrefix(PLACEHOLDER_PREFIX).trimStart('/')
        return target.newBuilder()
            .encodedPath(target.encodedPath.trimEnd('/') + "/" + relative)
            .encodedQuery(original.encodedQuery)
            .build()
    }

    companion object {
        /** 与 NetworkModule 里 Retrofit 的占位 baseUrl 路径保持一致 */
        const val PLACEHOLDER_PREFIX = "/placeholder"

        /** 自发现冷却 60s：服务端重启期间只扫一次，避免并发轮询各扫一遍造成连接风暴 */
        private const val RECOVERY_COOLDOWN_NS = 60_000_000_000L
    }
}

/**
 * 局域网候选地址（纯函数，可 JVM 单测）。
 *
 * 只在「已配置的地址本身是私网 IP」时生成同网段邻居：DHCP 重新分配、电脑换网口、
 * USB 直连都会让末位或网段变化。候选数有界（≤10），每个探测 700ms 超时，
 * 最坏额外 ~8s 且只在连接失败时发生一次，不做全网段扫描、不碰公网域名。
 * 模拟器固定回环别名 10.0.2.2 与 127.0.0.1 一并纳入（真机上这两个必然连不上，代价极小）。
 */
internal fun lanCandidates(target: HttpUrl): List<HttpUrl> {
    val hosts = linkedSetOf<String>()
    hosts += "10.0.2.2"
    hosts += "127.0.0.1"
    val parts = target.host.split('.')
    val octets = parts.map { it.toIntOrNull() }
    if (octets.size == 4 && octets.all { it != null && it in 0..255 } &&
        EndpointSafety.isLanHost(target.host)
    ) {
        val a = octets[0]!!
        val b = octets[1]!!
        val c = octets[2]!!
        val d = octets[3]!!
        for (last in intArrayOf(d - 2, d - 1, d + 1, d + 2, d + 3, 1, 11, 12, 100)) {
            if (last in 1..254) hosts += "$a.$b.$c.$last"
        }
    }
    hosts -= target.host
    // 沿用当前已规范化的 API 前缀：EndpointSafety 已把根路径补成 /v1/，
    // 但私有化部署可能用 /api/v1/ 等自定义前缀。旧版硬编码 /v1/ 会让自愈写回一个
    // 路径错误的地址，换 IP 成功后所有请求反而变成 404。
    val apiPath = target.encodedPath
    return hosts.take(10).mapNotNull { host ->
        runCatching {
            target.newBuilder()
                .scheme(if (target.scheme == "https") "https" else "http")
                .host(host)
                .encodedPath(apiPath)
                .build()
        }.getOrNull()
    }
}

/**
 * `/health` 响应必须是 **PadGuard 自己的**健康结构，而不只是「HTTP 200」。
 *
 * 自发现会把命中的主机写回 baseUrl，之后家长令牌、孩子截图与位置全部发往该地址。
 * 局域网里随便一台开了 8090 的设备（路由器管理页、开发机上的其它服务、刻意伪造的
 * 监听者）都能返回 200，只看状态码等于把凭据拱手送人。这里要求 JSON 同时含
 * `status:UP` / `ready` / `db` 三个字段，并配合调用处的 `Content-Type` 校验。
 *
 * 注意边界：明文 HTTP 局域网内无法做真正的服务端身份认证，本校验只挡住「无意误命中」，
 * 挡不住蓄意伪造。因此调用方**不重放带凭据的原请求**，并在提示里给出改回地址的入口。
 */
internal fun isPadGuardHealth(body: String): Boolean {
    if (body.isBlank() || !body.trimStart().startsWith("{")) return false
    val compact = body.replace(Regex("\\s"), "")
    return compact.contains("\"status\":\"UP\"") &&
        compact.contains("\"ready\"") &&
        compact.contains("\"db\"")
}

/**
 * 只有「地址层」失败才值得换 IP。TLS 握手失败的正解是把误升级的 https 改回 http，
 * 换一个 IP 只会掩盖问题；其它 IOException（如被安全策略拒绝）也不该触发自发现。
 */
internal fun isConnectionLevelFailure(e: IOException): Boolean {
    if (e is javax.net.ssl.SSLException) return false
    val msg = e.message.orEmpty()
    if (msg.contains("SSL", ignoreCase = true) || msg.contains("TLS", ignoreCase = true)) return false
    return e is UnknownHostException || e is ConnectException || e is SocketTimeoutException ||
        msg.contains("Failed to connect", ignoreCase = true) ||
        msg.contains("Connection refused", ignoreCase = true) ||
        msg.contains("Unable to resolve host", ignoreCase = true) ||
        msg.contains("timeout", ignoreCase = true) ||
        msg.contains("timed out", ignoreCase = true)
}

/**
 * 连接失败提示：**中文 + 当前真实地址 + 具体原因**。
 *
 * 抽成纯函数便于 JVM 单测（不依赖 Android / 真实网络）。OkHttp 原文是英文
 * （"Failed to connect to /192.168.1.10:8090"），历史上直接上屏就是英文；
 * 后来虽然统一翻成「连不上服务器」，但把最有用的地址丢掉了 —— 家长依旧不知道
 * 该把「高级设置」改成什么。这里在异常产生的同一层把地址补回来。
 *
 * 分类顺序有意为之：TLS 握手失败要先判，否则会被后面更宽泛的 "Failed to connect"
 * 吞掉，而它的正解恰恰相反（不是改 IP，是把误升级的 https 改回内网 http）。
 */
internal fun connectionFailureMessage(target: HttpUrl, e: IOException): String {
    val raw = e.message.orEmpty()
    val reason = when {
        e is javax.net.ssl.SSLException ||
            raw.contains("SSL", ignoreCase = true) || raw.contains("TLS", ignoreCase = true) ->
            "HTTPS/TLS 握手失败，服务端可能未启用 HTTPS；若在内网联调请把地址改回 http:// 的实际内网 IP"
        e is java.net.UnknownHostException || raw.contains("Unable to resolve host", ignoreCase = true) ->
            "服务器地址无法解析，域名或 IP 可能不存在、DNS 不通"
        e is java.net.SocketTimeoutException ||
            raw.contains("timeout", ignoreCase = true) || raw.contains("timed out", ignoreCase = true) ->
            "连接超时，服务端可能未启动、IP 不可达或被防火墙拦截"
        e is java.net.ConnectException ||
            raw.contains("Failed to connect", ignoreCase = true) ||
            raw.contains("Connection refused", ignoreCase = true) ->
            "连接被拒绝，该地址上没有服务在监听（服务端未启动、端口写错，或填成了别的机器）"
        else -> "网络不可达，请确认服务端已启动、地址与端口正确"
    }
    val address = "${target.scheme}://${target.host}:${target.port}${target.encodedPath}"
    return "连不上服务器 $address：$reason。可在登录页「⚙ 高级设置」修改地址，改完立即生效。"
}
