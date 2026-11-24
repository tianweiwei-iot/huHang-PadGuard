package com.padguard.child.guard

import android.os.SystemClock
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
 * ## 时钟域：必须用单调钟（新增缺陷 I）
 * 早期 `suspendFor` / `isActive` 都用 `System.currentTimeMillis()`。豁免窗口一旦开启，
 * 孩子只需在设置里把系统时间**回拨**，`now < suspendedUntil` 就长期成立，窗口永不 self-heal：
 * `OperationGuardAccessibilityService` 在豁免期放行的不只是普通设置页，还包括
 * 「设备管理员停用页」与「应用详情/卸载入口」的自检分支 —— 于是可以停用设备管理员、
 * 关闭无障碍，整条应用管控链路被拆掉。这正是"时间窗天然自愈"被裸墙钟破坏的场景。
 *
 * 改用 [SystemClock.elapsedRealtime]：单调、不受改时间影响。窗口纯进程内内存态
 * （进程死即消失），所以单调钟在设备重启后归零不会造成跨重启语义错乱。
 *
 * ## 豁免边界（防绕过）
 * 服务侧只对设置页与设备管理员停用页的顶回生效；「设置→应用详情/卸载入口」的自检保护、
 * 安装器拦截、受管控应用门禁一律不豁免——向导也不会把家长带去那些界面，
 * 孩子无法借豁免窗口卸载本应用或绕过应用门禁。
 */
@Singleton
class GuardGateSuspension @Inject constructor() {

    @Volatile
    private var suspendedUntilElapsedMs = 0L

    /**
     * 当前豁免窗口的**持有者**（R34）。
     *
     * 这是单例，但「申请豁免」的页面有两处：装机/补授权流水线 [OWNER_CONSENT]、
     * App 内补开页 [OWNER_PERMISSION_GUIDE]。补开页点「一键补齐」会置 reauthRequired，
     * 入口分流同一帧把组合切到 OneTimeConsentScreen —— 旧页 `onDispose` 无条件 `clear()`
     * 与流水线刚发起设置页跳转的 `suspendFor()` 没有先后顺序保证：
     * clear 后执行就会抹掉刚开的窗口，家长被带去无障碍设置页时立刻被闸门顶回
     * （「设置页闪一下就回来、开关拨不动」）。
     *
     * 因此 `clear` 只认自己那份窗口；流水线 finish 作为整条授权的权威收口仍可
     * 用 [clearAll] 无条件恢复拦截，不会留下永久豁免。
     */
    @Volatile
    private var currentOwner: String = DEFAULT_OWNER

    /** 本进程见过的最大单调读数：任何回拨 / 脏值都不得让时间倒退（与受信钟护栏同纪律）。 */
    private var highWaterElapsedMs = 0L
    private val clockLock = Any()

    /**
     * 单调读数注入缝：JVM 单元测试替换它即可覆盖回拨/推进分支，
     * 避免触碰 android.os.SystemClock 的未实现桩。生产路径始终取 [SystemClock.elapsedRealtime]。
     */
    internal var elapsedNow: () -> Long = { SystemClock.elapsedRealtime() }

    /**
     * 受护栏的单调读数：只前进不后退。
     *
     * 单靠"换用 elapsedRealtime"还不够 —— 若读数因 ROM 异常 / 深度休眠回拨，
     * `now < suspendedUntil` 会重新成立，**已经过期的豁免窗口会复活**。
     * 高水位把这类脏值钳住，保证窗口只能单向走向过期。
     */
    private fun trustedElapsed(): Long = synchronized(clockLock) {
        val raw = elapsedNow()
        if (raw > highWaterElapsedMs) highWaterElapsedMs = raw
        highWaterElapsedMs
    }

    /**
     * 向导发起设置页跳转时调用：续期豁免窗口，并登记持有者。
     *
     * [owner] 不传即流水线（[OWNER_CONSENT]）；App 内补开页必须显式传
     * [OWNER_PERMISSION_GUIDE]，否则它离场时会清掉流水线刚开的窗口。
     */
    fun suspendFor(durationMs: Long, owner: String = DEFAULT_OWNER) {
        suspendedUntilElapsedMs = trustedElapsed() + durationMs
        currentOwner = owner
    }

    /**
     * 页面结束自己的设置会话时调用：**只清自己持有的窗口**。
     *
     * 若窗口已被另一个页面续期（例如补开页出栈时流水线已经接手并打开设置页），
     * 这里直接返回，绝不把别人正在用的豁免抹掉。
     */
    fun clear(owner: String = DEFAULT_OWNER) {
        if (currentOwner != owner) return
        suspendedUntilElapsedMs = 0L
    }

    /**
     * 整条授权流水线收尾（finish / 家长放弃）时的权威收口：无条件恢复拦截。
     * 仅允许流水线调用，避免任何页面借「结束」之名留下别人的豁免窗口。
     */
    fun clearAll() {
        suspendedUntilElapsedMs = 0L
        currentOwner = DEFAULT_OWNER
    }

    fun isActive(): Boolean = trustedElapsed() < suspendedUntilElapsedMs

    companion object {
        /** 装机/补授权流水线（OneTimeConsentViewModel） */
        const val OWNER_CONSENT = "consent"
        /** App 内「权限引导 / 授权总览」补开页（PermissionGuideViewModel） */
        const val OWNER_PERMISSION_GUIDE = "permission_guide"

        private const val DEFAULT_OWNER = OWNER_CONSENT
    }
}
