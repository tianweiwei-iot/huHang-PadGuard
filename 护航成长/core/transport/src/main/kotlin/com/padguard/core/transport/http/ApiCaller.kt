package com.padguard.core.transport.http

import com.padguard.core.common.Logger
import com.padguard.core.common.TimeProvider
import retrofit2.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HTTP 调用统一收口。
 *
 * 三件事在这里一次做完，避免每个接口重复写：
 * 1. **异常吞掉**：任何 IOException / 解析异常都转成 [ApiResult.Failure]，
 *    不允许抛到守护服务的协程里 —— 未捕获异常会让 supervisor 之外的 scope 整片取消，
 *    表现为"服务还在但什么都不干了"。
 * 2. **顺带对时**：每个响应都带 `serverTime`，用它持续校准时钟偏移，
 *    不必为对时单独发请求；这让防时间篡改检测的基准始终新鲜。
 * 3. **HTTP 码与业务码归一**：401 与 `code=40101` 都映射到 [ApiCode.TOKEN_INVALID]，
 *    上层只判一个常量。
 */
@Singleton
class ApiCaller @Inject constructor(
    private val timeProvider: TimeProvider
) {

    /** data 必须非空的接口 */
    suspend fun <T : Any> call(
        tag: String,
        block: suspend () -> Response<ApiEnvelope<T>>
    ): ApiResult<T> = when (val raw = invoke(tag, block)) {
        is ApiResult.Success -> {
            val value = raw.value.data
            if (value == null) {
                ApiResult.Failure(ApiCode.LOCAL_PARSE_ERROR, "$tag: response data is null")
            } else {
                ApiResult.Success(value, raw.value.serverTime)
            }
        }
        is ApiResult.BizError -> raw
        is ApiResult.Failure -> raw
    }

    /** data 可空（只关心成功与否）的接口 */
    suspend fun <T : Any> callIgnoringData(
        tag: String,
        block: suspend () -> Response<ApiEnvelope<T>>
    ): ApiResult<Unit> = when (val raw = invoke(tag, block)) {
        is ApiResult.Success -> ApiResult.Success(Unit, raw.value.serverTime)
        is ApiResult.BizError -> raw
        is ApiResult.Failure -> raw
    }

    /** 需要拿到整个 envelope（例如策略接口要读原始 JSON）的场景 */
    suspend fun <T : Any> callEnvelope(
        tag: String,
        block: suspend () -> Response<ApiEnvelope<T>>
    ): ApiResult<ApiEnvelope<T>> = invoke(tag, block)

    private suspend fun <T : Any> invoke(
        tag: String,
        block: suspend () -> Response<ApiEnvelope<T>>
    ): ApiResult<ApiEnvelope<T>> {
        val startedAt = System.currentTimeMillis()
        val response = try {
            block()
        } catch (e: IOException) {
            Logger.w(tag) { "network failure: ${e.message}" }
            return ApiResult.Failure(ApiCode.LOCAL_NETWORK_ERROR, e.message ?: "network error", e)
        } catch (e: Exception) {
            // 包含 SerializationException：服务端字段变更时不能让终端崩
            Logger.e(tag, e) { "unexpected failure" }
            return ApiResult.Failure(ApiCode.LOCAL_PARSE_ERROR, e.message ?: "parse error", e)
        }

        if (!response.isSuccessful) {
            val code = when (response.code()) {
                401 -> ApiCode.TOKEN_INVALID
                403 -> ApiCode.DEVICE_UNBOUND
                409 -> ApiCode.CLOCK_SKEW
                in 500..599 -> ApiCode.SERVER_ERROR
                else -> ApiCode.BAD_REQUEST
            }
            Logger.w(tag) { "http ${response.code()} -> biz code $code" }
            return ApiResult.BizError(code, "HTTP ${response.code()} ${response.message()}")
        }

        val envelope = response.body()
            ?: return ApiResult.Failure(ApiCode.LOCAL_PARSE_ERROR, "$tag: empty body")

        // 对时：折半补偿往返延迟（契约 §5.2）
        if (envelope.serverTime > 0) {
            timeProvider.syncServerTime(envelope.serverTime, System.currentTimeMillis() - startedAt)
        }

        if (!envelope.isSuccess) {
            Logger.w(tag) { "biz error ${envelope.code}: ${envelope.message}" }
            return ApiResult.BizError(envelope.code, envelope.message)
        }
        return ApiResult.Success(envelope, envelope.serverTime)
    }
}
