package com.padguard.parent.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.parent.data.model.DeviceLogDto
import com.padguard.parent.domain.repository.DeviceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

/**
 * 通知记录 ViewModel（A2 家长端展示）。
 *
 * 孩子端以隐私最小化方式代收系统通知，仅上行「包名 + 标题」（见子端 ChildNotificationListenerService），
 * 经服务端 [com.padguard.server.controller.ParentDeviceController.listDeviceLogs] 落库并返回。
 * 这里只解析已最小化的 payload，绝不触碰通知正文。
 */
data class NotificationRecordItem(
    val id: String?,
    val packageName: String,
    val title: String,
    val timestamp: Long?
)

data class NotificationRecordUiState(
    val items: List<NotificationRecordItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class NotificationRecordViewModel @Inject constructor(
    private val deviceRepository: DeviceRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotificationRecordUiState())
    val uiState: StateFlow<NotificationRecordUiState> = _uiState.asStateFlow()

    private var loadedDeviceId: String = ""

    fun load(deviceId: String) {
        if (loadedDeviceId == deviceId && _uiState.value.items.isNotEmpty()) return
        loadedDeviceId = deviceId
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            val result = deviceRepository.getDeviceLogs(deviceId, "NOTIFICATION_COLLECTED", 200)
            result.onSuccess { logs ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    items = logs.mapNotNull { parse(it) }
                )
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: "加载失败")
            }
        }
    }

    private fun parse(dto: DeviceLogDto): NotificationRecordItem? {
        val obj = runCatching { JSONObject(dto.payloadJson ?: "{}") }.getOrElse { JSONObject() }
        val pkg = obj.optString("packageName").orEmpty()
        val title = obj.optString("title").orEmpty()
        if (pkg.isBlank() && title.isBlank()) return null
        return NotificationRecordItem(dto.id, pkg, title, dto.timestamp)
    }
}
