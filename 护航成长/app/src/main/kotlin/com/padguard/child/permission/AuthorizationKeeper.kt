package com.padguard.child.permission

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.padguard.child.AppForeground
import com.padguard.child.MainActivity
import com.padguard.child.guard.AccessibilityCapabilityHealer
import com.padguard.core.common.Logger
import com.padguard.core.data.repository.AuthRepository
import com.padguard.core.data.repository.ReauthSource
import com.padguard.core.engine.admin.DeviceAdminBridge
import com.padguard.core.engine.guard.GuardMasterSwitch
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 授权保持器（防回退自愈层）—— 实现用户诉求"授权不会自动丢失 / 写死"。
 *
 * ## 为什么需要它
 * 既有的一次性授权流水线（[com.padguard.child.consent.OneTimeConsentViewModel]）解决了
 * "装机一次拿全、使用中不再弹框"；但它只在**首次装机**时主动引导。
 * 授权完成之后，若孩子在系统设置里把某项关掉（或国产 ROM 切换某权限时强制重启 App 把
 * 无障碍/录屏授权清空），旧逻辑只在后台静默上报告警，**不会自动把家长带回重新授权** ——
 * 这正是用户感知到的"授权会丢、没写死"。
 *
 * ## Android 的硬边界（必须如实告知，不可替代）
 * AppOps（悬浮窗 / 使用情况访问 / 安装未知应用）与无障碍服务**连设备所有者都无权代为授予**，
 * 任何第三方 App 都无法把它们"不可逆地写死"——孩子或家长在系统设置里手动关掉后，
 * 应用既无权阻止、也无权静默改回（那本身属于越权 / 后门，是被明确禁止的）。
 * 因此"不会丢失"的正解不是"锁死不可改"，而是 **持久化 + 全量自检 + 自愈**：
 *   - 装机一次性拿全（既有流水线，满足"一次授权、使用中无需再点"）；
 *   - 本类在 开机 / 服务启动 / 周期 三个时机全量复检；
 *   - 发现某项被关掉：可静默补的按各自能力补回 —— 运行时权限仅 DO/PO 可补，
 *     无障碍则 DO 或装机 adb 授权 WRITE_SECURE_SETTINGS 的非 DO 设备均可补
 *     （[AccessibilityCapabilityHealer.canSilentHeal]，覆盖 EMUI 一键清理）；
 *     其余（使用情况访问 / 安装未知应用，平台不允许静默）以及无静默通道设备的缺失项，
 *     **自动把家长带回一次性授权引导流程重新勾选** —— 而不是只后台静默告警。
 *
 * ## 与既有 [com.padguard.child.guard.PermissionHealthChecker] 的关系
 * 既有检查只覆盖"无障碍 + 悬浮窗"两项且仅上报；本类扩展到**全部授权项**
 * （设备管理员 / 运行时权限 / 全部特殊权限），并把"非 DO 缺失"从"上报"升级为
 * "自动拉回引导流程"，是用户诉求"不会自动丢失"的真正落地。
 *
 * ## 不干扰正常使用
 * 拉回引导页只在**本 App 已在前台**（家长正看着）时进行；App 在后台时不强行抢前台，
 * 仅置位 [AuthRepository.reauthRequired]，待下一次打开 App（入口分流）即进入引导。
 * 既保证"丢失必被补回"，又避免后台周期性地把孩子正在用的平板弹到授权页。
 */
