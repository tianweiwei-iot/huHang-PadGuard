package com.padguard.child.guard

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.padguard.core.common.Logger
import com.padguard.core.data.model.RiskLevel
import com.padguard.core.data.model.RiskType
import com.padguard.core.data.repository.AuthRepository
import com.padguard.core.data.repository.LogRepository
import com.padguard.core.transport.RemoteDataSource
import com.padguard.core.engine.admin.DeviceAdminBridge
import com.padguard.core.engine.admin.OpResult
import com.padguard.core.engine.guard.GuardMasterSwitch
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 权限掉失自检与自愈。
 *
 * ## 解决什么问题
 * 在系统设置里授予/切换某些特殊权限（如国产 ROM 的"后台弹出界面" / 悬浮窗）时，
 * 系统会**强制重启**本应用。重启后：
 *  - 无障碍服务绑定被解除（设置里"无障碍"项被系统自动关闭）；
 *  - MediaProjection 的"允许录屏"授权随进程死亡被清空。
 * 于是表现为"之前打开的无障碍、允许录屏等权限全部被取消"。
 * 若无障碍失效，设置/应用拦截层整体瘫痪；录屏授权丢失则看屏/录屏下发即失败。
 *
 * 本检查在 [com.padguard.child.service.GuardService] 每次启动时执行：
 *  - 无障碍：DO 设备静默重启用（[AccessibilityCapabilityHealer.ensureEnabled]）；
 *    非 DO 设备诚实上报告警并引导家长/操作员去设置重新启用；
 *  - 悬浮窗：掉了只告警引导（悬浮窗无法被代码静默授予，必须用户操作）。
 * 这样权限被系统静默撤销时，设备不再"悄无声息地管控残缺"，而是明明白白地提醒。
 */
