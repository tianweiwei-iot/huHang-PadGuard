package com.padguard.server.observability

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.padguard.server.dto.ServerMetrics
import jakarta.annotation.PostConstruct
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque

private val HHMMSS: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

/**
 * HTTP 请求量 / 错误率采集（60 秒滑动窗口）。
 *
 * 为什么要它：后台「服务端监控」页的 QPM 与错误率此前是硬编码的 0 和 "0.00"，
 * 运营看到的是一组永远不会变的假数字，无法据此判断服务是否异常。
 * 这里用最轻量的方式算真值——只维护最近 60 秒的时间戳队列，不引入 Micrometer
 * 等重型依赖，也不写磁盘，重启即清零（与进程生命周期一致，符合"运行时指标"语义）。
 *
 * 采样口子放在 Servlet Filter 而非拦截器：拦截器管不到 404、以及被排除鉴权的路径，
 * 会低估请求量；Filter 覆盖全部进入容器的 HTTP 请求。
 */
@Component
class RequestMetrics : OncePerRequestFilter() {

    private val lock = Any()
    private val all = ArrayDeque<Long>()
    private val errs = ArrayDeque<Long>()
    private val latencies = ArrayDeque<Long>()

    /** 分钟级计数桶（环形，覆盖最近 [HISTORY_MINUTES] 分钟），供后台趋势图取真实序列 */
    private val bucketCount = LongArray(HISTORY_MINUTES)
    private val bucketMinute = LongArray(HISTORY_MINUTES) { -1L }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain
    ) {
        // 监控自身的探针与静态资源不计入业务 QPM，避免后台开着页面就把指标顶上去
        if (request.requestURI == "/health" || request.requestURI.startsWith("/admin/")) {
            chain.doFilter(request, response)
            return
        }
        val start = System.currentTimeMillis()
        try {
            chain.doFilter(request, response)
        } finally {
            val status = response.status
            val cost = System.currentTimeMillis() - start
            val now = System.currentTimeMillis()
            synchronized(lock) {
                all.addLast(now)
                latencies.addLast(cost)
                if (status >= 500) errs.addLast(now)
                bumpMinuteBucket(now)
                trim(now)
            }
        }
    }

    private fun bumpMinuteBucket(now: Long) {
        val minute = now / 60_000L
        val idx = (minute % HISTORY_MINUTES).toInt()
        if (bucketMinute[idx] != minute) {
            bucketMinute[idx] = minute
            bucketCount[idx] = 0
        }
        bucketCount[idx]++
    }

    /**
     * 最近 [HISTORY_MINUTES] 分钟每分钟的请求数（旧 → 新）。
     * 没有流量的分钟返回 0，保证序列长度恒定、前端趋势图不会因为缺数据而画错。
     */
    fun qpmHistory(): List<Int> {
        val nowMinute = System.currentTimeMillis() / 60_000L
        return (0 until HISTORY_MINUTES).map { k ->
            val minute = nowMinute - (HISTORY_MINUTES - 1 - k)
            val idx = (minute % HISTORY_MINUTES).toInt()
            if (bucketMinute[idx] == minute) bucketCount[idx].toInt() else 0
        }
    }

    private fun trim(now: Long) {
        val edge = now - WINDOW_MS
        while (all.isNotEmpty() && all.first() < edge) all.removeFirst()
        while (errs.isNotEmpty() && errs.first() < edge) errs.removeFirst()
        while (latencies.size > MAX_SAMPLES) latencies.removeFirst()
        while (all.size > MAX_SAMPLES) all.removeFirst()
    }

    /** 最近 60 秒请求数 */
    fun requestsPerMin(): Int = synchronized(lock) { all.size }

    /** 最近 60 秒 5xx 占比，保留两位小数；无请求时为 "0.00" */
    fun errorRate(): String = synchronized(lock) {
        if (all.isEmpty()) "0.00" else String.format("%.2f", errs.size * 100.0 / all.size)
    }

    /** 最近请求的平均耗时（毫秒），用于判断"卡不卡"；无样本为 0 */
    fun avgLatencyMs(): Long = synchronized(lock) {
        if (latencies.isEmpty()) 0L else latencies.sum() / latencies.size
    }

    private companion object {
        const val WINDOW_MS = 60_000L
        const val MAX_SAMPLES = 20_000
        const val HISTORY_MINUTES = 30
    }
}

/**
 * 运行日志环形缓冲：把最近的 WARN/ERROR（以及本服务的 INFO 业务事件）留在内存里，
 * 供后台「实时日志」查看。
 *
 * 为什么不做"读日志文件"：服务端以 jar 方式启动时日志通常重定向到控制台或由运维托管，
 * 进程内并没有一个稳定可读的日志路径，读文件方案在不同部署方式下会时灵时不灵。
 * 用 Logback Appender 挂到 root logger 上，与部署方式无关，是更稳的做法。
 *
 * 缓冲有上限（默认 200 条）且只在内存：不落盘、不泄漏历史、不影响磁盘占用。
 */
@Component
class RuntimeLogRing {

    private val buf = ArrayDeque<ServerMetrics.LogLine>()

    @PostConstruct
    fun attach() {
        runCatching {
            val ctx = LoggerFactory.getILoggerFactory() as LoggerContext
            val appender = RingAppender(this)
            appender.context = ctx
            appender.name = "PADGUARD_RING"
            appender.start()
            ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).addAppender(appender)
        }.onFailure {
            LoggerFactory.getLogger(RuntimeLogRing::class.java)
                .warn("运行日志环形缓冲挂载失败，后台「实时日志」将显示为空（不影响主流程）", it)
        }
    }

    internal fun offer(level: String, logger: String, msg: String) {
        val line = ServerMetrics.LogLine(
            time = HHMMSS.format(Instant.now()),
            level = level,
            msg = if (level == "INFO") msg else "[$logger] $msg"
        )
        synchronized(buf) {
            buf.addLast(line)
            while (buf.size > MAX) buf.removeFirst()
        }
    }

    /** 最近的日志，新的在前 */
    fun recent(): List<ServerMetrics.LogLine> = synchronized(buf) { buf.toList().asReversed() }

    private class RingAppender(private val ring: RuntimeLogRing) : AppenderBase<ILoggingEvent>() {
        override fun append(event: ILoggingEvent) {
            val lvl = event.level
            val isOwnInfo = lvl == Level.INFO && event.loggerName.startsWith("com.padguard")
            // 只留 WARN 及以上，以及本服务的 INFO 业务事件（建号、开通、下发指令等），
            // 否则 DEBUG/INFO 噪声会把 200 条缓冲瞬间冲掉，运营看到的反而全是无关日志。
            if (lvl.isGreaterOrEqual(Level.WARN) || isOwnInfo) {
                ring.offer(lvl.toString(), event.loggerName, event.formattedMessage)
            }
        }
    }

    private companion object {
        const val MAX = 200
    }
}
