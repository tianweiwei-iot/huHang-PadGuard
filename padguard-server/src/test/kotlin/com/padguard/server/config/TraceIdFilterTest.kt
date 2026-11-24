package com.padguard.server.config

import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

@DisplayName("TraceIdFilter —— 请求 ID 贯穿与日志注入防护")
class TraceIdFilterTest {

    private fun run(incoming: String? = null): Pair<String?, MockHttpServletResponse> {
        val request = MockHttpServletRequest("GET", "/v1/devices")
        if (incoming != null) request.addHeader(TraceIds.HEADER, incoming)
        val response = MockHttpServletResponse()
        var seenInChain: String? = "not-run"
        val chain = FilterChain { _, _ -> seenInChain = MDC.get(TraceIds.MDC_KEY) }
        TraceIdFilter().doFilter(request, response, chain)
        return Pair(seenInChain, response)
    }

    @Test
    @DisplayName("请求处理期间 MDC 里必须拿得到 traceId")
    fun `traceId is visible in MDC while the chain runs`() {
        val (seen, _) = run()
        assertTrue(!seen.isNullOrBlank(), "日志里没有 traceId 等于没做链路贯穿；实际 MDC=$seen")
        assertTrue(Regex("[A-Za-z0-9_-]{1,64}").matches(seen!!), "traceId 格式不安全：$seen")
    }

    @Test
    @DisplayName("请求结束后必须清 MDC，避免线程复用串号")
    fun `MDC is cleared after the request`() {
        run()
        assertNull(MDC.get(TraceIds.MDC_KEY), "容器线程复用会把上一条请求的 traceId 带到下一条")
    }

    @Test
    @DisplayName("响应头回写 traceId，客户端报错截屏可直接带上")
    fun `response carries the request id`() {
        val (_, response) = run()
        val header = response.getHeader(TraceIds.HEADER)
        assertTrue(!header.isNullOrBlank(), "响应缺少 ${TraceIds.HEADER}")
        assertTrue(Regex("[A-Za-z0-9_-]{1,64}").matches(header!!), "回写的 ID 不合法：$header")
    }

    @Test
    @DisplayName("上游传来的合法 ID 原样复用（跨服务串链路）")
    fun `valid inbound id is reused verbatim`() {
        val (_, response) = run("gw-abc_123")
        assertEquals("gw-abc_123", response.getHeader(TraceIds.HEADER))
    }

    @Test
    @DisplayName("含换行 / 空格 / 控制字符的入站 ID 一律丢弃，防日志注入")
    fun `unsafe inbound ids are rejected`() {
        listOf("bad\nINFO forged", "has space", "bad\tid", "x".repeat(65), "", "中文ID")
            .forEach { unsafe ->
                val generated = TraceIds.resolve(unsafe)
                assertNotEquals(unsafe, generated, "不安全 ID 被采信：$unsafe")
                assertTrue(Regex("[A-Za-z0-9_-]{1,64}").matches(generated))
                assertFalse(generated.contains('\n'))
            }
    }

    @Test
    @DisplayName("边界：64 位合法、65 位拒绝、null 生成")
    fun `resolve honours the length boundary`() {
        val max = "a".repeat(64)
        assertEquals(max, TraceIds.resolve(max))
        assertNotEquals("a".repeat(65), TraceIds.resolve("a".repeat(65)))
        assertTrue(TraceIds.resolve(null).isNotBlank())
    }
}
