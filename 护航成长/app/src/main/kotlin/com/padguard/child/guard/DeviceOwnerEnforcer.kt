package com.padguard.child.guard

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.padguard.child.di.ApplicationScope
import com.padguard.core.common.Logger
import com.padguard.core.engine.admin.DeviceAdminBridge
import com.padguard.core.engine.admin.OpResult
import com.padguard.core.engine.lock.LockController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device Owner 治本路径（P2）—— 独立于三件套软管控（P0/P1），仅当本机持有 DO 时生效。
 *
 * ## 设计边界（必须独立、不得影响 P0/P1）
 * - 本类是 P2 的唯一载体：所有 DO 专属的"硬"管控都在这里，P0/P1（无障碍三件套）代码**零改动**。
 * - 闸门单一：[DeviceAdminBridge.isDeviceOwner] 为 false（绝大多数存量 C 端平板）时，本类**整体 no-op**，
 *   既不回收权限、也不进 Kiosk —— 行为与未引入 P2 完全一致。
 * - 因此"有条件的平板（能成为 DO 的）走 P2，其余走三件套"天然成立：同一份代码，按设备能力分流。
 *
 * ## 它补上三件套做不到的"真"两件事（计划 §7）
 * 1. **真回收应用权限**：进入受控态 → 对受控/被取消授权的应用强制
 *    [android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED]，
 *    相机/定位/麦克风等敏感权限被系统级拒绝，关无障碍也拿不回来（三件套只能事后顶回）；
 * 2. **真锁死 HOME（Kiosk）**：[kioskActive] 为真时，[com.padguard.child.KidHomeActivity] 据此
 *    [com.padguard.child.ui.lock.LockTaskSupport.start]，系统禁用手势/返回/最近任务，
 *    连"退出锁屏"都做不到 —— 直接把需求③④⑤从"软拦截"升级为"系统锁死"。
 *
 * 应用**真隐藏**已由 [com.padguard.core.engine.enforcer.AppPolicyEnforcer] 在 DO 下完成，
 * 本类不重复造轮子，避免双重隐藏；本类只负责权限回收 + Kiosk 钉死标志。
 *
 * ## 安全
 * - 只回收**受控/被取消授权**且**非系统关键、非系统应用**的权限，输入法/拨号器/系统 UI/自身包永不回收（防砖）；
 * - 记录已回收的 (pkg, perm) 对，解锁时**精确归还**（DEFAULT，不擅自重新授予），避免解除管控后应用异常；
 * - 回收失败（某 ROM 对特定权限返回 false）逐条 [OpResult] 收口，不抛异常、不影响其余应用。
 */
