package com.padguard.core.engine

import com.padguard.core.common.Logger
import com.padguard.core.common.TimeProvider
import com.padguard.core.data.model.Command
import com.padguard.core.data.model.CommandAck
import com.padguard.core.data.model.RiskLevel
import com.padguard.core.data.model.RiskType
import com.padguard.core.data.model.policy.AppListMode
import com.padguard.core.data.model.policy.PolicyPackage
import com.padguard.core.data.repository.AuthRepository
import com.padguard.core.data.repository.LogRepository
import com.padguard.core.data.repository.PolicyRepository
import com.padguard.core.engine.admin.DeviceAdminBridge
import com.padguard.core.engine.command.CommandExecutor
import com.padguard.core.engine.command.EngineEffect
import com.padguard.core.engine.enforcer.AppLimitEnforcer
import com.padguard.core.engine.enforcer.AppPolicyEnforcer
import com.padguard.core.engine.enforcer.EnforceReport
import com.padguard.core.engine.enforcer.EyeCareEnforcer
import com.padguard.core.engine.enforcer.EyeCareVerdict
import com.padguard.core.engine.enforcer.KioskEnforcer
import com.padguard.core.engine.enforcer.LimitReason
import com.padguard.core.engine.enforcer.LimitVerdict
import com.padguard.core.engine.enforcer.MinorModeEnforcer
import com.padguard.core.engine.enforcer.MinorModeVerdict
import com.padguard.core.engine.enforcer.MonitoringEnforcer
import com.padguard.core.engine.enforcer.MonitoringRuntime
import com.padguard.core.engine.enforcer.PeripheralEnforcer
import com.padguard.core.engine.enforcer.SecurityEnforcer
import com.padguard.core.engine.enforcer.SmartShutdownEnforcer
import com.padguard.core.engine.enforcer.SystemLockEnforcer
import com.padguard.core.engine.enforcer.WebEnforcer
import com.padguard.core.engine.enforcer.WebVerdict
import com.padguard.core.engine.guard.TamperDetector
import com.padguard.core.engine.guard.TamperFinding
import com.padguard.core.engine.lock.LockController
import com.padguard.core.engine.lock.LockDetail
import com.padguard.core.engine.lock.LockReason
import com.padguard.core.engine.lock.LockState
import com.padguard.core.engine.schedule.ScheduleEvaluator
import com.padguard.core.engine.schedule.ScheduleVerdict
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 策略执行引擎的统一入口（编排器）。
 *
 * app 层**只依赖这一个类**，不直接碰各个 Enforcer。这样做的收益很实际：
 * 前台服务里不会散落十几个 `xxxEnforcer.apply(...)`，
 * 也就不会出现"新增一个子策略后忘了在服务里加一行"的静默漏管控。
 *
 * ## 三条对外能力
 * | 能力 | 方法 | 调用方 |
 * |---|---|---|
 * | 全量落地策略 | [applyAll] / [applyCurrent] | 策略下发后、开机后、周期自检 |
 * | 周期采样与判定 | [tick] | 前台服务定时器（建议 15s） |
 * | 执行远程指令 | [execute] | MQTT / HTTPS 指令通道 |
 *
 * ## 关于 APP_LIMIT 为什么不锁全屏（重要设计取舍）
 * 单应用限额（某个游戏今天玩够了）只应该拦住**那个应用**，
 * 不该把整台平板锁死——孩子还要用它做作业。
 * 因此 [tick] 的返回值里，单应用超额通过 [EngineTick.blockedPackage] 交给 app 层弹拦截页，
 * **不写入** [LockController]；只有"每日总时长耗尽"才升级为全屏锁定。
 * 这一层区分在策略模型里没有对应字段（服务端没给 `lockOnExceed`），
 * 属于端上的产品判断，若后续服务端要可配，改这里一处即可。
 */
