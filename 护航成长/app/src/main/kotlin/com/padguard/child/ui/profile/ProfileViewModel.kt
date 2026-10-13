package com.padguard.child.ui.profile

import android.app.admin.DevicePolicyManager
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.core.data.repository.AuthRepository
import com.padguard.core.data.repository.PolicyRepository
import com.padguard.core.data.model.policy.AppListMode
import com.padguard.core.data.model.policy.ControlMode
import com.padguard.core.engine.admin.Capability
import com.padguard.core.engine.admin.DeviceAdminBridge
import com.padguard.child.guard.GuardA11y
import com.padguard.child.guard.GuardGate
import com.padguard.child.guard.GuardPasswordManager
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
    private val admin: DeviceAdminBridge,
    private val policyRepository: PolicyRepository,
    private val guardPassword: GuardPasswordManager
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
            combine(
                base,
                authRepository.childNickname,
                authRepository.childAvatar,
                policyRepository.observePolicy(),
                authRepository.isChildLoggedIn
            ) { b, nickname, avatar, policy, childLoggedIn ->
                val (name, sn, bound, deviceName, creds) = b
                val adminState = detectAdminState()
                val appPolicy = policy.app
                ProfileUiState(
                    studentName = name,
                    childNickname = nickname,
                    childAvatar = avatar,
                    isChildLoggedIn = childLoggedIn,
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
                    usageStatsGranted = isUsageStatsGranted(),
                    batteryOptimizationWhitelisted = isBatteryOptimizationWhitelisted(),
                    // ---------- 应用管控自检 ----------
                    // 家长最常问的是"我禁了应用，孩子还是能打开"。答案其实由三件事共同决定，
                    // 缺一件就静默失效，而界面上原本一点提示都没有：
                    //   1. 名单有没有真的收到（下发了 0 条 = 家长端根本没配置成功）；
                    //   2. 拿不拿得到前台包名（没开"使用情况访问"就永远探不到）；
                    //   3. 有没有 DO/PO（隐藏/挂起应用只有 DO/PO 能做，设备管理员做不到）。
                    // 把三项摊开显示，家长不用猜就能知道该补哪一项。
                    appListModeLabel = when (appPolicy.mode) {
                        AppListMode.BLACKLIST -> "黑名单"
                        AppListMode.WHITELIST -> "白名单"
                    },
                    appListCount = if (appPolicy.mode == AppListMode.BLACKLIST) {
                        appPolicy.blacklist.size
                    } else {
                        appPolicy.whitelist.size
                    },
                    appSystemRuleCount = appPolicy.systemAppRules.size,
                    canHideApps = admin.can(Capability.HIDE_PACKAGES),
                    canSuspendApps = admin.can(Capability.SUSPEND_PACKAGES),
                    // ---------- 设备所有者(DO/PO)专属能力矩阵 ----------
                    // 这些能力在存量 C 端平板（无 DO）下由 engine 层逐条回执 UNSUPPORTED 降级，
                    // 这里把它们逐项摊开，让家长/孩子一眼看出"哪些管控在本机根本不生效"，
                    // 而不是误以为策略已下发就一定能落地。can() 是单一事实来源。
                    ownerCapabilities = buildOwnerCapabilityMatrix(admin),
                    // 操作闸门基础设施告警：密码已启用，但操作闸门无障碍服务未开 → 系统级拦截失效
                    guardInfraWarning = guardPassword.isEnabledNow() && !GuardA11y.isEnabled(context)
                )
            }
                .flowOn(Dispatchers.IO)
                .collect { _state.value = it }
        }
    }

    /**
     * 孩子端自定义设备名。
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
     * 退出家庭账号登录。
     *
     * 只清"家庭账号登录态"（[AuthRepository.childLogout]），**保留设备绑定**：
     * 设备绑定是家长扫码建立的受控关系，登出家庭账号不等于解除管控；
     * 真正解除管控要走家长端「解绑设备」，那里会同时清掉凭据与管控策略。
     *
     * 登出后 [authRepository.isChildLoggedIn] 变 false，界面自动回到未登录态。
     */
    fun logout() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { authRepository.childLogout() }
                .onFailure { Logger.w(TAG) { "退出登录失败：${it.message}" } }
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

    /**
     * 真实检测电池优化白名单状态。
     * isIgnoringBatteryOptimizations 在 API 23+ 可用；个别 OEM 定制ROM 可能抛异常，
     * 此时按"未加白名单"处理（引导用户去确认，比假装已豁免安全）。
     */
    private fun isBatteryOptimizationWhitelisted(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }
            .getOrDefault(false)
    }

    /**
     * 操作密码闸门检查：供「我的」页中需要改动系统设置（如开启操作闸门无障碍服务）的入口调用。
     *
     * 返回 `true` 表示可继续（未启用密码 / 处于解锁窗口）；`false` 表示已拉起解锁界面、
     * 原动作应中止。与 [com.padguard.child.guard.GuardGate] 全 App 统一口径。
     */
    fun ensureGuardUnlocked(ctx: Context): Boolean = GuardGate.ensureUnlocked(ctx, guardPassword)

    /**
     * 构建设备所有者(DO/PO)专属能力矩阵，供「我的」页如实标注本机可用能力。
     *
     * 判定全部走 [DeviceAdminBridge.can]，与 engine 层降级逻辑同源，避免 UI 与运行时口径脱节。
     * 存量 C 端平板无 DO，绝大多数项会落到"需设备所有者"，这正是要如实告知家长的事实。
     */
    private fun buildOwnerCapabilityMatrix(admin: DeviceAdminBridge): List<CapabilityStatus> = listOf(
        CapabilityStatus("防卸载自身", admin.can(Capability.BLOCK_UNINSTALL), "需设备所有者(DO/PO)"),
        CapabilityStatus("静默安装应用", admin.can(Capability.SILENT_INSTALL), "需 DO/PO"),
        CapabilityStatus("静默卸载应用", admin.can(Capability.SILENT_UNINSTALL), "需 DO/PO"),
        CapabilityStatus("禁止截屏 / 录屏", admin.can(Capability.DISABLE_SCREEN_CAPTURE), "需 DO/PO"),
        CapabilityStatus("Kiosk 纯净桌面", admin.can(Capability.LOCK_TASK), "需设备所有者(DO)"),
        CapabilityStatus("禁用 USB 调试 / 数据", admin.can(Capability.GLOBAL_SETTINGS), "需 DO（仅生产构建生效）")
    )
}

