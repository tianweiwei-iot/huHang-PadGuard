package com.padguard.child.bind

import android.app.Application
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.core.data.repository.AuthRepository
import com.padguard.core.engine.admin.DeviceAdminBridge
import com.padguard.core.transport.TransportSettings
import com.padguard.core.transport.http.ApiCaller
import com.padguard.core.transport.http.ApiCode
import com.padguard.core.transport.http.ApiResult
import com.padguard.core.transport.http.BindRequest
import com.padguard.core.transport.http.PadGuardApi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 绑定流程 ViewModel。
 *
 * 流程：Welcome → InputCode → Submitting → Success / Failed
 *
 * 真实链路：孩子端扫描/输入家长端下发的 6 位绑定码 → 组装设备静态信息 →
 * 调服务端 [PadGuardApi.bind]（`POST /api/v1/device/bind`）→ 服务端消费绑定码、
 * 创建设备并下发令牌/MQTT 凭据/HMAC 密钥 → 本地 [AuthRepository.saveBindResult] 持久化。
 * 绑定成功后管控端轮询 `getDeviceList()` 即可看到本设备。
 *
 * 注意：绑定发生在拿到设备令牌之前，[PadGuardApi] 走标准 Retrofit 且 [AuthInterceptor]
 * 对 `/device/bind` 放行鉴权，因此本流程不依赖任何本地令牌。
 */
@HiltViewModel
class BindViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val transportSettings: TransportSettings,
    private val apiCaller: ApiCaller,
    private val padGuardApi: PadGuardApi,
    private val deviceAdminBridge: DeviceAdminBridge,
    private val app: Application
) : ViewModel() {

    private val _state = MutableStateFlow(BindUiState(step = BindStep.Welcome))
    val state: StateFlow<BindUiState> = _state.asStateFlow()

    /** 当前生效的服务器地址（绑定欢迎页「高级设置」可修改并持久化，绑定后立即生效）。 */
    val serverUrl: StateFlow<String> = transportSettings.baseUrl

    fun setServerUrl(url: String) {
        transportSettings.setBaseUrl(url)
    }

    fun onCodeChanged(input: String) {
        val sanitized = input.filter { it.isDigit() }.take(6)
        _state.value = _state.value.copy(code = sanitized, errorMessage = null)
    }

    /** 欢迎页「② 输入绑定码」：进入手动输入页（清空旧码，避免残留演示码误提交） */
    fun goToInputCode() {
        if (_state.value.step == BindStep.Submitting) return
        _state.value = BindUiState(step = BindStep.InputCode)
    }

    fun submit() {
        val code = _state.value.code
        if (code.length != 6) {
            _state.value = _state.value.copy(errorMessage = "请输入 6 位数字绑定码")
            return
        }
        _state.value = _state.value.copy(step = BindStep.Submitting, errorMessage = null)
        viewModelScope.launch {
            performBind(code)
        }
    }

    fun retry() {
        _state.value = BindUiState(step = BindStep.InputCode, code = _state.value.code)
    }

    private suspend fun performBind(code: String) {
        val req = buildBindRequest(code)
        when (val result = apiCaller.call("child-bind") { padGuardApi.bind(req) }) {
            is ApiResult.Success -> {
                val saved = runCatching {
                    authRepository.saveBindResult(result.value, deviceSn = req.deviceSn)
                }.getOrElse { e ->
                    _state.value = _state.value.copy(
                        step = BindStep.Failed,
                        errorMessage = "绑定成功但本地凭据保存失败：${e.message}"
                    )
                    return
                }
                _state.value = _state.value.copy(step = BindStep.Success)
            }
            is ApiResult.BizError -> _state.value = _state.value.copy(
                step = BindStep.Failed,
                errorMessage = mapBindError(result.code, result.message)
            )
            is ApiResult.Failure -> _state.value = _state.value.copy(
                step = BindStep.Failed,
                errorMessage = result.message
            )
        }
    }

    /**
     * 组装绑定请求体（契约 §5.1）。
     * deviceSn 用稳定且无需额外权限的 [Settings.Secure.ANDROID_ID]，
     * controlMode 取当前真实权限模式（由 [DeviceAdminBridge] 探测），供服务端统计与策略分级。
     */
    private fun buildBindRequest(code: String): BindRequest {
        val dm = app.resources.displayMetrics
        val resolution = "${dm.widthPixels}x${dm.heightPixels}"
        val appVersion = runCatching {
            @Suppress("DEPRECATION")
            app.packageManager.getPackageInfo(app.packageName, 0).versionName
        }.getOrNull() ?: ""
        val androidId = Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID)
            .orEmpty()
        val controlMode = deviceAdminBridge.refreshControlMode().name
        return BindRequest(
            bindCode = code,
            deviceSn = androidId,
            imei = "",
            fingerprint = Build.FINGERPRINT.orEmpty(),
            model = Build.MODEL.orEmpty(),
            brand = Build.BRAND.orEmpty(),
            androidVersion = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            resolution = resolution,
            controlMode = controlMode,
            appVersion = appVersion,
            romInfo = ""
        )
    }

    /** 业务错误码映射为可读文案（绑定码无效/过期/已用/冲突是最常见场景）。 */
    private fun mapBindError(code: Int, message: String): String =
        when (code) {
            ApiCode.TOKEN_INVALID ->
                "绑定码无效或已过期，请在家长端重新生成"
            ApiCode.BIND_CODE_EXPIRED ->
                "绑定码无效、已过期或已使用，请在家长端重新生成"
            ApiCode.BIND_CONFLICT ->
                "该设备已绑定其他账号，请先解绑或更换账号"
            ApiCode.DEVICE_UNBOUND ->
                "设备未绑定，请重试"
            ApiCode.CLOCK_SKEW ->
                "设备时间偏差过大，请先校准系统时间"
            ApiCode.SERVER_ERROR ->
                "服务端异常，请稍后重试"
            else -> message.ifBlank { "绑定失败，请重试" }
        }

    fun backToWelcome() {
        _state.value = BindUiState()
    }

    fun backToInput() {
        _state.value = _state.value.copy(step = BindStep.InputCode, errorMessage = null)
    }
}

/** 绑定流程状态机。 */
enum class BindStep { Welcome, InputCode, Submitting, Waiting, Success, Failed }

data class BindUiState(
    val step: BindStep = BindStep.Welcome,
    val code: String = "",
    val errorMessage: String? = null
)
