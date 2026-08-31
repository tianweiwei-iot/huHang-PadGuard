package com.padguard.core.transport.mqtt

import android.content.Context
import com.padguard.core.common.Logger
import com.padguard.core.transport.TransportMode
import com.padguard.core.transport.TransportSettings
import com.padguard.core.transport.http.CredentialStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MqttDefaultFilePersistence
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * MQTT 实时通道（契约 §3）。
 *
 * 设计要点：
 * - **CleanSession = false**：设备离线期间服务端下发的 QoS1 指令会在 Broker 侧堆积，
 *   重连后补投。这是"晚上断网、早上开机仍能收到昨晚锁屏指令"的基础。
 * - **遗嘱消息（LWT）**：进程被杀 / 网络硬断时由 Broker 代发离线状态，
 *   管控端不必等 KeepAlive 超时才知道设备掉线，也避免了"被强杀后管控端仍显示在线"的假在线。
 * - **自动重连 + AlarmPingSender**：见 [AlarmPingSender] 的注释。
 * - **连续失败降级**：达到 [MAX_FAILURE_BEFORE_DEGRADE] 次后切 [TransportMode.POLLING]，
 *   由上层改走 HTTPS 轮询，保证校园防火墙封 1883/8883 时管控不断链。
 *
 * 本类只负责"连接与收发字节"，不理解业务语义；签名校验、幂等、指令执行都在上层。
 */
