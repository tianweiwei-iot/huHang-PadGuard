package com.padguard.child.permission

import android.content.Context
import android.content.Intent
import com.padguard.child.AppForeground
import com.padguard.child.MainActivity
import com.padguard.child.guard.AccessibilityCapabilityHealer
import com.padguard.core.common.Logger
import com.padguard.core.data.repository.AuthRepository
import com.padguard.core.engine.admin.DeviceAdminBridge
import com.padguard.core.engine.guard.GuardMasterSwitch
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 授权保持器（防回退自愈层）—— 实现用户诉求"授权不会自动丢失 / 写死"。
 *
 * ## 为什么需要它
 * 既有的一次性授权流水线（[com.padguard.child.consent.OneTimeConsentViewModel]）解决了
 * "装机一次拿全、使用中不再弹框"；但它只在**首次装机**时主动引导。
 * 授权完成之后，若孩子在系统设置里把某项关掉（或国产 ROM 切换某权限时强制重启 App 把
 * 无障碍/录屏授权清空），旧逻辑只在后台静默上报告警，**不会自动把家长带回重新授权** ——
 * 这正是用户感知到的"授权会丢、没写死"。
 *
 * ## Android 的硬边界（必须如实告知，不可替代）
 * AppOps（悬浮窗 / 使用情况访问 / 安装未知应用）与无障碍服务**连设备所有者都无权代为授予**，
 * 任何第三方 App 都无法把它们"不可逆地写死"——孩子或家长在系统设置里手动关掉后，
 * 应用既无权阻止、也无权静默改回（那本身属于越权 / 后门，是被明确禁止的）。
 * 因此"不会丢失"的正解不是"锁死不可改"，而是 **持久化 + 全量自检 + 自愈**：
 *   - 装机一次性拿全（既有流水线，满足"一次授权、使用中无需再点"）；
 *   - 本类在 开机 / 服务启动 / 周期 三个时机全量复检；
 *   - 发现某项被关掉：DO 设备静默补回能补的（运行时权限 + 无障碍），
 *     其余（使用情况访问 / 安装未知应用，平台不允许静默）以及所有非 DO 设备的缺失项，
 *     **自动把家长带回一次性授权引导流程重新勾选** —— 而不是只后台静默告警。
 *
 * ## 与既有 [com.padguard.child.guard.PermissionHealthChecker] 的关系
 * 既有检查只覆盖"无障碍 + 悬浮窗"两项且仅上报；本类扩展到**全部授权项**
 * （设备管理员 / 运行时权限 / 全部特殊权限），并把"非 DO 缺失"从"上报"升级为
 * "自动拉回引导流程"，是用户诉求"不会自动丢失"的真正落地。
 *
 * ## 不干扰正常使用
 * 拉回引导页只在**本 App 已在前台**（家长正看着）时进行；App 在后台时不强行抢前台，
 * 仅置位 [AuthRepository.reauthRequired]，待下一次打开 App（入口分流）即进入引导。
 * 既保证"丢失必被补回"，又避免后台周期性地把孩子正在用的平板弹到授权页。
 */
@Singleton
class AuthorizationKeeper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val admin: DeviceAdminBridge,
    private val permissionGranter: PermissionGranter,
    private val healer: AccessibilityCapabilityHealer,
    private val authRepository: AuthRepository,
    private val guardMasterSwitch: GuardMasterSwitch
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 全量复检并自愈 / 拉回。
     *
     * 幂等、低打扰：所有项都齐时只清掉"需重新授权"标记，不做任何动作；
     * 缺失时再分级处理（DO 静默补 / 非 DO 拉回引导）。
     */
    suspend fun reconcile() = withContext(Dispatchers.Default) {
        // 未绑定 / 管控暂停 / 从未走过装机引导：没有管控依据，不动作（避免误扰）
        if (!guardMasterSwitch.active.first()) return@withContext
        if (!authRepository.isBound.first()) return@withContext
        if (!authRepository.isConsentDone.first()) return@withContext

        admin.refreshControlMode()
        val hasOwner = admin.hasOwnerPrivileges()

        // ① DO 能静默补的：运行时权限（覆盖安装 / 系统清授予后必丢）+ 无障碍服务
        if (hasOwner) {
            runCatching { permissionGranter.ensureGranted() }
                .onFailure { Logger.w(TAG, it) { "re-grant runtime failed" } }
            if (admin.isDeviceOwner()) {
                runCatching { healer.ensureEnabled() }
                    .onFailure { Logger.w(TAG, it) { "re-enable a11y failed" } }
            }
        }

        // ② 计算仍缺失的关键项（DO 下 INSTALL_PACKAGES 已被 missing() 排除）
        val stillMissing = buildSet {
            if (!admin.isAdminActive() && !authRepository.isDeviceAdminPromptAck.first()) {
                add(KEY_DEVICE_ADMIN)
            }
            addAll(permissionGranter.missingRuntimePermissions())
            permissionGranter.missingSpecialPermissions().forEach { add(it.key) }
        }

        if (stillMissing.isEmpty()) {
            // 全齐：复位"需重新授权"标记（此前若因瞬时掉失置位过，这里清掉）
            authRepository.clearReauthRequired()
            return@withContext
        }

        // ③ 仍缺失：DO 下只剩平台不允许静默的项（使用情况访问 / 安装未知应用），
        //    非 DO 下包括全部特殊权限 —— 统统把家长拉回引导流程重新勾选。
        Logger.w(TAG) { "authorization dropped, items=${stillMissing}, hasOwner=$hasOwner" }
        authRepository.setReauthRequired()
        bringBackToConsent()
    }

    /**
     * 把授权页宿主拉回前台，让家长重新勾选缺失项。
     *
     * 复用与 [com.padguard.child.consent.ConsentReturnPresenter] 相同的
     * "落在本 App 任务栈 → 命中 Android 10+ 后台启动豁免"策略；
     * 仅当本 App 已在前台时才真正拉起，避免后台周期任务把孩子正在用的平板弹到授权页。
     */
    private fun bringBackToConsent() {
        if (!AppForeground.isForeground) return // 后台：只置位标记，下次打开 App 由入口分流接管
        val last = prefs.getLong(KEY_LAST_BRING_BACK, 0L)
        val now = System.currentTimeMillis()
        if (now - last < BRING_BACK_COOLDOWN_MS) return // 去抖：避免短期内反复抢前台
        prefs.edit().putLong(KEY_LAST_BRING_BACK, now).apply()
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                }
            )
        }.onFailure { Logger.w(TAG) { "bring back to consent failed: ${it.message}" } }
    }

    companion object {
        private const val TAG = "AuthorizationKeeper"
        private const val PREFS_NAME = "padguard_auth_keeper"
        private const val KEY_LAST_BRING_BACK = "last_bring_back_ms"
        private const val KEY_DEVICE_ADMIN = "deviceAdmin"

        /** 拉回前台去抖窗口：15 分钟 */
        private const val BRING_BACK_COOLDOWN_MS = 15 * 60 * 1000L
    }
}