@Singleton
class AuthorizationKeeper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val admin: DeviceAdminBridge,
    private val permissionGranter: PermissionGranter,
    private val healer: AccessibilityCapabilityHealer,
    private val authRepository: AuthRepository,
    private val guardMasterSwitch: GuardMasterSwitch,
    private val ledger: AuthorizationLedger
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 单一飞行守卫：开机广播 / 服务启动 / 周期巡检三路时机可能重叠触发 [reconcile]，
     * 不加保护会导致 [permissionGranter.ensureGranted] 与无障碍自愈被并发重复执行、
     * 账本 SharedPreferences 被重复提交。用 `Mutex + running` 标志让同一时刻只有一个
     * reconcile 在跑，其余重叠调用直接跳过（reconcile 幂等，周期巡检会补上漏网之鱼）。
     */
    private val reconcileMutex = Mutex()
    private var reconcileRunning = false

    /**
     * 全量复检并自愈 / 拉回。
     *
     * 幂等、低打扰：所有项都齐时只清掉"需重新授权"标记，不做任何动作；
     * 缺失时再分级处理（DO 静默补 / 非 DO 拉回引导）。
     */
    suspend fun reconcile() {
        // 单一飞行：已有 reconcile 在跑则本帧跳过，避免重复自愈与冗余账本写盘。
        //
        // 注意 `return@withLock` 只能退出 lambda、**退不出本函数** —— 旧写法用它做守卫，
        // 实际效果是"跳过置位、然后照样执行 doReconcile()"，而且在 finally 里把并发那一方
        // 的 running 标记提前清掉，等于守卫双向失效（两次 reconcile 真的会并行跑）。
        // 因此先把"是否轮到我"求值成布尔，再在锁外决定是否返回。
        val proceed = reconcileMutex.withLock {
            if (reconcileRunning) {
                Logger.v(TAG) { "reconcile skipped: another reconcile already in progress" }
                false
            } else {
                reconcileRunning = true
                true
            }
        }
        if (!proceed) return
        try {
            doReconcile()
        } finally {
            reconcileMutex.withLock { reconcileRunning = false }
        }
    }

    private suspend fun doReconcile() = withContext(Dispatchers.Default) {
        // 未绑定 / 管控暂停 / 从未走过装机引导：没有管控依据，不动作（避免误扰）
        if (!guardMasterSwitch.active.first()) return@withContext
        if (!authRepository.isBound.first()) return@withContext
        if (!authRepository.isConsentDone.first()) return@withContext

        admin.refreshControlMode()
        val hasOwner = admin.hasOwnerPrivileges()

        // ① 能静默补的两类，门不同、别混：
        //   - 运行时权限（覆盖安装 / 系统清授予后必丢）：只有 DO/PO 能静默 re-grant，留 hasOwner 门；
        //   - 无障碍服务：DO 或装机 adb 授权 WRITE_SECURE_SETTINGS 的非 DO 设备都能静默重开，
        //     门必须走 canSilentHeal()——否则目标华为平板（非 DO + 已授权）在开机/服务启动/
        //     周期巡检这条入口上被 hasOwner 挡住，一键清理后只能等 PermissionHealthChecker
        //     的慢周期兜底（第三处漏网，2026-11-17 收口）。
        if (hasOwner) {
            runCatching { permissionGranter.ensureGranted() }
                .onFailure { Logger.w(TAG, it) { "re-grant runtime failed" } }
        }
        if (healer.canSilentHeal()) {
            runCatching { healer.ensureEnabled() }
                .onFailure { Logger.w(TAG, it) { "re-enable a11y failed" } }
        }

        // ② 计算仍缺失的关键项（DO 下 INSTALL_PACKAGES 已被 missing() 排除）
        //
        // 授权专项：特殊权限不再「当前没开就拉回」，而是先记「曾授予」账本，
        // 只有**可检测、参与自愈、且曾授予又掉**的项才算脱管。
        // 否则家长在装机时明确跳过的项、或本机根本没有该开关的项，会被每轮自检
        // 反复拉回授权页（见 AuthorizationLedger 类注释）。
        //
        // 单次遍历同时完成「记账」与「判掉失」。旧写法有三处冗余：
        //   ① applicable() 全量查一遍 isGranted 记账；
        //   ② missingSpecialPermissions() 内部再全量查一遍（等价的 missing()）；
        //   ③ filter 里第三次调 isGranted —— 而来自 missing() 的项 granted 恒 false，
        //      传进 needsReauth 的只是个恒值，白付了两次系统 API 调用。
        // 现在每项只查一次，口径也统一：不可检测项不查、不记账、不参与自愈。
        val droppedSpecial = buildList {
            SpecialPermission.applicable(context, hasOwner).forEach { perm ->
                val granted = if (perm.detectable) perm.isGranted(context) else false
                ledger.observe(perm.key, granted)
                if (!granted && AuthorizationLedger.needsReauth(
                        granted = false,
                        everGranted = ledger.everGranted(perm.key),
                        detectable = perm.detectable,
                        requiredForReauth = perm.requiredForReauth
                    )
                ) {
                    add(perm.key)
                }
            }
        }

        // ②-b 运行时权限也必须遵守「曾授予又丢」纪律，与 [AuthorizationLedger] 同口径。
        //
        // 旧写法在 stillMissing 里直接把「当前缺失的运行时权限」全量并入：
        // 只要**当前**没授予就算脱管。但 [PermissionGranter.ensureGranted] 的静默补授只在
        // `hasOwner` 时执行，真机（非 DO/PO 软管控）根本不会跑；家长在装机系统弹窗里
        // 明确拒绝的某一项（例如精确定位）就会**永久**留在缺失清单里 →
        // 每轮周期自检都置 `reauthRequired` → 入口分流 `reauthRequired == true` 让
        // **每次打开 App 都强制进 OneTimeConsentScreen(autoStart=true)**，
        // 而 finish() 刚清掉标记、下一轮自检又置位 —— 这正是「怎么授权都授权不完 /
        // 反复被拉回引导页」的闭环。特殊权限早已用账本修掉这类骚扰，运行时权限这一路漏改了。
        //
        // 现在只把「曾观察到已授予、如今掉了」的项算脱管；从未授予过的尊重家长选择，
        // 不再抢前台，改由补开页如实单列（见 PermissionGuideViewModel）。
        val missingRuntime = permissionGranter.missingRuntimePermissions()
        val runtimeEver = runtimeEverGranted().toMutableSet()
        // getStringSet 返回的集合不可直接修改，上面已复制；只在确有新增时写盘，
        // 避免周期自检每拍都产生一次无意义的 SharedPreferences 提交。
        val newlyGranted = permissionGranter.requiredPermissions
            .filter { it !in missingRuntime && it !in runtimeEver }
            .toSet()
        if (newlyGranted.isNotEmpty()) {
            runtimeEver += newlyGranted
            runCatching { prefs.edit().putStringSet(KEY_RUNTIME_EVER_GRANTED, runtimeEver).commit() }
                .onFailure { Logger.w(TAG, it) { "record runtime ever-granted failed" } }
        }
        val droppedRuntime = droppedRuntimePermissions(missingRuntime, runtimeEver)
        if (droppedRuntime.isNotEmpty()) {
            Logger.w(TAG) { "runtime permission dropped after granted: $droppedRuntime" }
        }

        val stillMissing = buildSet {
            val adminActive = admin.isAdminActive()
            // 记账：只要这一次看到它是激活的，就永久记住"这台设备上曾经有过管理员"。
            // 这是区分「从未有过」与「曾有过又丢」的唯一依据（R-P3-13）。
            if (adminActive) markDeviceAdminEverActive()
            if (
                isDeviceAdminMissing(
                    active = adminActive,
                    everActive = hasDeviceAdminEverActive(),
                    promptAck = authRepository.isDeviceAdminPromptAck.first(),
                    hasOwnerPrivileges = hasOwner
                )
            ) {
                add(KEY_DEVICE_ADMIN)
            }
            addAll(droppedRuntime)
            addAll(droppedSpecial)
        }

        if (stillMissing.isEmpty()) {
            // 全齐：只复位**本自检名下**的那条来源。旧写法无条件清全局 bool，会把
            // 「通知代收授权掉失」「卸载防护为 0」「家长刚点一键补齐」等与本自检维度无关的
            // 引导请求一起吞掉（权限页点了没反应 / 权限掉了却不被拉回）。R36 来源化收口。
            authRepository.clearReauthRequired(ReauthSource.KEEPER)
            return@withContext
        }

        // ③ 仍缺失：DO 下只剩平台不允许静默的项（使用情况访问 / 安装未知应用），
        //    非 DO 下包括全部特殊权限 —— 统统把家长拉回引导流程重新勾选。
        Logger.w(TAG) { "authorization dropped, items=${stillMissing}, hasOwner=$hasOwner" }
        authRepository.setReauthRequired(ReauthSource.KEEPER)
        bringBackToConsent()
    }

    /**
     * 把授权页宿主拉回前台，让家长重新勾选缺失项。
     *
     * 复用与 [com.padguard.child.consent.ConsentReturnPresenter] 相同的
     * "落在本 App 任务栈 → 命中 Android 10+ 后台启动豁免"策略；
     * 仅当本 App 已在前台时才真正拉起，避免后台周期任务把孩子正在用的平板弹到授权页。
     */
    private fun bringBackToConsent() {
        if (!AppForeground.isForeground) return // 后台：只置位标记，下次打开 App 由入口分流接管
        // 时钟域：单调钟 + 高水位。KEY_LAST_BRING_BACK 历史上存过墙钟值，
        // 且 elapsedRealtime 在重启后归零 —— 两种情况都会出现 last > now，
        // 一律视为「早已过期」放行，绝不让旧值把冷却窗延长成永久抑制。
        val last = prefs.getLong(KEY_LAST_BRING_BACK, 0L)
        val now = SystemClock.elapsedRealtime()
        if (last in 1..now && now - last < BRING_BACK_COOLDOWN_MS) return // 去抖：避免短期内反复抢前台
        // 戳必须**启动成功之后**才落。旧实现在 startActivity 之前就写冷却时间戳：
        // 一旦拉起抛异常（Activity 管理器拒绝 / ROM 拦截），冷却窗已经把这次失败"记成已尝试"，
        // 接下来整个冷窗内都不再补拉 —— 家长看到的仍是"权限页点了没反应"，
        // 而日志里连一条失败都查不到（因为下一次尝试被静默跳过了）。
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                }
            )
        }.onSuccess {
            prefs.edit().putLong(KEY_LAST_BRING_BACK, now).apply()
        }.onFailure { Logger.w(TAG) { "bring back to consent failed: ${it.message}" } }
    }

    /** 本机曾观察到「已授予」的运行时权限（持久化，跨进程/重启有效）。 */
    private fun runtimeEverGranted(): Set<String> =
        runCatching { prefs.getStringSet(KEY_RUNTIME_EVER_GRANTED, emptySet()).orEmpty() }
            .getOrDefault(emptySet())

    /** 这台设备上是否**曾经**观察到设备管理员处于激活态（持久化，跨进程重启有效）。 */
    private fun hasDeviceAdminEverActive(): Boolean = prefs.getBoolean(KEY_DA_EVER_ACTIVE, false)

    /** 记住"曾经有过管理员"，供 [isDeviceAdminMissing] 区分"从未有过"与"曾有过又丢"。 */
    private fun markDeviceAdminEverActive() {
        if (prefs.getBoolean(KEY_DA_EVER_ACTIVE, false)) return
        prefs.edit().putBoolean(KEY_DA_EVER_ACTIVE, true).commit()
        Logger.i(TAG) { "device admin observed active, recorded as ever-active" }
    }

    companion object {
        private const val TAG = "AuthorizationKeeper"
        private const val PREFS_NAME = "padguard_auth_keeper"
        private const val KEY_LAST_BRING_BACK = "last_bring_back_ms"
        private const val KEY_DEVICE_ADMIN = "deviceAdmin"

        /** 「曾经有过设备管理员」账本（R-P3-13），与 [KEY_LAST_BRING_BACK] 同库但语义完全不同 */
        private const val KEY_DA_EVER_ACTIVE = "deviceAdminEverActive"

        /**
         * 「运行时权限曾授予」账本：只记系统明确判定为已授予的权限名，
         * 供 [droppedRuntimePermissions] 区分「家长主动拒绝（从未授予）」与「曾授予又丢」。
         * 与 [KEY_DA_EVER_ACTIVE] 同为单向写入、无删除入口，状态抖动不会抹掉历史。
         */
        private const val KEY_RUNTIME_EVER_GRANTED = "runtimeEverGranted"

        /** 拉回前台去抖窗口：15 分钟 */
        private const val BRING_BACK_COOLDOWN_MS = 15 * 60 * 1000L
    }
}

