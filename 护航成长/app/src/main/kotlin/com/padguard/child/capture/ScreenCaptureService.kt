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
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.widget.Toast
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.padguard.core.common.Logger
import com.padguard.core.transport.RemoteDataSource
import com.padguard.core.transport.http.ApiResult
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
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
 * **服务生命周期与投影绑定（关键）**：`MediaProjection` 与"带 mediaProjection 类型的前台服务"
 * 在 Android 14+ 上是一体的 —— 服务实例一旦销毁，投影随即失效。表现为：首次截屏成功、服务
 * `stopSelf()` 退出后，第二次截屏会重新 `startForegroundService` 起一个新服务实例，系统视之为
 * "foreground service change"，在 `createVirtualDisplay` 前就把缓存投影回收掉，日志形如：
 * `MediaProjectionManagerService: Stopped MediaProjection due to foreground service change`
 * → `SecurityException: Cannot create VirtualDisplay with non-current MediaProjection`。
 *
 * 因此只要 [ScreenCaptureSession] 里还持有有效投影，本服务就必须**继续存活**（详见 [stopIfIdle]），
 * 这样后续截屏/录屏都在同一个 FGS 会话内复用投影，无需再次授权；投影失效时由
 * [ScreenCaptureSession.setOnProjectionLost] 回调通知服务退出。
 */
@AndroidEntryPoint
class ScreenCaptureService : Service() {

    @Inject lateinit var remote: RemoteDataSource

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
            stopIfIdle()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra(EXTRA_ACTION)
        if (action == null) {
            stopSelf()
            return START_STICKY
        }
        // 前台类型必须与本次任务匹配（见 [enterForeground]）：
        // 先解析动作再进前台，截屏这类纯投影任务绝不携带 microphone 类型。
        enterForeground(action, intent.getBooleanExtra(EXTRA_WITH_AUDIO, false))
        when (action) {
            ACTION_SCREENSHOT -> captureScreenshot(intent.getStringExtra(EXTRA_SHOT_ID).orEmpty(), intent)
            ACTION_SCREEN_RECORD -> startScreenRecord(
                taskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty(),
                resolution = intent.getStringExtra(EXTRA_RESOLUTION) ?: DEFAULT_RESOLUTION,
                withAudio = intent.getBooleanExtra(EXTRA_WITH_AUDIO, false),
                intent = intent
            )
            ACTION_STOP_RECORD -> stopScreenRecord()
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
        releaseCaptureSession()
        releaseRecorder()
        releaseAudio()
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
            else -> Logger.w(TAG) { "screenshot upload failed: $result" }
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
                val plane = image.planes[0]
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width
                val padded = Bitmap.createBitmap(
                    width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
                )
                padded.copyPixelsFromBuffer(plane.buffer)
                // 行对齐带来的右侧黑边必须裁掉，否则家长端看到的截图右边有一条黑带
                val frame = if (rowPadding == 0) padded
                else Bitmap.createBitmap(padded, 0, 0, width, height)
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

    // ==================== 录屏 ====================

    @Suppress("DEPRECATION")
    private fun startScreenRecord(taskId: String, resolution: String, withAudio: Boolean, intent: Intent) {
        if (mediaRecorder != null) {
            Logger.w(TAG) { "screen record already running, ignore start for $taskId" }
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
                val metrics = resources.displayMetrics
                val (w, h) = resolutionSize(resolution, metrics.widthPixels, metrics.heightPixels)
                val out = File(cacheDir, "rec_$taskId.mp4")

                val recorder = MediaRecorder().apply {
                    if (withAudio && hasAudioPermission()) {
                        setAudioSource(MediaRecorder.AudioSource.MIC)
                    }
                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    if (withAudio && hasAudioPermission()) {
                        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    }
                    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    setVideoSize(w, h)
                    setVideoFrameRate(VIDEO_FRAME_RATE)
                    setOutputFile(out.absolutePath)
                    prepare()
                }

                mediaRecorder = recorder
                recordFile = out
                activeTaskId = taskId
                recordStartedAt = System.currentTimeMillis()

                // 复用常驻 VirtualDisplay：不能为同一投影再建第二个 VD（Android 14+ 禁止）。
                // 编码器分辨率与屏幕分辨率不同，必须先 resize 再切 Surface —— 否则输出与编码器
                // 输入不匹配，编码器几乎收不到帧（实测 11s 只录出 5KB）。
                // 录完在 stopScreenRecord 里恢复屏幕尺寸，保证截图继续可用。
                ensureCaptureSession(projection, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
                sharedDisplay?.setSurface(null)
                sharedDisplay?.resize(w, h, metrics.densityDpi)
                sharedDisplay?.setSurface(recorder.surface)
                recorder.start()
                Logger.i(TAG) { "screen record started: taskId=$taskId ${w}x$h audio=$withAudio" }
            } catch (t: Throwable) {
                Logger.e(TAG, t) { "screen record start failed" }
                // P2 修复：之前失败只记日志、被控端无任何提示，家长端也收不到失败信号。
                // 这里给出本机可感知的提示，孩子立刻知道录屏没起来（多半是未授权屏幕采集）。
                // 注意：此处在协程内，this 是 CoroutineScope 而非 Context，必须用 applicationContext。
                Toast.makeText(
                    applicationContext,
                    "录屏启动失败：${t.message ?: "未知错误"}（请先授权屏幕采集）",
                    Toast.LENGTH_LONG
                ).show()
                releaseRecorder()
                stopIfIdle()
            }
        }
    }

    private fun stopScreenRecord() {
        scope.launch {
            val taskId = activeTaskId
            val file = recordFile
            val durationSec = if (recordStartedAt > 0) {
                ((System.currentTimeMillis() - recordStartedAt) / 1000).toInt()
            } else 0

            try {
                mediaRecorder?.stop()
            } catch (t: Throwable) {
                // 录制时长过短时 stop() 会抛（还没产出有效帧），此时文件不可用，如实记录即可
                Logger.w(TAG) { "recorder stop: ${t.message}" }
            }
            releaseRecorder()
            // 录屏结束：把常驻 VirtualDisplay resize 回屏幕尺寸并切回截图用的 ImageReader
            runCatching {
                val m = resources.displayMetrics
                sharedDisplay?.setSurface(null)
                sharedDisplay?.resize(m.widthPixels, m.heightPixels, m.densityDpi)
                sharedDisplay?.setSurface(frameReader?.surface)
            }

            if (taskId != null && file != null && file.exists() && file.length() > 0) {
                when (val r = remote.uploadMedia(taskId, durationSec, MIME_VIDEO_MP4, file)) {
                    is ApiResult.Success -> Logger.i(TAG) { "screen record uploaded: $taskId ${file.length() / 1024}KB" }
                    else -> Logger.w(TAG) { "screen record upload failed: $r" }
                }
            } else {
                Logger.w(TAG) { "screen record produced no file, nothing to upload" }
            }
            runCatching { file?.delete() }
            stopIfIdle()
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
            } catch (t: Throwable) {
                Logger.e(TAG, t) { "audio record start failed" }
                releaseAudio()
                stopIfIdle()
            }
        }
    }

