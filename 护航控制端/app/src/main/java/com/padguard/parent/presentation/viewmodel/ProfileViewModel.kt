package com.padguard.parent.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.parent.domain.repository.AuthRepository
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

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val serverPrefs: ServerPrefs
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    /** 当前生效的服务器地址（「我的」页可修改，改完立即生效，无需退出登录） */
    val serverUrl: StateFlow<String> = serverPrefs.baseUrl

    /** 保存服务器地址。格式不合法（必须以 http:// 或 https:// 开头）则忽略。 */
    fun saveServerUrl(url: String) {
        val u = url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) return
        serverPrefs.setBaseUrl(u)
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
}
