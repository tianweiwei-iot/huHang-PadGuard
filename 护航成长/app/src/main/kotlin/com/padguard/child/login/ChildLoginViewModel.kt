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
import kotlinx.coroutines.flow.first
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

    /** 是否勾选「记住账号」；勾选后仅账号落地，下次进入自动填充账号（密码从不持久化，符合安全设计）。 */
    private val _remember = MutableStateFlow(false)
    val remember: StateFlow<Boolean> = _remember

    /**
     * 用户本轮是否已手动编辑过账号 / 勾选（R29）。
     *
     * `rememberedCredentials.first()` 冷启动读盘有几十~几百毫秒延迟，旧实现首帧无条件回填，
     * 会把家长刚敲进去的手机号清空、把刚勾的「记住账号」弹回未勾选。
     */
    @Volatile
    private var userEdited = false

    init {
        // 已记录过账号：回填账号并默认勾选「记住账号」，省去每次重输。
        // 密码按设计从不持久化（见 AuthRepository.saveChildCredentials），故只回填账号；
        // 旧逻辑要求 savedPwd 非空才回填，导致「记住账号」功能彻底失效（密码永远为空串），
        // 改为仅判断账号非空即可回填并默认勾选（决策抽成 rememberInitial 便于单测，见 companion）。
        viewModelScope.launch {
            val remembered = authRepository.rememberedCredentials.first()
            // 持久化回填只能发生在用户动手之前，绝不覆盖已输入内容。
            if (userEdited) return@launch
            val (phone, checked) = rememberInitial(remembered)
            _phone.value = phone
            _remember.value = checked
        }
    }

    fun onRememberChanged(value: Boolean) {
        userEdited = true
        _remember.value = value
        // 取消勾选立即清除已记录的账号，避免"关了开关但账号还在库里"
        if (!value) {
            viewModelScope.launch { authRepository.clearRememberedCredentials() }
        }
    }

    companion object {
        /**
         * 「记住账号」初始回填决策（R15·L1 抽离，便于单测，无协程依赖）。
         *
         * 密码按安全设计从不持久化（见 AuthRepository.saveChildCredentials），
         * rememberedCredentials 第二元素恒为空串。旧逻辑要求密码非空才回填，
         * 导致功能彻底失效；正确口径：只看账号是否非空 —— 非空则回填账号并默认勾选。
         */
        internal fun rememberInitial(saved: Pair<String, String>): Pair<String, Boolean> {
            val (phone, _) = saved
            return if (phone.isNotBlank()) phone to true else "" to false
        }
    }

    private val _state = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val state: StateFlow<LoginUiState> = _state

    fun onPhoneChanged(value: String) {
        userEdited = true
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
                    // 勾选了才记录密码；未勾选则不写入（且已在开关关闭时清除过）
                    if (_remember.value) {
                        authRepository.saveChildCredentials(p, pw)
                    }
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
