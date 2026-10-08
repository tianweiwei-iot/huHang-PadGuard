package com.padguard.child.ui.message

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.core.data.repository.MessageRepository
import com.padguard.core.transport.TransportSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 消息详情 ViewModel。
 *
 * 按 [serverMsgId] 从 [MessageRepository] 取回家长发布的单条消息；
 * 媒体地址（服务端默认相对路径）用 [TransportSettings.baseUrl] 拼成绝对地址后再交给 UI 渲染；
 * 进入详情即标记已读（调用 [MessageRepository.markRead]）。
 */
@HiltViewModel
class MessageDetailViewModel @Inject constructor(
    private val messageRepository: MessageRepository,
    private val transportSettings: TransportSettings
) : ViewModel() {

    private val _state = MutableStateFlow<MessageDetailUiState?>(null)
    val state: StateFlow<MessageDetailUiState?> = _state.asStateFlow()

    fun load(serverMsgId: String) {
        viewModelScope.launch {
            val entity = messageRepository.getByServerId(serverMsgId) ?: run {
                _state.value = null
                return@launch
            }
            val base = transportSettings.baseUrl.value.trimEnd('/')
            val absUrl = if (entity.mediaUrl.isNotBlank()) resolveAbsoluteUrl(entity.mediaUrl, base) else ""
            _state.value = MessageDetailUiState(
                serverMsgId = entity.serverMsgId,
                title = entity.title,
                body = entity.body,
                contentType = entity.contentType,
                mediaUrl = absUrl,
                mediaName = entity.mediaName,
                timestamp = entity.timestamp,
                isRead = entity.isRead
            )
            if (!entity.isRead) messageRepository.markRead(entity.serverMsgId)
        }
    }
}

data class MessageDetailUiState(
    val serverMsgId: String,
    val title: String,
    val body: String,
    val contentType: String,
    val mediaUrl: String,
    val mediaName: String,
    val timestamp: Long,
    val isRead: Boolean
)
