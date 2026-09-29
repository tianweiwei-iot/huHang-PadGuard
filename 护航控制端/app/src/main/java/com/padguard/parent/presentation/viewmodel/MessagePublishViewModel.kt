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
    /** 素材上传中：大视频在弱网下要传一会，界面必须给出反馈，否则家长会以为选错了 */
    val isUploading: Boolean = false,
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
     * 真正上传本地选取的素材。
     *
     * 早期这里是写死的 mock 地址（`https://mock.padguard.com/...`）：
     * 界面看起来"选好了素材"，孩子端拿到的却是一个根本不存在的域名，
     * 表现就是"家长发布成功、孩子端一片空白"，且没有任何报错可查。
     * 现在改为先上传再回填真实地址，发布链路才真正闭环。
     */
    fun attachMedia(bytes: ByteArray, fileName: String, contentType: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isUploading = true, toast = null)
            messageRepository.uploadMedia(fileName = fileName, contentType = contentType, bytes = bytes)
                .onSuccess { media ->
                    _uiState.value = _uiState.value.copy(
                        isUploading = false,
                        mediaName = media.fileName,
                        mediaUrl = media.url,
                        toast = "素材已就绪：${media.fileName}"
                    )
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        isUploading = false,
                        toast = "素材上传失败：${e.message}"
                    )
                }
        }
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
    }
}
