package com.padguard.child.capture

import com.padguard.core.common.Logger
import com.padguard.core.data.repository.AuthRepository
import com.padguard.core.transport.TransportSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import android.os.SystemClock

/**
 * 实时看屏的**无障碍通道** —— 令牌缺失时的"零弹窗"推流路径。
 *
 * ## 为什么需要它
 * MediaProjection 令牌是一次性且进程内的：Android 15 QPR1 起**锁屏即强制断开投影**，
 * ROM 省电回收、用户点状态栏投影芯片、进程被杀也都会让令牌失效，且失效后必须
 * 重新弹系统确认框才能恢复。结果是：家长每次点"实时看屏"，孩子端都弹出
 * "为实时守护做准备，请确认屏幕采集授权"——确认框本该是装机时的一次性动作，
 * 却变成了每次监控的拦路虎，直接违背监控的初衷。
 *
 * 而无障碍服务的 `takeScreenshot`（API 30+）授权来自"启用无障碍服务"这个
 * **持久**授权：重启不失效、无令牌、无弹窗、不起 mediaProjection 前台服务。
 * 截屏已经在用它（[AccessibilityScreenshot]），这里把同一条链路推成**连续帧**。
 *
 * ## 与投影推流的分工
 * - 已持有投影令牌 → 仍走 [ScreenCaptureService] 的 ImageReader 路径（约 4fps，更流畅）；
 * - 令牌缺失但无障碍可用 → 走本通道（约 2fps，幻灯片感略强，但**全程无任何弹窗**）；
 * - 两者都不可用 → 才退化成"通知引导孩子确认"。
 *
 * ## 平台边界（如实声明）
 * - 系统对连续拍照有限频（约 333ms/次，过快返回
 *   `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT`），因此目标帧率压在 ~2fps；
 * - 屏幕熄灭/锁定时系统可能拒拍，此时跳帧重试，家长端表现为画面保持最后一帧；
 * - 长连接协议与投影推流完全一致（`4 字节长度 + JPEG`），家长端零改动。
 *
 * 生命周期：由 [com.padguard.child.service.GuardService]（常驻前台服务）持有，
 * 不自起前台服务 —— 推流只是网络 + 拍照，进程已被守护服务保活，无需重复占位。
 */
