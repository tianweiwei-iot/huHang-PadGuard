package com.padguard.child.capture

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.graphics.Rect
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.view.Surface
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.widget.Toast
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.padguard.core.common.Logger
import com.padguard.core.data.repository.AuthRepository
import com.padguard.core.transport.RemoteDataSource
import com.padguard.core.transport.TransportSettings
import com.padguard.core.transport.http.ApiResult
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * 屏幕采集前台服务：承担远程截屏与录屏。
 *
 * ## 为什么必须是前台服务
 * API 29+ 起 `MediaProjection` 只允许在**前台服务**中使用（否则抛
 * `SecurityException: Media projections require a foreground service of type FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`）。
 * 所以这里不能用普通后台协程，必须挂前台通知。
 *
 * ## 授权时机
 * [MediaProjection] 令牌由 [CapturePermissionActivity] 在系统弹窗确认后放入 [ScreenCaptureSession]。
 * 若本服务被拉起时还没有令牌，会把当前请求暂存到 [pendingIntent]，
 * 由授权页在拿到令牌后**原样重放**该 Intent —— 这样"用户点截屏 → 弹授权 → 自动完成截屏"是一条完整的链路，
 * 不需要用户点第二次。
 *
 * ## 资源边界
 * 录屏是长任务，停止后才上传。
 *
 * **服务生命周期与投影（2026-10-17 策略变更）**：`MediaProjection` 与"带 mediaProjection
 * 类型的前台服务"在 Android 14+ 上是一体的 —— 任务执行期间（截屏/录屏/投影推流中）服务必须
 * 持续存活、投影复用同一 FGS 会话，否则系统以 "foreground service change" 回收投影。
 * 但**任务一结束就必须释放投影并退出**（见 [stopIfIdle]）：Android 15 QPR1 起，投影活着
 * 状态栏就常驻系统强制的"正在共享屏幕"芯片，任务结束后仍挂着既暴露监控又给孩子留反制入口
 * （点芯片可一键 Stop sharing）。跨会话复用令牌的收益已不抵常驻芯片的代价——
 * 截屏与实时看屏已首选无障碍通道（[AccessibilityScreenshot] / [AccessibilityLiveStreamer]），
 * 投影只剩录屏与兜底职责，需要时由暖令牌/通知引导重新授权。
 * 投影被外部掐断时由 [ScreenCaptureSession.setOnProjectionLost] 回调负责收尾。
 */
@AndroidEntryPoint
class ScreenCaptureService : Service() {

    @Inject lateinit var remote: RemoteDataSource
    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var transportSettings: TransportSettings

    /** 上传失败的媒体进重试队列，不再"失败即删" */
    @Inject lateinit var mediaRetry: MediaUploadRetry

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ==================== 实时看屏推流状态 ====================
    private val streaming = AtomicBoolean(false)
    private var streamJob: Job? = null
    @Volatile private var streamDeadlineMs = 0L

    /** 当前推流长连接；停止/重建时必须 cancel，否则 execute() 会一直阻塞（见 [releaseStream]） */
    @Volatile private var streamCall: okhttp3.Call? = null

    /**
     * 最近一次成功写出帧的时刻（elapsedRealtime）。
     * 这是判断推流"还活着"的唯一可信依据 —— Job 是否 active 不可信，见 [startLiveStream]。
     */
    @Volatile private var lastPushAtMs = 0L

    /**
     * 本次推流启动的时刻。与 [lastPushAtMs] 的区别很关键：
     * 后者是"写过帧"的时刻，连接刚建立、一帧都还没写出时它是 0，
     * 若此时直接用 [LIVE_STALE_MS] 判定，就会把一条**正在握手、还没来得及出帧**的推流
     * 当成死流拆掉重建 —— 与家长端的重连周期（无帧时 5s）共振，
     * 表现为"每 5 秒 restart 一次、永远推不出第一帧、家长端始终只有一张静止图"。
     * 因此首次出帧前改用 [LIVE_START_GRACE_MS] 宽限。
     */
    @Volatile private var streamStartedAtMs = 0L

    /**
     * 当前推流实际生效的画质档位（true=高清）。
     *
     * 由服务端随 LIVE_VIEW_START 指令下发（对应家长端「高清画面」开关）。
     * 单独存一份"实际生效值"而不是每次读 Intent，是为了能在续期时比对：
     * 家长在观看途中改了开关，只有把新旧档位对起来，才能判断该续期还是该重建流
     * （见 [startLiveStream]——只续期的话，已建立的流会一直用旧档位出帧）。
     *
     * 默认 false：老服务端不下发该字段时按标准档推流，行为与升级前一致。
     */
    @Volatile private var liveHighDefinition = false

    /** 当前档位的最小出帧间隔（高清帧更重，节流也略强） */
    private val liveMinFrameIntervalMs: Long
        get() = if (liveHighDefinition) LIVE_MIN_FRAME_INTERVAL_HD_MS else LIVE_MIN_FRAME_INTERVAL_STD_MS

    /** 当前推流实际生效的宽度（自适应档位内浮动，见 [adaptQuality]） */
    private val liveFrameMaxWidth: Int
        get() = effWidth

    /** 当前推流实际生效的 JPEG 质量 */
    private val liveJpegQuality: Int
        get() = effQuality

    // ==================== 帧缓冲复用（消除逐帧分配导致的 GC 卡顿）====================
    //
    // 实时推流每帧都要"取原始帧 → 缩放 → 压 JPEG"。早期实现每一步都新建 Bitmap：
    // 全分辨率 RGBA_8888 约 9MB，再建一张缩放图约数 MB，单帧分配 9~17MB，
    // 每秒数帧就会频繁触发 GC，表现为家长端画面周期性"卡屏"（设备端泵线程被 GC 停顿，
    // 服务端队列随之排空，家长端停在最后一帧）。
    // 这里把三块缓冲常驻复用：全分辨率(padded) / 裁掉行对齐填充 / 缩放后，
    // 整条推流只在档位切换时重建一次缩放缓冲，把逐帧分配降到只剩 JPEG 输出数组（约 100~300KB）。
    @Volatile private var liveFullBitmap: Bitmap? = null
    @Volatile private var liveCropBitmap: Bitmap? = null
    @Volatile private var liveScaledBitmap: Bitmap? = null
    @Volatile private var liveCropCanvas: Canvas? = null
    @Volatile private var liveScaledCanvas: Canvas? = null
    /** 复用同一块输出缓冲，避免每帧新建 ByteArrayOutputStream（其内部 byte[] 同样要扩容） */
    private val liveStreamBaos = ByteArrayOutputStream(256 * 1024)

    // ==================== 自适应码率（在家长选定的档位内上下浮动，给带宽与流畅度兜底）====================
    //
    // 服务端中转架构下，每一路实时看屏都是"一份上行 + 一份下行"带宽。
    // 并发一高，若每路都按最高画质猛推，服务端出口带宽会被打满、各路一起卡。
    // 这里按"单帧编码+上传耗时"反向调节：耗时超预算就先降质量、再降分辨率，
    // 明显从容就恢复，把每路带宽稳定在上限附近，避免挤兑式卡顿；网络/CPU 宽裕时
    // 又不浪费清晰度。注意：这是档位内的浮动，家长的"高清/标准"选择仍是上限。
    @Volatile private var effWidth = LIVE_FRAME_MAX_WIDTH_STD
    @Volatile private var effQuality = LIVE_JPEG_QUALITY_STD
    /** 最近一帧真实帧的"取样起点"，用于测量编码+上传耗时以驱动自适应 */
    private var realFrameStartMs = 0L

    /** 释放推流复用的全部缓冲（推流停止/重建时调用，防止内存常驻） */
    private fun releaseLiveBuffers() {
        liveFullBitmap = null
        liveCropBitmap = null
        liveScaledBitmap = null
        liveCropCanvas = null
        liveScaledCanvas = null
    }

    /**
     * 推流专用客户端：读写超时必须为 0（无限等待）。
     * 用默认的 30s 读超时会在推流途中直接被判定超时掐断，表现为"看几秒就断"。
     */
    private val streamingClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    /** 录屏的最长时长兜底：家长端忘了点停止时，不能无限录下去（存储与耗电都扛不住） */
    private var recordStopJob: Job? = null

    /** 录音的最长时长兜底（与录屏同思路） */
    private var audioStopJob: Job? = null

    /**
     * 起停竞态标志：stop 到达时 start 协程尚未落地（audioTaskId 仍为 null），
     * 先记请求；start 落地后据此立即自停，避免"永远录下去没人停"。
     */
    @Volatile private var audioStopRequested = false

    // ==================== 常驻采集会话 ====================
    // Android 14+ 硬性限制：同一个 MediaProjection 只允许 createVirtualDisplay 一次，
    // 第二次直接抛 SecurityException（"Don't take multiple captures by invoking
    // MediaProjection#createVirtualDisplay multiple times on the same instance"）。
    // 因此投影建出的 VirtualDisplay + ImageReader 必须常驻复用：
    // 截屏 = 从常驻 ImageReader 取一帧；录屏 = 把输出 Surface 切到 MediaRecorder，录完再切回。
    private var sharedDisplay: VirtualDisplay? = null
    private var frameReader: ImageReader? = null

    /**
     * 最近一次成功取到的帧（JPEG）。
     * 屏幕画面完全静止时 SurfaceFlinger 不会重新合成，也就没有新帧可取 ——
     * 此时"上一帧"就是当前屏幕的真实样子，直接复用即可，家长端不必白等或失败。
     */
    @Volatile private var lastFrame: ByteArray? = null

    private var mediaRecorder: MediaRecorder? = null
    private var recordFile: File? = null
    private var activeTaskId: String? = null
    private var recordStartedAt = 0L

    // ==================== 录屏（MediaCodec 直接从采集帧编码）====================
    //
    // **为什么不用 MediaRecorder 切 Surface 那条老路**：
    // 同一投影只能建一个 VirtualDisplay，老做法是录屏时把 VD 输出切到 MediaRecorder 的 Surface。
    // 实测这台 TB330FU / Android 14 上，切完 Surface 后 VD **几乎不再合成新帧** ——
    // 录 43 秒只出 3 帧（0.1fps）、录 15 秒只出 1 帧，家长看到的就是一张定格的图，
    // 而且录屏期间 VD 被独占，实时看屏也一起冻住。
    // 改成：**VD 永远只输出到常驻 ImageReader**（它本来就在稳定出帧），
    // 录屏循环从 ImageReader 取帧、画到编码器的输入 Surface，再由 MediaMuxer 封装成 MP4。
    // 这样录屏与实时看屏共用同一路帧源，两边都能动。
    private var videoCodec: MediaCodec? = null
    private var videoMuxer: MediaMuxer? = null
    private var encoderSurface: Surface? = null
    private var recordJob: Job? = null
    @Volatile private var muxerStarted = false
    @Volatile private var videoTrackIndex = -1
    private val screenRecording = AtomicBoolean(false)

