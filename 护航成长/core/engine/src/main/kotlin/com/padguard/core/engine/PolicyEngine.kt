package com.padguard.core.engine

import com.padguard.core.common.Logger
import com.padguard.core.common.TimeProvider
import com.padguard.core.data.model.Command
import com.padguard.core.data.model.CommandAck
import com.padguard.core.data.model.RiskLevel
import com.padguard.core.data.model.RiskType
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
import com.padguard.core.engine.enforcer.MonitoringEnforcer
import com.padguard.core.engine.enforcer.MonitoringRuntime
import com.padguard.core.engine.enforcer.PeripheralEnforcer
import com.padguard.core.engine.enforcer.SecurityEnforcer
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
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
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
    private val webEnforcer: WebEnforcer,
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

            val effective = resolveEffectivePolicy(policy)
            val report = EnforceReport()

            // 下发顺序不是随意排的，见 ORDER 说明
            report.merge(securityEnforcer.apply(effective.security))
            report.merge(peripheralEnforcer.apply(effective.peripheral))
            report.merge(systemLockEnforcer.apply(effective.systemLock))
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
     */
    suspend fun tick(foregroundPackage: String?, screenOn: Boolean): EngineTick {
        val policy = policyRepository.getPolicy()
        val autoReasons = mutableMapOf<LockReason, LockDetail>()

        // ---------- 1. 时段锁 ----------
        val schedule = scheduleEvaluator.evaluate(policy.schedule)
        if (schedule is ScheduleVerdict.Locked) {
            autoReasons[LockReason.SCHEDULE] = LockDetail(
                text = "当前处于禁用时段 ${schedule.rule.start}–${schedule.rule.end}",
                untilMillis = schedule.untilMillis
            )
        }

        // ---------- 2. 使用限额 ----------
        val limit = appLimitEnforcer.track(policy.appLimit, foregroundPackage, screenOn)
        var blockedPackage: String? = null
        if (limit is LimitVerdict.Blocked) {
            if (limit.reason == LimitReason.DAILY_TOTAL) {
                autoReasons[LockReason.DAILY_LIMIT] = LockDetail(limit.message())
            } else {
                // 单应用超额只拦这个应用，不锁整机（见类注释的设计取舍）
                blockedPackage = limit.packageName
            }
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

        // ---------- 3. 护眼休息 ----------
        val eyeCare = eyeCareEnforcer.track(policy.eyeCare, screenOn)
        if (eyeCare is EyeCareVerdict.Resting && eyeCare.forceLock) {
            val minutes = (eyeCare.remainingSeconds + 59) / 60
            autoReasons[LockReason.EYE_CARE] = LockDetail(
                text = "护眼休息中，剩余约 $minutes 分钟",
                untilMillis = eyeCare.untilMillis
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
            blockedPackage = blockedPackage
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
    /** 非空表示该应用已超额，应被拦截（但不锁整机） */
    val blockedPackage: String?
) {
    val shouldShowLockScreen: Boolean get() = lockState.locked
    val shouldBlockApp: Boolean get() = blockedPackage != null
}
