package com.padguard.parent.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.parent.data.remote.readableUserFacingError
import com.padguard.parent.domain.repository.AuthRepository
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
    val path: String = "/v1",
    /** 协议：http 或 https。解析时保留用户原输入，保存时原样回写，避免 https 被悄悄降级成 http */
    val scheme: String = "http"
)

/** 把完整 baseUrl 解析成 地址 / 端口 / 前缀，供高级设置弹窗回显。 */
fun parseServerConfig(raw: String): ServerConfig {
    val trimmed = raw.trim()
    // 先判定协议：用户若填了 https://，解析后必须把 scheme 记下来，
    // 否则 buildServerConfig 一律回写 http://，等于把加密链路悄悄降级成明文。
    val scheme = when {
        trimmed.startsWith("https://", ignoreCase = true) -> "https"
        trimmed.startsWith("http://", ignoreCase = true) -> "http"
        else -> "http"
    }
    val noScheme = trimmed.removePrefix("https://").removePrefix("http://")
    val slash = noScheme.indexOf('/')
    val authority = if (slash >= 0) noScheme.substring(0, slash) else noScheme
    val pathPart = if (slash >= 0) noScheme.substring(slash) else "/"
    val colon = authority.indexOf(':')
    val host = if (colon >= 0) authority.substring(0, colon) else authority
    val port = if (colon >= 0) authority.substring(colon + 1) else "8090"
    return ServerConfig(host = host, port = port, path = pathPart, scheme = scheme)
}

