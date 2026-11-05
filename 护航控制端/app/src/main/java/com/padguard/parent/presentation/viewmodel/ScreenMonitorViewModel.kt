package com.padguard.parent.presentation.viewmodel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.parent.domain.model.ScreenMonitorSettings
import com.padguard.parent.domain.model.ScreenRecordTask
import com.padguard.parent.domain.model.ScreenshotData
import com.padguard.parent.domain.repository.DeviceRepository
import com.padguard.parent.domain.repository.MonitorRepository
import com.padguard.parent.domain.repository.PolicyRepository
import com.padguard.parent.presentation.util.GallerySaver
import com.padguard.parent.presentation.util.TimeFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

/**
 * 屏幕监控 ViewModel（实时管控 > 屏幕监控）
 *
 * 职责：实时画面、截屏、录屏（开始 / 停止与计时）、远程锁屏、屏幕监控设置、截屏历史。
 * 锁屏能力复用 [PolicyRepository.lockScreen]，不在此重复定义指令下发。
 *
 * ## 实时画面是「流」不是「轮询」
 * 家长端看到的是孩子平板屏幕的实时视频流：孩子端持续采集屏幕帧并推到服务端，
 * 服务端原样中继，家长端通过一条长连接逐帧接收（[MonitorRepository.liveFrames]）。
 * 因此不存在"几秒刷新一张图"的延迟，孩子屏幕上是什么家长就看到什么。
 *
 * 流有 TTL（孩子端默认 180s 自动停推），家长端在观看期间每 [LIVE_KEEPALIVE_MS]
 * 续期一次，保证长时间挂在页面也不会断流。
 *
 * ## 截屏
 * 截屏直接取当前实时帧落相册：既不需要孩子端再授权，也不产生额外指令往返，
 * 所见即所存。
 */
data class ScreenMonitorUiState(
    val settings: ScreenMonitorSettings = ScreenMonitorSettings(deviceId = ""),
    val settingsDirty: Boolean = false,
    val screenshot: ScreenshotData? = null,
    val liveImage: Bitmap? = null,
    /**
     * 最近一帧**实时画面**的到达时间（本机墙钟，毫秒）。
     *
     * 与 [ScreenshotData.capturedAt] 是两件事：后者是"上次截屏"的时间，
     * 拿它当"更新于"会让家长在连续看流时看到几小时前的旧时间，误判画面是否还在动。
     */
    val liveUpdatedAt: Long = 0L,
    /** 实时流是否已建立（已收到至少一帧） */
    val liveConnected: Boolean = false,
    val fetchingFrame: Boolean = false,
    /**
     * 画面迟迟不来时的原因提示。
     *
     * 没有它，家长只会看到一个永远转圈的「正在连接实时画面…」，
     * 既不知道要不要等，也不知道该去平板上点一下授权 ——
     * 而孩子端缺 MediaProjection 授权恰恰是最常见的原因。
     */
    val liveHint: String? = null,
    /**
     * 孩子端 Android 主版本号（如 10 / 14），未知时为 null。
     *
     * 用途只有一个：判断这台设备**有没有可能**免授权看屏。
     * 零弹窗通道依赖 `AccessibilityService.takeScreenshot()`，那是 **API 30（Android 11）** 才有的能力；
     * 低于它的设备只能走 MediaProjection，系统必然弹一次「是否允许录制屏幕」。
     * 不提前说明的话，家长会把这个必然动作当成故障。
     */
    val deviceAndroidMajor: Int? = null,
    /** 平板当前是否处于家长发起的远程锁屏状态；false 时按钮显示"锁屏"，true 时显示"解锁" */
    val remoteLocked: Boolean = false,
    val screenshotHistory: List<ScreenshotData> = emptyList(),
    val recording: ScreenRecordTask? = null,
    val recordingSeconds: Int = 0,
    val lastRecordSummary: String? = null,
    /** 最近一次录屏的文件地址（孩子端上传完成后由轮询填入），非空即可回放 */
    val lastRecordUrl: String? = null,
    /** 最近一次录屏在本机相册（Movies/PadGuard）保存后的 URI，非空表示已留存到家长端平板 */
    val lastRecordSavedPath: String? = null,
    val isLoading: Boolean = false,
    val toast: String? = null,
    val error: String? = null,
    /** 实时看屏上行带宽（KB/s）：服务端 1s 滚动窗口统计的字节速率 ÷ 1024 */
    val liveUpKbps: Double = 0.0,
    /** 实时看屏下行带宽（KB/s）：服务端转发给本家长端占用的带宽 */
    val liveDownKbps: Double = 0.0
) {
    val isRecording: Boolean get() = recording != null
}

