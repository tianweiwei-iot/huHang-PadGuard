package com.padguard.core.engine.guard

import android.content.Context
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import com.padguard.core.common.Logger
import com.padguard.core.common.TimeProvider
import com.padguard.core.data.model.RiskLevel
import com.padguard.core.data.model.RiskType
import com.padguard.core.data.model.policy.ControlMode
import com.padguard.core.data.model.policy.SecurityPolicy
import com.padguard.core.data.model.policy.SystemLockPolicy
import com.padguard.core.engine.admin.DeviceAdminBridge
import com.padguard.core.engine.enforcer.isBlocking
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * 绕过与篡改检测。
 *
 * ## 设计立场：检测不是为了阻止，而是为了「不被蒙在鼓里」
 * 本类检出的大多数情况（已 root、权限被摘、时间被改）终端都**无力阻止**。
 * 那还检测什么？—— 检测的价值在于把"管控已失效"这件事变成服务端可见的事实：
 *
 * - 家长能看到"设备已 root，部分管控可能失效"，而不是以为一切正常；
 * - 学校资产管理能定位到具体哪台设备被动过手脚；
 * - 服务端可以据此触发人工介入（联系家长/收回设备），这是唯一真正有效的手段。
 *
 * 一个只报"一切正常"的管控系统，比一个会说"我这里出问题了"的系统危险得多。
 *
 * ## 时钟篡改的本地检测方法
 * 不依赖服务端也能检出改时间：同时记录墙钟与单调时钟，
 * 两次检测之间**墙钟的增量应当约等于单调时钟的增量**。
 * 若两者相差超过容差（[CLOCK_DRIFT_TOLERANCE_MS]），说明墙钟被人为跳变。
 * 这个方法的好处是离线也能生效 —— 而孩子改时间前往往会先断网。
 */
