package com.padguard.child.ui.profile

import android.app.admin.DevicePolicyManager
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.core.data.repository.AuthRepository
import com.padguard.core.data.model.policy.ControlMode
import com.padguard.core.engine.admin.DeviceAdminBridge
import com.padguard.core.common.Logger
import com.padguard.core.transport.RemoteDataSource
import com.padguard.core.transport.TransportSettings
import com.padguard.core.transport.http.ApiResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * "我的"页 ViewModel。
 *
 * 设备硬件信息（型号 / Android 版本 / 存储 / 电量）实时读取，不进数据库；
 * 管控权限状态读取 [DevicePolicyManager] 标记的位。
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val transportSettings: TransportSettings,
    private val remote: RemoteDataSource,
    private val admin: DeviceAdminBridge
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    /** 当前生效的服务器地址（「更多 → 服务器地址」可修改并持久化）。 */
    val serverUrl: StateFlow<String> = transportSettings.baseUrl

    fun setServerUrl(url: String) {
        transportSettings.setBaseUrl(url)
    }

    init {
        viewModelScope.launch {
            // combine 仅对 2~5 个流有解构重载；超过 5 个会退化为 vararg(Array) 重载，解构参数推断失败。
            // 这里把 7 个流拆成「5 + 2」两段组合。
            val base = combine(
                authRepository.studentName,
                authRepository.deviceSn,
                authRepository.isBound,
                authRepository.deviceName,
                authRepository.rememberedCredentials
            ) { name, sn, bound, deviceName, creds ->
                ProfileBase(name, sn, bound, deviceName, creds)
            }
            combine(base, authRepository.childNickname, authRepository.childAvatar) { b, nickname, avatar ->
                val (name, sn, bound, deviceName, creds) = b
                val adminState = detectAdminState()
                ProfileUiState(
                    studentName = name,
                    childNickname = nickname,
                    childAvatar = avatar,
                    deviceSn = sn.ifBlank { Build.SERIAL ?: "未识别" },
                    deviceName = deviceName,
                    isBound = bound,
                    account = creds.first,
                    accountPassword = creds.second,
                    deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                    androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                    storage = readStorage(),
                    battery = readBattery(),
                    adminState = adminState,
                    deviceAdminActive = adminState != AdminState.NONE,
                    isDeviceOwner = adminState == AdminState.OWNER,
                    usageStatsGranted = isUsageStatsGranted()
                )
            }
                .flowOn(Dispatchers.IO)
                .collect { _state.value = it }
        }
    }

    /**
     * 孩子端自定义设备名（P6）。
     * 本地持久化 + 上报服务端两步：服务端写入台账后，
     * 家长端下次拉取设备列表即可看到新名字（实时性由家长端轮询/推送决定）。
     */
    /** 清除本机已记录的账号密码（不影响登录态与已绑定设备）。 */
    fun clearRememberedCredentials() {
        viewModelScope.launch { authRepository.clearRememberedCredentials() }
    }

    fun saveDeviceName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { authRepository.saveDeviceName(trimmed) }
            val deviceId = runCatching { authRepository.getDeviceId() }.getOrDefault("")
            if (deviceId.isNotBlank()) {
                remote.updateDeviceName(deviceId, trimmed)
            }
        }
    }

    /**
     * 孩子端自定义"孩子姓名"，本地持久化 + 实时上报服务端（家长端台账同步可见）。
     */
    fun saveStudentName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            authRepository.saveStudentName(trimmed)
            val deviceId = runCatching { authRepository.getDeviceId() }.getOrDefault("")
            if (deviceId.isNotBlank()) {
                remote.updateChildProfile(childName = trimmed)
            }
        }
    }

    /**
     * 孩子端自定义昵称，本地持久化 + 实时上报服务端（家长端台账同步可见）。
     */
    fun saveNickname(nickname: String) {
        val trimmed = nickname.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            authRepository.saveChildNickname(trimmed)
            val deviceId = runCatching { authRepository.getDeviceId() }.getOrDefault("")
            if (deviceId.isNotBlank()) {
                remote.updateChildProfile(childNickname = trimmed)
            }
        }
    }

    /**
     * 孩子端选择头像（图库图片），上传服务端拿到 URL 后本地持久化。
     * 服务端 /device/avatar 已顺便把 URL 写入台账，家长端拉取即可显示。
     */
    fun saveAvatar(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull() ?: return@launch
            val deviceId = runCatching { authRepository.getDeviceId() }.getOrDefault("")
            if (deviceId.isBlank()) return@launch
            when (val r = remote.uploadAvatar(bytes, "avatar_${System.currentTimeMillis()}.jpg")) {
                is ApiResult.Success -> authRepository.saveChildAvatar(r.value)
                is ApiResult.BizError -> Logger.w(TAG) { "头像上传失败：${r.message}" }
                is ApiResult.Failure -> Logger.w(TAG) { "头像上传失败：${r.message}" }
            }
        }
    }

    private fun readStorage(): String {
        return runCatching {
            val stat = StatFs(Environment.getDataDirectory().path)
            val total = stat.totalBytes / (1024 * 1024 * 1024)
            val free = stat.availableBytes / (1024 * 1024 * 1024)
            "$free GB 可用 / 共 $total GB"
        }.getOrDefault("未知")
    }

    private fun readBattery(): String {
        return runCatching {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (level in 0..100) "电量 $level%" else "电量未知"
        }.getOrDefault("电量未知")
    }

    /**
     * 准确探测设备管控权限状态（P7 修复核心）。
     *
     * 之前 [isDeviceAdminActive] 只判断"是否激活了 Device Admin"，
     * 但普通 Device Admin（DA）并不等于 Device Owner（DO）：
     * DA 足以支撑锁屏/相机禁用等基础能力，但 Kiosk、USB 限制、静默安装等需要 DO。
     * 状态页若只显示"已开启/未开启"，孩子看到"已开启"却点"去开启"无反应
     * （因为 DA 已激活，系统不再弹激活窗），误以为授权失败。
     *
     * 这里统一区分三种状态：NONE / ADMIN / OWNER，让状态页与引导页都基于真实权限说话。
     */
    private fun detectAdminState(): AdminState {
        return runCatching {
            admin.refreshControlMode()
            when (admin.controlMode.value) {
                ControlMode.DEVICE_OWNER, ControlMode.PROFILE_OWNER -> AdminState.OWNER
                ControlMode.DEVICE_ADMIN -> AdminState.ADMIN
                ControlMode.LEGACY -> AdminState.NONE
                else -> AdminState.NONE
            }
        }.getOrDefault(AdminState.NONE)
    }

    private fun isUsageStatsGranted(): Boolean {
        // 真实检测「使用情况访问」权限：Android 把它放在 AppOps 的 PACKAGE_USAGE_STATS，
        // 普通 checkSelfPermission 拿不到，必须用 AppOpsManager.checkOpNoThrow。
        // 之前写死返回 false，导致「我的」页永远显示未授权、孩子也拿不到真实时长。
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        return runCatching {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            ) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }
}

