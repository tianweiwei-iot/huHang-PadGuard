package com.padguard.child.ui.message

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.core.data.repository.LogRepository
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
 * P0 阶段复用 [LogRepository] 已有的告警 + 拦截事件作为消息源，
 * 后续接入服务端 / MQTT 推送的"家长消息"或"系统公告"时，
 * 只需在 [MessageListScreen] 顶部插入一个 Section 即可，列表组件保持不变。
 */
@HiltViewModel
class MessageListViewModel @Inject constructor(
    private val logRepository: LogRepository
) : ViewModel() {

    private val _state = MutableStateFlow(MessageListUiState())
    val state: StateFlow<MessageListUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            logRepository.observeRecentBlocks(limit = 50)
                .map { items ->
                    MessageListUiState(
                        messages = items.map {
                            MessageItemUi(
                                id = "${it.source.name}-${it.timestamp}",
                                title = titleFor(it.summary),
                                preview = it.summary,
                                timestamp = it.timestamp,
                                isUnread = it.timestamp > lastReadMarker
                            )
                        }
                    )
                }
                .collect { _state.value = it }
        }
    }

    private fun titleFor(summary: String): String = when {
        summary.startsWith("拦截") -> "访问受限"
        summary.contains("时长") -> "时长提醒"
        summary.contains("卸载") -> "安全提醒"
        else -> "管控通知"
    }

    /**
     * 已读标记：本次启动后读过的消息时间戳，便于下次进入时高亮未读。
     * 真正实现需要把 marker 持久化到 DataStore，P0 阶段先用进程内变量。
     */
    @Volatile
    private var lastReadMarker: Long = 0L

    fun markAllRead() {
        lastReadMarker = System.currentTimeMillis()
    }
}

data class MessageListUiState(
    val messages: List<MessageItemUi> = emptyList()
)

data class MessageItemUi(
    val id: String,
    val title: String,
    val preview: String,
    val timestamp: Long,
    val isUnread: Boolean
)
