package com.padguard.child.guard

import com.padguard.core.data.model.policy.AppListMode

/**
 * 应用管控决策的**纯函数层**（无 Android 依赖，可脱离设备直接单测）。
 *
 * ## 为什么需要这一层（根因重构的一部分）
 * "应用管控形同虚设"除了非 DO 无真禁能力之外，还有一处结构性缺陷：
 * **判定逻辑散落在三处、口径互不相同、难以验证**——
 *   - [AppGuardPolicyStore] 读 DataStore 缓存做名单判定；
 *   - [com.padguard.child.KidHomeViewModel.buildAllowedApps] 在桌面再过滤一遍；
 *   - [com.padguard.child.guard.OperationGuardAccessibilityService.decide] 做软拦截判定。
 * 三处各自实现"该包是否受控 / 是否可启动"，极易漂移，且无法单测。
 *
 * 这里把"给定一份策略快照，判断某包能否启动 / 是否受控 / 打开是否要门禁"收敛为**单一纯函数**，
 * 三处共用同一份口径，并可直接用假数据单测，从根上消除"一边免密一边要密""桌面显示但点开被拦"这类分叉。
 *
 * 防砖（`isSystemCritical`）与"取消授权"（`isHidden`）是两条外部事实，作为参数注入，
 * 不在此层硬编码，保证与 [AppGuardPolicyStore] 的防砖名单 / 授权账本保持唯一真相。
 */
class AppGuardDecisions(
    private val snapshot: Snapshot,
    private val isSystemCritical: (String) -> Boolean,
    private val isHidden: (String) -> Boolean
) {

    /** 是否已授权（白名单内 / 黑名单外）。已授权应用一律放行，是"授权可用"的底线。 */
    fun isAuthorized(pkg: String): Boolean = when (snapshot.mode) {
        AppListMode.WHITELIST -> pkg in snapshot.whitelist
        AppListMode.BLACKLIST -> pkg !in snapshot.blacklist
    }

    /**
     * 家长是否"取消授权"（hidden）——无条件底线语义。
     * 不依赖「打开应用也要密码」开关，也不因未设家长密码而漏放（最严）。
     */
    fun isHiddenControlled(pkg: String): Boolean =
        if (isSystemCritical(pkg)) false else isHidden(pkg)

    /**
     * 该包是否受管控（需要密码门禁）。
     *
     * 判定口径（与桌面 / 软拦截完全一致）：
     * 1. 已授权 → 放行；
     * 2. 取消授权 → 受控（最严）；
     * 3. 精细规则（systemAppRules）ALLOW/BLOCK 优先于名单；
     * 4. 白名单外 / 黑名单内 → 受控。
     */
    fun isControlled(pkg: String): Boolean {
        if (isAuthorized(pkg)) return false
        if (isHiddenControlled(pkg)) return true
        snapshot.systemAppRules[pkg]?.let { return it == "BLOCK" }
        return when (snapshot.mode) {
            AppListMode.WHITELIST -> pkg !in snapshot.whitelist
            AppListMode.BLACKLIST -> pkg in snapshot.blacklist
        }
    }

    /**
     * 打开该应用是否需要门禁（孩子桌面点开 / 无障碍兜底共用的判定）。
     *
     * 与 [com.padguard.child.guard.OperationGuardAccessibilityService.decide] 完全对齐，
     * 确保「桌面点开」与「最近任务 / 通知点开」行为一致，不会一边免密一边要密：
     * - 系统关键包（防砖名单）→ 不拦；
     * - 本次针对该包验证通过（appAccessGranted，只放这一个包）→ 放行；
     * - 处于解锁窗口内（unlocked，家长刚输过密码）→ 放行，含取消授权的应用；
     * - 取消授权（hidden）→ 未解锁时一律经过门禁（最严，不依赖任何开关）；
     * - 其余受管控应用 → 依赖「打开应用也要密码」开关，否则不拦。
     */
    fun needsLaunchGuard(
        pkg: String,
        appAccessGranted: Boolean,
        unlocked: Boolean
    ): Boolean {
        if (isSystemCritical(pkg)) return false
        if (appAccessGranted) return false
        if (unlocked) return false
        if (isHiddenControlled(pkg)) return true
        if (!snapshot.requirePasswordOnLaunch) return false
        return isControlled(pkg)
    }

    /**
     * 孩子端桌面是否应展示并允许启动该应用。
     *
     * 这是"应用管控"的**权威入口判定**：被取消授权 / 名单外的应用**在桌面直接不出现**，
     * 从源头减少触达机会，而不是"点了再弹框顶回"——
     * 后者在非 DO 设备上是可被最近任务 / 系统桌面绕过的（这正是"应用管控照样能打开"的成因之一）。
     *
     * - 系统关键包（防砖名单）→ 永远可启动，绝不隐藏；
     * - 取消授权 / hidden → 不可启动（必须隐藏，否则孩子能看到图标）；
     * - 白 / 黑名单口径 → 名单外 / 黑名单内不可启动。
     *
     * 注意：受管控但**已授权**的应用（"打开应用也要密码"那种）此处仍可见，
     * 点开时由 [needsLaunchGuard] 弹门禁——它们不该被藏起来，藏起来反而破坏"白名单放行"语义。
     */
    fun isLaunchableByChild(pkg: String): Boolean {
        if (isSystemCritical(pkg)) return true
        if (isHiddenControlled(pkg)) return false
        return isAuthorized(pkg)
    }

    /** 策略快照（纯数据，可单测）。与 [AppGuardPolicyStore] 同步写入的内容保持一致。 */
    data class Snapshot(
        val mode: AppListMode,
        val whitelist: Set<String>,
        val blacklist: Set<String>,
        /** 系统应用精细规则：包名 -> ALLOW / BLOCK（优先于名单） */
        val systemAppRules: Map<String, String>,
        /** 打开受管控应用是否要密码（家长端开关，默认关 = 只拦安装/卸载） */
        val requirePasswordOnLaunch: Boolean,
        /** 无 DO 降级：安装确认页是否要密码（家长端开关，默认 true = 要） */
        val requirePasswordOnInstall: Boolean,
        /** 无 DO 降级：卸载确认页是否要密码（家长端开关，默认 true = 要） */
        val requirePasswordOnUninstall: Boolean
    )
}