@Singleton
class AccessibilityLiveStreamer @Inject constructor(
    private val a11y: AccessibilityScreenshot,
    private val authRepository: AuthRepository,
    private val transportSettings: TransportSettings
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val streaming = AtomicBoolean(false)

    @Volatile private var deadlineMs = 0L
    @Volatile private var highDefinition = false

    /** 当前推流长连接；停止时必须 cancel，否则 execute() 会一直阻塞 */
    @Volatile private var call: okhttp3.Call? = null

    fun isStreaming(): Boolean = streaming.get()

    /**
     * 启动（或续期）推流。
     *
     * 家长端会周期性重发 LIVE_VIEW_START 续期，与投影路径同一套 TTL 语义：
     * 流已存在时只把截止时间往后推，并同步画质档位。
     *
     * @return true = 流已就绪/续期（无弹窗）；false = 无障碍通道不可用，调用方走投影引导
     */
    fun start(highDefinition: Boolean): Boolean {
        if (!a11y.isAvailable()) return false
        this.highDefinition = highDefinition
        if (streaming.get()) {
            deadlineMs = SystemClock.elapsedRealtime() + TTL_MS
            Logger.i(TAG) { "a11y live stream active, renewed (hd=$highDefinition)" }
            return true
        }
        streaming.set(true)
        deadlineMs = SystemClock.elapsedRealtime() + TTL_MS
        scope.launch { pump() }
        Logger.i(TAG) { "a11y live stream started (hd=$highDefinition, no consent dialog)" }
        return true
    }

    fun stop() {
        if (!streaming.getAndSet(false) && call == null) return
        Logger.i(TAG) { "a11y live stream stopped" }
        runCatching { call?.cancel() }
        call = null
    }

    /**
     * 推流主循环：与投影推流同一服务端协议（POST /device/stream/up，
     * body = `4 字节长度 + JPEG` 的连续帧流）。
     *
     * 写帧发生在 OkHttp 的写入线程（RequestBody.writeTo 是阻塞调用），
     * 拍照是挂起调用，这里用 runBlocking 桥接 —— 只发生在 OkHttp 工作线程，
     * 不占主线程；停止时 [stop] 置位 streaming + cancel call，循环在下一次
     * 条件检查处退出，阻塞中的 write 被连接取消打断，与投影路径同口径。
     */
    private suspend fun pump() {
        val deviceId = authRepository.getDeviceId()
        val token = authRepository.getDeviceToken()
        if (deviceId.isBlank() || token.isBlank()) {
            Logger.w(TAG) { "a11y live stream aborted: no device credentials yet" }
            streaming.set(false)
            return
        }
        val base = transportSettings.baseUrl.value.trimEnd('/')
        val url = "$base/device/stream/up"
        val maxWidth = if (highDefinition) MAX_WIDTH_HD else MAX_WIDTH_STD
        val quality = if (highDefinition) JPEG_QUALITY_HD else JPEG_QUALITY_STD
        // 系统限频约 333ms/次，帧间隔必须压在其之上并留余量，否则整轮失败空转
        val minFrameIntervalMs = if (highDefinition) MIN_FRAME_INTERVAL_HD_MS else MIN_FRAME_INTERVAL_STD_MS

        val body = object : okhttp3.RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun writeTo(sink: okio.BufferedSink) {
                var frames = 0
                var consecutiveFailures = 0
                var lastWriteAt = 0L
                while (streaming.get() && SystemClock.elapsedRealtime() < deadlineMs) {
                    val jpeg: ByteArray? = kotlinx.coroutines.runBlocking {
                        a11y.grabScaledJpeg(maxWidth, quality)
                    }
                    if (jpeg == null) {
                        // 限频/锁屏拒拍等瞬时失败：跳帧重试；连续过多次说明通道已实质失效
                        if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                            Logger.w(TAG) { "a11y live stream giving up after $consecutiveFailures failed grabs" }
                            return
                        }
                        SystemClock.sleep(FRAME_RETRY_MS)
                        continue
                    }
                    consecutiveFailures = 0
                    try {
                        val since = SystemClock.elapsedRealtime() - lastWriteAt
                        if (lastWriteAt > 0L && since < minFrameIntervalMs) {
                            SystemClock.sleep(minFrameIntervalMs - since)
                        }
                        sink.writeInt(jpeg.size)
                        sink.write(jpeg)
                        sink.flush()
                        frames++
                        lastWriteAt = SystemClock.elapsedRealtime()
                    } catch (t: Throwable) {
                        Logger.i(TAG) { "a11y live stream write ended after $frames frames: ${t.message}" }
                        return
                    }
                }
                Logger.i(TAG) { "a11y live stream finished, frames=$frames" }
            }
        }

        val request = okhttp3.Request.Builder()
            .url(url)
            .post(body)
            .header("Authorization", "Bearer $token")
            .header("X-Device-Id", deviceId)
            .build()

        val newCall = client().newCall(request)
        call = newCall
        try {
            newCall.execute().use { resp ->
                Logger.i(TAG) { "a11y live stream upload completed: http=${resp.code}" }
            }
        } catch (t: Throwable) {
            Logger.w(TAG) { "a11y live stream upload failed: ${t.message}" }
        } finally {
            call = null
            streaming.set(false)
        }
    }

    /**
     * 推流专用客户端：读写超时必须为 0（无限等待），与投影推流同口径 ——
     * 默认 30s 读超时会在推流途中直接被判定超时掐断，表现为"看几秒就断"。
     */
    private val streamingClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    private fun client(): OkHttpClient = streamingClient

    companion object {
        private const val TAG = "AccessibilityLiveStreamer"

        /** 与投影推流同一 TTL：家长端周期续期，超时未续期自动收尾 */
        private const val TTL_MS = 180_000L

        /** 画质档位与投影推流对齐（标准/高清），家长端无需感知帧来自哪条通道 */
        private const val MAX_WIDTH_STD = 860
        private const val MAX_WIDTH_HD = 1440
        private const val JPEG_QUALITY_STD = 70
        private const val JPEG_QUALITY_HD = 85

        /** 帧间隔下限：必须高于系统 ~333ms 的拍照限频并留余量（约 2fps） */
        private const val MIN_FRAME_INTERVAL_STD_MS = 500L
        private const val MIN_FRAME_INTERVAL_HD_MS = 450L

        /** 单次拍照失败后的重试间隔 */
        private const val FRAME_RETRY_MS = 250L

        /** 连续失败上限：通道实质失效时及时放弃，别空转烧电 */
        private const val MAX_CONSECUTIVE_FAILURES = 20
    }
}
