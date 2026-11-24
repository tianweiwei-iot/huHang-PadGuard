package com.padguard.parent.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.parent.domain.repository.AuthRepository
import com.padguard.parent.data.remote.readableUserFacingError
import com.padguard.parent.di.ServerPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 我的页 ViewModel
 * 负责退出登录等账号操作，通过 AuthRepository 这一官方接口完成，
 * 而非在 UI 层遗留 TODO 占位。
 */
data class ProfileUiState(
    val isLoggingOut: Boolean = false,
    val loggedOut: Boolean = false,
    val error: String? = null
)

/**
 * 修改登录密码的独立状态。
 *
 * 刻意不塞进 [ProfileUiState]：那边挂着 `loggedOut`，一旦把改密码的进度混进去，
 * 弹窗里的"原密码不正确"和首页的登出跳转就会共用一个状态机，
 * 容易出现"改密码失败却跳回登录页"这类串味问题。
 */
data class PasswordChangeState(
    val submitting: Boolean = false,
    val error: String? = null,
    /** 新密码已生效、新令牌已落盘：UI 据此关弹窗并提示 */
    val done: Boolean = false
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val serverPrefs: ServerPrefs
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    /** 当前生效的服务器地址（「我的」页可修改，改完立即生效，无需退出登录） */
    val serverUrl: StateFlow<String> = serverPrefs.baseUrl

    /**
     * 保存服务器地址。格式不合法、或公网地址使用明文 HTTP 时忽略并给出提示。
     * 表单（ServerConfigEditor）已做同规则校验并展示原因，这里是兜底，防止新增入口绕过。
     */
    fun saveServerUrl(url: String) {
        if (!serverPrefs.setBaseUrl(url)) {
            _uiState.value = _uiState.value.copy(
                error = "服务器地址无效或不安全：公网地址必须使用 HTTPS，内网请填写 10.x / 172.16~31.x / 192.168.x / 127.x 的内网 IP"
            )
        } else {
            _uiState.value = _uiState.value.copy(error = null)
        }
    }

    fun logout() {
        if (_uiState.value.isLoggingOut) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoggingOut = true)
            authRepository.logout()
                .onSuccess { _uiState.value = _uiState.value.copy(isLoggingOut = false, loggedOut = true) }
                .onFailure { e -> _uiState.value = _uiState.value.copy(isLoggingOut = false, error = e.message) }
        }
    }

    // ==================== 修改登录密码 ====================

    private val _passwordChange = MutableStateFlow(PasswordChangeState())
    val passwordChange: StateFlow<PasswordChangeState> = _passwordChange.asStateFlow()

    /**
     * 提交修改登录密码。
     *
     * 前置校验只做"必填 / 两次输入一致 / 长度"，强度规则交给服务端 ——
     * 两端各写一套必然漂移，最终表现是"端上放行、服务端拒绝"的莫名失败。
     * 失败文案直接取服务端的中文原因（原密码不正确 / 新密码不能与原密码相同 / 密码至少 6 位），
     * 经 [readableUserFacingError] 洗掉错误码前缀后上屏。
     */
    fun changePassword(oldPassword: String, newPassword: String, confirmPassword: String) {
        if (_passwordChange.value.submitting) return
        if (oldPassword.isBlank()) {
            _passwordChange.value = PasswordChangeState(error = "请先输入当前密码")
            return
        }
        if (newPassword.length < 6) {
            _passwordChange.value = PasswordChangeState(error = "新密码至少 6 位")
            return
        }
        if (newPassword != confirmPassword) {
            _passwordChange.value = PasswordChangeState(error = "两次输入的新密码不一致")
            return
        }
        if (newPassword == oldPassword) {
            _passwordChange.value = PasswordChangeState(error = "新密码不能与当前密码相同")
            return
        }
        viewModelScope.launch {
            _passwordChange.value = PasswordChangeState(submitting = true)
            authRepository.changePassword(oldPassword, newPassword)
                .onSuccess { _passwordChange.value = PasswordChangeState(done = true) }
                .onFailure { e ->
                    _passwordChange.value = PasswordChangeState(
                        error = readableUserFacingError(e, "修改密码失败，请稍后重试")
                    )
                }
        }
    }

    /** 关闭弹窗时调用：清空错误与完成标记，避免下次打开还残留上一次的报错 */
    fun clearPasswordChangeState() {
        _passwordChange.value = PasswordChangeState()
    }
}
