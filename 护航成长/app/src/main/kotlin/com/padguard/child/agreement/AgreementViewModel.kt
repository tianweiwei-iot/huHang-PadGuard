package com.padguard.child.agreement

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.child.permission.PermissionGranter
import com.padguard.core.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 授权协议弹窗的视图模型。
 *
 * - [agree]：触发 [PermissionGranter.grantAll]（同意即自动授予全部权限）。
 *   持久化在 grantAll 内部完成，[AuthRepository.isAgreementAccepted] 翻转后
 *   入口门禁自动收起弹窗。
 * - [decline]：暂不同意，不持久化任何高敏授权，进入受限预览模式；
 *   下次冷启动仍会再次提示（见 MainActivity.EntryRouter）。
 */
@HiltViewModel
class AgreementViewModel @Inject constructor(
    private val permissionGranter: PermissionGranter,
    private val authRepository: AuthRepository,
    private val sessionState: AgreementSessionState
) : ViewModel() {

    private val _granting = MutableStateFlow(false)
    val granting: StateFlow<Boolean> = _granting.asStateFlow()

    /** 是否已同意当前版本协议，供 UI 决定是否展示弹窗。 */
    val accepted = authRepository.isAgreementAccepted

    fun agree() {
        if (_granting.value) return
        viewModelScope.launch {
            _granting.value = true
            try {
                // 持久化在 grantAll 内部完成；异常不应让弹窗卡死
                runCatching { permissionGranter.grantAll() }
            } finally {
                _granting.value = false
            }
            // 同意已持久化，会话级拒绝标记随之失效（防御性清零，避免脏状态残留）
            sessionState.clear()
            // 无需手动关闭：isAgreementAccepted 翻转会驱动 EntryRouter 重组收起弹窗
        }
    }

    /**
     * 暂不同意：进入受限预览模式。
     *
     * 历史上这里是空方法体，配合 [AgreementDialog] 的 `onDismissRequest = { }`（点外部不关闭）
     * 与入口只看持久化同意位的挂载条件，三者叠加构成一个死局：
     * 点了「暂不同意」界面毫无反应、返回键也关不掉，设备被永久卡在协议弹窗上。
     *
     * 修复的落点是给这条分支一个真实出口 —— 翻转会话级标记让弹窗收起，
     * 同时**刻意不写任何持久化状态**：不授予高敏权限这一事实不需要记录，
     * 持久化的只会是"同意"。冷启动后 [AgreementSessionState] 复位，重新征求同意。
     */
    fun decline() {
        sessionState.markDeclined()
    }
}
