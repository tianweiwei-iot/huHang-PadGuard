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
    private val authRepository: AuthRepository
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
            // 无需手动关闭：isAgreementAccepted 翻转会驱动 EntryRouter 重组收起弹窗
        }
    }

    fun decline() {
        // 受限预览模式：不授予任何高敏权限，等下次冷启动再提示
    }
}
