package com.padguard.server.config

import jakarta.servlet.DispatcherType
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 第 6 批护栏：MVC **异步二次派发**的 traceId 贯穿。
 *
 * 背景：`TraceIdFilter` 是 `OncePerRequestFilter`。Spring MVC 的 `DeferredResult` /
 * `StreamingResponseBody` 会先返回「未完成」释放容器线程，真正写响应时容器用
 * `DispatcherType.ASYNC` 把**同一个 request 对象**再派发一次。默认
 * `shouldNotFilterAsyncDispatch()` 返回 true，第二次派发直接跳过本 Filter；
 * 而第一次派发的 finally 已经 `MDC.remove`，于是响应阶段的所有日志都没有 ID。
 *
 * 这里不 mock 掉 Filter，而是直接调真实的 `OncePerRequestFilter.doFilter` 两次，
 * 让框架自己的「是否已经过滤过」判定参与进来 —— 只测 `doFilterInternal` 是测不出
 * 这个坑的。
 */
@DisplayName("MVC 异步派发 traceId —— 二次派发复用与容器异步线程作用域")
class MvcAsyncTraceTest {

    @Test
    @DisplayName("ASYNC 二次派发必须复用首次生成的 traceId，且 chain 内 MDC 有值")
    fun `async re-dispatch reuses the original request id`() {
        MDC.remove(TraceIds.MDC_KEY)
        val request = MockHttpServletRequest("GET", "/v1/stream/device-1/live")
        val response = MockHttpServletResponse()
        val filter = TraceIdFilter()

        var firstId: String? = null
        filter.doFilter(request, response, FilterChain { _, _ -> firstId = MDC.get(TraceIds.MDC_KEY) })
        assertNotNull(firstId, "首次 REQUEST 派发应在 chain 内设置 traceId")
        // 容器线程被释放去服务别的请求，MDC 必须已清空。
        assertNull(MDC.get(TraceIds.MDC_KEY), "首次派发结束后必须清 MDC，避免线程复用串号")

        // 同一个 request 对象被容器以 ASYNC 类型二次派发（真正写响应的阶段）。
        request.setDispatcherType(DispatcherType.ASYNC)
        var secondId: String? = null
        filter.doFilter(request, response, FilterChain { _, _ -> secondId = MDC.get(TraceIds.MDC_KEY) })

        assertEquals(
            firstId, secondId,
            "二次派发拿不到原 traceId：响应阶段的日志会与请求阶段断链，客户端手里的 X-Request-Id 只能查到半条链路"
        )
        assertEquals(firstId, response.getHeader(TraceIds.HEADER), "响应头不能被二次派发改写")
        assertNull(MDC.get(TraceIds.MDC_KEY), "二次派发结束后同样要清 MDC")
    }

    @Test
    @DisplayName("上游 ID 在异步二次派发后仍原样保留")
    fun `inbound id survives async re-dispatch`() {
        MDC.remove(TraceIds.MDC_KEY)
        val request = MockHttpServletRequest("GET", "/api/v1/device/commands")
        request.addHeader(TraceIds.HEADER, "gw-poll-42")
        val response = MockHttpServletResponse()
        val filter = TraceIdFilter()

        filter.doFilter(request, response, FilterChain { _, _ -> })
        request.setDispatcherType(DispatcherType.ASYNC)
        var seen: String? = null
        filter.doFilter(request, response, FilterChain { _, _ -> seen = MDC.get(TraceIds.MDC_KEY) })

        assertEquals("gw-poll-42", seen, "网关传入的 ID 在异步派发后必须保持，不能被重新生成")
        assertEquals("gw-poll-42", response.getHeader(TraceIds.HEADER))
    }

    /**
     * 这条是**前提验证型**护栏（当前实现下即为绿，不冒充红灯）：证明
     * `StreamingResponseBody` 的容器异步线程确实拿不到 MDC，因此端点必须显式传 ID；
     * 同时证明 `TraceIds.withTrace` 能安全恢复，容器线程池复用不会残留。
     */
    @Test
    @DisplayName("看屏流在容器异步线程上只能靠显式传 ID 关联，且作用域结束后不残留")
    fun `streaming body needs an explicit id and leaves no residue`() {
        MDC.remove(TraceIds.MDC_KEY)
        val request = MockHttpServletRequest("GET", "/v1/stream/device-1/live")
        request.addHeader(TraceIds.HEADER, "gw-stream-1")
        val response = MockHttpServletResponse()
        var captured: String? = null
        TraceIdFilter().doFilter(request, response, FilterChain { _, _ -> captured = TraceIds.current() })
        assertEquals("gw-stream-1", captured, "控制器只能在请求线程里捕获 ID")

        val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "stream-body") }
        try {
            val onBoot = pool.submit<String?> { MDC.get(TraceIds.MDC_KEY) }.get(3, TimeUnit.SECONDS)
            assertNull(onBoot, "前提：容器异步线程不应自动继承 MDC")

            val inScope = pool.submit<String?> {
                TraceIds.withTrace(captured!!) { MDC.get(TraceIds.MDC_KEY) }
            }.get(3, TimeUnit.SECONDS)
            assertEquals(captured, inScope, "显式包作用域后，流内部日志应归属原请求")

            val residue = pool.submit<String?> { MDC.get(TraceIds.MDC_KEY) }.get(3, TimeUnit.SECONDS)
            assertNull(residue, "withTrace 必须恢复旧值，容器线程池复用时不能把两条流串成一条")
        } finally {
            pool.shutdownNow()
        }
    }
}
