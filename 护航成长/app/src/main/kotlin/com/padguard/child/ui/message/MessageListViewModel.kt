package com.padguard.child.ui.message

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.core.data.db.ParentMessageEntity
import com.padguard.core.data.repository.MessageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 消息列表 ViewModel。
 *
 * 数据源为 [MessageRepository]（家长发布信息落地的本地持久化）。
 * 每条 [ParentMessageEntity] 映射成一个 [MessageItemUi]，id 即 [ParentMessageEntity.serverMsgId]，
 * 点击后由路由 `message/detail/{messageId}` 进入详情页加载并标记已读。
 */
@HiltViewModel
class MessageListViewModel @Inject constructor(
    private val messageRepository: MessageRepository
) : ViewModel() {

    private val _state = MutableStateFlow(MessageListUiState())
    val state: StateFlow<MessageListUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            messageRepository.observeAll()
                .map { items ->
                    MessageListUiState(
                        // 首次发射即视为完成首轮读取：数据库空表是真的"没有消息"，
                        // 而默认构造出的空列表只是"还没读到"，两者不能都渲染成"暂无消息"
                        loaded = true,
                        messages = items.map { entity ->
                            MessageItemUi(
                                id = entity.serverMsgId,
                                title = entity.title.ifBlank { defaultTitle(entity) },
                                preview = entity.body.ifBlank { mediaLabel(entity) },
                                timestamp = entity.timestamp,
                                isUnread = !entity.isRead
                            )
                        }
                    )
                }
                .collect { _state.value = it }
        }
    }

    private fun defaultTitle(entity: ParentMessageEntity): String = when (entity.contentType) {
        "VIDEO" -> "视频通知"
        "IMAGE" -> "图片通知"
        "AUDIO" -> "语音通知"
        else -> "家长消息"
    }

    private fun mediaLabel(entity: ParentMessageEntity): String = when (entity.contentType) {
        "VIDEO" -> "［视频］${entity.mediaName.ifBlank { "未命名" }}"
        "IMAGE" -> "［图片］${entity.mediaName.ifBlank { "未命名" }}"
        "AUDIO" -> "［语音］${entity.mediaName.ifBlank { "未命名" }}"
        else -> "来自家长的信息"
    }
}

data class MessageListUiState(
    /** 是否已完成首轮读取；false 时列表为空只代表"还没读到"，不能显示"暂无消息" */
    val loaded: Boolean = false,
    val messages: List<MessageItemUi> = emptyList()
)

data class MessageItemUi(
    val id: String,
    val title: String,
    val preview: String,
    val timestamp: Long,
    val isUnread: Boolean
)
