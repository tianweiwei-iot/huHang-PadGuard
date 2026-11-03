package com.padguard.child.guard

import android.content.Context
import com.padguard.child.di.ApplicationScope
import com.padguard.core.common.Logger
import com.padguard.core.data.model.RiskLevel
import com.padguard.core.data.model.RiskType
import com.padguard.core.data.model.policy.ControlMode
import com.padguard.core.data.repository.AuthRepository
import com.padguard.core.data.repository.LogRepository
import com.padguard.core.engine.admin.Capability
import com.padguard.core.engine.admin.DeviceAdminBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 卸载防御聚合模块（问题二·非 DO 设备加固）。
 *
 * 分层防御中 L1 的全部「活性校验 / 系统级封死 / 密码闸门告警」收口于此，单一职责：
 * - [verifyLiveness]：设备管理员 / DO 身份被意外摘除时，立刻上报高危告警（治「管控悄然失效」）；
 * - [applyUninstallBlockIfSupported]：持有 DO/PO 时系统级封死自身卸载（治本）；
 * - [reportUninstallGateMissing]：家长要求「卸载需密码」但本地尚未设密码时，升级为高危告警
 *   （治「设了密码却没生效」的告警盲区）；
 * - [checkShield]：**新增，本轮事故的直接补丁** —— 把"卸载到底需不需要密码"算成
 *   [UninstallShield]，三层全空时立刻上报并把家长带回装机引导（治「失效不可见」）。
 *
 * 本模块**不触碰**任何既有「设置页拦截 / S11 软拦截」逻辑，仅做聚合与告警，
 * 避免引入新的绕过口；所有动作均为幂等或带节流，重复调用安全。
 */