/**
 * 运行时权限里哪些属于「曾授予又丢」（**纯函数**，便于 JVM 单测锁定）。
 *
 * 与 [AuthorizationLedger.needsReauth] 完全同一条纪律：只有曾经明确观察到已授予、
 * 现在又缺失的项，才是真实的脱管信号，才值得把家长拉回授权页。
 * 从未授予过的（家长装机时在系统弹窗里主动拒绝、或该 ROM 直接不给）一律不算 ——
 * 否则非 DO 设备上这类项永久缺失，周期自检会无限置位 `reauthRequired`，
 * 表现为「每次打开 App 都被强制拉回授权页、怎么授权都授权不完」。
 *
 * 不把运行时权限塞进 [AuthorizationLedger]：那里的 [AuthorizationLedger.isTrackable]
 * 有意只认 [SpecialPermission] 的 key，混进 `android.permission.*` 会破坏既有契约。
 */
internal fun droppedRuntimePermissions(
    missing: List<String>,
    everGranted: Set<String>
): List<String> = missing.filter { it in everGranted }

/**
 * 设备管理员是否应被判定为**缺失**（需要把家长拉回重新激活）。
 *
 * ## 为什么不能只判 `!isAdminActive()`
 * 旧实现把"未激活"直接当缺失，只用一个"家长已跳过 DA 引导"的标记（`daPromptAck`）豁免。
 * 这个豁免是**一次性开关**，一旦置位就永久生效，于是出现两处串味：
 *
 * 1. **跳过 DO 的标记被当成"管理员没了"**：家长在 DO 预置页选择"已知风险，继续"后，
 *    设备以非 DO 软管控形态运行，`isAdminActive()` 天然为 false —— 判定层却把它当成
 *    "管理员被停用"，反复把家长拉回授权流程。
 * 2. **真丢失被静默吞掉**（更严重）：`daPromptAck` 不只由"家长主动跳过"写入，
 *    授权流水线"带家长去过激活页但没激活"也会落一次（`OneTimeConsentViewModel.markDeviceAdminGiveUp`）。
 *    此后孩子真去设置里把管理员停用，这条判定仍然被 ack 豁免 → **不告警、不拉回**，
 *    设备长期处于无闸门状态（对应 R-P1-7 的"非 DO 长期无闸门"）。
 *
 * ## 正确口径：区分「从未有过」与「曾有过又丢」
 * - 当前激活 → 不缺失；
 * - DO/PO 形态 → 设备管理员由系统绑定，不参与缺失判定（避免 DO 标记串味）；
 * - 曾激活过、现在掉了 → **真丢失**，必须补开，`promptAck` 不得豁免（这是唯一的篡改信号）；
 * - 从未激活过 → 尊重家长显式跳过（`promptAck`），否则在"激活页点不了激活"的机型上会死循环。
 *
 * 抽成不依赖 Android 的纯函数，便于在 JVM 单测里锁定这四条分支。
 */
internal fun isDeviceAdminMissing(
    active: Boolean,
    everActive: Boolean,
    promptAck: Boolean,
    hasOwnerPrivileges: Boolean
): Boolean = when {
    active -> false
    hasOwnerPrivileges -> false
    everActive -> true
    else -> !promptAck
}