@Singleton
class DeviceOwnerEnforcer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val admin: DeviceAdminBridge,
    private val lockController: LockController,
    private val appGuardStore: AppGuardPolicyStore,
    @ApplicationScope private val scope: CoroutineScope
) {

    /** KidHomeActivity 读取此流决定是否在受控态进 Kiosk 真锁死 HOME。非 DO 恒为 false。 */
    private val _kioskActive = MutableStateFlow(false)
    val kioskActive: StateFlow<Boolean> = _kioskActive.asStateFlow()

    /** 本轮受控态已强制拒绝的 (包名, 权限) 对，解锁时据此精确归还，避免误伤未回收的应用。 */
    private val revokedPairs = mutableSetOf<Pair<String, String>>()

    init {
        // 独立订阅锁定态：进入锁定（时间到/远程/宵禁…）即回收权限 + 置 Kiosk 标志；解除即归还。
        // 与 P0/P1 的 LockScreenPresenter 同订阅 lockController.state，但各管各的、互不耦合。
        // StateFlow 对新订阅者回放当前值 —— 即使本类晚于锁定被实例化，也能自愈到正确状态。
        lockController.state
            .onEach { state -> onLockStateChanged(state.locked) }
            .launchIn(scope)
    }

    private fun onLockStateChanged(locked: Boolean) {
        val isDo = admin.isDeviceOwner()
        _kioskActive.value = isDo && locked
        if (!isDo) {
            // 非 DO 设备：P2 完全不参与，但需确保已无残留（例如曾 DO 后被摘除）。
            if (revokedPairs.isNotEmpty()) releasePermissions()
            return
        }
        if (locked) revokeControlledPermissions() else releasePermissions()
    }

    /**
     * 进入受控态：回收受控/被取消授权应用的敏感运行时权限（真回收）。
     * 仅作用于"本机已安装 + 受控/被取消授权 + 非系统关键 + 非系统应用"的应用，
     * 逐项 [DeviceAdminBridge.revokeRuntimePermission]。
     */
    private fun revokeControlledPermissions() {
        val decisions = appGuardStore.decisions() ?: run {
            Logger.w(TAG) { "revokeControlledPermissions: no policy snapshot, skip" }
            return
        }
        val pm = context.packageManager
        val installed = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
            }
        }.getOrDefault(emptyList())

        val revocable = selectRevocablePackages(
            installedPackages = installed.map { it.packageName },
            isControlled = { pkg -> decisions.isControlled(pkg) || decisions.isHiddenControlled(pkg) },
            isSystemCritical = appGuardStore::isSystemCritical,
            isSystemApp = { pkg -> installed.firstOrNull { it.packageName == pkg }
                ?.let { it.flags and ApplicationInfo.FLAG_SYSTEM != 0 } ?: false }
        )

        var revoked = 0
        for (pkg in revocable) {
            for (perm in DANGEROUS_PERMISSIONS) {
                if (revokedPairs.contains(pkg to perm)) continue
                when (val r = admin.revokeRuntimePermission(pkg, perm)) {
                    is OpResult.Ok -> {
                        revokedPairs.add(pkg to perm)
                        revoked++
                    }
                    is OpResult.Unsupported -> { /* 该权限/设备不支持，跳过 */ }
                    is OpResult.Failed -> Logger.w(TAG) { "revoke $perm on $pkg failed: ${r.reason}" }
                }
            }
        }
        Logger.i(TAG) {
            "DO controlled-state entered: revoked $revoked permission grants, ${revokedPairs.size} pairs tracked"
        }
    }

    /** 解除受控态：把本轮强制拒绝的权限精确归还到系统默认（DEFAULT），不擅自重新授予。 */
    private fun releasePermissions() {
        if (revokedPairs.isEmpty()) return
        val snapshot = revokedPairs.toSet()
        revokedPairs.clear()
        var restored = 0
        for ((pkg, perm) in snapshot) {
            when (val r = admin.restoreRuntimePermission(pkg, perm)) {
                is OpResult.Ok -> restored++
                is OpResult.Unsupported -> { /* 已无 DO（被摘除），无法归还，忽略 */ }
                is OpResult.Failed -> Logger.w(TAG) { "restore $perm on $pkg failed: ${r.reason}" }
            }
        }
        Logger.i(TAG) { "DO controlled-state exited: restored $restored permission grants" }
    }

    /** 当前是否应进入 Kiosk 真锁死（供 KidHomeActivity 调用）。 */
    fun isKioskActive(): Boolean = _kioskActive.value

    companion object {
        private const val TAG = "DeviceOwnerEnforcer"

        /**
         * 受控态要强制回收的敏感运行时权限（真回收清单）。
         * 选这些"隐私/安全敏感"且"被滥用即构成实质危害"的权限；
         * 通信/存储等较基础权限不在此列，避免解除管控后应用长期异常。
         */
        val DANGEROUS_PERMISSIONS = setOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.READ_CALL_LOG,
            android.Manifest.permission.READ_SMS,
            android.Manifest.permission.SEND_SMS
        )

        /**
         * 纯函数：从已安装包中筛出"受控且可安全回收权限"的包。
         * 排除系统关键（防砖）与系统应用（避免解除管控后系统组件异常），剩余受控/被取消授权的用户应用。
         * 抽出为纯函数是为了可单测，与 Android 框架解耦。
         */
        fun selectRevocablePackages(
            installedPackages: List<String>,
            isControlled: (String) -> Boolean,
            isSystemCritical: (String) -> Boolean,
            isSystemApp: (String) -> Boolean
        ): List<String> = installedPackages.filter { pkg ->
            !isSystemCritical(pkg) && !isSystemApp(pkg) && isControlled(pkg)
        }
    }
}