    // 录制循环（drainEncoder）与收尾/丢弃路径（stop / release muxer）跑在不同 IO 协程上，
    // 曾出现「收尾已 release muxer、循环里最后一次 drainEncoder 仍持有旧引用并 writeSampleData」
    // 的竞态 → IllegalStateException: writeSampleData returned an error → **整个孩子端进程崩溃**，
    // 录屏文件随之丢失（家长端自然收不到视频）。用这把锁把 drain 与 stop/release 串行化。
    private val muxerLock = Any()
    /** MP4 要求每条 track 的 pts 单调递增；surface 输入时间戳理论上单调，此处兜底防抖动 */
    private var lastWrittenPtsUs = Long.MIN_VALUE / 2
    /** muxer 写入失败后置位：后续写入跳过，收尾时不再上传（半截 MP4 没有 moov 头，必然打不开） */
    @Volatile private var recordWriteFailed = false
    /** 本次录制实际写入 muxer 的样本数：收尾时 0 样本的文件是空壳 MP4，绝不能上传 */
    @Volatile private var recordSampleCount = 0
    /** muxer.stop() 是否成功：stop 失败的文件只有未收尾的占位 moov，播放器必报损坏 */
    @Volatile private var muxerStopOk = false

    /** 已进入前台状态；重复 startForeground 会触发系统回收 MediaProjection，见 [enterForeground] */
    private var foregroundEntered = false

    // 环境音采集与录屏相互独立：家长可能要求"只录音"，也可能"录屏+录音"同时开
    private var audioRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var audioTaskId: String? = null
    private var audioStartedAt = 0L

    override fun onCreate() {
        super.onCreate()
        // 投影失效即退出：两者一体，投影没了服务继续留着只会是一条空通知
        ScreenCaptureSession.setOnProjectionLost {
            Logger.i(TAG) { "projection lost, stopping capture service" }
            // 投影失效时若仍在录屏，不能静默丢档 —— 编码器里已攒下的帧仍可收尾成片，
            // 让家长至少拿到失效前那段；收尾逻辑自带"0 样本/半成品不上传"的护栏。
            if (screenRecording.get()) {
                Logger.w(TAG) { "projection lost while recording, finalize what we have" }
                scope.launch(Dispatchers.IO) { runCatching { finalizeRecording(upload = true) } }
            }
            stopIfIdle()
            // 不在这里补暖令牌（历史遗留已移除）：投影被外部掐断（锁屏/ROM 回收/
            // 孩子点状态栏芯片）时若主动弹系统确认框，只会出现在孩子正常使用平板的
            // 中途。令牌的重新取得交给按需路径——投影类功能真正下发且无令牌时，
            // 由 CaptureConsentPrompter 按前台/后台给弹框或通知引导。
        }
        // 服务启动时先把上一轮上传失败攒下的队列重传一遍（弱网恢复后的自然时机）
        scope.launch { runCatching { mediaRetry.drain() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra(EXTRA_ACTION)
        if (action == null) {
            stopSelf()
            return START_STICKY
        }
        // 暖令牌意图若意外被当作普通意图启动：凭据已在授权页就位，无需任何采集，直接收尾。
        if (action == ACTION_WARMUP) {
            Logger.i(TAG) { "warmup intent reached service, token already stored, no capture needed" }
            stopIfIdle()
            return START_STICKY
        }
        // 前台类型必须与本次任务匹配（见 [enterForeground]）：
        // 先解析动作再进前台，截屏这类纯投影任务绝不携带 microphone 类型。
        // 停止/控制类动作不进前台：停止不需要任何采集授权；此刻多半已无 appop，
        // 强行 startForeground 只会抛 SecurityException 留一条错误日志（见 [stopDirect]）。
        val isControlAction = action == ACTION_LIVE_STOP ||
            action == ACTION_STOP_RECORD || action == ACTION_STOP_AUDIO_RECORD
        if (!isControlAction) {
            enterForeground(action, intent.getBooleanExtra(EXTRA_WITH_AUDIO, false))
        }
        when (action) {
            ACTION_SCREENSHOT -> captureScreenshot(intent.getStringExtra(EXTRA_SHOT_ID).orEmpty(), intent)
            ACTION_SCREEN_RECORD -> startScreenRecord(
                taskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty(),
                resolution = intent.getStringExtra(EXTRA_RESOLUTION) ?: DEFAULT_RESOLUTION,
                withAudio = intent.getBooleanExtra(EXTRA_WITH_AUDIO, false),
                intent = intent
            )
            ACTION_STOP_RECORD -> stopScreenRecord()
            ACTION_LIVE_START -> startLiveStream(intent)
            ACTION_LIVE_STOP -> stopLiveStream()
            ACTION_AUDIO_RECORD -> startAudioRecord(
                taskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
            )
            ACTION_STOP_AUDIO_RECORD -> stopAudioRecord()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Logger.i(TAG) { "ScreenCaptureService destroyed" }
        // 服务已退出，回调随之作废，避免残留闭包引用已销毁实例
        ScreenCaptureSession.setOnProjectionLost(null)
        releaseStream("destroy")
        recordStopJob?.cancel()
        audioStopJob?.cancel()
        releaseCaptureSession()
        releaseRecorder()
        releaseAudio()
        // 未收尾的录制/录音文件在此清理 —— 它们永远不会被上传
        // （留着就是 cacheDir 里没人认领的垃圾，积少成多把磁盘写满）。
        // 已入重试队列的文件不在这里（在 media_retry/ 目录，由队列自己管理）。
        runCatching { recordFile?.takeIf { it.exists() }?.delete() }
        runCatching { audioFile?.takeIf { it.exists() }?.delete() }
        scope.cancel()
        super.onDestroy()
    }

    // ==================== 远程截屏 ====================

    private fun captureScreenshot(shotId: String, intent: Intent) {
        if (shotId.isBlank()) {
            Logger.w(TAG) { "screenshot missing shotId" }
            stopIfIdle()
            return
        }
        val projection = ScreenCaptureSession.obtainProjection()
        if (projection == null) {
            awaitPermission(intent)
            stopIfIdle()
            return
        }
        scope.launch {
            try {
                doCapture(projection, shotId)
            } catch (t: Throwable) {
                Logger.e(TAG, t) { "screenshot failed" }
            } finally {
                stopIfIdle()
            }
        }
    }

    private suspend fun doCapture(projection: MediaProjection, shotId: String) {
        Logger.i(TAG) { "doCapture begin, projection=$projection" }
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels

        val reader = ensureCaptureSession(projection, width, height, metrics.densityDpi)
        if (reader == null) {
            Logger.w(TAG) { "capture session unavailable, drop shot $shotId" }
            return
        }
        val jpeg = withContext(Dispatchers.IO) { grabFrame(reader, width, height) }
            ?: lastFrame   // 无新帧 = 屏幕没变，上一帧即当前画面
        if (jpeg == null) {
            Logger.w(TAG) { "screenshot frame timeout, shotId=$shotId" }
            return
        }
        lastFrame = jpeg
        val now = System.currentTimeMillis()
        when (val result = remote.uploadScreenshot(shotId, now, TRIGGER_REMOTE, jpeg)) {
            is ApiResult.Success -> Logger.i(TAG) { "screenshot uploaded: $shotId ${jpeg.size / 1024}KB" }
            else -> {
                // 上传失败不再"即删"。落盘移交重试队列，弱网恢复后由 drain 重传 ——
                // 早期实现这里只记一行日志就把帧丢了，家长端永远等不到这张截图。
                Logger.w(TAG) { "screenshot upload failed: $result" }
                val tmp = File(cacheDir, "shot_$shotId.jpg")
                if (runCatching { tmp.writeBytes(jpeg) }.isSuccess) {
                    mediaRetry.enqueue(tmp, MediaUploadRetry.Kind.Screenshot(shotId, now, TRIGGER_REMOTE))
                }
            }
        }
    }

    /**
     * 取常驻采集会话；首次调用时创建 —— 这是该投影**唯一一次** [MediaProjection.createVirtualDisplay]
     * （Android 14+ 禁止对同一投影二次调用，见类顶部说明）。
     *
     * 会话一经创建就常驻：后续截屏直接从 [frameReader] 取帧（免掉每次建 VD 的 100~300ms 延迟），
     * 录屏则用 [VirtualDisplay.setSurface] 切换输出，不再另建 VD。
     */
    private fun ensureCaptureSession(
        projection: MediaProjection,
        width: Int,
        height: Int,
        density: Int
    ): ImageReader? {
        frameReader?.let { return it }
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, IMAGE_BUFFER_COUNT)
        val display = try {
            projection.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME, width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null
            )
        } catch (t: Throwable) {
            Logger.e(TAG, t) { "create capture session failed" }
            runCatching { reader.close() }
            return null
        }
        sharedDisplay = display
        frameReader = reader
        return reader
    }

    /**
     * 把常驻 VirtualDisplay 的输出切回截图用的 ImageReader。
     * 录屏期间输出被切到 MediaRecorder，录完/丢弃残留录制后必须切回来，否则截图会一直拿不到帧。
     */
    private fun restoreDisplayToReader() {
        runCatching {
            // 不 resize、也不置 null：VD 尺寸全程保持屏幕原生分辨率，
            // 中间态的 setSurface(null) 反而会让 VD 彻底停止合成。
            sharedDisplay?.setSurface(frameReader?.surface)
        }
    }

    /** 释放常驻采集会话（服务销毁或投影失效时调用） */
    private fun releaseCaptureSession() {
        runCatching { sharedDisplay?.setSurface(null) }
        runCatching { sharedDisplay?.release() }
        runCatching { frameReader?.close() }
        sharedDisplay = null
        frameReader = null
        lastFrame = null
    }

    /**
     * 从常驻 [ImageReader] 取最新一帧并压成 JPEG。
     *
     * 用轮询 `acquireLatestImage()` 而不是注册 OnImageAvailableListener 等回调：
     * 常驻会话下，上一帧取完后若长时间无人消费，队列会被生产端填满、生产端随之停摆，
     * 此后"有新帧"的回调**永远不会再触发**（没有缓冲可用就入不了队），实测第二次截屏必超时。
     * 轮询取帧既能立刻拿到已就绪的帧，又会持续释放缓冲让生产端保持流动。
     */
    private fun grabFrame(reader: ImageReader, width: Int, height: Int): ByteArray? {
        val deadline = SystemClock.elapsedRealtime() + CAPTURE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val image = reader.acquireLatestImage()
            if (image == null) {
                SystemClock.sleep(FRAME_POLL_INTERVAL_MS)
                continue
            }
            try {
                val frame = imageToBitmap(image, width, height)
                val out = ByteArrayOutputStream()
                frame.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                return out.toByteArray()
            } catch (t: Throwable) {
                Logger.e(TAG, t) { "frame decode failed" }
                return null
            } finally {
                image.close()
            }
        }
        return null
    }

