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
 * 我的页 ViewModel
 * 负责退出登录等账号操作，通过 AuthRepository 这一官方接口完成，
 * 而非在 UI 层遗留 TODO 占位。
 */
data class ProfileUiState(
    val isLoggingOut: Boolean = false,
    val loggedOut: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    fun logout() {
        if (_uiState.value.isLoggingOut) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoggingOut = true)
            authRepository.logout()
                .onSuccess { _uiState.value = _uiState.value.copy(isLoggingOut = false, loggedOut = true) }
                .onFailure { e -> _uiState.value = _uiState.value.copy(isLoggingOut = false, error = e.message) }
        }
    }
}
