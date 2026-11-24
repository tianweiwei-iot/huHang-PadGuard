package com.padguard.child.guard

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.padguard.child.di.ApplicationScope
import com.padguard.core.common.Logger
import com.padguard.core.data.model.policy.ManagementMode
import com.padguard.core.data.repository.AuthRepository
import com.padguard.core.engine.admin.DeviceAdminBridge
import com.padguard.core.engine.admin.OpResult
import com.padguard.core.engine.guard.GuardMasterSwitch
import com.padguard.core.engine.lock.LockController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device Owner 治本路径（P2）—— 独立于三件套软管控（P0/P1），仅当本机持有 DO 时生效。
 *
 * ## 设计边界（必须独立、不得影响 P0/P1）
 * - 本类是 P2 的唯一载体：所有 DO 专属的"硬"管控都在这里，P0/P1（无障碍三件套）代码**零改动**。
 * - 闸门两道且都为真才生效：家长选择了硬管控（ManagementMode.HARD），且
 *   [DeviceAdminBridge.isDeviceOwner] 为 true。任一不满足，本类**整体 no-op** 并精确归还
 *   此前回收的权限——这就是"硬 / 软两档并行、互不影响"的落点：设备哪怕是 DO，
 *   家长选软管控时也绝不做系统级权限回收与 Kiosk 真锁死。
 * - 家长在「我的」页切换模式会实时触发重新评估，无需重启 App。
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
    private val guardMasterSwitch: GuardMasterSwitch,
    private val authRepository: AuthRepository,
    @ApplicationScope private val scope: CoroutineScope
) {

    /** KidHomeActivity 读取此流决定是否在受控态进 Kiosk 真锁死 HOME。非 DO 恒为 false。 */
    private val _kioskActive = MutableStateFlow(false)
    val kioskActive: StateFlow<Boolean> = _kioskActive.asStateFlow()

    /** 本轮受控态已强制拒绝的 (包名, 权限) 对，解锁时据此精确归还，避免误伤未回收的应用。 */
    private val revokedPairs = mutableSetOf<Pair<String, String>>()

    /**
     * 状态变更串行锁。
     *
     * `lockController.state` 与 `guardMasterSwitch.active` 两条 Flow 都在 `@ApplicationScope`
     * （`CoroutineScope(SupervisorJob() + Dispatchers.Default)`，多线程）上并发触发，
     * 都汇入 [onLockStateChanged]，而该方法会读写共享可变状态 [revokedPairs]
     * （recover 时 add、release 时 toSet+clear+遍历 restore）。
     * 两路协程若在不同线程同时进入，会出现：
     *   - `ConcurrentModificationException`（被协程吞掉，导致本次回收/归还被截断）；
     *   - 更隐蔽的：线程 A 释放了一半权限、线程 B 已 clear 并 restore 了那一半，
     *     A 随后 add 的权限不再被追踪 → 解锁后相机/定位/麦克风等**永久残留 DENIED**。
     * 用 Mutex 把整段决策 + 回收/归还收口到同一临界区，彻底消除竞态，串行重评估也保证最终一致。
     */
    private val transitionMutex = Mutex()

    /**
     * 家长是否选择了硬管控。未读到 / 未选择 / 软管控一律按 false 处理：
     * 宁可少做系统级强管控，也绝不在家长没选硬管控时越权回收权限。
     * 多协程并发读写（模式切换与锁定态两条流），故用 @Volatile 保证可见性。
     */
    @Volatile
    private var hardControlEnabled: Boolean = false

    init {
        // 家长切档时立即重新评估：切到软管控要归还已回收权限并撤掉 Kiosk。
        authRepository.managementMode
            .onEach { mode ->
                val enabled = mode == ManagementMode.HARD
                if (enabled != hardControlEnabled) {
                    hardControlEnabled = enabled
                    onLockStateChanged(lockController.state.value.locked)
                }
            }
            .launchIn(scope)

        // 独立订阅锁定态：进入锁定（时间到/远程/宵禁…）即回收权限 + 置 Kiosk 标志；解除即归还。
        // 与 P0/P1 的 LockScreenPresenter 同订阅 lockController.state，但各管各的、互不耦合。
        // StateFlow 对新订阅者回放当前值 —— 即使本类晚于锁定被实例化，也能自愈到正确状态。
        lockController.state
            .onEach { state -> onLockStateChanged(state.locked) }
            .launchIn(scope)
        // 订阅整机管控总开关：家长/操作员在控制端"暂停管控"时，即便当前处于锁定态，
        // 也立即归还已回收权限并解除 Kiosk；恢复管控时按当前锁定态重新评估。
        // 复用同一决策入口 onLockStateChanged —— active=false 时内部早返回并释放，
        // 不重复一套分支。
        guardMasterSwitch.active
            .onEach { onManagementActiveChanged(it) }
            .launchIn(scope)
    }

    /**
     * 整机管控总开关变化时的再评估。
     *
     * 复用 [onLockStateChanged] 单一决策入口：
     * - active=false：内部早返回，归还已回收权限并置 Kiosk=false（设备完全可用）；
     * - active=true：按当前锁定态重新进入/退出受控态。
     * StateFlow 回放首值会触发一次对账，可自愈"权限残留但开关已暂停"的异常态。
     */
    private suspend fun onManagementActiveChanged(active: Boolean) {
        if (active) {
            Logger.i(TAG) { "management re-activated, re-evaluating DO controlled-state" }
        } else {
            Logger.i(TAG) { "management paused, releasing DO controlled-state (permissions + Kiosk)" }
        }
        // 复用同一决策入口；锁在 onLockStateChanged 内部获取，这里不二次加锁（Mutex 不可重入）。
        onLockStateChanged(lockController.state.value.locked)
    }

    private suspend fun onLockStateChanged(locked: Boolean) {
        // 串行化：两路 Flow 在多线程 @ApplicationScope 上并发触发，共享可变状态 revokedPairs
        // 若无保护会出现并发修改（解锁后权限残留 DENIED）。整段收口到同一临界区。
        transitionMutex.withLock {
            // 整机管控暂停：无论锁定态如何，DO 都不回收权限、不进 Kiosk，设备完全可用。
            if (!guardMasterSwitch.active.value) {
                if (revokedPairs.isNotEmpty()) releasePermissions()
                _kioskActive.value = false
                return@withLock
            }
            // 两道闸门：家长选了硬管控 + 设备确实是 DO，才启用系统级增强。
            // 家长选软管控时即便设备是 DO 也整体 no-op，并归还此前回收的权限。
            val isDo = hardControlEnabled && admin.isDeviceOwner()
            _kioskActive.value = isDo && locked
            if (!isDo) {
                // 未启用硬管控 / 非 DO 设备：P2 完全不参与，但需确保已无残留
                // （曾切到软管控，或 DO 被摘除）。
                if (revokedPairs.isNotEmpty()) releasePermissions()
                return@withLock
            }
            if (locked) revokeControlledPermissions() else releasePermissions()
        }
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
