package com.padguard.core.transport.mqtt

/**
 * Topic 规范（契约 §3.2）：`pg/v1/{tenantId}/{direction}/{deviceId}/{type}`
 *
 * 把 tenantId 放进 topic 前缀，是为了在 Broker（EMQX/Mosquitto）侧直接用 ACL
 * 做租户级隔离 —— 一个 ACL 规则 `pg/v1/%tenant/#` 就能挡住跨校数据串台，
 * 无需在业务层逐条校验。
 */
object MqttTopics {

    const val PREFIX = "pg/v1"

    const val QOS_HEARTBEAT = 0
    const val QOS_RELIABLE = 1

    // ---------- 下行（订阅） ----------

    fun downCommand(tenant: String, deviceId: String) = "$PREFIX/$tenant/down/$deviceId/command"
    fun downPolicy(tenant: String, deviceId: String) = "$PREFIX/$tenant/down/$deviceId/policy"
    fun downConfig(tenant: String, deviceId: String) = "$PREFIX/$tenant/down/$deviceId/config"

    /** 分组批量指令（课堂全员锁屏）。groupId 用 + 通配，由服务端保证只推给组内设备 */
    fun broadcastCommand(tenant: String) = "$PREFIX/$tenant/broadcast/+/command"

    /** 订阅列表：一次 subscribe 多 topic，减少重连后的往返 */
    fun subscriptions(tenant: String, deviceId: String): List<Pair<String, Int>> = listOf(
        downCommand(tenant, deviceId) to QOS_RELIABLE,
        downPolicy(tenant, deviceId) to QOS_RELIABLE,
        downConfig(tenant, deviceId) to QOS_RELIABLE,
        broadcastCommand(tenant) to QOS_RELIABLE
    )

    // ---------- 上行（发布） ----------

    fun upHeartbeat(tenant: String, deviceId: String) = "$PREFIX/$tenant/up/$deviceId/heartbeat"
    fun upAck(tenant: String, deviceId: String) = "$PREFIX/$tenant/up/$deviceId/ack"
    fun upEvent(tenant: String, deviceId: String) = "$PREFIX/$tenant/up/$deviceId/event"
    fun upStatus(tenant: String, deviceId: String) = "$PREFIX/$tenant/up/$deviceId/status"

    /** 判断收到的 topic 属于哪类下行消息 */
    fun classify(topic: String): DownlinkKind = when {
        topic.endsWith("/command") -> DownlinkKind.COMMAND
        topic.endsWith("/policy") -> DownlinkKind.POLICY
        topic.endsWith("/config") -> DownlinkKind.CONFIG
        else -> DownlinkKind.UNKNOWN
    }

    enum class DownlinkKind { COMMAND, POLICY, CONFIG, UNKNOWN }
}

/** MQTT 收到的一条下行消息 */
data class MqttInbound(
    val topic: String,
    val kind: MqttTopics.DownlinkKind,
    val payload: String,
    val receivedAt: Long
)
