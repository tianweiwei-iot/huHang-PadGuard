package com.padguard.child.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.core.data.repository.AuthRepository
import com.padguard.core.transport.TransportSettings
import com.padguard.core.transport.http.ApiCaller
import com.padguard.core.transport.http.ApiResult
import com.padguard.core.transport.http.FamilyAuthApi
import com.padguard.core.transport.http.FamilyLoginRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 被管控端登录 ViewModel（复用家长家庭账号）。
 *
 * 流程：输入手机号+密码 → 调 [FamilyAuthApi.login]（家庭账号登录接口）→
 * 校验通过则 [AuthRepository.saveChildLogin] 持久化登录态，入口分流自动推进到权限/绑定流程。
 * 服务器地址经 [TransportSettings] 动态生效（高级设置可改），无需重装。
 */
@HiltViewModel
class ChildLoginViewModel @Inject constructor(
    private val familyAuthApi: FamilyAuthApi,
    private val apiCaller: ApiCaller,
    private val authRepository: AuthRepository,
    private val transportSettings: TransportSettings
) : ViewModel() {

    /** 当前生效的服务器地址（高级设置可修改并持久化，立即生效）。 */
    val serverUrl: StateFlow<String> = transportSettings.baseUrl
    fun setServerUrl(url: String) = transportSettings.setBaseUrl(url)

    private val _phone = MutableStateFlow("")
    val phone: StateFlow<String> = _phone

    private val _password = MutableStateFlow("")
    val password: StateFlow<String> = _password

    private val _state = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val state: StateFlow<LoginUiState> = _state

    fun onPhoneChanged(value: String) {
        _phone.value = value
        if (_state.value is LoginUiState.Error) _state.value = LoginUiState.Idle
    }

    fun onPasswordChanged(value: String) {
        _password.value = value
        if (_state.value is LoginUiState.Error) _state.value = LoginUiState.Idle
    }

    fun login() {
        val p = _phone.value.trim()
        val pw = _password.value
        if (p.isBlank() || pw.isBlank()) {
            _state.value = LoginUiState.Error("请输入账号和密码")
            return
        }
        _state.value = LoginUiState.Loading
        viewModelScope.launch {
            val result = apiCaller.call("child-login") {
                familyAuthApi.login(FamilyLoginRequest(phone = p, password = pw))
            }
            when (result) {
                is ApiResult.Success -> {
                    authRepository.saveChildLogin(p, result.value.token, result.value.user?.id ?: "")
                    _state.value = LoginUiState.Success
                }
                is ApiResult.BizError -> _state.value = LoginUiState.Error(result.message)
                is ApiResult.Failure -> _state.value = LoginUiState.Error(result.message)
            }
        }
    }
}

/** 登录页 UI 状态机。 */
sealed interface LoginUiState {
    data object Idle : LoginUiState
    data object Loading : LoginUiState
    data object Success : LoginUiState
    data class Error(val msg: String) : LoginUiState
}
