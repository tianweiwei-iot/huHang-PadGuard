package com.padguard.child.guard

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 操作闸门的「装机向导豁免」开关。
 *
 * ## 为什么必须存在
 * 一次性授权向导会带家长去系统设置页办理「安装未知应用」等授权，而操作闸门
 * （[OperationGuardAccessibilityService]）对系统设置前台无条件拦截：密码未设置时
 * 直接 GLOBAL_ACTION_BACK 顶回。无障碍服务恰好在向导中段（无障碍步骤之后）才启用，
 * 此后向导剩下的设置步骤（安装未知应用等）刚打开就被弹回——表现为
 * 「跳到设置页 1 秒就被顶回来，根本来不及拨开关」，装机流程卡死。
 *
 * ## 为什么用时间窗而不是布尔量
 * 进程被杀重建时布尔量状态难以对齐；时间窗天然自愈：向导每发起一次设置页跳转
 * 就续期一次，向导结束立即 [clear]。窗口过期后闸门自动恢复——即使向导异常退出，
 * 也不会把设置页永久敞开给孩子。
 *
 * ## 豁免边界（防绕过）
 * 服务侧只对 **REASON_SETTINGS 的顶回** 生效；「设置→应用详情/卸载入口」的自检保护、
 * 安装器拦截、受管控应用门禁一律不豁免——向导也不会把家长带去那些界面，
 * 孩子无法借豁免窗口卸载本应用或绕过应用门禁。
 */
@Singleton
class GuardGateSuspension @Inject constructor() {

    @Volatile
    private var suspendedUntilMs = 0L

    /** 向导发起设置页跳转时调用：续期豁免窗口。 */
    fun suspendFor(durationMs: Long) {
        suspendedUntilMs = System.currentTimeMillis() + durationMs
    }

    /** 向导结束（完成 / 家长选择放弃）时调用：立即恢复拦截。 */
    fun clear() {
        suspendedUntilMs = 0L
    }

    fun isActive(): Boolean = System.currentTimeMillis() < suspendedUntilMs
}
