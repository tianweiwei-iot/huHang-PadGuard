package com.padguard.server

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 第 6 批的**源码级契约守卫**：把 MVC 异步端点的 traceId 接线固化成测试。
 *
 * 为什么必须扫源码：异步派发的 traceId 丢失是**静默**的 —— 编译通过、业务逻辑单测全绿，
 * 只有家长/孩子端真出问题时翻日志才会发现「响应阶段的日志没有 X-Request-Id」。
 * 而这类端点在本项目里只有两处、平时很少改动，几个月后加第三处时没人会记得要包一层。
 * 这里既锁死现有两处的接线，也锁死「以后新增异步端点必须一并接线」。
 *
 * 源码从 `src/main/kotlin` 读取：Gradle `test` 工作目录默认是 `padguard-server/`
 * （与 `ScheduledTraceContractTest` / `DataInitializerTest` 同一约定）。
 */
@DisplayName("MVC 异步端点 traceId 接线契约守卫")
class MvcAsyncTraceContractTest {

    private val mainSrc = File("src/main/kotlin/com/padguard/server")

    private fun isCode(line: String): Boolean {
        val t = line.trimStart()
        return t.isNotEmpty() &&
            !t.startsWith("*") && !t.startsWith("//") && !t.startsWith("/*")
    }

    @Test
    @DisplayName("TraceIdFilter 必须参与 ASYNC 二次派发")
    fun `filter opts into async dispatch`() {
        assertTrue(mainSrc.isDirectory, "测试工作目录应为 padguard-server 项目根，实际 ${mainSrc.absolutePath}")
        val lines = File(mainSrc, "config/TraceIdFilter.kt").readLines()
        val hit = lines.any { isCode(it) && it.contains("override fun shouldNotFilterAsyncDispatch") && it.contains("= false") }
        assertTrue(
            hit,
            "OncePerRequestFilter 默认跳过 ASYNC 二次派发；不覆写 shouldNotFilterAsyncDispatch()=false，" +
                "DeferredResult/StreamingResponseBody 写响应阶段的日志全部没有 traceId"
        )
    }

    @Test
    @DisplayName("长轮询端点必须捕获请求 ID 并包裹完成/超时/结束回调")
    fun `long polling callbacks inherit the request trace`() {
        val lines = File(mainSrc, "controller/ChildControllers.kt").readLines()
        val start = lines.indexOfFirst { it.trimStart().startsWith("fun commands(") }
        assertTrue(start >= 0, "找不到 ChildDeviceController.commands")
        val end = (start + 1 until lines.size).firstOrNull {
            lines[it].contains("return deferred")
        } ?: minOf(start + 70, lines.size - 1)
        val body = lines.subList(start, minOf(end + 1, lines.size)).joinToString("\n")

        assertTrue(
            body.contains("TraceIds.current()"),
            "长轮询必须在请求线程里捕获 traceId；回调由 MQTT/家长下发/超时线程触发，那时再读 MDC 已经是空的"
        )
        val scopes = Regex("TraceIds\\.withTrace\\(").findAll(body).count()
        assertTrue(
            scopes >= 3,
            "onReady / onTimeout / onCompletion 三个回调都要包作用域，实际只有 $scopes 处。" +
                "漏一个就会有一条回调链路的日志断开"
        )
    }

    @Test
    @DisplayName("StreamingResponseBody 的 lambda 必须显式包 trace 作用域")
    fun `streaming body wraps a trace scope`() {
        val lines = File(mainSrc, "stream/StreamControllers.kt").readLines()
        val start = lines.indexOfFirst { isCode(it) && it.contains("StreamingResponseBody {") }
        assertTrue(start >= 0, "找不到 ParentStreamController.live 的 StreamingResponseBody")
        val body = lines.subList(start, minOf(start + 12, lines.size)).joinToString("\n")
        assertTrue(
            body.contains("TraceIds.withTrace("),
            "看屏流的 lambda 跑在容器异步线程上，MDC 天然为空；不显式传 ID，" +
                "流终止/断流日志（含 finally 的 unsubscribe）无法归属到发起观看的那次请求"
        )
    }

    @Test
    @DisplayName("全仓每个异步端点所在文件都必须带 trace 接线（防将来新增端点漏接）")
    fun `every async endpoint file wires trace explicitly`() {
        val asyncFiles = mutableSetOf<File>()
        mainSrc.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            file.readLines().forEach { line ->
                if (!isCode(line)) return@forEach
                if (line.contains("DeferredResult<") || line.contains("StreamingResponseBody {")) {
                    asyncFiles += file
                }
            }
        }
        assertTrue(asyncFiles.size >= 2, "应至少覆盖长轮询与看屏流两个异步端点文件，实际 ${asyncFiles.size}")
        val missing = asyncFiles.filterNot { it.readText().contains("TraceIds.withTrace(") }.map { it.name }
        assertTrue(
            missing.isEmpty(),
            "以下文件含 MVC 异步端点但没有显式 trace 作用域，响应阶段日志会丢 X-Request-Id：$missing"
        )
    }

    @Test
    @DisplayName("文档不得继续声称 MVC 异步未覆盖")
    fun `documented limitation is updated`() {
        val doc = File(mainSrc, "config/TraceIdFilter.kt").readText()
        assertFalse(
            doc.contains("若将来控制器引入"),
            "MVC 异步已在本批接线，注释还留着『若将来引入 Callable/DeferredResult 需要显式传 ID』会误导后续维护"
        )
        assertTrue(
            doc.contains("异步派发"),
            "注释里应保留对异步派发行为的说明，便于后来者理解为什么要覆写 shouldNotFilterAsyncDispatch"
        )
    }
}
