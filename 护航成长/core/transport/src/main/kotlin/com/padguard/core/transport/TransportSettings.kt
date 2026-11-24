package com.padguard.core.transport

import android.app.Application
import android.content.SharedPreferences
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
class TransportSettings @Inject constructor(
    private val application: Application
) {
    private val prefs: SharedPreferences =
        application.getSharedPreferences("padguard_child_transport", android.content.Context.MODE_PRIVATE)

    private val _baseUrl =
        MutableStateFlow(normalizeBaseUrl(initialSafeBaseUrl()))
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    /** 启动时从存储读出 baseUrl，但不信任旧值：过不了安全闸门的一律丢弃并回退到构建期默认值。 */
    private fun initialSafeBaseUrl(): String {
        val stored = prefs.getString(KEY_BASE_URL, null)
        if (stored.isNullOrBlank()) return BuildConfig.DEFAULT_BASE_URL
        val verdict = EndpointSafety.check(stored, BuildConfig.ALLOW_CLEARTEXT_PUBLIC)
        return when (verdict) {
            is Verdict.Allowed -> verdict.normalizedUrl
            is Verdict.Rejected -> {
                Logger.w(TAG) { "dropped unsafe stored baseUrl, fall back to default: ${verdict.reason}" }
                BuildConfig.DEFAULT_BASE_URL
            }
        }
    }

    /**
     * 启动时从存储读出 broker，与 [initialSafeBaseUrl] 同口径：
     * 过不了 [EndpointSafety.checkMqtt] 闸门的旧值一律丢弃并回退到构建期默认值。
     * 此前 [setMqttBroker] 只改内存不落盘，导致绑定响应下发的 broker 覆盖值在重启后丢失、
     * 设备悄悄回连构建期默认 broker —— 与 baseUrl 的行为不一致，正是私有化部署"换服务器后重启即脱管"的根因。
     */
    private val _mqttBroker = MutableStateFlow(initialSafeMqttBroker())
    val mqttBroker: StateFlow<String> = _mqttBroker.asStateFlow()

    private fun initialSafeMqttBroker(): String {
        val stored = prefs.getString(KEY_MQTT_BROKER, null)
        if (stored.isNullOrBlank()) return BuildConfig.DEFAULT_MQTT_BROKER
        val verdict = EndpointSafety.checkMqtt(stored, BuildConfig.ALLOW_CLEARTEXT_PUBLIC)
        return when (verdict) {
            is Verdict.Allowed -> stored
            is Verdict.Rejected -> {
                Logger.w(TAG) { "dropped unsafe stored mqttBroker, fall back to default: ${verdict.reason}" }
                BuildConfig.DEFAULT_MQTT_BROKER
            }
        }
    }

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

    /**
     * 锁屏上滑逃逸后重新抢占前台的延迟（毫秒）。
     *
     * 用于"上滑返回桌面间隙"治理：值越小，孩子上滑后越快被拉回锁屏页，越接近"无感"；
     * 设为 0 表示下一帧即抢占。默认 [DEFAULT_REARM_DELAY_MS]，经远程配置可调，
     * 无需发版即可把间隙压到接近 0。仅 DO（设备所有者）模式下才能真正零间隙
     * （无桌面可滑），非 DO 模式下此值越小越逼近但无法绝对为零。
     */
    private val _rearmDelayMs =
        MutableStateFlow(prefs.getInt(KEY_REARM_DELAY_MS, DEFAULT_REARM_DELAY_MS))
    val rearmDelayMs: StateFlow<Int> = _rearmDelayMs.asStateFlow()

    /**
     * 设置上行 HTTP 接入点。
     *
     * **硬性安全闸门**：任何写入都先过 [EndpointSafety.check]。
     * 公网明文（http:// 公网 IP/域名）一律拒绝、且**不落盘** —— 孩子端平板没有键盘，
     * 一旦被手填成公网 http，令牌与儿童数据就会明文过公网；这条闸门让"明文公网"在代码层面不可能发生，
     * 同时不牺牲内网私有化部署（10/172.16~31/192.168/127/169.254 等仍放行）。
     *
     * 返回的 [Verdict] 让调用方（高级设置弹窗等）能就地把拒绝原因展示给用户；
     * 调用方若忽略返回值，危险地址也绝不会真正生效（仅此一处写入）。
     */
    fun setBaseUrl(url: String): Verdict {
        if (url.isBlank()) return Verdict.Rejected("服务器地址不能为空")
        return when (val verdict = EndpointSafety.check(url, BuildConfig.ALLOW_CLEARTEXT_PUBLIC)) {
            is Verdict.Rejected -> {
                Logger.w(TAG) { "refuse baseUrl (unsafe): ${verdict.reason}" }
                verdict
            }
            is Verdict.Allowed -> {
                _baseUrl.value = verdict.normalizedUrl
                prefs.edit().putString(KEY_BASE_URL, verdict.normalizedUrl).apply()
                Logger.i(TAG) { "baseUrl -> ${verdict.normalizedUrl} (${verdict.level})" }
                verdict
            }
        }
    }

    /**
     * 设置 MQTT broker URI。
     *
     * 同样过 [EndpointSafety.checkMqtt]：明文 `tcp://`/`ws://` 仅放行内网，
     * 公网一律拒绝且不落盘。注意 broker URI 也可能来自**服务端绑定响应下发**
     * （[com.padguard.core.transport.RealRemoteDataSource]），因此这里是一道必要的纵深防御 ——
     * 即便服务端被篡改指向公网明文 broker，孩子端也不会把设备令牌明文交出去。
     */
    fun setMqttBroker(uri: String): Verdict {
        if (uri.isBlank()) return Verdict.Rejected("MQTT 服务器地址不能为空")
        return when (val verdict = EndpointSafety.checkMqtt(uri, BuildConfig.ALLOW_CLEARTEXT_PUBLIC)) {
            is Verdict.Rejected -> {
                Logger.w(TAG) { "refuse mqttBroker (unsafe): ${verdict.reason}" }
                verdict
            }
            is Verdict.Allowed -> {
                _mqttBroker.value = uri
                // 与 baseUrl 同口径落盘：broker 覆盖值（绑定响应下发 / 调试页手动覆盖）必须跨重启保留，
                // 否则私有化部署"换服务器后重启"会悄悄回连构建期默认 broker 而脱管。
                prefs.edit().putString(KEY_MQTT_BROKER, uri).apply()
                Logger.i(TAG) { "mqttBroker -> $uri (${verdict.level})" }
                verdict
            }
        }
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

    /**
     * 设置上滑逃逸后重新抢占的延迟（毫秒），持久化并按安全区间钳制。
     * 允许调试页/远程配置把间隙压到接近 0。
     */
    fun setRearmDelayMs(ms: Int) {
        val clamped = ms.coerceIn(MIN_REARM_DELAY_MS, MAX_REARM_DELAY_MS)
        _rearmDelayMs.value = clamped
        prefs.edit().putInt(KEY_REARM_DELAY_MS, clamped).apply()
        Logger.i(TAG) { "rearmDelayMs -> $clamped" }
    }

    /** 服务端 monitoring 策略或 UPDATE_CONFIG 指令下发的周期参数，均做区间钳制防止被恶意配置打爆 */
    fun applyRemoteConfig(
        heartbeatSec: Int? = null,
        pollingSec: Int? = null,
        logUploadSec: Int? = null,
        rearmDelayMs: Int? = null
    ) {
        heartbeatSec?.let { _heartbeatIntervalSec.value = it.coerceIn(MIN_HEARTBEAT_SEC, MAX_HEARTBEAT_SEC) }
        pollingSec?.let { _pollingIntervalSec.value = it.coerceIn(MIN_POLLING_SEC, MAX_POLLING_SEC) }
        logUploadSec?.let { _logUploadIntervalSec.value = it.coerceIn(MIN_LOG_UPLOAD_SEC, MAX_LOG_UPLOAD_SEC) }
        rearmDelayMs?.let { setRearmDelayMs(it) }
        Logger.i(TAG) {
            "remote config applied: heartbeat=${_heartbeatIntervalSec.value}s " +
                "polling=${_pollingIntervalSec.value}s logUpload=${_logUploadIntervalSec.value}s " +
                "rearmDelayMs=${_rearmDelayMs.value}"
        }
    }

    private fun normalizeBaseUrl(url: String): String = if (url.endsWith("/")) url else "$url/"

    companion object {
        private const val TAG = "TransportSettings"

        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MQTT_BROKER = "mqtt_broker"
        private const val KEY_REARM_DELAY_MS = "rearm_delay_ms"

        /** 上滑逃逸后重新抢占前台的默认延迟（毫秒）：接近 0，使孩子几乎无感 */
        const val DEFAULT_REARM_DELAY_MS = 16
        private const val MIN_REARM_DELAY_MS = 0
        private const val MAX_REARM_DELAY_MS = 2000

        const val DEFAULT_HEARTBEAT_SEC = 30
        /**
         * 降级轮询周期。
         *
         * 取 2s 而非 60s：这个值是**指令延迟的下限**——定时轮询下家长点「锁屏」/
         * 「实时看屏」最坏要等整整一个周期才被孩子端取走，60s 一轮在实测中就是
         * 「点了半天没反应」。
         *
         * 正常路径下它并不决定实时性：轮询请求会带长轮询参数挂在服务端，
         * 指令一到立即返回、返回后立刻发起下一轮；这里只在长轮询不可用
         * （旧服务端 / 请求失败退避）时才真正生效。
         */
        const val DEFAULT_POLLING_SEC = 2
        const val DEFAULT_LOG_UPLOAD_SEC = 300

        // 契约 §4.3：心跳 5s–300s 可远程配置
        private const val MIN_HEARTBEAT_SEC = 5
        private const val MAX_HEARTBEAT_SEC = 300
        // 下限放宽到 1s：原先的 15s 会把服务端下发的短周期强行抬高，
        // 导致即便服务端想让指令更快落地也做不到（远程配置被静默钳制）。
        private const val MIN_POLLING_SEC = 1
        private const val MAX_POLLING_SEC = 600
        private const val MIN_LOG_UPLOAD_SEC = 30
        private const val MAX_LOG_UPLOAD_SEC = 3600
    }
}