@Singleton
class MqttTransport @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: TransportSettings,
    private val credentials: CredentialStore
) {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val connectMutex = Mutex()

    private var client: MqttAsyncClient? = null
    private var pingSender: AlarmPingSender? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /**
     * 下行消息流。replay = 0 且 extraBufferCapacity 留足，
     * 避免上层来不及消费时把 Paho 的回调线程堵住（Paho 回调线程被堵会连带停掉心跳）。
     */
    private val _inbound = MutableSharedFlow<MqttInbound>(extraBufferCapacity = 128)
    val inbound: SharedFlow<MqttInbound> = _inbound.asSharedFlow()

    @Volatile
    private var consecutiveFailures = 0

    /**
     * 建立连接。幂等：已连接时直接返回 true。
     * @return 是否连接成功
     */
    suspend fun connect(): Boolean = connectMutex.withLock {
        if (!credentials.isBound) {
            Logger.d(TAG) { "skip mqtt connect: device not bound yet" }
            return false
        }
        if (client?.isConnected == true) return true

        val deviceId = credentials.deviceId
        val tenant = credentials.tenantId
        val broker = settings.mqttBroker.value

        return try {
            withContext(Dispatchers.IO) {
                val mqttClient = client ?: createClient(broker, deviceId).also { client = it }
                val options = buildConnectOptions(tenant, deviceId)
                awaitToken("connect") { listener -> mqttClient.connect(options, null, listener) }
                subscribeAll(mqttClient, tenant, deviceId)
                publishOnlineStatus(tenant, deviceId)
            }
            consecutiveFailures = 0
            _connected.value = true
            settings.setMode(TransportMode.MQTT)
            Logger.i(TAG) { "mqtt connected: $broker" }
            true
        } catch (e: Exception) {
            _connected.value = false
            consecutiveFailures++
            Logger.w(TAG, e) { "mqtt connect failed (#$consecutiveFailures)" }
            if (consecutiveFailures >= MAX_FAILURE_BEFORE_DEGRADE) {
                settings.setMode(TransportMode.POLLING)
            }
            false
        }
    }

    suspend fun disconnect() = connectMutex.withLock {
        val mqttClient = client ?: return
        runCatching {
            withContext(Dispatchers.IO) {
                if (mqttClient.isConnected) {
                    awaitToken("disconnect") { listener -> mqttClient.disconnect(null, listener) }
                }
                mqttClient.close(true)
            }
        }.onFailure { Logger.w(TAG, it) { "mqtt disconnect failed" } }
        pingSender?.stop()
        client = null
        pingSender = null
        _connected.value = false
    }

    /**
     * 发布消息。
     * @return true 表示已交给 Paho（QoS1 下 Paho 会持久化并保证最终投递）
     */
    suspend fun publish(topic: String, payload: String, qos: Int, retain: Boolean = false): Boolean {
        val mqttClient = client
        if (mqttClient == null || !mqttClient.isConnected) return false
        return runCatching {
            withContext(Dispatchers.IO) {
                val message = MqttMessage(payload.toByteArray()).apply {
                    this.qos = qos
                    isRetained = retain
                }
                mqttClient.publish(topic, message)
            }
            true
        }.getOrElse {
            Logger.w(TAG, it) { "mqtt publish failed: $topic" }
            false
        }
    }

    // ==================== 内部实现 ====================

    private fun createClient(broker: String, deviceId: String): MqttAsyncClient {
        val persistenceDir = File(context.filesDir, PERSISTENCE_DIR).apply { mkdirs() }
        val sender = AlarmPingSender(context).also { pingSender = it }
        return MqttAsyncClient(
            broker,
            CLIENT_ID_PREFIX + deviceId,
            MqttDefaultFilePersistence(persistenceDir.absolutePath),
            sender
        ).apply {
            setCallback(createCallback())
        }
    }

    private fun buildConnectOptions(tenant: String, deviceId: String) = MqttConnectOptions().apply {
        userName = credentials.mqttUsername.ifBlank { deviceId }
        password = credentials.mqttPassword.toCharArray()
        // false：保留离线期间的 QoS1 指令，见类注释
        isCleanSession = false
        keepAliveInterval = KEEP_ALIVE_SEC
        connectionTimeout = CONNECT_TIMEOUT_SEC
        isAutomaticReconnect = true
        maxInflight = MAX_INFLIGHT
        // 遗嘱消息 retain = true，管控端订阅即可拿到最后已知状态
        setWill(
            MqttTopics.upStatus(tenant, deviceId),
            OFFLINE_PAYLOAD.toByteArray(),
            MqttTopics.QOS_RELIABLE,
            true
        )
    }

    private fun createCallback() = object : MqttCallbackExtended {

        override fun connectComplete(reconnect: Boolean, serverURI: String?) {
            _connected.value = true
            consecutiveFailures = 0
            if (!reconnect) return
            // 自动重连不会恢复订阅关系，必须手动补订，否则表现为"在线但收不到指令"
            scope.launch {
                val mqttClient = client ?: return@launch
                val tenant = credentials.tenantId
                val deviceId = credentials.deviceId
                runCatching {
                    subscribeAll(mqttClient, tenant, deviceId)
                    publishOnlineStatus(tenant, deviceId)
                }.onFailure { Logger.w(TAG) { "resubscribe after reconnect failed: ${it.message}" } }
                Logger.i(TAG) { "mqtt reconnected to $serverURI, subscriptions restored" }
            }
        }

        override fun connectionLost(cause: Throwable?) {
            _connected.value = false
            Logger.w(TAG, cause) { "mqtt connection lost" }
        }

        override fun messageArrived(topic: String?, message: MqttMessage?) {
            if (topic == null || message == null) return
            val inbound = MqttInbound(
                topic = topic,
                kind = MqttTopics.classify(topic),
                payload = String(message.payload),
                receivedAt = System.currentTimeMillis()
            )
            // tryEmit 不挂起，保证 Paho 回调线程立即返回
            if (!_inbound.tryEmit(inbound)) {
                Logger.w(TAG) { "inbound buffer full, dropped message from $topic" }
            }
        }

        override fun deliveryComplete(token: org.eclipse.paho.client.mqttv3.IMqttDeliveryToken?) = Unit
    }

    private suspend fun subscribeAll(mqttClient: MqttAsyncClient, tenant: String, deviceId: String) {
        val subscriptions = MqttTopics.subscriptions(tenant, deviceId)
        awaitToken("subscribe") { listener ->
            mqttClient.subscribe(
                subscriptions.map { it.first }.toTypedArray(),
                subscriptions.map { it.second }.toIntArray(),
                null,
                listener
            )
        }
    }

    private suspend fun publishOnlineStatus(tenant: String, deviceId: String) {
        publish(MqttTopics.upStatus(tenant, deviceId), ONLINE_PAYLOAD, MqttTopics.QOS_RELIABLE, retain = true)
    }

    /** 把 Paho 的 listener 回调桥接成挂起函数 */
    private suspend fun awaitToken(
        opName: String,
        block: (IMqttActionListener) -> Unit
    ): IMqttToken = suspendCancellableCoroutine { cont: CancellableContinuation<IMqttToken> ->
        val listener = object : IMqttActionListener {
            override fun onSuccess(asyncActionToken: IMqttToken?) {
                if (cont.isActive) cont.resume(asyncActionToken ?: return)
            }

            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                if (cont.isActive) {
                    cont.resumeWith(
                        Result.failure(exception ?: IllegalStateException("mqtt $opName failed"))
                    )
                }
            }
        }
        runCatching { block(listener) }.onFailure {
            if (cont.isActive) cont.resumeWith(Result.failure(it))
        }
    }

    companion object {
        private const val TAG = "MqttTransport"
        private const val CLIENT_ID_PREFIX = "pgc_"
        private const val PERSISTENCE_DIR = "mqtt"

        /**
         * 90s：与契约 §3.1 一致。
         * 不能更短 —— setExactAndAllowWhileIdle 在 Doze 下有 9 分钟限流，
         * KeepAlive 设得过短只会让 Broker 频繁判超时，反而更不稳。
         */
        private const val KEEP_ALIVE_SEC = 90
        private const val CONNECT_TIMEOUT_SEC = 20
        private const val MAX_INFLIGHT = 20

        /** 连续失败达到此次数即降级为 HTTPS 轮询 */
        private const val MAX_FAILURE_BEFORE_DEGRADE = 3

        private const val ONLINE_PAYLOAD = """{"online":true}"""
        private const val OFFLINE_PAYLOAD = """{"online":false}"""
    }
}
