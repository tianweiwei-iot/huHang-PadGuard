package com.padguard.server.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import jakarta.servlet.DispatcherType
import jakarta.servlet.FilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/**
 * 「TraceIdFilter 必须参与 ASYNC 二次派发」的**行为级**验证。
 *
 * 历史背景：该契约原本只由源码文本断言锁死（扫 `shouldNotFilterAsyncDispatch() = false`
 * 这行代码在不在）。文本断言只证明"这行代码在"，不证明"行为正确"。
 * 本测试把契约落在过滤器真实执行上：
 *
 * 1. 首次 REQUEST 派发：过滤器生成 ID → attribute + 响应头 + MDC；
 * 2. 以 ASYNC 派发类型再次过过滤器（模拟容器对 DeferredResult 的二次派发）；
 * 3. 断言二次派发**重新进入了过滤器**（没有 OncePerRequestFilter 默认的跳过行为）、
 *    **复用同一个 ID**（不重新生成）、**MDC 重新就位**（真正写响应那一程的日志可归属）。
 */
class MvcAsyncDispatchTraceBehaviorTest {

    private val filter = TraceIdFilter()

    @Test
    fun `async dispatch re-enters the filter and reuses the same trace id`() {
        val request = MockHttpServletRequest("GET", "/deferred")
        val response = MockHttpServletResponse()

        // ---- 首次 REQUEST 派发 ----
        var firstChainMdc: String? = null
        filter.doFilter(request, response, FilterChain { _, _ -> firstChainMdc = MDC.get(TraceIds.MDC_KEY) })
        val firstId = request.getAttribute("com.padguard.server.config.TraceIdFilter.TRACE_ID")
        assertNotNull(firstId, "首次派发必须生成 traceId attribute")
        assertNotNull(firstChainMdc, "首次派发处理期间 MDC 必须有 traceId")
        assertEquals(firstId, response.getHeader(TraceIds.HEADER), "响应头必须带同一个 ID")

        // ---- ASYNC 二次派发（DeferredResult 完成后容器会这样再走一遍过滤器链）----
        request.setDispatcherType(DispatcherType.ASYNC)
        var asyncChainMdc: String? = null
        filter.doFilter(request, response, FilterChain { _, _ -> asyncChainMdc = MDC.get(TraceIds.MDC_KEY) })

        assertEquals(firstId, request.getAttribute("com.padguard.server.config.TraceIdFilter.TRACE_ID"),
            "二次派发必须复用首次派发的同一个 ID，而不是另生成一个（否则两半日志对不上）")
        assertEquals(firstId, asyncChainMdc, "ASYNC 派发处理期间 MDC 必须重新就位（过滤器参与二次派发）")
        assertEquals(firstId, response.getHeader(TraceIds.HEADER), "ASYNC 派发写出的响应头仍是同一个 ID")

        // 派发结束后容器线程复用，MDC 不得残留
        assertTrue(MDC.get(TraceIds.MDC_KEY) == null, "过滤器 finally 必须清 MDC，防串线")
    }

    @Test
    fun `filter explicitly opts into async dispatch`() {
        // 行为前提：OncePerRequestFilter 默认跳过 ASYNC 派发；覆写必须显式为 false。
        // shouldNotFilterAsyncDispatch 是 protected —— 用匿名子类暴露，断言的不是"代码在不在"，
        // 而是覆写后的实际返回值。
        val exposed = object : TraceIdFilter() {
            fun optIn(): Boolean = super.shouldNotFilterAsyncDispatch()
        }
        assertFalse(exposed.optIn(),
            "OncePerRequestFilter 默认跳过 ASYNC 二次派发；必须显式参与，否则写响应阶段的日志没有 traceId")
    }
}
