package com.padguard.server

import com.padguard.server.config.TraceIds
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor

@SpringBootApplication
@EnableScheduling
@EnableAsync
class PadGuardServerApplication {
    /**
     * 异步任务执行器（供 @Async 逆地理回填等使用）。
     * 有明确上下界（核心 4 / 最大 16 / 队列 200），避免高并发上报时无限创建线程；
     * 队列满时由调用线程兜底执行（CallerRunsPolicy），不丢弃回填任务。
     *
     * TaskDecorator 在**提交时刻**捕获 MDC 的 traceId 并带给工作线程：否则 `@Async`
     * （逆地理回填等）的日志在 console pattern 里 traceId 一栏是空的，与触发它的请求
     * 完全串不起来。无请求上下文时生成一个独立 `async-` ID，至少一次任务内部可聚合。
     * 装饰器在 CallerRunsPolicy 回落到调用线程执行时同样安全 —— [TraceIds.withTrace]
     * 退出会恢复原值而不是清空，不会把外层请求的 ID 抹掉。
     */
    @Bean(name = ["taskExecutor"])
    fun taskExecutor(): Executor {
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = 4
        executor.maxPoolSize = 16
        executor.queueCapacity = 200
        executor.setThreadNamePrefix("async-")
        executor.setRejectedExecutionHandler(java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy())
        executor.setTaskDecorator { runnable -> TraceIds.decorate(runnable) }
        executor.initialize()
        return executor
    }
}

fun main(args: Array<String>) {
    runApplication<PadGuardServerApplication>(*args)
}
