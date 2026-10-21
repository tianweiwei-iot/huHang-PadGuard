package com.padguard.child.guard

import com.padguard.child.di.ApplicationScope
import com.padguard.core.common.Logger
import com.padguard.core.data.model.RiskLevel
import com.padguard.core.data.model.RiskType
import com.padguard.core.data.model.policy.ControlMode
import com.padguard.core.data.repository.AuthRepository
import com.padguard.core.data.repository.LogRepository
import com.padguard.core.engine.admin.Capability
import com.padguard.core.engine.admin.DeviceAdminBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 卸载防御聚合模块（问题二·非 DO 设备加固）。
 *
 * 分层防御中 L1 的全部「活性校验 / 系统级封死 / 密码闸门告警」收口于此，单一职责：
 * - [verifyLiveness]：设备管理员 / DO 身份被意外摘除时，立刻上报高危告警（治「管控悄然失效」）；
 * - [applyUninstallBlockIfSupported]：持有 DO/PO 时系统级封死自身卸载（治本）；
 * - [reportUninstallGateMissing]：家长要求「卸载需密码」但本地尚未设密码时，升级为高危告警
 *   （治「设了密码却没生效」的告警盲区）。
 *
 * 本模块**不触碰**任何既有「设置页拦截 / S11 软拦截」逻辑，仅做聚合与告警，
 * 避免引入新的绕过口；所有动作均为幂等或带节流，重复调用安全。
 */
@Singleton
class UninstallDefenseModule @Inject constructor(
    private val admin: DeviceAdminBridge,
    private val appGuard: AppGuardPolicyStore,
    private val logRepository: LogRepository,
    private val authRepository: AuthRepository,
    @ApplicationScope private val scope: CoroutineScope
) {
    @Volatile private var lastMode: ControlMode? = null
    @Volatile private var lastGateAlertAt = 0L

    /**
     * 管控权限活性自检。
     *
     * @return true 表示检测到权限较上次下降（如 DO/PO/管理员被摘除沦为 LEGACY），并已上报高危告警。
     * 首次调用仅记录基准，不告警（避免启动即误报）；仅在「由有权限 → 无权限」的下降沿告警，
     * 不会在稳定于 LEGACY 时反复刷屏。
     */
    suspend fun verifyLiveness(): Boolean {
        val mode = admin.refreshControlMode()
        val prev = lastMode
        lastMode = mode
        if (prev == null) return false
        // 权限被降级（LEGACY = 连设备管理员都不是）视为脱管，必须让家长立刻看到
        if (prev != ControlMode.LEGACY && mode == ControlMode.LEGACY) {
            raise(
                RiskType.PERMISSION_REVOKED, RiskLevel.HIGH,
                mapOf(
                    "action" to "controlModeDegraded",
                    "from" to prev.name,
                    "to" to mode.name,
                    "note" to "管控权限被摘除（设备管理员/DO 已失效），卸载防护大幅降级"
                )
            )
            return true
        }
        return false
    }

    /**
     * 持有 DO/PO 时系统级封死自身卸载；非 DO 下为 no-op（由无障碍软拦截兜底）。
     * 幂等：重复调用无副作用。
     */
    fun applyUninstallBlockIfSupported(): Boolean {
        if (!admin.can(Capability.BLOCK_UNINSTALL)) return false
        val r = admin.setUninstallBlocked(admin.selfPackageName, true)
        Logger.i(TAG) { "applyUninstallBlockIfSupported -> $r" }
        return r.isOk
    }

    /**
     * 家长已开启「卸载需密码」但孩子端尚未设置管理密码：升级为高危告警（节流 1h）。
     * 配合无障碍 S11 分支的「顶回」形成「拦截 + 告警」，而非静默放行——
     * 否则孩子走「先停用设备管理员」这条 Android 不允许拦截的路径即可卸载，密码形同虚设。
     */
    fun reportUninstallGateMissing() {
        val now = System.currentTimeMillis()
        if (now - lastGateAlertAt < GATE_ALERT_INTERVAL_MS) return
        lastGateAlertAt = now
        scope.launch {
            raise(
                RiskType.UNINSTALL_ATTEMPT, RiskLevel.HIGH,
                mapOf(
                    "action" to "uninstallGateMissing",
                    "note" to "家长已开启「卸载需密码」，但孩子端尚未设置管理密码，卸载入口仅靠软拦截顶回"
                )
            )
        }
    }

    private fun raise(type: RiskType, level: RiskLevel, detail: Map<String, String>) {
        scope.launch {
            runCatching {
                logRepository.raise(
                    type = type,
                    level = level,
                    detail = detail,
                    deviceId = authRepository.getDeviceId()
                )
            }.onFailure { Logger.w(TAG, it) { "failed to raise uninstall-defense alert" } }
        }
    }

    companion object {
        private const val TAG = "UninstallDefense"
        /** 卸载闸门缺失告警最小间隔：1h */
        private const val GATE_ALERT_INTERVAL_MS = 60 * 60 * 1000L
    }
}
