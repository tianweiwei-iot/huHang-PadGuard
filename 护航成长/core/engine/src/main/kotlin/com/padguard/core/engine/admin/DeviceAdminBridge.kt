package com.padguard.core.engine.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import com.padguard.core.common.Logger
import com.padguard.core.data.model.policy.ControlMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DevicePolicyManager 能力桥接层。
 *
 * ## 为什么需要这一层
 * 被管控端最棘手的现实是：**同一份策略在不同权限模式下能落地的部分完全不同**。
 * 如果让各个 Enforcer 直接调 DPM，就会到处散落 `try { ... } catch (SecurityException)`，
 * 而且失败是静默的 —— 管控端以为策略下发成功，实际终端一条都没生效。
 *
 * 因此这里做三件事：
 * 1. **探测真实权限模式**（[controlMode]），不靠调用方猜；
 * 2. **能力查询**（[can]），Enforcer 先问"我能不能"，再决定是执行还是上报"降级不支持"；
 * 3. **统一异常收口**（[guarded]），任何 SecurityException / 厂商 ROM 的怪异行为
 *    都转成明确的失败记录，让"未生效"变成可见事实而不是黑洞。
 *
 * ## 权限模式与能力矩阵
 * | 能力 | DEVICE_OWNER | PROFILE_OWNER | DEVICE_ADMIN | LEGACY |
 * |---|---|---|---|---|
 * | 锁屏 / 密码策略 | ✅ | ✅ | ✅ | ❌ |
 * | 禁用相机 | ✅ | ✅ | ✅ | ❌ |
 * | 禁截屏 | ✅ | ✅ | ❌ | ❌ |
 * | 应用挂起 / 隐藏 | ✅ | ✅ | ❌ | ❌ |
 * | 阻止卸载自身 | ✅ | ✅ | 部分（靠 admin 激活态） | ❌ |
 * | 用户限制（WiFi/USB/开发者选项…） | ✅ | 部分（仅工作资料内） | ❌ | ❌ |
 * | 全局设置（自动对时/状态栏） | ✅ | ❌ | ❌ | ❌ |
 * | 静默安装 / 卸载 | ✅ | ✅ | ❌ | ❌ |
 * | 重启 / 恢复出厂 | ✅ | ❌ | ❌ | ❌ |
 * | LockTask（Kiosk） | ✅ | 需 affiliated | ❌ | ❌ |
 *
 * LEGACY 模式下几乎什么都做不了，只能靠无障碍服务做"事后拦截"，
 * 这也是为什么部署文档必须强调走 Device Owner。
 */
