package com.padguard.server.mqtt

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.dto.CommandPacket
import org.eclipse.paho.mqttv5.client.MqttClient
import org.eclipse.paho.mqttv5.common.MqttMessage
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicInteger

/** 服务端 -> 被管控端 的 MQTT 下发通道 */
@Component
class MqttGateway(
    private val mqttClient: MqttClient?,
    private val objectMapper: ObjectMapper,
    @Value("\${padguard.mqtt.tenant:default}") private val tenant: String,
    /** W2：策略版本通知改 retained，让上线设备订阅后立刻拿到最新版本 */
    @Value("\${padguard.mqtt.policy-retained:true}") private val policyRetained: Boolean = true
) {
    fun downCommandTopic(deviceId: String) = "pg/v1/$tenant/down/$deviceId/command"
    fun downPolicyTopic(deviceId: String) = "pg/v1/$tenant/down/$deviceId/policy"
    fun downConfigTopic(deviceId: String) = "pg/v1/$tenant/down/$deviceId/config"

    /** 连续发布失败次数（监控埋点，供 metrics / 告警消费；成功一次即清零） */
    private val publishFailureStreak = AtomicInteger(0)
    fun consecutivePublishFailures(): Int = publishFailureStreak.get()

    fun publishCommand(deviceId: String, packet: CommandPacket) {
        publish(
            downCommandTopic(deviceId), packet,
            context = "command msgId=${packet.msgId} type=${packet.type}"
        )
    }

    fun publishPolicyNotify(deviceId: String, version: Int) {
        // retained 的只是 `{version}` 一个整数，不含任何设备/未成年人个人信息。
        // 设备上线订阅时 broker 立刻补发这条，孩子端据此比对本地策略版本并主动拉取，
        // 不必等家长下一次改设置。关闭配置即退回原语义（非 retained）。
        publish(
            downPolicyTopic(deviceId), mapOf("version" to version),
            retained = policyRetained, context = "policyNotify version=$version"
        )
    }

    /** 下发运行参数 / 设备元信息（如自定义设备名）到被控端 */
    fun publishConfig(deviceId: String, values: Map<String, String>) {
        publish(downConfigTopic(deviceId), values, context = "config keys=${values.keys}")
    }

    /**
     * 解绑/易主时清除该设备下行通道上的 retained 消息。
     *
     * 策略通知（`publishPolicyNotify`）以 retained 形式下发：设备上线订阅即拉到最新版本号。
     * 一旦设备解绑，这枚 retained 消息仍留在 Broker 上；同一台平板被新家长重绑、或他人捡到后绑定，
     * 上线瞬间会先收到**上一任的策略 retained** —— 表现为"解绑后旧策略还在生效 / 新家长的策略被覆盖"。
     * 这里在解绑时向策略与配置两个下行主题各发一条空 retained 消息，把 Broker 上的旧 retained 顶掉，
     * 新绑定方上线拿到的是"无 retained"的干净状态，由正常策略下发流程重建。
     * 仅打印 warn 不抛异常：清 retained 失败不影响解绑主流程，最坏只是旧 retained 多留一会，
     * 下次重绑/易主会再清一次。
     */
    fun clearRetained(deviceId: String) {
        publishEmptyRetained(downPolicyTopic(deviceId))
        publishEmptyRetained(downConfigTopic(deviceId))
    }

    private fun publishEmptyRetained(topic: String) {
        val client = mqttClient ?: return
        try {
            val msg = MqttMessage(ByteArray(0))
            msg.qos = 1
            msg.isRetained = true
            client.publish(topic, msg)
        } catch (e: Exception) {
            log.warn("clear retained failed topic={}: {}", topic, e.toString())
        }
    }

    private fun publish(topic: String, payload: Any, retained: Boolean = false, context: String = "") {
        val client = mqttClient ?: run {
            // MQTT 被配置显式关闭（local profile / 家庭自建免 broker）是**预期状态**，不是故障：
            // 指令已以 PENDING 落库，由 HTTP 长轮询 + W2 补发兜底。只留 debug，
            // 否则免 broker 部署每天会刷出几万条无意义 error 把真故障淹没。
            log.debug(
                "MQTT disabled, publish skipped: topic={} context={} (HTTP polling / redelivery takes over)",
                topic, context
            )
            return
        }
        try {
            val msg = MqttMessage(objectMapper.writeValueAsBytes(payload))
            msg.qos = 1
            msg.isRetained = retained
            client.publish(topic, msg)
            publishFailureStreak.set(0)
        } catch (e: Exception) {
            val streak = publishFailureStreak.incrementAndGet()
            // 改造前这里完全没有日志：调用方 CommandService 只有一条泛泛的
            // warn("command publish failed")，没有 topic / msgId / 连续失败次数，
            // 运维无法区分「偶发网络抖动」与「broker 整体宕机」，更没有告警触发点。
            // 补上结构化 error；异常继续抛出让调用方保持原有降级语义。
            // 指令仍是 PENDING，由 W2 补发队列在设备上线时重放，不会因 broker 故障丢失。
            log.error(
                "MQTT publish FAILED topic={} context={} retained={} consecutiveFailures={} error={}",
                topic, context, retained, streak, e.toString()
            )
            throw e
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(MqttGateway::class.java)
    }
}
