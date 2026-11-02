package com.padguard.child.permission

import android.app.Application
import com.padguard.child.guard.GuardGate
import com.padguard.child.guard.GuardPasswordManager
import com.padguard.child.guard.WebGuardVpnController
import com.padguard.core.common.Logger
import com.padguard.core.data.model.policy.ControlMode
import com.padguard.core.data.repository.PolicyRepository
import com.padguard.core.engine.admin.DeviceAdminBridge
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
    private val deviceAdminRequest: DeviceAdminRequest,
    private val webGuardVpn: WebGuardVpnController,
    private val policyRepository: PolicyRepository
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

    // ==================== 上网防护（VPN）====================

    /** 系统是否已授予 VPN 权限（决定按钮文案与"已开启"标记）。 */
    fun isWebGuardPrepared(): Boolean = webGuardVpn.isPrepared()

    /** 拉起系统 VPN 确认框；已授权时返回 null。 */
    fun prepareVpnIntent(): android.content.Intent? = webGuardVpn.prepareIntent()

    /**
     * 授权回来后立刻按当前策略对账一次，免等下一轮自检（最长要几分钟）。
     *
     * 家长刚点完"允许"，如果界面上"已开启"却要等几分钟才真正开始过滤，
     * 会被当成"授权没生效"。这里主动拉一次，做到所见即所得。
     */
    fun syncWebGuardNow() {
        viewModelScope.launch {
            runCatching {
                val web = policyRepository.getPolicy().web
                val state = webGuardVpn.sync(web)
                Logger.i(TAG) { "web guard vpn synced after consent: $state" }
            }.onFailure { Logger.w(TAG, it) { "web guard vpn sync after consent failed" } }
        }
    }

    companion object {
        private const val TAG = "PermissionGuideVM"
    }
}