@Singleton
class PermissionHealthChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val admin: DeviceAdminBridge,
    private val healer: AccessibilityCapabilityHealer,
    private val logRepository: LogRepository,
    private val authRepository: AuthRepository,
    private val guardMasterSwitch: GuardMasterSwitch,
    private val remote: RemoteDataSource
) {

    private val component = ComponentName(context, OperationGuardAccessibilityService::class.java)
    private val flat = component.flattenToString()
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    suspend fun check() = withContext(Dispatchers.Default) {
        if (!guardMasterSwitch.active.value) return@withContext // 暂停管控时本就不需要这些权限
        if (!authRepository.isBound.first()) return@withContext // 未绑定不检查（无管控依据）
        val a11yOk = checkAccessibility()
        val overlayOk = checkOverlay()
        // 运行期被系统自动回收的运行时危险权限（定位/相机/电话/存储等），在 DO 形态下无感自愈；
        // 非 DO 仅记录待恢复项由 UI 引导。与无障碍/悬浮窗自检互补，覆盖"权限被撤销"全部来源。
        healRuntimePermissions()
        // 自愈成功后闭环服务端告警：把"已恢复"的类别通知服务端关闭对应 ACTIVE 告警
        val recovered = computeRecovered(a11yOk, overlayOk)
        resolveIfNeeded(recovered)
    }

    private fun isA11yEnabled(): Boolean {
        val current = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return current.split(':').any { it.equals(flat, ignoreCase = true) }
    }

    private suspend fun checkAccessibility(): Boolean {
        if (isA11yEnabled()) {
            prefs.edit().putBoolean(KEY_A11Y_EVER, true).apply()
            return true
        }
        // 无障碍未启用：DO 优先静默重启用；非 DO 诚实告警引导重新授权
        val ever = prefs.getBoolean(KEY_A11Y_EVER, false)
        if (admin.isDeviceOwner()) {
            runCatching { healer.ensureEnabled() }
                .onFailure { Logger.w(TAG, it) { "DO a11y re-enable failed" } }
        }
        // DO 形态下若静默自愈已恢复启用，则不再上报告警，避免家长端出现"无障碍服务已关闭"误报
        val a11yNow = isA11yEnabled()
        return if (!needsA11yDroppedAlert(a11yNow, admin.isDeviceOwner())) {
            Logger.i(TAG) { "accessibility auto-recovered under Device Owner; alert suppressed" }
            true
        } else {
            raiseDroppedAlert("无障碍服务", ever, "accessibility")
            false
        }
    }

    private suspend fun checkOverlay(): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(context)
        if (granted) {
            prefs.edit().putBoolean(KEY_OVERLAY_EVER, true).apply()
            return true
        }
        val ever = prefs.getBoolean(KEY_OVERLAY_EVER, false)
        raiseDroppedAlert("悬浮窗(后台弹窗)权限", ever, "overlay")
        return false
    }

    /**
     * 运行期被系统自动回收的运行时危险权限自愈。
     *
     * ## 解决什么问题
     * [checkAccessibility]/[checkOverlay] 只覆盖无障碍与悬浮窗；而 Android 11+ 的"闲置应用权限自动撤销"、
     * 以及 OEM 省电/权限管家，还会静默回收**普通运行时危险权限**（定位、相机、电话、存储等）。
     * 这类回收发生在应用运行期、无需用户操作，会导致定位/录音/相册等功能静默失效，
     * 并在家长端表现为"权限被撤销"告警。
     *
     * ## 自愈策略
     * - **DO/PO 形态**：应用对自身权限拥有授予能力，可**无感重新授予**，彻底闭环"权限被撤销"，
     *   家长端不再反复告警、功能不再因缺权限失效；
     * - **非 DO 形态**：应用无权替自己授予，仅记录待恢复项（由「我的」页状态横幅引导家长重新授予）。
     *
     * 关键约束：自愈**不以任何方式改变锁定态**——无论权限是否恢复，锁屏与解锁链路都不依赖这些被回收的权限，
     * 因此不会出现"权限被收 → 设备解不开锁"的死锁。
     *
     * @return 本轮实际重新授予的权限数量
     */
    fun healRuntimePermissions(): Int {
        if (!guardMasterSwitch.active.value) return 0
        val missing = CRITICAL_RUNTIME_PERMISSIONS.filter {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return 0
        // 非 DO/PO：无权代为授予，仅记录待恢复项供 UI 引导
        if (!admin.hasOwnerPrivileges()) {
            Logger.w(TAG) { "runtime permission(s) missing but no DO, guide via UI: $missing" }
            return 0
        }
        var granted = 0
        for (perm in missing) {
            when (val r = admin.grantRuntimePermission(context.packageName, perm)) {
                is OpResult.Ok -> granted++
                is OpResult.Unsupported -> Logger.w(TAG) { "cannot auto-grant $perm (no DO)" }
                is OpResult.Failed -> Logger.w(TAG) { "auto-grant $perm failed: ${r.reason}" }
            }
        }
        if (granted > 0) {
            Logger.i(TAG) { "healed $granted revoked runtime permission(s) under Device Owner" }
        }
        return granted
    }

    /**
     * 运行期周期性复检：无障碍 / 悬浮窗被 ROM 静默回收后，DO 形态静默自愈，
     * 非 DO 诚实告警引导。与 [healRuntimePermissions] 周期对称，弥补"仅启动时自检"的盲区——
     * 进程长期存活期间若被系统解除无障碍绑定，能在下一周期自动拉回，无需等到下次重启。
     *
     * 自愈成功后一并闭环服务端告警：把"已恢复"的类别通知服务端关闭对应 ACTIVE 告警，
     * 避免家长端"修好了还在提示权限被撤销 / 无障碍已关闭"。
     */
    suspend fun recheckSpecialCapabilities() {
        if (!guardMasterSwitch.active.value) return
        if (!authRepository.isBound.first()) return
        val a11yOk = checkAccessibility()
        val overlayOk = checkOverlay()
        // 运行期权限由 GuardService 的 permHeal 节拍先行自愈，这里仅观测"是否已恢复"并闭环告警
        val recovered = computeRecovered(a11yOk, overlayOk)
        resolveIfNeeded(recovered)
    }

    /**
     * 计算本次应通知服务端关闭的告警类别（恢复即关闭闭环）。
     *
     * ## 边沿触发，避免每轮自检产生无意义流量
     * 维护一份"当前缺失能力集合"持久化到 SharedPreferences：
     * - 本次 `now` = 当前确为缺失的能力（a11y / overlay / runtime 任一项未满足）；
     * - 本次 `recovered` = `prev - now`（上一轮缺失、本轮已恢复的能力），仅这些触发关闭；
     * - 写回 `now` 作为下一轮的 `prev`。
     * 因此一次"掉失 → 恢复"只会触发一次关闭，不会在能力持续恢复态下反复上报。
     *
     * ## 关键安全约束
     * `GUARD_PERMISSION_DROPPED` 同时覆盖无障碍与悬浮窗两种掉失，故仅当两者都已恢复
     * （`a11yOk && overlayOk`）才关闭它，避免把"悬浮窗仍缺失"的有效告警一并抹掉。
     */
    private fun computeRecovered(a11yOk: Boolean, overlayOk: Boolean): Set<String> {
        val prev = readDropped()
        val now = mutableSetOf<String>()
        if (!a11yOk) now += "a11y"
        if (!overlayOk) now += "overlay"
        if (CRITICAL_RUNTIME_PERMISSIONS.any { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            now += "runtime"
        }
        val recovered = computeResolveCategories(a11yOk, overlayOk, prev, now)
        writeDropped(now)
        return recovered
    }

    private fun readDropped(): MutableSet<String> =
        (prefs.getStringSet(KEY_DROPPED, emptySet()) ?: emptySet()).toMutableSet()

    private fun writeDropped(set: Set<String>) {
        prefs.edit().putStringSet(KEY_DROPPED, set).apply()
    }

    /**
     * 把自愈恢复的类别通知服务端关闭对应 ACTIVE 告警（恢复即关闭闭环）。
     *
     * 仅在确有恢复时发起网络请求；集合为空直接跳过，避免每轮自检都产生无意义流量。
     * 调用失败仅记日志、不影响本地自愈结果——告警关闭是"锦上添花"，不应反噬主链路。
     */
    private suspend fun resolveIfNeeded(recovered: Set<String>) {
        if (recovered.isEmpty()) return
        if (!authRepository.isBound.first()) return
        runCatching { remote.resolveActiveAlerts(recovered.toList()) }
            .onFailure { Logger.w(TAG, it) { "failed to notify server to close recovered alerts: $recovered" } }
    }

    private suspend fun raiseDroppedAlert(name: String, ever: Boolean, key: String) {
        val now = System.currentTimeMillis()
        val last = prefs.getLong("alert_$key", 0L)
        if (now - last < ALERT_INTERVAL_MS) return // 去抖，避免进程反复重启刷同一条告警
        prefs.edit().putLong("alert_$key", now).apply()
        val detail = if (ever) {
            "权限被系统静默撤销（曾在设置里授予，切换其它权限触发应用重启后被清空）：$name。" +
                "请到系统设置重新授予，否则管控/看屏将失效。"
        } else {
            "$name 尚未授予。请在系统设置 → 无障碍 / 应用权限中授予护航成长相应权限，" +
                "否则管控/看屏将失效。"
        }
        runCatching {
            logRepository.raise(
                type = RiskType.GUARD_PERMISSION_DROPPED,
                level = RiskLevel.HIGH,
                detail = mapOf("permission" to key, "note" to detail),
                deviceId = authRepository.getDeviceId()
            )
        }.onFailure { Logger.w(TAG, it) { "failed to raise GUARD_PERMISSION_DROPPED" } }
    }

    companion object {
        private const val TAG = "PermissionHealth"
        private const val PREFS_NAME = "padguard_perm_health"
        private const val KEY_A11Y_EVER = "a11y_ever"
        private const val KEY_OVERLAY_EVER = "overlay_ever"
        /** 当前缺失能力集合（"a11y"/"overlay"/"runtime"），边沿触发恢复即关闭告警的依据 */
        private const val KEY_DROPPED = "dropped_caps"
        private const val ALERT_INTERVAL_MS = 6 * 60 * 60 * 1000L

        /**
         * 是否需上报告警（纯判定，便于单测）。DO 形态下若静默自愈后已恢复启用则不报，
         * 遏止家长端"无障碍服务已关闭"误报；非 DO 或自愈未成功则照常上报。
         */
        @JvmStatic
        internal fun needsA11yDroppedAlert(a11yEnabledAfterHeal: Boolean, isDeviceOwner: Boolean): Boolean =
            !(isDeviceOwner && a11yEnabledAfterHeal)

        /**
         * 纯判定：给定当前与历史缺失能力集合，算出应关闭的告警类别集合。
         * 抽成纯函数便于单测，不依赖 SharedPreferences / 设备能力。
         *
         * 边沿触发：`recoveredCaps = prevDropped - nowDropped`，只在对缺失能力"从掉失到恢复"的
         * 那一拍才关闭对应告警。`GUARD_PERMISSION_DROPPED` 同覆盖无障碍与悬浮窗，故仅当两者都恢复
         * （`a11yOk && overlayOk`）才关，避免误关"悬浮窗仍缺失"的有效告警。
         */
        @JvmStatic
        internal fun computeResolveCategories(
            a11yOk: Boolean,
            overlayOk: Boolean,
            prevDropped: Set<String>,
            nowDropped: Set<String>
        ): Set<String> {
            val recoveredCaps = prevDropped - nowDropped
            val set = mutableSetOf<String>()
            if ("a11y" in recoveredCaps) set += "ACCESSIBILITY_DISABLED"
            if (("a11y" in recoveredCaps || "overlay" in recoveredCaps) && a11yOk && overlayOk) {
                set += "GUARD_PERMISSION_DROPPED"
            }
            if ("runtime" in recoveredCaps) set += "PERMISSION_REVOKED"
            return set
        }

        /**
         * 应用声明且运行期可能被系统自动回收的关键危险权限。仅用于 DO 形态下的自愈再授予；
         * 与 AndroidManifest 中声明的危险权限保持一致。SYSTEM_ALERT_WINDOW / PACKAGE_USAGE_STATS
         * 等无法经 DPM 授予，故不在此列（由无障碍/悬浮窗自检与 UI 引导覆盖）。
         */
        private val CRITICAL_RUNTIME_PERMISSIONS = listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }
}