@Singleton
class DeviceAdminBridge @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val dpm: DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private val _controlMode = MutableStateFlow(ControlMode.LEGACY)
    val controlMode: StateFlow<ControlMode> = _controlMode.asStateFlow()

    /** 管理员组件。通过 PackageManager 反查，避免 engine 模块反向依赖 app 模块 */
    val adminComponent: ComponentName? by lazy { resolveAdminComponent() }

    init {
        refreshControlMode()
    }

    /**
     * 重新探测当前权限模式。
     *
     * 必须支持随时重探：Device Owner 可能被 `adb dpm remove-active-admin` 摘掉，
     * 设备管理器也可能被用户在设置里关闭。若只在启动时探测一次，
     * 权限被摘除后终端会继续"假装在管控"，这是最危险的失效模式。
     */
    fun refreshControlMode(): ControlMode {
        val pkg = context.packageName
        val mode = when {
            dpm.isDeviceOwnerApp(pkg) -> ControlMode.DEVICE_OWNER
            dpm.isProfileOwnerApp(pkg) -> ControlMode.PROFILE_OWNER
            adminComponent?.let { dpm.isAdminActive(it) } == true -> ControlMode.DEVICE_ADMIN
            else -> ControlMode.LEGACY
        }
        if (_controlMode.value != mode) {
            Logger.w(TAG) { "control mode changed: ${_controlMode.value} -> $mode" }
        }
        _controlMode.value = mode
        return mode
    }

    fun isDeviceOwner(): Boolean = _controlMode.value == ControlMode.DEVICE_OWNER

    fun isAdminActive(): Boolean = adminComponent?.let { dpm.isAdminActive(it) } == true

    /** 能力查询：Enforcer 据此决定"执行"还是"回执 UNSUPPORTED" */
    fun can(capability: Capability): Boolean {
        val mode = _controlMode.value
        return when (capability) {
            Capability.LOCK_NOW,
            Capability.DISABLE_CAMERA,
            Capability.PASSWORD_POLICY ->
                mode != ControlMode.LEGACY

            Capability.DISABLE_SCREEN_CAPTURE,
            Capability.SUSPEND_PACKAGES,
            Capability.HIDE_PACKAGES,
            Capability.BLOCK_UNINSTALL,
            Capability.SILENT_INSTALL,
            Capability.SILENT_UNINSTALL ->
                mode == ControlMode.DEVICE_OWNER || mode == ControlMode.PROFILE_OWNER

            Capability.USER_RESTRICTIONS ->
                mode == ControlMode.DEVICE_OWNER || mode == ControlMode.PROFILE_OWNER

            Capability.GLOBAL_SETTINGS,
            Capability.STATUS_BAR,
            Capability.REBOOT,
            Capability.WIPE,
            Capability.LOCK_TASK,
            Capability.KEEP_UNINSTALLABLE ->
                mode == ControlMode.DEVICE_OWNER
        }
    }

    // ==================== 基础动作 ====================

    fun lockNow(): OpResult = guarded("lockNow") {
        require(can(Capability.LOCK_NOW)) { "lockNow requires active device admin" }
        dpm.lockNow()
    }

    fun reboot(): OpResult = guarded("reboot") {
        val admin = requireAdmin()
        require(can(Capability.REBOOT)) { "reboot requires Device Owner" }
        dpm.reboot(admin)
    }

    /**
     * 恢复出厂。
     * 高危操作，engine 层只提供能力，是否执行由 [com.padguard.core.engine.command.CommandExecutor]
     * 依据指令类型与签名校验结果决定。
     */
    fun wipeData(): OpResult = guarded("wipeData") {
        require(can(Capability.WIPE)) { "wipeData requires Device Owner" }
        dpm.wipeData(0)
    }

    fun setCameraDisabled(disabled: Boolean): OpResult = guarded("setCameraDisabled=$disabled") {
        val admin = requireAdmin()
        dpm.setCameraDisabled(admin, disabled)
    }

    fun setScreenCaptureDisabled(disabled: Boolean): OpResult =
        guarded("setScreenCaptureDisabled=$disabled") {
            val admin = requireAdmin()
            require(can(Capability.DISABLE_SCREEN_CAPTURE)) { "requires DO/PO" }
            dpm.setScreenCaptureDisabled(admin, disabled)
        }

    fun setStatusBarDisabled(disabled: Boolean): OpResult = guarded("setStatusBarDisabled=$disabled") {
        val admin = requireAdmin()
        require(can(Capability.STATUS_BAR)) { "requires Device Owner" }
        dpm.setStatusBarDisabled(admin, disabled)
    }

    fun setMaximumTimeToLock(millis: Long): OpResult = guarded("setMaximumTimeToLock=$millis") {
        val admin = requireAdmin()
        dpm.setMaximumTimeToLock(admin, millis)
    }

    // ==================== 用户限制 ====================

    fun addUserRestriction(key: String): OpResult = guarded("addRestriction:$key") {
        val admin = requireAdmin()
        require(can(Capability.USER_RESTRICTIONS)) { "requires DO/PO" }
        dpm.addUserRestriction(admin, key)
    }

    fun clearUserRestriction(key: String): OpResult = guarded("clearRestriction:$key") {
        val admin = requireAdmin()
        require(can(Capability.USER_RESTRICTIONS)) { "requires DO/PO" }
        dpm.clearUserRestriction(admin, key)
    }

    /** 按开关状态增删限制，Enforcer 里最常用的形态 */
    fun setUserRestriction(key: String, restricted: Boolean): OpResult =
        if (restricted) addUserRestriction(key) else clearUserRestriction(key)

    fun hasUserRestriction(key: String): Boolean {
        val um = context.getSystemService(Context.USER_SERVICE) as? UserManager ?: return false
        return um.hasUserRestriction(key)
    }

    /**
     * 是否持有设备所有者 / 资料所有者特权。
     *
     * 运行时权限自动授予（`setPermissionGrantState`）与多数用户限制只有这两种身份才可用，
     * 普通 DEVICE_ADMIN 拿不到。集中判断避免各方法散落重复条件。
     */
    fun hasOwnerPrivileges(): Boolean =
        isDeviceOwner() || dpm.isProfileOwnerApp(context.packageName)

    // ==================== 权限自动授予（说明书 §7） ====================

    /**
     * 经 Device Owner / Profile Owner 特权把某个运行时权限直接置为「已授予」。
     *
     * 对应说明书 §5.3「同意并授权 → 自动获取全部权限」：孩子点击同意后，
     * 不必逐个弹系统授权框，由 DPC 一次性把全部所需权限到位。
     * 普通设备管理员（无 DO/PO）会返回 [OpResult.Unsupported]——这是预期的降级，
     * 上层据此在 UI 上引导走系统授权流程。
     */
    fun grantRuntimePermission(permission: String): OpResult = guarded("grant:$permission") {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            "setPermissionGrantState requires API 23+"
        }
        require(hasOwnerPrivileges()) { "grantRuntimePermission requires Device/Profile Owner" }
        val admin = requireAdmin()
        val granted = dpm.setPermissionGrantState(
            admin,
            context.packageName,
            permission,
            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
        )
        require(granted) { "setPermissionGrantState returned false for $permission" }
    }

    /**
     * 协议同意后应用的「常开加固基线」（说明书 §11 防绕过）。
     *
     * 这些限制一旦开启未成年人模式就不应被解除，仅 Device Owner 可设。
     * 逐项返回成功/失败，便于上层汇总成「哪些加固未生效」上报服务端，
     * 避免"看起来加固了实际没生效"的静默失效。
     */
    fun applyHardeningBaseline(): List<Pair<String, OpResult>> {
        val results = mutableListOf<Pair<String, OpResult>>()
        results += "usbDebug" to setUserRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES, true)
        results += "factoryReset" to setUserRestriction(UserManager.DISALLOW_FACTORY_RESET, true)
        results += "safeBoot" to setUserRestriction(UserManager.DISALLOW_SAFE_BOOT, true)
        results += "unknownSources" to setUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, true)
        results += "configDateTime" to setUserRestriction(UserManager.DISALLOW_CONFIG_DATE_TIME, true)

        // USB 文件传输关闭（防通过 USB 拷走数据），API 28+ 才有，且不接受 admin 参数
        val usb = runCatching {
            require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { "requires API 28+" }
            require(can(Capability.GLOBAL_SETTINGS)) { "requires Device Owner" }
            dpm.setUsbDataSignalingEnabled(false)
            OpResult.Ok
        }.getOrElse { e ->
            if (e is SecurityException) OpResult.Unsupported("usbDataSignaling", e.message.orEmpty())
            else OpResult.Failed("usbDataSignaling", e.message.orEmpty())
        }
        results += "usbDataSignaling" to usb

        return results
    }

    // ==================== 全局 / 安全设置 ====================

    /**
     * 自动对时开关。
     *
     * API 30 起 `setGlobalSetting(AUTO_TIME)` 与 `setAutoTimeRequired` 双双废弃，
     * 必须改用 `setAutoTimeEnabled` / `setAutoTimeZoneEnabled`。
     * 这个版本分叉如果漏了，在 Android 11+ 上会静默无效 ——
     * 而"自动对时没锁住"意味着改系统时间即可绕过所有时段管控，属于 P0 级漏洞。
     */
    @Suppress("DEPRECATION")
    fun setAutoTimeEnforced(enforced: Boolean): OpResult = guarded("setAutoTime=$enforced") {
        val admin = requireAdmin()
        require(can(Capability.GLOBAL_SETTINGS)) { "requires Device Owner" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            dpm.setAutoTimeEnabled(admin, enforced)
            dpm.setAutoTimeZoneEnabled(admin, enforced)
        } else {
            dpm.setAutoTimeRequired(admin, enforced)
            dpm.setGlobalSetting(admin, android.provider.Settings.Global.AUTO_TIME, if (enforced) "1" else "0")
            dpm.setGlobalSetting(admin, android.provider.Settings.Global.AUTO_TIME_ZONE, if (enforced) "1" else "0")
        }
        // 无论哪条分支，都再补一道用户限制：禁止用户手改日期时间
        if (enforced) {
            runCatching { dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_DATE_TIME) }
        } else {
            runCatching { dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_DATE_TIME) }
        }
    }

    fun setGlobalSetting(key: String, value: String): OpResult = guarded("globalSetting:$key=$value") {
        val admin = requireAdmin()
        require(can(Capability.GLOBAL_SETTINGS)) { "requires Device Owner" }
        dpm.setGlobalSetting(admin, key, value)
    }

    fun setSecureSetting(key: String, value: String): OpResult = guarded("secureSetting:$key=$value") {
        val admin = requireAdmin()
        require(can(Capability.GLOBAL_SETTINGS)) { "requires Device Owner" }
        dpm.setSecureSetting(admin, key, value)
    }

    /**
     * 写 `Settings.System` 表。
     *
     * 亮度、息屏时长这类字段在 System 表而非 Secure/Global 表，
     * 用 [setSecureSetting] 写会静默失败（不抛异常，值也不生效）—— 这类"看起来成功了"
     * 的坑最难排查，因此必须走 API 28 才有的 `setSystemSetting`，
     * 且它只接受系统白名单内的少数几个键。
     */
    fun setSystemSetting(key: String, value: String): OpResult = guarded("systemSetting:$key=$value") {
        val admin = requireAdmin()
        require(can(Capability.GLOBAL_SETTINGS)) { "requires Device Owner" }
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { "setSystemSetting requires Android 9+" }
        dpm.setSystemSetting(admin, key, value)
    }

    // ==================== 应用管控 ====================

    /**
     * 挂起应用：图标变灰、点击弹系统提示，但应用数据保留。
     * 比"隐藏"体验更好（孩子能看到应用还在，只是现在不可用），是黑名单的首选手段。
     */
    fun setPackagesSuspended(packages: List<String>, suspended: Boolean): SuspendResult {
        val admin = adminComponent
        if (admin == null || !can(Capability.SUSPEND_PACKAGES)) {
            return SuspendResult(emptyList(), packages, "requires DO/PO")
        }
        if (packages.isEmpty()) return SuspendResult(emptyList(), emptyList(), null)
        return try {
            // 返回值是**未能处理**的包名（未安装等），必须用它来区分"真挂起了"与"包不存在"
            val failed = dpm.setPackagesSuspended(admin, packages.toTypedArray(), suspended)?.toList().orEmpty()
            val ok = packages - failed.toSet()
            if (failed.isNotEmpty()) {
                Logger.w(TAG) { "setPackagesSuspended($suspended) failed for: $failed" }
            }
            SuspendResult(ok, failed, null)
        } catch (e: Exception) {
            Logger.e(TAG, e) { "setPackagesSuspended failed" }
            SuspendResult(emptyList(), packages, e.message)
        }
    }

    fun isPackageSuspended(packageName: String): Boolean {
        val admin = adminComponent ?: return false
        if (!can(Capability.SUSPEND_PACKAGES)) return false
        return runCatching { dpm.isPackageSuspended(admin, packageName) }.getOrDefault(false)
    }

    /** 隐藏应用：更彻底（图标消失、无法启动），用于必须完全屏蔽的系统应用 */
    fun setApplicationHidden(packageName: String, hidden: Boolean): OpResult =
        guarded("setApplicationHidden:$packageName=$hidden") {
            val admin = requireAdmin()
            require(can(Capability.HIDE_PACKAGES)) { "requires DO/PO" }
            val ok = dpm.setApplicationHidden(admin, packageName, hidden)
            require(ok) { "setApplicationHidden returned false (package missing?)" }
        }

    fun setUninstallBlocked(packageName: String, blocked: Boolean): OpResult =
        guarded("setUninstallBlocked:$packageName=$blocked") {
            val admin = requireAdmin()
            require(can(Capability.BLOCK_UNINSTALL)) { "requires DO/PO" }
            dpm.setUninstallBlocked(admin, packageName, blocked)
        }

    /**
     * 限定允许启用的无障碍服务。
     *
     * 这是防绕过里很容易被忽略的一环：第三方无障碍工具（自动点击器、按键精灵类应用）
     * 可以自动点掉我们的拦截弹窗、自动确认卸载对话框。只放行本应用 + 系统读屏服务后，
     * 这类工具即便安装了也无法启用。
     *
     * **必须保留系统读屏服务**（TalkBack 等），否则视障学生将无法使用设备 ——
     * 传空列表等于"只允许系统内置服务"，传 null 才是"不限制"。
     */
    fun setPermittedAccessibilityServices(packages: List<String>?): OpResult =
        guarded("setPermittedAccessibility=${packages?.size ?: "unlimited"}") {
            val admin = requireAdmin()
            require(can(Capability.USER_RESTRICTIONS)) { "requires DO/PO" }
            val ok = dpm.setPermittedAccessibilityServices(admin, packages)
            require(ok) { "system rejected accessibility whitelist (an active service is not in list)" }
        }

    /** 限定允许启用的输入法：防止用第三方输入法内置浏览器绕过上网管控 */
    fun setPermittedInputMethods(packages: List<String>?): OpResult =
        guarded("setPermittedInputMethods=${packages?.size ?: "unlimited"}") {
            val admin = requireAdmin()
            require(can(Capability.USER_RESTRICTIONS)) { "requires DO/PO" }
            val ok = dpm.setPermittedInputMethods(admin, packages)
            require(ok) { "system rejected input method whitelist (active IME not in list)" }
        }

    // ==================== Kiosk / LockTask ====================

    fun setLockTaskPackages(packages: List<String>): OpResult = guarded("setLockTaskPackages") {
        val admin = requireAdmin()
        require(can(Capability.LOCK_TASK)) { "requires Device Owner" }
        dpm.setLockTaskPackages(admin, packages.toTypedArray())
    }

    fun isLockTaskPermitted(packageName: String): Boolean =
        runCatching { dpm.isLockTaskPermitted(packageName) }.getOrDefault(false)

    /**
     * LockTask 期间放开哪些系统 UI 特性。
     *
     * 保留 HOME 与 OVERVIEW 是**故意的取舍**：完全锁死会让设备在异常情况下变成砖头，
     * 家长也无法取回控制权。这里只锁通知、状态栏与键盘快捷键，
     * 真正的"不能退出"由白名单 + 本应用作为 Launcher 共同保证。
     */
    fun setLockTaskFeatures(features: Int): OpResult = guarded("setLockTaskFeatures=$features") {
        val admin = requireAdmin()
        require(can(Capability.LOCK_TASK)) { "requires Device Owner" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dpm.setLockTaskFeatures(admin, features)
        }
    }

    /**
     * 把本应用设为**常驻默认桌面**。
     *
     * 为什么必须是 `addPersistentPreferredActivity` 而不是引导用户去设置里选：
     * 用户手选的默认桌面可以随时在设置里改回去，Kiosk 就被一步破解；
     * 而 DO 通过这个 API 写入的偏好项**用户无法清除**（设置里的"清除默认"对它无效），
     * 这是 Kiosk 模式真正锁得住的关键一环。
     *
     * 代价是：解除时必须显式调 [clearPersistentPreferredLauncher]，
     * 否则即使卸掉策略，设备也会一直回到本应用 —— 属于"变砖"级风险，务必成对使用。
     */
    fun setPersistentPreferredLauncher(component: ComponentName): OpResult =
        guarded("setPersistentLauncher:${component.className}") {
            val admin = requireAdmin()
            require(can(Capability.LOCK_TASK)) { "requires Device Owner" }
            val filter = android.content.IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            dpm.addPersistentPreferredActivity(admin, filter, component)
        }

    fun clearPersistentPreferredLauncher(packageName: String): OpResult =
        guarded("clearPersistentLauncher:$packageName") {
            val admin = requireAdmin()
            require(can(Capability.LOCK_TASK)) { "requires Device Owner" }
            dpm.clearPackagePersistentPreferredActivities(admin, packageName)
        }

    // ==================== 内部工具 ====================

    private fun requireAdmin(): ComponentName =
        adminComponent ?: error("device admin receiver not found in manifest")

    /**
     * 统一异常收口。
     *
     * 厂商 ROM（尤其国内定制系统）经常在标准 API 上抛非文档化的异常，
     * 或者返回成功但实际无效果。这里把所有异常收成 [OpResult.Failed]，
     * 由上层汇总成"哪些策略未生效"上报服务端 —— 让降级可见，而不是让管控静默失效。
     */
    private inline fun guarded(op: String, block: () -> Unit): OpResult = try {
        block()
        Logger.d(TAG) { "ok: $op" }
        OpResult.Ok
    } catch (e: SecurityException) {
        Logger.e(TAG, e) { "denied: $op" }
        OpResult.Failed(op, "SecurityException: ${e.message}")
    } catch (e: IllegalArgumentException) {
        Logger.w(TAG, e) { "unsupported: $op" }
        OpResult.Unsupported(op, e.message.orEmpty())
    } catch (e: IllegalStateException) {
        Logger.w(TAG, e) { "unsupported: $op" }
        OpResult.Unsupported(op, e.message.orEmpty())
    } catch (e: Exception) {
        Logger.e(TAG, e) { "failed: $op" }
        OpResult.Failed(op, e.message.orEmpty())
    }

    private fun resolveAdminComponent(): ComponentName? {
        val pkg = context.packageName
        // 优先取已激活的管理员，避免 manifest 里存在多个 receiver 时选错
        runCatching { dpm.activeAdmins }
            .getOrNull()
            ?.firstOrNull { it.packageName == pkg }
            ?.let { return it }

        // 未激活时从 manifest 反查声明了 DEVICE_ADMIN_ENABLED 的 receiver
        val intent = Intent(DeviceAdminReceiver.ACTION_DEVICE_ADMIN_ENABLED).setPackage(pkg)
        val receivers = runCatching {
            context.packageManager.queryBroadcastReceivers(intent, PackageManager.MATCH_ALL)
        }.getOrNull().orEmpty()

        return receivers.firstOrNull()?.activityInfo
            ?.let { ComponentName(it.packageName, it.name) }
            .also { if (it == null) Logger.e(TAG) { "no DeviceAdminReceiver declared!" } }
    }

    companion object {
        private const val TAG = "DeviceAdminBridge"
    }
}

