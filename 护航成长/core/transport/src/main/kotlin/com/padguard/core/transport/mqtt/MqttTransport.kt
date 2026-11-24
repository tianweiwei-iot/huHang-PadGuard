package com.padguard.core.transport.mqtt

import android.content.Context
import com.padguard.core.common.Logger
import com.padguard.core.transport.BuildConfig
import com.padguard.core.transport.TransportMode
import com.padguard.core.transport.TransportSettings
import com.padguard.core.transport.UplinkSigner
import com.padguard.core.transport.http.CredentialStore
import com.padguard.core.transport.security.CertPinTrustManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
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
import java.util.concurrent.atomic.AtomicInteger
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
    /**
     * 当前 [client] 是用哪个 broker 地址创建的。
     *
     * 必须有这个记录：`transportSettings` 支持服务端远程下发，broker 地址是会在运行时变的。
     * 只判 `client != null` 就复用，会让"换 broker"永远不生效 ——
     * 旧地址可达就连到弃用节点，不可达就每轮 connect 失败、永久降级 POLLING，
     * 进程重启前新地址永远不生效；而日志仍按新地址打印，排障时会被彻底误导。
     */
    private var clientBroker: String? = null
    private var pingSender: AlarmPingSender? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /**
     * 下行消息流。replay = 0 且 extraBufferCapacity 留足，
     * 避免上层来不及消费时把 Paho 的回调线程堵住（Paho 回调线程被堵会连带停掉心跳）。
     *
     * 第八轮 P2-6 优化：缓冲满时由"静默丢弃新消息"改为 **DROP_OLDEST**——
     * 指令队列挤爆时丢最旧的（通常是早已被新状态覆盖的过期指令，如连续多次 applyPolicy），
     * 保留最新的。静默丢新的风险场景：孩子离线期间堆积的指令重放 + 高频心跳同时到达，
     * 丢掉新指令意味着"最新锁屏指令没执行"，比丢旧指令严重得多。
     */
    private val _inbound = MutableSharedFlow<MqttInbound>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val inbound: SharedFlow<MqttInbound> = _inbound.asSharedFlow()

    private val consecutiveFailures = AtomicInteger(0)

    /**
     * 建立连接。幂等：已连接时直接返回 true。
     * @return 是否连接成功
     */
    suspend fun connect(): Boolean = connectMutex.withLock {
        if (!credentials.isBound) {
            Logger.d(TAG) { "skip mqtt connect: device not bound yet" }
            return false
        }
        val deviceId = credentials.deviceId
        val tenant = credentials.tenantId
        val broker = settings.mqttBroker.value

        // 快速路径必须同时比对 clientBroker：broker 地址运行时会被服务端下发变更，
        // 只判 isConnected 的话，旧 broker 仍可达时 connect() 直接短路返回 true，
        // 新地址**进程重启前永远不生效**——正是 clientBroker 字段注释要防的场景，
        // 原先只是漏了把快速路径也纳入该检查。
        if (client?.isConnected == true && clientBroker == broker) return true

        return try {
            withContext(Dispatchers.IO) {
                // broker 与缓存不一致时**必须重建**：Paho 的 broker 地址在构造时绑定，
                // 复用旧实例等于继续连旧地址（详见 clientBroker 注释）。
                val mqttClient = client?.takeIf { clientBroker == broker } ?: run {
                    client?.let { stale -> runCatching { stale.close(true) }.onFailure { Logger.w(TAG, it) { "mqtt stale client close failed" } } }
                    createClient(broker, deviceId).also {
                        client = it
                        clientBroker = broker
                    }
                }
                val options = buildConnectOptions(tenant, deviceId, broker)
                awaitToken("connect") { listener -> mqttClient.connect(options, null, listener) }
                subscribeAll(mqttClient, tenant, deviceId)
                publishOnlineStatus(tenant, deviceId)
            }
            consecutiveFailures.set(0)
            _connected.value = true
            settings.setMode(TransportMode.MQTT)
            Logger.i(TAG) { "mqtt connected: $broker" }
            true
        } catch (e: CancellationException) {
            // 取消不是连接失败：服务停止 / 换账号 / 网络恢复重连都会取消本 suspend，
            // 若计入 consecutiveFailures，几次取消就能把通道永久降级到 POLLING，
            // 且该计数只在下次成功 connect 时才清零（见成功分支）。结构化并发纪律：原样上抛。
            throw e
        } catch (e: Exception) {
            _connected.value = false
            val failures = consecutiveFailures.incrementAndGet()
            Logger.w(TAG, e) { "mqtt connect failed (#$failures)" }
            if (failures >= MAX_FAILURE_BEFORE_DEGRADE) {
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
                // R-P0-3：上行应用层签名。有 hmacSecret 时把 payload 包成带 HMAC 的信封，
                // 无密钥（未绑定 / mock）时退回明文。遗嘱离线报文不走这里（见 buildConnectOptions），
                // 服务端对"未签名且 online=false"的 status 单独豁免。
                val out = UplinkSigner.wrap(payload, credentials.hmacSecret) ?: payload
                val message = MqttMessage(out.toByteArray()).apply {
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
        // 重建客户端前先显式停止旧 ping sender（其持有的 AlarmManager 闹钟 + 广播接收器），
        // 否则 broker 变更 / 反复重连会累积旧的定时 PINGREQ 与接收器（资源泄漏 + 重复心跳）。
        // disconnect() 已走同一 stop() 范式，这里补齐「仅重建、未 disconnect」分支。
        pingSender?.stop()
        val sender = AlarmPingSender(context).also { pingSender = it }
        return MqttAsyncClient(
            normalizeBrokerUri(broker),
            CLIENT_ID_PREFIX + deviceId,
            MqttDefaultFilePersistence(persistenceDir.absolutePath),
            sender
        ).apply {
            setCallback(createCallback())
        }
    }

    /**
     * 第八轮评估 P1-C：TLS 支持。
     *
     * 协议归一化：允许配置里写更直观的别名——
     * - `mqtts://host:8883` → `ssl://host:8883`（Paho 的 TLS 协议名是 ssl）
     * - `mqtt://host:1883`  → `tcp://host:1883`
     *
     * TLS 信任策略：走**系统 CA**（SSLContext 默认），与 OkHttp 侧一致——
     * 公网部署用 Let's Encrypt/商业证书即天然可信；家庭局域网保持 tcp 明文（隐私政策已告知）。
     * 自签名 CA 的私有化部署需在客户端分发 CA 证书后注入自定义 TrustManager，
     * 与服务端 `scripts/gen-certs.sh` 的证书一起联调（第二批任务，见第八轮评估报告）。
     */
    private fun normalizeBrokerUri(broker: String): String = when {
        broker.startsWith("mqtts://") -> "ssl://" + broker.removePrefix("mqtts://")
        broker.startsWith("mqtt://") -> "tcp://" + broker.removePrefix("mqtt://")
        else -> broker
    }

    private fun isTlsBroker(broker: String): Boolean =
        broker.startsWith("ssl://") || broker.startsWith("mqtts://") || broker.startsWith("tls://")

    /**
     * TLS 套接字工厂（第八轮第二批：自签名 CA 支持）。
     *
     * 信任策略按部署形态分两档：
     * 1. `filesDir/mqtt/ca.pem` 存在 → 以该 CA 为唯一信任锚（私有化自签名部署：
     *    随 `scripts/gen-certs.sh` 生成的根证书在装机时一并放进该路径）；
     * 2. 不存在 → 系统 CA（公网部署用 Let's Encrypt/商业证书，免配置）。
     *
     * 任何构建失败都降级回系统默认工厂并告警，绝不让 TLS 配置问题变成"连不上 broker"的哑故障。
     */
    private fun buildSocketFactory(): javax.net.ssl.SSLSocketFactory =
        runCatching {
            val caFile = File(context.filesDir, "mqtt/ca.pem")
            if (caFile.isFile) {
                val cf = java.security.cert.CertificateFactory.getInstance("X.509")
                val ca = caFile.inputStream().use { cf.generateCertificate(it) }
                val keyStore = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType())
                    .apply { load(null, null); setCertificateEntry("padguard-ca", ca) }
                val tmf = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
                    .apply { init(keyStore) }
                val sslCtx = javax.net.ssl.SSLContext.getInstance("TLS")
                sslCtx.init(null, tmf.trustManagers, null)
                Logger.i(TAG) { "mqtt tls: trusting provisioned CA (${caFile.name})" }
                sslCtx.socketFactory
            } else {
                // P0-1 证书固定（与 OkHttp 同源）：静态 pin 来自 BuildConfig.CERT_PINS；
                // 为空时走 TOFU 兜底（首次可信握手记下公钥，未知签发者即拒），
                // 阻断导入自定义 CA 的主动中间人对 MQTT 指令通道的解密观测。
                CertPinTrustManager.sslContext(
                    context,
                    CertPinTrustManager.parsePins(BuildConfig.CERT_PINS)
                ).socketFactory
            }
        }.getOrElse {
            Logger.w(TAG, it) { "mqtt tls socket factory build failed, falling back to system default" }
            javax.net.ssl.SSLContext.getDefault().socketFactory
        }

    private fun buildConnectOptions(tenant: String, deviceId: String, broker: String) = MqttConnectOptions().apply {
        userName = credentials.mqttUsername.ifBlank { deviceId }
        password = credentials.mqttPassword.toCharArray()
        // false：保留离线期间的 QoS1 指令，见类注释
        isCleanSession = false
        keepAliveInterval = KEEP_ALIVE_SEC
        connectionTimeout = CONNECT_TIMEOUT_SEC
        isAutomaticReconnect = true
        maxInflight = MAX_INFLIGHT
        if (isTlsBroker(normalizeBrokerUri(broker))) {
            socketFactory = buildSocketFactory()
        }
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
            consecutiveFailures.set(0)
            if (!reconnect) return
            // 自动重连不会恢复订阅关系，必须手动补订，否则表现为"在线但收不到指令"
            scope.launch {
                val mqttClient = client ?: return@launch
                val tenant = credentials.tenantId
                val deviceId = credentials.deviceId
                runCatching {
                    subscribeAll(mqttClient, tenant, deviceId)
                    publishOnlineStatus(tenant, deviceId)
                }.onSuccess {
                    // Paho 自动重连成功且订阅已恢复：必须主动切回 MQTT。
                    // 轮询循环里 connect() 命中 isConnected 快速路径时不会再调 setMode(MQTT)，
                    // 少了这一步 mode 会永久停在 POLLING：心跳上报错报通道，且 HTTP 端 fatal 时
                    // isOnline() 会把一条仍能收发指令的设备误判为整机离线。
                    settings.setMode(TransportMode.MQTT)
                    Logger.i(TAG) { "mqtt reconnected to $serverURI, subscriptions restored, back to mqtt mode" }
                }.onFailure { error ->
                    // 自动重连成功但订阅没恢复 = "在线却收不到指令"。立即降级到轮询，
                    // 否则 MQTT 与 HTTP 两条通道同时空窗，家长端只能看到设备永久离线。
                    Logger.w(TAG, error) { "resubscribe after reconnect failed, falling back to polling" }
                    settings.setMode(TransportMode.POLLING)
                }
            }
        }

        override fun connectionLost(cause: Throwable?) {
            _connected.value = false
            // Paho 的 isAutomaticReconnect 只负责传输层重试：broker 长时间不可达时，
            // 旧实现 mode 仍停在 MQTT，而 HTTP 轮询循环只在 POLLING 下运行 ——
            // 两条下行通道一起停摆且不会自愈。先切 POLLING 让存活信号/指令拉取接管，
            // 轮询循环内的限频 connect() 成功后再由 connect() 切回 MQTT。
            settings.setMode(TransportMode.POLLING)
            Logger.w(TAG, cause) { "mqtt connection lost, transport degraded to polling" }
        }

        override fun messageArrived(topic: String?, message: MqttMessage?) {
            if (topic == null || message == null) return
            val inbound = MqttInbound(
                topic = topic,
                kind = MqttTopics.classify(topic),
                payload = String(message.payload),
                receivedAt = System.currentTimeMillis()
            )
            // DROP_OLDEST 策略下 tryEmit 恒成功（见 _inbound 注释），此处不再有静默丢新消息的路径
            _inbound.tryEmit(inbound)
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

    /** 把 Paho 的 listener 回调桥接成挂起函数。返回值无消费方，签名即 Unit。 */
    private suspend fun awaitToken(
        opName: String,
        block: (IMqttActionListener) -> Unit
    ) {
        suspendCancellableCoroutine { cont: CancellableContinuation<Unit> ->
            val listener = object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    // IMqttActionListener 契约允许 token 为 null（MqttAsyncClient 的
                    // userContext 回调路径就会传 null）。原实现 `cont.resume(token ?: return)`
                    // 在 token 为 null 时**既不 resume 也不 fail**：continuation 永久悬挂，
                    // 而调用方（connect/disconnect）持着 connectMutex —— 一次命中即
                    // 后续所有 connect() 永久卡死。resume Unit 即可，token 本就无人使用。
                    if (cont.isActive) cont.resume(Unit)
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