@HiltViewModel
class ScreenMonitorViewModel @Inject constructor(
    private val monitorRepository: MonitorRepository,
    private val policyRepository: PolicyRepository,
    private val deviceRepository: DeviceRepository,
    private val gallerySaver: GallerySaver,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val deviceId: String = savedStateHandle.get<String>("deviceId").orEmpty()

    private val _uiState = MutableStateFlow(ScreenMonitorUiState())
    val uiState: StateFlow<ScreenMonitorUiState> = _uiState.asStateFlow()

    /** 录屏计时协程。停止录屏 / ViewModel 销毁时必须取消，避免协程泄漏。 */
    private var recordTicker: Job? = null

    /** 实时拉流协程；随 ViewModel 生命周期终止 */
    private var liveJob: Job? = null

    /** 独立于 viewModelScope：onCleared 时 viewModelScope 已取消，停流指令需要一个还活着的 scope。 */
    private val releaseScope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        load()
    }

    fun load() {
        if (deviceId.isBlank()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            monitorRepository.getScreenMonitorSettings(deviceId)
                .onSuccess { _uiState.value = _uiState.value.copy(settings = it, settingsDirty = false) }
                .onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
            // 远程锁屏状态必须每次进页面都重新拉：家长可能换过手机、或在别的端上锁过屏。
            // 用本地记忆会导致"明明锁着却显示锁屏按钮"，点了等于什么都没发生。
            deviceRepository.getDeviceList()
                .onSuccess { list ->
                    val mine = list.firstOrNull { it.deviceId == deviceId || it.id == deviceId }
                    mine?.let {
                        _uiState.value = _uiState.value.copy(
                            remoteLocked = it.remoteLocked,
                            deviceAndroidMajor = it.osVersion?.trim()?.takeWhile { c -> c.isDigit() }?.toIntOrNull()
                        )
                    }
                }
            _uiState.value = _uiState.value.copy(isLoading = false)
            startLiveStream()
        }
    }

    /**
     * 老系统「必然要弹一次授权」的预期说明。
     *
     * 零弹窗看屏依赖 `AccessibilityService.takeScreenshot()`，该 API 自 **Android 11（API 30）** 起才有。
     * 更早的机型（本项目的存量平板就停在 Android 10）无论无障碍是否开启，都只能走 MediaProjection，
     * 而 MediaProjection 的授权是系统隐私红线，设备所有者（DO）也绕不过去。
     *
     * 不提前说明的后果，我们在现场见过：家长点「实时看屏」，平板上弹出一个系统框，
     * 家长以为"上次不是能看吗，怎么又要授权"，把它当成 bug 来报。
     */
    private fun showLegacyCaptureExpectation() {
        val major = _uiState.value.deviceAndroidMajor ?: return
        if (major >= 11) return
        _uiState.value = _uiState.value.copy(
            liveHint = "这台平板是 Android $major，系统不支持免授权看屏：平板上会弹出" +
                "「是否允许录制屏幕」，需要孩子点一次「立即开始」。同一次开机内不会重复弹出。"
        )
    }

    /**
     * 建立实时看屏流：先下发开流指令让孩子端开始推帧，再消费长连接逐帧刷新画面。
     *
     * - 断流后自动重连（孩子端重启 / 网络抖动），不打断家长观看体验；
     * - 观看期间定时续期，避免孩子端 TTL 到期自动停推。
     */
    private fun startLiveStream() {
        if (deviceId.isBlank()) return
        liveJob?.cancel()
        liveJob = viewModelScope.launch {
            // 开流前先把"这台设备上必然会发生的那一次授权"讲清楚。
            // 与"迟迟没有画面"的提示是两回事：那个是异常兜底，这个是**预期说明**——
            // Android 10 及更早的机型没有无障碍截屏能力，弹授权不是故障，而是系统限制。
            showLegacyCaptureExpectation()
            // 连续"没出画"的次数：用来做指数退避，避免平板离线时每 5 秒一次开流
            // （一小时 720 次）把服务端和孩子端一起打满。
            var noFrameAttempts = 0
            while (coroutineContext.isActive) {
                _uiState.value = _uiState.value.copy(fetchingFrame = true)
                monitorRepository.startLiveView(deviceId).onFailure { e ->
                    val reason = e.message ?: "未知错误"
                    if (noFrameAttempts == 0) {
                        // 第一次失败给 toast；后续重试只更新占位文案，否则会每轮弹一次
                        _uiState.value = _uiState.value.copy(
                            liveConnected = false,
                            fetchingFrame = false,
                            toast = "开启实时画面失败：$reason"
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(liveHint = "开启实时画面失败：$reason（自动重连中）")
                    }
                }

                // 续期：孩子端推流有 TTL，这里在观看期间持续续命
                val keepAlive = launch {
                    while (coroutineContext.isActive) {
                        delay(LIVE_KEEPALIVE_MS)
                        runCatching { monitorRepository.startLiveView(deviceId) }
                    }
                }

                // 带宽遥测：每 2s 拉一次 /stream/{deviceId}/status，
                // 让家长端实时显示"上行 / 下行"带宽占用，
                // 直接回答"实时看屏占了多少带宽、并发高时会不会被打满"。
                val bandwidthPoller = launch {
                    while (coroutineContext.isActive) {
                        delay(LIVE_BANDWIDTH_POLL_MS)
                        monitorRepository.getLiveStatus(deviceId)
                            .onSuccess { st ->
                                _uiState.value = _uiState.value.copy(
                                    liveUpKbps = st.upstreamBps / 1024.0,
                                    liveDownKbps = st.downstreamBps / 1024.0
                                )
                            }
                    }
                }

                var gotFrame = false
                try {
                    // 首帧迟迟不来时把真实原因告诉家长，而不是让他对着转圈干等。
                    // 最常见的原因就是孩子端没有屏幕采集授权（首次装机 / 平板重启后令牌失效），
                    // 此时平板上会弹出系统的「是否允许录制屏幕」，必须由孩子本人点一次「立即开始」。
                    val firstFrameHint = launch {
                        delay(LIVE_FIRST_FRAME_HINT_MS)
                        if (!gotFrame) {
                            _uiState.value = _uiState.value.copy(
                                liveHint = "画面迟迟未到：请确认平板已开机联网；" +
                                    "若平板上弹出了「是否允许录制屏幕」，需点一次「立即开始」"
                            )
                        }
                    }
                    monitorRepository.liveFrames(deviceId)
                        .onStart { _uiState.value = _uiState.value.copy(fetchingFrame = true) }
                        .catch { e ->
                            // 断流原因写进 liveHint（页面已渲染）而不是 error（页面从不渲染）：
                            // 原来断流后家长只看到"等待平板上传实时画面"，以为是平板的问题。
                            _uiState.value = _uiState.value.copy(
                                liveConnected = false,
                                fetchingFrame = false,
                                liveHint = "实时画面中断：${e.message ?: "网络异常"}（自动重连中）"
                            )
                        }
                        .onCompletion { keepAlive.cancel(); firstFrameHint.cancel(); bandwidthPoller.cancel() }
                        .collect { bitmap ->
                            gotFrame = true
                            noFrameAttempts = 0
                            firstFrameHint.cancel()
                            _uiState.value = _uiState.value.copy(
                                liveImage = bitmap,
                                liveUpdatedAt = System.currentTimeMillis(),
                                liveConnected = true,
                                fetchingFrame = false,
                                liveHint = null,
                                error = null
                            )
                        }
                } finally {
                    keepAlive.cancel()
                    bandwidthPoller.cancel()
                }

                _uiState.value = _uiState.value.copy(
                    liveConnected = false,
                    fetchingFrame = false,
                    // 断流后清空画面：离线/停止推流时不应再显示最后一帧（可能已是很久以前的旧画面），
                    // 否则家长会误以为仍在看实时画面。清空后页面回到「等待平板上传实时画面」占位态。
                    liveImage = null
                )
                // 正常退出页面 / 被主动停止：不再重连
                if (!coroutineContext.isActive) break
                // 出过画后再断：1.5s 快速重连（多半是网络抖动，家长几乎无感）
                if (gotFrame) {
                    noFrameAttempts = 0
                    delay(LIVE_RECONNECT_MS)
                } else {
                    // 一直没出画：多半是平板离线 / 未授权采集，指数退避（3→6→12→24→48→60s 封顶），
                    // 避免每 5 秒发一次 startLiveView 空转。
                    noFrameAttempts++
                    delay(reconnectDelayMs(noFrameAttempts))
                }
            }
        }
    }

    /** 手动重连实时画面（占位态下家长可主动触发）。 */
    fun refreshScreenshot() {
        if (deviceId.isBlank()) return
        // 手动重连要立刻给反馈：清掉上一次的断流原因，页面回到"正在连接…"
        _uiState.value = _uiState.value.copy(liveHint = null, fetchingFrame = true)
        startLiveStream()
    }

    /**
     * 截屏：把当前实时帧存入系统相册。
     * 取的是家长此刻看到的画面，无需孩子端配合，也不会打断实时流。
     */
    fun captureScreenshot() {
        if (deviceId.isBlank()) return
        val frame = _uiState.value.liveImage
        // 实时流没画面时不能就此放弃：回落到服务端"下发截屏指令 + 轮询取图"通道，
        // 两条路互为备份，任何一条可用家长都能拿到画面。
        if (frame == null) {
            captureViaServer()
            return
        }
        val width = frame.width
        val height = frame.height
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { frame.toJpeg() }
            val record = ScreenshotData(
                deviceId = deviceId,
                imageUrl = null,
                thumbnailUrl = null,
                status = "READY",
                capturedAt = System.currentTimeMillis(),
                width = width,
                height = height
            )
            val history = (listOf(record) + _uiState.value.screenshotHistory).take(MAX_SCREENSHOT_HISTORY)
            // 落相册必须在 IO 线程：MediaStore 插入 + 整张 JPEG 写入，2560x1600 的截图有数 MB，
            // 放在 viewModelScope(Main) 上会让主线程掉帧甚至 ANR。录屏保存已用 launch(IO)，此处对齐。
            val saveResult = withContext(Dispatchers.IO) { gallerySaver.saveJpeg(bytes) }
            saveResult
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        screenshot = record,
                        screenshotHistory = history,
                        toast = "截屏已保存到相册（Pictures/PadGuard）"
                    )
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        screenshot = record,
                        screenshotHistory = history,
                        toast = "保存到相册失败：${e.message}"
                    )
                }
        }
    }

    /**
     * 服务端截屏通道：下发指令 → 轮询至 READY → 下载原图 → 存相册。
     * 实时流不可用（孩子端刚重启 / 未授权 / 弱网）时的兜底路径。
     */
    private fun captureViaServer() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(fetchingFrame = true)
            val shot = awaitScreenshot()
            if (shot == null) {
                _uiState.value = _uiState.value.copy(
                    fetchingFrame = false,
                    toast = "截屏失败：平板未上传画面（若未授权屏幕采集，请在平板「我的 → 设备管理员」重新授权）"
                )
                return@launch
            }
            val url = shot.imageUrl
            if (url == null) {
                _uiState.value = _uiState.value.copy(fetchingFrame = false, toast = "截屏失败：缺少图片地址")
                return@launch
            }
            val bytes = monitorRepository.downloadImage(url).getOrNull()
            val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            if (bytes == null || bitmap == null) {
                _uiState.value = _uiState.value.copy(fetchingFrame = false, toast = "截屏下载失败")
                return@launch
            }
            val history = (listOf(shot) + _uiState.value.screenshotHistory).take(MAX_SCREENSHOT_HISTORY)
            // 同上方主路径：落盘不能占主线程
            val saveResult = withContext(Dispatchers.IO) { gallerySaver.saveJpeg(bytes) }
            saveResult
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        screenshot = shot,
                        liveImage = bitmap,
                        liveUpdatedAt = System.currentTimeMillis(),
                        screenshotHistory = history,
                        fetchingFrame = false,
                        toast = "截屏已保存到相册（Pictures/PadGuard）"
                    )
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        screenshot = shot,
                        liveImage = bitmap,
                        liveUpdatedAt = System.currentTimeMillis(),
                        screenshotHistory = history,
                        fetchingFrame = false,
                        toast = "画面已更新，保存到相册失败：${e.message}"
                    )
                }
        }
    }

    /**
     * 下发截屏指令并轮询到 READY。
     * @return 本次请求产生的截图；超过等待上限仍 PENDING（被控端离线/未授权）时返回 null
     *
     * 只认**本次请求之后**产生的图。
     * 早期实现每轮轮询都直接取"最新一张 READY"——若历史上截过屏，第一次轮询就会把
     * 上一轮的旧图（capturedAt 早于本次请求）返回并存入相册，家长以为截的是当下画面，
     * 实际可能是几分钟前的旧画面。现在以服务端受理时间戳（ack.capturedAt，
     * 见 ApiDataSource.requestScreenshot 的映射）为下限，capturedAt >= 该值才采用。
     */
    private suspend fun awaitScreenshot(): com.padguard.parent.domain.model.ScreenshotData? {
        val ack = monitorRepository.requestScreenshot(deviceId).getOrNull() ?: return null
        val requestAt = ack.capturedAt
        for (poll in 0 until SCREENSHOT_POLL_TIMES) {
            delay(SCREENSHOT_POLL_INTERVAL_MS)
            val latest = monitorRepository.getScreenshotHistory(deviceId, limit = 1)
                .getOrNull()?.firstOrNull() ?: continue
            if (latest.isReady && latest.capturedAt >= requestAt) return latest
        }
        return null
    }

    /** 录屏开始 / 停止。 */
    fun toggleRecording() {
        val current = _uiState.value.recording
        if (current == null) startRecording() else stopRecording(current)
    }

    private fun startRecording() {
        if (deviceId.isBlank()) return
        val settings = _uiState.value.settings
        viewModelScope.launch {
            // recordResolution / recordWithAudio 为预留字段（wire 兼容继续下发），
            // 孩子端录屏恒按原生分辨率、不含音轨——UI 已明示，这里不再暗示它们生效。
            monitorRepository.startScreenRecord(deviceId, settings.recordResolution, settings.recordWithAudio)
                .onSuccess { task ->
                    _uiState.value = _uiState.value.copy(
                        recording = task,
                        recordingSeconds = 0,
                        lastRecordSummary = null,
                        lastRecordUrl = null,
                        toast = "已开始录屏"
                    )
                    startRecordTicker()
                }
                .onFailure { _uiState.value = _uiState.value.copy(toast = "录屏启动失败：${it.message}") }
        }
    }

    private fun stopRecording(task: ScreenRecordTask) {
        val seconds = _uiState.value.recordingSeconds
        stopRecordTicker()
        viewModelScope.launch {
            monitorRepository.stopScreenRecord(deviceId, task.taskId)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        recording = null,
                        recordingSeconds = 0,
                        lastRecordUrl = null,
                        lastRecordSummary = "最近录屏已保存，时长 ${TimeFormat.formatDuration(seconds)}",
                        toast = "录屏已保存"
                    )
                    // 停止是异步的：mp4 由孩子端编码后上传，服务端置 READY 才有 url。
                    // 这里轮询最多 20 次 × 1.5s = 30s，拿到地址后页面即可给出回放入口。
                    // 轮询失败不改变已成功停止的事实，静默结束即可。
                    pollRecordUrl(task.taskId, seconds)
                }
                .onFailure { _uiState.value = _uiState.value.copy(toast = "录屏停止失败：${it.message}") }
        }
    }

    private suspend fun pollRecordUrl(taskId: String, seconds: Int) {
        repeat(20) {
            delay(1_500L)
            val dto = monitorRepository.getMediaTask(deviceId, taskId).getOrNull() ?: return@repeat
            if (!dto.url.isNullOrBlank()) {
                _uiState.value = _uiState.value.copy(
                    lastRecordUrl = dto.url,
                    lastRecordSummary = "最近录屏已保存，时长 ${
                        TimeFormat.formatDuration(dto.durationSeconds ?: seconds)
                    }"
                )
                // 同时把录屏文件下载并保存到本机相册（Movies/PadGuard），满足「录像保存在家长端平板」。
                // 与流式回放互不干扰：回放仍走服务端地址，本地副本用于长期留存。
                saveRecordingLocally(dto.url)
                return
            }
        }
    }

    /**
     * 把录屏文件下载到家长端本机（系统视频库），避免只留一份在服务端。
     * 下载/保存失败不影响已成功的停止与回放，仅本地留存这一步静默失败并提示。
     */
    private fun saveRecordingLocally(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            // 必须走流式下载：录屏文件动辄上百 MB，`downloadImage` 会把整个响应体读成
            // ByteArray，下载瞬间堆内存翻倍（字节数组 + 写库缓冲），中低端机直接 OOM。
            // 仓储接口里本来就有 downloadToFile、注释也写明了"录屏必须用它"，
            // 只是这个调用点一直没用上。
            val temp = monitorRepository.downloadToFile(url)
                .onFailure { _uiState.value = _uiState.value.copy(toast = "录像下载失败：${it.message}") }
                .getOrNull() ?: return@launch
            try {
                gallerySaver.saveVideo(temp)
                    .onSuccess { path ->
                        _uiState.value = _uiState.value.copy(
                            lastRecordSavedPath = path,
                            toast = "录像已保存到本机相册"
                        )
                    }
                    .onFailure { _uiState.value = _uiState.value.copy(toast = "录像本机保存失败：${it.message}") }
            } finally {
                // 临时文件只用于搬运，无论成功失败都要删；否则每录一次就多占一份磁盘
                runCatching { temp.delete() }
            }
        }
    }

    /** 远程锁屏；受「允许远程锁屏」开关约束。 */
    /**
     * 远程锁屏 / 解锁。
     *
     * 这是**同一个按钮的两个方向**，而不是两个互不相干的指令：
     * 早期版本只有"锁屏"，家长点下去孩子端锁上后，界面上再也找不到解锁入口，
     * 只能等孩子端策略自行恢复 —— 等于把自己关在门外。
     * 现在按服务端记录的 [remoteLocked] 决定这一下点出去的是"锁"还是"开"，
     * 成功后立刻翻转本地状态，按钮文案随之切换。
     *
     * 解锁不受 [ScreenMonitorSettings.allowRemoteLock] 限制：
     * 那个开关管的是"允不允许远程锁屏"，若把解锁也一起挡住，
     * 家长关掉开关后设备正锁着，就再也开不了了。
     */
    fun toggleRemoteLock() {
        if (_uiState.value.remoteLocked) {
            viewModelScope.launch {
                policyRepository.unlockScreen(deviceId)
                    .onSuccess {
                        _uiState.value = _uiState.value.copy(
                            remoteLocked = false, toast = "已发送解锁指令"
                        )
                    }
                    .onFailure {
                        _uiState.value = _uiState.value.copy(toast = "解锁指令发送失败：${it.message}")
                    }
            }
            return
        }
        if (!_uiState.value.settings.allowRemoteLock) {
            _uiState.value = _uiState.value.copy(toast = "已关闭远程锁屏，请先在设置中开启")
            return
        }
        viewModelScope.launch {
            policyRepository.lockScreen(deviceId)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        remoteLocked = true, toast = "已发送锁屏指令"
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(toast = "锁屏指令发送失败：${it.message}")
                }
        }
    }

    /** 修改本地设置草稿（仅改表单，不立即下发）。 */
    fun editSettings(transform: (ScreenMonitorSettings) -> ScreenMonitorSettings) {
        _uiState.value = _uiState.value.copy(
            settings = transform(_uiState.value.settings),
            settingsDirty = true
        )
    }

    /** 保存设置并下发至被管控平板。 */
    fun saveSettings() {
        if (deviceId.isBlank()) return
        viewModelScope.launch {
            monitorRepository.updateScreenMonitorSettings(_uiState.value.settings)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(settings = it, settingsDirty = false, toast = "设置已下发")
                }
                .onFailure { _uiState.value = _uiState.value.copy(toast = "设置下发失败：${it.message}") }
        }
    }

    fun clearToast() {
        _uiState.value = _uiState.value.copy(toast = null)
    }

    private fun startRecordTicker() {
        recordTicker?.cancel()
        recordTicker = viewModelScope.launch {
            while (true) {
                delay(1_000)
                _uiState.value = _uiState.value.copy(recordingSeconds = _uiState.value.recordingSeconds + 1)
            }
        }
    }

    private fun stopRecordTicker() {
        recordTicker?.cancel()
        recordTicker = null
    }

    /** 退出页面时停止孩子端推流，避免后台一直采集耗电。 */
    fun stopLive() {
        if (deviceId.isBlank()) return
        releaseScope.launch { runCatching { monitorRepository.stopLiveView(deviceId) } }
    }

    override fun onCleared() {
        stopRecordTicker()
        liveJob?.cancel()
        stopLive()
        super.onCleared()
    }

    private fun Bitmap.toJpeg(quality: Int = 92): ByteArray =
        ByteArrayOutputStream().use { out ->
            compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }

    /**
     * 无画面时的重连等待：3s 起步、每次翻倍、60s 封顶。
     *
     * 平板离线一小时的开流次数因此从 720 次降到约 65 次，且平板一上线最迟 60s 内就能自动接上。
     */
    private fun reconnectDelayMs(attempt: Int): Long {
        val shift = (attempt - 1).coerceIn(0, 5)
        return (LIVE_RETRY_BASE_MS shl shift).coerceAtMost(LIVE_RETRY_MAX_MS)
    }

    private companion object {
        /** 截屏历史在端上保留的最大条数。 */
        const val MAX_SCREENSHOT_HISTORY = 20

        /** 实时流续期间隔：孩子端推流 TTL 180s，这里 2 分钟续一次。 */
        const val LIVE_KEEPALIVE_MS = 120_000L
        /** 带宽遥测轮询间隔：每 2s 拉一次状态，既能反映实时带宽又不至于刷屏 */
        const val LIVE_BANDWIDTH_POLL_MS = 2_000L

        /** 已出画后断流的重连等待 */
        const val LIVE_RECONNECT_MS = 1_500L

        /**
         * 下发开流指令后多久仍无首帧，就给出原因提示。
         *
         * 取 12s：正常链路下首帧在 1~3s 内到达；孩子端在等授权弹窗时，
         * 这个时长足够让家长意识到"需要去平板点一下"，又不至于刚进来就误报。
         */
        const val LIVE_FIRST_FRAME_HINT_MS = 12_000L

        /** 服务端截屏轮询：20 次 × 2s = 40s */
        const val SCREENSHOT_POLL_TIMES = 20
        const val SCREENSHOT_POLL_INTERVAL_MS = 2_000L

        /** 从未出画（孩子端离线/未就绪）时的首次重试等待，之后按 2 的幂退避 */
        const val LIVE_RETRY_BASE_MS = 3_000L

        /** 退避上限：平板长时间离线时也要保证"一上线就能在 1 分钟内接上" */
        const val LIVE_RETRY_MAX_MS = 60_000L
    }
}
