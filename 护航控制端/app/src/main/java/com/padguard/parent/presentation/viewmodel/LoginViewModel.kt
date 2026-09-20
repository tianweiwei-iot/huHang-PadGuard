package com.padguard.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.domain.repository.AuthRepository
import com.padguard.parent.di.ServerPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 「高级设置」弹窗用的服务器配置拆分：地址 / 端口 / 接口前缀。
 *
 * 持久化层 [ServerPrefs] 仍只存一份完整 baseUrl，这里只是把完整 URL 拆开给人编辑，
 * 保存时再拼回完整 URL，避免改动已经验证过的动态 baseUrl 机制（[com.padguard.parent.data.remote.HostSelectionInterceptor]）。
 */
data class ServerConfig(
    val host: String = "",
    val port: String = "8090",
    val path: String = "/v1"
)

/** 把完整 baseUrl 解析成 地址 / 端口 / 前缀，供高级设置弹窗回显。 */
fun parseServerConfig(raw: String): ServerConfig {
    val noScheme = raw.trim().removePrefix("https://").removePrefix("http://")
    val slash = noScheme.indexOf('/')
    val authority = if (slash >= 0) noScheme.substring(0, slash) else noScheme
    val pathPart = if (slash >= 0) noScheme.substring(slash) else "/"
    val colon = authority.indexOf(':')
    val host = if (colon >= 0) authority.substring(0, colon) else authority
    val port = if (colon >= 0) authority.substring(colon + 1) else "8090"
    return ServerConfig(host = host, port = port, path = pathPart)
}

/** 由 地址 / 端口 / 前缀 拼回完整 baseUrl；任一不合法返回 null。 */
fun buildServerConfig(host: String, port: String, path: String): String? {
    val h = host.trim()
    if (h.isBlank()) return null
    val p = port.trim().toIntOrNull() ?: return null
    if (p <= 0 || p > 65535) return null
    val cleanPath = path.trim().trimStart('/').trimEnd('/').let { if (it.isBlank()) "" else "/$it" }
    return "http://$h:$p$cleanPath/"
}

/**
 * 登录页 ViewModel
 * 管理登录状态、表单输入和登录流程
 */
data class LoginUiState(
    val isSmsMode: Boolean = false,
    val phone: String = "",
    val password: String = "",
    val smsCode: String = "",
    val isLoading: Boolean = false,
    val isSendingCode: Boolean = false,
    val countdown: Int = 0,
    val isLoggedIn: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val serverPrefs: ServerPrefs
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    /** 当前生效的服务器地址（登录页「高级设置」可修改，改完立即生效） */
    val serverUrl: StateFlow<String> = serverPrefs.baseUrl

    /** 保存服务器地址。返回 false 表示格式不合法（必须以 http:// 或 https:// 开头）。 */
    fun saveServerUrl(url: String): Boolean {
        val u = url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) return false
        serverPrefs.setBaseUrl(u)
        return true
    }

    fun toggleLoginMode() {
        _uiState.value = _uiState.value.copy(
            isSmsMode = !_uiState.value.isSmsMode,
            error = null
        )
    }

    fun updatePhone(phone: String) {
        _uiState.value = _uiState.value.copy(phone = phone, error = null)
    }

    fun updatePassword(password: String) {
        _uiState.value = _uiState.value.copy(password = password, error = null)
    }

    fun updateSmsCode(code: String) {
        _uiState.value = _uiState.value.copy(smsCode = code, error = null)
    }

    fun sendSmsCode(phone: String) {
        if (phone.length < 11) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSendingCode = true)
            val result = authRepository.sendSmsCode(phone)
            result.onSuccess {
                // 开始倒计时 60 秒
                var count = 60
                _uiState.value = _uiState.value.copy(countdown = count, isSendingCode = false)
                while (count > 0) {
                    kotlinx.coroutines.delay(1000)
                    count--
                    _uiState.value = _uiState.value.copy(countdown = count)
                }
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    isSendingCode = false,
                    error = e.message ?: "发送失败"
                )
            }
        }
    }

    fun loginWithPassword() {
        val state = _uiState.value
        if (state.phone.isBlank() || state.password.isBlank()) return
        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, error = null)
            val result = authRepository.loginWithPassword(state.phone, state.password)
            result.onSuccess {
                _uiState.value = _uiState.value.copy(isLoading = true, isLoggedIn = true)
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: "登录失败")
            }
        }
    }

    fun loginWithSms() {
        val state = _uiState.value
        if (state.phone.isBlank() || state.smsCode.length != 6) return
        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, error = null)
            val result = authRepository.loginWithSmsCode(state.phone, state.smsCode)
            result.onSuccess {
                _uiState.value = _uiState.value.copy(isLoading = true, isLoggedIn = true)
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: "登录失败")
            }
        }
    }
}
