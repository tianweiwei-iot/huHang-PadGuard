package com.padguard.core.transport

import com.padguard.core.common.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** 当前生效的上行通道（心跳里上报，服务端据此感知降级设备） */
enum class TransportMode {
    /** MQTT 长连接（首选） */
    MQTT,

    /** HTTPS 轮询（MQTT 被校园防火墙封端口时的降级通道） */
    POLLING
}

/**
 * 传输层运行参数。
 *
 * 三个来源，优先级由低到高：
 * 1. 构建期 BuildConfig（默认值）
 * 2. 绑定响应中服务端指定的接入点（多租户/私有化部署时不同域名）
 * 3. 远程 UPDATE_CONFIG 指令 / 调试页手动覆盖
 *
 * 之所以做成可变的运行时配置而不是编译常量：
 * 服务端尚未就绪，需要在同一个 APK 里在「Mock 数据」与「真实接口」之间来回切换验证；
 * 上线后同一能力用于灰度切换接入点，避免为改域名重新发包。
 */
@Singleton
class TransportSettings @Inject constructor() {

    private val _baseUrl = MutableStateFlow(normalizeBaseUrl(BuildConfig.DEFAULT_BASE_URL))
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    private val _mqttBroker = MutableStateFlow(BuildConfig.DEFAULT_MQTT_BROKER)
    val mqttBroker: StateFlow<String> = _mqttBroker.asStateFlow()

    private val _useMock = MutableStateFlow(BuildConfig.USE_MOCK_SERVER)

    /**
     * 是否走本地 Mock。
     * release 构建下 BuildConfig.USE_MOCK_SERVER 恒为 false，且 [setUseMock] 会被忽略，
     * 防止正式包被误切到 Mock 而造成"看起来在管控、实际没有连服务端"的假象。
     */
    val useMock: StateFlow<Boolean> = _useMock.asStateFlow()

    private val _mode = MutableStateFlow(TransportMode.MQTT)
    val mode: StateFlow<TransportMode> = _mode.asStateFlow()

    private val _heartbeatIntervalSec = MutableStateFlow(DEFAULT_HEARTBEAT_SEC)
    val heartbeatIntervalSec: StateFlow<Int> = _heartbeatIntervalSec.asStateFlow()

    private val _pollingIntervalSec = MutableStateFlow(DEFAULT_POLLING_SEC)
    val pollingIntervalSec: StateFlow<Int> = _pollingIntervalSec.asStateFlow()

    private val _logUploadIntervalSec = MutableStateFlow(DEFAULT_LOG_UPLOAD_SEC)
    val logUploadIntervalSec: StateFlow<Int> = _logUploadIntervalSec.asStateFlow()

    fun setBaseUrl(url: String) {
        if (url.isBlank()) return
        _baseUrl.value = normalizeBaseUrl(url)
        Logger.i(TAG) { "baseUrl -> ${_baseUrl.value}" }
    }

    fun setMqttBroker(uri: String) {
        if (uri.isBlank()) return
        _mqttBroker.value = uri
        Logger.i(TAG) { "mqttBroker -> $uri" }
    }

    fun setUseMock(enabled: Boolean) {
        if (!BuildConfig.DEBUG && enabled) {
            Logger.w(TAG) { "refuse to enable mock transport in release build" }
            return
        }
        _useMock.value = enabled
        Logger.i(TAG) { "useMock -> $enabled" }
    }

    /** MQTT 连接反复失败时降级为轮询；恢复后由 [MqttTransport] 主动切回 */
    fun setMode(mode: TransportMode) {
        if (_mode.value == mode) return
        _mode.value = mode
        Logger.w(TAG) { "transport mode -> $mode" }
    }

    /** 服务端 monitoring 策略或 UPDATE_CONFIG 指令下发的周期参数，均做区间钳制防止被恶意配置打爆 */
    fun applyRemoteConfig(
        heartbeatSec: Int? = null,
        pollingSec: Int? = null,
        logUploadSec: Int? = null
    ) {
        heartbeatSec?.let { _heartbeatIntervalSec.value = it.coerceIn(MIN_HEARTBEAT_SEC, MAX_HEARTBEAT_SEC) }
        pollingSec?.let { _pollingIntervalSec.value = it.coerceIn(MIN_POLLING_SEC, MAX_POLLING_SEC) }
        logUploadSec?.let { _logUploadIntervalSec.value = it.coerceIn(MIN_LOG_UPLOAD_SEC, MAX_LOG_UPLOAD_SEC) }
        Logger.i(TAG) {
            "remote config applied: heartbeat=${_heartbeatIntervalSec.value}s " +
                "polling=${_pollingIntervalSec.value}s logUpload=${_logUploadIntervalSec.value}s"
        }
    }

    private fun normalizeBaseUrl(url: String): String = if (url.endsWith("/")) url else "$url/"

    companion object {
        private const val TAG = "TransportSettings"

        const val DEFAULT_HEARTBEAT_SEC = 30
        const val DEFAULT_POLLING_SEC = 60
        const val DEFAULT_LOG_UPLOAD_SEC = 300

        // 契约 §4.3：心跳 5s–300s 可远程配置
        private const val MIN_HEARTBEAT_SEC = 5
        private const val MAX_HEARTBEAT_SEC = 300
        private const val MIN_POLLING_SEC = 15
        private const val MAX_POLLING_SEC = 600
        private const val MIN_LOG_UPLOAD_SEC = 30
        private const val MAX_LOG_UPLOAD_SEC = 3600
    }
}
