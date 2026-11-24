package com.padguard.core.transport.mqtt

import com.padguard.core.common.Logger
import com.padguard.core.data.model.RiskEvent
import com.padguard.core.data.repository.RealtimeAlertPublisher
import com.padguard.core.transport.http.CredentialStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [RealtimeAlertPublisher] 的 MQTT 实现：把 HIGH 风险事件即时推到 `up/alert/realtime` topic（QoS1）。
 *
 * 行为约束（fail-closed，详见接口 KDoc）：
 * - [sendNow] 非阻塞、吞掉一切异常，绝不冒泡到 `LogRepository.raise` 的调用方。
 * - 未绑定 / deviceId 空 / MQTT 未连接时直接放弃，交给批量 HTTPS 上报兜底。
 * - 不做任何「标记已上传」操作，避免与批量路径竞争。
 *
 * 底层复用 [MqttTransport.publish]：已内置上行 HMAC 签名（[com.padguard.core.transport.mqtt.UplinkSigner]）
 * 与 tenant 前缀 topic，命中 Broker 既有 ACL `pg/v1/%tenant/#`，无需改 ACL。
 */
@Singleton
class MqttRealtimeAlertPublisher @Inject constructor(
    private val mqtt: MqttTransport,
    private val credentials: CredentialStore
) : RealtimeAlertPublisher {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun sendNow(event: RiskEvent) {
        val deviceId = credentials.deviceId
        val tenant = credentials.tenantId
        if (deviceId.isBlank() || !credentials.isBound) return
        runCatching {
            val payload = json.encodeToString(RiskEvent.serializer(), event)
            mqtt.publish(
                MqttTopics.upRealtimeAlert(tenant, deviceId),
                payload,
                MqttTopics.QOS_RELIABLE
            )
        }.onFailure { Logger.w(TAG, it) { "realtime alert publish failed: ${event.type}" } }
    }

    companion object {
        private const val TAG = "MqttRealtimeAlertPublisher"
    }
}
