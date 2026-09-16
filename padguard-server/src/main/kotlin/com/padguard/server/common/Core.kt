package com.padguard.server.common

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

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

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(BizException::class)
    fun handleBiz(e: BizException): ResponseEntity<*> =
        if (e.audience == Audience.CHILD) {
            ResponseEntity.ok(DeviceApiResponse<Any>(e.code, e.message))
        } else {
            ResponseEntity.ok(ApiResponse<Any>(e.code, e.message))
        }

    @ExceptionHandler(Exception::class)
    fun handleOther(e: Exception): ResponseEntity<*> {
        e.printStackTrace()
        return ResponseEntity.status(500)
            .body(ApiResponse<Any>(ParentErr.SERVER_ERROR, e.message ?: "server error"))
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
