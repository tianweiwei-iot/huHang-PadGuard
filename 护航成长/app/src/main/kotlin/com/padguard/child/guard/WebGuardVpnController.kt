package com.padguard.child.guard

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.VpnService
import android.os.UserManager
import com.padguard.core.common.Logger
import com.padguard.core.data.model.policy.WebFilterMode
import com.padguard.core.data.model.policy.WebPolicy
import com.padguard.core.engine.admin.DeviceAdminBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 上网防护（本地 VPN）通道的筹备、启停与**策略对账**。
 *
 * ## 它在整体链路里的位置
 * [WebGuardVpnService] 是「上网管控」域名级拦截的执行体；本控制器负责四件事：
 * 1. 判定「当前策略是否需要 VPN」（[requiredBy]）；
 * 2. 探测 VPN 是否已被系统授权（[VpnService.prepare]）；
 * 3. 提供拉起系统 VPN 确认框的 Intent（首次授权 / 事后补授权）；
 * 4. **幂等对账**（[sync]）：策略每落地一次就调用一次，需要就拉起来、不需要就停掉。
 *
 * ## 第九轮修复的关键缺口
 * 之前本类只有 `start()/stop()` 两个方法，而**全工程没有任何调用点**：
 * 家长端能配的「黑名单 / 白名单 / 违规分类 / 安全搜索」在设备侧完全没有执行通道，
 * 而且不报错（静默失效）。现在由 [com.padguard.child.service.GuardService] 在每次策略
 * 落地与周期自检时调用 [sync]，链路才真正闭合。
 *
 * ## 关键安全约束（与整体「如实降级 / fail-closed」一致）
 * - **绝不自动开启**：VPN 仅当策略确实需要、且系统已授权时才启动；未授权一律跳过并记录，
 *   由 UI 引导家长补授权（[com.padguard.child.consent.OneTimeConsentScreen] /
 *   [com.padguard.child.ui.profile.PermissionGuideScreen]）；
 * - **establish 失败即自停**：[WebGuardVpnService] 内部任何异常都会 `stopSelf`，
 *   绝不静默留下一个半死的 VPN（那会卡死 DNS）；
 * - **仅主机路由 DNS、不加默认路由**：最坏是「过滤失效 / 短暂无网」，不会把整台平板堵死；
 * - **DO/PO 机型走 always-on 授权**：设备所有者可代用户授予 VPN 权限（系统不弹窗），
 *   并用 `DISALLOW_CONFIG_VPN` 锁住 VPN 设置，孩子无法自行关闭 —— 这是唯一能做到
 *   「开了就关不掉」的形态；非 DO 机型只能依赖用户授权，用户随时可在系统设置里撤销，
 *   撤销后 [sync] 会记录 NEED_CONSENT，绝不假装仍在保护。
 */