@Singleton
class UninstallDefenseModule @Inject constructor(
    @ApplicationContext private val context: Context,
    private val admin: DeviceAdminBridge,
    private val appGuard: AppGuardPolicyStore,
    private val guardPassword: GuardPasswordManager,
    private val logRepository: LogRepository,
    private val authRepository: AuthRepository,
    @ApplicationScope private val scope: CoroutineScope
) {
    @Volatile private var lastMode: ControlMode? = null
    @Volatile private var lastGateAlertAt = 0L

    /**
     * 最近一次计算出的卸载防护状态。
     *
     * `null` = 本进程还没算过（刚开机/刚起服务），此时**不要**据此报警或改通知文案 ——
     * 那是"未知"，不是"没有防护"；把未知说成故障会让家长对告警脱敏。
     */
    @Volatile var shield: UninstallShield? = null
        private set

    @Volatile private var lastShieldAlertAt = 0L
    @Volatile private var lastBringBackAt = 0L
    @Volatile private var lastAttemptAlertAt = 0L

    /** 本进程是否至少成功把"禁止卸载自身"落到过系统（DO 重启后由基线重压，故仅作加速缓存）。 */
    private val uninstallBlockApplied = AtomicBoolean(false)

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
        if (r.isOk) uninstallBlockApplied.set(true)
        Logger.i(TAG) { "applyUninstallBlockIfSupported -> $r" }
        return r.isOk
    }

    // ==================== 「卸载需密码」就绪度 ====================

    /**
     * 计算当前卸载防护强度（只读，无副作用）。
     *
     * 每次调用都**重新探测**权限模式：设备管理员完全可能在设置里被停用，
     * 而"只用启动时算一次"正是本次事故里"日志说一切都好、实际什么也没有"的成因。
     */
    fun currentShield(): UninstallShield {
        val mode = admin.refreshControlMode()
        val hasOwner = mode == ControlMode.DEVICE_OWNER || mode == ControlMode.PROFILE_OWNER
        return UninstallShieldResolver.resolve(
            ownerPrivileges = hasOwner,
            uninstallBlockApplied = hasOwner &&
                (uninstallBlockApplied.get() || admin.can(Capability.BLOCK_UNINSTALL)),
            adminActive = admin.isAdminActive(),
            a11yAlive = GuardA11y.isAlive(context),
            passwordEnabled = guardPassword.isEnabledNow()
        )
    }

    /**
     * 周期自检：把"卸载需密码是否真的生效"变成可上报、可被家长看见的状态。
     *
     * ## 为什么必须做（事故复盘）
     * 现场那台平板上：DO 没有、设备管理员没激活、无障碍服务从头到尾没连上，
     * 三条通道全空，孩子长按图标一次就把管控删干净了 —— 而家长端、孩子端**没有任何一处**
     * 提示过"你的防护现在是 0"。本方法的职责就是让这种状态不可能再静默：
     * 1. 状态变化写日志（现场排查看 `UninstallDefense` 这一行即可，不用再猜）；
     * 2. 处于 [UninstallShield.needsParentAttention] 时上报告警（节流 6h，避免刷屏）；
     * 3. 同时置位 `reauthRequired`（节流 12h），家长下次打开管控台即被带回装机引导，
     *    且在那之前孩子端通知栏文案会显示"防护未就绪"（见 GuardService）。
     *
     * 幂等：可被任意周期循环反复调用，重复调用只更新状态与节流计时。
     */
    suspend fun checkShield(): UninstallShield {
        val now = System.currentTimeMillis()
        val current = currentShield()
        val previous = shield
        shield = current
        if (previous != current) {
            Logger.w(TAG) {
                "uninstall shield changed: $previous -> $current " +
                    "(parentView=${current.parentSummary})"
            }
        }
        if (!current.needsParentAttention) return current
        // 家长主动关掉了「卸载需密码」→ 防护弱是家长的明确选择，不该被当成故障打扰。
        // 但状态仍如实写入 [shield] 与日志，排查时看得见。
        if (!appGuard.uninstallNeedsPassword()) {
            Logger.i(TAG) { "uninstall shield=$current, parent disabled uninstall password, no alert" }
            return current
        }

        if (now - lastShieldAlertAt >= SHIELD_ALERT_INTERVAL_MS) {
            lastShieldAlertAt = now
            Logger.e(TAG) {
                "uninstall shield is NOT protecting the app: $current (${current.parentSummary})"
            }
            raise(
                RiskType.PERMISSION_REVOKED, RiskLevel.HIGH,
                mapOf(
                    "action" to "uninstallShieldMissing",
                    "shield" to current.name,
                    "note" to current.parentSummary,
                    "fix" to "请激活设备管理员（或部署为设备所有者）并开启无障碍服务、设置操作密码"
                )
            )
        }
        // 把家长带回装机引导：只在"必须补"的时候做，且 12h 一次，避免把家长困在授权页。
        // 这是"卸载需密码"从口号变成事实的唯一入口 —— 没有它，家长永远不会知道要补。
        if (now - lastBringBackAt >= BRING_BACK_INTERVAL_MS) {
            lastBringBackAt = now
            runCatching { authRepository.setReauthRequired() }
                .onFailure { Logger.w(TAG, it) { "failed to flag reauth for uninstall shield" } }
        }
        return current
    }

    /**
     * 拦截到一次卸载尝试后上报（节流 30s）。
     *
     * 走 `raise` 而不是普通流水：孩子尝试卸载是最需要家长立即介入的信号之一，
     * 只在行为日志里留痕等于没人看得见。复用既有 [RiskType.UNINSTALL_ATTEMPT]，
     * 用 `action` 区分是哪个入口被拦住的。
     */
    fun reportUninstallAttempt(entry: String) {
        val now = System.currentTimeMillis()
        if (now - lastAttemptAlertAt < ATTEMPT_ALERT_INTERVAL_MS) return
        lastAttemptAlertAt = now
        raise(
            RiskType.UNINSTALL_ATTEMPT, RiskLevel.HIGH,
            mapOf(
                "action" to "uninstallEntryBlocked",
                "entry" to entry,
                "note" to "已拦截一次安装/卸载入口（$entry），未验证家长密码前无法继续"
            )
        )
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
        /** 「卸载防护整体缺失」告警最小间隔：6h（服务端不做去重，太密只会让家长对告警脱敏） */
        private const val SHIELD_ALERT_INTERVAL_MS = 6 * 60 * 60 * 1000L
        /** 把家长带回装机引导的最小间隔：12h（太频繁会把家长困在授权页） */
        private const val BRING_BACK_INTERVAL_MS = 12 * 60 * 60 * 1000L
        /** 卸载尝试告警最小间隔：30s（孩子连点也只留一条） */
        private const val ATTEMPT_ALERT_INTERVAL_MS = 30 * 1000L
    }
}
