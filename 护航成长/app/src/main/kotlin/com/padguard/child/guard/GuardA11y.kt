package com.padguard.child.guard

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/**
 * 操作闸门无障碍服务的状态探测。
 *
 * 该服务（[OperationGuardAccessibilityService]）是操作密码闸门的系统级拦截硬依赖：
 * 未启用时，孩子可从设置 / 通知栏绕过家长设置的密码，闸门形同虚设。
 *
 * 判定口径集中在此，[com.padguard.child.service.GuardService.ensureGuardInfra]
 * 与「我的」页的状态横幅、受控桌面门禁、卸载防护强度共用同一逻辑，
 * 避免多处实现导致判定不一致。
 *
 * ## 三种状态，必须分清（2026-11-05 线上误判复盘）
 * 开关状态与运行状态是两件事，历史上把它们混成一个布尔值，直接导致了一次
 * "**服务好端端跑着，全端却一致认为它掉线了**"的误判：
 *
 * | 状态 | 含义 | 处置 |
 * |---|---|---|
 * | 开关关闭 | 用户/ROM 把服务从无障碍列表移除 | 需家长介入（DO 可静默补回） |
 * | 开关开着但没连上 | 进程被杀 / 绑定被解绑 | 可自愈（系统会重绑，DO 可强制重绑） |
 * | 开关开着且已连接 | 正常 | 无 |
 *
 * ## 心跳为什么必须续约
 * 本类原先只在 [markAlive]（服务连接成功那一刻）写一次时间戳，但 [isAlive] 的
 * 容忍窗口只有 2 分钟 —— 于是**服务连接满 2 分钟后，判定必然翻转为"已死亡"**，
 * 而服务其实一直在正常工作。表现为：
 * - 心跳里 `a11yAlive` 恒为 false → 家长端显示"无障碍掉线"；
 * - 受控桌面认为门禁没有兜底，退化成"只弹框不启动应用"；
 * - 卸载防护从软门降级，并周期性误报"防护未就绪"；
 * - [com.padguard.core.engine.guard.TamperDetector] 每轮扫描都打一行
 *   "a11y switch ON but process dead"。
 *
 * 修复分两层，缺一不可：
 * 1. **进程内真值**：服务对象就在本进程里，[markAlive] 置 CONNECTED、
 *    [markDead] 置 DISCONNECTED，这是最权威、且不受时钟/磁盘影响的判据；
 * 2. **持久心跳兜底**：看门狗与窗口事件周期性（节流）续约，让"进程刚重启、
 *    服务还没连上"的短暂空窗仍能读到新鲜时间戳，而不是立刻误判。
 *
 * 三态取值刻意保留 UNKNOWN：进程重启后内存态清零，此时既不能断言"活着"
 * （服务可能确实没起来），也不能断言"死了"（系统往往几十毫秒内就会重绑），
 * 只能退化到"心跳是否新鲜"这一时间判据。
 */
object GuardA11y {

    private const val PREFS_NAME = "padguard_a11y_liveness"
    private const val KEY_LAST_ALIVE = "last_alive_ms"

    /**
     * 服务存活心跳的容忍窗口。
     *
     * 服务每次连接 / 每轮看门狗都会刷新一次 [markAlive]/[markAliveTick]。若超过该时长未见心跳，
     * 视为「服务已不在运行」（进程被系统杀死 / 被省电策略停止），此时才应告警。
     * 取 2 分钟：远小于任何合理的心跳间隔（看门狗 500ms 一轮，落盘节流 30s），
     * 又能跨过看门狗兜底拉起（≤90s）的瞬时空窗，避免把「被杀→自恢复」过程中的一瞬误报。
     */
    private const val ALIVE_GRACE_MS = 2 * 60 * 1000L

    /**
     * 心跳落盘的最小间隔。
     *
     * 看门狗 500ms 一轮，若每轮都写 SharedPreferences，一天就是 17 万次磁盘写 ——
     * 对低端平板的 IO 与耗电都不是可以忽略的量级。30s 一次足够让
     * [ALIVE_GRACE_MS]（120s）窗口内始终有 4 次续约机会，掉一次也跨不过容忍窗口。
     */
    private const val HEARTBEAT_WRITE_INTERVAL_MS = 30 * 1000L

