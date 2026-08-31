package com.padguard.child.bind

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.core.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 绑定流程 ViewModel。
 *
 * 流程：Welcome → InputCode → Submitting → Waiting → Success / Failed
 *
 * P0 阶段为模拟实现：把绑定码直接当作 deviceId 写本地 DataStore，
 * 真实接入时把 [performBind] 里的模拟换成调用 transport 模块的 ApiCaller 即可。
 */
@HiltViewModel
class BindViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _state = MutableStateFlow(BindUiState(step = BindStep.Welcome))
    val state: StateFlow<BindUiState> = _state.asStateFlow()

    fun onCodeChanged(input: String) {
        val sanitized = input.filter { it.isDigit() }.take(6)
        _state.value = _state.value.copy(code = sanitized, errorMessage = null)
    }

    /**
     * 欢迎页「开始绑定」一键绑定：用演示绑定码 [DEFAULT_TEST_BIND_CODE] 直接走完提交流程。
     *
     * 为什么欢迎页不跳「输入码」页：演示/联调阶段没有家长端 App 来生成真实 6 位码，
     * 强行让用户在键盘上敲码只会让首次启动卡 30 秒。一键绑定能验证「绑定→落本地→进入主页」整条链路。
     * 真实接入时此方法替换为「从家长端扫码/读屏」得到的 code → [submit]。
     */
    fun startBindingWithDefaultCode() {
        if (_state.value.step == BindStep.Submitting || _state.value.step == BindStep.Waiting) return
        _state.value = _state.value.copy(code = DEFAULT_TEST_BIND_CODE, errorMessage = null)
        submit()
    }

    fun submit() {
        val code = _state.value.code
        if (code.length != 6) {
            _state.value = _state.value.copy(errorMessage = "请输入 6 位数字绑定码")
            return
        }
        _state.value = _state.value.copy(step = BindStep.Submitting, errorMessage = null)
        viewModelScope.launch {
            // 模拟网络请求：先等 1.2s，再进入「等待家长确认」状态，再等 2s 模拟家长点击
            delay(1_200)
            _state.value = _state.value.copy(step = BindStep.Waiting)
            delay(2_000)
            performBind(code)
        }
    }

    fun retry() {
        _state.value = BindUiState(step = BindStep.InputCode, code = _state.value.code)
    }

    private suspend fun performBind(code: String) {
        // P0 模拟：把绑定码当作 deviceId 写入本地，并构造一个空白的 BindResult
        val result = com.padguard.core.data.model.BindResult(
            deviceId = code,
            deviceToken = "tok-$code",
            mqttUsername = "child-$code",
            mqttPassword = "pwd-$code",
            hmacSecret = "hmac-$code",
            expiresAt = System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000
        )
        val success = runCatching {
            authRepository.saveBindResult(result, deviceSn = "SN-$code")
            true
        }.getOrDefault(false)
        if (success) {
            _state.value = _state.value.copy(step = BindStep.Success)
        } else {
            _state.value = _state.value.copy(
                step = BindStep.Failed,
                errorMessage = "保存失败，请重试"
            )
        }
    }

    fun backToWelcome() {
        _state.value = BindUiState()
    }

    fun backToInput() {
        _state.value = _state.value.copy(step = BindStep.InputCode, errorMessage = null)
    }

    companion object {
        /**
         * 演示/联调用的固定绑定码。
         *
         * P0 阶段服务端 + 家长端 App 尚未联调，用固定码走通「绑定→落本地→进入主页」链路。
         * 等家长端能生成真实码时，移除此常量 + [startBindingWithDefaultCode]，欢迎页跳到输入码页。
         */
        const val DEFAULT_TEST_BIND_CODE = "123456"
    }
}

/** 绑定流程状态机。 */
enum class BindStep { Welcome, InputCode, Submitting, Waiting, Success, Failed }

data class BindUiState(
    val step: BindStep = BindStep.Welcome,
    val code: String = "",
    val errorMessage: String? = null
)
