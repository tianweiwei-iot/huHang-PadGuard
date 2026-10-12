package com.padguard.server

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
     */
    @Bean(name = ["taskExecutor"])
    fun taskExecutor(): Executor {
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = 4
        executor.maxPoolSize = 16
        executor.queueCapacity = 200
        executor.setThreadNamePrefix("async-")
        executor.setRejectedExecutionHandler(java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy())
        executor.initialize()
        return executor
    }
}

fun main(args: Array<String>) {
    runApplication<PadGuardServerApplication>(*args)
}
