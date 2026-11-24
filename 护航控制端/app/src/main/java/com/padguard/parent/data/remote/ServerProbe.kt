package com.padguard.parent.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 「测试连接」：在保存服务器地址之前，先确认这个地址上到底有没有护航服务端。
 *
 * ## 为什么需要它
 *
 * 家长端历史上最反复的一类故障是「注册/登录提示连接服务器失败」，而真正原因几乎
 * 都是地址填错（示例 IP `192.168.1.10`、旧公网 IP、写成 8080、模拟器填 127.0.0.1）。
 * 用户只能靠"点登录→看提示→猜哪里错"来试错，一次要等好几秒，而且拿不到具体方向。
 *
 * 有了探测，用户在同一个表单里点一下就能得到结论：
 *   - 「连接正常」→ 地址没问题，登录失败就是账号/口令问题，别再改地址；
 *   - 「连接被拒绝」→ 服务端没启动或端口写错；
 *   - 「HTTPS 握手失败」→ 内网服务端只开 HTTP，协议选错了；
 *   - 「能连通但不是护航服务端」→ 端口指向了别的系统（例如本机 8080 上的其他项目）。
 *
 * ## 设计
 *
 * 不用 OkHttp / Retrofit：探测要能打到「保存前的任意地址」，不能受记忆化 baseUrl
 * 与 [HostSelectionInterceptor] 的自动纠偏影响（否则地址明明错了也会被悄悄改写，
 * 用户看到的"正常"是假象）。这里直接用 [HttpURLConnection]，只探一个 `/health`。
 *
 * 解析与文案全部抽成纯函数，JVM 单测可直接覆盖，不需要真机或网络。
 */
object ServerProbe {

    /**
     * 判断一条失败提示是否属于「连不上服务器」这一类。
     *
     * 登录页据此决定是否自动展开服务器地址表单。之所以按文案判定而不是按异常类型：
     * 到 UI 这一层异常已经被 `readableUserFacingError` 洗成了字符串，原始类型已丢失；
     * 而这条链路上的文案就是本类与 [HostSelectionInterceptor] 产出的，集合是封闭的。
     *
     * 关键词必须与上面 [describeFailure] / [describeHttp] 的产出保持一致 ——
     * 新增一类连接失败文案时，这里要同步补词，否则自动展开会失灵。
     */
    fun looksLikeConnectionFailure(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        return CONNECTION_FAILURE_HINTS.any { message.contains(it) }
    }

    private val CONNECTION_FAILURE_HINTS = listOf(
        "连不上服务器",
        "无法连接服务器",
        "地址无法解析",
        "连接被拒绝",
        "连接超时",
        "握手失败",
        "没有服务在监听",
        // 明文被系统拦截时，解法也在地址表单里（把协议改对），所以要一并展开
        "明文 HTTP"
    )

    /** 一次探测的结论。[ok] 表示"确实是护航服务端且状态正常"。 */
    data class Result(val ok: Boolean, val message: String)

    private const val TIMEOUT_MS = 4000

    /** 健康接口挂在根路径，不在 `/v1` 前缀下（服务端 HealthController 的 `@GetMapping("/health")`）。 */
    private const val HEALTH_PATH = "/health"

