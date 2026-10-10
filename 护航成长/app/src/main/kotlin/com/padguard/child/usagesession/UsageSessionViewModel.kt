package com.padguard.child.usagesession

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.core.common.TimeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 使用计时会话的 UI 视图模型。
 *
 * 仅做"状态转发 + 动作封装"：底层权威逻辑全在 [UsageSessionManager]，
 * 这里把它暴露给 Compose，并在用户操作时切到 viewModelScope 调用挂起方法。
 */
@HiltViewModel
class UsageSessionViewModel @Inject constructor(
    private val manager: UsageSessionManager,
    private val timeProvider: TimeProvider
) : ViewModel() {

    /** 会话 UI 状态（含是否需要确认计时、是否进入倒计时、档位等） */
    val state: StateFlow<UsageSessionManager.UiState> = manager.state

    /** 孩子确认"计时开始" */
    fun confirmStart() {
        viewModelScope.launch { manager.confirmStart() }
    }

    /** 申请延时（档位分钟数） */
    fun requestExtend(minutes: Int) {
        viewModelScope.launch { manager.requestExtend(minutes) }
    }

    /** 本地平滑倒计时：在"当前校准墙钟"时刻的实时剩余毫秒，供浮层每秒刷新 */
    fun liveRemainingMs(): Long = manager.liveRemainingMs(timeProvider.now())
}
