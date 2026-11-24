package com.padguard.child.guard

import android.content.Context
import android.os.SystemClock
import com.padguard.child.di.ApplicationScope
import com.padguard.core.common.Logger
import com.padguard.core.data.model.RiskLevel
import com.padguard.core.data.model.RiskType
import com.padguard.core.data.model.policy.ControlMode
import com.padguard.core.data.repository.AuthRepository
import com.padguard.core.data.repository.ReauthSource
import com.padguard.core.data.repository.LogRepository
import com.padguard.core.engine.admin.Capability
import com.padguard.core.engine.admin.DeviceAdminBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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

    /** 控制模式持久化（R-P1-6）：进程被杀重启后首 tick 仍可读到上次模式，避免漏报 DO 摘除。 */
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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

    /** [noticeShield] 的"连续判弱"计数：变强立即复位，变弱需连续两拍才改通知文案。 */
    private val weakShieldStreak = AtomicInteger(0)

    /**
     * 管控权限活性自检。
     *
     * @return true 表示检测到权限较上次下降（如 DO/PO/管理员被摘除沦为 LEGACY），并已上报高危告警。
     * 首次调用仅记录基准，不告警（避免启动即误报）；仅在「由有权限 → 无权限」的下降沿告警，
     * 不会在稳定于 LEGACY 时反复刷屏。
     */
    suspend fun verifyLiveness(): Boolean {
        val mode = admin.refreshControlMode()
        // 内存无基线时从磁盘恢复上次持久化的控制模式，避免进程被杀重启后首 tick 因
        // lastMode==null 而漏报"DO/管理员被摘除"（R-P1-6）。
        val prev = lastMode ?: runCatching {
            prefs.getString(KEY_LAST_MODE, null)?.let { ControlMode.valueOf(it) }
        }.getOrNull()
        lastMode = mode
        // 只在模式真正变化时同步落盘：这是「DO/管理员被摘除」的下降沿基线，
        // 旧写法每 tick 无脑 apply()，进程随即被杀会丢 → 重启后拿旧基线重复告警。
        runCatching {
            if (prefs.getString(KEY_LAST_MODE, null) != mode.name) {
                prefs.edit().putString(KEY_LAST_MODE, mode.name).commit()
            }
        }
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
     * 供**常驻通知**使用的实时防护判定（带单向迟滞），只读、无告警、无上报。
     *
     * ## 为什么通知不能读 [shield]
     * [shield] 由 [checkShield] 每 10 分钟才更新一次。开机 / 守护进程重建的第一拍经常撞上
     * 「服务端策略还没下发（操作密码未落盘）＋ 无障碍服务尚未重绑」的瞬时窗口，那一拍算出
     * `NONE` 后就被钉在通知栏整整 10 分钟 —— 家长明明已经把装机授权做完了，通知还在喊
     * "卸载防护未开启"。告警/上报继续走 10 分钟周期，但**通知必须读实时状态**：
     * 权限一补齐，下一拍（≤15s）就恢复"守护中"。
     *
     * ## 为什么要单向迟滞
     * `refreshControlMode()` 在管理员接收器尚未就绪 / DPM 瞬时查询失败时会偶发回落
     * `LEGACY`（见《权限撤销误报与锁死复发》根因 2）。若一判弱就改通知，通知会随主循环
     * 每 15 秒闪一次"未开启"。因此：**变强立即生效，变弱需连续两拍确认**。
     *
     * @return null = 尚未确认（保持上一份正常文案），绝不把"未知"说成"故障"。
     */
    fun noticeShield(): UninstallShield? {
        val current = currentShield()
        if (!current.needsParentAttention) {
            weakShieldStreak.set(0)
            return current
        }
        return if (weakShieldStreak.incrementAndGet() >= NOTICE_CONFIRM_STREAK) current else null
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
     * 3. 同时按来源置位引导请求（节流 12h，仅 [UninstallShield.NONE]），家长下次打开
     *    管控台即被带回装机引导；孩子端通知栏文案另由 [noticeShield] 实时探测驱动
     *    （见 GuardService），**不再等本 10 分钟周期**刷新。
     *
     * 幂等：可被任意周期循环反复调用，重复调用只更新状态与节流计时。
     */
    suspend fun checkShield(): UninstallShield {
        val now = SystemClock.elapsedRealtime()
        val current = currentShield()
        val previous = shield
        shield = current
        if (previous != current) {
            Logger.w(TAG) {
                "uninstall shield changed: $previous -> $current " +
                    "(parentView=${current.parentSummary})"
            }
        }
        if (!current.needsParentAttention) {
            // 防护已恢复：只清本模块名下那条来源，不碰权限自检 / 家长手动补齐的请求（R36）。
            authRepository.clearReauthRequired(ReauthSource.UNINSTALL_SHIELD)
            return current
        }
        // 家长主动关掉了「卸载需密码」→ 防护弱是家长的明确选择，不该被当成故障打扰。
        // 但状态仍如实写入 [shield] 与日志，排查时看得见。
        if (!appGuard.uninstallNeedsPassword()) {
            Logger.i(TAG) { "uninstall shield=$current, parent disabled uninstall password, no alert" }
            authRepository.clearReauthRequired(ReauthSource.UNINSTALL_SHIELD)
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
        // 只把「孩子端能修」的状态带回装机引导。
        // SOFT_GATE_WITHOUT_PASSWORD = 无障碍服务已开、家长端还没下发操作密码；孩子端
        // 授权页根本没有"设置操作密码"这一步，拉回去也修不好，反而形成
        // 「授权完成 → 10 分钟后又被拉回 → 再授权」的循环（用户感知：授权了还提示）。
        // 该状态的告警照发（家长端可见），真正要做的动作在家长端「操作密码」卡片。
        if (current == UninstallShield.SOFT_GATE_WITHOUT_PASSWORD) {
            authRepository.clearReauthRequired(ReauthSource.UNINSTALL_SHIELD)
            return current
        }
        // 把家长带回装机引导：只在"必须补"的时候做，且 12h 一次，避免把家长困在授权页。
        // 这是"卸载需密码"从口号变成事实的唯一入口 —— 没有它，家长永远不会知道要补。
        if (now - lastBringBackAt >= BRING_BACK_INTERVAL_MS) {
            lastBringBackAt = now
            runCatching { authRepository.setReauthRequired(ReauthSource.UNINSTALL_SHIELD) }
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
        val now = SystemClock.elapsedRealtime()
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
        val now = SystemClock.elapsedRealtime()
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
        private const val PREFS_NAME = "padguard_uninstall_defense"
        private const val KEY_LAST_MODE = "last_control_mode"
        /** 卸载闸门缺失告警最小间隔：1h */
        private const val GATE_ALERT_INTERVAL_MS = 60 * 60 * 1000L
        /** 「卸载防护整体缺失」告警最小间隔：6h（服务端不做去重，太密只会让家长对告警脱敏） */
        private const val SHIELD_ALERT_INTERVAL_MS = 6 * 60 * 60 * 1000L
        /** 把家长带回装机引导的最小间隔：12h（太频繁会把家长困在授权页） */
        private const val BRING_BACK_INTERVAL_MS = 12 * 60 * 60 * 1000L
        /** 卸载尝试告警最小间隔：30s（孩子连点也只留一条） */
        private const val ATTEMPT_ALERT_INTERVAL_MS = 30 * 1000L
        /** 通知栏「判弱」确认拍数：2 拍（约 30s）过滤 DPM 瞬时抖动，变强不设延迟 */
        private const val NOTICE_CONFIRM_STREAK = 2
    }
}
