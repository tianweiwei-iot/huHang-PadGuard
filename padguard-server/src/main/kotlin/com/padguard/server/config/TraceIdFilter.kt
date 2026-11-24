package com.padguard.server.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * 单次请求贯穿的 traceId：入站已有合法 ID 则复用，否则生成；写进 MDC 并回给调用方。
 *
 * ## 为什么必须有
 * 服务端此前没有 logback 配置、没有 MDC、没有请求 ID（阶段 0 实测）。一次「绑定失败 /
 * 支付没到账 / 指令没下去」在日志里是几十行互不相关的时间戳，家长端报错时只能靠手机号
 * 和时间点人工拼线索。有了 `X-Request-Id`，客户端报错截屏里的这个头可以直接定位
 * 这一次请求的全部日志；`application.yml` 的 console pattern 会把它打进每一行。
 *
 * ## 为什么入站 ID 要校验
 * 该值会被写进日志文件与响应头。直接采信客户端传入值等于开放日志注入：
 * 一个带 `\n` 或控制字符的头可以伪造日志行、污染运维检索，甚至干扰按行采集的 agent。
 * 因此只接受 `[A-Za-z0-9_-]{1,64}`，其余一律丢弃并重新生成 —— 合法但不存在的 ID
 * 依然透传，便于网关 / 上游服务把整条链路串起来。
 *
 * ## 异步链路
 * MDC 是 ThreadLocal，不会自动跨线程。本 Filter 只保证**同步请求链路**的贯穿：
 * - `@Async` 执行器由 [com.padguard.server.PadGuardServerApplication.taskExecutor] 的
 *   TaskDecorator 继承提交时刻的 traceId（无上下文时生成独立 `async-` ID）；
 * - MQTT 上行回调线程由 [com.padguard.server.mqtt.ChildUplinkHandler.messageArrived]
 *   用 [TraceIds.withNewTrace] 包成单条报文一个 `mqtt-` ID；
 * - `@Scheduled` 的 7 处定时方法由各自公开入口用 `withNewTrace("sched")` 包一层，
 *   每轮调度一个独立 `sched-` ID。刻意**不替换默认调度器**：自定义 TaskScheduler 会
 *   改变既有定时任务的并发度，风险远大于 logging 收益。覆盖由
 *   `ScheduledTraceContractTest` 源码扫描锁死，以后新增定时任务漏包会当场测试失败。
 *
 * ## MVC 异步派发
 * `DeferredResult`（长轮询取指令）与 `StreamingResponseBody`（实时看屏）会先返回
 * 「未完成」并释放容器线程，真正写响应时容器把**同一个 request 对象**以
 * `DispatcherType.ASYNC` 二次派发。`OncePerRequestFilter` 默认跳过这次派发，而首次
 * 派发的 finally 已经清掉 MDC —— 响应阶段的日志会整段没有 ID。因此本 Filter 覆写
 * `shouldNotFilterAsyncDispatch()` 为 false，并把首次生成的 ID 存在 request attribute
 * 里供二次派发读回（不能靠重新读请求头：客户端没带 `X-Request-Id` 时会生成新 ID，
 * 一次请求被劈成两条链路）。与 `ResponseHeaderFilter` 的处理方式一致。
 *
 * 光靠 Filter 还不够：`StreamingResponseBody` 的 lambda 与长轮询的完成回调跑在
 * **容器异步线程 / 唤醒者线程**，不在这次派发的调用栈里，MDC 天然为空。两处端点
 * 各自在请求线程捕获 `TraceIds.current()`，再用 `TraceIds.withTrace` 显式传进去；
 * `withTrace` 会恢复旧值，所以不会抹掉 MQTT 回调原有的 `mqtt-` ID。接线由
 * `MvcAsyncTraceContractTest` 源码扫描锁死，新增异步端点漏接会当场测试失败。
 *
 * ## 已知局限（不虚构能力）
 * 容器级 `DispatcherType.ERROR` 派发（异常一路逃逸到容器、由 `/error` 兜底）仍不经过
 * 本 Filter；正常业务异常在 `GlobalExceptionHandler` 内处理，属于首次 REQUEST 派发，
 * 有 ID。绕过 Spring MVC、自己 `startAsync()` 的裸 `AsyncContext` 同样不覆盖
 * （当前代码没有这种用法）。此外定时任务内部的 `@Async` 会由 taskExecutor 继承
 * 当前 `sched-` ID（同一条链路），这是预期行为，不是串线。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class TraceIdFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain
    ) {
        // 优先读首次派发存下的 ID：ASYNC 二次派发是同一个 request 对象，重新走
        // resolve(header) 在「客户端没带 X-Request-Id」时会另生成一个，前后两半日志对不上。
        // 仍交给 resolve 校验一遍，attribute 万一被别处塞了非法值也不会污染日志。
        val saved = request.getAttribute(ATTR_TRACE_ID) as? String
        val traceId = TraceIds.resolve(saved ?: request.getHeader(TraceIds.HEADER))
        request.setAttribute(ATTR_TRACE_ID, traceId)
        response.setHeader(TraceIds.HEADER, traceId)
        MDC.put(TraceIds.MDC_KEY, traceId)
        try {
            chain.doFilter(request, response)
        } finally {
            // 容器线程会被复用；不清会在下一条无关请求上残留上一个 traceId，
            // 造成「两条请求的日志串在一起」这种比没有 ID 更糟的假象。
            MDC.remove(TraceIds.MDC_KEY)
        }
    }

    /**
     * 必须参与 ASYNC 二次派发，否则真正写响应那一程没有 traceId（见类注释）。
     * 首次派发结束时 `OncePerRequestFilter` 会移除自己的 "already filtered" 标记，
     * 因此二次派发会重新进入 [doFilterInternal]，此时靠 [ATTR_TRACE_ID] 复用同一个 ID。
     */
    override fun shouldNotFilterAsyncDispatch(): Boolean = false

    private companion object {
        const val ATTR_TRACE_ID = "com.padguard.server.config.TraceIdFilter.TRACE_ID"
    }
}