data class ProfileUiState(
    val studentName: String = "",
    val childNickname: String = "",
    val childAvatar: String = "",
    val deviceSn: String = "未识别",
    val deviceName: String = "",
    val isBound: Boolean = false,
    /** 已记录的家长端账号（空串表示未记录） */
    val account: String = "",
    /** 已记录的家长端密码（空串表示未记录；UI 默认掩码展示） */
    val accountPassword: String = "",
    val deviceModel: String = "未知设备",
    val androidVersion: String = "Android --",
    val storage: String = "未知",
    val battery: String = "未知",
    val adminState: AdminState = AdminState.NONE,
    val deviceAdminActive: Boolean = false,
    val isDeviceOwner: Boolean = false,
    val usageStatsGranted: Boolean = false
)

/** 设备管控权限真实状态（P7）：区别于"仅设备管理员"与"设备所有者" */
enum class AdminState {
    /** 未激活任何设备管理权限，锁屏/应用管控等全部不可用 */
    NONE,
    /** 已激活普通设备管理员（DA）：可锁屏、禁相机，但不可 Kiosk/USB 限制/静默安装 */
    ADMIN,
    /** 设备所有者 / 资料所有者（DO/PO）：全部能力可用 */
    OWNER
}

/** combine 第一段的聚合结果（5 个流），避免 combine 退化为 vararg(Array) 重载 */
private data class ProfileBase(
    val studentName: String,
    val deviceSn: String,
    val isBound: Boolean,
    val deviceName: String,
    val creds: Pair<String, String>
)

private const val TAG = "ProfileViewModel"
