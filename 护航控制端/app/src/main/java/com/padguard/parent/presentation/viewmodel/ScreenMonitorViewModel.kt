package com.padguard.presentation.viewmodel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.domain.model.ScreenMonitorSettings
import com.padguard.domain.model.ScreenRecordTask
import com.padguard.domain.model.ScreenshotData
import com.padguard.domain.repository.MonitorRepository
import com.padguard.domain.repository.PolicyRepository
import com.padguard.presentation.util.GallerySaver
import com.padguard.presentation.util.TimeFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 屏幕监控 ViewModel（实时管控 > 屏幕监控）
 *
 * 职责：实时画面、截屏、录屏（开始 / 停止与计时）、远程锁屏、屏幕监控设置、截屏历史。
 * 锁屏能力复用 [PolicyRepository.lockScreen]，不在此重复定义指令下发。
 *
 * ## 实时画面与截屏的等待语义
 * 截屏指令是异步的：家长端下发 → 被控端采集上传 → 服务端状态由 PENDING 转 READY。
 * 请求接口只回执"已受理"，直接展示只会得到空占位 —— 因此统一走
 * [awaitScreenshot] 轮询至 READY 再取图，实时画面与截屏存相册共用这一条链。
 */
data class ScreenMonitorUiState(
    val settings: ScreenMonitorSettings = ScreenMonitorSettings(deviceId = ""),
    val settingsDirty: Boolean = false,
    val screenshot: ScreenshotData? = null,
    val liveImage: Bitmap? = null,
    val fetchingFrame: Boolean = false,
    val screenshotHistory: List<ScreenshotData> = emptyList(),
    val recording: ScreenRecordTask? = null,
    val recordingSeconds: Int = 0,
    val lastRecordSummary: String? = null,
    val isLoading: Boolean = false,
    val toast: String? = null,
    val error: String? = null
) {
    val isRecording: Boolean get() = recording != null
}