/** 单条 DPM 操作的结果 */
sealed interface OpResult {
    data object Ok : OpResult

    /** 当前权限模式或系统版本不支持，属于**预期内**的降级，回执 UNSUPPORTED */
    data class Unsupported(val op: String, val reason: String) : OpResult

    /** 真正的失败（权限被拒、ROM 异常），需要上报 POLICY_APPLY_FAILED */
    data class Failed(val op: String, val reason: String) : OpResult

    val isOk: Boolean get() = this is Ok
}

/** 批量挂起结果：[failed] 里的包名未能挂起（未安装 / 系统保护） */
data class SuspendResult(
    val succeeded: List<String>,
    val failed: List<String>,
    val error: String?
)

/** DPM 能力枚举，与 [DeviceAdminBridge.can] 配合使用 */
enum class Capability {
    LOCK_NOW,
    PASSWORD_POLICY,
    DISABLE_CAMERA,
    DISABLE_SCREEN_CAPTURE,
    SUSPEND_PACKAGES,
    HIDE_PACKAGES,
    BLOCK_UNINSTALL,
    KEEP_UNINSTALLABLE,
    SILENT_INSTALL,
    SILENT_UNINSTALL,
    USER_RESTRICTIONS,
    GLOBAL_SETTINGS,
    STATUS_BAR,
    REBOOT,
    WIPE,
    LOCK_TASK
}
