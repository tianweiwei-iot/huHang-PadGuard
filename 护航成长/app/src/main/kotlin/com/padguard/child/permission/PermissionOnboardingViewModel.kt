package com.padguard.child.permission

import android.app.Application
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.child.receiver.PadGuardDeviceAdminReceiver
import com.padguard.core.common.Logger
import com.padguard.core.data.model.policy.ControlMode
import com.padguard.core.data.repository.AuthRepository
import com.padguard.core.engine.admin.DeviceAdminBridge
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 权限一键获取 ViewModel（初次打开 App 时一次性授予）。
 *
 * 设计要点（对应需求：一键获取最高权限 / 后台获取 / 前端只显示进度）：
 * 1. [start] 先探测真实权限模式（[DeviceAdminBridge.controlMode]）；
 * 2. 若已是 Device Owner / Profile Owner（即拿到"最高管理权限"），则 [runBackgroundGrant]
 *    在后台协程里静默跑完 [PermissionGranter.grantAllWithProgress]——全程无系统弹窗、无设置跳转，
 *    仅通过 [steps] 把每一步状态推给前端渲染进度条；
 * 3. 若尚未是设备所有者（普通已安装应用无法静默拿全权限），则进入 [Phase.NEED_PROVISIONING]，
 *    由 [ProvisioningGuide] 给出"成为设备所有者"的合法预置指引（这是拿到最高权限的唯一前置）。
 *
 * 完成后 [AuthRepository.savePermissionsDone] 持久化，[MainActivity.EntryRouter] 据此自动推进到登录态，
 * 故本页无需自行导航。
 */
@HiltViewModel
class PermissionOnboardingViewModel @Inject constructor(
    private val app: Application,
    private val permissionGranter: PermissionGranter,
    private val admin: DeviceAdminBridge,
    private val authRepository: AuthRepository
) : ViewModel() {

    enum class Phase { IDLE, RUNNING, DONE, ERROR, NEED_PROVISIONING }

    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _steps = MutableStateFlow<List<PermissionGranter.GrantStep>>(emptyList())
    val steps: StateFlow<List<PermissionGranter.GrantStep>> = _steps.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 当前权限模式，供 UI 展示（解释为何需要预置）。 */
    val controlMode: StateFlow<ControlMode> = admin.controlMode

    /** 成为设备所有者（最高权限）的预置指引。 */
    val provisioning = ProvisioningGuide(admin.adminComponent, app.packageName)

    /**
     * 一键开始。
     *
     * DO/PO 设备：直接进入后台静默授予；其余设备：进入预置引导（无最高权限则无法静默拿全权限）。
     */
    fun start() {
        _error.value = null
        admin.refreshControlMode()
        if (admin.hasOwnerPrivileges()) runBackgroundGrant()
        else _phase.value = Phase.NEED_PROVISIONING
    }

    /**
     * 预置完成后（设备已变为 DO）由用户点「重新检测」触发，直接进入后台授予。
     */
    fun retryProvisioning() {
        _error.value = null
        admin.refreshControlMode()
        if (admin.hasOwnerPrivileges()) runBackgroundGrant()
        else _error.value = "仍未获得设备所有者权限，请先按指引完成预置（adb 命令或扫码）。"
    }

    /**
     * 降级路径：仅激活设备管理员（非 DO，能力受限）。
     * 仅用于让用户快速进入 App；要真正"最高权限 + 后台静默拿全权限"仍需走 [ProvisioningGuide] 成为 DO。
     */
    fun deviceAdminIntent(): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin.adminComponent)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "激活后可启用锁屏、应用管控、防卸载等核心能力（最高权限需设备所有者）"
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * 显式逃生出口：当前非设备所有者、Runtime 权限无法静默授予时，仍允许以"能力受限"模式进入。
     * 会如实记录降级告警，避免"看起来授权了实际没生效"的静默失效。
     */
    fun forceContinue() {
        Logger.w(TAG) { "forceContinue without Device Owner: runtime permissions NOT silently granted, entering degraded mode" }
        viewModelScope.launch {
            runCatching { authRepository.savePermissionsDone() }
            _phase.value = Phase.DONE
        }
    }

    private fun runBackgroundGrant() {
        _phase.value = Phase.RUNNING
        _steps.value = permissionGranter.stepKeys.map { (key, label) ->
            PermissionGranter.GrantStep(key, label, PermissionGranter.GrantStep.State.PENDING)
        }
        viewModelScope.launch {
            try {
                permissionGranter.grantAllWithProgress { step ->
                    _steps.value = _steps.value.map { if (it.key == step.key) step else it }
                }
                authRepository.savePermissionsDone()
                _phase.value = Phase.DONE
            } catch (t: Throwable) {
                Logger.e(TAG, t) { "background grant failed" }
                _error.value = t.message ?: "授权失败"
                _phase.value = Phase.ERROR
            }
        }
    }

    companion object {
        private const val TAG = "PermOnboardingVM"
    }
}
