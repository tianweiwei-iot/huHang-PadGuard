package com.padguard.child.guard

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.VpnService
import com.padguard.core.common.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 上网防护（本地 VPN）通道的筹备与启停控制器。
 *
 * ## 它在 E1 里的位置
 * [WebGuardVpnService] 是落地「上网管控」域名级拦截的执行体；本控制器负责三件事：
 * 1. 探测 VPN 是否已被系统授权（[VpnService.prepare]）；
 * 2. 提供拉起系统 VPN 确认框的 Intent（首次授权用）；
 * 3. 持久化一个「家长已开启上网防护」开关，并由 [GuardService] 据此按需启停服务。
 *
 * ## 关键安全约束（与整体「如实降级 / fail-closed」一致）
 * - **绝不自动开启**：VPN 仅当家长在一次性授权里明确授予、且本端开关打开后才启动；
 *   默认关闭，避免任何设备在未授权情形下被拉起一个未经验证的全设备流量通道。
 * - **establish 失败即自停**：服务内部任何异常都会 [WebGuardVpnService.stopSelf]，
 *   绝不静默留下一个半死的 VPN（那会卡死 DNS）。
 * - **仅主机路由 DNS、不加默认路由**：服务只对「DNS 服务器 IP」做主机路由，
 *   其它流量仍走原网络，因此即便通道逻辑有 bug，最坏是「域名过滤失效 / 短暂无网」，
 *   不会把整台平板的互联网全堵死（无默认路由 = 不存在「全量转发」这一最危险路径）。
 *
 * ⚠️ 本通道涉及底层网络栈，必须在真机回归；本环境无法验证，故标注 DEVICE_QA_REQUIRED。
 */
@Singleton
class WebGuardVpnController @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 家长是否已开启上网防护（本地开关；开启后 [GuardService] 才允许拉起 VPN）。 */
    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        Logger.i(TAG) { "web guard vpn enabled=$enabled" }
    }

    /** VPN 是否已被系统授权（用户点过确认框）。 */
    fun isPrepared(): Boolean = runCatching { VpnService.prepare(context) == null }.getOrDefault(false)

    /** 拉起系统 VPN 确认框所需的 Intent；已授权时返回 null。 */
    fun prepareIntent(): Intent? = runCatching { VpnService.prepare(context) }.getOrNull()

    /** 当前 VPN 服务是否正在运行（由服务自身心跳标记）。 */
    fun isActive(): Boolean {
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

    /** 拉起 VPN 前台服务（仅在 [isEnabled] 为真且 [isPrepared] 时由 [GuardService] 调用）。 */
    fun start() {
        if (!isEnabled() || !isPrepared()) {
            Logger.w(TAG) { "web guard vpn start skipped: enabled=${isEnabled()} prepared=${isPrepared()}" }
            return
        }
        runCatching {
            val intent = Intent(context, WebGuardVpnService::class.java).apply {
                action = WebGuardVpnService.ACTION_START
            }
            context.startForegroundService(intent)
        }.onFailure { Logger.e(TAG, it) { "failed to start web guard vpn" } }
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

    companion object {
        private const val TAG = "WebGuardVpnCtrl"
        private const val PREFS_NAME = "padguard_webguard_vpn"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_ACTIVE_AT = "active_at"
        /** 心跳宽限：超过此时长未见心跳视为服务已不在运行（进程被杀 / 被系统回收）。 */
        private const val ACTIVE_GRACE_MS = 2 * 60 * 1000L
    }
}
