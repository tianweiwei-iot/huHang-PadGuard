package com.padguard.core.transport.http

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 服务端统一响应包装（契约 §5）。
 *
 * `{ "code": 0, "message": "ok", "data": { }, "serverTime": 1785000000000 }`
 *
 * [serverTime] 每个响应都带，终端顺手用它持续校准时钟偏移，
 * 无需为对时单独发请求 —— 这是防时间篡改的低成本加固点。
 */
@Serializable
data class ApiEnvelope<T>(
    @SerialName("code") val code: Int = ApiCode.OK,
    @SerialName("message") val message: String = "",
    @SerialName("data") val data: T? = null,
    @SerialName("serverTime") val serverTime: Long = 0L
) {
    val isSuccess: Boolean get() = code == ApiCode.OK
}

/** 契约 §5 定义的业务错误码 */
object ApiCode {
    const val OK = 0

    const val BAD_REQUEST = 40001

    /** 令牌失效：需重新绑定或走 refresh（服务端待确认，见契约 §9.3） */
    const val TOKEN_INVALID = 40101

    /** 设备已被管理员解绑：终端应清空本地凭据并回到未绑定态 */
    const val DEVICE_UNBOUND = 40301

    /** 绑定码已失效 */
    const val BIND_CODE_EXPIRED = 40302

    /** 设备已绑定其他账号 */
    const val BIND_CONFLICT = 40303

    /** 与服务端时间差超过 5 分钟：终端应立即对时后重试 */
    const val CLOCK_SKEW = 40901

    const val SERVER_ERROR = 50000

    /** 本地构造的错误码：网络不可达、超时、解析失败等 */
    const val LOCAL_NETWORK_ERROR = -1
    const val LOCAL_PARSE_ERROR = -2
}

/**
 * 传输层统一结果类型。
 *
 * 刻意不让 HTTP 异常穿透到上层：被管控端必须在弱网、断网、服务端 5xx 下
 * 继续按本地策略执行管控，任何一处未捕获异常导致守护服务崩溃，
 * 都等于给了"断网 + 崩服务"这条绕过路径。
 */
sealed interface ApiResult<out T> {

    data class Success<T>(val value: T, val serverTime: Long) : ApiResult<T>

    /** 服务端明确返回的业务失败 */
    data class BizError(val code: Int, val message: String) : ApiResult<Nothing>

    /** 网络/解析层失败，可重试 */
    data class Failure(val code: Int, val message: String, val cause: Throwable? = null) : ApiResult<Nothing>

    val isSuccess: Boolean get() = this is Success

    fun valueOrNull(): T? = (this as? Success)?.value

    /** 是否属于"重试也没意义"的终态错误 */
    fun isFatal(): Boolean = this is BizError && code in FATAL_CODES

    companion object {
        private val FATAL_CODES = setOf(
            ApiCode.TOKEN_INVALID,
            ApiCode.DEVICE_UNBOUND,
            ApiCode.BIND_CODE_EXPIRED,
            ApiCode.BIND_CONFLICT
        )
    }
}