    /** 本进程内服务连接的三种状态，语义见类文档。 */
    internal enum class Link { UNKNOWN, CONNECTED, DISCONNECTED }

    /** 进程内实时状态：服务对象在本进程里，这里就是最权威的判据。 */
    @Volatile
    internal var link: Link = Link.UNKNOWN
        private set

    /** 上次心跳落盘时刻，用于节流。进程重启后归零，首轮必然落盘。 */
    @Volatile
    private var lastWrittenAt = 0L

    /**
     * 本应用的「操作闸门无障碍服务」是否已在系统设置中启用。
     *
     * 注意：这只代表**开关状态**，不代表服务在跑。判断"能不能用"请用 [isAlive]。
     *
     * ## 双口径交叉，消除"明明已开却判定未开"（2026-11 复盘的误报根因）
     * 旧实现只认 [AccessibilityManager.getEnabledAccessibilityServiceList]，
     * 而该列表在部分定制 ROM（EMUI / MIUI 等）下会**滞后或被过滤**——
     * 用户已在系统「无障碍」里打开本服务，列表却还没刷新，于是不少设备稳定地误报"未授权"，
     * 表现为：主页横幅喊"需要授权"、授权流水线不认为它已办妥、拨完开关不自动跳下一项。
     *
     * 修复：以系统落盘的 [Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES] 为**主口径**
     * （它与系统「无障碍」设置页读取的是同一份数据，开关打开它就必然包含本服务，绝不被 Manager 缓存拖累）；
     * [AccessibilityManager] 列表降级为**兜底**（仅当读取落盘设置异常时再用），
     * 二者任一为真即视为已启用。这样一来，只要开关真的打开，就不会再误报未授权。
     *
     * @return true = 已启用（闸门系统级拦截可用）；false = 未启用或探测失败。
     */
    fun isEnabled(context: Context): Boolean {
        // ① 主口径：系统持久化设置（最权威，与设置页同源）。
        val flat = ComponentName(context, OperationGuardAccessibilityService::class.java).flattenToString()
        val enabledServices = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        }.getOrNull()
        if (matchesEnabledSetting(flat, enabledServices)) return true

        // ② 兜底口径：AccessibilityManager 已启用列表（落盘设置读取异常时的退路）。
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

    /**
     * 判定某组件是否已在 `ENABLED_ACCESSIBILITY_SERVICES` 设置串中启用（纯函数，便于单测）。
     *
     * 系统把已启用的无障碍服务以 `:` 分隔的 flatten 组件名拼接存进该设置项，
     * 例如 `com.padguard.child/com.padguard.child.guard.OperationGuardAccessibilityService`。
     * 用 [String.equals] 的 `ignoreCase` 是因为个别 ROM 会把包名/类名小写落盘。
     *
     * @param flatComponent 本服务的 [ComponentName.flattenToString] 结果
     * @param enabledServices [Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES] 的原始字符串（可空）
     */
    @JvmStatic
    internal fun matchesEnabledSetting(flatComponent: String, enabledServices: String?): Boolean {
        if (flatComponent.isBlank()) return false
        return enabledServices.orEmpty()
            .split(':')
            .any { it.equals(flatComponent, ignoreCase = true) }
    }

    /**
     * 由无障碍服务在 `onServiceConnected` 调用：置为已连接并**强制**落盘心跳。
     *
     * 强制落盘的理由：连接瞬间是最需要留下"它真的起来过"证据的时刻，
     * 不能被 [HEARTBEAT_WRITE_INTERVAL_MS] 节流吞掉（否则进程重启后头 30s 无心跳）。
     */
    fun markAlive(context: Context) {
        link = Link.CONNECTED
        writeHeartbeat(context, force = true)
    }

