package com.padguard.child.guard

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.SharedPreferences
import android.view.accessibility.AccessibilityManager

/**
 * 操作闸门无障碍服务的状态探测。
 *
 * 该服务（[OperationGuardAccessibilityService]）是操作密码闸门的系统级拦截硬依赖：
 * 未启用时，孩子可从设置 / 通知栏绕过家长设置的密码，闸门形同虚设。
 *
 * 判定口径集中在此，[com.padguard.child.service.GuardService.ensureGuardInfra]
 * 与「我的」页的状态横幅共用同一逻辑，避免多处实现导致判定不一致。
 */
object GuardA11y {

    private const val PREFS_NAME = "padguard_a11y_liveness"
    private const val KEY_LAST_ALIVE = "last_alive_ms"

    /**
     * 服务存活心跳的容忍窗口。
     *
     * 服务每次连接 / 每轮看门狗都会刷新一次 [markAlive]。若超过该时长未见心跳，
     * 视为「服务已不在运行」（进程被系统杀死 / 被省电策略停止），此时才应告警。
     * 取 2 分钟：远小于任何合理的心跳间隔（看门狗 500ms 一轮），又能跨过看门狗兜底拉起
     * （≤90s）的瞬时空窗，避免把「被杀→自恢复」过程中的一瞬误报成「人为关闭」。
     */
    private const val ALIVE_GRACE_MS = 2 * 60 * 1000L

    /**
     * 本应用的「操作闸门无障碍服务」是否已在系统设置中启用。
     *
     * @return true = 已启用（闸门系统级拦截可用）；false = 未启用或探测失败。
     */
    fun isEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        return runCatching {
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any {
                    it.resolveInfo?.serviceInfo?.packageName == context.packageName &&
                        it.resolveInfo?.serviceInfo?.name
                            ?.endsWith("OperationGuardAccessibilityService") == true
                }
        }.getOrDefault(false)
    }

    /** 由无障碍服务在连接成功与每轮看门狗时调用，记录「最近存活时刻」。 */
    fun markAlive(context: Context) {
        runCatching {
            prefs(context).edit().putLong(KEY_LAST_ALIVE, System.currentTimeMillis()).apply()
        }.onFailure { /* 写入失败不应影响主流程 */ }
    }

    /** 读取最近一次存活心跳时刻（毫秒）；无记录返回 0。 */
    fun lastAliveAt(context: Context): Long =
        runCatching { prefs(context).getLong(KEY_LAST_ALIVE, 0L) }.getOrDefault(0L)

    /**
     * 服务是否**真正在运行**（不仅设置开关打开，且近期有心跳）。
     *
     * 这是 [com.padguard.core.engine.guard.GuardInfraProbe.isAccessibilityServiceAlive] 的实现依据：
     * - 尚无心跳记录时退化为「开关是否打开」，避免在服务刚连接前的空窗误报；
     * - 有心跳记录时，必须「开关开着且距上次心跳未超过 [ALIVE_GRACE_MS]」才算活着，
     *   从而把「进程被系统杀掉」（开关仍在、但不再上报心跳）与「用户手动关闭」（开关没了）统一收敛为
     *   可靠的「未运行」判定，且不会冤枉用户「人为关闭」。
     */
    fun isAlive(context: Context): Boolean {
        val last = lastAliveAt(context)
        if (last == 0L) return isEnabled(context)
        if (!isEnabled(context)) return false
        return System.currentTimeMillis() - last <= ALIVE_GRACE_MS
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