    /**
     * Image（RGBA_8888）→ Bitmap。
     *
     * 行对齐会在右侧多出一段填充，必须按 stride 换算后再裁掉，
     * 否则画面右边会带一条黑带（截图和录屏都会中招，两者共用这里）。
     */
    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val padded = Bitmap.createBitmap(
            width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
        )
        padded.copyPixelsFromBuffer(plane.buffer)
        return if (rowPadding == 0) padded
        else Bitmap.createBitmap(padded, 0, 0, width, height)
    }

    // ==================== 录屏 ====================

    @Suppress("DEPRECATION")
    private fun startScreenRecord(taskId: String, resolution: String, withAudio: Boolean, intent: Intent) {
        // resolution / withAudio 是协议里的**预留字段**——
        // 录屏始终按屏幕原生分辨率编码、且不混入音轨（AAC 音轨混流独立排期）。
        // 这里"接受但不生效"，并如实记一行日志：字段继续随指令收发（协议砍能力只砍
        // 产品承诺，不动线上 wire 兼容），但任何一端都不许再暗示它们有用。
        if (resolution.isNotBlank() || withAudio) {
            Logger.w(TAG) {
                "screen record params accepted but NOT honored: " +
                    "resolution=$resolution withAudio=$withAudio; " +
                    "recording at native screen resolution without audio track"
            }
        }
        // 残留录制必须先收尾。
        // 之前的写法是"已在录制就直接 return"：一旦上一次录制没收到 stop 指令
        // （服务端漏发、进程被杀后恢复、家长端点了停止但指令丢失），mediaRecorder 就永远非 null，
        // 从此这台设备的录屏功能**永久失效**，家长端只看到"录屏启动失败"却查不到任何原因。
        // 残留录制的产物没有对应的 stop 事件，上传也没人认领，直接丢弃最安全。
        if (screenRecording.get()) {
            // 同一任务的重复 start 指令：服务端在回执延迟/时钟偏移下会把同一条
            // screenRecord 反复下发（实测 3 秒一次）。若每次都当"残留"丢弃重启，
            // 录制永远只有几秒、且每次丢弃都强制释放编解码器（曾引发
            // dequeueOutputBuffer 与 release 并发的进程崩溃）。同任务幂等忽略才是正解。
            if (activeTaskId == taskId) {
                Logger.i(TAG) { "duplicate screen record start for $taskId, keep current recording" }
                return
            }
            Logger.w(TAG) { "stale screen record detected (task=${activeTaskId}), discard it before starting $taskId" }
            releaseRecordingResources()
            recordFile = null
            activeTaskId = null
            recordStartedAt = 0L
        }
        val projection = ScreenCaptureSession.obtainProjection()
        if (projection == null) {
            awaitPermission(intent)
            stopIfIdle()
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val metrics = resources.displayMetrics
                val w = metrics.widthPixels
                val h = metrics.heightPixels
                val out = File(cacheDir, "rec_$taskId.mp4")

                // 编码器走 Surface 输入：我们把采集到的帧"画"进它的输入 Surface，
                // 由 MediaMuxer 封装成 MP4。全程**不碰** VirtualDisplay 的输出目标。
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, RECORD_BIT_RATE)
                    setInteger(MediaFormat.KEY_FRAME_RATE, RECORD_FRAME_RATE)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, RECORD_I_FRAME_INTERVAL)
                }
                val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val surface = codec.createInputSurface()
                codec.start()
                val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                videoCodec = codec
                videoMuxer = muxer
                encoderSurface = surface
                muxerStarted = false
                videoTrackIndex = -1
                recordWriteFailed = false
                recordSampleCount = 0
                muxerStopOk = false

                // 常驻采集会话保持不变：VD 一直输出到 ImageReader，录屏与实时看屏共用这路帧。
                val reader = ensureCaptureSession(projection, w, h, metrics.densityDpi)
                if (reader == null) throw IllegalStateException("采集会话不可用，请重新授权屏幕采集")

                recordFile = out
                activeTaskId = taskId
                recordStartedAt = System.currentTimeMillis()
                screenRecording.set(true)
                Logger.i(TAG) { "screen record started: taskId=$taskId ${w}x$h (MediaCodec from ImageReader)" }

                val startNs = System.nanoTime()
                var encoded = 0
                recordJob = scope.launch(Dispatchers.IO) {
                    // 帧缓冲复用：1200×1920 的 ARGB_8888 是 9MB，
                    // 每帧新建 Bitmap 会有明显的分配/GC 抖动，实测把帧率压到 3fps 上下。
                    // 复用同一块缓冲、绘制时用 src 矩形裁掉行对齐填充，可省掉每帧两次分配。
                    var frameBuffer: Bitmap? = null
                    while (screenRecording.get()) {
                        val image = reader.acquireLatestImage()
                        if (image != null) {
                            try {
                                val plane = image.planes[0]
                                val rowPadding = plane.rowStride - plane.pixelStride * w
                                val paddedW = w + rowPadding / plane.pixelStride
                                val buf = frameBuffer ?: Bitmap
                                    .createBitmap(paddedW, h, Bitmap.Config.ARGB_8888)
                                    .also { frameBuffer = it }
                                buf.copyPixelsFromBuffer(plane.buffer)

                                // 同一路帧既喂编码器 —— 录制与实时看屏共用帧源，
                                // 这样录屏期间家长端看到的画面也是在动的。
                                val canvas = surface.lockCanvas(null)
                                if (canvas != null) {
                                    canvas.drawBitmap(buf, Rect(0, 0, w, h), Rect(0, 0, w, h), null)
                                    surface.unlockCanvasAndPost(canvas)
                                    encoded++
                                } else {
                                    Logger.w(TAG) { "编码器输入 Surface 取不到 Canvas，跳过本帧" }
                                }

                                // 顺手刷新推流用的缓存帧：录制循环会先把最新帧取走，
                                // 推流的 grabFrame 容易拿到 null，若不这样喂它，
                                // 家长端在录屏期间看到的就是一张定格的旧图。
                                // 压缩 JPEG 本身不便宜，降频到每 6 帧一次，别拖慢录制。
                                if (streaming.get() && encoded % 6 == 0) {
                                    val out = ByteArrayOutputStream()
                                    if (buf.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                                        lastFrame = out.toByteArray()
                                    }
                                }
                            } catch (t: Throwable) {
                                Logger.w(TAG) { "record frame failed: ${t.message}" }
                            } finally {
                                image.close()
                            }
                        }
                        drainEncoder(end = false)
                        delay(RECORD_POLL_MS)
                    }
                    runCatching { frameBuffer?.recycle() }
                    Logger.i(TAG) { "screen record loop finished: taskId=$taskId frames=$encoded" }
                }

                // 兜底自动收尾：家长端忘了点"停止录屏"时不能无限录下去。
                recordStopJob?.cancel()
                recordStopJob = scope.launch {
                    delay(MAX_RECORD_MS)
                    if (activeTaskId == taskId && screenRecording.get()) {
                        Logger.w(TAG) { "screen record reached max duration, auto stop: $taskId" }
                        stopScreenRecord()
                    }
                }
            } catch (t: Throwable) {
                Logger.e(TAG, t) { "screen record start failed" }
                // ：之前失败只记日志、被控端无任何提示，家长端也收不到失败信号。
                // 这里给出本机可感知的提示，孩子立刻知道录屏没起来（多半是未授权屏幕采集）。
                // 注意：此处在协程内，this 是 CoroutineScope 而非 Context，必须用 applicationContext。
                Toast.makeText(
                    applicationContext,
                    "录屏启动失败：${t.message ?: "未知错误"}（请先授权屏幕采集）",
                    Toast.LENGTH_LONG
                ).show()
                runCatching { finalizeRecording(upload = false) }
                stopIfIdle()
            }
        }
    }

    /**
     * 释放录屏占用的编解码资源（同步，可在任何地方调用）。
     *
     * 抽出来是因为两处需要它：正常停止（[stopScreenRecord]）和**丢弃残留录制**
     * （[startScreenRecord] 开头）——后者在普通函数里，不能调 suspend 的 [finalizeRecording]。
     */
    /**
     * 释放录屏占用的编解码资源（同步，可在任何地方调用）。
     *
     * 用于「丢弃残留录制」（[startScreenRecord] 开头）：上次没收到 stop、进程被杀恢复等场景下
     * recordJob 还在跑，直接丢弃最安全。这里只负责停掉并释放，不做收尾上传。
     */
    private fun releaseRecordingResources() {
        screenRecording.set(false)
        recordStopJob?.cancel()
        runCatching { recordJob?.cancel() }
        releaseCodecAndMuxer()
        recordJob = null
        muxerStarted = false
        videoTrackIndex = -1
    }

    /** 仅释放编解码器与封装器，不动 screenRecording / recordJob 标志（收尾流程由调用方控制） */
    private fun releaseCodecAndMuxer() {
        synchronized(muxerLock) {
            val codec = videoCodec
            val muxer = videoMuxer
            val surface = encoderSurface
            videoCodec = null
            videoMuxer = null
            encoderSurface = null
            // 先释放封装器再释放编解码器，顺序反了会触发 MediaMuxer 内部异常
            runCatching { muxer?.release() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { surface?.release() }
        }
    }

    /**
     * 收尾录屏：停止取帧 → 通知编码器结束 → 抽干剩余输出 → 释放封装器 → 上传。
     */
    /**
     * 收尾录屏：先让录制循环正常退出（停止往编码器塞帧）并等它真正退出，
     * 再通知编码器结束、抽干剩余输出、停封装器 —— 顺序与并发是「能不能产出可用 MP4」的关键：
     * 之前 recordJob 循环还在往编码器塞帧、抽数据的同时，另一处就 stop 了封装器，
     * 得到的 mp4 往往缺 moov 头或帧数不足，家长端拿到的是「打不开 / 0 秒」的废文件，
     * 表现就是「录屏结束没产生视频」。
     */
    private suspend fun finalizeRecording(upload: Boolean) {
        val taskId = activeTaskId
        val file = recordFile
        val durationSec = if (recordStartedAt > 0) {
            ((System.currentTimeMillis() - recordStartedAt) / 1000).toInt()
        } else 0
        // 1) 停止塞帧，并等录制循环体真正退出（最多等 3s，防止 lockCanvas 卡死导致永久挂起）
        screenRecording.set(false)
        runCatching { withTimeoutOrNull(3000L) { recordJob?.join() } }
        recordStopJob?.cancel()
        // 2) 通知编码器收尾 + 抽干 + 停封装器（与 drainEncoder 共用 muxerLock 串行化）
        synchronized(muxerLock) {
            runCatching { videoCodec?.signalEndOfInputStream() }
                .onFailure { Logger.w(TAG) { "signalEndOfInputStream: ${it.message}" } }
        }
        runCatching { drainEncoder(end = true) }
            .onFailure { Logger.w(TAG) { "drainEncoder: ${it.message}" } }
        synchronized(muxerLock) {
            if (videoMuxer != null && muxerStarted) {
                // stop() 成功与否直接决定文件可不可播：stop 失败意味着磁盘上的
                // 只是 MediaMuxer 启动时写的"占位 moov"（样本表为空），播放器必报损坏。
                // 置 muxerStopOk 供上传判据使用，绝不能让半成品混进家长端相册。
                muxerStopOk = runCatching { videoMuxer?.stop() }.isSuccess
                if (!muxerStopOk) Logger.w(TAG) { "muxer stop failed, file is not playable and will be discarded" }
            }
        }
        // 3) 释放编解码与封装资源
        releaseCodecAndMuxer()
        recordJob = null
        muxerStarted = false
        videoTrackIndex = -1

        if (upload && taskId != null && file != null && file.exists() &&
            file.length() > 0 && !recordWriteFailed && muxerStopOk && recordSampleCount > 0
        ) {
            when (val r = remote.uploadMedia(taskId, durationSec, MIME_VIDEO_MP4, file)) {
                is ApiResult.Success -> Logger.i(TAG) { "screen record uploaded: $taskId ${file.length() / 1024}KB samples=$recordSampleCount" }
                else -> {
                    // 上传失败不再"失败即删"。移交重试队列，弱网恢复后重传 ——
                    // 早期实现这里之后无条件 delete，家长端永远等不到这段录屏。
                    Logger.w(TAG) { "screen record upload failed: $r" }
                    mediaRetry.enqueue(file, MediaUploadRetry.Kind.Media(taskId, durationSec, MIME_VIDEO_MP4))
                }
            }
        } else if (upload && recordWriteFailed) {
            Logger.w(TAG) { "screen record had muxer write failure, skip uploading broken file" }
        } else if (upload && !muxerStopOk) {
            Logger.w(TAG) { "screen record muxer not finalized, skip uploading unplayable file" }
        } else if (upload && recordSampleCount <= 0) {
            Logger.w(TAG) { "screen record has no encoded samples ($recordSampleCount), skip uploading empty file" }
        } else if (upload) {
            Logger.w(TAG) { "screen record produced no file, nothing to upload" }
        }
        // 入队成功的文件已被 enqueue 移走（copyTo + delete 源），这里 delete 是空操作；
        // 成功上传 / 主动丢弃的残留文件则在此清理
        runCatching { file?.delete() }
        recordFile = null
        activeTaskId = null
        recordStartedAt = 0L
        // 收尾时顺手重传队列里积压的媒体（含刚入队的那条——网络可能已恢复）
        runCatching { mediaRetry.drain() }
        stopIfIdle()
    }

    private fun stopScreenRecord() {
        scope.launch(Dispatchers.IO) {
            finalizeRecording(upload = true)
        }
    }

    /**
     * 把编码器已产出的 H264 数据抽出来写进 MediaMuxer。
     *
     * 必须持续调用：编码器输出缓冲满了就会停止接收新输入，帧循环会因此卡死。
     * [end] 为 true 时抽到 END_OF_STREAM 为止（收尾阶段）。
     *
     * **整个函数必须在 muxerLock 内**：dequeueOutputBuffer 会阻塞等待编码器输出，
     * 若此时另一线程在收尾/丢弃路径里 release codec，系统会取消挂起的 dequeue
     * 并抛 `IllegalStateException: Pending dequeue output buffer request cancelled`
     * —— 这是把整个孩子端进程打崩的真实事故（实测 2026-09-29 14:18）。
     * 只锁 writeSampleData 挡不住这一处，锁必须覆盖 dequeue。
     */
    private fun drainEncoder(end: Boolean) {
        synchronized(muxerLock) {
            val codec = videoCodec ?: return
            val muxer = videoMuxer ?: return
            val info = MediaCodec.BufferInfo()
            val timeoutUs = if (end) 100_000L else 10_000L
            // 收尾阶段的时间上限：正常 EOS 毫秒级就到；编码器异常时绝不能在锁内死等，
            // 否则 finalizeRecording 永远走不到 muxer.stop()，文件永远是未收尾的占位 moov
            val endDeadline = if (end) SystemClock.elapsedRealtime() + DRAIN_END_TIMEOUT_MS else 0L
            while (true) {
                val index = try {
                    codec.dequeueOutputBuffer(info, timeoutUs)
                } catch (t: Throwable) {
                    // codec 被并发释放等场景：放弃本次抽数，交由上层收尾逻辑兜底
                    Logger.w(TAG) { "dequeueOutputBuffer aborted: ${t.message}" }
                    return
                }
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!end) return
                    if (SystemClock.elapsedRealtime() > endDeadline) {
                        Logger.w(TAG) { "drainEncoder(end) timed out waiting EOS" }
                        return
                    }
                    continue
                }
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxerStarted) {
                        // 双重检查：等锁期间收尾可能已把 muxer 停掉/释放
                        if (videoMuxer == null) return
                        videoTrackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                        lastWrittenPtsUs = Long.MIN_VALUE / 2
                    }
                    continue
                }
                if (index < 0) return
                val buffer = codec.getOutputBuffer(index) ?: run {
                    codec.releaseOutputBuffer(index, false)
                    return
                }
                if (info.size > 0 && muxerStarted &&
                    (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                ) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    // writeSampleData 绝不允许抛出进程级崩溃（历史事故：收尾竞态导致
                    // writeSampleData returned an error → 孩子端整个崩掉）。失败只标记并放弃本帧。
                    if (videoMuxer == null || recordWriteFailed) {
                        codec.releaseOutputBuffer(index, false)
                        return
                    }
                    runCatching {
                        if (info.presentationTimeUs > lastWrittenPtsUs) {
                            muxer.writeSampleData(videoTrackIndex, buffer, info)
                            lastWrittenPtsUs = info.presentationTimeUs
                            recordSampleCount++
                        }
                    }.onFailure { t ->
                        recordWriteFailed = true
                        Logger.w(TAG) { "writeSampleData failed (recording aborted, file will be discarded): ${t.message}" }
                    }
                }
                codec.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            }
        }
    }

    // ==================== 实时看屏推流 ====================
    //
    // 家长要的是"孩子屏幕上现在是什么样"，而不是隔十几秒刷新一张静态图。
    // 这里把常驻 ImageReader 产出的帧按目标帧率持续压成 JPEG 推给服务端，
    // 服务端原样转发给正在观看的家长端 —— 端到端是一条流，不是轮询。

    private fun startLiveStream(intent: Intent) {
        // 画质档位由服务端随指令下发；缺省 false 保证老服务端下行为不变
        val requestedHd = intent.getBooleanExtra(EXTRA_HIGH_DEFINITION, false)
        // ---- 活性判定：绝不能只看协程是否还挂着 ----
        //
        // 推流用的是 OkHttp 长连接，execute() 是**阻塞调用**：帧循环一旦退出
        // （TTL 到期 / 对端断开 / 写失败），它会卡在等待响应，很久都不返回，
        // 于是 streamJob.isActive 一直是 true。
        //
        // 历史 bug 正是这样：推流实际早已停摆，之后每一条 LIVE_VIEW_START 都被
        // 误判成"已在推流"，只是把 deadline 往后推，却从不真正重启 ——
        // 家长端从此一帧也收不到，只能每 5 秒重试一次，永远黑屏。
        //
        // 因此以「最近一次成功写出帧的时间」为准：超时未出帧一律判定死亡并重建。
        val now = SystemClock.elapsedRealtime()
        val fresh = streaming.get() && if (lastPushAtMs > 0L) {
            // 已出过帧：停滞超过阈值才算死
            now - lastPushAtMs < LIVE_STALE_MS
        } else {
            // 还没出过帧：握手中，宽限期内不重建（见 [streamStartedAtMs] 注释）
            now - streamStartedAtMs < LIVE_START_GRACE_MS
        }
        if (fresh) {
            // 画质档位没变：家长端会周期性续期，重复 start 是正常现象，把截止时间往后推即可
            if (requestedHd == liveHighDefinition) {
                streamDeadlineMs = SystemClock.elapsedRealtime() + LIVE_TTL_MS
                Logger.i(TAG) { "live stream active, renewed" }
                return
            }
            // 档位变了：只续期的话，已建立的流会继续用旧档位出帧，
            // 家长改了开关却看不到任何变化，等于开关失效。这里必须拆掉重建。
            Logger.i(TAG) { "live stream quality changed: hd=$requestedHd, rebuilding" }
        }
        liveHighDefinition = requestedHd
        // 档位切换：把自适应状态复位到该档位上限，缩放缓冲交给 pump 按新宽度重建。
        // 否则切换"高清/标准"后，effWidth/effQuality 还停在旧档位的浮动值，
        // 新档位要么迟迟不生效、要么首帧仍按旧上限出帧，等于开关再次失效。
        effWidth = if (requestedHd) LIVE_FRAME_MAX_WIDTH_HD else LIVE_FRAME_MAX_WIDTH_STD
        effQuality = if (requestedHd) LIVE_JPEG_QUALITY_HD else LIVE_JPEG_QUALITY_STD
        liveScaledBitmap = null
        liveScaledCanvas = null
        // 残留推流必须彻底拆掉再重建：残留的 call 不 cancel，execute() 会一直挂着，
        // 新起的推流与旧的争抢同一个投影会话，画面依旧出不来。
        releaseStream("restart")
        val projection = ScreenCaptureSession.obtainProjection()
        if (projection == null) {
            awaitPermission(intent)
            stopIfIdle()
            return
        }
        streaming.set(true)
        // 归零 = "尚未写出任何帧"，走宽限期判定；真正出帧时刻由帧泵写入
        lastPushAtMs = 0L
        streamStartedAtMs = SystemClock.elapsedRealtime()
        streamDeadlineMs = SystemClock.elapsedRealtime() + LIVE_TTL_MS
        streamJob = scope.launch {
            pumpFrames(projection)
        }
    }

    private fun stopLiveStream() {
        releaseStream("stop")
        Logger.i(TAG) { "live stream stopped" }
        stopIfIdle()
    }

    /**
     * 彻底拆掉一次推流。
     *
     * 只 `Job.cancel()` 是不够的：被取消的协程若正阻塞在 OkHttp 的 `execute()` 上，
     * 取消信号根本传不进去，socket 会一直悬着，服务端那条长连接也一直不释放。
     * 必须先 `Call.cancel()` 打断阻塞，再取消协程。
     */
    private fun releaseStream(reason: String) {
        if (streamJob == null && streamCall == null) {
            streaming.set(false)
            return
        }
        Logger.i(TAG) { "release live stream: reason=$reason" }
        runCatching { streamCall?.cancel() }
        streamCall = null
        streamJob?.cancel()
        streamJob = null
        streaming.set(false)
        releaseLiveBuffers()
    }

    /** 帧泵：一条长连接上持续写 `4 字节长度 + JPEG` */
    private suspend fun pumpFrames(projection: MediaProjection) {
        val deviceId = authRepository.getDeviceId()
        val token = authRepository.getDeviceToken()
        if (deviceId.isBlank() || token.isBlank()) {
            Logger.w(TAG) { "live stream aborted: no device credentials yet" }
            streaming.set(false)
            return
        }
        val base = transportSettings.baseUrl.value.trimEnd('/')
        val url = "$base/device/stream/up"
        val metrics = resources.displayMetrics
        val reader = ensureCaptureSession(projection, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        if (reader == null) {
            Logger.w(TAG) { "live stream aborted: capture session unavailable" }
            streaming.set(false)
            return
        }

        val body = object : okhttp3.RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun writeTo(sink: okio.BufferedSink) {
                var frames = 0
                // 最近一次实际推给服务端的 JPEG。
                // 屏幕完全静止时 ImageReader 拿不到新帧（SurfaceFlinger 不重新合成），
                // 此时"上一帧"就是孩子屏幕的真实样子 —— 复用它重发，而不是只写心跳：
                // 心跳帧（长度 0）服务端不更新 lastFrameAt，家长端会据此判定断流并不断重连，
                // 表现为"看屏永远黑屏/卡在某一帧"。这是实时监控能不能成立的关键。
                var lastSent: ByteArray? = null
                var lastRealFrameAt = 0L
                /** 上一帧真正写出的时刻，用于按档位节流（见 [liveMinFrameIntervalMs]） */
                var lastWriteAt = 0L
                while (streaming.get() && SystemClock.elapsedRealtime() < streamDeadlineMs) {
                    val payload: ByteArray?
                    if (mediaRecorder != null || screenRecording.get()) {
                        // 录屏期间让帧：录制循环与推流共用同一个常驻 ImageReader，
                        // 两条消费端各自 acquireLatestImage 会互相偷帧 —— 实测 21 秒
                        // 只抢到 9 帧，录出来的视频近乎幻灯片。录屏期间录制循环独占
                        // 帧源（它每 6 帧会顺手刷新 lastFrame），这里改用缓存帧保活，
                        // 家长端看屏在录制期间退化为低频刷新，录制质量不受损。
                        payload = if (lastFrame != null &&
                            SystemClock.elapsedRealtime() - lastRealFrameAt >= LIVE_KEEPALIVE_MS
                        ) {
                            lastRealFrameAt = SystemClock.elapsedRealtime()
                            lastFrame
                        } else null
                        SystemClock.sleep(LIVE_FRAME_INTERVAL_MS)
                    } else {
                        val jpeg = grabFrameScaled(reader, metrics.widthPixels, metrics.heightPixels)
                        if (jpeg != null) {
                            // 记录"真实帧"取样起点，用于测量编码+上传耗时并驱动自适应（见 [adaptQuality]）
                            realFrameStartMs = SystemClock.elapsedRealtime()
                            lastSent = jpeg
                            lastRealFrameAt = SystemClock.elapsedRealtime()
                            payload = jpeg
                        } else {
                            // 无新帧：距上次重发超过保活间隔就复用上一帧，否则只写心跳。
                            // 这样既保证服务端 lastFrameAt 持续刷新（家长端判定流活着），
                            // 又不会在静止画面下把同样的 30KB 每 200ms 推一次白白吃满上行带宽。
                            val cached = lastSent
                            payload = if (cached != null &&
                                SystemClock.elapsedRealtime() - lastRealFrameAt >= LIVE_KEEPALIVE_MS
                            ) {
                                lastRealFrameAt = SystemClock.elapsedRealtime()
                                cached
                            } else null
                        }
                        if (jpeg == null) SystemClock.sleep(LIVE_FRAME_INTERVAL_MS)
                    }
                    try {
                        if (payload == null) {
                            // 纯心跳：告诉服务端连接还在，不计入画面
                            sink.writeInt(0)
                        } else {
                            // 真实帧：按档位补一个最小间隔。
                            // 取帧本身不带节流（有新帧立刻返回），屏幕连续变化时出帧速率完全
                            // 由 SurfaceFlinger 决定；高清档单帧可达数百 KB，不压一下上行会打满。
                            val minGap = liveMinFrameIntervalMs
                            val since = SystemClock.elapsedRealtime() - lastWriteAt
                            if (lastWriteAt > 0L && since < minGap) SystemClock.sleep(minGap - since)
                            sink.writeInt(payload.size)
                            sink.write(payload)
                            frames++
                        }
                        sink.flush()
                        lastWriteAt = SystemClock.elapsedRealtime()
                        lastPushAtMs = SystemClock.elapsedRealtime()
                        // 真实帧：用"编码+上传"耗时驱动自适应（见 [adaptQuality]）。
                        // 仅对刚压出来的新帧计时，复用缓存帧(keepalive)不计，避免把"静止画面"误判成拥塞。
                        if (realFrameStartMs > 0L) {
                            adaptQuality(SystemClock.elapsedRealtime() - realFrameStartMs)
                            realFrameStartMs = 0L
                        }
                    } catch (t: Throwable) {
                        Logger.i(TAG) { "live stream write ended after $frames frames: ${t.message}" }
                        return
                    }
                }
                Logger.i(TAG) { "live stream finished, frames=$frames" }
            }
        }

        val request = okhttp3.Request.Builder()
            .url(url)
            .post(body)
            .header("Authorization", "Bearer $token")
            .header("X-Device-Id", deviceId)
            .build()

        val call = streamingClient.newCall(request)
        streamCall = call
        try {
            call.execute().use { resp ->
                Logger.i(TAG) { "live stream upload completed: http=${resp.code}" }
            }
        } catch (t: Throwable) {
            Logger.w(TAG) { "live stream upload failed: ${t.message}" }
        } finally {
            streamCall = null
            streaming.set(false)
            stopIfIdle()
        }
    }

    /**
     * 取帧并按流媒体规格压缩。
     *
     * 直接推原始分辨率（1920x1200）的帧在 Wi-Fi 上也有 200~400KB/帧，
     * 4fps 就是 1~2MB/s，家长端必然卡顿且画面永远是几秒前的。
     * 缩放后单帧降到几十 KB，延迟感知明显下降，画面仍然清晰可读。
     *
     * 缩放宽度与 JPEG 质量取当前生效档位（[liveFrameMaxWidth] / [liveJpegQuality]），
     * 由家长端「高清画面」开关决定 —— 早期版本这两个值是写死的常量，
     * 开关改了设备端毫不知情，正是"设了高清还是模糊"的根因。
     */
    private fun grabFrameScaled(reader: ImageReader, width: Int, height: Int): ByteArray? {
        val deadline = SystemClock.elapsedRealtime() + LIVE_FRAME_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val image = reader.acquireLatestImage()
            if (image == null) {
                SystemClock.sleep(FRAME_POLL_INTERVAL_MS)
                continue
            }
            try {
                val plane = image.planes[0]
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width
                val padPixels = rowPadding / pixelStride
                val paddedW = width + padPixels
                val (full, crop) = ensureLiveBitmaps(width, height, paddedW)
                // 把带行对齐填充的原始帧拷进 full（无逐帧分配）
                full.copyPixelsFromBuffer(plane.buffer)
                // 裁掉右侧填充：padPixels==0 时 full 已是干净帧，直接以 full 为源
                val src = if (padPixels > 0) {
                    liveCropCanvas!!.drawBitmap(
                        full, android.graphics.Rect(padPixels, 0, padPixels + width, height),
                        android.graphics.Rect(0, 0, width, height), null
                    )
                    crop
                } else full
                val maxWidth = effWidth
                val scale = (maxWidth.toFloat() / width).coerceAtMost(1f)
                liveStreamBaos.reset()
                if (scale < 1f) {
                    val sw = (width * scale).toInt()
                    val sh = (height * scale).toInt()
                    val scaled = ensureLiveScaled(sw, sh)
                    liveScaledCanvas!!.drawBitmap(
                        src, android.graphics.Rect(0, 0, width, height),
                        android.graphics.Rect(0, 0, sw, sh), null
                    )
                    scaled.compress(Bitmap.CompressFormat.JPEG, effQuality, liveStreamBaos)
                } else {
                    src.compress(Bitmap.CompressFormat.JPEG, effQuality, liveStreamBaos)
                }
                return liveStreamBaos.toByteArray()
            } catch (t: Throwable) {
                Logger.e(TAG, t) { "live frame decode failed" }
                return null
            } finally {
                image.close()
            }
        }
        return null
    }

    /** 复用全分辨率(padded)与裁剪两块缓冲；尺寸变化时才重建 */
    private fun ensureLiveBitmaps(width: Int, height: Int, paddedW: Int): Pair<Bitmap, Bitmap> {
        var full = liveFullBitmap
        if (full == null || full.width != paddedW || full.height != height) {
            full = Bitmap.createBitmap(paddedW, height, Bitmap.Config.ARGB_8888)
            liveFullBitmap = full
        }
        var crop = liveCropBitmap
        if (crop == null || crop.width != width || crop.height != height) {
            crop = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            liveCropBitmap = crop
            liveCropCanvas = Canvas(crop)
        }
        return full to crop
    }

    /** 复用缩放缓冲；仅在目标尺寸变化（自适应改分辨率）时重建 */
    private fun ensureLiveScaled(w: Int, h: Int): Bitmap {
        var s = liveScaledBitmap
        if (s == null || s.width != w || s.height != h) {
            s = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            liveScaledBitmap = s
            liveScaledCanvas = Canvas(s)
        }
        return s
    }

    /**
     * 按"上一帧编码+上传耗时"反向调节画质/分辨率。
     *
     * [costMs] 包含取帧、缩放、压 JPEG 与网络写出（含最小间隔节流）的总耗时。
     * - 超过预算的 [ADAPT_DOWN_RATIO] 倍：先降 JPEG 质量，降到下限仍不够再降分辨率；
     * - 低于预算的 [ADAPT_UP_RATIO] 倍：先恢复分辨率、再恢复质量，向档位上限回升。
     * 这样每路带宽稳定在上限附近：并发高/上行慢时自动瘦身避免挤兑式卡顿；
     * 链路宽裕时不浪费家长选定的清晰度。这是档位内的浮动，"高清/标准"选择仍是上限。
     */
    private fun adaptQuality(costMs: Long) {
        val hd = liveHighDefinition
        val ceilW = if (hd) LIVE_FRAME_MAX_WIDTH_HD else LIVE_FRAME_MAX_WIDTH_STD
        val ceilQ = if (hd) LIVE_JPEG_QUALITY_HD else LIVE_JPEG_QUALITY_STD
        val floorW = if (hd) ADAPT_FLOOR_WIDTH_HD else ADAPT_FLOOR_WIDTH_STD
        val floorQ = if (hd) ADAPT_FLOOR_QUALITY_HD else ADAPT_FLOOR_QUALITY_STD
        val budget = if (hd) HD_BUDGET_MS else STD_BUDGET_MS
        if (costMs > budget * ADAPT_DOWN_RATIO) {
            if (effQuality > floorQ) {
                effQuality = maxOf(floorQ, effQuality - ADAPT_QUALITY_STEP)
            } else if (effWidth > floorW) {
                effWidth = maxOf(floorW, (effWidth * ADAPT_WIDTH_STEP).toInt())
                // 分辨率变了，缩放缓冲必须重建（尺寸不匹配 Canvas 会抛异常）
                liveScaledBitmap = null
                liveScaledCanvas = null
            }
        } else if (costMs < budget * ADAPT_UP_RATIO) {
            if (effWidth < ceilW) {
                effWidth = minOf(ceilW, (effWidth / ADAPT_WIDTH_STEP).toInt())
                liveScaledBitmap = null
                liveScaledCanvas = null
            } else if (effQuality < ceilQ) {
                effQuality = minOf(ceilQ, effQuality + ADAPT_QUALITY_STEP)
            }
        }
    }

    // ==================== 环境音采集 ====================

    /**
     * 远程录音（环境音）。
     *
     * 与录屏共用同一个前台服务：录音需要 `FOREGROUND_SERVICE_TYPE_MICROPHONE`，
     * 该类型已在 [fgsType] 中声明，再开一个服务只会多一条常驻通知、多一份被回收的概率。
     *
     * 权限缺失时**如实失败**而不是静默录一段静音文件：家长拿到一个全静音的音频
     * 会误以为"孩子那边很安静"，这比明确告诉他"没权限"危险得多。
     */
    @Suppress("DEPRECATION")
    private fun startAudioRecord(taskId: String) {
        if (audioRecorder != null) {
            Logger.w(TAG) { "audio record already running, ignore start for $taskId" }
            stopIfIdle()
            return
        }
        // 起停竞态：stop 先于 start 完成时（指令几乎同时到达），早期实现里
        // stopAudioRecord 看到 audioTaskId=null 就直接返回，随后 start 才落地 ——
        // 结果这条录音要一直录到服务被杀，没有任何停止路径。这里记一个
        // "停止已请求"标志，start 落地后立刻自停。
        if (audioStopRequested) {
            Logger.w(TAG) { "stop was requested before audio start landed, cancel start for $taskId" }
            audioStopRequested = false
            stopIfIdle()
            return
        }
        if (!hasAudioPermission()) {
            Logger.w(TAG) { "RECORD_AUDIO not granted, cannot record audio for $taskId" }
            stopIfIdle()
            return
        }
        scope.launch {
            try {
                val out = File(cacheDir, "aud_$taskId.m4a")
                val recorder = MediaRecorder().apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(AUDIO_BIT_RATE)
                    setAudioSamplingRate(AUDIO_SAMPLE_RATE)
                    setOutputFile(out.absolutePath)
                    prepare()
                }
                recorder.start()
                audioRecorder = recorder
                audioFile = out
                audioTaskId = taskId
                audioStartedAt = System.currentTimeMillis()
                Logger.i(TAG) { "audio record started: taskId=$taskId" }
                // 与录屏同样给出最长时长兜底 —— 家长端忘了点停止时不能无限录
                // （耗电与存储都扛不住，且 MediaRecorder 有内部文件大小上限）。
                audioStopJob?.cancel()
                audioStopJob = scope.launch {
                    delay(MAX_AUDIO_RECORD_MS)
                    if (audioTaskId == taskId && audioRecorder != null) {
                        Logger.w(TAG) { "audio record reached max duration, auto stop: $taskId" }
                        stopAudioRecord()
                    }
                }
                // 起停竞态的兜底：start 落地前就收到了 stop
                if (audioStopRequested) {
                    audioStopRequested = false
                    stopAudioRecord()
                }
            } catch (t: Throwable) {
                Logger.e(TAG, t) { "audio record start failed" }
                releaseAudio()
                stopIfIdle()
            }
        }
    }

    private fun stopAudioRecord() {
        // start 协程还没落地（prepare/start 进行中）：先记请求，落地后由 start 自停
        if (audioRecorder == null && audioTaskId == null) {
            audioStopRequested = true
            return
        }
        scope.launch {
            audioStopRequested = false
            audioStopJob?.cancel()
            val taskId = audioTaskId
            val file = audioFile
            val durationSec = if (audioStartedAt > 0) {
                ((System.currentTimeMillis() - audioStartedAt) / 1000).toInt()
            } else 0

            try {
                audioRecorder?.stop()
            } catch (t: Throwable) {
                // 时长过短时 stop() 必然抛（还没编码出有效帧），此时文件不可用
                Logger.w(TAG) { "audio recorder stop: ${t.message}" }
            }
            releaseAudio()

            if (taskId != null && file != null && file.exists() && file.length() > 0) {
                when (val r = remote.uploadMedia(taskId, durationSec, MIME_AUDIO_M4A, file)) {
                    is ApiResult.Success ->
                        Logger.i(TAG) { "audio uploaded: $taskId ${file.length() / 1024}KB ${durationSec}s" }
                    else -> {
                        // 上传失败进重试队列，不再静默删除（家长端等不到这段录音）
                        Logger.w(TAG) { "audio upload failed: $r" }
                        mediaRetry.enqueue(file, MediaUploadRetry.Kind.Media(taskId, durationSec, MIME_AUDIO_M4A))
                    }
                }
            } else {
                Logger.w(TAG) { "audio record produced no file, nothing to upload" }
            }
            // 入队成功的文件已被移走；此处清理的是"没入队"的残留
            runCatching { file?.delete() }
            runCatching { mediaRetry.drain() }
            stopIfIdle()
        }
    }

    private fun releaseAudio() {
        runCatching { audioRecorder?.reset() }
        runCatching { audioRecorder?.release() }
        audioRecorder = null
        audioFile = null
        audioTaskId = null
        audioStartedAt = 0L
    }

    private fun releaseRecorder() {
        runCatching { mediaRecorder?.reset() }
        runCatching { mediaRecorder?.release() }
        mediaRecorder = null
        recordFile = null
        activeTaskId = null
        recordStartedAt = 0L
    }

    /**
     * 空闲收尾：主动释放投影并退出服务。
     *
     * **策略变更（2026-10-17）**：旧实现"只要还持有有效投影就绝不停Self"，
     * 目的是跨会话复用投影令牌免二次授权。但 Android 15 QPR1 起，只要投影活着，
     * 状态栏左上角就常驻一块系统强制的高亮芯片（"正在共享屏幕 + 计时"），孩子点它
     * 还会弹"Stop sharing screen?"并可一键掐断——监控结束后红标仍挂在孩子屏幕上，
     * 既暴露监控行为又给孩子留了反制入口，直接违背监护场景的隐蔽性要求。
     *
     * 现行分工下（截屏/实时看屏首选无障碍通道，见 [AccessibilityScreenshot] 与
     * [AccessibilityLiveStreamer]），投影只剩"录屏 + 无障碍不可用时的兜底"两个消费者，
     * 复用令牌的收益已不抵常驻芯片的代价。因此：**任务一空闲就释放投影、退出服务**，
     * 芯片随手任务结束立即消失；下次录屏需要投影时，由解锁/开机暖令牌或通知引导重新授权。
     */
    private fun stopIfIdle() {
        if (mediaRecorder != null || audioRecorder != null) return
        // 录屏进行中同样不能退 —— 早期实现只查了 mediaRecorder/audioRecorder，
        // 而现行录屏走 MediaCodec 路径（mediaRecorder 恒为 null），投影一失效服务就被
        // stopSelf，onDestroy 随之把未收尾的录制文件扔在 cacheDir 里：
        // 既传不上去（静默丢档），又变成没人认领的垃圾文件。
        if (screenRecording.get()) return
        // 推流中不能退出：服务一死投影就废，画面会断在最后一帧
        if (streaming.get()) return
        // 主动释放投影：注意暖令牌路径（ACTION_WARMUP）只有令牌、尚未取投影，
        // release() 会把刚存的令牌一并清掉导致装机向导的授权白做 —— 必须仅在有
        // 活投影时才释放。
        if (ScreenCaptureSession.projection() != null) {
            ScreenCaptureSession.release()
        }
        stopSelf()
    }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // ==================== 授权 ====================

    /**
     * 还没有采集令牌：暂存请求并引导授权。
     *
     * 从服务（后台）直接 startActivity 会被 Android 10+ 拦掉，孩子根本看不到系统授权框，
     * 家长端就永远停在"等待授权"。这里统一走高优先级通知：点击即跳转授权页，
     * 属于用户主动触发，系统必然放行。授权页拿到令牌后会重放 [pendingIntent]，无需再点一次。
     */
    private fun awaitPermission(intent: Intent) {
        pendingIntent = intent
        Logger.i(TAG) { "media projection not granted yet, requesting permission" }
        // 走统一派发口：应用在前台时直接顶起系统确认框，后台时退化为中性通知引导。
        // 正常情况下令牌已由"解锁/开机暖令牌"提前就位，这里只是兜底。
        CaptureConsentPrompter.dispatch(this, intent)
    }

    // ==================== 前台通知 ====================

    private fun enterForeground(action: String, withAudio: Boolean) {
        // 幂等守卫：同一个服务实例只允许成功进入前台一次。
        //
        // 注意与历史版本的关键差别：**失败时不能把标志位置 true**。
        // Android 14+ 上 `mediaProjection` 类型的前台服务要求调用方已持有
        // `android:project_media` appop（即用户已授权 MediaProjection）。
        // 早期实现在这里直接 startForegroundService 起服务 —— 此时还没授权，
        // 系统抛 SecurityException，但标志位却被置成 true，
        // 于是授权完成后重放请求时再也不会重试，createVirtualDisplay 必然失败，
        // 表现为"截屏/录屏永远转圈"。现在失败如实记录并保持可重试。
        //
        // 前台类型按需裁剪（实测 TB330FU / Android 14+ 的硬经验）：
        // `microphone` 类型额外要求 RECORD_AUDIO 处于"可用状态"（运行时授权 +
        // while-in-use 资格）。把 microphone 类型无条件拼进 startForeground，
        // 会因为资格不满足让**整个** startForeground 抛 SecurityException ——
        // 服务进不了前台，紧接着的 getMediaProjection 也被拒，
        // 授权令牌在 obtainProjection 的兜底清理里被丢弃，
        // 于是家长端每 16 秒一次截屏轮询，孩子端就每 16 秒弹一次授权框。
        // 纯截屏/无声录屏根本不需要麦克风，绝不为它搭上主链路。
        if (foregroundEntered) return
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            val channel = NotificationChannel(
                CHANNEL_ID, "屏幕采集", NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            manager.createNotificationChannel(channel)
        }
        val needsMic = withAudio || action == ACTION_AUDIO_RECORD
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), fgsType(needsMic))
            foregroundEntered = true
        } catch (t: Throwable) {
            if (!needsMic) {
                Logger.e(TAG, t) {
                    "startForeground failed (mediaProjection 类型要求已取得采集授权，" +
                        "必须先由授权页拿到令牌再起服务)"
                }
                return
            }
            // 麦克风资格不满足（运行时授权被策略回收 / 无 while-in-use 资格等）：
            // 退化为纯投影，保住截屏与无声录屏主链路；录音由 MediaRecorder 层自行失败并如实上报。
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), fgsType(false))
                foregroundEntered = true
                Logger.w(TAG) { "microphone fgs type rejected, degraded to projection-only (audio disabled)" }
            } catch (e: Throwable) {
                Logger.e(TAG, e) { "startForeground failed even with projection-only type" }
            }
        }
    }

    /** 未进入前台时 MediaProjection 不可用，如实告知调用方而不是静默失败 */
    private fun inForeground(): Boolean = foregroundEntered

    private fun buildNotification(): Notification {
        val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) android.app.PendingIntent.FLAG_IMMUTABLE else 0)
        val content = android.app.PendingIntent.getActivity(this, 0, Intent(), flags)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("护航守护")
            .setContentText(if (mediaRecorder != null) "屏幕录制进行中" else "屏幕采集已就绪")
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentIntent(content)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    /**
     * 前台服务类型。
     *
     * `mediaProjection` 是本服务的主类型（截屏/录屏全靠它）；
     * `microphone` 只在确实要采集声音时才带上 —— 它对调用方的
     * RECORD_AUDIO 运行时授权与 while-in-use 资格有额外硬性要求，
     * 不满足时整个 startForeground 一起失败（见 [enterForeground] 内注释）。
     */
    private fun fgsType(withMic: Boolean): Int {
        var type = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        if (withMic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return type
    }

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "padguard_capture"
        private const val NOTIFICATION_ID = 1002

        const val ACTION_SCREENSHOT = "screenshot"
        const val ACTION_SCREEN_RECORD = "screen_record"
        const val ACTION_STOP_RECORD = "stop_record"
        const val ACTION_AUDIO_RECORD = "audio_record"
        const val ACTION_STOP_AUDIO_RECORD = "stop_audio_record"
        const val ACTION_LIVE_START = "live_start"
        const val ACTION_LIVE_STOP = "live_stop"

        /**
         * 暖令牌动作：仅用于设备解锁/开机时补齐 MediaProjection 凭据。
         * 该意图不会真正到达采集逻辑——授权页在拿到令牌后即收尾，
         * 这里兜底处理（若意外被当作普通意图启动）以免空跑前台服务。
         */
        const val ACTION_WARMUP = "warmup"
        /** 标记某条采集意图属于"暖令牌"请求（只补凭据、不触发采集） */
        const val EXTRA_WARMUP = "warmup_flag"

        const val EXTRA_ACTION = "action"
        private const val EXTRA_SHOT_ID = "shotId"
        private const val EXTRA_TASK_ID = "taskId"
        private const val EXTRA_RESOLUTION = "resolution"
        private const val EXTRA_WITH_AUDIO = "withAudio"
        /** 实时看屏画质档位：true=高清。由服务端随 LIVE_VIEW_START 下发（家长端「高清画面」开关） */
        private const val EXTRA_HIGH_DEFINITION = "highDefinition"

        private const val DEFAULT_RESOLUTION = "HD_720P"

        /** 截图触发类型：远程指令触发（与 [AccessibilityScreenshot] 共用同一口径，服务端据此统计来源） */
        const val TRIGGER_REMOTE = "REMOTE"
        private const val MIME_VIDEO_MP4 = "video/mp4"
        private const val MIME_AUDIO_M4A = "audio/mp4"
        private const val VIRTUAL_DISPLAY_NAME = "padguard-shot"
        private const val IMAGE_BUFFER_COUNT = 3
        private const val JPEG_QUALITY = 80
        private const val VIDEO_FRAME_RATE = 30
        private const val AUDIO_BIT_RATE = 96_000
        private const val AUDIO_SAMPLE_RATE = 44_100
        private const val CAPTURE_TIMEOUT_MS = 3000L
        /** 取帧轮询间隔：首帧通常 100~300ms 内就绪，50ms 轮询几乎零开销 */
        private const val FRAME_POLL_INTERVAL_MS = 50L

        /** 单次录屏的最长时长，到点自动收尾（家长端忘点停止的兜底） */
        private const val MAX_RECORD_MS = 10 * 60 * 1000L

        /** 单次录音的最长时长，到点自动收尾（与录屏同思路） */
        private const val MAX_AUDIO_RECORD_MS = 30 * 60 * 1000L

        // ---- 录屏编码参数（MediaCodec 从采集帧编码）----
        /**
         * 取帧间隔：约 10fps。
         * 录屏走的是"从 ImageReader 取帧 → 画进编码器 Surface"，每帧要一次 Bitmap 转换与绘制，
         * 1200×1920 全屏下 10fps 是流畅度与 CPU 占用的平衡点；再高会明显拖慢整机。
         */
        private const val RECORD_POLL_MS = 40L
        private const val RECORD_FRAME_RATE = 10
        private const val RECORD_BIT_RATE = 4_000_000
        private const val RECORD_I_FRAME_INTERVAL = 2

        /** 收尾阶段等 EOS 的上限：正常毫秒级到达；编码器异常时到点放弃，避免在锁内死等 */
        private const val DRAIN_END_TIMEOUT_MS = 3000L

        // ---- 实时看屏推流参数 ----
        // 这三个数直接决定服务端的中转带宽（上行 1 份 + 下行 1 份 = 2 份），
        // 而看屏是"低频突发"而非持续占用，因此这里按"能看清就够"取值，不按"越清晰越好"。
        // 调整依据：看屏的目的是确认"孩子此刻在干什么"，不是看高清视频；
        // 文字与图块在 640 宽下仍可辨认，而带宽比 720 宽省掉约 20%。
        //
        // 若要再压带宽，优先降帧率而不是降分辨率：帧率决定"动作的连续感"，
        // 分辨率决定"看不看得清"。家长滑动屏幕时 4fps 尚可，降到 2fps 就明显是幻灯片了。
        /** 目标帧率 4fps：滑动时仍连贯，且比 5fps 省 20% */
        private const val LIVE_FRAME_INTERVAL_MS = 250L

        /**
         * 超过这个时间没写出任何帧（含心跳）就判定推流已死，下次 start 必须重建。
         * 不能依赖 Job.isActive —— 阻塞在 execute() 上的协程 cancel 不掉，见 [startLiveStream]。
         */
        private const val LIVE_STALE_MS = 5_000L
        /**
         * 从"启动推流"到"写出第一帧"的宽限期。
         * 必须显著大于家长端的无帧重连间隔（5s），否则两者共振：
         * 每 5 秒拆一次刚建立的连接，第一帧永远推不出去。
         */
        private const val LIVE_START_GRACE_MS = 20_000L
        /**
         * 屏幕静止时复用上一帧重发的间隔。
         * 服务端只在收到**真实帧**时刷新 lastFrameAt，心跳帧不算；
         * 家长端靠 lastFrameAt 判断流是否还活着，所以静止画面也必须周期性重发一帧。
         * 屏幕真的没变化时，重发同一帧也只是让画面"保持在线"——间隔太长（如 1s）
         * 看起来就是"几秒刷新一次图片"，不是实时画面。取 ~3fps：
         * 单帧约 16KB → 约 48KB/s（≈384kbps），局域网完全扛得住，观感是连续的。
         * 有真实新帧时不走这里，立即推送，不受本间隔限制。
         */
        private const val LIVE_KEEPALIVE_MS = 300L
        /**
         * 单帧等待上限。
         * 屏幕有变化时 acquireLatestImage 立刻返回，本值不起作用；
         * 只有静止时才等满这个时长 —— 它直接决定了静止画面的重发频率。
         * 取 250ms（配合 [LIVE_KEEPALIVE_MS]）约 3fps：观感是连续的画面，
         * 若沿用 1s 就会退化成"一秒换一张图"，被当成"在刷新图片而不是看实时画面"。
         */
        private const val LIVE_FRAME_TIMEOUT_MS = 250L
        /**
         * 标准档推流宽度。
         *
         * 640 宽在平板上（典型 1200~1600 宽屏）缩放到约 40%，
         * 小字号与图标细节会糊成一团 —— 这正是"画面根本看不清"的来源。
         * 提到 860 后缩放比约 60%，正文文字边缘清晰，单帧体积仍可控（约 40~70KB）。
         */
        private const val LIVE_FRAME_MAX_WIDTH_STD = 860
        /** 高清档推流宽度：接近常见平板的物理宽度，文字与图标基本无损 */
        private const val LIVE_FRAME_MAX_WIDTH_HD = 1440

        /**
         * 标准档 JPEG 质量。
         *
         * 50 是"肉眼勉强接受"的下限，文字边缘会出现明显块状噪点；
         * 提到 70 后噪点基本消失，体积只增加约 20%——清晰度的收益远大于这点开销。
         */
        private const val LIVE_JPEG_QUALITY_STD = 70
        /** 高清档 JPEG 质量：文字锐利，与 70 相比细节差异肉眼可辨 */
        private const val LIVE_JPEG_QUALITY_HD = 92

        /**
         * 最小出帧间隔（写出真实帧时的节流下限）。
         *
         * 帧泵里只有"没取到新帧"时才 sleep，屏幕持续变化（播放视频、连续滑动）时
         * 会尽可能快地推帧。标准档单帧几十 KB 尚可承受；高清档单帧约 200~300KB，
         * 不限帧的话瞬时上行可达数 MB/s —— 家长端来不及收、设备自身编码也扛不住，
         * 结果反而是画面更卡。因此按档位给一个下限：高清约 6fps、标准约 8fps，
         * 都高于"看得连贯"所需的帧率，观感不受影响，上行则被稳定压住。
         */
        private const val LIVE_MIN_FRAME_INTERVAL_STD_MS = 120L
        private const val LIVE_MIN_FRAME_INTERVAL_HD_MS = 160L
        /**
         * 单次推流的最长时长。
         * 家长端观看期间会周期性续期（重新下发 start），一旦家长关掉页面或掉线，
         * 续期停止，推流在这里自动结束 —— 不会有一条流永远推下去耗孩子的电和流量。
         */
        private const val LIVE_TTL_MS = 180_000L

        // ---- 自适应码率参数（在家长选定的档位内浮动，见 [adaptQuality]）----
        // 并发一高，每路都按最高画质猛推会把服务端出口带宽打满、各路一起卡。
        // 按"单帧编码+上传耗时"反向调节：超预算就降质量/分辨率，从容就恢复。
        // 这些下限是"档位内的地板"，保证再怎么自适应也不会糊到看不清。
        private const val ADAPT_FLOOR_WIDTH_HD = 1024
        private const val ADAPT_FLOOR_QUALITY_HD = 72
        private const val ADAPT_FLOOR_WIDTH_STD = 720
        private const val ADAPT_FLOOR_QUALITY_STD = 60
        /** 质量步长：每次下调/恢复 6，避免画质跳变突兀 */
        private const val ADAPT_QUALITY_STEP = 6
        /** 分辨率步长系数：每次乘 0.85（下调）或除 0.85（恢复） */
        private const val ADAPT_WIDTH_STEP = 0.85f
        /** 耗时超预算的倍数即下调；低于该比例即尝试恢复 */
        private const val ADAPT_DOWN_RATIO = 1.5f
        private const val ADAPT_UP_RATIO = 0.55f
        /** 单帧预算（编码+上传）：高清档略宽，标准档更紧 */
        private const val HD_BUDGET_MS = 170L
        private const val STD_BUDGET_MS = 120L

        /** 等待授权期间暂存的采集请求（进程内，仅在授权流程中短暂存在） */
        @Volatile var pendingIntent: Intent? = null

    fun captureScreenshot(context: Context, shotId: String) {
        val intent = Intent(context, ScreenCaptureService::class.java)
            .putExtra(EXTRA_ACTION, ACTION_SCREENSHOT)
            .putExtra(EXTRA_SHOT_ID, shotId)
        start(context, intent)
    }

    /** 仅构建截屏采集 Intent（供 GuardService 在需要授权时包装成可点击通知） */
    fun screenshotIntent(context: Context, shotId: String): Intent =
        Intent(context, ScreenCaptureService::class.java)
            .putExtra(EXTRA_ACTION, ACTION_SCREENSHOT)
            .putExtra(EXTRA_SHOT_ID, shotId)

    fun startScreenRecord(context: Context, taskId: String, resolution: String, withAudio: Boolean) {
        val intent = Intent(context, ScreenCaptureService::class.java)
            .putExtra(EXTRA_ACTION, ACTION_SCREEN_RECORD)
            .putExtra(EXTRA_TASK_ID, taskId)
            .putExtra(EXTRA_RESOLUTION, resolution)
            .putExtra(EXTRA_WITH_AUDIO, withAudio)
        start(context, intent)
    }

    /** 仅构建录屏采集 Intent（供 GuardService 在需要授权时包装成可点击通知） */
    fun screenRecordIntent(context: Context, taskId: String, resolution: String, withAudio: Boolean): Intent =
        Intent(context, ScreenCaptureService::class.java)
            .putExtra(EXTRA_ACTION, ACTION_SCREEN_RECORD)
            .putExtra(EXTRA_TASK_ID, taskId)
            .putExtra(EXTRA_RESOLUTION, resolution)
            .putExtra(EXTRA_WITH_AUDIO, withAudio)

        fun stopScreenRecord(context: Context) {
            stopDirect(context, Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_ACTION, ACTION_STOP_RECORD))
        }

        fun startAudioRecord(context: Context, taskId: String) {
            val intent = Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_ACTION, ACTION_AUDIO_RECORD)
                .putExtra(EXTRA_TASK_ID, taskId)
            start(context, intent)
        }

        fun stopAudioRecord(context: Context) {
            stopDirect(context, Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_ACTION, ACTION_STOP_AUDIO_RECORD))
        }

        /** 仅构建实时推流停止 Intent（直达服务用，不经授权派发） */
        private fun liveStreamStopIntent(context: Context): Intent =
            Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_ACTION, ACTION_LIVE_STOP)

        /**
         * 停止/控制类意图**直达服务**，绝不经过 [CaptureConsentPrompter.dispatch]。
         *
         * 为什么必须有这条独立通道（历史 bug：停止监控反而弹授权框）：
         * 旧实现把 ACTION_LIVE_STOP 等停止指令与采集指令混走同一条 [start] 路由，
         * 令牌缺失时 dispatch 会把"停止看屏"包装进授权页 —— 孩子端在监控**结束**的
         * 一瞬间弹出"Share your screen with 护航成长?"系统确认框，荒谬且暴露监控。
         * 早期未暴露是因为旧策略令牌常驻（hasToken 恒真走静默分支）；
         * 自"任务空闲即释放投影"与"看屏走无障碍通道"起，停止时无令牌成为常态。
         *
         * 用普通 startService（非 startForegroundService）：GuardService 自身是前台服务，
         * 应用不处于后台态，startService 合法；服务处理完停止动作即 stopSelf，
         * 不触发"必须 5s 内 startForeground"的义务，也不需要任何采集授权。
         */
        private fun stopDirect(context: Context, intent: Intent) {
            runCatching {
                context.startService(intent)
            }.onFailure { Logger.w(TAG) { "failed to route capture stop: ${it.message}" } }
        }

        /**
         * 解绑即清本机采集媒体残留。
         *
         * 采集产物全部直写 cacheDir（[PhotoCapture] 的 photo_*.jpg、截屏 shot_*.jpg、
         * 录屏 rec_*.mp4、录音 aud_*.m4a），与 Room/DataStore 里的记录是同一边界：
         * 都属于"旧家庭在本设备上的管控痕迹"。只清数据库不清文件，
         * 平板转手后旧家庭的录音录像仍躺在 cacheDir，隐私承诺不可信。
         *
         * 调用时序：必须在 stopScreenRecord / stopAudioRecord 之后 ——
         * 先确保没有正在写入的句柄，再删文件。失败由调用方 runCatching 兜住。
         */
        fun wipeLocalMedia(context: Context) {
            val cache = context.cacheDir ?: return
            val victims = cache.listFiles { f ->
                f.isFile && (
                    f.name.startsWith("shot_") || f.name.startsWith("photo_") ||
                        f.name.startsWith("rec_") || f.name.startsWith("aud_")
                    )
            } ?: return
            var deleted = 0
            victims.forEach { f -> if (runCatching { f.delete() }.getOrDefault(false)) deleted++ }
            Logger.w(TAG) { "capture cache wiped on unbind: $deleted/${victims.size} files" }
        }

        /** 开启实时看屏推流（家长端打开看屏页时调用，观看期间会周期性续期） */
        fun startLiveStream(context: Context, highDefinition: Boolean = false) {
            start(context, liveStreamIntent(context, highDefinition))
        }

        /** 仅构建实时推流 Intent（供 GuardService 在需要授权时包装成可点击通知） */
        fun liveStreamIntent(context: Context, highDefinition: Boolean = false): Intent =
            Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_ACTION, ACTION_LIVE_START)
                .putExtra(EXTRA_HIGH_DEFINITION, highDefinition)

        fun stopLiveStream(context: Context) {
            stopDirect(context, liveStreamStopIntent(context))
        }

        /**
         * 暖令牌意图：设备解锁/开机时由 [com.padguard.child.capture.CaptureConsentPrompter]
         * 派发，目的是在孩子端尚未持有 MediaProjection 凭据时补一次系统确认，
         * 让令牌就位；不携带任何真实采集动作。授权页据此只存令牌、不启动采集。
         */
        fun warmupIntent(context: Context): Intent =
            Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_ACTION, ACTION_WARMUP)
                .putExtra(EXTRA_WARMUP, true)

        /** 授权成功后重放暂存请求 */
        fun replayPending(context: Context) {
            val pending = pendingIntent ?: return
            pendingIntent = null
            // 此时用户已授权，appop 到位，起前台服务是合法的。
            // 若服务已在运行，startForegroundService 只会回调 onStartCommand，不会重建实例。
            startAuthorized(context, pending)
        }

        /**
         * 采集请求的统一入口。
         *
         * **必须先过授权页，不能直接 startForegroundService**（Android 14+ 硬约束）：
         * `mediaProjection` 类型的前台服务要求调用方持有 `android:project_media` appop，
         * 而该 appop 只有在用户通过系统弹窗确认后才会授予。
         * 直接从后台（GuardService）起服务必然抛 SecurityException，
         * 且此时还没有投影，`createVirtualDisplay` 也无米下锅 —— 截屏/录屏全线失效。
         *
         * 因此路由是：GuardService → 授权页（前台 Activity）→ 拿到令牌 → 起前台服务 → 采集。
         * 授权页在已持有令牌时会直接放行，不会重复弹窗打扰孩子。
         */
        private fun start(context: Context, intent: Intent) {
            runCatching {
                CaptureConsentPrompter.dispatch(context, intent)
            }.onFailure { Logger.e(TAG, it) { "failed to route capture request" } }
        }

        /** 已取得授权后由授权页调用：此刻起前台服务才是合法的 */
        @JvmStatic
        fun startAuthorized(context: Context, intent: Intent) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Logger.e(TAG, it) { "failed to start ScreenCaptureService" } }
        }

        // 注：曾有 resolutionSize()（把家长端下发的画质档位换算成像素），
        // 但录屏编码路径从未调用它——能力未上线就不留暗示它存在的死代码。
        // 画质档位真正生效时（连同 AAC 音轨混流一起排期），随编码参数一并重建。
    }
}
