package com.padguard.core.transport.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ApiResult] / [ApiEnvelope] 纯逻辑的单元测试（不依赖 Android，跑在本地 JVM）。
 */
class ApiResultTest {

    @Test
    fun `Success reports success and exposes value`() {
        val s = ApiResult.Success("v", 100L)
        assertTrue(s.isSuccess)
        assertEquals("v", s.valueOrNull())
        assertFalse(s.isFatal())
    }

    @Test
    fun `BizError with terminal code is fatal`() {
        assertTrue(ApiResult.BizError(ApiCode.TOKEN_INVALID, "x").isFatal())
        assertTrue(ApiResult.BizError(ApiCode.DEVICE_UNBOUND, "x").isFatal())
        assertTrue(ApiResult.BizError(ApiCode.BIND_CODE_EXPIRED, "x").isFatal())
        assertTrue(ApiResult.BizError(ApiCode.BIND_CONFLICT, "x").isFatal())
    }

    @Test
    fun `BizError with retryable code is not fatal`() {
        assertFalse(ApiResult.BizError(ApiCode.CLOCK_SKEW, "x").isFatal())
        assertFalse(ApiResult.BizError(ApiCode.SERVER_ERROR, "x").isFatal())
        assertFalse(ApiResult.BizError(ApiCode.BAD_REQUEST, "x").isFatal())
    }

    @Test
    fun `Failure and Success are never fatal`() {
        assertFalse(ApiResult.Failure(ApiCode.LOCAL_NETWORK_ERROR, "x").isFatal())
        assertFalse(ApiResult.Success("v", 1L).isFatal())
    }

    @Test
    fun `valueOrNull returns null for non-Success`() {
        assertNull(ApiResult.BizError(ApiCode.TOKEN_INVALID, "x").valueOrNull())
        assertNull(ApiResult.Failure(ApiCode.LOCAL_NETWORK_ERROR, "x").valueOrNull())
    }

    @Test
    fun `ApiEnvelope isSuccess is driven by code`() {
        assertTrue(ApiEnvelope(code = ApiCode.OK, data = "x").isSuccess)
        assertFalse(ApiEnvelope(code = ApiCode.TOKEN_INVALID, data = null).isSuccess)
    }
}