    private fun stopAudioRecord() {
        scope.launch {
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
                    else -> Logger.w(TAG) { "audio upload failed: $r" }
                }
            } else {
                Logger.w(TAG) { "audio record produced no file, nothing to upload" }
            }
            runCatching { file?.delete() }
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
     * 空闲收尾：决定本服务是否退出。
     *
     * **只要 [ScreenCaptureSession] 还持有有效投影，就绝不能 stopSelf**（见类注释）：
     * 服务一退出，投影就被系统标记为"非当前"，后续每次截屏都会以
     * `Cannot create VirtualDisplay with non-current MediaProjection` 失败并被迫重新授权。
     * 因此服务与投影同生命周期 —— 投影丢失时由 [ScreenCaptureSession] 的失效回调负责收尾。
     */
    private fun stopIfIdle() {
        if (mediaRecorder != null || audioRecorder != null) return
        if (ScreenCaptureSession.projection() != null) return
        stopSelf()
    }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // ==================== 授权 ====================

    /**
     * 还没有采集令牌：暂存请求并拉起授权页。
     * 授权页拿到令牌后会重放 [pendingIntent]，用户无需再点一次。
     */
    private fun awaitPermission(intent: Intent) {
        pendingIntent = intent
        Logger.i(TAG) { "media projection not granted yet, requesting permission" }
        CapturePermissionActivity.startWith(this, intent)
    }

    // ==================== 前台通知 ====================

    private fun enterForeground(action: String, withAudio: Boolean) {
        // 幂等守卫：同一个服务实例只允许成功进入前台一次。
        //
        // 注意与历史版本的关键差别：**失败时不能把标志位置 true**。
        // Android 14+ 上 `mediaProjection` 类型的前台服务要求调用方已持有
        // `android:project_media` appop（即用户已授权 MediaProjection）。
        // 旧实现在这里直接 startForegroundService 起服务 —— 此时还没授权，
        // 系统抛 SecurityException，但标志位却被置成 true，
        // 于是授权完成后重放请求时再也不会重试，createVirtualDisplay 必然失败，
        // 表现为"截屏/录屏永远转圈"。现在失败如实记录并保持可重试。
        //
        // 前台类型按需裁剪（实测 TB330FU / Android 14+ 的硬教训）：
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

        const val EXTRA_ACTION = "action"
        private const val EXTRA_SHOT_ID = "shotId"
        private const val EXTRA_TASK_ID = "taskId"
        private const val EXTRA_RESOLUTION = "resolution"
        private const val EXTRA_WITH_AUDIO = "withAudio"

        private const val DEFAULT_RESOLUTION = "HD_720P"
        private const val TRIGGER_REMOTE = "REMOTE"
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
            val intent = Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_ACTION, ACTION_STOP_RECORD)
            start(context, intent)
        }

        fun startAudioRecord(context: Context, taskId: String) {
            val intent = Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_ACTION, ACTION_AUDIO_RECORD)
                .putExtra(EXTRA_TASK_ID, taskId)
            start(context, intent)
        }

        fun stopAudioRecord(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_ACTION, ACTION_STOP_AUDIO_RECORD)
            start(context, intent)
        }

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
                CapturePermissionActivity.startWith(context, intent)
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

        /** 把家长端下发的分辨率档位换算成实际像素；超出屏幕物理分辨率时按屏幕尺寸回落 */
        private fun resolutionSize(resolution: String, screenW: Int, screenH: Int): Pair<Int, Int> {
            val (w, h) = when (resolution.uppercase()) {
                "SD_480P" -> 720 to 480
                "FHD_1080P", "FULL_HD_1080P" -> 1920 to 1080
                else -> 1280 to 720
            }
            return w.coerceAtMost(screenW.coerceAtLeast(1)) to h.coerceAtMost(screenH.coerceAtLeast(1))
        }
    }
}