/**
 * traceId 的唯一生成 / 传播出口。
 *
 * 提为 public 是因为 MQTT 回调与 `@Async` 执行器都在 `config` 包之外，需要共用同一套
 * 「生成什么格式、怎么放进 MDC、怎么恢复」的规则；各写一份迟早会漂成两种格式，
 * 运维按 `X-Request-Id` 检索时就会漏掉一半日志。
 */
object TraceIds {
    const val HEADER = "X-Request-Id"
    const val MDC_KEY = "traceId"

    private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,64}")

    fun resolve(incoming: String?): String =
        incoming?.takeIf { SAFE_ID.matches(it) } ?: newId()

    /** 32 位无连字符 UUID；可选前缀仅用于区分日志来自 HTTP / async / mqtt 哪条链路。 */
    fun newId(prefix: String? = null): String {
        val raw = UUID.randomUUID().toString().replace("-", "")
        return if (prefix.isNullOrBlank()) raw else "$prefix-$raw"
    }

    fun current(): String? = MDC.get(MDC_KEY)

    /**
     * 在一个显式 traceId 作用域内执行 [block]，结束后**恢复调用前的 MDC 值**。
     *
     * 必须是「恢复」而不是无条件 `MDC.remove`：作用域可能嵌套在一条 HTTP 请求的
     * Filter 之内（例如 CallerRunsPolicy 让 `@Async` 任务回落到请求线程执行），
     * 无条件清空会把外层请求的 traceId 抹掉，后半段日志全部失去关联。
     * 线程池复用同理 —— 残留一个 ID 比没有 ID 更危险，会把两条无关请求串成一条。
     */
    fun <T> withTrace(id: String, block: () -> T): T {
        val previous = MDC.get(MDC_KEY)
        MDC.put(MDC_KEY, id)
        try {
            return block()
        } finally {
            if (previous == null) MDC.remove(MDC_KEY) else MDC.put(MDC_KEY, previous)
        }
    }

    fun <T> withNewTrace(prefix: String, block: () -> T): T = withTrace(newId(prefix), block)

    /**
     * 任务提交时刻捕获 MDC：有上下文就沿用（`@Async` 与触发它的请求同一条链路），
     * 没有就生成独立的 `async-` ID（至少让一次异步任务内部的日志能聚到一起）。
     * 捕获必须在**调用线程**完成，Runnable 体内再读 MDC 已经是空的了。
     */
    fun decorate(runnable: Runnable): Runnable {
        val inherited = MDC.get(MDC_KEY) ?: newId("async")
        return Runnable { withTrace(inherited) { runnable.run() } }
    }
}
