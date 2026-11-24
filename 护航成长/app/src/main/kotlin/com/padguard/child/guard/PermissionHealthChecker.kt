package com.padguard.child.guard

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import com.padguard.core.common.Logger
import com.padguard.core.data.model.RiskLevel
import com.padguard.core.data.model.RiskType
import com.padguard.core.data.repository.AuthRepository
import com.padguard.core.data.repository.ReauthSource
import com.padguard.core.data.repository.LogRepository
import com.padguard.core.transport.RemoteDataSource
import com.padguard.core.transport.http.ApiResult
import com.padguard.core.engine.admin.DeviceAdminBridge
import com.padguard.child.guard.A11yRebindNotifier
import com.padguard.core.engine.admin.OpResult
import com.padguard.core.engine.guard.GuardMasterSwitch
import com.padguard.child.notify.NotificationCollectionPrefs
import com.padguard.child.notify.NotifyPermission
import com.padguard.child.permission.PermissionGranter
import com.padguard.child.permission.SpecialPermission
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 权限掉失自检与自愈。
 *
 * ## 解决什么问题
 * 在系统设置里授予/切换某些特殊权限（如国产 ROM 的"后台弹出界面" / 悬浮窗）时，
 * 系统会**强制重启**本应用。重启后：
 *  - 无障碍服务绑定被解除（设置里"无障碍"项被系统自动关闭）；
 *  - MediaProjection 的"允许录屏"授权随进程死亡被清空。
 * 于是表现为"之前打开的无障碍、允许录屏等权限全部被取消"。
 * 若无障碍失效，设置/应用拦截层整体瘫痪；录屏授权丢失则看屏/录屏下发即失败。
 *
 * 本检查在 [com.padguard.child.service.GuardService] 每次启动时执行：
 *  - 无障碍：具备静默写通道的设备（DO 或装机 adb 授权 WRITE_SECURE_SETTINGS 的非 DO，
 *    见 [AccessibilityCapabilityHealer.canSilentHeal]）静默重启用；无通道设备如实上报告警
 *    并引导家长/操作员去设置重新启用；
 *  - 悬浮窗：掉了只告警引导（悬浮窗无法被代码静默授予，必须用户操作）。
 * 这样权限被系统静默撤销时，设备不再"悄无声息地管控残缺"，而是明明白白地提醒。
 */
