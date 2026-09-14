package com.padguard.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.domain.model.MessageContentType
import com.padguard.domain.model.MessagePublishRequest
import com.padguard.domain.model.PublishedMessage
import com.padguard.domain.repository.MessageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 信息发布 ViewModel（实时管控 > 信息发布）
 *
 * 支持文字 / 图片 / 视频 / 声音四类内容，可设置平板端显示时长与是否霸屏显示。
 */
data class MessagePublishUiState(
    val contentType: MessageContentType = MessageContentType.TEXT,
    val text: String = "",
    val mediaUrl: String? = null,
    val mediaName: String? = null,
    val displaySeconds: Int = DEFAULT_DISPLAY_SECONDS,
    val fullScreen: Boolean = false,
    val playAudio: Boolean = true,
    val history: List<PublishedMessage> = emptyList(),
    val isPublishing: Boolean = false,
    val toast: String? = null
) {
    /** 媒体类内容必须已选素材；文字类必须已输入内容。 */
    val canPublish: Boolean
        get() = if (contentType == MessageContentType.TEXT) text.isNotBlank() else !mediaUrl.isNullOrBlank()

    companion object {
        const val DEFAULT_DISPLAY_SECONDS = 10
    }
}

@HiltViewModel
class MessagePublishViewModel @Inject constructor(
    private val messageRepository: MessageRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val deviceId: String = savedStateHandle.get<String>("deviceId").orEmpty()

    private val _uiState = MutableStateFlow(MessagePublishUiState())
    val uiState: StateFlow<MessagePublishUiState> = _uiState.asStateFlow()

    init {
        loadHistory()
    }

    fun loadHistory() {
        if (deviceId.isBlank()) return
        viewModelScope.launch {
            messageRepository.getPublishedMessages(deviceId)
                .onSuccess { _uiState.value = _uiState.value.copy(history = it) }
        }
    }

    /** 切换内容类型时清空素材，避免「图片内容残留视频素材」这类跨类型脏状态。 */
    fun selectContentType(type: MessageContentType) {
        if (type == _uiState.value.contentType) return
        _uiState.value = _uiState.value.copy(
            contentType = type,
            mediaUrl = null,
            mediaName = null
        )
    }

    fun updateText(value: String) {
        _uiState.value = _uiState.value.copy(text = value)
    }

    /**
     * 选择素材。
     *
     * Mock 阶段：直接生成一份占位素材，用于打通发布链路。
     * 接入真实后端时，此处应改为 Android Photo Picker（图片/视频）或 SAF 文档选择器（声音）。
     */
    fun pickMedia() {
        val asset = when (_uiState.value.contentType) {
            MessageContentType.TEXT -> return
            MessageContentType.IMAGE -> MOCK_IMAGE
            MessageContentType.VIDEO -> MOCK_VIDEO
            MessageContentType.AUDIO -> MOCK_AUDIO
        }
        _uiState.value = _uiState.value.copy(mediaName = asset.first, mediaUrl = asset.second)
    }

    fun clearMedia() {
        _uiState.value = _uiState.value.copy(mediaName = null, mediaUrl = null)
    }

    fun updateDisplaySeconds(seconds: Int) {
        _uiState.value = _uiState.value.copy(displaySeconds = seconds)
    }

    fun toggleFullScreen(value: Boolean) {
        _uiState.value = _uiState.value.copy(fullScreen = value)
    }

    fun togglePlayAudio(value: Boolean) {
        _uiState.value = _uiState.value.copy(playAudio = value)
    }

    fun publish() {
        val current = _uiState.value
        if (current.isPublishing || !current.canPublish) return
        val request = MessagePublishRequest(
            deviceId = deviceId,
            contentType = current.contentType,
            text = current.text.takeIf { it.isNotBlank() },
            mediaUrl = current.mediaUrl,
            mediaName = current.mediaName,
            displaySeconds = current.displaySeconds,
            fullScreen = current.fullScreen,
            playAudio = current.playAudio
        )
        viewModelScope.launch {
            _uiState.value = current.copy(isPublishing = true)
            messageRepository.publishMessage(request)
                .onSuccess { published ->
                    _uiState.value = _uiState.value.copy(
                        isPublishing = false,
                        text = "",
                        mediaUrl = null,
                        mediaName = null,
                        history = (listOf(published) + _uiState.value.history).take(MAX_HISTORY),
                        toast = if (published.fullScreen) "已霸屏发布至平板" else "已发布至平板"
                    )
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        isPublishing = false,
                        toast = e.message ?: "发布失败，请重试"
                    )
                }
        }
    }

    fun clearToast() {
        _uiState.value = _uiState.value.copy(toast = null)
    }

    private companion object {
        const val MAX_HISTORY = 20

        // Mock 素材（名称 to 资源地址）
        val MOCK_IMAGE = "家长留言图片.png" to "https://mock.padguard.com/assets/image_notice.png"
        val MOCK_VIDEO = "学习提醒视频.mp4" to "https://mock.padguard.com/assets/study_reminder.mp4"
        val MOCK_AUDIO = "语音提醒.m4a" to "https://mock.padguard.com/assets/voice_notice.m4a"
    }
}
