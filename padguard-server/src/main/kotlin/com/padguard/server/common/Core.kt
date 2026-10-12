package com.padguard.server.common

import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MaxUploadSizeExceededException

/** 调用方：决定用哪套响应包装 / 错误码 */
enum class Audience { PARENT, CHILD }

/** 控制端（家长）错误码：1xxx/2xxx/3xxx */
object ParentErr {
    const val SUCCESS = 0
    const val PARAM_ERROR = 1001
    const val UNAUTHORIZED = 1002
    const val FORBIDDEN = 1003
    /**
     * 账号已被后台冻结（suspended）。
     *
     * 独立错误码而非复用 UNAUTHORIZED：客户端要能据此给出"账号已被冻结，请联系客服"
     * 的明确提示，而不是笼统的"登录已过期，请重新登录"——后者会让家长反复重试登录，
     * 永远得不到可执行的下一步。
     */
    const val ACCOUNT_SUSPENDED = 1004
    /** 账号待激活（pending）：已建档但未开通，同样不允许登录 */
    const val ACCOUNT_PENDING = 1005
    const val DEVICE_NOT_FOUND = 2001
    const val DEVICE_OFFLINE = 2002
    const val DEVICE_NOT_OWNED = 2003
    /** 同一设备的并发观看数已达上限（每路观看都是一份完整的上行+下行带宽） */
    const val LIVE_VIEWERS_FULL = 2004
    /** 服务端同时推流的设备数已达上限（并发带宽保护，见 MonitorService.startLiveView） */
    const val LIVE_DEVICES_FULL = 2006
    const val POLICY_NOT_FOUND = 3001
    const val POLICY_CONFLICT = 3002
    /** 围栏不存在（或不属于当前设备，按"不存在"处理以避免泄漏归属） */
    const val GEOFENCE_NOT_FOUND = 3003
    // 订阅：4xxx
    /** 未订阅 / 订阅已过期，功能不可用 */
    const val SUBSCRIPTION_REQUIRED = 4001
    /** 订阅有效但设备数已达上限，不能再绑定 */
    const val DEVICE_LIMIT_EXCEEDED = 4002
    const val ORDER_NOT_FOUND = 4003
    const val ORDER_STATE_INVALID = 4004
    /**
     * S14 限流：触发频控（登录 / 绑定等爆破面）。
     * 注意不能用 1004 —— 该值已被 ACCOUNT_SUSPENDED（账号冻结）占用，
     * 同值会让异常映射 when 分支互相遮蔽（限流 429 与冻结 401 必坏一个）。
     */
    const val TOO_MANY_REQUESTS = 1006
    const val SERVER_ERROR = 5001
}

/** 被管控端（孩子）错误码：40xxx */
object ChildErr {
    const val SUCCESS = 0
    const val PARAM_ERROR = 40001
    const val TOKEN_INVALID = 40101
    const val DEVICE_UNBOUND = 40301
    const val BIND_CODE_EXPIRED = 40302
    const val DEVICE_ALREADY_BOUND = 40303
    /** 家长账号未订阅或已过期 —— 孩子端必须给出这条明确提示，而不是笼统的"绑定失败" */
    const val SUBSCRIPTION_REQUIRED = 40304
    /** 可绑定设备数已达套餐上限 */
    const val DEVICE_LIMIT_EXCEEDED = 40305
    /** 家长账号已被冻结：孩子端不得继续绑定/换绑，需提示"家长账号已被冻结" */
    const val PARENT_ACCOUNT_SUSPENDED = 40306
    const val TIME_OUT_OF_SYNC = 40901
    /** S14 限流：绑定等匿名入口触发频控 */
    const val TOO_MANY_REQUESTS = 40902
    const val SERVER_ERROR = 50000
}

/** 业务异常，携带错误码与调用方 */
class BizException(
    val code: Int,
    override val message: String,
    val audience: Audience = Audience.PARENT
) : RuntimeException(message)

/** 控制端（家长）统一响应包装：{ code, message, data, timestamp } */
data class ApiResponse<T>(
    val code: Int = 0,
    val message: String = "success",
    val data: T? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        fun <T> ok(data: T?) = ApiResponse(data = data)
        fun <T> fail(code: Int, message: String) = ApiResponse<T>(code, message)
    }
}

