package com.padguard.child.hidden

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.padguard.child.ui.lock.LockScreenPresenter
import com.padguard.core.common.Logger
import com.padguard.core.engine.admin.DeviceAdminBridge
import com.padguard.core.engine.admin.OpResult
import com.padguard.core.engine.lock.LockController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 家长密码验证通过后的「退出并卸载」。
 *
 * ## 为什么必须走完整释放（2026-11-18 真机 bug 复盘）
 * 旧实现只做两件事：`setUninstallBlocked(self, false)` + `removeActiveAdmin()`。
 * 在 **Device Owner** 设备上这两步都不足以卸载：
 * 1. DO 身份本身禁止系统卸载自身应用，`removeActiveAdmin()` 对 DO 无效（还会抛 SecurityException
 *    被 runCatching 吞掉）——必须 `clearDeviceOwnerApp()`；
 * 2. `SecurityEnforcer` / `AppPolicyEnforcer` / `SelfProtectionModule` 还压着
 *    `DISALLOW_UNINSTALL_APPS`、`DISALLOW_APPS_CONTROL` 等用户限制，不清掉照样装不卸；
 * 3. Kiosk 常驻桌面 / LockTask 白名单 / 锁屏浮层不撤，卸载确认页出不来或被盖住。
 *
 * 因此这里直接复用解绑时的权威释放入口 [DeviceAdminBridge.releaseControl]：
 * 清桌面 → 清 LockTask → 放出被挂起/隐藏的应用 → 还原相机截屏与用户限制 →
 * 解除自身卸载封锁 → 最后 relinquish DO/管理员。释放完立即重探权限模式，
 * 避免策略主循环在「权限已摘除、缓存还是 DO」的几秒窗口里把封锁重新压回去。
 *
 * ## 与无障碍闸门的分工
 * 密码验证时由 [FloatingActionActivity] 打开 10 分钟设备操作窗口
 * （`GuardPasswordManager.unlock()`），否则 `OperationGuardAccessibilityService` 会把
 * 系统卸载确认页当成 REASON_INSTALLER 顶回 —— 表现为"密码输对了却毫无反应"。
 * 本类只负责把系统层的锁全部解开并把卸载页拉起来。
 *
 * ## 已知边界（如实标注）
 * - 卸载最后一步必须由家长在**系统界面**确认；本应用不能静默卸载自身（Android 安全模型）。
 * - 家长在系统弹窗点"取消"时应用仍在设备上，但 DO 与限制已被释放；下次策略刷新会重新武装。
 */
@Singleton
class ExitController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val admin: DeviceAdminBridge,
    private val lockController: LockController,
    private val lockScreenPresenter: LockScreenPresenter
) {
    /**
     * 释放管控并拉起系统卸载页。调用前必须已通过家长密码验证。
     *
     * DPM / PMS 调用放在后台线程；锁屏收口与 startActivity 回到主线程。
     */
    suspend fun requestUninstall(activity: Activity) {
        // 1) 先撤锁屏：否则系统卸载确认页会被我们的全屏锁屏盖住，家长根本点不到"确定"。
        runCatching { lockController.releaseAll() }
            .onFailure { Logger.e(TAG, it) { "release lock before uninstall failed" } }
        withContext(Dispatchers.Main) {
            runCatching { lockScreenPresenter.dismiss() }
                .onFailure { Logger.e(TAG, it) { "dismiss lock screen before uninstall failed" } }
        }

        // 2) 完整释放所有系统级管控（含 DO / 用户限制 / 卸载封锁 / Kiosk 桌面）。
        //    旧版这里的"只解封 + removeActiveAdmin"是卸载失效的直接原因，不得回退。
        val ops = withContext(Dispatchers.Default) {
            val released = runCatching { admin.releaseControl() }
                .onFailure { Logger.e(TAG, it) { "releaseControl failed before uninstall" } }
                .getOrDefault(emptyList())
            // 立刻重探：relinquish 后 _controlMode 仍是 DO 的话，15s 内的策略自检会再压一次封锁。
            runCatching { admin.refreshControlMode() }
            released
        }
        val failed = ops.filter { it.second is OpResult.Failed }.map { it.first }
        Logger.i(TAG) {
            "uninstall prepared: ${ops.size} release ops, failed=$failed, mode=${admin.controlMode.value}"
        }

        // 3) 拉起系统卸载确认页。DO 已清、卸载封锁已解、无障碍窗口已开，
        //    这一步才真正能走到系统"确定卸载"。
        val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        withContext(Dispatchers.Main) {
            runCatching {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    activity.startActivity(intent)
                } else {
                    Logger.w(TAG) { "activity gone before uninstall intent, aborted" }
                }
            }.onFailure { Logger.e(TAG, it) { "uninstall intent failed" } }
        }
    }

    companion object {
        private const val TAG = "ExitController"
    }
}
