package com.padguard.server.mqtt

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.config.TraceIds
import com.padguard.server.domain.DeviceEvent
import com.padguard.server.dto.AckPacket
import com.padguard.server.dto.EventPacket
import com.padguard.server.dto.HeartbeatDto
import com.padguard.server.repository.CommandRepository
import com.padguard.server.repository.DeviceEventRepository
import com.padguard.server.security.UplinkSigning
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.common.Audience
import com.padguard.server.common.UplinkValidation
import com.padguard.server.service.AlertService
import com.padguard.server.service.AppDistributionConverger
import com.padguard.server.service.CommandType
import com.padguard.server.service.DeviceService
import com.padguard.server.ws.WebSocketPushService
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.eclipse.paho.mqttv5.client.IMqttToken
import org.eclipse.paho.mqttv5.client.MqttCallback
import org.eclipse.paho.mqttv5.client.MqttClient
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse
import org.eclipse.paho.mqttv5.common.MqttException
import org.eclipse.paho.mqttv5.common.MqttMessage
import org.eclipse.paho.mqttv5.common.packet.MqttProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** 接收被管控端上行：ack / heartbeat / event / status，并回写状态、推 WS */
@Component
class ChildUplinkHandler(
    private val mqttClient: MqttClient?,
    private val objectMapper: ObjectMapper,
    private val commandRepository: CommandRepository,
    private val deviceEventRepository: DeviceEventRepository,
    private val deviceService: DeviceService,
    private val webSocketPush: WebSocketPushService,
    private val alertService: AlertService,
    /** R-P2-12：INSTALL_APP 回执同步收敛分发台账，否则家长端"分发中"永不闭环 */
    private val appDistributionConverger: AppDistributionConverger,
    /**
     * R-P0-3 上行验签开关（默认开启 = fail-closed）。
     *
     * 滚动升级期可临时设为 false：此时"持有密钥却未签名"的设备仍放行（仅告警），
     * 避免旧版 APK（尚未签名）的报文被一刀切拒收导致设备集体"失联"。
     * 全部孩子端升级完成后应改回 true（默认值）彻底闭环。
     */
    @Value("\${padguard.mqtt.require-uplink-signature:true}") private val requireUplinkSignature: Boolean = true,
    /**
     * H3：业务处理放到有界线程池；为 null 时（纯 JVM 单测直构）同步执行，保留既有测试口径。
     * 生产由 [MqttConfig.uplinkExecutor] 注入，回调线程只做投递，不被查库/推 WS 拖住。
     */
    @Qualifier("uplinkExecutor") private val uplinkExecutor: Executor? = null
) : MqttCallback {

    private val log = LoggerFactory.getLogger(ChildUplinkHandler::class.java)

    @PostConstruct
    fun subscribe() {
        val client = mqttClient ?: run {
            log.info("MQTT disabled (padguard.mqtt.enabled=false): uplink subscriber skipped")
            return
        }
        client.setCallback(this)
        client.subscribe("pg/v1/+/up/+/ack", 1)
        client.subscribe("pg/v1/+/up/+/heartbeat", 1)
        client.subscribe("pg/v1/+/up/+/event", 1)
        client.subscribe("pg/v1/+/up/+/status", 1)
        // B：HIGH 风险事件实时单发通道（QoS1）。孩子端 raise() 对 HIGH 级事件在落库同时即时推到这里，
        // 关键告警（如隧道静默失效 WEB_GUARD_DOWN）秒级到达家长端，不受批量日志窗口拖累。
        client.subscribe("pg/v1/+/up/+/alert/realtime", 1)
        log.info("ChildUplinkHandler subscribed to uplink topics")
    }

    /**
     * Paho 回调在它自己的线程上执行，不经过 HTTP Filter，MDC 天然是空的。
     * 每条上行报文包一个独立的 `mqtt-` 作用域：一次 ack / 心跳 / 事件引发的
     * 验签、落库、WS 推送等所有日志可以一次检索出来，且报文之间不串号。
     * 作用域退出会清空 Paho 线程的 MDC，避免线程复用把上一条报文的 ID 带下去。
     */
    override fun messageArrived(topic: String?, message: MqttMessage?) {
        val executor = uplinkExecutor
        if (executor == null) {
            TraceIds.withNewTrace("mqtt") { handleMessageArrived(topic, message) }
            return
        }
        // traceId 必须在提交线程生成：worker 里再 new 会让一次上行内部的日志散成两段。
        val traceId = TraceIds.newId("mqtt")
        val task = Runnable { TraceIds.withTrace(traceId) { handleMessageArrived(topic, message) } }
        try {
            executor.execute(task)
        } catch (e: RejectedExecutionException) {
            // 队列满时明确丢弃而不是回落回调线程：回落会让一条慢消息拖住全部设备。
            // 心跳有 15s 级冗余，丢弃单条通常可自愈；ack 丢失由幂等补报兜底。
            log.warn("uplink executor saturated, message dropped: topic={} err={}", topic, e.message)
        }
    }

    private fun handleMessageArrived(topic: String?, message: MqttMessage?) {
        if (topic == null || message == null) return
        val segments = topic.split("/")
        // pg/v1/{tenant}/up/{deviceId}/{type...}
        if (segments.size < 6) return
        val deviceId = segments[4]
        // type 可能是多段：ack/heartbeat/event/status 是单段，但 HIGH 风险实时告警是
        // `alert/realtime` 双段（见孩子端 MqttTopics.upRealtimeAlert）。
        // 旧实现只取 segments[5] 得到 "alert"，与 when 的 "alert/realtime" 永远匹配不上，
        // 导致 HIGH 告警消息进得来却静默丢弃、秒级告警失效（且无异常、连 warn 都没有）。
        val type = segments.drop(5).joinToString("/")
        val json = String(message.payload, StandardCharsets.UTF_8)
        // R-P0-3：上行应用层验签。返回要处理的 payload 原文；null 表示已拒收（已记日志/告警）。
        val payloadToProcess = verifyUplink(deviceId, type, json) ?: return
        // 整体兜底：回调里抛出的任何异常都会被 Paho 视为致命错误并**断开与 broker 的唯一共享连接**
        // （随后才重连），一条畸形报文就能让全部设备的上下行抖动。与 handleAck 的 runCatching 同口径。
        runCatching {
            when (type) {
                "ack" -> handleAck(deviceId, payloadToProcess)
                "heartbeat" -> handleHeartbeat(deviceId, payloadToProcess)
                "event" -> handleEvent(deviceId, payloadToProcess)
                "status" -> handleStatus(deviceId, payloadToProcess)
                // B：HIGH 风险事件实时单发（复用与 event 通道一致的落库 + 告警 + WS 推送逻辑）
                "alert/realtime" -> handleRealtimeAlert(deviceId, payloadToProcess)
                // ACL 允许设备写 up/%u/#，type 集合未来再漂移时不能静默黑洞：
                // 外层 runCatching 只能捕异常，when 无匹配分支时它什么也捕不到。
                else -> log.warn("unknown uplink type dropped: topic={} type={}", topic, type)
            }
        }.onFailure { log.warn("uplink dropped: topic={} err={}", topic, it.message) }
    }

    /**
     * 上行验签（R-P0-3）。返回要处理的 payload 原文；返回 null 表示拒收。
     *
     * 四类报文的处理：
     * 1. 信封报文（含 s/p）：取设备 hmacSecret 验签。密钥缺失时，若 [requireUplinkSignature]
     *    关闭则放行（迁移期兼容未下发密钥的台账），否则按伪造拒收。
     * 2. 裸 status 且 online=false：这是 broker 代发的**遗嘱**报文，broker 无法签名，
     *    单独豁免接受（攻击者伪造离线只会造成"短暂误报离线"，且会被下次心跳/HTTP 信号自愈，
     *    代价远小于"强拒遗嘱导致所有设备离线检测失效"）。
     * 3. 裸报文（其它类型，或 online=true 的 status）：设备已有密钥却未签名 →
     *    若 [requireUplinkSignature] 开启则按伪造拒收（fail-closed）；关闭时放行并告警。
     * 4. 设备无密钥（历史遗留/未下发）：无法验签，放行（与旧链路一致，仅告警）。
     */
    private fun verifyUplink(deviceId: String, type: String, json: String): String? {
        val root = runCatching { objectMapper.readTree(json) }.getOrNull() ?: run {
            log.warn("uplink dropped: unparseable json device={} type={}", deviceId, type)
            return null
        }
        val isEnvelope = root.has("s") && root.has("p")
        if (isEnvelope) {
            val pNode = root.get("p")
            val sNode = root.get("s")
            if (!pNode.isTextual || !sNode.isTextual) {
                log.warn("uplink dropped: malformed envelope device={} type={}", deviceId, type)
                return null
            }
            val inner = pNode.asText()
            val sig = sNode.asText()
            val secret = deviceService.getHmacSecret(deviceId)
            if (secret == null) {
                if (!requireUplinkSignature) {
                    log.warn("uplink accepted (envelope but no device secret, permissive): device={}", deviceId)
                    return inner
                }
                raiseForged(deviceId, type, "signed envelope from unknown/secret-less device")
                return null
            }
            if (!UplinkSigning.verify(secret, inner, sig)) {
                raiseForged(deviceId, type, "HMAC signature mismatch")
                return null
            }
            return inner
        }

        // 裸报文
        if (type == "status" && root.has("online") && root.get("online").asBoolean(false) == false) {
            // broker 遗嘱离线，无法签名，豁免接受
            return json
        }
        val secret = deviceService.getHmacSecret(deviceId)
        if (secret == null) {
            // 信封分支在 requireUplinkSignature=true 时会拒收无密钥设备；裸报文必须对齐，
            // 否则只需去掉 s/p 字段即可绕过验签，伪造 event/heartbeat/status 推假告警与假在线。
            if (!requireUplinkSignature) {
                log.warn("uplink accepted unsigned from secret-less device (permissive): device={} type={}", deviceId, type)
                return json
            }
            raiseForged(deviceId, type, "unsigned uplink from secret-less device")
            return null
        }
        // 有密钥却未签名
        if (!requireUplinkSignature) {
            log.warn("uplink accepted unsigned from signed device (permissive): device={} type={}", deviceId, type)
            return json
        }
        raiseForged(deviceId, type, "unsigned uplink from device that has hmacSecret")
        return null
    }

    private fun raiseForged(deviceId: String, type: String, reason: String) {
        log.warn("FORGED UPLINK REJECTED: device={} type={} reason={}", deviceId, type, reason)
        runCatching {
            alertService.create(
                deviceId = deviceId,
                level = "CRITICAL",
                title = "检测到伪造的上行报文",
                message = "设备 $deviceId 的 MQTT 上行($type)签名校验失败：$reason。可能为中间人伪造或端上签名实现不一致，已拒绝处理。",
                category = "FORGED_UPLINK"
            )
        }.onFailure { log.warn("failed to raise FORGED_UPLINK alert: {}", it.message) }
    }

    /**
     * 处理指令回执：MQTT 上行与 HTTP 降级端点共用。
     *
     * **必须同时兼容三种载荷形状**，否则回执会整体失败（历史故障）：
     * 1. 裸回执 `{msgId, status, ...}`（MQTT 上行）
     * 2. 批量包装 `{deviceId, acks:[{...}, {...}]}`（孩子端 HTTP 上报的实际格式）
     * 3. 数组 `[{...}, {...}]`
     *
     * 之前只认第 1 种，孩子端发的第 2 种被强行按 AckPacket 解析 → msgId 缺失 → 抛异常 →
     * HTTP 500。结果是**所有指令的回执全部石沉大海**：家长端只看到"指令发送失败"，
     * 而孩子端其实早已执行成功（锁屏已生效、截图已上传），形成极难定位的"假失败"。
     */
    fun handleAck(deviceId: String, json: String) {
        val root = runCatching { objectMapper.readTree(json) }.getOrNull() ?: return
        val nodes: List<com.fasterxml.jackson.databind.JsonNode> = when {
            root.isArray -> root.toList()
            root.has("acks") && root.get("acks").isArray -> root.get("acks").toList()
            else -> listOf(root)
        }
        for (node in nodes) {
            val ack = runCatching { objectMapper.treeToValue(node, AckPacket::class.java) }.getOrNull()
                ?: continue
            if (ack.msgId.isBlank()) continue
            applyAck(deviceId, ack)
        }
    }

    /**
     * ack 状态机（S6）。三条硬约束：
     *
     * 1. **双键匹配**：按 msgId + deviceId 查指令。只按 msgId 查的话，任何知道别人 msgId
     *    的设备都能伪造回执（MQTT 上行无来源校验），把别人的指令改成 SUCCESS。
     * 2. **白名单**：只接受孩子端契约 [com.padguard.core.data.model.AckStatus] 定义过的
     *    5 种状态（SUCCESS/FAILED/UNSUPPORTED/DUPLICATED/REJECTED）+ 服务端 EXPIRED 之外的
     *    任意垃圾值一律丢弃，避免脏状态进库后家长端轮询逻辑失明。
     * 3. **单向流转**：终态（SUCCESS/UNSUPPORTED/DUPLICATED/REJECTED/EXPIRED）不可再被覆盖。
     *    EXPIRED 是服务端 sweeper 判定的最终结论，之后才慢悠悠到达的回执不能"复活"指令；
     *    FAILED 不算终态 —— 孩子端 FAILED 会带 retryCount<3 重试，允许 FAILED -> SUCCESS 升级。
     */
    private fun applyAck(deviceId: String, ack: AckPacket) {
        val cmd = commandRepository.findByMsgIdAndDeviceId(ack.msgId, deviceId) ?: run {
            log.warn("ack dropped: msgId={} not found on device {}", ack.msgId, deviceId)
            return
        }
        if (ack.status !in ACK_ALLOWED_STATUSES) {
            log.warn("ack dropped: msgId={} illegal status={}", ack.msgId, ack.status)
            return
        }
        if (cmd.status in ACK_TERMINAL_STATUSES) {
            log.info("ack ignored: msgId={} already terminal status={}", ack.msgId, cmd.status)
            return
        }
        // 错误码合并规则与改造前逐字一致：仅当回执确实带错误时才覆盖，
        // 否则沿用读到的旧值（避免把历史 SUCCESS 回执的 errorCode 清掉）。
        val hasError = !ack.errorCode.isNullOrBlank() || !ack.errorMessage.isNullOrBlank()
        val finalErrorCode = if (hasError) ack.errorCode else cmd.errorCode
        val finalErrorMessage = if (hasError) ack.errorMessage else cmd.errorMessage

        // 条件 UPDATE 把「判非终态 + 写回执」合成一条原子语句，由数据库行锁裁决唯一赢家。
        // 改造前的整行 save 会在两类并发下丢更新：MQTT / HTTP 双通道回执互相覆盖，
        // 以及把 sweeper 刚收敛的 EXPIRED 用旧快照复活成 SUCCESS（家长端「过期又成功」）。
        // 只有抢到写入权的那一次才允许连带收敛分发台账、推送 command.result。
        val updated = commandRepository.applyAckIfOpen(
            msgId = ack.msgId,
            deviceId = deviceId, // 用鉴权注入的 deviceId，不信任回执报文里的字段
            terminal = ACK_TERMINAL_STATUSES,
            status = ack.status,
            executedAt = ack.executedAt,
            errorCode = finalErrorCode,
            errorMessage = finalErrorMessage
        )
        if (updated == 0) {
            log.info("ack ignored: msgId={} already advanced by a concurrent writer", ack.msgId)
            return
        }
        // applyAckIfOpen 带 clearAutomatically=true，cmd 已脱离持久化上下文。
        // 此后只读已加载的 type，绝不能再改它的字段，否则脏检查整行写回会让 CAS 白做。
        // R-P2-12：安装类指令的回执同步收敛分发台账（SUCCESS = 已受理 → DISPATCHED，
        // 真正的 SUCCESS 由应用台账上报给出），否则家长端"分发中"永远不闭环。
        if (cmd.type == CommandType.INSTALL_APP) {
            runCatching {
                appDistributionConverger.onCommandAck(
                    msgId = ack.msgId, deviceId = deviceId, ackStatus = ack.status,
                    errorCode = ack.errorCode, errorMessage = ack.errorMessage
                )
            }.onFailure { log.warn("distribution converge failed: msgId={} err={}", ack.msgId, it.message) }
        }
        deviceService.getUserId(deviceId)?.let { userId ->
            webSocketPush.pushToUser(userId, mapOf(
                "type" to "command.result", "deviceId" to deviceId,
                "msgId" to ack.msgId, "status" to ack.status
            ))
        }
    }

    companion object {
        /** 孩子端契约 AckStatus 全集（护航成长 core/data Command.kt:156） */
        val ACK_ALLOWED_STATUSES = setOf("SUCCESS", "FAILED", "UNSUPPORTED", "DUPLICATED", "REJECTED")

        /** 终态：一旦到达不可回退。FAILED 可重试所以不在内；EXPIRED 是服务端 sweeper 结论。 */
        val ACK_TERMINAL_STATUSES = setOf("SUCCESS", "UNSUPPORTED", "DUPLICATED", "REJECTED", "EXPIRED")

        /** 与孩子端 RiskType.TIME_LIMIT_EXCEEDED 逐字符一致 */
        const val EVENT_TIME_LIMIT_EXCEEDED = "TIME_LIMIT_EXCEEDED"

        /**
         * S-4 同款：单次事件批硬上限。事件批与日志批不同——每条 fresh 事件都会
         * 建 1 条告警 + 1 条 alert.new WS 推送，超大批次会把 alerts 表与推送同时打爆
         * （孩子端正常单批仅个位数条）。MQTT 单发通道 [handleEvent] 恒为 1 条不受影响。
         */
        const val MAX_EVENTS_PER_BATCH = 500
    }

    private fun handleHeartbeat(deviceId: String, json: String) {
        val hb = objectMapper.readValue(json, HeartbeatDto::class.java)
        deviceService.applyHeartbeat(deviceId, hb)
    }

    private fun handleEvent(deviceId: String, json: String) {
        val ev = objectMapper.readValue(json, EventPacket::class.java)
        handleEventPackets(deviceId, listOf(ev))
    }

    /**
     * B：HIGH 风险事件实时单发通道（MQTT `alert/realtime` topic，QoS1）。
     *
     * 孩子端 `LogRepository.raise` 对 HIGH 级事件在落库同时，经 MQTT 即时推到本 topic，
     * 让关键告警（如隧道静默失效 WEB_GUARD_DOWN）秒级到达家长端，不再受批量日志窗口（默认 5 分钟）拖累。
     *
     * 复用与 `event` 通道**完全一致**的 [handleEventPackets]：落库 + 建告警 + WS 推送三件事，
     * 保证两路口径永不漂移。eventId 由孩子端全局唯一生成，与 `event` 批量通道去重，
     * 同一事件经两条通道到达也不会重复建警。整段已在 [handleMessageArrived] 的 runCatching 内，
     * 畸形报文只会丢弃、绝不中断共享 MQTT 连接。
     */
    private fun handleRealtimeAlert(deviceId: String, json: String) {
        val ev = runCatching { objectMapper.readValue(json, EventPacket::class.java) }.getOrNull() ?: return
        handleEventPackets(deviceId, listOf(ev))
    }

    /**
     * 风险事件入库 + 告警 + WS 推送 —— **MQTT 与 HTTP 两条通路共用的唯一实现**。
     *
     * 为什么必须共用而不是各写一份：HTTP 是 MQTT 不可用时的**降级**通道。
     * 两边各写一份的话语义迟早漂移（一边落库一边不落、一边告警一边不告警），
     * 真出问题时根本说不清"告警到底为什么丢"。
     *
     * @return (accepted, duplicated)
     */
    fun handleEventPackets(deviceId: String, events: List<EventPacket>): Pair<Int, Int> {
        if (events.isEmpty()) return 0 to 0
        // S-4 同款：每条 fresh 事件 = 1 行告警 + 1 条 WS 推送，超大批次必须整批拒绝。
        // MQTT 通道抛出后由上行分发的外层 catch 记 warn（恶意批次被丢弃，属预期防护）。
        if (events.size > MAX_EVENTS_PER_BATCH) {
            throw BizException(ParentErr.PARAM_ERROR, "事件批量过大（上限 $MAX_EVENTS_PER_BATCH 条）", Audience.CHILD)
        }
        // 结构边界：`device_events.type` 是 VARCHAR(32)，detail 又是整段 JSON 落库。
        // 超长类型会抛非业务异常（500），孩子端不会丢弃、会永久重试队头 ——
        // 必须在校验阶段转成 BizError（400），端上才会认栽丢弃并继续上报后续事件。
        events.forEach { ev ->
            val detailBytes = objectMapper.writeValueAsBytes(ev.detail ?: emptyMap<String, Any?>()).size
            UplinkValidation.eventError(ev, detailBytes)?.let { reason ->
                throw BizException(ParentErr.PARAM_ERROR, "事件非法：$reason", Audience.CHILD)
            }
        }

        // 批内先去重，再一次性查库：避免同一条事件在同批里被处理两次、
        // 也避免逐条 exists 造成的上百次 DB 往返。
        val candidates = events.distinctBy { it.eventId }
        val existing = deviceEventRepository.findExistingEventIds(candidates.map { it.eventId })
        val fresh = candidates.filter { it.eventId !in existing }
        if (fresh.isEmpty()) return 0 to events.size

        // S8：批量写入；并发上报同一 eventId 撞 unique 约束时降级为逐行 ON CONFLICT DO NOTHING。
        // 告警与 WS 推送只作用于**真正落库**的事件，保持「去重作用在整条链路」的语义不变。
        val inserted = try {
            deviceEventRepository.saveAll(fresh.map { ev ->
                DeviceEvent(
                    id = UUID.randomUUID().toString(), deviceId = deviceId, eventId = ev.eventId,
                    type = ev.type, level = ev.level, timestamp = ev.timestamp,
                    detailJson = objectMapper.writeValueAsString(ev.detail ?: emptyMap<String, Any?>())
                )
            })
            fresh
        } catch (e: org.springframework.dao.DataIntegrityViolationException) {
            log.warn("device_events 批量写入撞唯一约束（并发上报竞态），降级逐行 upsert: {}", e.message)
            fresh.filter { ev ->
                deviceEventRepository.insertIgnoreConflict(
                    id = UUID.randomUUID().toString(), deviceId = deviceId, eventId = ev.eventId,
                    type = ev.type, level = ev.level,
                    detailJson = objectMapper.writeValueAsString(ev.detail ?: emptyMap<String, Any?>()),
                    timestamp = ev.timestamp
                ) > 0
            }
        }

        // 高危事件落告警并推 WS。
        // 去重作用在**整条链路**上，而不是只挡住落库：MQTT QoS1 与 HTTP 重试都会让同一条
        // 事件重复到达，只挡落库却照常告警的话，一次重传就给家长推一条重复告警。
        // 推送只由 createFromEvent 内部发出（带完整 Alert DTO）；此前这里额外推一条只有
        // deviceId/eventType/level 的精简 alert.new，导致家长端每条事件收到两条同 type 的 WS。
        inserted.forEach { ev ->
            alertService.createFromEvent(deviceId, ev.type, ev.level, ev.detail)
        }
        return inserted.size to (events.size - inserted.size)
    }

    private fun handleStatus(deviceId: String, json: String) {
        val online = (objectMapper.readValue(json, Map::class.java)["online"] as? Boolean) ?: return
        if (online) {
            deviceService.applyHeartbeatOnline(deviceId)
        } else {
            // 这一路的 offline 多为 MQTT 遗嘱（连接断了，不代表设备没了）。
            // 由 DeviceService 结合 presence 判断是否为真离线，避免状态反复横跳。
            deviceService.handleBrokerOffline(deviceId)
        }
    }

    override fun disconnected(disconnectResponse: MqttDisconnectResponse?) {
        log.warn("MQTT disconnected: ${disconnectResponse?.reasonString}")
    }

    override fun mqttErrorOccurred(exception: MqttException?) {
        log.warn("MQTT error occurred: ${exception?.message}")
    }

    override fun authPacketArrived(reasonCode: Int, properties: MqttProperties?) {}

    override fun deliveryComplete(token: IMqttToken?) {}

    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
        log.info("MQTT connectComplete reconnect=$reconnect uri=$serverURI")
    }
}