@HiltViewModel
class ScreenMonitorViewModel @Inject constructor(
    private val monitorRepository: MonitorRepository,
    private val policyRepository: PolicyRepository,
    private val gallerySaver: GallerySaver,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val deviceId: String = savedStateHandle.get<String>("deviceId").orEmpty()

    private val _uiState = MutableStateFlow(ScreenMonitorUiState())
    val uiState: StateFlow<ScreenMonitorUiState> = _uiState.asStateFlow()

    /** 录屏计时协程。停止录屏 / ViewModel 销毁时必须取消，避免协程泄漏。 */
    private var recordTicker: Job? = null

    /** 实时画面自动刷新循环；随 ViewModel 生命周期终止 */
    private var liveLoop: Job? = null

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
            monitorRepository.getScreenshotHistory(deviceId)
                .onSuccess { history ->
                    _uiState.value = _uiState.value.copy(screenshotHistory = history)
                    // 最近一张若已 READY 直接上屏，避免进页面后画面区长时间空白
                    history.firstOrNull { it.isReady }?.let { showImage(it) }
                }
            _uiState.value = _uiState.value.copy(isLoading = false)
            startLiveLoop()
        }
    }

    /**
     * 实时画面刷新循环：按设置的间隔持续请求并展示最新画面。
     * 单轮串行（采集→上传→取图完成才开始下一轮），避免弱网下请求堆积。
     */
    private fun startLiveLoop() {
        liveLoop?.cancel()
        liveLoop = viewModelScope.launch {
            while (isActive) {
                refreshScreenshotInternal()
                delay(_uiState.value.settings.autoRefreshSeconds.coerceAtLeast(3) * 1_000L)
            }
        }
    }

    /** 刷新实时画面（不记入截屏历史）。 */
    fun refreshScreenshot() {
        if (deviceId.isBlank()) return
        viewModelScope.launch { refreshScreenshotInternal() }
    }

    private suspend fun refreshScreenshotInternal() {
        _uiState.value = _uiState.value.copy(fetchingFrame = true)
        val shot = awaitScreenshot()
        if (shot != null) {
            _uiState.value = _uiState.value.copy(screenshot = shot, fetchingFrame = false)
            showImage(shot)
        } else {
            _uiState.value = _uiState.value.copy(fetchingFrame = false)
        }
    }

    /** 截屏、展示并保存到系统相册。 */
    fun captureScreenshot() {
        if (deviceId.isBlank()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(fetchingFrame = true)
            val shot = awaitScreenshot()
            if (shot == null) {
                _uiState.value = _uiState.value.copy(
                    fetchingFrame = false,
                    toast = "截屏失败：平板未上传画面（若已拒绝授权，请在平板「我的→设备管理员」重新授权屏幕采集）"
                )
                return@launch
            }
            val saved: Result<Bitmap> = shot.imageUrl
                ?.let { url ->
                    monitorRepository.downloadImage(url).mapCatching { bytes ->
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            ?: error("图片解码失败")
                        // 先落相册再上屏：落相册失败时家长至少还能看到画面，且 toast 会说明原因
                        gallerySaver.saveJpeg(bytes).getOrElse { err ->
                            _uiState.value = _uiState.value.copy(liveImage = bitmap)
                            throw err
                        }
                        bitmap
                    }
                }
                ?: Result.failure(IllegalStateException("截图缺少图片地址"))
            saved.onSuccess { bitmap ->
                val history = (listOf(shot) + _uiState.value.screenshotHistory).take(MAX_SCREENSHOT_HISTORY)
                _uiState.value = _uiState.value.copy(
                    screenshot = shot,
                    liveImage = bitmap,
                    screenshotHistory = history,
                    fetchingFrame = false,
                    toast = "截屏已保存到相册（Pictures/PadGuard）"
                )
            }.onFailure { e ->
                // 图已到手但落相册失败/下载失败：仍展示画面，明确告知保存失败原因
                _uiState.value = _uiState.value.copy(
                    screenshot = shot,
                    fetchingFrame = false,
                    toast = "画面已更新，保存到相册失败：${e.message}"
                )
            }
        }
    }

    /**
     * 请求一帧并轮询至 READY。
     * @return READY 的截图；超过等待上限仍 PENDING（被控端离线/未授权）时返回 null
     */
    private suspend fun awaitScreenshot(): ScreenshotData? {
        monitorRepository.requestScreenshot(deviceId).getOrNull() ?: return null
        for (poll in 0 until SCREENSHOT_POLL_TIMES) {
            delay(SCREENSHOT_POLL_INTERVAL_MS)
            val latest = monitorRepository.getScreenshotHistory(deviceId, limit = 1)
                .getOrNull()?.firstOrNull() ?: continue
            if (latest.isReady) return latest
        }
        return null
    }

    /** 拉取图片并渲染到实时画面区；失败静默（下一轮刷新会重试） */
    private suspend fun showImage(shot: ScreenshotData) {
        val url = shot.imageUrl ?: return
        monitorRepository.downloadImage(url).onSuccess { bytes ->
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap != null) {
                _uiState.value = _uiState.value.copy(liveImage = bitmap)
            }
        }
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
            monitorRepository.startScreenRecord(deviceId, settings.recordResolution, settings.recordWithAudio)
                .onSuccess { task ->
                    _uiState.value = _uiState.value.copy(
                        recording = task,
                        recordingSeconds = 0,
                        lastRecordSummary = null,
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
            monitorRepository.stopScreenRecord(task.taskId)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        recording = null,
                        recordingSeconds = 0,
                        lastRecordSummary = "最近录屏已保存，时长 ${TimeFormat.formatDuration(seconds)}",
                        toast = "录屏已保存"
                    )
                }
                .onFailure { _uiState.value = _uiState.value.copy(toast = "录屏停止失败：${it.message}") }
        }
    }

    /** 远程锁屏；受「允许远程锁屏」开关约束。 */
    fun lockScreen() {
        if (!_uiState.value.settings.allowRemoteLock) {
            _uiState.value = _uiState.value.copy(toast = "已关闭远程锁屏，请先在设置中开启")
            return
        }
        viewModelScope.launch {
            policyRepository.lockScreen(deviceId)
                .onSuccess { _uiState.value = _uiState.value.copy(toast = "已发送锁屏指令") }
                .onFailure { _uiState.value = _uiState.value.copy(toast = "锁屏指令发送失败") }
        }
    }

    /** 修改本地设置草稿（仅改表单，不立即下发）。 */
    fun editSettings(transform: (ScreenMonitorSettings) -> ScreenMonitorSettings) {
        _uiState.value = _uiState.value.copy(
            settings = transform(_uiState.value.settings),
            settingsDirty = true
        )
    }

    /** 保存设置并下发至被管控平板；刷新间隔变化即刻生效。 */
    fun saveSettings() {
        if (deviceId.isBlank()) return
        viewModelScope.launch {
            monitorRepository.updateScreenMonitorSettings(_uiState.value.settings)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(settings = it, settingsDirty = false, toast = "设置已下发")
                    startLiveLoop()
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

    override fun onCleared() {
        stopRecordTicker()
        liveLoop?.cancel()
        super.onCleared()
    }

    private companion object {
        /** 截屏历史在端上保留的最大条数。 */
        const val MAX_SCREENSHOT_HISTORY = 20

        /** 单帧截图最长等待：20 次 × 2s = 40s。
         *  首次采集需孩子在平板通知里点击授权屏幕采集，留足授权交互时间，避免过早判定失败（P1） */
        const val SCREENSHOT_POLL_TIMES = 20
        const val SCREENSHOT_POLL_INTERVAL_MS = 2_000L
    }
}