@Singleton
class PolicyEngine @Inject constructor(
    private val admin: DeviceAdminBridge,
    private val peripheralEnforcer: PeripheralEnforcer,
    private val systemLockEnforcer: SystemLockEnforcer,
    private val appPolicyEnforcer: AppPolicyEnforcer,
    private val appLimitEnforcer: AppLimitEnforcer,
    private val eyeCareEnforcer: EyeCareEnforcer,
    private val minorModeEnforcer: MinorModeEnforcer,
    private val webEnforcer: WebEnforcer,
    private val smartShutdownEnforcer: SmartShutdownEnforcer,
    private val kioskEnforcer: KioskEnforcer,
    private val securityEnforcer: SecurityEnforcer,
    private val monitoringEnforcer: MonitoringEnforcer,
    private val scheduleEvaluator: ScheduleEvaluator,
    private val tamperDetector: TamperDetector,
    private val lockController: LockController,
    private val commandExecutor: CommandExecutor,
    private val policyRepository: PolicyRepository,
    private val logRepository: LogRepository,
    private val authRepository: AuthRepository,
    private val timeProvider: TimeProvider
) {

    /** 需要平台能力的副作用（锁屏页、截屏、定位……），由 app 层消费 */
    val effects: SharedFlow<EngineEffect> = commandExecutor.effects

    /** 锁定态，锁屏页与状态页直接订阅 */
    val lockState: StateFlow<LockState> = lockController.state

    /**
     * 当前生效的管控模式（NORMAL / LEARNING / FOCUS），随策略包下发。
     *
     * PolicyPackage 携带 sceneMode 字段，引擎据此持有当前管控模式，
     * 供 UI / 后续模式化管控消费。
     */
    private val _sceneMode = MutableStateFlow("NORMAL")
    val sceneMode: StateFlow<String> = _sceneMode.asStateFlow()

    /**
     * 串行化 [applyAll]。
     *
     * 策略下发、开机自检、周期自检三条路径可能并发触发全量下发。
     * 若不串行化，两次 apply 交错执行会出现"A 清限制 → B 加限制 → A 再清"，
     * 最终状态取决于线程调度，现场表现为"策略偶发不生效"，极难复现。
     */
    private val applyMutex = Mutex()

    @Volatile
    private var lastAppliedVersion = NOT_APPLIED

    /** 当日已上报过的告警 key，防止同一原因每 15s 刷一条 */
    private val alertedKeys = mutableSetOf<String>()
    private var alertDayKey = ""

    // ==================== 全量策略落地 ====================

    /** 用本地缓存的当前策略执行一次全量下发 */
    suspend fun applyCurrent(force: Boolean = false): EnforceReport =
        applyAll(policyRepository.getPolicy(), force)

    /**
     * 全量下发。
     *
     * @param force true = 无条件重新下发（周期自检用，用于把被用户改回去的限制重新压上）；
     *              false = 版本未变则跳过（策略推送用，省掉几十次 binder 调用）
     */
    suspend fun applyAll(policy: PolicyPackage, force: Boolean = false): EnforceReport =
        applyMutex.withLock {
            if (!force && policy.version == lastAppliedVersion) {
                Logger.v(TAG) { "policy v${policy.version} already applied, skip" }
                return@withLock EnforceReport().apply { note("policy.skip=v${policy.version}") }
            }

            admin.refreshControlMode()

            // 先过生效窗口，再派生未成年人模式基线：
            // 顺序反了会把过期的分龄额度压到设备上（过期窗口本应回落不管控基线）。
            val effective = minorModeEnforcer.deriveBaseline(resolveEffectivePolicy(policy))
            val report = EnforceReport()

            // 下发顺序不是随意排的，见 ORDER 说明
            report.merge(securityEnforcer.apply(effective.security))
            report.merge(peripheralEnforcer.apply(effective.peripheral))
            report.merge(systemLockEnforcer.apply(effective.systemLock, effective.minorMode.enabled))
            report.merge(appPolicyEnforcer.apply(effective.app))
            report.merge(webEnforcer.apply(effective.web))
            report.merge(monitoringEnforcer.apply(effective.monitoring))
            // Kiosk 放最后：它会改默认桌面，前面任一步骤炸了都还能靠系统桌面救回来
            report.merge(kioskEnforcer.apply(effective.kiosk))

            if (policy.version != effective.version) {
                report.markUnsupported(
                    "policy.window",
                    "策略 v${policy.version} 不在生效窗口内，已回落到不管控基线（安全项保留）"
                )
            }

            lastAppliedVersion = policy.version
            //  把本次策略包携带的管控模式提升为当前生效模式，供 UI / 模式化管控消费。
            if (_sceneMode.value != policy.sceneMode) _sceneMode.value = policy.sceneMode

            if (report.hasFailure) {
                raiseOnce(
                    key = "applyFailed:v${policy.version}",
                    type = RiskType.POLICY_APPLY_FAILED,
                    level = RiskLevel.HIGH,
                    detail = mapOf(
                        "version" to policy.version.toString(),
                        "failed" to report.failed.joinToString("; ").take(MAX_DETAIL_CHARS)
                    )
                )
            }

            Logger.i(TAG) { "policy v${policy.version} applied: ${report.summary()}" }
            report
        }

    /**
     * 生效窗口裁决。
     *
     * `effectiveFrom` / `effectiveTo` 用于"这套策略只在某段时间有效"（如寒假模式）。
     * 窗口外**不是**什么都不做，而是回落到出厂默认策略（等于解除各类限制），
     * 否则过期策略会永久残留，家长在管控端看到"已过期"、设备上却还锁着。
     *
     * 唯一例外是安全项（防卸载 / 防强停）：这块沿用原策略。
     * 理由很直接——窗口过期是运营配置问题，不该导致管控端直接失去对设备的控制权；
     * 一旦被卸载，连"下发新策略"这条路都没了。
     */
    private fun resolveEffectivePolicy(policy: PolicyPackage): PolicyPackage {
        val now = timeProvider.now()
        val notStarted = policy.effectiveFrom > 0 && now < policy.effectiveFrom
        val expired = policy.effectiveTo > 0 && now > policy.effectiveTo
        if (!notStarted && !expired) return policy

        Logger.w(TAG) {
            "policy v${policy.version} out of window (from=${policy.effectiveFrom}, to=${policy.effectiveTo}, now=$now)"
        }
        return PolicyPackage.default().copy(
            deviceId = policy.deviceId,
            scene = policy.scene,
            security = policy.security,
            monitoring = policy.monitoring
        )
    }

    // ==================== 周期采样与判定 ====================

    /**
     * 周期采样一次，聚合出"现在该不该锁、该拦谁"。
     *
     * 由前台服务按 [MonitoringRuntime] 的心跳间隔（下限 15s）调用。
     * 全部计时基于单调时钟，改系统时间不会让额度回血。
     *
     * @param foregroundPackage 当前前台应用；null 表示取不到（无使用情况访问权限时会这样）
     * @param screenOn 屏幕是否点亮；息屏不计时
     * @param dailyLimitExpired 日总时长是否已由服务端权威会话判定为耗尽
     *        （true 时统一经 [LockReason.DAILY_LIMIT] 锁屏；本地采样累计不再参与日总时长判定，
     *         以换取三端（家长端 / 孩子端 / 服务端）时间严格一致）
     */
    suspend fun tick(
        foregroundPackage: String?,
        screenOn: Boolean,
        dailyLimitExpired: Boolean = false
    ): EngineTick {
        // 未成年人模式的分龄基线在这里派生一次，让下面的限额/护眼判定
        // 与 applyAll 走的是同一份口径。若各自读原始策略，会出现
        // "策略已下发但限额还按旧额度算"的错位。
        val policy = minorModeEnforcer.deriveBaseline(policyRepository.getPolicy())
        val autoReasons = mutableMapOf<LockReason, LockDetail>()

        // ---------- 1. 时段锁 ----------
        val schedule = scheduleEvaluator.evaluate(policy.schedule)
        if (schedule is ScheduleVerdict.Locked) {
            autoReasons[LockReason.SCHEDULE] = LockDetail(
                text = "当前处于禁用时段 ${schedule.rule.start}–${schedule.rule.end}",
                untilMillis = schedule.untilMillis
            )
        }

        // ---------- 1.5 未成年人模式夜间宵禁 ----------
        // 独立于时段锁判定：家长关闭「时段管控」不得解除合规强制项。
        val minorMode = minorModeEnforcer.evaluate(policy.minorMode, timezone = policy.schedule.timezone)
        if (minorMode is MinorModeVerdict.Curfew) {
            autoReasons[LockReason.CURFEW] = LockDetail(
                text = minorMode.text,
                untilMillis = minorMode.untilMillis
            )
        }

        // ---------- 1.6 定时锁机 ----------
        // 家长端「上网管控」页的独立开关；此前只有 UI 与存储，执行侧从未接线。
        val smartShutdownUntil = smartShutdownEnforcer.evaluate(
            policy.web, timezone = policy.schedule.timezone
        )
        if (smartShutdownUntil != null) {
            autoReasons[LockReason.SMART_SHUTDOWN] = LockDetail(
                text = "定时锁机时间已到，请休息",
                untilMillis = smartShutdownUntil
            )
        }

        // ---------- 2. 使用限额 ----------
        val limit = appLimitEnforcer.track(policy.appLimit, foregroundPackage, screenOn)
        var blockedPackage: String? = null
        var blockedReason = ""
        if (limit is LimitVerdict.Blocked) {
            // 家长已放行（临时解锁）的应用本轮不拦。
            // 孩子端最常见的申请入口是"应用拦截页"，家长同意的正是"再玩一会儿这一个应用"；
            // 若这里不看放行状态，拦截页会在下一个 15 秒采样点立刻重新盖上 ——
            // 家长端显示"已同意"，孩子端却毫无变化，家长只会以为功能坏了。
            val tempReleased = limit.packageName.isNotBlank() &&
                lockController.isTempUnlockActive(limit.packageName)
            if (limit.reason == LimitReason.DAILY_TOTAL) {
                // 日总时长锁定已改由服务端权威会话驱动（见 [dailyLimitExpired] 入参），
                // 本地采样累计不再参与 DAILY_TOTAL 判定，以换取三端时间严格一致。
                // 此处仅在极端兜底路径下收到该判定时记一条日志，不做任何锁定动作
                // （避免与下方会话驱动的 DAILY_LIMIT 重复/冲突）。
                Logger.v(TAG) { "local DAILY_TOTAL verdict ignored: server session is authoritative" }
            } else {
                blockedPackage = if (tempReleased) null else limit.packageName
            }
            if (tempReleased) {
                Logger.i(TAG) { "limit(${limit.reason}) skipped: ${limit.packageName} temp-unlocked by parent" }
            } else if (limit.reason != LimitReason.DAILY_TOTAL) {
                raiseOnce(
                    key = "limit:${limit.reason}:${limit.packageName}",
                    type = RiskType.TIME_LIMIT_EXCEEDED,
                    level = RiskLevel.NORMAL,
                    detail = mapOf(
                        "packageName" to limit.packageName,
                        "reason" to limit.reason.name,
                        "usedMinutes" to limit.usedMinutes.toString(),
                        "quotaMinutes" to limit.quotaMinutes.toString()
                    )
                )
            }
        }

        // ---------- 2.5 黑名单前台拦截兜底 ----------
        // 黑名单的主管控手段是 DO 的「隐藏应用」（图标消失、无法启动），
        // 但设备失去 Device Owner（重装/解绑后重新激活、ROM 升级等）后会退化为 LEGACY，
        // 隐藏/挂起都不可用 —— 如果这里不再拦截，孩子就能照常打开被管控的应用（实际故障）。
        // 因此在每个采样点检查前台包名：命中黑名单就复用单应用拦截页压回去，
        // 并上报 BLACKLIST_APP_LAUNCH 让家长知道孩子尝试过。
        if (foregroundPackage != null && blockedPackage == null) {
            val isBlacklisted = when (policy.app.mode) {
                AppListMode.BLACKLIST ->
                    foregroundPackage in policy.app.blacklist ||
                        policy.app.systemAppRules[foregroundPackage]?.uppercase() == "BLOCK"
                // 白名单模式不在此兜底：差集逻辑需要"全部可启动应用"参与，
                // 在采样点做会把桌面、输入法等一并拦死，风险远大于收益（白名单依赖 DO）。
                AppListMode.WHITELIST -> false
            }
            if (isBlacklisted && !lockController.isTempUnlockActive(foregroundPackage)) {
                blockedPackage = foregroundPackage
                blockedReason = "该应用已被家长管控，无法使用"
                raiseOnce(
                    key = "blacklist:$foregroundPackage",
                    type = RiskType.BLACKLIST_APP_LAUNCH,
                    level = RiskLevel.NORMAL,
                    detail = mapOf(
                        "packageName" to foregroundPackage,
                        "mode" to policy.app.mode.name
                    )
                )
            }
        }

        // ---------- 3. 护眼休息 ----------
        val eyeCare = eyeCareEnforcer.track(policy.eyeCare, screenOn)
        if (eyeCare is EyeCareVerdict.Resting && eyeCare.forceLock) {
            val minutes = (eyeCare.remainingSeconds + 59) / 60
            autoReasons[LockReason.EYE_CARE] = LockDetail(
                text = "护眼休息中，剩余约 $minutes 分钟",
                untilMillis = eyeCare.untilMillis
            )
        }

        // ---------- 3.5 日总时长（服务端权威会话驱动） ----------
        // 早期实现依据本地采样累计判定 DAILY_TOTAL，三端各自按本地时钟算，必然对不上。
        // 改为服务端锁定计时起点与额度，三端用同一 serverNow - startEpoch 派生剩余，
        // 到期未延时即经此处挂 DAILY_LIMIT，并经 syncAutoReasons 统一管理生命周期
        // （临时解锁期内会被 LockController 自动抑制，避免家长放行被立刻锁回）。
        // dailyLimitExpired 由 GuardService 从会话管理器算出后传入，本地不依赖采样。
        if (dailyLimitExpired) {
            autoReasons[LockReason.DAILY_LIMIT] = LockDetail(
                text = "今日使用时间已用完"
            )
            raiseOnce(
                key = "limit:DAILY_TOTAL:session",
                type = RiskType.TIME_LIMIT_EXCEEDED,
                level = RiskLevel.NORMAL,
                detail = mapOf("source" to "server-session")
            )
        }

        // ---------- 4. 一次性同步自动原因 ----------
        // 用差集同步而不是逐个 request/release：
        // 逐个调用会在"时段刚结束、限额刚触发"的瞬间出现锁定态短暂为空，导致锁屏页闪一下
        lockController.syncAutoReasons(autoReasons)

        return EngineTick(
            lockState = lockController.state.value,
            schedule = schedule,
            limit = limit,
            eyeCare = eyeCare,
            minorMode = minorMode,
            blockedPackage = blockedPackage,
            blockedReason = blockedReason
        )
    }

    // ==================== 篡改自检 ====================

    /**
     * 篡改自检并落库告警。
     *
     * 只检测与上报，**不尝试阻止**——改时间、开 ADB、root 这些行为端上封不住，
     * 谎称能封会让管控端做出错误承诺。上报后由服务端/家长侧决策。
     */
    suspend fun scanTamper(): List<TamperFinding> {
        val policy = policyRepository.getPolicy()
        // systemLock 必须一起传：debug 构建/救援通道会主动解除调试类限制，
        // 缺了它 TamperDetector 只能按默认策略判定，把"按设计解除"误报成 POLICY_APPLY_FAILED。
        val findings = tamperDetector.detect(policy.security, policy.systemLock)
        if (findings.isEmpty()) return emptyList()

        val deviceId = authRepository.getDeviceId()
        findings.forEach { finding ->
            logRepository.raise(
                type = finding.type,
                level = finding.level,
                detail = finding.detail,
                deviceId = deviceId
            )
            Logger.w(TAG) { "tamper detected: ${finding.type} ${finding.detail}" }
        }

        // 权限被降级意味着之前下发的限制可能已失效，立刻重新对齐一次
        if (findings.any { it.type == RiskType.PERMISSION_REVOKED }) {
            applyCurrent(force = true)
        }
        return findings
    }

    // ==================== 指令执行 ====================

    suspend fun execute(command: Command, deviceId: String): CommandAck =
        commandExecutor.execute(command, deviceId)

    // ==================== 生命周期 ====================

    /**
     * 开机回调。
     *
     * 重启会清掉临时解锁（防"临时解锁 30 分钟后重启白嫖"）与篡改基准，
     * 并强制重新下发一次策略——部分厂商 ROM 在恢复出厂/系统升级后会丢用户限制。
     */
    suspend fun onDeviceBoot() {
        lockController.onDeviceBoot()
        tamperDetector.onDeviceBoot()
        appLimitEnforcer.resetSampling()
        eyeCareEnforcer.reset()
        lastAppliedVersion = NOT_APPLIED
        applyCurrent(force = true)
    }

    // ==================== 只读查询（供 app 层 UI / 拦截判定） ====================

    fun monitoring(): MonitoringRuntime = monitoringEnforcer.current()

    fun kioskRuntime() = kioskEnforcer.runtime()

    fun isKioskAllowed(packageName: String): Boolean = kioskEnforcer.isAllowed(packageName)

    fun matchUrl(url: String, pageTitle: String = ""): WebVerdict = webEnforcer.match(url, pageTitle)

    fun isMonitoredBrowser(packageName: String): Boolean = webEnforcer.isMonitoredBrowser(packageName)

    fun activePeripheralOverrides(): Map<String, Boolean> = peripheralEnforcer.activeOverrides()

    /** 未成年人模式当前状态（是否开启 / 是否处于宵禁），供状态页与家长展示 */
    suspend fun minorModeVerdict(): MinorModeVerdict =
        minorModeEnforcer.evaluate(policyRepository.getPolicy().minorMode, timezone = policyRepository.getPolicy().schedule.timezone)

    /** 当前档位对应的宵禁时段；模式关闭或未启用宵禁时返回 null */
    suspend fun curfewRule(): com.padguard.core.data.model.policy.ScheduleRule? =
        minorModeEnforcer.curfewRuleOf(policyRepository.getPolicy().minorMode)

    /**
     * 未成年人模式是否开启。
     *
     * 孩子端据此**隐藏**退出入口：指南要求"退出未成年人模式需家长验证"，
     * 最稳的实现是孩子端根本不给这个入口，而不是"给了但验证失败"。
     */
    suspend fun minorModeEnabled(): Boolean =
        policyRepository.getPolicy().minorMode.enabled

    /** 护眼休息的"跳过"入口，供家长临时授权场景使用 */
    fun skipEyeCareRest() {
        eyeCareEnforcer.skipRest()
        lockController.releaseLock(LockReason.EYE_CARE)
    }

    // ==================== 内部 ====================

    /**
     * 同一原因当天只上报一次。
     *
     * 采样间隔 15s，若不去重，一次"额度耗尽"能在放学后刷出上千条告警，
     * 既撑爆本地库也会把家长的通知栏刷成噪音。
     */
    private suspend fun raiseOnce(
        key: String,
        type: RiskType,
        level: RiskLevel,
        detail: Map<String, String>
    ) {
        val today = currentDayKey()
        if (today != alertDayKey) {
            alertDayKey = today
            alertedKeys.clear()
        }
        if (!alertedKeys.add(key)) return

        val deviceId = authRepository.getDeviceId()
        logRepository.raise(type = type, level = level, detail = detail, deviceId = deviceId)
    }

    /** 日切用服务端校准后的墙钟，与限额统计保持同一口径 */
    private fun currentDayKey(): String =
        Instant.ofEpochMilli(timeProvider.now()).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    companion object {
        private const val TAG = "PolicyEngine"
        private const val NOT_APPLIED = -1
        private const val MAX_DETAIL_CHARS = 500
    }
}

/**
 * 一次采样的聚合结果。
 *
 * app 层据此决定：显示锁屏页（[LockState.locked]）、
 * 弹应用拦截页（[blockedPackage]）、还是只弹一个即将超时的提醒（[limit] 为 Warning）。
 */
data class EngineTick(
    val lockState: LockState,
    val schedule: ScheduleVerdict,
    val limit: LimitVerdict,
    val eyeCare: EyeCareVerdict,
    /** 未成年人模式判定：是否开启、当前是否处于夜间宵禁 */
    val minorMode: MinorModeVerdict = MinorModeVerdict.Off,
    /** 非空表示该应用已超额，应被拦截（但不锁整机） */
    val blockedPackage: String?,
    /** 拦截原因文案（限额超额/黑名单命中），供拦截页展示 */
    val blockedReason: String = ""
) {
    val shouldShowLockScreen: Boolean get() = lockState.locked
    val shouldBlockApp: Boolean get() = blockedPackage != null
}