    suspend fun check(rawBaseUrl: String): Result = withContext(Dispatchers.IO) {
        val origin = originOf(rawBaseUrl)
            ?: return@withContext Result(
                false,
                "地址格式不对：需要以 http:// 或 https:// 开头，形如 http://192.168.1.11:8090/v1/"
            )
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(origin + HEALTH_PATH).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = runCatching {
                stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }.getOrDefault("")
            describeHttp(code, body, origin)
        } catch (e: Exception) {
            describeFailure(e, origin)
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * 从完整 baseUrl 取 `scheme://host[:port]`。
     *
     * 健康接口在根路径，而 baseUrl 通常以 `/v1/` 结尾，直接拼接会得到
     * `/v1/health`（服务端没有这个路由，返回 404，会被误判成"地址错误"）。
     */
    fun originOf(baseUrl: String): String? {
        val trimmed = baseUrl.trim()
        val scheme = when {
            trimmed.startsWith("http://", ignoreCase = true) -> "http"
            trimmed.startsWith("https://", ignoreCase = true) -> "https"
            else -> return null
        }
        val rest = trimmed.substring(scheme.length + 3)
        val authority = rest.substringBefore('/').substringAfterLast('@')
        if (authority.isBlank()) return null
        // 端口必须显式可解析，避免把 "http://192.168.1.11:abc" 当成合法地址去探测
        val port = authority.substringAfterLast(':', "")
        if (authority.contains(':') && port.toIntOrNull()?.let { it in 1..65535 } != true) return null
        return "$scheme://$authority"
    }

    /** 把 HTTP 状态码 + 响应体翻译成"下一步该改什么"。 */
    fun describeHttp(code: Int, body: String, origin: String): Result = when {
        code in 200..299 -> {
            // 去掉空白再匹配：Jackson 默认紧凑输出，但前置代理 / 调试网关可能美化打印，
            // 不要因为 `"status": "UP"` 多了一个空格就把健康响应判成"不是护航服务端"。
            val compact = body.replace(" ", "").replace("\n", "").replace("\r", "").replace("\t", "")
            val up = compact.contains("\"status\":\"UP\"", ignoreCase = true)
            val dbDown = compact.contains("\"db\":\"DOWN\"", ignoreCase = true)
            when {
                up && dbDown -> Result(
                    true,
                    "$origin 已连上护航服务端，但它报告数据库异常（db DOWN），登录/注册会失败。请查看服务端日志。"
                )
                up -> Result(true, "$origin 已连上护航服务端，状态正常，可以保存并登录。")
                // 200 但不是健康接口的 JSON：多半端口指向了别的 HTTP 服务
                else -> Result(
                    false,
                    "$origin 能打开，但返回的不是护航服务端的健康接口：端口可能指向了别的系统，请核对端口与接口前缀。"
                )
            }
        }
        code == 404 -> Result(
            false,
            "$origin 连得上，但没有 /health 接口（HTTP 404）：多半是端口填成了别的服务，或接口前缀多写了 /v1。"
        )
        code == 401 || code == 403 -> Result(
            false,
            "$origin 有服务在响应，但拒绝访问（HTTP $code）：这个地址不是本工程的护航服务端。"
        )
        code in 500..599 -> Result(
            false,
            "$origin 上的服务端自身报错（HTTP $code），不是地址问题。请查看服务端日志后重试。"
        )
        else -> Result(false, "$origin 返回 HTTP $code，不像护航服务端，请核对地址与端口。")
    }

    /** 把网络异常翻译成具体原因。文案要求：说清"哪里错了 + 怎么改"。 */
    fun describeFailure(e: Throwable, origin: String): Result = when (e) {
        is UnknownHostException -> Result(
            false,
            "地址无法解析：$origin 的主机名不存在。请填 IP 地址（如 192.168.1.11），不要填电脑名。"
        )
        is ConnectException -> Result(
            false,
            "连接被拒绝：$origin 上没有服务在监听。请确认服务端已启动，并核对端口（本工程默认 8090，不是 8080）。"
        )
        is SocketTimeoutException -> Result(
            false,
            "连接超时：$origin 一直没有响应。请确认手机和服务器连的是同一个 WiFi，且服务器防火墙没有拦截该端口。"
        )
        is SSLException -> Result(
            false,
            "HTTPS 握手失败：$origin 大概只开了 HTTP。请把协议改成 HTTP；若是自签证书，浏览器与 App 都会拒绝。"
        )
        is IOException -> {
            val raw = e.message.orEmpty()
            if (raw.contains("Cleartext", ignoreCase = true)) {
                Result(false, "系统拦截了明文 HTTP 请求：请改用 https，或改用内网地址（内网 http 允许）。")
            } else {
                Result(false, "连不上 $origin：${raw.ifBlank { "未知网络错误" }}")
            }
        }
        else -> Result(false, "连不上 $origin：${e.message ?: e::class.java.simpleName}")
    }
}