@Singleton
class WebGuardVpnController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val admin: DeviceAdminBridge
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 当前是否「应当运行」上网防护（由策略推导，不是用户开关）。 */
    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        if (prefs.getBoolean(KEY_ENABLED, false) == enabled) return
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        Logger.i(TAG) { "web guard vpn enabled=$enabled" }
    }

    /** VPN 是否已被系统授权（用户点过确认框，或 DO 已设为 always-on）。 */
    fun isPrepared(): Boolean = runCatching { VpnService.prepare(context) == null }.getOrDefault(false)

    /** 拉起系统 VPN 确认框所需的 Intent；已授权时返回 null。 */
    fun prepareIntent(): Intent? = runCatching { VpnService.prepare(context) }.getOrNull()

    /**
     * 当前 VPN 通道是否在运行。
     *
     * 双判据：进程内标记（同进程，最准）+ 心跳（跨进程/被系统杀后重建时的兜底）。
     * 单用心跳会误判——心跳只在建立时写一次，2 分钟后即便服务活得好好的也判为"没运行"，
     * 于是每轮对账都重复 start（服务侧虽幂等，但会白起前台服务、刷通知）。
     */
    fun isActive(): Boolean {
        if (WebGuardVpnService.running) return true
        val last = prefs.getLong(KEY_ACTIVE_AT, 0L)
        if (last == 0L) return false
        return System.currentTimeMillis() - last <= ACTIVE_GRACE_MS
    }

    /** 由服务在建立/断开时调用，记录心跳。 */
    internal fun markActive(active: Boolean) {
        runCatching {
            prefs.edit().putLong(KEY_ACTIVE_AT, if (active) System.currentTimeMillis() else 0L).apply()
        }
    }

    /**
     * 该策略是否需要 VPN 通道。
     *
     * ## 为什么不能只看 mode
     * 服务端组包时 `web.mode` 恒为 `BLACKLIST`（黑名单是唯一落地形态，白名单语义由
     * `whitelist` 字段承载）。若按「mode != OFF 就起通道」，**每台设备都会常驻一个 VPN**，
     * 哪怕家长一条规则都没配 —— 白耗电、常驻通知、还多一个可被质疑的流量通道。
     *
     * 因此按**实际是否有规则**判定：
     * - 违规分类（服务端有默认屏蔽项）或安全搜索 → 有内容要拦；
     * - 黑名单模式 → 黑名单非空才算有规则；
     * - 白名单模式 → 本身就是"非名单一律拦"，空名单也必须有通道，否则等于没开。
     */
    fun requiredBy(policy: WebPolicy): Boolean {
        if (policy.blockedCategories.isNotEmpty() || policy.safeSearch) return true
        return when (policy.mode) {
            WebFilterMode.OFF -> false
            WebFilterMode.BLACKLIST -> policy.blacklist.isNotEmpty()
            WebFilterMode.WHITELIST -> true
        }
    }

    /**
     * 策略 → 通道的**幂等对账**（唯一入口，可任意重复调用）。
     *
     * @return 本次对账后的状态，供调用方写日志/上报，不参与业务判定。
     */
    fun sync(policy: WebPolicy): SyncState {
        val wanted = requiredBy(policy)
        setEnabled(wanted)

        if (!wanted) {
            // 策略关掉上网管控：停止通道并解除 always-on 锁定，
            // 否则孩子端会留着一个"家长已经关掉、但系统还锁着"的 VPN，既耗电又无法在设置里关。
            disableAlwaysOn()
            if (isActive()) stop()
            return SyncState.OFF
        }

        if (!isPrepared()) {
            // DO/PO：代用户授权 + 锁定设置（系统不弹窗），这是唯一"关不掉"的形态
            if (enableAlwaysOn() && isPrepared()) {
                Logger.i(TAG) { "web guard vpn authorized via always-on (DO/PO)" }
            } else {
                // 非 DO 且用户没点过授权框：如实降级，等 UI 引导补授权
                if (isActive()) stop()
                Logger.w(TAG) { "web guard vpn required but not authorized (needs user consent)" }
                return SyncState.NEED_CONSENT
            }
        }

        if (isActive()) return SyncState.RUNNING
        return if (start()) SyncState.RUNNING else SyncState.START_BLOCKED
    }

    /**
     * 拉起 VPN 前台服务。
     *
     * @return 是否成功把启动请求交给了系统。注意"交出去"不等于"establish 成功"，
     *   后者由服务自己保证（失败即自停），真实状态以 [isActive] 为准。
     */
    fun start(): Boolean {
        if (!isEnabled()) {
            Logger.w(TAG) { "web guard vpn start skipped: not required by policy" }
            return false
        }
        if (!isPrepared()) {
            Logger.w(TAG) { "web guard vpn start skipped: not authorized" }
            return false
        }
        return runCatching {
            val intent = Intent(context, WebGuardVpnService::class.java).apply {
                action = WebGuardVpnService.ACTION_START
            }
            context.startForegroundService(intent)
            true
        }.onFailure { t ->
            // Android 12+ 后台启动前台服务受限（非 DO 且无豁免时会抛这个）：
            // 不吞掉、不重试打转，记录后由下一轮对账（周期自检 / 回前台 / 开机）再试。
            if (t is android.app.ForegroundServiceStartNotAllowedException) {
                Logger.w(TAG) { "web guard vpn start blocked by FGS background restriction" }
            } else {
                Logger.e(TAG, t) { "failed to start web guard vpn" }
            }
        }.getOrDefault(false)
    }

    /** 停止 VPN 服务。 */
    fun stop() {
        runCatching {
            val intent = Intent(context, WebGuardVpnService::class.java).apply {
                action = WebGuardVpnService.ACTION_STOP
            }
            context.startService(intent)
        }.onFailure { Logger.e(TAG, it) { "failed to stop web guard vpn" } }
    }

    // ==================== DO/PO：always-on 授权 ====================

    /**
     * 设备所有者代授权：把本应用设为 always-on VPN（系统据此直接授予 VPN 权限，不弹框），
     * 并加上 `DISALLOW_CONFIG_VPN` 让 VPN 设置在系统里变灰 —— 孩子无法关闭或换成别的 VPN。
     *
     * 非 DO/PO 直接返回 false，调用方按「需要用户授权」降级。
     */
    private fun enableAlwaysOn(): Boolean {
        if (!admin.hasOwnerPrivileges()) return false
        val result = admin.setAlwaysOnVpn(context.packageName, lockdown = true)
        if (!result.isOk) {
            Logger.w(TAG) { "setAlwaysOnVpn failed: $result" }
            return false
        }
        admin.setUserRestriction(UserManager.DISALLOW_CONFIG_VPN, true)
        prefs.edit().putBoolean(KEY_ALWAYS_ON, true).apply()
        return true
    }

    /** 解除 always-on（策略关闭时）。仅当本端确实设过才清除，避免误清别处的配置。 */
    private fun disableAlwaysOn() {
        if (!prefs.getBoolean(KEY_ALWAYS_ON, false)) return
        if (!admin.hasOwnerPrivileges()) return
        runCatching { admin.setAlwaysOnVpn(null, lockdown = false) }
            .onFailure { Logger.w(TAG, it) { "clear always-on vpn failed" } }
        runCatching { admin.setUserRestriction(UserManager.DISALLOW_CONFIG_VPN, false) }
        prefs.edit().putBoolean(KEY_ALWAYS_ON, false).apply()
        Logger.i(TAG) { "always-on vpn cleared (web guard turned off)" }
    }

    /** 对账结果，仅用于日志/诊断展示。 */
    enum class SyncState {
        /** 策略未要求上网管控，通道已停 */
        OFF,
        /** 策略要求，但系统尚未授权（需家长在 VPN 确认框点允许） */
        NEED_CONSENT,
        /** 通道运行中 */
        RUNNING,
        /** 已授权但本次启动被系统拒绝（后台 FGS 限制等），下轮重试 */
        START_BLOCKED
    }

    companion object {
        private const val TAG = "WebGuardVpnCtrl"
        private const val PREFS_NAME = "padguard_webguard_vpn"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_ACTIVE_AT = "active_at"
        private const val KEY_ALWAYS_ON = "always_on"

        /** 心跳宽限：超过此时长未见心跳视为服务已不在运行（进程被杀 / 被系统回收）。 */
        private const val ACTIVE_GRACE_MS = 2 * 60 * 1000L
    }
}
