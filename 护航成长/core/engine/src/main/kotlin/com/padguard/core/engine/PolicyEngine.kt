package com.padguard.core.engine

import com.padguard.core.common.Logger
import com.padguard.core.common.TimeProvider
import com.padguard.core.data.model.Command
import com.padguard.core.data.model.CommandAck
import com.padguard.core.data.model.RiskLevel
import com.padguard.core.data.model.RiskType
import com.padguard.core.data.model.policy.AppListMode
import com.padguard.core.data.model.policy.AppPolicy
import com.padguard.core.data.model.policy.BlacklistAction
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
     * 串行化 [applyAll]。
     *
     * 策略下发、开机自检、周期自检三条路径可能并发触发全量下发。
     * 若不串行化，两次 apply 交错执行会出现"A 清限制 → B 加限制 → A 再清"，
     * 最终状态取决于线程调度，现场表现为"策略偶发不生效"，极难复现。
     */
    private val applyMutex = Mutex()

    @Volatile
    private var lastAppliedVersion = NOT_APPLIED

    /**
     * 当日已上报过的告警 key，防止同一原因每 15s 刷一条。
     *
     * 必须由 [alertLock] 保护：本集合有两个并发写入入口 —— `applyAll`（受 [applyMutex] 保护）
     * 与 `tick`（**不在**该互斥内）。无保护的 `HashSet.add()` 在并发下会丢失更新，
     * 极端情况还会破坏内部结构；后果是同一告警重复上报（刷屏）或该报的一条被吞掉。
     * 跨天重置（clear + 换 dayKey）也必须与 add 一起原子完成。
     */
    private val alertLock = Any()
    private val alertedKeys = mutableSetOf<String>()
    private var alertDayKey = ""

    /**
     * 篡改自检里「持续为真、检测层又没有自身闩锁」的状态型告警，在**上报层**做边沿去重：
     * 只在「上一轮没有 → 本轮出现」那一拍上报；状态消失即复位，下次再出现仍会报。
     *
     * 为什么收在 [scanTamper] 而不是给每个 Detector 加字段：这里是**唯一**把 TamperFinding
     * 变成 RiskEvent 的出口，检测层每轮重新构造 findings 是正常语义，不该被改。
     * 旧实现对每个 finding 无条件 `raise`，而服务端 `createFromEvent` 按 eventId 去重、
     * 不按类别去重 —— 开发者选项开着、Wi-Fi 挂代理、debug 包装到已绑定设备、凭据被清
     * （CONTROL_LOST）都会每轮自检新建一条 ACTIVE 告警，家长端红点只增不减，
     * 正是「反复提示 / 一直提示」的直接来源之一。
     *
     * 事件型（尝试卸载、打开黑名单应用、改时间）刻意**不**进本集合：家长要看次数。
     * HOME_CHANGED / ROOT_DETECTED / a11y / usageStats 在检测层已有各自闩锁，也不进。
     */
    private val tamperEdgeLock = Any()
    private var prevStatefulTamper: Set<String> = emptySet()

    // ==================== 全量策略落地 ====================

    /** 用本地缓存的当前策略执行一次全量下发 */
    suspend fun applyCurrent(force: Boolean = false): EnforceReport =
        applyAll(policyRepository.getPolicy(), force)

    /**
     * 按策略里的「日志保留天数」清理本地已上传日志。
     *
     * 保留期字段（`security.logRetentionDays`）此前没有任何执行点：家长把它设成 3 天还是 180 天，
     * 本地库都只增不减 —— 一台设备跑上一年，日志表能把存储吃掉一大截。
     * 由周期上报任务调用即可（幂等、只删已上传记录，不会丢未上报数据）。
     *
     * @return 实际使用的保留天数，便于调用方写日志。
     */
    suspend fun purgeExpiredLogs(): Int {
        val policy = policyRepository.getPolicy()
        val days = securityEnforcer.effectiveRetentionDays(policy.security)
        logRepository.purge(days)
        return days
    }

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

            // 能力性降级也必须让家长看见。
            // 去重键按"降级项签名"而不是策略版本：家长每改一次策略版本号就 +1，
            // 按版本去重会让同一批"本机做不到"的项每天反复弹告警；
            // 反过来只要降级集合没变，同一天内只报一次。
            //
            // 级别用 INFO 而不是 NORMAL：降级是"这台机器本来就这样"，不是故障。
            // 用 WARNING 会让没有 DO 的存量设备天天占一条告警，家长很快学会无视告警 ——
            // 那时真正的 HIGH（锁屏失效 / 权限被撤销）也会被一起无视。
            if (report.hasDegradation) {
                val signature = report.unsupported
                    .map { it.substringBefore(':') }
                    .sorted()
                    .joinToString(",")
                raiseOnce(
                    key = "degraded:${signature.hashCode()}",
                    type = RiskType.POLICY_DEGRADED,
                    level = RiskLevel.INFO,
                    detail = mapOf(
                        "version" to policy.version.toString(),
                        "unsupportedCount" to report.unsupported.size.toString(),
                        "unsupported" to report.unsupported.joinToString("; ").take(MAX_DETAIL_CHARS)
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
     * @param awaitingStart 新的一天尚未确认「开始计时」（已绑定且时间管控开启、会话未 active）。
     *        true 时挂 [LockReason.AWAITING_START] 等待页：未确认期间不计时、不消耗额度，
     *        但设备不可自由使用。由 GuardService 从会话管理器算出后传入（须叠加"已绑定"，
     *        未绑定设备不挂——等待页对未绑定设备没有意义）。
     * @param timerPaused 计时已暂停（熄屏自动暂停 / 长按悬浮球确认暂停）。
     *        true 时挂 [LockReason.TIMER_PAUSED]：恢复计时前设备不可操作。
     *        该原因不受临时解锁抑制，只有会话 resume() 能解除。
     */
    suspend fun tick(
        foregroundPackage: String?,
        screenOn: Boolean,
        dailyLimitExpired: Boolean = false,
        awaitingStart: Boolean = false,
        timerPaused: Boolean = false
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
        // 护眼强制休息锁屏期间屏幕点亮但设备被锁定，这段时间不应计入使用时长：
        // 本地累计跳过（避免上报口径失真），服务端权威会话也会统一扣减。
        // 取上一轮 tick 已 settle 的锁定态：本轮 syncAutoReasons 在末尾才更新，
        // 因此这里看到的是"上一轮结束时的 EYE_CARE 是否生效"，与休息实际进行态一致（最多一个采样周期延迟）。
        val eyeCareResting = lockController.state.value.reasons.contains(LockReason.EYE_CARE)
        val limit = appLimitEnforcer.track(policy.appLimit, foregroundPackage, screenOn, eyeCareResting)
        var blockedPackage: String? = null
        var blockedReason = ""
        var silentClose = false
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
        // 因此在每个采样点检查前台包名：命中黑名单就按 blacklistAction 处置
        // （BLOCK_DIALOG 弹拦截页 / SILENT_CLOSE 静默退回桌面），
        // 并上报 BLACKLIST_APP_LAUNCH 让家长知道孩子尝试过。
        if (foregroundPackage != null && blockedPackage == null) {
            val (blocked, silent) = decideBlacklistBlock(
                foregroundPackage,
                policy.app,
                lockController.isTempUnlockActive(foregroundPackage)
            )
            if (blocked != null) {
                blockedPackage = blocked
                silentClose = silent
                if (!silent) blockedReason = "该应用已被家长管控，无法使用"
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

        // ---------- 2.6 Kiosk 降级态的应用层兜底 ----------
        // 无 Device Owner 时 LockTask 与常驻桌面都不可用，Kiosk 只剩一句"已开启"的空话：
        // 家长配了白名单，孩子照样能开任何应用。这里在采样点做最后一道拦截，
        // 让"纯净桌面"在非 DO 机型上至少是"能拦住"而不是"完全没作用"。
        if (foregroundPackage != null && blockedPackage == null) {
            val kioskRuntimes = kioskEnforcer.runtime()
            if (decideKioskBlock(
                    foregroundPackage = foregroundPackage,
                    kiosk = kioskRuntimes,
                    tempUnlocked = lockController.isTempUnlockActive(foregroundPackage)
                )
            ) {
                blockedPackage = foregroundPackage
                silentClose = false
                blockedReason = "当前为纯净桌面模式，仅可使用家长允许的应用"
            }
        }

        // ---------- 3. 护眼休息 ----------
        val eyeCare = eyeCareEnforcer.track(policy.eyeCare, screenOn)
        if (eyeCare is EyeCareVerdict.Resting && eyeCare.forceLock) {
            // 锁屏顶部展示「本次休息总时长」（固定值，来自家长设定的 restMinutes），
            // 与底部 MM:SS 精确倒计时分工明确：上=总时长，下=倒计时。
            // 历史实现错把「递减的剩余分钟」放到顶部，导致"约 X 分钟"既不像总时长也不像倒计时。
            val totalRestMinutes = policy.eyeCare.restMinutes
            autoReasons[LockReason.EYE_CARE] = LockDetail(
                text = "本次护眼休息共 $totalRestMinutes 分钟",
                untilMillis = eyeCare.untilMillis
            )
        }

        // ---------- 3.5 日总时长（服务端权威会话驱动） ----------
        // 早期实现依据本地采样累计判定 DAILY_TOTAL，三端各自按本地时钟算，必然对不上。
        // 改为服务端锁定计时起点与额度，三端用同一 serverNow - startEpoch 派生剩余，
        // 到期未延时即经此处挂 DAILY_LIMIT，并经 syncAutoReasons 统一管理生命周期
        // （临时解锁期内会被 LockController 自动抑制，避免家长放行被立刻锁回）。
        // dailyLimitExpired 由 GuardService 从会话管理器算出后传入，本地不依赖采样。
        // 双保险：会话侧已按 timeControlEnabled 判过到期，这里再按策略包的 appLimit.enabled 兜一层。
        // 存量服务端 / 缓存策略包不带该标记时，家长关掉「时间管控」后设备仍会被锁 ——
        // 时段锁有 schedule.enabled 兜底，时长这一路此前没有，表现为"总开关关了、照样到点锁屏"。
        if (dailyLimitExpired && policy.appLimit.enabled) {
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

        // ---------- 3.6 等待「开始计时」确认 ----------
        // 新的一天经孩子确认才开始计时（家长端可主动发起/催办）。未确认期间保持等待页：
        // 不计时、不消耗额度，但也不放行 —— "一直不确认"不能成为绕过。
        // 与 DAILY_LIMIT 互斥（awaitingStart 只在会话未 active 时为 true，
        // 而 isExpired() 在 !active 时恒 false，两者不会同时成立）。
        if (awaitingStart && policy.appLimit.enabled) {
            autoReasons[LockReason.AWAITING_START] = LockDetail(
                text = "今日使用计时尚未开始，这是每天使用前的例行确认（并非故障），点「开始计时」后开始记录使用时间"
            )
        }

        // ---------- 3.55 暂停计时（停止计时期间不可操作） ----------
        // 与 AWAITING_START 一样走 syncAutoReasons 差集：暂停瞬间由 UsageSessionManager
        // 直接 requestLock 立即生效，之后每轮由这里重派生，避免"手动挂了又被差集解掉"。
        // 刻意不看 policy.appLimit.enabled：暂停是设备级硬锁，家长关掉时长管控时
        // 会话侧会在下一次刷新清除暂停态，在此之前宁可多锁一轮也不放行。
        if (timerPaused) {
            autoReasons[LockReason.TIMER_PAUSED] = LockDetail(
                text = "计时已暂停，点「继续计时」后恢复使用"
            )
        }

        // ---------- 4. 一次性同步自动原因 ----------
        // 用差集同步而不是逐个 request/release：
        // 逐个调用会在"时段刚结束、限额刚触发"的瞬间出现锁定态短暂为空，导致锁屏页闪一下
        lockController.syncAutoReasons(autoReasons)

        // 取出 EyeCareEnforcer 在本轮产生的待上报休息事件（restId, 实际休息毫秒），上抛给 GuardService
        val pendingRestReport = eyeCareEnforcer.consumePendingRestReport()

        return EngineTick(
            lockState = lockController.state.value,
            schedule = schedule,
            limit = limit,
            eyeCare = eyeCare,
            minorMode = minorMode,
            blockedPackage = blockedPackage,
            blockedReason = blockedReason,
            silentClose = silentClose,
            pendingRestReport = pendingRestReport
        )
    }

    /**
     * 下一次时段状态翻转的绝对时间（R-P2-6，供 app 层设精确闹钟）。
     *
     * 息屏进入 Doze 后，守护服务的 `delay()` 不会唤醒 CPU —— 只靠轮询会让
     * "21:00 开始锁机"实际拖到下一次系统唤醒。app 层据此排一次
     * `setExactAndAllowWhileIdle`，到点立刻复查。
     */
    suspend fun nextScheduleTransition(atMillis: Long = timeProvider.now()): Long? =
        scheduleEvaluator.nextTransitionAt(policyRepository.getPolicy().schedule, atMillis)

    /**
     * 多窗口下"该拦谁"的只读预判（R-P2-10）。
     *
     * ## 解决什么
     * 分屏时同时有两个 resumed 的应用。旧实现只把"最后被点过的那个"当作前台，
     * 于是另一半屏幕上的被禁/超额应用既不计时、也不进入拦截判定 ——
     * 孩子把游戏和计算器并排开着，游戏就能一直玩下去。
     *
     * ## 为什么单独做成只读预判，而不是让 [tick] 一次判多个
     * [tick] 带有**副作用**（累计每应用时长、记启动次数、推进护眼与会话状态）。
     * 对多个候选各跑一次 tick 会把同一个采样周期的时长重复计入，等于凭空吃掉孩子的额度。
     * 因此这里只做判定（[AppLimitEnforcer.evaluate] 与纯函数判定），
     * 由调用方把选中的包**作为唯一前台**交给 [tick]，计时仍然只发生一次。
     *
     * @return 应当优先拦截的包名；都不该拦时返回 null（调用方回落到"最近 resumed 的那个"）
     */
    suspend fun blockedForegroundAmong(candidates: List<String>): String? {
        if (candidates.size <= 1) return null
        val policy = minorModeEnforcer.deriveBaseline(policyRepository.getPolicy())
        val kiosk = kioskEnforcer.runtime()
        return decideMultiWindowPriority(
            candidates = candidates,
            app = policy.app,
            kiosk = kiosk,
            tempUnlocked = { pkg -> lockController.isTempUnlockActive(pkg) },
            limitVerdict = { pkg -> appLimitEnforcer.evaluate(policy.appLimit, pkg) }
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
        // isDeviceBound 区分「debug 构建在开发/未绑定测试机」与「误部署到真实受管子设备」，后者才告警。
        val bound = authRepository.getDeviceId().isNotBlank()
        val findings = tamperDetector.detect(policy.security, policy.systemLock, isDeviceBound = bound)
        if (findings.isEmpty()) return emptyList()

        val deviceId = authRepository.getDeviceId()
        val currentTypes = findings.mapTo(HashSet()) { it.type.name }
        val freshStateful = synchronized(tamperEdgeLock) {
            val statefulNow = currentTypes.intersect(STATEFUL_TAMPER_TYPES)
            val fresh = statefulNow - prevStatefulTamper
            // 无论本轮是否上报都要更新基线：状态持续为真时下一轮不再报，
            // 状态消失后再出现时又是一次「从无到有」，能正确再报一次。
            prevStatefulTamper = statefulNow
            fresh
        }
        findings.forEach { finding ->
            val typeName = finding.type.name
            if (typeName in STATEFUL_TAMPER_TYPES && typeName !in freshStateful) return@forEach
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

    fun matchUrl(url: String, pageTitle: String = ""): WebVerdict = webEnforcer.match(url, pageTitle)

    fun isMonitoredBrowser(packageName: String): Boolean = webEnforcer.isMonitoredBrowser(packageName)

    // 注：以下只读 API 经核验无任何消费点（E6），已按评估文档"接线或删除"原则移除：
    // isKioskAllowed / activePeripheralOverrides / minorModeVerdict / curfewRule /
    // minorModeEnabled / skipEyeCareRest。
    // 保留 monitoring()（GuardService:619 已消费）、kioskRuntime()（E2 水印桥接使用）、
    // matchUrl / isMonitoredBrowser（E1 上网管控依赖，单独迭代）。

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
        // "跨天重置"与"去重登记"必须在同一临界区内完成，否则并发下可能
        // 线程 A 刚 clear、线程 B 就把新 key 加进去，随后 A 再 clear 一次把它抹掉。
        val shouldRaise = synchronized(alertLock) {
            if (today != alertDayKey) {
                alertDayKey = today
                alertedKeys.clear()
            }
            alertedKeys.add(key)
        }
        if (!shouldRaise) return

        val deviceId = authRepository.getDeviceId()
        logRepository.raise(type = type, level = level, detail = detail, deviceId = deviceId)
    }

    /**
     * 告警去重的日切键：用服务端校准后的墙钟 + 与用量统计完全相同的时区。
     *
     * 时区必须是 Asia/Shanghai，不能取 `ZoneId.systemDefault()`：
     * 设备时区非东八区时，去重键的清空时刻会和用量日切差几个小时 ——
     * 同一个业务日内 `limit:APP_DAILY:<pkg>` 被提前清空 → 同一原因重复上报（家长通知栏刷屏）；
     * 反向则会把两个业务日合成一天，该报的告警被吞掉。
     * 该常量与 [com.padguard.core.data.repository.UsageRepository.dayKey] 的时区必须一致。
     */
    private fun currentDayKey(): String =
        Instant.ofEpochMilli(timeProvider.now()).atZone(ZoneId.of(BIZ_ZONE)).toLocalDate().toString()

    companion object {
        private const val TAG = "PolicyEngine"
        private const val NOT_APPLIED = -1
        private const val MAX_DETAIL_CHARS = 500

        /** 业务时区：与 UsageRepository.dayKey / 服务端 StatisticsService 保持完全一致 */
        private const val BIZ_ZONE = "Asia/Shanghai"

        /**
         * 走「边沿上报」的状态型篡改类别：条件会持续为真，但状态本身没有恢复动作，
         * 重复上报只会把家长端告警列表刷满。新增 Detector 时若产出的是持续型信号，
         * 必须登记到这里；若是一次性行为事件，则**不要**登记。
         */
        internal val STATEFUL_TAMPER_TYPES = setOf(
            RiskType.DEVELOPER_OPTIONS_ENABLED.name,
            RiskType.USB_DEBUG_ENABLED.name,
            RiskType.DEBUG_BUILD_ACTIVE.name,
            RiskType.PROXY_DETECTED.name,
            RiskType.CONTROL_LOST.name
        )

        /** 纯函数：本轮该上报的状态型类别 = 本轮新出现的 ∩ 白名单。便于 JVM 单测锁死边沿语义。 */
        internal fun freshStatefulTamper(prev: Set<String>, current: Set<String>): Set<String> =
            (current - prev).intersect(STATEFUL_TAMPER_TYPES)

        /**
         * 分屏（多窗口）时"该优先拦谁"的纯逻辑（R-P2-10）。
         *
         * 判据与 [tick] 的内联判定同源：黑名单兜底 → Kiosk 降级白名单 → 单应用限额，
         * 但**只判定不计时**（[limitVerdict] 由调用方注入只读的 evaluate）。
         * 顺序即优先级：黑名单/纯桌面是家长明确的"不许用"，比"时长超了"更该先拦。
         *
         * 为什么临时放行要在最外层统一跳过：家长刚点"同意再玩一会儿"，
         * 若这里仍按黑名单/限额返回该包，拦截页会在下一个采样点立刻盖上，
         * 家长端显示"已同意"而孩子端毫无变化。
         */
        internal suspend fun decideMultiWindowPriority(
            candidates: List<String>,
            app: AppPolicy,
            kiosk: com.padguard.core.engine.enforcer.KioskRuntime,
            tempUnlocked: (String) -> Boolean,
            limitVerdict: suspend (String) -> LimitVerdict
        ): String? {
            for (pkg in candidates) {
                if (pkg.isBlank()) continue
                if (tempUnlocked(pkg)) continue
                if (decideBlacklistBlock(pkg, app, tempUnlocked = false).first != null) return pkg
                if (decideKioskBlock(pkg, kiosk, tempUnlocked = false)) return pkg
                val verdict = limitVerdict(pkg)
                // DAILY_TOTAL 走服务端权威会话（见 tick 注释），本地这一路不重复判定。
                if (verdict is LimitVerdict.Blocked && verdict.reason != LimitReason.DAILY_TOTAL) return pkg
            }
            return null
        }

        /**
         * Kiosk 降级态下的前台拦截判定（纯函数，便于 JVM 单测）。
         *
         * 三个前提缺一不可，每一个都对应一种"拦了会出事"的场景：
         * 1. `enabled`：家长没开 Kiosk 就不能拦；
         * 2. `degraded`：DO 机型由 LockTask 真锁定，应用层再拦属于重复且会误伤
         *    （例如系统桌面切换的瞬间）；
         * 3. `userAppCount > 0`：白名单没配任何应用时若照拦，设备只剩桌面 → 变砖。
         *    [com.padguard.core.engine.enforcer.KioskRuntime.allowedPackages] 恒含 self/桌面/必备包，
         *    不能拿它判空。
         */
        internal fun decideKioskBlock(
            foregroundPackage: String,
            kiosk: com.padguard.core.engine.enforcer.KioskRuntime,
            tempUnlocked: Boolean
        ): Boolean {
            if (!kiosk.enabled || !kiosk.degraded) return false
            if (kiosk.userAppCount <= 0) return false
            if (tempUnlocked) return false
            return foregroundPackage !in kiosk.allowedPackages
        }

        /**
         * 黑名单前台拦截判定（纯函数，便于 JVM 单测）。
         *
         * 仅在「设备退化为 LEGACY（无 DO/PO，隐藏/挂起不可用）」时由 [tick] 兜底调用，
         * 命中黑名单且未被家长临时放行时返回被拦包名；[BlacklistAction.SILENT_CLOSE] 时
         * 第二个返回值为 true，告知 app 层静默退回桌面而非弹拦截页。
         *
         * 抽出为纯函数是因为 [tick] 整体依赖大量 Android 侧 Enforcer，无法在 JVM 单测里直接构造；
         * 把"命中判定 + 处置方式"这一核心策略与平台副作用解耦，既保证可测，也不改变既有行为。
         */
        internal fun decideBlacklistBlock(
            foregroundPackage: String,
            app: AppPolicy,
            tempUnlocked: Boolean
        ): Pair<String?, Boolean> {
            if (tempUnlocked) return null to false
            // 白名单模式不在此兜底：差集逻辑需要"全部可启动应用"参与，
            // 在采样点做会把桌面、输入法等一并拦死，风险远大于收益（白名单依赖 DO）。
            if (app.mode == AppListMode.WHITELIST) return null to false
            // systemAppRules 是按包显式覆盖：ALLOW 优先于黑名单成员资格（例如被归类进黑名单的
            // 系统应用明确放行），BLOCK 则强制纳入拦截（即便不在黑名单列表里）。
            return when (app.systemAppRules[foregroundPackage]?.uppercase()) {
                "ALLOW" -> null to false
                "BLOCK" -> foregroundPackage to (app.blacklistAction == BlacklistAction.SILENT_CLOSE)
                else -> if (foregroundPackage in app.blacklist) {
                    foregroundPackage to (app.blacklistAction == BlacklistAction.SILENT_CLOSE)
                } else {
                    null to false
                }
            }
        }
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
    val blockedReason: String = "",
    /**
     * 黑名单命中的处置方式：true = 静默退回桌面（[BlacklistAction.SILENT_CLOSE]），
     * 不弹拦截页；false = 弹拦截页（[BlacklistAction.BLOCK_DIALOG]）。
     * 仅在 [blockedPackage] 非空且来自黑名单兜底时适用。
     */
    val silentClose: Boolean = false,
    /**
     * 护眼强制休息结束后待上报服务端的事件（restId, 实际休息毫秒）。
     * 非空时由 [com.padguard.child.service.GuardService] 异步上报，
     * 服务端据此从每日总时长中扣减，保证三端一致。取走即由 EyeCareEnforcer 清空。
     */
    val pendingRestReport: Pair<String, Long>? = null
) {
    val shouldShowLockScreen: Boolean get() = lockState.locked
    val shouldBlockApp: Boolean get() = blockedPackage != null
}