data class ProfileUiState(
    val studentName: String = "",
    val childNickname: String = "",
    val childAvatar: String = "",
    val deviceSn: String = "未识别",
    val deviceName: String = "",
    val isBound: Boolean = false,
    /** 是否已登录家庭账号（孩子端独立于设备绑定的一套登录态） */
    val isChildLoggedIn: Boolean = false,
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
    val usageStatsGranted: Boolean = false,
    /**
     * 本应用是否已在电池优化白名单里。
     * 不在名单时息屏进 Doze，后台心跳/定位/推送被冻结，"每晚脱管"。
     * 必须读真实状态 —— 写死 false 会让已加白名单的设备也显示"未开启"（假状态）。
     */
    val batteryOptimizationWhitelisted: Boolean = false,
    /** 当前生效的应用名单模式（黑名单 / 白名单） */
    val appListModeLabel: String = "黑名单",
    /** 名单条数；0 表示家长端还没有下发任何应用管控配置 */
    val appListCount: Int = 0,
    /** 系统应用的显式规则条数（BLOCK / ALLOW 覆盖） */
    val appSystemRuleCount: Int = 0,
    /** 能否隐藏应用（图标消失）—— 仅 DO/PO */
    val canHideApps: Boolean = false,
    /** 能否挂起应用（图标变灰）—— 仅 DO/PO */
    val canSuspendApps: Boolean = false,
    /**
     * 设备所有者(DO/PO)专属能力矩阵：逐项标出本机是否可用。
     * 存量 C 端平板通常没有 DO，这些项会如实显示"需设备所有者(DO)"。
     */
    val ownerCapabilities: List<CapabilityStatus> = emptyList(),
    /**
     * 操作闸门基础设施告警：家长已启用操作密码，但「操作闸门无障碍服务」未在系统设置开启，
     * 此时系统级拦截失效，孩子可从设置/通知栏绕过密码。需在「我的」页弹出引导横幅。
     */
    val guardInfraWarning: Boolean = false
)

/** 设备所有者(DO/PO)专属能力的可用性状态，供「我的」页能力矩阵逐项如实展示 */
data class CapabilityStatus(
    /** 能力名称，如「防卸载自身」「Kiosk 纯净桌面」 */
    val name: String,
    /** 当前设备是否具备该能力（true = 可用；false = 需设备所有者，本机降级不可用） */
    val supported: Boolean,
    /** 不可用时展示的原因，如「需设备所有者(DO)」 */
    val note: String
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
