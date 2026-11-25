package com.padguard.server.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.ThreadPoolExecutor

/**
 * MVC 异步端点（看屏拉流 `StreamingResponseBody` 等）的**有界**线程池。
 *
 * 默认 `SimpleAsyncTaskExecutor` 每来一个请求就 new 一个线程、且没有上限。
 * 看屏是 30 分钟级长连接，几百路并发就能把 JVM 线程与内存打满，
 * 连 /health、家长轮询和后台一起排队。这里改为固定大小池 + 直接拒绝：
 * 容量到顶时明确失败（429/5xx 由调用方语义决定），而不是无限创建线程拖垮全站。
 *
 * 池容量不得低于 [maxLiveDevices]（推流并发准入上限），
 * 否则"允许开流"的家长在拉流阶段反而拿不到线程，表现为明明没满却看不了。
 * 线程按需创建，配置值大不等于启动即占满内存。
 */
@Configuration
class MvcAsyncConfig(
    @Value("\${padguard.stream.max-live-devices:1000}") private val maxLiveDevices: Int,
    @Value("\${padguard.mvc.async-pool-size:1000}") private val configuredPoolSize: Int
) {
    @Bean(name = ["mvcAsyncTaskExecutor"])
    fun mvcAsyncTaskExecutor(): ThreadPoolTaskExecutor {
        val size = maxOf(configuredPoolSize, maxLiveDevices).coerceAtLeast(4)
        return ThreadPoolTaskExecutor().apply {
            corePoolSize = size
            maxPoolSize = size
            // 无队列：避免请求在队列里静默排队直到超时，拒绝要当场可见
            setQueueCapacity(0)
            setThreadNamePrefix("pg-mvc-async-")
            // 拒绝时绝不回落到"调用方线程自己跑"：那会让长流占住 Tomcat 请求线程，
            // 等于回到同步阻塞旧路（这条禁令由 ServerCapacityContractTest 锁定）
            setRejectedExecutionHandler(ThreadPoolExecutor.AbortPolicy())
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(30)
        }
    }
}