/** 被管控端（孩子）统一响应包装：{ code, message, data, serverTime } */
data class DeviceApiResponse<T>(
    val code: Int = 0,
    val message: String = "success",
    val data: T? = null,
    val serverTime: Long = System.currentTimeMillis()
) {
    companion object {
        fun <T> ok(data: T?) = DeviceApiResponse(data = data)
        fun <T> fail(code: Int, message: String) = DeviceApiResponse<T>(code, message)
    }
}

/**
 * 全局异常兜底。
 *
 * ## 为什么要在写出响应前 `reset()`
 * 这是一个真实踩过的坑：Controller 在成功路径上预设了 Content-Type
 * （例如下载 APK 时设为 `application/vnd.android.package-archive`），
 * 随后在处理过程中抛出异常。此时若直接返回 JSON 错误体，Spring 会发现
 * "没有转换器能用 APK 这个 Content-Type 写 ApiResponse"，抛
 * `HttpMessageNotWritableException` —— 于是**异常处理器自己失败了**，
 * 客户端收到连接重置，而真正导致失败的那个原始异常在日志里彻底消失。
 * 排查时只会看到一句 converter 报错，极易误判成网络问题。
 *
 * 因此在写错误响应之前先清掉响应上已被设置的头/Content-Type，
 * 保证错误永远能以 JSON 的形式送达。
 *
 * ## 为什么区分"客户端断连"
 * 用户在管控端取消一次大文件下载，Tomcat 就会抛
 * `IOException: Connection reset by peer` / `AsyncRequestNotUsableException`。
 * 把它记成 ERROR 会让日志被这类正常中断淹没，真正需要关注的故障反而看不见。
 * 这里降级为 DEBUG 并直接放行（返回 null 表示不处理），不做任何响应改写。
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(BizException::class)
    fun handleBiz(e: BizException, response: HttpServletResponse): ResponseEntity<*>? {
        if (!resetIfPossible(response)) return null
        // 鉴权失败必须落到对应的 HTTP 状态码，不能只写在响应体里：
        // - 家长端：OkHttp Authenticator 只在 401 上触发自动续期；
        // - 被管控端：HTTP 401/403 会被映射为 TOKEN_INVALID / DEVICE_UNBOUND 终态，
        //   终端据此清空本地凭据回到绑定页。若这里回 200，终端会永远停在
        //   "已绑定"的假象里（例如服务端换库后设备记录已不存在）。
        val status = when {
            e.audience == Audience.PARENT && e.code == ParentErr.UNAUTHORIZED -> HttpStatus.UNAUTHORIZED
            // 冻结/待激活必须落到 401 而不是 403：
            // 家长端 OkHttp Authenticator 只在 401 上触发（刷新令牌→失败→清空本地会话→
            // SessionViewModel 响应式退回登录页）。回 403 的话 Authenticator 根本不介入，
            // 客户端会停在"令牌还在、界面还在主页、但每个接口都报错"的假登录态里。
            e.audience == Audience.PARENT &&
                (e.code == ParentErr.ACCOUNT_SUSPENDED || e.code == ParentErr.ACCOUNT_PENDING) ->
                HttpStatus.UNAUTHORIZED
            // S14 限流：必须是 429，客户端与网关才能识别"稍后重试"语义，重试退避才不会变成立即重打
            e.code == ParentErr.TOO_MANY_REQUESTS || e.code == ChildErr.TOO_MANY_REQUESTS ->
                HttpStatus.TOO_MANY_REQUESTS
            // 越权（含后台 RBAC 闸门拒绝、家长端"资源不属于当前用户"）必须落到 403：
            // HTTP 状态码是网关 / 前端拦截器判断成败的首要依据，回 200 会让越权操作被误判为"成功"，
            // 管理端 http() 在 !res.ok 时直接抛错、家长端 ApiDataSource 在 !isSuccessful 时走错误分支——
            // 两者都不会静默放行，但只有正确的 403 才能让"被拒绝"这一语义在链路各层一致。
            e.audience == Audience.PARENT && e.code == ParentErr.FORBIDDEN -> HttpStatus.FORBIDDEN
            e.audience == Audience.CHILD && e.code == ChildErr.TOKEN_INVALID -> HttpStatus.UNAUTHORIZED
            e.audience == Audience.CHILD && e.code == ChildErr.DEVICE_UNBOUND -> HttpStatus.FORBIDDEN
            // 家长账号被冻结：孩子端是"已绑定设备想再绑/换绑"的场景，属终态拒绝（403），
            // 不能回 401 —— 401 会让孩子端清空凭据回到绑定页，把已绑定设备也一并踢掉。
            e.audience == Audience.CHILD && e.code == ChildErr.PARENT_ACCOUNT_SUSPENDED -> HttpStatus.FORBIDDEN
            // 参数错误 / 业务校验失败落到 400，不能回 200 只把错误码塞进响应体：
            // 管理端 api.js 的 http() 以 `res.ok`（即 HTTP 2xx）判定成败，
            // 回 200 时它会把 { code:1001, data:null } 当成成功返回，
            // 于是前端弹出"保存成功"，而事实上服务端拒绝写入 —— 用户改了半天权限，什么都没变。
            // 典型场景：RBAC 保存时"有写无读"的前置校验被拦，前台却提示保存成功。
            e.code == ParentErr.PARAM_ERROR -> HttpStatus.BAD_REQUEST
            else -> HttpStatus.OK
        }
        return if (e.audience == Audience.CHILD) {
            ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(DeviceApiResponse<Any>(e.code, e.message))
        } else {
            ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse<Any>(e.code, e.message))
        }
    }

    /**
     * 上传体积超限：给明确文案而不是笼统的 500。
     * 家长分发 APK 时若超过 `spring.servlet.multipart.max-file-size`，
     * 返回 413 + "文件过大"才能让管控端给出可操作的提示，
     * 否则用户只会看到"操作失败"，反复重试。
     */
    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleUploadTooLarge(
        e: MaxUploadSizeExceededException,
        response: HttpServletResponse
    ): ResponseEntity<*>? {
        if (!resetIfPossible(response)) return null
        log.warn("upload rejected: {}", e.message)
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
            .contentType(MediaType.APPLICATION_JSON)
            .body(ApiResponse<Any>(ParentErr.PARAM_ERROR, "上传文件过大，请调整服务端 multipart 上限后重试"))
    }

    @ExceptionHandler(Exception::class)
    fun handleOther(e: Exception, response: HttpServletResponse): ResponseEntity<*>? {
        // 客户端主动断开：正常业务中断，不记账、不改写响应
        if (isClientAbort(e)) {
            log.debug("client aborted the request: {}", e.message)
            return null
        }
        if (!resetIfPossible(response)) {
            // 响应已提交，改不动了；此时必须把原始异常记下来，
            // 否则就会重演"只看到 converter 报错、看不到真凶"的排查困境。
            log.error("unhandled exception (response already committed)", e)
            return null
        }
        log.error("unhandled exception", e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .contentType(MediaType.APPLICATION_JSON)
            .body(ApiResponse<Any>(ParentErr.SERVER_ERROR, e.message ?: "server error"))
    }

    /**
     * 清掉响应上已设置的状态与头，为 JSON 错误体腾出干净的写出环境。
     * @return 是否可以继续写错误响应；false 表示响应已提交（客户端已收到部分数据），不能再改。
     */
    private fun resetIfPossible(response: HttpServletResponse): Boolean {
        if (response.isCommitted) return false
        return runCatching { response.reset() }.isSuccess
    }

    private fun isClientAbort(e: Throwable): Boolean {
        var cursor: Throwable? = e
        while (cursor != null) {
            val name = cursor::class.java.name
            if (name.contains("ClientAbort") ||
                name.contains("AsyncRequestNotUsable") ||
                name.contains("BrokenPipe") ||
                (cursor is java.io.IOException && cursor.message?.contains("reset by peer") == true)
            ) return true
            cursor = cursor.cause
        }
        return false
    }
}

/** SHA-256 十六进制 */
object HashUtil {
    fun sha256(input: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

/**
 * 客户端真实 IP 的取法（全局唯一定义）。
 *
 * 家庭部署常见前置 Nginx / 端口转发，只认 `RemoteAddr` 会一律拿到 127.0.0.1——
 * 局域网发现会因此永远发现不了任何东西，且这种错很难从现象反推。
 * 统一在这里处理转发头，任何需要"对方网络位置"的地方都调它，避免每个调用点各写各的、
 * 出现一处改了另一处没改的漂移。
 */
object ClientIps {
    fun of(request: jakarta.servlet.http.HttpServletRequest): String? {
        val xff = request.getHeader("X-Forwarded-For")
            ?.split(',')
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        return (xff ?: request.remoteAddr)?.trim()?.takeIf { it.isNotBlank() }
    }
}
