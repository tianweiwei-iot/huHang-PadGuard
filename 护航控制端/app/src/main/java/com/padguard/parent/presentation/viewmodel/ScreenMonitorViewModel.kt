package com.padguard.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.domain.model.ScreenMonitorSettings
import com.padguard.domain.model.ScreenRecordTask
import com.padguard.domain.model.ScreenshotData
import com.padguard.domain.repository.MonitorRepository
import com.padguard.domain.repository.PolicyRepository
import com.padguard.presentation.util.TimeFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 屏幕监控 ViewModel（实时管控 > 屏幕监控）
 *
 * 职责：实时画面、截屏、录屏（开始 / 停止与计时）、远程锁屏、屏幕监控设置、截屏历史。
 * 锁屏能力复用 [PolicyRepository.lockScreen]，不在此重复定义指令下发。
 */
data class ScreenMonitorUiState(
    val settings: ScreenMonitorSettings = ScreenMonitorSettings(deviceId = ""),
    val settingsDirty: Boolean = false,
    val screenshot: ScreenshotData? = null,
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
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val deviceId: String = savedStateHandle.get<String>("deviceId").orEmpty()

    private val _uiState = MutableStateFlow(ScreenMonitorUiState())
    val uiState: StateFlow<ScreenMonitorUiState> = _uiState.asStateFlow()

    /** 录屏计时协程。停止录屏 / ViewModel 销毁时必须取消，避免协程泄漏。 */
    private var recordTicker: Job? = null

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
            monitorRepository.requestScreenshot(deviceId)
                .onSuccess { _uiState.value = _uiState.value.copy(screenshot = it) }
                .onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
            monitorRepository.getScreenshotHistory(deviceId)
                .onSuccess { _uiState.value = _uiState.value.copy(screenshotHistory = it) }
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    /** 刷新实时画面（不记入截屏历史）。 */
    fun refreshScreenshot() {
        if (deviceId.isBlank()) return
        viewModelScope.launch {
            monitorRepository.requestScreenshot(deviceId)
                .onSuccess { _uiState.value = _uiState.value.copy(screenshot = it) }
                .onFailure { _uiState.value = _uiState.value.copy(toast = "刷新失败：${it.message}") }
        }
    }

    /** 截屏并记入历史。 */
    fun captureScreenshot() {
        if (deviceId.isBlank()) return
        viewModelScope.launch {
            monitorRepository.requestScreenshot(deviceId)
                .onSuccess { shot ->
                    val history = (listOf(shot) + _uiState.value.screenshotHistory).take(MAX_SCREENSHOT_HISTORY)
                    _uiState.value = _uiState.value.copy(
                        screenshot = shot,
                        screenshotHistory = history,
                        toast = "已截屏"
                    )
                }
                .onFailure { _uiState.value = _uiState.value.copy(toast = "截屏失败：${it.message}") }
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

    /** 保存设置并下发至被管控平板。 */
    fun saveSettings() {
        if (deviceId.isBlank()) return
        viewModelScope.launch {
            monitorRepository.updateScreenMonitorSettings(_uiState.value.settings)
                .onSuccess { _uiState.value = _uiState.value.copy(settings = it, settingsDirty = false, toast = "设置已下发") }
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
        super.onCleared()
    }

    private companion object {
        /** 截屏历史在端上保留的最大条数。 */
        const val MAX_SCREENSHOT_HISTORY = 20
    }
}
