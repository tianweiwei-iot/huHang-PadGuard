package com.padguard.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

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
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

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
