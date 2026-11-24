package com.padguard.server.config

import jakarta.annotation.PreDestroy
import org.eclipse.paho.mqttv5.client.MqttClient
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import org.springframework.beans.factory.DisposableBean

/**
 * MQTT 客户端生命周期钩子：应用停机时显式断开 Broker 连接。
 *
 * 原先 [MqttConfig.mqttClient] 只负责"连上"，从不"断开"——进程被 SIGTERM 终止后，
 * Paho 的 `setAutomaticReconnect(true)` 让客户端在后台反复重试，而 Broker 一侧直到
 * keepalive 超时（默认 90s）才把这条会话判定为掉线。后果：
 *   - 滚动发布/重启期间，旧实例的半开连接在 Broker 上堆积，挤占连接数；
 *   - 开启 `cleanStart=false` 时旧会话被保留，Broker 持续为其缓存 QoS1 下行，
 *     新实例用随机 clientId 连上来也接不上那条会话，出现"推送偶发不到"的玄学现象。
 *
 * 这里在销毁阶段主动 `disconnect()`，给 Broker 一个干净的断开信号，让会话即时回收。
 * 注入的是可空 Bean（MQTT 关闭 / Broker 不可达时 MqttConfig 返回 null），
 * 此时方法体自然退化为 no-op，不影响免 Broker 部署。
 */
@Configuration
class MqttLifecycle(private val client: MqttClient?) : DisposableBean {

    private val log = LoggerFactory.getLogger(MqttLifecycle::class.java)

    @PreDestroy
    override fun destroy() {
        val c = client ?: return
        runCatching {
            if (c.isConnected) c.disconnect()
        }.onFailure {
            log.warn("MQTT 客户端断开失败（可以忽略，Broker 会在 keepalive 超时后回收会话）: {}", it.message)
        }
    }
}
