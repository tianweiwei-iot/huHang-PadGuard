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
    const val DEVICE_NOT_FOUND = 2001
    const val DEVICE_OFFLINE = 2002
    const val DEVICE_NOT_OWNED = 2003
    const val POLICY_NOT_FOUND = 3001
    const val POLICY_CONFLICT = 3002
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
    const val TIME_OUT_OF_SYNC = 40901
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
        // 鉴权失败必须落到 HTTP 401，不能只写在响应体里：
        // 客户端的自动续期是靠 OkHttp Authenticator 拦截 401 触发的，
        // 若这里回 200 + code=1002，续期逻辑永远不会执行，
        // 结果就是 accessToken 过期后全端接口一直报「token 不合法」，只能重新登录。
        val status = if (e.code == ParentErr.UNAUTHORIZED) HttpStatus.UNAUTHORIZED else HttpStatus.OK
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
