package com.padguard.core.transport.http

import com.padguard.core.common.Logger
import com.padguard.core.common.TimeProvider
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * [ApiCaller] 行为测试（本地 JVM，不依赖 Android）。
 *
 * 关键点：
 * - [Logger] 在 JVM 测试里会调到 android.util.Log 的 stub 抛异常，故 @Before 关闭 [Logger.enabled]。
 * - retrofit2.Response 可在纯 JVM 构造，无需 Robolectric。
 * - 用真实 [TimeProvider] 即可验证「顺带对时」逻辑。
 */
class ApiCallerTest {

    private var originalLoggerEnabled = true

    @Before
    fun setUp() {
        originalLoggerEnabled = Logger.enabled
        Logger.enabled = false
    }

    @After
    fun tearDown() {
        Logger.enabled = originalLoggerEnabled
    }

    private fun errorBody(): ResponseBody {
        val mediaType = "text/plain".toMediaType()
        return "{}".toResponseBody(mediaType)
    }

    @Test
    fun `successful response with data returns Success carrying serverTime`() = runBlocking {
        val caller = ApiCaller(TimeProvider())
        val env = ApiEnvelope(code = ApiCode.OK, data = "hello", serverTime = 1_700_000_000_000L)
        val result = caller.call("t") { Response.success(env) }
        assertTrue(result is ApiResult.Success)
        assertEquals("hello", (result as ApiResult.Success).value)
        assertEquals(1_700_000_000_000L, result.serverTime)
    }

    @Test
    fun `successful response syncs server time via TimeProvider`() = runBlocking {
        val tp = TimeProvider()
        val caller = ApiCaller(tp)
        val serverTime = 1_700_000_000_000L
        caller.call("t") { Response.success(ApiEnvelope(code = ApiCode.OK, data = "x", serverTime = serverTime)) }
        assertTrue("now() 应约等于 serverTime", kotlin.math.abs(tp.now() - serverTime) < 5000)
    }

    @Test
    fun `null data on success code maps to LOCAL_PARSE_ERROR`() = runBlocking {
        val caller = ApiCaller(TimeProvider())
        val result = caller.call("t") { Response.success(ApiEnvelope(code = ApiCode.OK, data = null, serverTime = 1)) }
        assertTrue(result is ApiResult.Failure)
        assertEquals(ApiCode.LOCAL_PARSE_ERROR, (result as ApiResult.Failure).code)
    }

    @Test
    fun `envelope carrying non-zero biz code maps to BizError`() = runBlocking {
        val caller = ApiCaller(TimeProvider())
        val result = caller.call("t") {
            Response.success(ApiEnvelope(code = ApiCode.DEVICE_UNBOUND, message = "unbound", data = null))
        }
        assertTrue(result is ApiResult.BizError)
        assertEquals(ApiCode.DEVICE_UNBOUND, (result as ApiResult.BizError).code)
    }

    @Test
    fun `http 401 maps to TOKEN_INVALID`() = runBlocking {
        val result = httpError(401)
        assertTrue(result is ApiResult.BizError)
        assertEquals(ApiCode.TOKEN_INVALID, (result as ApiResult.BizError).code)
    }

    @Test
    fun `http 403 maps to DEVICE_UNBOUND`() = runBlocking {
        val result = httpError(403)
        assertTrue(result is ApiResult.BizError)
        assertEquals(ApiCode.DEVICE_UNBOUND, (result as ApiResult.BizError).code)
    }

    @Test
    fun `http 409 maps to CLOCK_SKEW`() = runBlocking {
        val result = httpError(409)
        assertTrue(result is ApiResult.BizError)
        assertEquals(ApiCode.CLOCK_SKEW, (result as ApiResult.BizError).code)
    }

    @Test
    fun `http 500 maps to SERVER_ERROR`() = runBlocking {
        val result = httpError(500)
        assertTrue(result is ApiResult.BizError)
        assertEquals(ApiCode.SERVER_ERROR, (result as ApiResult.BizError).code)
    }

    @Test
    fun `http 418 maps to BAD_REQUEST`() = runBlocking {
        val result = httpError(418)
        assertTrue(result is ApiResult.BizError)
        assertEquals(ApiCode.BAD_REQUEST, (result as ApiResult.BizError).code)
    }

    @Test
    fun `IOException maps to LOCAL_NETWORK_ERROR`() = runBlocking {
        val caller = ApiCaller(TimeProvider())
        val result = caller.call<String>("t") { throw java.io.IOException("boom") }
        assertTrue(result is ApiResult.Failure)
        assertEquals(ApiCode.LOCAL_NETWORK_ERROR, (result as ApiResult.Failure).code)
    }

    @Test
    fun `unexpected Exception maps to LOCAL_PARSE_ERROR`() = runBlocking {
        val caller = ApiCaller(TimeProvider())
        val result = caller.call<String>("t") { throw RuntimeException("weird") }
        assertTrue(result is ApiResult.Failure)
        assertEquals(ApiCode.LOCAL_PARSE_ERROR, (result as ApiResult.Failure).code)
    }

    private suspend fun httpError(code: Int): ApiResult<*> {
        val caller = ApiCaller(TimeProvider())
        return caller.call("t") { Response.error<ApiEnvelope<String>>(code, errorBody()) }
    }
}