    /**
     * 服务存活期间的**续约**：由看门狗循环与窗口事件回调高频调用，
     * 内部按 [HEARTBEAT_WRITE_INTERVAL_MS] 节流，调用方无需关心频率。
     *
     * 这是修复"连接 2 分钟后必然误判死亡"的关键一环 —— 没有它，
     * [markAlive] 写下的时间戳会一直停在那里，直到过期。
     */
    fun markAliveTick(context: Context) {
        link = Link.CONNECTED
        writeHeartbeat(context, force = false)
    }

    /**
     * 由服务 `onDestroy` 调用：服务对象已销毁，立刻判死，不等心跳过期。
     *
     * 与"心跳过期"的区别在于**时效**：解绑/被关闭的那一刻就应反映到 UI 与门禁，
     * 而不是让家长再等 2 分钟才看到"掉线"。
     */
    fun markDead() {
        link = Link.DISCONNECTED
    }

    /** 供 UI 区分「已启用但没连上」与「已启用且在跑」；跨进程场景返回 false。 */
    fun isConnectedNow(): Boolean = link == Link.CONNECTED

    /** 读取最近一次存活心跳时刻（毫秒）；无记录返回 0。 */
    fun lastAliveAt(context: Context): Long =
        runCatching { prefs(context).getLong(KEY_LAST_ALIVE, 0L) }.getOrDefault(0L)

    /**
     * 服务是否**真正在运行**（不仅设置开关打开，且进程内已连接或近期有心跳）。
     *
     * 这是 [com.padguard.core.engine.guard.GuardInfraProbe.isAccessibilityServiceAlive] 的实现依据：
     * - 开关关着 → 一定不可用（这是唯一能确定"人为关闭"的信号）；
     * - 进程内已连接 → 可用（最权威，不受心跳过期影响）；
     * - 进程内已断开 → 不可用（服务对象已销毁）；
     * - 进程内状态未知（刚重启，服务尚未连接）→ 退化为"心跳是否新鲜"，
     *   避免在系统重绑前的几十毫秒空窗里误报。
     */
    fun isAlive(context: Context): Boolean =
        decideAlive(
            enabled = isEnabled(context),
            link = link,
            lastAliveAt = lastAliveAt(context),
            // 心跳新鲜度是短时窗，必须用单调钟：裸墙钟下回拨系统时间会让
            // now-lastAliveAt 恒为负，UNKNOWN 分支永远判"活着"，
            // 无障碍服务实际已死也不再提示补授权，软闸门静默失效。
            now = SystemClock.elapsedRealtime(),
            graceMs = ALIVE_GRACE_MS
        )

    /**
     * 纯判定，便于单测覆盖全部分支（不依赖 Context / 磁盘 / 时钟）。
     *
     * @param enabled 系统设置里的开关状态
     * @param link 进程内连接状态
     * @param lastAliveAt 持久化的最近心跳时刻（0 = 从未记录）
     * @param now 当前时刻
     * @param graceMs 心跳容忍窗口
     */
    @JvmStatic
    internal fun decideAlive(
        enabled: Boolean,
        link: Link,
        lastAliveAt: Long,
        now: Long,
        graceMs: Long
    ): Boolean {
        if (!enabled) return false
        return when (link) {
            Link.CONNECTED -> true
            Link.DISCONNECTED -> false
            // 从未有过心跳记录：只能相信开关（可能是首次装机，服务尚未连接）
            // lastAliveAt > now 说明是旧版本落盘的墙钟值 / 单调钟重启归零 / 读数回拨，
            // 一律判为心跳过期，绝不能让负差值把"已死"判成"还活着"。
            Link.UNKNOWN -> lastAliveAt == 0L || (now >= lastAliveAt && now - lastAliveAt <= graceMs)
        }
    }

    private fun writeHeartbeat(context: Context, force: Boolean) {
        // 与 isAlive 的 now 同域（单调钟）；写节流 @Volatile 内存态同样只在本进程比较。
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastWrittenAt < HEARTBEAT_WRITE_INTERVAL_MS) return
        lastWrittenAt = now
        runCatching {
            prefs(context).edit().putLong(KEY_LAST_ALIVE, now).apply()
        }.onFailure { /* 写入失败不应影响主流程 */ }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
