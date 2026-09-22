package com.padguard.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.domain.repository.AuthRepository
import com.padguard.parent.di.AccountPrefs
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
    val error: String? = null,
    /** 历史登录账号（最近使用在前），供账号框下拉选择 */
    val history: List<AccountPrefs.SavedAccount> = emptyList(),
    /** 是否"记住密码"；勾选后下次进入自动回填该账号密码 */
    val rememberPassword: Boolean = false
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val serverPrefs: ServerPrefs,
    private val accountPrefs: AccountPrefs
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        // 回填上次登录的账号：有保存的密码就一并回填，并把"记住密码"勾上，
        // 让用户看得见"密码是被记住的"，而不是莫名其妙已经填好了。
        val history = accountPrefs.history()
        val last = history.firstOrNull()
        _uiState.value = _uiState.value.copy(
            history = history,
            phone = last?.phone.orEmpty(),
            password = last?.password.orEmpty(),
            rememberPassword = last?.password != null
        )
    }

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
        // 输入/选择到已保存过的账号时，自动带出其密码并同步"记住密码"勾选状态
        // 敲到（或选到）保存过的账号就自动带出密码；新账号保留用户正在输入的内容
        val saved = accountPrefs.passwordOf(phone)
        _uiState.value = if (saved != null) {
            _uiState.value.copy(phone = phone, password = saved, rememberPassword = true, error = null)
        } else {
            _uiState.value.copy(phone = phone, error = null)
        }
    }

    /** 从历史账号列表里选一个：回填账号 + 已保存的密码。 */
    fun selectHistoryAccount(phone: String) {
        val saved = accountPrefs.passwordOf(phone)
        _uiState.value = _uiState.value.copy(
            phone = phone,
            password = saved.orEmpty(),
            rememberPassword = saved != null,
            error = null
        )
    }

    fun removeHistoryAccount(phone: String) {
        accountPrefs.remove(phone)
        val history = accountPrefs.history()
        _uiState.value = _uiState.value.copy(
            history = history,
            phone = _uiState.value.phone.takeIf { it != phone }.orEmpty(),
            password = _uiState.value.password.takeIf { _uiState.value.phone != phone }.orEmpty()
        )
    }

    fun toggleRememberPassword(remember: Boolean) {
        _uiState.value = _uiState.value.copy(rememberPassword = remember)
        // 取消勾选立刻清掉已保存的密码，避免"以为关了其实还在"
        if (!remember && _uiState.value.phone.isNotBlank()) {
            accountPrefs.save(_uiState.value.phone, null, remember = false)
            _uiState.value = _uiState.value.copy(history = accountPrefs.history())
        }
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
                accountPrefs.save(state.phone, state.password, state.rememberPassword)
                _uiState.value = _uiState.value.copy(
                    isLoading = true,
                    isLoggedIn = true,
                    history = accountPrefs.history()
                )
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
                // 短信登录没有密码可存，只记账号，方便下次直接选中
                accountPrefs.save(state.phone, null, remember = false)
                _uiState.value = _uiState.value.copy(
                    isLoading = true,
                    isLoggedIn = true,
                    history = accountPrefs.history()
                )
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: "登录失败")
            }
        }
    }
}
