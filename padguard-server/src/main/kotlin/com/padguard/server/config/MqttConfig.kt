package com.padguard.server.config

import org.eclipse.paho.mqttv5.client.MqttClient
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions
import org.eclipse.paho.mqttv5.client.persist.MqttDefaultFilePersistence
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.UUID
import java.util.concurrent.ThreadPoolExecutor

@Configuration
class MqttConfig(
    @Value("\${padguard.mqtt.broker-url}") private val brokerUrl: String,
    @Value("\${padguard.mqtt.username:}") private val username: String,
    @Value("\${padguard.mqtt.password:}") private val password: String,
    @Value("\${padguard.mqtt.keep-alive-seconds:90}") private val keepAlive: Int,
    @Value("\${padguard.mqtt.uplink-pool-size:8}") private val uplinkPoolSize: Int,
    @Value("\${padguard.mqtt.uplink-queue-capacity:2048}") private val uplinkQueueCapacity: Int,
    @Value("\${padguard.mqtt.max-inflight:200}") private val maxInflight: Int,
    /**
     * MQTT 同步操作超时（毫秒）。
     *
     * Paho 的 timeToWait 默认是 **-1（无限等待）**：Broker 收包但不回 PUBACK 时，
     * `client.publish()` 会让调用线程永久阻塞。该调用发生在业务请求线程与定时任务线程上，
     * 200 次即可占满 Tomcat / 停掉全部 @Scheduled。必须显式设成有限值。
     */
    @Value("\${padguard.mqtt.operation-timeout-ms:5000}") private val operationTimeoutMs: Long
) {

    private val log = LoggerFactory.getLogger(MqttConfig::class.java)

    /**
     * 上行处理线程池：Paho 的回调线程全局只有一条，验签/查库/落库/推 WS 都塞在里面时，
     * 一台设备卡住会让全部设备的 ack、心跳和告警排队。回调只负责投递，业务在这里并发处理。
     *
     * 队列有界 + AbortPolicy：过载时明确丢弃并记日志，绝不退化成 CallerRunsPolicy
     * 把负载重新压回 Paho 回调线程（那就等于没有隔离）。容量需结合实际压测再调。
     */
    @Bean(name = ["uplinkExecutor"])
    fun uplinkExecutor(): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
        corePoolSize = uplinkPoolSize.coerceAtLeast(1)
        maxPoolSize = (uplinkPoolSize * 2).coerceAtLeast(2)
        queueCapacity = uplinkQueueCapacity.coerceAtLeast(1)
        setThreadNamePrefix("pg-uplink-")
        setRejectedExecutionHandler(ThreadPoolExecutor.AbortPolicy())
        setWaitForTasksToCompleteOnShutdown(true)
        setAwaitTerminationSeconds(10)
    }

    /**
     * 仅当 padguard.mqtt.enabled=true 时创建 MQTT 客户端；本地免 Docker 运行置为 false，避免强连 EMQX 导致启动失败。
     *
     * 返回**可空**：Broker 不可达时返回 null 而非抛异常。理由：
     * - 抛异常会让整个服务端启动失败，导致家长端与被管控端全部不可用；而实际上下行走 HTTP 轮询仍可工作。
     * - “Broker 抖动”不应升级为“服务整体故障”，应降级：实时推送暂不可用，轮询继续兜底。
     * 消费方 [MqttGateway] 与 [com.padguard.server.mqtt.ChildUplinkHandler] 均已按可空处理。
     */
    @Bean
    @ConditionalOnProperty(name = ["padguard.mqtt.enabled"], havingValue = "true", matchIfMissing = true)
    fun mqttClient(): MqttClient? {
        val clientId = "padguard-server-" + UUID.randomUUID().toString().substring(0, 8)
        val persistence = MqttDefaultFilePersistence(System.getProperty("java.io.tmpdir") + "/padguard-mqtt")
        val client = MqttClient(brokerUrl, clientId, persistence)
        // 关键：Paho 默认无限等待，半开连接会让发布线程永久挂住。
        client.timeToWait = operationTimeoutMs.coerceAtLeast(1)

        val pwBytes = password.toByteArray()
        val opts = MqttConnectionOptions().apply {
            setCleanStart(false)
            // 连接阶段同样不能无限等；下面 20 次重试每次最多 5s，总启动耗时有上界。
            setConnectionTimeout((operationTimeoutMs / 1000).coerceAtLeast(1).toInt())
            keepAliveInterval = keepAlive
            setAutomaticReconnect(true)
            // MQTT 5 用 ReceiveMaximum 约束在途 QoS1/2 报文（v3 的 maxInflight）。
            // 默认值过小会让高并发上行直接被 broker/客户端限流，表现为指令回执/心跳成片迟到。
            setReceiveMaximum(maxInflight.coerceAtLeast(10))
            if (username.isNotBlank()) {
                this.userName = username
                setPassword(pwBytes)
            }
        }

        // EMQX 启动需要几秒，反复重试直到连上（开发环境足够）
        var connected = false
        repeat(20) {
            try {
                client.connect(opts)
                connected = true
                return@repeat
            } catch (e: Exception) {
                Thread.sleep(3000)
            }
        }
        if (!connected) {
            log.error(
                "无法连接 MQTT Broker: {}，已降级为 HTTP 轮询下发（实时推送暂不可用）。" +
                    "请检查 Broker 是否已启动、地址是否可达。",
                brokerUrl
            )
            return null
        }
        log.info("MQTT client connected: {}", brokerUrl)
        return client
    }
}