@Singleton
class TamperDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val admin: DeviceAdminBridge,
    private val probe: GuardInfraProbe,
    private val timeProvider: TimeProvider
) {

    private var lastWallClock: Long = 0L
    private var lastElapsed: Long = 0L
    /** 控制权态迟滞基线：最近一次"未变弱"的态；降级需跨多次扫描确认，避免瞬时抖动误报 */
    private var stableControlMode: ControlMode? = null
    /** 连续"比基线弱"的扫描计数；达到阈值才确认降级 */
    private var controlModeWeakStreak = 0
    /** 控制权态"持续变弱"多少轮扫描才确认为真实降级（迟滞，防误报） */
    private val permissionDowngradeConfirmScans = 2
    private var rootReported = false

    // 守护基建告警的一次性闩锁：只在「由好变坏」的那一刻报一次，
    // 恢复后复位 —— 若每轮自检都报，家长端会被同一条告警刷屏。
    private var accessibilityLostReported = false
    private var usageLostReported = false

    /**
     * 执行一轮检测。
     *
     * @return 本轮新发现的问题；空列表表示一切正常。
     *   调用方（[com.padguard.core.engine.PolicyEngine]）负责落库并上报。
     */
    fun detect(policy: SecurityPolicy, systemLock: SystemLockPolicy = SystemLockPolicy()): List<TamperFinding> {
        val findings = mutableListOf<TamperFinding>()

        detectClockTampering(policy, findings)
        detectPermissionDowngrade(findings)
        detectDebugChannels(findings)
        if (policy.blockRoot) detectRoot(findings)
        detectRestrictionLoss(policy, systemLock, findings)
        detectGuardInfra(findings)

        if (findings.isNotEmpty()) {
            Logger.w(TAG) { "tamper findings: ${findings.map { it.type }}" }
        }
        return findings
    }

    /**
     * 墙钟跳变检测。
     *
     * 首次调用只记录基准不报警（此时没有可比较的前值）。
     * 容差取 2 分钟：足以覆盖 NTP 自动校时的正常修正，又能抓住"往前调半小时"这种作弊。
     */
    private fun detectClockTampering(policy: SecurityPolicy, findings: MutableList<TamperFinding>) {
        if (!policy.antiClockTamper) return

        val wall = System.currentTimeMillis()
        val elapsed = timeProvider.elapsedRealtime()

        if (lastWallClock == 0L || elapsed < lastElapsed) {
            // 首次检测，或设备刚重启（单调时钟归零）→ 重置基准，不判定
            lastWallClock = wall
            lastElapsed = elapsed
            return
        }

        val wallDelta = wall - lastWallClock
        val elapsedDelta = elapsed - lastElapsed
        val drift = abs(wallDelta - elapsedDelta)

        lastWallClock = wall
        lastElapsed = elapsed

        if (drift > CLOCK_DRIFT_TOLERANCE_MS) {
            findings += TamperFinding(
                type = RiskType.CLOCK_TAMPERING,
                level = RiskLevel.HIGH,
                detail = mapOf(
                    "wallDeltaMs" to wallDelta.toString(),
                    "elapsedDeltaMs" to elapsedDelta.toString(),
                    "driftMs" to drift.toString(),
                    "direction" to if (wallDelta > elapsedDelta) "forward" else "backward"
                )
            )
        }
    }

    /**
     * 管控权限降级检测。
     *
     * `adb shell dpm remove-active-admin` 或用户在设置里关闭设备管理器都会导致降级。
     * 降级后终端仍在运行、仍在上报心跳，但绝大多数策略已静默失效 ——
     * 这是整个系统最危险的失效模式，必须以 HIGH 级告警立刻上报。
     */
    /**
     * 控制权态降级检测（如设备管理员被用户在设置中关闭、DO 被摘除）。
     *
     * ## 迟滞（hysteresis）防误报
     * [com.padguard.core.engine.admin.DeviceAdminBridge.refreshControlMode] 在设备管理员接收器尚未就绪、
     * 或 `dpm` 查询瞬时失败时会偶发回落到 [ControlMode.LEGACY]；若每次抖动都上报 `PERMISSION_REVOKED`，
     * 会引发家长端"权限被撤销"的**误报**，并触发 [com.padguard.core.engine.PolicyEngine] 在抖动态重放策略。
     * 因此要求"持续变弱"跨 [permissionDowngradeConfirmScans] 次扫描才确认，单次毛刺（下一轮即恢复）不告警；
     * 真正的降级（用户确实关闭了设备管理员）会持续成立，必然跨过阈值被捕获。
     */
    private fun detectPermissionDowngrade(findings: MutableList<TamperFinding>) {
        val current = admin.refreshControlMode()
        val base = stableControlMode
        if (base == null) {
            stableControlMode = current
            return
        }
        // 回到基线或更强：恢复稳定，重置计数（单次抖动到此为止）
        if (current.ordinal <= base.ordinal) {
            stableControlMode = current
            controlModeWeakStreak = 0
            return
        }
        // current 比基线弱
        controlModeWeakStreak++
        if (controlModeWeakStreak < permissionDowngradeConfirmScans) return

        findings += TamperFinding(
            type = RiskType.PERMISSION_REVOKED,
            level = RiskLevel.HIGH,
            detail = mapOf(
                "from" to base.name,
                "to" to current.name,
                "streak" to controlModeWeakStreak.toString()
            )
        )
        // 已确认降级：把弱态设为新基线，避免后续每轮重复告警；恢复时才重新计数
        stableControlMode = current
        controlModeWeakStreak = 0
    }

    /** 开发者选项 / USB 调试被打开：这是绕过管控的常见前置动作 */
    private fun detectDebugChannels(findings: MutableList<TamperFinding>) {
        // debug 构建自我豁免：调试通道是开发/远程运维的生命线（见 SystemLockEnforcer），
        // 客户端自己恢复了这些开关还反复告警，只会制造"狼来了"，把真告警淹没掉。
        if (admin.isDebuggableBuild) return

        val devEnabled = runCatching {
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                0
            ) == 1
        }.getOrDefault(false)

        val adbEnabled = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        }.getOrDefault(false)

        if (devEnabled) {
            findings += TamperFinding(
                type = RiskType.DEVELOPER_OPTIONS_ENABLED,
                level = RiskLevel.NORMAL,
                detail = mapOf("source" to "Settings.Global.DEVELOPMENT_SETTINGS_ENABLED")
            )
        }
        if (adbEnabled) {
            findings += TamperFinding(
                type = RiskType.USB_DEBUG_ENABLED,
                level = RiskLevel.HIGH,
                detail = mapOf("source" to "Settings.Global.ADB_ENABLED")
            )
        }
    }

    /**
     * root 检测。
     *
     * 只做**只读的文件与属性检查**，不执行 `su`、不 fork shell：
     * 执行外部命令在部分 ROM 上会触发安全弹窗、被安全软件拦截，甚至造成 ANR，
     * 而收益仅是多覆盖少量隐藏 root 的场景，不值得。
     *
     * 检出后只上报一次（[rootReported]）—— root 状态不会自己消失，重复告警只会淹没告警列表。
     */
    private fun detectRoot(findings: MutableList<TamperFinding>) {
        if (rootReported) return

        val suspiciousPath = SU_PATHS.firstOrNull { runCatching { File(it).exists() }.getOrDefault(false) }
        val testKeys = Build.TAGS?.contains("test-keys") == true
        val rootApp = ROOT_MANAGER_PACKAGES.firstOrNull { isInstalled(it) }

        if (suspiciousPath == null && !testKeys && rootApp == null) return

        rootReported = true
        findings += TamperFinding(
            type = RiskType.ROOT_DETECTED,
            level = RiskLevel.HIGH,
            detail = buildMap {
                suspiciousPath?.let { put("suPath", it) }
                if (testKeys) put("buildTags", Build.TAGS.orEmpty())
                rootApp?.let { put("rootApp", it) }
                put("note", "已 root 设备无法保证管控有效性，建议人工介入")
            }
        )
    }

    /**
     * 关键用户限制丢失检测。
     *
     * 部分国产 ROM 在系统升级、账号切换后会清掉 DO 下发的用户限制。
     * 由于清除是静默的，若不主动核对，终端会以为策略还在生效。
     * 这里抽查最关键的几条，发现丢失即上报 POLICY_APPLY_FAILED 触发重新下发。
     */
    private fun detectRestrictionLoss(
        policy: SecurityPolicy,
        systemLock: SystemLockPolicy,
        findings: MutableList<TamperFinding>
    ) {
        if (admin.controlMode.value != ControlMode.DEVICE_OWNER) return

        // 「预期存在的限制」必须和真正下发它们的 Enforcer 口径一致。
        // 曾经这里按 SecurityPolicy 的开关直接列固定清单，结果把"按设计主动解除"的项
        // 当成了"限制被绕过"：debug 构建会主动解除 USB 调试限制（见 SystemLockEnforcer 的
        // debug 豁免与 DeviceAdminBridge.applyHardeningBaseline），于是每次自检都误报
        // POLICY_APPLY_FAILED，首页「最近拦截」里刷满「管控事件：POLICY_APPLY_FAILED」。
        // 排查时极易被这条假告警带偏，真正的告警反而被淹没。
        val expected = buildList {
            if (policy.antiClockTamper) add(UserManager.DISALLOW_CONFIG_DATE_TIME)
            if (policy.blockRoot) add(UserManager.DISALLOW_FACTORY_RESET)
            // 完全复刻 SystemLockEnforcer 的判定：debug 构建豁免 + 救援通道（ALLOW）时不封。
            // 任一侧口径不一致，就会把"按设计解除"误报成"限制被绕过"。
            val blockDebug = !admin.isDebuggableBuild &&
                (systemLock.developerOptions.isBlocking() || systemLock.usbDebug.isBlocking())
            if (blockDebug) add(UserManager.DISALLOW_DEBUGGING_FEATURES)
            // DISALLOW_UNINSTALL_APPS 由 AppPolicy.installPolicy.allowUserUninstall 决定，
            // 本类只拿得到 SecurityPolicy，无法判断它此刻该开还是该关，故不列入预期；
            // 防卸载的真实基线是 AppPolicyEnforcer.protectSelf 的 setUninstallBlocked(自己)，
            // 那一项无条件生效，不受任何策略开关影响。
        }
        val missing = expected.filterNot { admin.hasUserRestriction(it) }
        if (missing.isEmpty()) return

        findings += TamperFinding(
            type = RiskType.POLICY_APPLY_FAILED,
            level = RiskLevel.NORMAL,
            detail = mapOf(
                "missingRestrictions" to missing.joinToString(","),
                "note" to "限制项已丢失（ROM 升级或系统清理导致），需重新下发"
            )
        )
    }

    /**
     * 守护基建存活检测（S12）。
     *
     * 无障碍服务（操作密码闸门的系统级拦截硬依赖）与 Usage Access
     * （应用管控探不到前台包名的直接原因）被用户在设置里关掉后，
     * 终端不会崩溃、心跳照常 —— 不主动核对，家长会以为管控仍在生效。
     *
     * 闩锁语义：丢失的那一刻报一次，之后每轮扫描保持静默；
     * 权限恢复后复位，再次丢失会重新报。设备重启不复位（重启不会自动恢复权限，
     * 复位只会造成重启后同一条告警再刷一次）。
     */
    private fun detectGuardInfra(findings: MutableList<TamperFinding>) {
        // 用「存活」而非「开关是否打开」判定：开关开着但服务进程被系统/省电策略杀掉时，
        // isAccessibilityServiceEnabled() 仍返回 true，会漏报；而开关被关掉时，只对「用户真的关了」
        // 报一次，绝不应把「进程被系统杀掉、看门狗会在 GRACE 窗口内自愈」误报成「人为关闭」。
        val a11yAlive = runCatching { probe.isAccessibilityServiceAlive() }.getOrDefault(false)
        val a11yEnabled = runCatching { probe.isAccessibilityServiceEnabled() }.getOrDefault(false)
        val usageGranted = runCatching { probe.isUsageAccessGranted() }.getOrDefault(false)

        when {
            // 1) 开关开着且进程存活：一切正常，复位闩锁
            a11yAlive -> accessibilityLostReported = false
            // 2) 开关开着但进程瞬时死亡：可自愈，不打扰家长，也不冤枉用户「人为关闭」。
            //    watchdog / 无障碍自杀活会在窗口内拉起进程，下一轮自检自然回到分支 1。
            a11yEnabled -> {
                accessibilityLostReported = false
                Logger.i(TAG) { "a11y switch ON but process dead; watchdog should self-heal, skip report" }
            }
            // 3) 开关确实被关掉：这才需要家长介入，且仅在「由好变坏」那一刻报一次。
            !accessibilityLostReported -> {
                accessibilityLostReported = true
                findings += TamperFinding(
                    type = RiskType.ACCESSIBILITY_DISABLED,
                    level = RiskLevel.HIGH,
                    detail = mapOf(
                        "note" to "操作闸门的系统级拦截已关闭，孩子可在设置中绕过密码闸门，请引导孩子重新开启"
                    )
                )
            }
        }

        if (usageGranted) {
            usageLostReported = false
        } else if (!usageLostReported) {
            usageLostReported = true
            findings += TamperFinding(
                type = RiskType.USAGE_ACCESS_LOST,
                level = RiskLevel.NORMAL,
                detail = mapOf(
                    "note" to "使用情况访问权限被关闭，应用管控与时长统计将静默失效，请引导孩子重新授权"
                )
            )
        }
    }

    private fun isInstalled(packageName: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                packageName,
                android.content.pm.PackageManager.PackageInfoFlags.of(0L)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
        }
        true
    }.getOrDefault(false)

    /** 重启后调用：清空时钟基准，避免把关机时长误判成时钟跳变 */
    fun onDeviceBoot() {
        lastWallClock = 0L
        lastElapsed = 0L
        rootReported = false
    }

    companion object {
        private const val TAG = "TamperDetector"

        /** 墙钟与单调时钟的允许偏差：2 分钟（覆盖正常 NTP 校时） */
        const val CLOCK_DRIFT_TOLERANCE_MS = 2 * 60 * 1000L

        private val SU_PATHS = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/system/app/Superuser.apk",
            "/data/adb/magisk",
            "/data/adb/modules"
        )

        private val ROOT_MANAGER_PACKAGES = listOf(
            "com.topjohnwu.magisk",
            "eu.chainfire.supersu",
            "com.koushikdutta.superuser",
            "com.noshufou.android.su"
        )
    }
}

/** 一条检测结论，由上层转成 [com.padguard.core.data.model.RiskEvent] 上报 */
data class TamperFinding(
    val type: RiskType,
    val level: RiskLevel,
    val detail: Map<String, String>
)
