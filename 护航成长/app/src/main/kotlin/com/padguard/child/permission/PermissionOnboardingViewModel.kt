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

    enum class Phase { IDLE, RUNNING, DONE, ERROR, NEED_RUNTIME_PERMISSION, NEED_PROVISIONING }

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
     * **Device Owner 不再是前置条件**（上架合规要求：不索取与功能无关的最高权限）。
     * - DO/PO 设备：后台静默授予，用户零感知；
     * - 普通设备：走标准的系统运行时权限弹窗，授予后立即进入 App，管控能力完整。
     *
     * 预置成为设备所有者降级为**可选增强**（防卸载 / 加固基线），放在 [NEED_PROVISIONING]
     * 里由实施人员按需进入，绝不再阻塞装机流程。
     */
    fun start() {
        _error.value = null
        admin.refreshControlMode()
        if (admin.hasOwnerPrivileges()) runBackgroundGrant()
        else _phase.value = Phase.NEED_RUNTIME_PERMISSION
    }

    /** 普通设备需要家长在系统弹窗里确认的运行时权限（与 manifest 声明一一对应） */
    val runtimePermissions: List<String> get() = permissionGranter.requiredPermissions

    /** 权限展示名（供 UI 列表渲染） */
    fun labelOf(perm: String): String = permissionGranter.labelOf(perm)

    /** 可选的增强项入口：想启用防卸载 / 加固基线时才需要 */
    fun openProvisioning() {
        _phase.value = Phase.NEED_PROVISIONING
    }

    /**
     * 运行时权限弹窗结果。
     *
     * 定位类权限被拒**不阻断装机**：没有定位时电子围栏与实时定位自动停用，
     * 其余管控能力照常。这比"少一个权限就卡住不让用"更符合家长的实际诉求
     * ——也避免了"必须同意全部权限才能用"被应用市场判定为强制授权。
     */
    fun onRuntimePermissionResult(result: Map<String, Boolean>) {
        val denied = result.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            Logger.w(TAG) { "runtime permissions denied: $denied (entering degraded mode)" }
            _error.value = "已跳过：${denied.map { permissionGranter.labelOf(it) }.joinToString("、")}，相关功能将不可用"
        }
        viewModelScope.launch {
            runCatching { authRepository.savePermissionsDone() }
            _phase.value = Phase.DONE
        }
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
