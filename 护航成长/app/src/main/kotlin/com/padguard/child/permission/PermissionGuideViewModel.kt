package com.padguard.child.permission

import android.app.Application
import com.padguard.child.guard.GuardGate
import com.padguard.child.guard.GuardPasswordManager
import com.padguard.core.data.model.policy.ControlMode
import com.padguard.core.engine.admin.DeviceAdminBridge
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * App 内「权限补开」页的数据源。
 *
 * 装机的一次性授权走 [com.padguard.child.consent.OneTimeConsentViewModel]；
 * 这个 ViewModel 只服务于**装机之后**的场景：家长在主页发现某项没开，
 * 点进来单独补一下。它刻意保持很小——只读状态 + 提供跳转所需的意图，
 * 不承担任何流水线编排，避免两套装机流程各自演化。
 */
@HiltViewModel
class PermissionGuideViewModel @Inject constructor(
    private val app: Application,
    private val admin: DeviceAdminBridge,
    private val guardPassword: GuardPasswordManager,
    private val deviceAdminRequest: DeviceAdminRequest
) : androidx.lifecycle.ViewModel() {

    init {
        admin.refreshControlMode()
    }

    /** 当前管控身份 */
    val controlMode: StateFlow<ControlMode> = admin.controlMode

    /** 成为设备所有者（最高权限）的预置指引 */
    val provisioning = ProvisioningGuide(admin.adminComponent, app.packageName)

    /** 硬控闸门：未解锁则拉起密码框并放行原动作（返回 false） */
    fun ensureGuardUnlocked(): Boolean = GuardGate.ensureUnlocked(app, guardPassword)

    fun deviceAdminIntent(): android.content.Intent = deviceAdminRequest.intent(admin)
}