@Singleton
class PermissionHealthChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val admin: DeviceAdminBridge,
    private val healer: AccessibilityCapabilityHealer,
    private val logRepository: LogRepository,
    private val authRepository: AuthRepository,
    private val guardMasterSwitch: GuardMasterSwitch,
    private val remote: RemoteDataSource,
    private val granter: PermissionGranter,
    private val a11yGuideNotifier: A11yRebindNotifier,
    private val notifyPrefs: NotificationCollectionPrefs
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 应用真正需要守住的运行时权限 —— **直接复用** [PermissionGranter.requiredPermissions]。
     *
     * ## 为什么不能另写一份清单（2026-11-05 线上复盘）
     * 本类原先自带一份常量清单，里面列了 `READ_PHONE_STATE` —— 而该权限在合规瘦身时
     * 已从 AndroidManifest 删除。后果不是"多检查一项"这么轻：
     * `checkSelfPermission` 对**未声明**的权限恒返回 DENIED，于是 [computeRecovered] 里
     * "runtime" **永远**被计入"当前缺失"，`recoveredCaps = prev - now` 永远算不出 runtime 恢复 →
     * 一旦因别的原因（控制权降级、设备管理员被停用）上报过 `PERMISSION_REVOKED`，
     * 这条告警就**再也不会被自动关闭**，家长端永久挂着一条消除不掉的"权限被撤销"。
     * 存储权限也有同款问题：manifest 里标了 `maxSdkVersion=32`，Android 13+ 上同样恒未授予。
     *
     * [PermissionGranter.requiredPermissions] 已经把"版本差异 + 是否真的声明"编码进了一份清单，
     * 这里只管复用，绝不再造第二份口径。
     */
    private val criticalRuntimePermissions: List<String>
        get() = granter.requiredPermissions

    /** 当前缺失的关键运行时权限（版本与声明均已对齐，不会出现"恒缺失"的幽灵项）。 */
    private fun missingCriticalRuntimePermissions(): List<String> =
        criticalRuntimePermissions.filter {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

    suspend fun check() = withContext(Dispatchers.Default) {
        if (!guardMasterSwitch.active.value) return@withContext // 暂停管控时本就不需要这些权限
        if (!authRepository.isBound.first()) return@withContext // 未绑定不检查（无管控依据）
        val a11y = checkAccessibility()
        val a11yOk = a11y.ok
        val overlayOk = checkOverlay()
        // 无障碍已恢复：撤掉此前那条常驻「操作闸门已失效」全屏引导，并复位本地引导节流窗。
        // 否则家长在系统设置里重新开启后，通知栏仍挂着划不掉的失效警告；且 30 分钟节流窗
        // 未复位会让"恢复后短期内再次真掉线"完全不提示。
        if (a11yOk) clearA11yRebindGuideIfNeeded()
        // 运行期被系统自动回收的运行时危险权限（定位/相机/电话/存储等），在 DO 形态下无感自愈；
        // 非 DO 仅记录待恢复项由 UI 引导。与无障碍/悬浮窗自检互补，覆盖"权限被撤销"全部来源。
        healRuntimePermissions()
        // 其余特殊权限快检（usageStats / 电池优化 / 精确闹钟 / 通知 / 通知代收）：此前盲区，现纳入 5 分钟快检。
        checkOtherSpecialCapabilities()
        // 自愈成功后闭环服务端告警：把"已恢复"的类别通知服务端关闭对应 ACTIVE 告警
        val recovered = computeRecovered(a11yOk, overlayOk, a11y.selfHealed)
        resolveIfNeeded(recovered)
    }

    /**
     * 无障碍服务是否在系统设置中启用。
     *
     * ## 统一到 [GuardA11y.isEnabled]（2026-11 修复）
     * 旧实现自己读 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 原始串，
     * 与 [GuardA11y.isEnabled] 走 [android.view.accessibility.AccessibilityManager] 列表是**两套口径**，
     * 二者在定制 ROM 下会不一致，正是"主页喊缺授权、自检却认为已开"的根因之一。
     * 现在全部收敛到 [GuardA11y.isEnabled] 这唯一事实源（其内部已把落盘设置作为主口径、
     * Manager 列表作兜底），主页横幅与常驻自检永不再打架。
     */
    private fun isA11yEnabled(): Boolean = GuardA11y.isEnabled(context)

    /**
     * 「无障碍疑似异常」的内存观察态（供 [isA11yObservationActive] 组合判定，见其文档）。
     *
     * ## 为什么需要它（2026-11-16 一键清理事故复盘）
     * EMUI「一键清理/清理加速」把本应用整包杀掉后，无障碍服务随进程死亡。
     * 开关仍开着、进程被看门狗拉回的前 ~2 分钟内，[GuardA11y.isAlive] 仍靠**陈旧心跳**
     * 判"活着"（[GuardA11y.ALIVE_GRACE_MS] 容忍窗），观察窗（disabled/detached since）
     * 都还没起算 —— 此时持久化侧看不出任何异常，若调度侧不提速，告警要等 5 分钟周期
     * 转到心跳过期之后那一拍才可能落下。这个内存标志把"已连接"与"靠陈旧心跳撑着"
     * 区分开：进程刚重启但服务没连上（EMUI 阻断重绑的典型形态）也算疑似，
     * 让 GuardService 立即切换到 2 分钟快检节奏。
     *
     * 进程内瞬时态、不落盘：进程重启后归零，随后的第一次 check 会重新赋值，
     * 与持久化窗口（[isA11yObservationActive] 的另一半）互补不冲突。
     */
    @Volatile
    private var a11ySuspect = false

    /**
     * a11y 是否处于「观察期 / 疑似异常」——GuardService 据此把权限自检从 5 分钟
     * 周期临时提速到 2 分钟（见 GuardService 主循环 capHeal 节拍）。
     *
     * 三路信号任一为真即视为观察中（纯判定见 [a11yObservationActive]，便于单测）：
     * 1. [a11ySuspect]：最近一轮 check 确认 a11y 未处于健康态（未启用 / 已判定不可用 /
     *    开关开着但没连上、仅靠陈旧心跳撑着）；
     * 2. 持久化的「未启用」观察窗起算过（[KEY_A11Y_DISABLED_SINCE] != 0，尚未清除）；
     * 3. 持久化的「开着却没连上」观察窗起算过（[KEY_DETACHED_SINCE] != 0，尚未清除）。
     *
     * 后两路保证进程重启后（内存态归零、但观察窗还在计时）快检节奏不丢。
     */
    fun isA11yObservationActive(): Boolean = a11yObservationActive(
        suspect = a11ySuspect,
        a11yDisabledSince = prefs.getLong(KEY_A11Y_DISABLED_SINCE, 0L),
        detachedSince = prefs.getLong(KEY_DETACHED_SINCE, 0L)
    )

    /**
     * 无障碍健康度结果。
     *
     * [selfHealed] 表示**本轮**由静默写通道把服务从"未启用"拉回了启用态。
     * 它单独带出来是为了让 [computeRecovered] 能关闭 [com.padguard.core.engine.guard.TamperDetector]
     * 上报过的 `ACCESSIBILITY_DISABLED`：那条告警按"开关被关掉"即时上报一次，
     * 而恢复边沿只认 prev/now 差分；静默自愈成功时本函数直接返回可用、prev 从未记录过
     * a11y，差分永远为空 → 家长端永久残留"无障碍已关闭"红点（本轮修复）。
     */
    internal data class A11yHealth(val ok: Boolean, val selfHealed: Boolean = false)

    private suspend fun checkAccessibility(): A11yHealth {
        if (isA11yEnabled()) {
            clearA11yDisabledSince()
            prefs.edit().putBoolean(KEY_A11Y_EVER, true).apply()
            // 开关开着只说明"用户没关"，不代表服务在跑。分三种结局：
            // 已连上 / 刚断开（给容忍窗口）/ 长时间连不上（按不可用上报）。
            if (GuardA11y.isAlive(context)) {
                // 真健康 = 进程内已连接；仅靠陈旧心跳撑着（进程刚重启、服务还没连上）
                // 在 EMUI 清理场景里恰恰是"重绑被阻断"的前兆，维持疑似态驱动 2 分钟快检，
                // 连上那一刻自然转回正常节奏（见 [a11ySuspect] 文档）。
                a11ySuspect = !GuardA11y.isConnectedNow()
                clearDetachedSince()
                return A11yHealth(true)
            }
            a11ySuspect = true
            healDetachedService()
            if (GuardA11y.isAlive(context)) {
                a11ySuspect = !GuardA11y.isConnectedNow()
                clearDetachedSince()
                return A11yHealth(true)
            }
            return A11yHealth(handlePersistentDetach())
        }
        // 无障碍未启用：可静默写的设备（DO 或 WRITE_SECURE_SETTINGS 授权）优先自动重启用，
        // 既无 DO 也无授权时诚实告警引导重新授权。
        //
        // 但**不能读到"未启用"就立刻告警**：国产 ROM 在系统设置里切换任意特殊权限时会
        // 强制重启本应用，重启过程中 ENABLED_ACCESSIBILITY_SERVICES 会被短暂清空，
        // 自检恰好撞上这一拍就看到"未启用"，几秒后系统自己就重绑好了。旧实现在这里
        // 零容忍 —— 立即上报高危告警，并用全屏 intent 把「操作闸门已失效」警告页顶到
        // 前台；而"开关开着但服务没连上"反倒有 10 分钟容忍窗口（见 handlePersistentDetach）。
        // 口径不对称，正是真机上「设备自己弹危险警告 / 反复提示」的直接来源。
        // 这里补一个对称的短观察窗：只有持续未启用才认定为真脱管。
        clearDetachedSince()
        // 开关被关掉（EMUI「一键清理」最典型的落点）本身就是疑似态：观察窗起算期间
        // 维持 2 分钟快检，让"持续未启用"在下一拍就被确认，而不是等满 5 分钟周期。
        a11ySuspect = true
        val ever = prefs.getBoolean(KEY_A11Y_EVER, false)
        if (healer.canSilentHeal()) {
            runCatching { healer.ensureEnabled() }
                .onFailure { Logger.w(TAG, it) { "silent a11y re-enable failed" } }
        }
        // 静默自愈（DO 或 WRITE_SECURE_SETTINGS）已恢复启用，则不再上报告警，避免家长端
        // 出现"无障碍服务已关闭"误报（EMUI 一键清理后 1~3 分钟内自动恢复时家长本无需到场）
        val a11yNow = isA11yEnabled()
        if (!needsA11yDroppedAlert(a11yNow, healer.canSilentHeal())) {
            clearA11yDisabledSince()
            Logger.i(TAG) { "accessibility auto-recovered via silent-write channel; alert suppressed" }
            // selfHealed 让本轮顺便关闭 TamperDetector 可能已上报的 ACCESSIBILITY_DISABLED
            return A11yHealth(true, selfHealed = a11yNow)
        }
        if (!a11yDisabledToleranceElapsed()) {
            // 观察期内一律"暂视为可用"：与 handlePersistentDetach 的容忍窗口同口径。
            // 若这里返回 false，computeRecovered 会把一次瞬时抖动记成真实缺失，
            // 下一拍又伪造出一条"恢复"事件去关闭告警，反而制造更多噪音。
            return A11yHealth(true)
        }
        raiseDroppedAlert("无障碍服务", ever, "accessibility")
        triggerA11yRebindGuide()
        return A11yHealth(false)
    }

    /**
     * 「无障碍未启用」的容忍窗口是否已耗尽（单调钟 + 高水位，脏值一律重新起窗）。
     *
     * 首次看到未启用只记起点、不告警；后续自检持续超过 [A11Y_DISABLED_TOLERANCE_MS]
     * 才允许上报。周期自检约 5 分钟一轮，因此实际效果接近"连续两拍都未启用才报"。
     */
    private fun a11yDisabledToleranceElapsed(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val since = prefs.getLong(KEY_A11Y_DISABLED_SINCE, 0L)
        if (since <= 0L || since > now) {
            prefs.edit().putLong(KEY_A11Y_DISABLED_SINCE, now).apply()
            Logger.i(TAG) { "a11y reported disabled, starting tolerance window" }
            return false
        }
        return a11yDisabledConfirmed(now, since, A11Y_DISABLED_TOLERANCE_MS)
    }

    /** 观察到已启用 / DO 自愈成功即清窗口，保证下次真掉线拿到的是完整观察期。 */
    private fun clearA11yDisabledSince() {
        if (prefs.getLong(KEY_A11Y_DISABLED_SINCE, 0L) != 0L) {
            prefs.edit().remove(KEY_A11Y_DISABLED_SINCE).apply()
        }
    }

    /**
     * 「开关开着、服务却没连上」的处置。
     *
     * ## 为什么必须与「未开启」分开
     * 两者的家长动作完全不同：未开启要去系统设置重新勾选；没连上则**不需要家长做任何事**
     * ——系统一般会在进程被杀后自动重绑，DO 设备还能强制重绑。
     * 历史上把两者混成一个布尔值，于是"服务好端端跑着"也会被判成掉线（见 [GuardA11y] 类注释）；
     * 反过来，若只认开关不认连接，则"开关还在、绑定已死"的真故障会完全静默。
     *
     * ## 处置分级
     * - 已连上：什么都不做（绝大多数情况）；
     * - 没连上 + DO：走 [AccessibilityCapabilityHealer.rebindIfDetached] 强制重绑（内部限频）；
     * - 没连上 + 非 DO：只留痕。此时既不能代开也不能代绑，且**不能**上报
     *   "权限被撤销"——开关确实还在，报给家长只会造成误判。
     */
    private suspend fun healDetachedService() {
        if (GuardA11y.isAlive(context)) return
        Logger.w(TAG) { "a11y switch ON but service not attached; attempting bounded rebind" }
        if (!healer.canSilentHeal()) {
            Logger.w(TAG) {
                "no silent-write channel (no DO, no WRITE_SECURE_SETTINGS): cannot rebind silently; " +
                    "waiting for system rebind (if it never reconnects, ask guardian to re-toggle " +
                    "the service in Settings)"
            }
            return
        }
        runCatching { healer.rebindIfDetached() }
            .onFailure { Logger.w(TAG, it) { "a11y rebind failed" } }
    }

    /**
     * 长时间「开着却没连上」的兜底判定。
     *
     * ## 为什么要给容忍窗口而不是立刻告警
     * 进程被杀后系统重绑通常在秒级完成，DO 设备的强制重绑也会带来几秒空窗；
     * 若第一拍就上报"无障碍已关闭"，家长会在什么都没发生的情况下收到一条高危告警
     * —— 之前那种"狼来了"式的误报正是本次要消灭的对象。
     * 因此只有**持续**超过容忍窗口仍未连上，才认定为真的不可用。
     *
     * ## DO 与非 DO 的容忍窗刻意不同（2026-11-16 EMUI 一键清理事故复盘）
     * 旧实现一刀切 10 分钟：对 DO 是合理的（强制重绑自身就有 10 分钟冷却限频），
     * 但对非 DO 是灾难 —— 非 DO 没有任何自愈手段，系统若会重绑，早在进程被拉回的
     * 秒级就完成了；进程活着、服务却迟迟不连（EMUI 阻断重绑的典型形态）意味着
     * **永远不会自愈**，多等的每一分钟都是闸门裸奔、家长毫不知情的静默失效。
     * 因此非 DO 收敛到与其它观察窗同口径的 90 秒（叠加 [GuardA11y.ALIVE_GRACE_MS]
     * 的心跳过期窗，从服务死亡到告警约 3.5 分钟）；DO 维持 10 分钟不变。
     * 告警恢复后仍由 [computeRecovered] 边沿闭环，误报可自愈。
     *
     * ## 判定为不可用的后果
     * 返回 false 会让调用方把 "a11y" 计入缺失能力集合，从而：
     * - 上报一条**文案准确**的告警（点明"已开启但未连接"，而不是含糊的"未开启"）；
     * - 恢复连接后由边沿触发自动关闭该告警（见 [computeRecovered]）。
     */
    private suspend fun handlePersistentDetach(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val since = prefs.getLong(KEY_DETACHED_SINCE, 0L)
        val tolerance = detachedToleranceMs(admin.isDeviceOwner())
        // 高水位：since == 0 是「从未记录」；since > now 是脏值（升级前的旧墙钟值，
        // 或重启后 elapsedRealtime 归零）——两种情况都重新起窗，绝不容忍窗口被拉长。
        if (since <= 0L || since > now) {
            prefs.edit().putLong(KEY_DETACHED_SINCE, now).apply()
            Logger.i(TAG) { "a11y detached, starting tolerance window (${tolerance / 1000}s)" }
            return true
        }
        if (now - since < tolerance) return true
        Logger.w(TAG) {
            "a11y enabled but still detached after ${(now - since) / 1000}s; treating as unavailable"
        }
        raiseDroppedAlert(
            name = "无障碍服务（已开启但系统未连上）",
            ever = true,
            key = "a11y_detached"
        )
        triggerA11yRebindGuide()
        return false
    }

    private fun clearDetachedSince() {
        if (prefs.getLong(KEY_DETACHED_SINCE, 0L) != 0L) {
            prefs.edit().remove(KEY_DETACHED_SINCE).apply()
        }
    }

    /**
     * 悬浮窗权限自检，与 [checkAccessibility] **同口径**地挡住 ROM 瞬时抖动。
     *
     * ## 为什么补观察窗（2026-11-12）
     * 旧实现读到 `canDrawOverlays() == false` 就立刻 [raiseDroppedAlert]：没有 a11y 那条
     * 90 秒观察窗，口径不对称。国产 ROM 在系统设置里切换任意特殊权限时会强制重启本应用，
     * 权限管家重建状态期间 `canDrawOverlays()` 同样可能短暂返回 false。一次误读的代价不止
     * 一条高危告警：6 小时去抖让它挂在家长端一整天，[computeRecovered] 又把这一拍记成
     * "overlay 缺失"，下一拍恢复时再伪造一条 `GUARD_PERMISSION_DROPPED` 关闭请求 ——
     * 真机表现就是「权限明明开了还显示未开 / 告警反复出现又自己消失」。
     * 与 a11y 完全对称：只有**持续**超过 [OVERLAY_DISABLED_TOLERANCE_MS] 才认定真掉失。
     */
    private suspend fun checkOverlay(): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(context)
        if (granted) {
            clearOverlayDisabledSince()
            prefs.edit().putBoolean(KEY_OVERLAY_EVER, true).apply()
            return true
        }
        if (!overlayDisabledToleranceElapsed()) return true
        val ever = prefs.getBoolean(KEY_OVERLAY_EVER, false)
        raiseDroppedAlert("悬浮窗(后台弹窗)权限", ever, "overlay")
        return false
    }

    /** 与 [a11yDisabledToleranceElapsed] 同构，独立窗口互不串扰；脏值一律重新起窗。 */
    private fun overlayDisabledToleranceElapsed(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val since = prefs.getLong(KEY_OVERLAY_DISABLED_SINCE, 0L)
        if (since <= 0L || since > now) {
            prefs.edit().putLong(KEY_OVERLAY_DISABLED_SINCE, now).apply()
            Logger.i(TAG) { "overlay reported disabled, starting tolerance window" }
            return false
        }
        return disabledConfirmed(now, since, OVERLAY_DISABLED_TOLERANCE_MS)
    }

    /** 观察到已授予即清窗，保证下次真掉线拿到完整观察期。 */
    private fun clearOverlayDisabledSince() {
        if (prefs.getLong(KEY_OVERLAY_DISABLED_SINCE, 0L) != 0L) {
            prefs.edit().remove(KEY_OVERLAY_DISABLED_SINCE).apply()
        }
    }

    // ---------------- 其余特殊权限监控（授权盲区补全）----------------
    //
    // ## 历史盲区
    // 本自检原只覆盖无障碍 + 悬浮窗两项；usageStats / 安装未知应用 / 电池优化 /
    // 精确闹钟 / 通知 这五项「声明了却没自检」的特殊权限，掉失后只能依赖 30 分钟一轮的
    // AuthorizationKeeper，且还被「曾授予」账本门控 —— 孩子从设置里关掉、或从未授予的项长期静默失效：
    //   - USAGE_STATS 掉了 → 前台应用探测为空 → 应用限额 / 用量统计全失效；
    //   - INSTALL_PACKAGES 掉了 → 家长端远程推送安装静默失败（非 DO 设备走系统安装器）；
    //   - BATTERY_OPTIMIZATION 没加白 → 每晚息屏脱管；
    //   - EXACT_ALARM 被撤 → 时段边界锁屏变钝（"21:00 该锁，21:20 才锁"）；
    //   - APP_NOTIFICATIONS 关了 → 授权引导 / 锁屏全屏通知全收不到，家长端只看到"连接中"。
    // 这里把上述五项纳入本 5 分钟一轮的快检，与 a11y/overlay 同口径（容忍窗 + 去抖告警 +
    // 恢复即闭环），并立刻把家长拉回重新授权，把"权限丢了却不知道"的窗口从 30 分钟压到 5 分钟。
    //
    // 与 AuthorizationKeeper 同一纪律：从未授予过的项（家长/装机显式跳过、或本机无此开关）
    // 不告警、不抢前台 —— 只记录"曾授予"账本，只有曾授予又掉才视为真脱管。

    private suspend fun checkOtherSpecialCapabilities() {
        val hasOwner = admin.hasOwnerPrivileges()
        // 本轮确认掉失、需要把家长带回引导的权限 key。整轮结束一次性对账，
        // 已恢复 / 不再适用的项自动从来源集合移除（R36：单一 bool 时代靠 keeper 无条件
        // 清全局来「顺带」复位，改来源模型后必须由本自检自己对账，否则会永久残留）。
        val dropped = mutableSetOf<String>()
        for (perm in SPECIAL_PERMS_TO_MONITOR) {
            if (!perm.applies(context, hasOwner)) continue
            val granted = perm.isGranted(context)
            val everKey = everKeyOf(perm.key)
            if (granted) {
                if (!prefs.getBoolean(everKey, false)) prefs.edit().putBoolean(everKey, true).commit()
                clearSpecialDisabledSince(perm.key)
                continue
            }
            // 从未授予过：尊重家长/装机时的显式跳过，不告警、不抢前台。
            if (!prefs.getBoolean(everKey, false)) continue
            if (!specialDisabledToleranceElapsed(perm.key)) continue // 观察期内暂视为可用，挡 ROM 瞬时抖动
            raiseDroppedAlert(perm.label, ever = true, key = perm.key)
            dropped += perm.key
        }
        if (checkNotificationListener()) dropped += NOTIFICATION_LISTENER_KEY
        // 只替换本自检命名空间，绝不碰 keeper / 卸载防护 / 家长手动补齐的来源。
        authRepository.replaceReauthSources(ReauthSource.PERM_HEALTH_PREFIX, dropped)
    }

    /**
     * 通知代收授权（NotificationListenerService 的系统授权）掉失检测（缺口 #5）。
     *
     * 该能力默认关闭（[NotificationCollectionPrefs]），家长显式开启后才纳入闭环：
     * 功能开着、系统授权却掉了，才视为真掉失并告警引导重新授予；功能未开启时不打扰。
     *
     * @return 本轮确认掉失且需把家长带回引导，供 [checkOtherSpecialCapabilities] 批量对账来源集合。
     */
    private suspend fun checkNotificationListener(): Boolean {
        if (!notifyPrefs.isEnabled()) return false // 功能未开启：不视为掉失
        val enabled = NotifyPermission.isNotificationListenerEnabled(context)
        val everKey = everKeyOf(NOTIFICATION_LISTENER_KEY)
        if (enabled) {
            if (!prefs.getBoolean(everKey, false)) prefs.edit().putBoolean(everKey, true).commit()
            clearSpecialDisabledSince(NOTIFICATION_LISTENER_KEY)
            return false
        }
        if (!prefs.getBoolean(everKey, false)) return false // 从未开启过：不告警
        if (!specialDisabledToleranceElapsed(NOTIFICATION_LISTENER_KEY)) return false
        raiseDroppedAlert("通知代收授权", ever = true, key = NOTIFICATION_LISTENER_KEY)
        return true
    }

    private fun everKeyOf(key: String) = "ever_$key"
    private fun specialDisabledSinceKey(key: String) = "special_disabled_since_$key"

    private fun specialDisabledToleranceElapsed(key: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        val since = prefs.getLong(specialDisabledSinceKey(key), 0L)
        if (since <= 0L || since > now) {
            prefs.edit().putLong(specialDisabledSinceKey(key), now).apply()
            return false
        }
        return disabledConfirmed(now, since, SPECIAL_DISABLED_TOLERANCE_MS)
    }

    private fun clearSpecialDisabledSince(key: String) {
        if (prefs.getLong(specialDisabledSinceKey(key), 0L) != 0L) {
            prefs.edit().remove(specialDisabledSinceKey(key)).apply()
        }
    }

    /**
     * 运行期被系统自动回收的运行时危险权限自愈。
     *
     * ## 解决什么问题
     * [checkAccessibility]/[checkOverlay] 只覆盖无障碍与悬浮窗；而 Android 11+ 的"闲置应用权限自动撤销"、
     * 以及 OEM 省电/权限管家，还会静默回收**普通运行时危险权限**（定位、相机、电话、存储等）。
     * 这类回收发生在应用运行期、无需用户操作，会导致定位/录音/相册等功能静默失效，
     * 并在家长端表现为"权限被撤销"告警。
     *
     * ## 自愈策略
     * - **DO/PO 形态**：应用对自身权限拥有授予能力，可**无感重新授予**，彻底闭环"权限被撤销"，
     *   家长端不再反复告警、功能不再因缺权限失效；
     * - **非 DO 形态**：应用无权替自己授予，仅记录待恢复项（由「我的」页状态横幅引导家长重新授予）。
     *
     * 关键约束：自愈**不以任何方式改变锁定态**——无论权限是否恢复，锁屏与解锁链路都不依赖这些被回收的权限，
     * 因此不会出现"权限被收 → 设备解不开锁"的死锁。
     *
     * @return 本轮实际重新授予的权限数量
     */
    suspend fun healRuntimePermissions(): Int {
        if (!guardMasterSwitch.active.value) return 0
        val missing = missingCriticalRuntimePermissions()
        if (missing.isEmpty()) {
            // 记下"曾完整持有"，用于区分"从未授予"与"被系统静默回收"两种告警文案
            if (!prefs.getBoolean(KEY_RUNTIME_EVER, false)) {
                prefs.edit().putBoolean(KEY_RUNTIME_EVER, true).apply()
            }
            return 0
        }
        // 非 DO/PO：无权代为授予，仅记录待恢复项供 UI 引导。
        // 但"仅记一行日志"会让家长端完全无感（AUTH-04）：远程拍照/定位等功能静默失效，
        // 家长看到的却仍是"设备在线、策略已下发"。这里补一条与恢复闭环配对的专属告警。
        if (!admin.hasOwnerPrivileges()) {
            Logger.w(TAG) { "runtime permission(s) missing but no DO, guide via UI: $missing" }
            reportRuntimeRevocation(missing)
            return 0
        }
        var granted = 0
        for (perm in missing) {
            when (val r = admin.grantRuntimePermission(context.packageName, perm)) {
                is OpResult.Ok -> granted++
                is OpResult.Unsupported -> Logger.w(TAG) { "cannot auto-grant $perm (no DO)" }
                is OpResult.Failed -> Logger.w(TAG) { "auto-grant $perm failed: ${r.reason}" }
            }
        }
        if (granted > 0) {
            if (!prefs.getBoolean(KEY_RUNTIME_EVER, false)) {
                prefs.edit().putBoolean(KEY_RUNTIME_EVER, true).apply()
            }
            Logger.i(TAG) { "healed $granted revoked runtime permission(s) under Device Owner" }
        }
        return granted
    }

    /**
     * 非 DO 形态下运行期危险权限被回收的专属告警（AUTH-04）。
     *
     * ## 为什么不能复用 [raiseDroppedAlert]
     * 它固定上报 [RiskType.GUARD_PERMISSION_DROPPED]（无障碍 / 悬浮窗掉失的类别），
     * 而 [computeResolveCategories] 在 runtime 恢复时关闭的是 `PERMISSION_REVOKED`。
     * 若借用前者，家长端会留下一条永远消不掉的告警 —— 正是本类注释里记录过的历史事故。
     * 故上报类型由 [runtimeRevocationType] 单点定义，并由单测锁定"上报类别 == 恢复关闭类别"。
     *
     * ## 两条防误报约束
     * - 未绑定不报：此时装机授权流程本身还没走完，把"尚未勾选完"报成"被撤销"只会制造噪音；
     * - 6 小时去抖：与 a11y / overlay 同口径，避免 5 分钟一轮的自检反复刷同一条告警。
     *
     * ## 已知边界（如实记录，不擅自改动告警契约）
     * 本告警与「设备管理员被停用 / 控制权降级」共用 `PERMISSION_REVOKED` 类别
     * （`TamperDetector` 与 `PadGuardDeviceAdminReceiver` 也上报该类别），服务端按类别批量关闭。
     * 极端组合下（设备管理员被停用，恰逢某运行时权限在同一自检周期恢复）可能把那条更严重的
     * 告警一并关掉。这是既有 `computeResolveCategories` 的行为，并非本次引入；彻底隔离需新增
     * 专属告警类别并同步服务端白名单与家长端文案，属独立迭代，此处不扩大改动面。
     */
    private suspend fun reportRuntimeRevocation(missing: List<String>) {
        // 显式放行取消：runCatching 会把 CancellationException 一起吞掉，导致服务停不下来。
        val bound = try {
            authRepository.isBound.first()
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Logger.w(TAG, t) { "cannot read bind state, skip runtime revocation alert" }
            false
        }
        if (!bound) return

        val key = "runtime_revoked"
        val now = SystemClock.elapsedRealtime()
        if (!shouldRaiseAlert(now, prefs.getLong("alert_$key", 0L))) return
        // 只在上报成功后落去抖时间戳（同 [raiseDroppedAlert]），失败留待下一拍重试。
        val ever = prefs.getBoolean(KEY_RUNTIME_EVER, false)
        val names = missing.joinToString("、")
        val note = if (ever) {
            "系统自动回收了运行期危险权限（$names），定位 / 远程拍照等功能已静默失效。" +
                "这台设备不是设备所有者，App 无法自行补授，请到「系统设置 → 应用 → 护航成长 → 权限」重新授予。"
        } else {
            "运行期危险权限（$names）尚未授予，定位 / 远程拍照等功能不会生效。" +
                "请在「我的 → 权限引导」或「系统设置 → 应用 → 护航成长 → 权限」完成授权。"
        }
        runCatching {
            logRepository.raise(
                type = runtimeRevocationType(),
                level = RiskLevel.HIGH,
                detail = mapOf(
                    "permission" to "runtime",
                    "missing" to missing.joinToString(","),
                    "note" to note
                ),
                deviceId = authRepository.getDeviceId()
            )
        }.onSuccess { prefs.edit().putLong("alert_$key", now).apply() }
            .onFailure { Logger.w(TAG, it) { "failed to raise runtime permission revocation" } }
    }

    /**
     * 锁屏未能覆盖到前台时的如实上报（[RiskType.LOCK_SCREEN_UNPROTECTED]）。
     *
     * ## 为什么这条告警必须存在
     * 锁屏是家长端最常用的强干预。孩子端的锁屏呈现链（锁屏页 Activity + 全屏 intent 通知 +
     * 无障碍看门狗）走完 20 轮重试、约 6 秒后仍确认"锁屏页不在前台"时，此前**只写一行本地日志**：
     * 家长端回执仍是 SUCCESS、界面显示"已锁定"，而孩子手上什么都没发生。
     * 这种"以为锁了其实没锁"的空洞比任何漏拦都危险 —— 家长据此关掉了监管。
     *
     * 触发点刻意放在**呈现侧判定失败之后**，而不是周期体检：
     * 周期体检只知道"缺某个权限"，不知道"这次锁屏到底有没有铺上"，
     * 而且会与已有的「悬浮窗未授予」告警重复刷同一根因。
     *
     * ## 防误报的三道闸
     * 1. **只在屏幕亮着时调用**（由调用方保证）：息屏时不铺锁屏页是设计行为，否则会把屏幕点亮；
     * 2. **只在完整重试链耗尽后调用**：单次 `startActivity` 被 ROM 丢弃是常态，不代表锁不住；
     * 3. **30 分钟去抖**：锁定态会持续自愈巡查，不去抖会每轮刷一条。
     *
     * 与 [clearLockScreenUnprotected] 成对：上报类别与关闭类别同为
     * `LOCK_SCREEN_UNPROTECTED`，避免出现"上报一条、关闭另一条"而留下永久消不掉的告警
     * （本类注释里记录过同类事故）。
     */
    suspend fun reportLockScreenUnprotected(detail: String) {
        val key = "lock_unprotected"
        val now = SystemClock.elapsedRealtime()
        if (!shouldRaiseAlert(now, prefs.getLong("alert_$key", 0L), LOCK_ALERT_INTERVAL_MS)) return
        // 这条告警揭穿「以为锁了其实没锁」，绝不能因一次上报失败被去抖窗口吞掉 30 分钟。
        runCatching {
            logRepository.raise(
                type = RiskType.LOCK_SCREEN_UNPROTECTED,
                level = RiskLevel.HIGH,
                detail = mapOf("note" to detail),
                deviceId = authRepository.getDeviceId()
            )
        }.onSuccess { prefs.edit().putLong("alert_$key", now).apply() }
            .onFailure { Logger.w(TAG, it) { "failed to raise LOCK_SCREEN_UNPROTECTED" } }
    }

    /**
     * 锁屏确认覆盖到前台后，关闭此前上报的 [RiskType.LOCK_SCREEN_UNPROTECTED] 告警。
     *
     * 只在"本轮之前确实上报过"时由调用方触发（见 `LockScreenPresenter`），
     * 避免每次锁屏都发一次无意义的网络请求。
     */
    suspend fun clearLockScreenUnprotected() {
        // 显式放行取消：runCatching 会把 CancellationException 一并吞掉，导致服务停不下来。
        val bound = try {
            authRepository.isBound.first()
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Logger.w(TAG, t) { "cannot read bind state, skip lock-screen alert resolve" }
            return
        }
        if (!bound) return
        // ApiCaller.callIgnoringData 把 HTTP 400 转成 ApiResult.BizError **返回**而非抛异常，
        // 旧的 runCatching {}.onFailure {} 永远不触发：服务端白名单一旦漏掉本类别，
        // 关闭请求被拒 400，端上却完全静默，家长端「锁屏未能生效」告警永久挂着。
        // 显式检查返回值，跨端契约漂移不再无声。
        try {
            val result = remote.resolveActiveAlerts(listOf(RiskType.LOCK_SCREEN_UNPROTECTED.name))
            if (result !is ApiResult.Success) {
                Logger.w(TAG) { "resolve LOCK_SCREEN_UNPROTECTED rejected by server: $result" }
            }
        } catch (t: CancellationException) {
            throw t // 显式放行取消，别让服务停不下来
        } catch (t: Throwable) {
            Logger.w(TAG, t) { "failed to resolve LOCK_SCREEN_UNPROTECTED" }
        }
    }

    /**
     * 网页防护隧道重建成功后，关闭此前上报的 [RiskType.WEB_GUARD_DOWN] 告警。
     *
     * 与 `GuardService.watchdogWebGuard` 成对：该看门狗在隧道被系统拒绝（如 Android 12+
     * 后台 FGS 限制）时上报 HIGH 级 [RiskType.WEB_GUARD_DOWN]，隧道恢复（下个 tick 重建成功）
     * 即关掉，避免家长端"网页防护掉了"红点长期挂着、孩子端早已恢复却仍提示。
     * 只在"确实上报过"时由调用方触发（[webGuardDownRaised] 边沿），不每 tick 发无意义请求。
     */
    suspend fun clearWebGuardDown() {
        val bound = try {
            authRepository.isBound.first()
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Logger.w(TAG, t) { "cannot read bind state, skip web-guard-down alert resolve" }
            return
        }
        if (!bound) return
        try {
            val result = remote.resolveActiveAlerts(listOf(RiskType.WEB_GUARD_DOWN.name))
            if (result !is ApiResult.Success) {
                Logger.w(TAG) { "resolve WEB_GUARD_DOWN rejected by server: $result" }
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Logger.w(TAG, t) { "failed to resolve WEB_GUARD_DOWN" }
        }
    }

    /**
     * 操作密码经策略成功设置后，关闭此前上报的 [RiskType.GUARD_PASSWORD_NOT_SET] 告警。
     *
     * 与 `OperationGuardAccessibilityService.reportPasswordNotSet`（S11，24h 节流提醒）成对：
     * 无障碍拦截在位、守护基建齐备、密码闸却没启用，说明初始配置没做完。家长在控制端
     * 补设密码后，孩子端 [GuardService.applyGuardPassword] 落库成功即关掉该提示，
     * 避免"已设密码还提示未设"的反复误导。
     */
    suspend fun clearGuardPasswordNotSet() {
        val bound = try {
            authRepository.isBound.first()
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Logger.w(TAG, t) { "cannot read bind state, skip guard-password alert resolve" }
            return
        }
        if (!bound) return
        try {
            val result = remote.resolveActiveAlerts(listOf(RiskType.GUARD_PASSWORD_NOT_SET.name))
            if (result !is ApiResult.Success) {
                Logger.w(TAG) { "resolve GUARD_PASSWORD_NOT_SET rejected by server: $result" }
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Logger.w(TAG, t) { "failed to resolve GUARD_PASSWORD_NOT_SET" }
        }
    }

    /**
     * 运行期周期性复检：无障碍 / 悬浮窗被 ROM 静默回收后，DO 形态静默自愈，
     * 非 DO 诚实告警引导。与 [healRuntimePermissions] 周期对称，弥补"仅启动时自检"的盲区——
     * 进程长期存活期间若被系统解除无障碍绑定，能在下一周期自动拉回，无需等到下次重启。
     *
     * 自愈成功后一并闭环服务端告警：把"已恢复"的类别通知服务端关闭对应 ACTIVE 告警，
     * 避免家长端"修好了还在提示权限被撤销 / 无障碍已关闭"。
     */
    suspend fun recheckSpecialCapabilities() {
        if (!guardMasterSwitch.active.value) return
        if (!authRepository.isBound.first()) return
        val a11y = checkAccessibility()
        val a11yOk = a11y.ok
        val overlayOk = checkOverlay()
        // 与 check() 同口径：恢复即闭环本地引导通知（周期自检是 ROM 自动重绑后最常见的恢复观测点）
        if (a11yOk) clearA11yRebindGuideIfNeeded()
        // 运行期权限由 GuardService 的 permHeal 节拍先行自愈，这里仅观测"是否已恢复"并闭环告警
        checkOtherSpecialCapabilities()
        val recovered = computeRecovered(a11yOk, overlayOk, a11y.selfHealed)
        resolveIfNeeded(recovered)
    }

    /**
     * 无障碍恢复时的**本地**引导闭环（边沿触发）。
     *
     * [com.padguard.child.guard.A11yRebindNotifier] 发的是 `setOngoing(true)` 常驻通知，
     * 原先只有 [com.padguard.child.guard.A11yRebindGuideActivity] 被打开时才撤。可家长更常见的
     * 做法是直接在系统设置里重新勾上无障碍（或 ROM 自己重绑成功），引导页从未被打开 ——
     * 于是通知栏一直挂着「操作闸门已失效、应用管控被绕过」，且划不掉，真机表现就是
     * 「权限明明开了还显示未开 / 警告一直不消失」。
     *
     * 同时复位 [KEY_A11Y_GUIDE_LAST]：本地引导用的是 30 分钟去抖窗，若不复位，
     * 恢复后短期内再次真掉线会被这个旧时间戳吞掉，反而"该提示时不提示"。
     * 只在 `isGuideActive()` 为真时动作，避免每轮自检都写盘。
     */
    private fun clearA11yRebindGuideIfNeeded() {
        if (!a11yGuideNotifier.isGuideActive()) return
        a11yGuideNotifier.cancel()
        prefs.edit().remove(KEY_A11Y_GUIDE_LAST).apply()
        Logger.i(TAG) { "a11y recovered: rebind guide dismissed, local throttle reset" }
    }

    /**
     * 计算本次应通知服务端关闭的告警类别（恢复即关闭闭环）。
     *
     * ## 边沿触发，避免每轮自检产生无意义流量
     * 维护一份"当前缺失能力集合"持久化到 SharedPreferences：
     * - 本次 `now` = 当前确为缺失的能力（a11y / overlay / runtime 任一项未满足）；
     * - 本次 `recovered` = `prev - now`（上一轮缺失、本轮已恢复的能力），仅这些触发关闭；
     * - 写回 `now` 作为下一轮的 `prev`。
     * 因此一次"掉失 → 恢复"只会触发一次关闭，不会在能力持续恢复态下反复上报。
     *
     * ## 关键安全约束
     * `GUARD_PERMISSION_DROPPED` 同时覆盖无障碍与悬浮窗两种掉失，故仅当两者都已恢复
     * （`a11yOk && overlayOk`）才关闭它，避免把"悬浮窗仍缺失"的有效告警一并抹掉。
     */
    private fun computeRecovered(
        a11yOk: Boolean,
        overlayOk: Boolean,
        a11ySelfHealed: Boolean = false
    ): Set<String> {
        val prev = readDropped()
        val now = mutableSetOf<String>()
        if (!a11yOk) now += "a11y"
        if (!overlayOk) now += "overlay"
        if (missingCriticalRuntimePermissions().isNotEmpty()) {
            now += "runtime"
        }
        // 其余特殊权限（曾授予又掉）纳入闭环：恢复即关闭 GUARD_PERMISSION_DROPPED。
        // 只纳入"曾授予"的项，避免从未授予的项长期停在 now 里、卡住其它权限告警的关闭。
        val hasOwner = admin.hasOwnerPrivileges()
        for (perm in SPECIAL_PERMS_TO_MONITOR) {
            if (!perm.applies(context, hasOwner)) continue
            if (!perm.isGranted(context) && prefs.getBoolean(everKeyOf(perm.key), false)) now += perm.key
        }
        if (notifyPrefs.isEnabled() &&
            !NotifyPermission.isNotificationListenerEnabled(context) &&
            prefs.getBoolean(everKeyOf(NOTIFICATION_LISTENER_KEY), false)
        ) {
            now += NOTIFICATION_LISTENER_KEY
        }
        val recovered = computeResolveCategories(a11yOk, overlayOk, prev, now).toMutableSet()
        // 本轮静默自愈成功 → 顺手关闭 ACCESSIBILITY_DISABLED。
        //
        // 该类别可能来自 [com.padguard.core.engine.guard.TamperDetector]（按"开关被关掉"
        // 即时上报一次，与本地自检的 PREV/NOW 状态机互不共享）。它只能由这里的恢复边沿关闭，
        // 而静默自愈是"同一轮内从关到开"，prev 永远看不到 a11y → 差分恒空 → 红点永挂。
        // 幂等：服务端对已关闭的告警返回 0 行，重复请求无副作用。
        if (a11ySelfHealed) recovered += "ACCESSIBILITY_DISABLED"
        writeDropped(now)
        return recovered
    }

    private fun readDropped(): MutableSet<String> =
        (prefs.getStringSet(KEY_DROPPED, emptySet()) ?: emptySet()).toMutableSet()

    private fun writeDropped(set: Set<String>) {
        prefs.edit().putStringSet(KEY_DROPPED, set).apply()
    }

    /**
     * 把自愈恢复的类别通知服务端关闭对应 ACTIVE 告警（恢复即关闭闭环）。
     *
     * 仅在确有恢复时发起网络请求；集合为空直接跳过，避免每轮自检都产生无意义流量。
     * 调用失败仅记日志、不影响本地自愈结果——告警关闭是"锦上添花"，不应反噬主链路。
     */
    private suspend fun resolveIfNeeded(recovered: Set<String>) {
        if (recovered.isEmpty()) return
        if (!authRepository.isBound.first()) return
        // 与 clearLockScreenUnprotected 同口径：400 是 BizError 返回而非异常，
        // 只写 onFailure 会让「服务端白名单漏类别」这类跨端断裂完全静默。
        try {
            val result = remote.resolveActiveAlerts(recovered.toList())
            if (result !is ApiResult.Success) {
                Logger.w(TAG) { "resolve recovered alerts rejected by server: $recovered -> $result" }
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Logger.w(TAG, t) { "failed to notify server to close recovered alerts: $recovered" }
        }
    }

    private suspend fun raiseDroppedAlert(name: String, ever: Boolean, key: String) {
        val now = SystemClock.elapsedRealtime()
        if (!shouldRaiseAlert(now, prefs.getLong("alert_$key", 0L))) {
            return // 去抖，避免进程反复重启刷同一条告警
        }
        // 去抖时间戳统一在下方 onSuccess 里落盘：只在确实上报成功后才起算窗口。
        // 旧实现在这里无条件先写：一旦 logRepository.raise 抛异常（DB / 序列化 / 协程取消），
        // 接下来 6 小时都不会再重试，这条高危告警就永久丢了 —— 比误报更危险。
        val detail = if (ever) {
            "权限被系统静默撤销（曾在设置里授予，切换其它权限触发应用重启后被清空）：$name。" +
                "请到系统设置重新授予，否则管控/看屏将失效。" + a11yCauseHint(key)
        } else {
            "$name 尚未授予。请在系统设置 → 无障碍 / 应用权限中授予护航成长相应权限，" +
                "否则管控/看屏将失效。" + a11yCauseHint(key)
        }
        runCatching {
            logRepository.raise(
                type = RiskType.GUARD_PERMISSION_DROPPED,
                level = RiskLevel.HIGH,
                detail = mapOf("permission" to key, "note" to detail),
                deviceId = authRepository.getDeviceId()
            )
        }.onSuccess { prefs.edit().putLong("alert_$key", now).apply() }
            .onFailure { Logger.w(TAG, it) { "failed to raise GUARD_PERMISSION_DROPPED" } }
    }

    /**
     * 无障碍类掉失告警的成因提示（附在 note 尾部）。
     *
     * ## 为什么只对 a11y 追加（2026-11-16 EMUI 一键清理事故复盘）
     * 孩子在多任务界面点「一键清理/清理加速」后，EMUI 会停用无障碍服务，家长端只看到
     * "权限被系统静默撤销"，无从下手。追加上述成因与两条预防动作（重新开启 +
     * 把本应用加入启动管理白名单），家长就能一次到位修好并防复发；其它权限的掉失
     * 没有这个高频成因，维持原文案不动。
     */
    private fun a11yCauseHint(key: String): String =
        if (key == "accessibility" || key == "a11y_detached") {
            "常见成因：孩子用多任务『一键清理/清理加速』或省电策略停用了无障碍。" +
                "重新开启后，建议在系统『设置→应用→护航成长→启动管理（后台活动）』" +
                "允许自启动与后台运行，防止再次被清理。"
        } else {
            ""
        }

    /**
     * 本地即时引导：操作闸门（无障碍服务）失效时，由系统代拉引导页告知脱管状态并给出重开启入口。
     *
     * ## 为什么是独立的节流窗口（不与告警共用 6h）
     * 服务端告警面向家长、6 小时去抖足够；但**设备本地**的"重新开启"引导需要更频繁地出现，
     * 才能让每次掉线都被肉眼可见地顶到前台（尤其家长随手拿设备时）。取 30 分钟：
     * 既保证反复掉线持续可见，又避免进程长期存活期间每轮（5 分钟）自检都弹一次造成骚扰。
     * 复用 [shouldRaiseAlert] 纯判定；时钟域为单调钟，不存在回拨借窗反复刷的问题。
     */
    private fun triggerA11yRebindGuide() {
        val now = SystemClock.elapsedRealtime()
        if (!shouldRaiseAlert(now, prefs.getLong(KEY_A11Y_GUIDE_LAST, 0L), GUIDE_INTERVAL_MS)) return
        // [a11yGuideNotifier] 的 post() 明确返回「是否真的发了出去」：Android 14 全屏 intent
        // 特殊应用访问未授予、或 notify 抛异常时返回 false，且不置 notifier 的活跃态。
        // 旧实现在调用前无条件写 30 分钟节流时间戳，一次失败就把设备端最关键的
        // 「操作闸门已失效」引导静音半小时，家长拿设备时全程看不到 —— 故只在成功后落盘。
        val posted = runCatching { a11yGuideNotifier.post() }
            .onFailure { Logger.w(TAG, it) { "failed to post a11y rebind guide" } }
            .getOrDefault(false)
        if (posted) prefs.edit().putLong(KEY_A11Y_GUIDE_LAST, now).apply()
    }

    companion object {
        private const val TAG = "PermissionHealth"
        private const val PREFS_NAME = "padguard_perm_health"

        /**
         * 纳入 5 分钟快检、此前被漏掉的特殊权限（见 [checkOtherSpecialCapabilities]）。
         * 它们没有"曾授予"之外的检测盲区，掉失即静默失效，是"权限永不丢失"的最大漏洞。
         *
         * ## 必须与 [SpecialPermission] 目录全量对齐
         * 判据：每一项 `detectable && requiredForReauth` 的枚举项，要么在本集合里，
         * 要么有自己独立的 `checkXxx()`（[SpecialPermission.OVERLAY] 与
         * [SpecialPermission.ACCESSIBILITY] 属于后者，重复加入会双重告警）。
         * [SpecialPermission.OEM_BACKGROUND] 两项布尔都为 false，平台无查询接口，故意排除。
         *
         * 2026-11-15 补上 [SpecialPermission.INSTALL_PACKAGES]：它此前是唯一「目录里有、
         * 自检里没有」的项，非 DO 设备上家长开过又被系统清掉后，要等 30 分钟一轮的
         * AuthorizationKeeper 才发现，且 [SPECIAL_DROPPED_KEYS] 由本集合派生，
         * 导致 `GUARD_PERMISSION_DROPPED` 的恢复闭环也少一项。
         * 循环里的 `perm.applies(context, hasOwner)` 会在 DO/PO 下自动剔除它，不会误报。
         *
         * 契约测试 `PermissionSingleSourceContractTest` 锁死此覆盖关系，
         * 以后往目录加新权限若忘了同步这里会直接红。
         */
        private val SPECIAL_PERMS_TO_MONITOR = listOf(
            SpecialPermission.USAGE_STATS,
            SpecialPermission.INSTALL_PACKAGES,
            SpecialPermission.BATTERY_OPTIMIZATION,
            SpecialPermission.EXACT_ALARM,
            SpecialPermission.APP_NOTIFICATIONS
        )

        /** 通知代收授权的掉失键（仅家长开启该功能后才纳入闭环） */
        private const val NOTIFICATION_LISTENER_KEY = "notificationListener"

        /** 所有"特殊权限"掉失共享 [RiskType.GUARD_PERMISSION_DROPPED] 类别，闭环时统一判定是否仍有缺失 */
        private val SPECIAL_DROPPED_KEYS = setOf("a11y", "overlay", NOTIFICATION_LISTENER_KEY) +
            SPECIAL_PERMS_TO_MONITOR.map { it.key }

        /** 其余特殊权限掉失观察窗：与 a11y/overlay 同口径，挡 ROM 瞬时抖动 */
        private const val SPECIAL_DISABLED_TOLERANCE_MS = 90 * 1000L

        private const val KEY_A11Y_EVER = "a11y_ever"
        private const val KEY_OVERLAY_EVER = "overlay_ever"
        /** 当前缺失能力集合（"a11y"/"overlay"/"runtime"），边沿触发恢复即关闭告警的依据 */
        private const val KEY_DROPPED = "dropped_caps"

        /** 是否曾完整持有全部关键运行时权限：区分"从未授予"与"被系统静默回收"的告警文案 */
        private const val KEY_RUNTIME_EVER = "runtime_ever"

        /** 本地引导弹窗最近一次触发时刻；与告警去抖独立，窗口更短以保证设备端可见 */
        private const val KEY_A11Y_GUIDE_LAST = "a11y_guide_last"
        private const val GUIDE_INTERVAL_MS = 30 * 60 * 1000L

        /** 「无障碍未启用」首次被观察到的时刻；确认已启用即清除（与 detached 窗口各自独立） */
        private const val KEY_A11Y_DISABLED_SINCE = "a11y_disabled_since"

        /** 「悬浮窗未授予」首次被观察到的时刻；与 [KEY_A11Y_DISABLED_SINCE] 各自独立，互不串扰 */
        private const val KEY_OVERLAY_DISABLED_SINCE = "overlay_disabled_since"

        /**
         * 悬浮窗掉失观察窗：与 [A11Y_DISABLED_TOLERANCE_MS] 取同一时长，
         * 让「无障碍 / 悬浮窗 / 开着却没连上」三条判定口径一致地挡住 ROM 瞬时抖动。
         */
        private const val OVERLAY_DISABLED_TOLERANCE_MS = 90 * 1000L

        /**
         * 「无障碍未启用」的容忍窗口。
         *
         * 比 [DETACHED_TOLERANCE_MS]（10 分钟）短得多：开关真被关掉是需要家长立刻处理的
         * 脱管状态，不能等 10 分钟；但 90 秒足以覆盖 ROM 强制重启应用、设置串短暂清空、
         * 系统自动重绑这一整段抖动。配合约 5 分钟一轮的周期自检，等价于"连续两拍都
         * 读到未启用才告警"，既不误报也不明显延迟真脱管。
         */
        private const val A11Y_DISABLED_TOLERANCE_MS = 90 * 1000L

        /**
         * 告警去抖（纯函数便于单测）：距上次上报不足 [ALERT_INTERVAL_MS] 则跳过。
         * 只读不写时间戳，写入由调用方在**确实要上报**时完成。
         */
        @JvmStatic
        internal fun shouldRaiseAlert(
            nowMs: Long,
            lastMs: Long,
            intervalMs: Long = ALERT_INTERVAL_MS
        ): Boolean =
            // 时钟域：单调钟（elapsedRealtime）+ 高水位。
            // lastMs > nowMs 只可能来自两种脏值：升级前落盘的旧墙钟时间戳，
            // 或设备重启后 elapsedRealtime 归零而旧值仍在 —— 一律按「早已过期」放行，
            // 绝不让旧值把去抖窗口延长成对高危告警的永久静音（这正是旧墙钟实现的缺陷）。
            // 代价是切换时钟域后的第一条告警会立即发出（最多一条，方向安全）。
            lastMs <= 0L || lastMs > nowMs || nowMs - lastMs >= intervalMs

        /**
         * 「无障碍未启用」是否已**持续**超过容忍窗口（纯函数，便于 JVM 单测）。
         *
         * 与 [shouldRaiseAlert] 的脏值方向**刻意相反**：那里脏值要"放行"以免把高危告警
         * 永久静音；这里脏值（未记录 / 旧墙钟值 / 重启后 elapsedRealtime 归零）必须
         * 重新起观察窗，否则会跳过防误报窗口、第一拍就弹出全屏警告页。
         *
         * @return true = 已确认持续未启用，可以告警；false = 仍在观察期或需重新起窗
         */
        @JvmStatic
        internal fun a11yDisabledConfirmed(nowMs: Long, sinceMs: Long, toleranceMs: Long): Boolean =
            disabledConfirmed(nowMs, sinceMs, toleranceMs)

        /**
         * 「某项能力已**持续**未就绪超过容忍窗口」的通用纯判定（无障碍与悬浮窗共用）。
         *
         * 抽出单份实现是为了避免两条自检各自演化出不同的脏值/边界口径：脏值（未记录、
         * 旧墙钟值、重启后 elapsedRealtime 归零）必须重新起观察窗，否则会跳过防误报窗口、
         * 第一拍就弹告警。单测同时锁定 [a11yDisabledConfirmed] 委托本函数这一不变量。
         *
         * @return true = 已确认持续未就绪，可以告警；false = 仍在观察期或需重新起窗
         */
        @JvmStatic
        internal fun disabledConfirmed(nowMs: Long, sinceMs: Long, toleranceMs: Long): Boolean =
            sinceMs in 1..nowMs && nowMs - sinceMs >= toleranceMs

        /**
         * runtime 权限掉失告警的类型，**单点定义**。
         *
         * 其 `name` 必须严格等于 [computeResolveCategories] 在 runtime 恢复时关闭的类别
         * （`"runtime" in recoveredCaps -> "PERMISSION_REVOKED"`），否则家长端会留下一条
         * 再也关不掉的告警。`PermissionHealthCheckerTest` 用断言锁定这条配对契约。
         */
        @JvmStatic
        internal fun runtimeRevocationType(): RiskType = RiskType.PERMISSION_REVOKED

        /**
         * 「锁屏没覆盖上」是否该上报（纯函数便于单测）。
         *
         * 这是本告警唯一的防误报总闸，两条规则各自挡掉一类误报：
         * - **息屏不报**：息屏时 [com.padguard.child.ui.lock.LockScreenPresenter] 刻意不拉锁屏页
         *   （否则会把屏幕点亮），此时"锁屏页不可见"是设计行为。漏掉这条，晚上家长锁屏
         *   会稳定收到一条"锁屏未能生效"的假告警 —— 告警一旦不可信，真出事时也没人看。
         * - **已确认覆盖不报**：重试链期间页面可能恰好铺上，对账时以"是否真的占满前台"为准
         *   （分屏/浮窗形态不算覆盖）。
         */
        @JvmStatic
        internal fun shouldReportLockUnprotected(screenOn: Boolean, coverageConfirmed: Boolean): Boolean =
            screenOn && !coverageConfirmed

        /** "开关开着但服务没连上"的起始时刻；连上或确认关闭即清除 */
        private const val KEY_DETACHED_SINCE = "a11y_detached_since"
        private const val ALERT_INTERVAL_MS = 6 * 60 * 60 * 1000L

        /**
         * 锁屏未能覆盖的上报间隔。
         *
         * 比 [ALERT_INTERVAL_MS] 短得多：锁定态下自愈巡查每轮都会复查，6 小时去抖意味着
         * 家长可能一整天都收不到"这次锁屏没生效"的提示。但也不能短于一次锁屏会话的典型时长，
         * 否则同一次锁定会反复上报。30 分钟与本地无障碍引导同口径。
         */
        private const val LOCK_ALERT_INTERVAL_MS = 30 * 60 * 1000L

        /**
         * 「开着却没连上」的容忍窗口（DO 形态）。
         *
         * 系统重绑通常秒级完成，DO 的强制重绑也有几秒空窗且有 10 分钟限频；
         * 给足 10 分钟再判定"真的不可用"，避免把一次正常的进程重建误报成"无障碍已关闭"。
         */
        private const val DETACHED_TOLERANCE_MS = 10 * 60 * 1000L

        /**
         * 「开着却没连上」的容忍窗口（非 DO 形态）。
         *
         * 非 DO 没有任何静默自愈手段：系统若会重绑，早在进程被拉回的秒级就完成了；
         * 进程活着、服务却迟迟不连（EMUI 一键清理阻断重绑的典型形态）= 永远不会自愈。
         * 与「未启用」「悬浮窗」观察窗同口径取 90 秒，把"静默失效可被家长感知"的
         * 时延从最坏 ~12 分钟压到 ~3.5 分钟（含 [GuardA11y.ALIVE_GRACE_MS] 心跳窗）。
         */
        private const val DETACHED_TOLERANCE_NON_DO_MS = 90 * 1000L

        /**
         * 「开着却没连上」容忍窗口的取值（纯函数，便于单测）：
         * DO 有强制重绑与 10 分钟限频，保持长窗；非 DO 无自愈手段，收敛到 90 秒短窗。
         */
        @JvmStatic
        internal fun detachedToleranceMs(isDeviceOwner: Boolean): Long =
            if (isDeviceOwner) DETACHED_TOLERANCE_MS else DETACHED_TOLERANCE_NON_DO_MS

        /**
         * 「a11y 观察期」的三路合成判定（纯函数，便于单测）：
         * 疑似态（进程内内存标志）/ 「未启用」观察窗起算过 / 「没连上」观察窗起算过，
         * 任一为真即应让 GuardService 切换到 2 分钟快检。
         * 全假（健康且无计时中的观察窗）才回 5 分钟常规节奏。
         */
        @JvmStatic
        internal fun a11yObservationActive(
            suspect: Boolean,
            a11yDisabledSince: Long,
            detachedSince: Long
        ): Boolean = suspect || a11yDisabledSince != 0L || detachedSince != 0L

        /**
         * 是否需上报告警（纯判定，便于单测）。DO 形态下若静默自愈后已恢复启用则不报，
         * 遏止家长端"无障碍服务已关闭"误报；非 DO 或自愈未成功则照常上报。
         */
        @JvmStatic
        /**
         * 纯判定：无障碍「未启用」自检拍里是否仍需上报告警。
         * silentHealCapable = DO 或装机时 adb 授权了 WRITE_SECURE_SETTINGS（[AccessibilityCapabilityHealer.canSilentHeal]）：
         * 具备静默自愈能力的设备上，自愈后已恢复启用（a11yEnabledAfterHeal=true）则不再报，
         * 家长无需到场；自愈失败或本来就无静默通道的设备如实上报。
         */
        internal fun needsA11yDroppedAlert(a11yEnabledAfterHeal: Boolean, silentHealCapable: Boolean): Boolean =
            !(silentHealCapable && a11yEnabledAfterHeal)

        /**
         * 纯判定：给定当前与历史缺失能力集合，算出应关闭的告警类别集合。
         * 抽成纯函数便于单测，不依赖 SharedPreferences / 设备能力。
         *
         * 边沿触发：`recoveredCaps = prevDropped - nowDropped`，只在对缺失能力"从掉失到恢复"的
         * 那一拍才关闭对应告警。`GUARD_PERMISSION_DROPPED` 同覆盖无障碍与悬浮窗，故仅当两者都恢复
         * （`a11yOk && overlayOk`）才关，避免误关"悬浮窗仍缺失"的有效告警。
         */
        @JvmStatic
        internal fun computeResolveCategories(
            a11yOk: Boolean,
            overlayOk: Boolean,
            prevDropped: Set<String>,
            nowDropped: Set<String>
        ): Set<String> {
        val recoveredCaps = prevDropped - nowDropped
        val set = mutableSetOf<String>()
        if ("a11y" in recoveredCaps) set += "ACCESSIBILITY_DISABLED"
        // usageStats 恢复要把两条告警一起关：TamperDetector 上报的是独立的
        // USAGE_ACCESS_LOST，PermissionHealthChecker 上报的是共享的 GUARD_PERMISSION_DROPPED。
        // 旧实现只关后者，家长端会永久残留「使用情况权限被关闭」，且每次再掉失又叠一条。
        if ("usageStats" in recoveredCaps) set += "USAGE_ACCESS_LOST"
        // GUARD_PERMISSION_DROPPED 覆盖全部"特殊权限"掉失（a11y / overlay / usageStats /
        // 电池优化 / 精确闹钟 / 通知 / 通知代收）。仅当**当前没有任何特殊权限仍处于掉失态**时才关闭，
        // 避免把仍缺失项的有效告警一并抹掉（例如悬浮窗仍缺失时，a11y 恢复不应误关共享告警）。
        val specialKeys = SPECIAL_DROPPED_KEYS
        if (specialKeys.intersect(nowDropped).isEmpty() &&
            specialKeys.intersect(prevDropped).isNotEmpty()
        ) {
            set += "GUARD_PERMISSION_DROPPED"
        }
        if ("runtime" in recoveredCaps) set += "PERMISSION_REVOKED"
        return set
        }
    }
}