/** 由 地址 / 端口 / 前缀 拼回完整 baseUrl；任一不合法返回 null。 */
fun buildServerConfig(host: String, port: String, path: String, scheme: String = "http"): String? {
    val h = host.trim()
    if (h.isBlank()) return null
    val p = port.trim().toIntOrNull() ?: return null
    if (p <= 0 || p > 65535) return null
    val cleanPath = path.trim().trimStart('/').trimEnd('/').let { if (it.isBlank()) "" else "/$it" }
    // 保留用户选择的协议；不允许凭空降级成 http（默认也是 http，仅当解析时就无 scheme 才落到 http）。
    val s = if (scheme.equals("https", ignoreCase = true)) "https" else "http"
    return "$s://$h:$p$cleanPath/"
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
    /**
     * 是否处于注册模式。
     *
     * 做成登录页内的模式切换，而不是单独开一个注册页：
     * 注册与登录共用手机号、服务器地址、历史账号这些状态，
     * 拆成两个页面就得在它们之间来回传参，且服务地址配置会出现两份（改一处漏一处）。
     */
    val isRegisterMode: Boolean = false,
    /** 注册时的确认密码，仅用于在端上拦截"两次输入不一致"，不上传服务端 */
    val confirmPassword: String = "",
    /** 注册时的可选昵称；留空由服务端生成默认昵称 */
    val nickname: String = "",
    /** 历史登录账号（最近使用在前），供账号框下拉选择 */
    val history: List<AccountPrefs.SavedAccount> = emptyList(),
    /** 忘记密码引导弹窗开关（端 + 服务端均无自动重置端点，引导走客服人工重置） */
    val showForgotPassword: Boolean = false,
    /**
     * 是否已显式勾选同意《用户协议》和《隐私政策》（应用市场审核硬指标）。
     * 默示同意（"登录即表示同意"）不被国内应用市场接受，尤其未成年人相关产品。
     */
    val consentAgreed: Boolean = false
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val serverPrefs: ServerPrefs,
    private val accountPrefs: AccountPrefs,
    private val consentPrefs: com.padguard.parent.di.PrivacyConsentPrefs
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        // 只回填上次登录的**手机号**。密码一律不回填、不落库（见 AccountPrefs 类注释）：
        // 家长密码一旦持久化，设备被 root 或备份提取即等同于把家长门户钥匙交给孩子。
        val history = accountPrefs.history()
        _uiState.value = _uiState.value.copy(
            history = history,
            phone = history.firstOrNull()?.phone.orEmpty(),
            consentAgreed = consentPrefs.isAgreed()
        )
    }

    /**
     * 用户主动勾选同意协议时调用。落盘版本+时间，重启后免重复勾选；
     * 协议版本升级后 [PrivacyConsentPrefs.isAgreed] 返回 false，会强制重新勾选。
     */
    fun agreeToPolicies() {
        consentPrefs.agree()
        _uiState.value = _uiState.value.copy(consentAgreed = true, error = null)
    }

    /** 所有登录/注册入口的统一合规门槛：未显式勾选同意则拒绝发起请求。 */
    private fun consentGate(state: LoginUiState): Boolean {
        if (state.consentAgreed) return true
        _uiState.value = state.copy(error = "请先阅读并勾选同意《用户协议》和《隐私政策》")
        return false
    }

    /** 当前生效的服务器地址（登录页「高级设置」可修改，改完立即生效） */
    val serverUrl: StateFlow<String> = serverPrefs.baseUrl

    /**
     * 保存服务器地址。返回 false 表示被 [com.padguard.parent.data.remote.EndpointSafety] 拒绝
     * （格式不合法，或公网地址用了明文 HTTP）。具体原因由表单里的校验文案展示，
     * 这里只做最后一道拦截，避免未来新增的写入点绕过安全准入。
     */
    fun saveServerUrl(url: String): Boolean = serverPrefs.setBaseUrl(url)

    /** 恢复出厂默认服务器地址：用户填错导致彻底连不上时的兜底回退。 */
    fun resetServerUrl() {
        serverPrefs.resetToDefault()
    }

    fun updatePhone(phone: String) {
        _uiState.value = _uiState.value.copy(phone = phone, error = null)
    }

    /** 从历史账号列表里选一个：只回填手机号，密码仍需家长输入。 */
    fun selectHistoryAccount(phone: String) {
        _uiState.value = _uiState.value.copy(phone = phone, error = null)
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

    // 忘记密码——端 + 服务端均无自助重置端点（服务端改密需管理员），
    // 故弹出引导弹窗，引导用户走客服人工重置，而非一个"点了没反应"的死按钮。
    fun forgotPassword() {
        _uiState.value = _uiState.value.copy(showForgotPassword = true)
    }

    fun dismissForgotPassword() {
        _uiState.value = _uiState.value.copy(showForgotPassword = false)
    }

    fun updatePassword(password: String) {
        _uiState.value = _uiState.value.copy(password = password, error = null)
    }

    fun updateSmsCode(code: String) {
        _uiState.value = _uiState.value.copy(smsCode = code, error = null)
    }

    /** 登录 / 注册模式切换；切换时清空错误与仅属于另一模式的输入，避免串味 */
    fun toggleRegisterMode() {
        _uiState.value = _uiState.value.copy(
            isRegisterMode = !_uiState.value.isRegisterMode,
            confirmPassword = "",
            error = null
        )
    }

    fun updateConfirmPassword(v: String) {
        _uiState.value = _uiState.value.copy(confirmPassword = v, error = null)
    }

    fun updateNickname(v: String) {
        _uiState.value = _uiState.value.copy(nickname = v, error = null)
    }

    /**
     * 手机号注册并直接进入主界面。
     *
     * 端上只做"必须本地才能判"的校验（两次密码一致、长度），
     * 手机号格式与重复注册这类判断交给服务端 —— 端上重复一份正则，
     * 将来运营商放开新号段就要同时改两端，属于典型的规则双写漂移。
     *
     * 注册成功后服务端已签发 token，因此这里与登录成功走同一条收尾路径
     * （落盘账号 → isLoggedIn），不再要求用户重新登录一次。
     */
    fun register() {
        val state = _uiState.value
        if (!consentGate(state)) return
        val phone = state.phone.trim()
        val pwd = state.password
        when {
            phone.isBlank() -> {
                _uiState.value = state.copy(error = "请输入手机号")
                return
            }
            pwd.length < 6 -> {
                _uiState.value = state.copy(error = "密码至少 6 位")
                return
            }
            pwd != state.confirmPassword -> {
                _uiState.value = state.copy(error = "两次输入的密码不一致")
                return
            }
        }
        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, error = null)
            val result = authRepository.register(phone, pwd, state.nickname.trim().ifBlank { null })
            result.onSuccess {
                // 注册时不静默记住密码（无勾选语义）；记住密码仅限登录页用户主动勾选。
                accountPrefs.save(phone)
                // R-P3-5：成功后立刻把明文密码从内存状态里抹掉 ——
                // 登录页只是被导航走，ViewModel 仍留在返回栈里，密码会一直挂在
                // UiState 上（进程被 dump / 内存快照 / 崩溃上报都可能带出去）。
                _uiState.value = _uiState.value.copy(
                    isLoading = true,
                    isLoggedIn = true,
                    password = "",
                    confirmPassword = "",
                    history = accountPrefs.history()
                )
            }.onFailure { e ->
                // 统一中文错误出口：服务端的具体原因（用户不存在/密码错误/冻结/锁定）原样显示，
                // OkHttp 的英文网络异常翻译成「连不上服务器/连接超时」，不再直接上屏 e.message。
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = readableUserFacingError(e, "注册失败，请稍后重试")
                )
            }
        }
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
                    error = readableUserFacingError(e, "验证码发送失败，请稍后重试")
                )
            }
        }
    }

    fun loginWithPassword() {
        val state = _uiState.value
        if (!consentGate(state)) return
        if (state.phone.isBlank() || state.password.isBlank()) return
        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, error = null)
            val result = authRepository.loginWithPassword(state.phone, state.password)
            result.onSuccess {
                accountPrefs.save(state.phone)
                // R-P3-5：登录成功后立即清空明文密码（理由见 register()）
                _uiState.value = _uiState.value.copy(
                    isLoading = true,
                    isLoggedIn = true,
                    password = "",
                    history = accountPrefs.history()
                )
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = readableUserFacingError(e, "登录失败，请稍后重试")
                )
            }
        }
    }

    fun loginWithSms() {
        val state = _uiState.value
        if (!consentGate(state)) return
        if (state.phone.isBlank() || state.smsCode.length != 6) return
        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, error = null)
            val result = authRepository.loginWithSmsCode(state.phone, state.smsCode)
            result.onSuccess {
                // 短信登录没有密码可存，只记账号，方便下次直接选中
                accountPrefs.save(state.phone)
                // R-P3-5：验证码同样属于短时凭据，成功后立即从内存状态清掉
                _uiState.value = _uiState.value.copy(
                    isLoading = true,
                    isLoggedIn = true,
                    smsCode = "",
                    history = accountPrefs.history()
                )
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = readableUserFacingError(e, "登录失败，请稍后重试")
                )
            }
        }
    }
}
