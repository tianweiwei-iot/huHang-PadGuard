package com.padguard.child.ui.profile

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.core.data.repository.AuthRepository
import com.padguard.core.data.repository.PolicyRepository
import com.padguard.core.data.model.policy.AppListMode
import com.padguard.core.data.model.policy.ControlMode
import com.padguard.core.data.model.policy.ManagementMode
import com.padguard.core.engine.admin.Capability
import com.padguard.core.engine.admin.DeviceAdminBridge
import com.padguard.child.guard.GuardA11y
import com.padguard.child.guard.GuardGate
import com.padguard.child.guard.GuardPasswordManager
import com.padguard.child.permission.SpecialPermission
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
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
    private val guardPassword: GuardPasswordManager,
    /** 上网防护通道运行态（真实状态，供「隐私与安全」展示） */
    private val webGuardVpn: com.padguard.child.guard.WebGuardVpnController,
    /** 无障碍静默自愈能力（DO 或装机时 adb 授权 WRITE_SECURE_SETTINGS），供授权总览如实展示 */
    private val a11yHealer: com.padguard.child.guard.AccessibilityCapabilityHealer
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    /** 当前生效的服务器地址（「更多 → 服务器地址」可修改并持久化）。 */
    val serverUrl: StateFlow<String> = transportSettings.baseUrl

    /** 家长当前选择的管控模式；null 表示老设备尚未选过（入口分流会补弹二选一页）。 */
    val managementMode: StateFlow<ManagementMode?> = authRepository.managementMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * 随时切换管控模式。只改模式键本身——另一档已授予的权限、已下发的策略配置一律不动，
     * 这正是"两档并行、互不影响"。切到硬管控但设备尚无 DO 时，由页面引导完成预置
     * （App 无法自行提权为设备所有者）。
     *
     * 切回硬管控时必须撤销旧的 DO 跳过标记：否则入口分流会把它当成"已知风险仍继续"，
     * 直接跳过预置页，结果就是选了硬管控却没有 DO、能力全部落空。
     */
    fun setManagementMode(mode: ManagementMode) {
        viewModelScope.launch {
            if (mode == ManagementMode.HARD) authRepository.clearDeviceOwnerPromptAck()
            authRepository.setManagementMode(mode)
        }
    }

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
                val appPolicy = policy.app
                // 设备实时状态统一由 readRealtimeState() 采集、applyTo() 覆盖，
                // 与 ON_RESUME 的 refresh() 共用同一份字段集合（单点定义）。
                // 旧写法在这里逐项手写实时字段、refresh() 里又手写一遍且漏了
                // guardInfraWarning/guardInfraDetached —— 家长重开无障碍返回后，
                // 总览已显示"全部已开启"，顶部却仍挂着"操作闸门未完全生效"。
                readRealtimeState().applyTo(
                    ProfileUiState(
                        loaded = true,
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
                        // storage / battery 同属实时读数，已收口进 readRealtimeState()（R29），
                        // 此处不再手写，避免 combine 首帧与 refresh 又分叉。
                        // ---------- 应用管控自检 ----------
                        // 名单有没有真的收到 / 拿不拿得到前台包名 / 有没有 DO/PO，三项摊开。
                        // 能力矩阵与闸门横幅属于设备实时状态，统一交给 readRealtimeState()。
                        appListModeLabel = when (appPolicy.mode) {
                            AppListMode.BLACKLIST -> "黑名单"
                            AppListMode.WHITELIST -> "白名单"
                        },
                        appListCount = if (appPolicy.mode == AppListMode.BLACKLIST) {
                            appPolicy.blacklist.size
                        } else {
                            appPolicy.whitelist.size
                        },
                        appSystemRuleCount = appPolicy.systemAppRules.size
                    )
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

    /**
     * 「使用情况访问」只读单一事实源，禁止在本页再手写一套 AppOps 判定。
     *
     * 历史上这里用 `AppOpsManager.checkOpNoThrow`（API 29 起已废弃、Q+ 带额外的
     * uid/包名归属校验）且取 `Process.myUid()`，而 [SpecialPermission] 与前台应用探测
     * 用的是 `unsafeCheckOpNoThrow` + `applicationInfo.uid`。同一次系统查询出现两套口径：
     * 定制 ROM 上一旦结果不同，「我的」页会在同一屏里同时显示
     * 「授权总览：全部已开启」与「安全状态：使用情况访问 未开启 / 无法拦截」，
     * 家长端还会收到一条恢复不了的假 `USAGE_ACCESS_LOST` 告警 —— 正是
     * 「权限明明开了还显示未开 / 反复提示」最直接的成因。
     */
    private fun isUsageStatsGranted(): Boolean =
        SpecialPermission.USAGE_STATS.isGranted(context)

    /**
     * 电池优化白名单同样只读单一事实源。
     *
     * 旧实现与 [SpecialPermission.BATTERY_OPTIMIZATION] 在 API 29 上行为等价，
     * 但保留第二份手写就意味着「以后改一处必漏另一处」；一并收口，
     * 让授权总览、安全状态行、周期自检永远拿同一个答案。
     */
    private fun isBatteryOptimizationWhitelisted(): Boolean =
        SpecialPermission.BATTERY_OPTIMIZATION.isGranted(context)

    /**
     * 本机授权目录的实时统计，供「我的 → 授权总览」卡片展示。
     *
     * 读的是同一份 [SpecialPermission] 目录，而不是 UI 里写死的一串权限名：
     * 目录新增一项时，装机清单、补开页、掉失自检与这里会同时出现，
     * 不会留下「代码已经引导、界面还在说旧的那几项」这种会误导家长的滞后。
     *
     * 不可检测项（厂商私有开关）的 [SpecialPermission.isGranted] 恒 false，
     * 不能算成普通的「已确认缺失」待办，单列 [AuthorizationSummary.noStateApiCount]，
     * 由 UI 用「需您到设置页确认」如实表达，不冒充已开启也不谎报异常。
     *
     * 2026-11-12 修正：旧实现把这类项**塞进** `missingLabels`（`else -> true`），
     * 于是华为真机上 `missingCount` 永远 ≥ 1 —— 首页状态卡永远显示
     * 「待办：后台运行」「点进去可一键逐项补齐」，"当前全部已开启"这个分支永不可达。
     * 家长开完所有能查的权限，回来仍被叫去开一个早开过的开关，正是「反复提示 /
     * 明明开了还显示未开」的首页版。现在待办清单只收可检测且确实未授予的项，
     * 无状态项由独立计数如实单列，两套口径不再互相污染。
     */
    private fun readAuthorizationSummary(): AuthorizationSummary {
        val perms = SpecialPermission.applicable(context, admin.hasOwnerPrivileges())
        val missing = perms.filter { perm -> perm.detectable && !perm.isGranted(context) }
        return AuthorizationSummary(
            total = perms.size,
            missingLabels = missing.map { it.label },
            noStateApiCount = perms.count { !it.detectable },
            silentHealEnabled = a11yHealer.canSilentHeal()
        )
    }

    /**
     * 采集「设备实时状态」：系统设置页里随时可变、且不是任何 Flow 发射源的读数。
     *
     * 口径**单点定义**：combine 首帧与 [refresh] 的 ON_RESUME 复检都走这里，
     * 再由 [RealtimeState.applyTo] 一次性覆盖到 [ProfileUiState]。
     * 旧实现把实时字段在两处各手写一遍，refresh 漏了 `guardInfraWarning` /
     * `guardInfraDetached` 与 DO 能力矩阵 —— 家长在系统设置重开无障碍返回后，
     * 授权总览已刷新成「全部已开启」，顶部横幅却仍挂着「操作密码闸门未完全生效」，
     * 同屏自相矛盾，且要等某个 DataStore 流恰好 emit 才纠正。
     */
    private fun readRealtimeState(): RealtimeState {
        val adminState = detectAdminState()
        val a11yEnabled = GuardA11y.isEnabled(context)
        val passwordEnabled = guardPassword.isEnabledNow()
        return RealtimeState(
            adminState = adminState,
            deviceAdminActive = adminState != AdminState.NONE,
            isDeviceOwner = adminState == AdminState.OWNER,
            usageStatsGranted = isUsageStatsGranted(),
            batteryOptimizationWhitelisted = isBatteryOptimizationWhitelisted(),
            webGuardActive = webGuardVpn.isActive(),
            authorizationSummary = readAuthorizationSummary(),
            canHideApps = admin.can(Capability.HIDE_PACKAGES),
            canSuspendApps = admin.can(Capability.SUSPEND_PACKAGES),
            ownerCapabilities = buildOwnerCapabilityMatrix(admin),
            // 未开启 / 已开启但没连上是两种结局，文案与家长动作都不同，必须分开。
            guardInfraWarning = passwordEnabled && !a11yEnabled,
            guardInfraDetached = passwordEnabled && a11yEnabled && !GuardA11y.isAlive(context),
            // 电量/存储会随充电、安装应用随时变化，ON_RESUME 必须重读，
            // 否则家长开完权限返回时同屏出现「权限已刷新、电量冻结在进页面那一刻」。
            storage = readStorage(),
            battery = readBattery()
        )
    }

    /**
     * 页面重新可见时刷新「设备实时状态」。
     *
     * ## 为什么必须有
     * [state] 由 DataStore / 策略流的 combine 驱动，而设备管理员、使用情况访问、
     * 电池白名单、VPN 运行态、授权目录状态都是**系统设置页里随时可变**的读数，
     * 不是任何 Flow 的发射源。家长在「权限引导」里开完开关返回「我的」页时，
     * 若没有这一步，卡片仍显示开启前的旧状态 —— 表现就是"明明开了，界面还说未开启"。
     *
     * 只覆盖 [RealtimeState] 定义的实时字段：其余字段（昵称 / 设备名 / 名单统计等）
     * 仍以 combine 的全量快照为准，两条路径不会互相覆盖成脏数据。
     */
    fun refresh() {
        _state.value = readRealtimeState().applyTo(_state.value)
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

/**
 * 「授权总览」卡片的目录化统计。
 *
 * 全字段都来自 [com.padguard.child.permission.SpecialPermission]，
 * 刻意不携带 Android 类型，便于 UI 直接渲染、也便于 JVM 层做契约校验。
 */
data class AuthorizationSummary(
    /** 本机适用的授权项总数（含已开启的、也含无状态接口的厂商开关） */
    val total: Int = 0,
    /**
     * 真实待办：当前**可检测且确实未授予**的项展示名。
     * 刻意不含无状态接口的厂商开关 —— 它们恒 false，混进来会让"全部已开启"永不出现。
     */
    val missingLabels: List<String> = emptyList(),
    /**
     * 本机是否具备「无障碍被清理停用后静默写回」能力：
     * DO，或装机时经 adb 授予 WRITE_SECURE_SETTINGS（第三十二轮源头修复）。
     * 家长必须能一眼看出这台平板被一键清理后是"自动恢复"还是"需人工重开"，
     * 否则装了修复包却漏了装机授权，会误以为问题已解决。
     */
    val silentHealEnabled: Boolean = false,
    /**
     * 本机适用、但无公开状态查询接口的厂商开关项数。
     * 这是**独立统计**，不是 [missingLabels] 的子集：它们不是待办，只作如实提示。
     */
    val noStateApiCount: Int = 0
) {
    /** 真实待办数（只数可检测缺失项）；华为等定制 ROM 上开齐后可为 0 */
    val missingCount: Int get() = missingLabels.size
    /** 与 [missingCount] 同义：待办清单里每一项都能被 API 明确判定，不再做减法 */
    val detectableMissingCount: Int get() = missingCount
}

data class ProfileUiState(
    /**
     * 是否已完成首轮真实状态读取。
     *
     * 默认值里的 `isBound=false` / `adminState=NONE` 是"还没读到"，不是"确实没有"。
     * 缺这个标志时首帧会先渲染「未绑定 + 软管控 + 可被卸载」的红色告警，
     * 几十毫秒后才跳成「已绑定 + 设备所有者硬管控」——家长看到的是一次吓人的误报闪动。
     */
    val loaded: Boolean = false,
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
    /**
     * 上网防护（本地 DNS 过滤 VPN）是否正在运行。
     *
     * 必须是真实运行态而不是"策略要求开启"：策略要求但没授权时这里是 false，
     * 家长才能在「我的」里看到"网址拦截其实没生效"并点进去补授权。
     */
    val webGuardActive: Boolean = false,
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
    val guardInfraWarning: Boolean = false,
    /**
     * 无障碍**已开启但没有连上**（开关还在、服务对象不在）。
     *
     * 与 [guardInfraWarning] 分开的原因：这不需要家长重新授权，等系统重绑或重启服务即可，
     * 文案若沿用"未开启"，家长会去做一次无意义的设置操作。
     */
    val guardInfraDetached: Boolean = false,
    /** 授权目录的实时汇总（总数 / 缺失项 / 无状态接口项），目录驱动而非写死文案 */
    val authorizationSummary: AuthorizationSummary = AuthorizationSummary()
)

/**
 * 「设备实时状态」的**单点定义**。
 *
 * 这些读数都能在系统设置页里被家长随时改动，却不是任何 Flow 的发射源，
 * 因此 combine 首帧与 ON_RESUME 的 refresh() 必须都覆盖它们。
 * 旧实现在两处各手写一份字段列表，refresh 漏了闸门横幅两项与 DO 能力矩阵，
 * 导致「明明开了还显示未开 / 警告一直不消失」。现在由 [applyTo] 统一收口：
 * 新增实时字段只需改这一处，两条路径自然同步（JVM 单测锁死字段集合）。
 *
 * 刻意做成 internal + 全默认值：单测可直接构造，无需 Android 上下文或 Hilt。
 */
internal data class RealtimeState(
    val adminState: AdminState = AdminState.NONE,
    val deviceAdminActive: Boolean = false,
    val isDeviceOwner: Boolean = false,
    val usageStatsGranted: Boolean = false,
    val batteryOptimizationWhitelisted: Boolean = false,
    val webGuardActive: Boolean = false,
    val authorizationSummary: AuthorizationSummary = AuthorizationSummary(),
    val canHideApps: Boolean = false,
    val canSuspendApps: Boolean = false,
    val ownerCapabilities: List<CapabilityStatus> = emptyList(),
    /** 已启用操作密码但无障碍未开 → 横幅「操作密码闸门未完全生效」 */
    val guardInfraWarning: Boolean = false,
    /** 无障碍已开但服务没连上 → 横幅「操作闸门正在重新连接」 */
    val guardInfraDetached: Boolean = false,
    /** 本机存储占用、电量：同样只在系统侧变化、不是任何 Flow 的发射源（R29 补收口）。 */
    val storage: String = "未知",
    val battery: String = "未知"
) {
    /** 覆盖实时字段，保留 state 上的非实时字段（昵称 / 设备名 / 名单统计等）。 */
    fun applyTo(state: ProfileUiState): ProfileUiState = state.copy(
        adminState = adminState,
        deviceAdminActive = deviceAdminActive,
        isDeviceOwner = isDeviceOwner,
        usageStatsGranted = usageStatsGranted,
        batteryOptimizationWhitelisted = batteryOptimizationWhitelisted,
        webGuardActive = webGuardActive,
        authorizationSummary = authorizationSummary,
        canHideApps = canHideApps,
        canSuspendApps = canSuspendApps,
        ownerCapabilities = ownerCapabilities,
        guardInfraWarning = guardInfraWarning,
        guardInfraDetached = guardInfraDetached,
        storage = storage,
        battery = battery
    )
}

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
