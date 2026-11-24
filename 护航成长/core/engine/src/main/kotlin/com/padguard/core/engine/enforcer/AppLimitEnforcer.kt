package com.padguard.core.engine.enforcer

import com.padguard.core.common.Logger
import com.padguard.core.common.TimeProvider
import com.padguard.core.data.model.policy.AppLimitPolicy
import com.padguard.core.data.model.policy.AppLimitRule
import com.padguard.core.data.model.policy.DayType
import com.padguard.core.data.repository.UsageRepository
import com.padguard.core.engine.schedule.ScheduleEvaluator
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用时长与次数限额。
 *
 * ## 计时基准：为什么一行墙钟都不能用
 * 限额管控是被绕过最频繁的功能，孩子的第一反应就是改系统时间。
 * 所以这里的规则是**绝对的**：
 *
 * | 用途 | 时钟 | 原因 |
 * |---|---|---|
 * | 累计时长（本类核心） | `elapsedRealtime` 差值 | 单调递增，改时间无效，且含深度睡眠 |
 * | 日切 dayKey | [TimeProvider.now]（服务端校准墙钟） | "今天"是墙钟概念，但取校准值而非本地值 |
 * | 单次会话时长 | `elapsedRealtime` 差值（累计量） | 同上；跨重启改用受信墙钟差估算"离开时长" |
 *
 * ## 单次会话（`singleMinutes`）的两次加固
 * 早期实现用「本次前台会话的起始单调点」做减法，有两个可被孩子直接利用的缺口：
 *
 * 1. **按一下 Home 就清零**：只要前台应用发生变化就 `sessionStart = now`，
 *    于是"连按 Home 再点回来"两秒的功夫，30 分钟的单次限额重新计时 ——
 *    单次限额实际上等于不存在。
 * 2. **重启即续杯**：起始点只在内存里，进程被回收 / 设备重启后归零。
 *
 * 现在改为**累计 + 休息阈值**模型：[SingleSession] 记录本次会话已累计多久、
 * 最后一次"仍在用"的时刻。离开该应用不满 [SINGLE_SESSION_REST_MS] 就回来 → **续算**；
 * 离开达到阈值 → 视为真的休息过，重新起表。会话快照经 [SingleSessionStore] 落盘，
 * 重启后按"离开时长"决定续算还是重开（见 [SingleSession] 的字段语义）。
 *
 * 这带来的口径变化是刻意的：切走 2 分钟不算休息，切走 5 分钟才算。
 * "每次最多玩 30 分钟"约束的是**连续用眼**，二秒钟的 Home 键不构成休息。
 *
 * 注意日切仍依赖墙钟 —— 这是无法回避的（"每日 2 小时"本身就是墙钟语义）。
 * 缓解手段有三层：① 用服务端校准时间；② [com.padguard.core.engine.enforcer.SystemLockEnforcer]
 * 强制自动对时并禁止手改；③ [com.padguard.core.engine.guard.TamperDetector] 检测到跳变即告警。
 * 即便日切被改，**已累计的时长也不会因此清零**（数据按 dayKey 分行存储，改回来照样能查到）。
 *
 * ## 采样而非事件驱动
 * Android 没有可靠的"应用前后台切换"公开广播（UsageStats 有延迟、无障碍事件会被杀）。
 * 因此采用**定时采样**：由前台服务每 [SAMPLE_INTERVAL_MS] 调一次 [track]，
 * 把「上次采样到本次采样之间的时间」记到当时的前台应用头上。
 *
 * 采样有个必须处理的边界：息屏、Doze、进程被杀重启都会让两次采样间隔远超预期。
 * 若直接累加就会出现"睡了一夜，微信被记了 8 小时"。
 *
 * ## 增量必须取【两个单调钟的较小值】（不是简单截断到 60 秒）
 * Android 提供两个不受改系统时间影响的单调钟，语义不同：
 * - `elapsedRealtime()`：自开机起，**含深度睡眠**；
 * - `uptimeMillis()`：自开机起，**不含深度睡眠**（CPU 挂起期间不走）。
 *
 * 于是 `min(elapsedDelta, awakeDelta)` 恰好等于"这段时间里设备真正醒着多久"：
 * - 睡了一夜：elapsedDelta=8h、awakeDelta≈0 → 取 0，不会把睡眠算成使用；
 * - 主循环被弱网/GC 拖住 5 分钟（设备一直醒着）：两个差值都是 5 分钟 → 取 5 分钟，
 *   **完整计入**，不再被上限截断成 60 秒而少算（旧实现正是这样少算的）。
 *
 * 早期实现直接用 `elapsedRealtime` 差值再 `coerceAtMost(60s)`：既把深睡当使用（靠截断掩盖），
 * 又把"主循环真的卡了 5 分钟"少记成 1 分钟 —— 少记的方向对孩子有利，等于白送额度。
 * 保留 [MAX_DELTA_MS] 只是防御性上限（脏值/极端卡顿），不是深睡的挡板。
 */
@Singleton
class AppLimitEnforcer @Inject constructor(
    private val usageRepository: UsageRepository,
    private val timeProvider: TimeProvider,
    private val scheduleEvaluator: ScheduleEvaluator,
    private val sessionStore: SingleSessionStore
) {

    /** 上次采样的单调时钟点（含深睡）；-1 表示尚未开始采样 */
    private var lastSampleElapsed: Long = -1L

    /**
     * 上次采样的"设备醒着"时钟点（`uptimeMillis`，不含深睡）。
     *
     * 与 [lastSampleElapsed] 配对求 [computeDelta] 的较小值：只有这个差值才代表
     * "设备真的醒着、孩子可能真的在看屏幕"的时长。两者必须同一次采样一起更新，
     * 分开更新会让某一侧的差值凭空多出一整轮。
     */
    private var lastSampleUptime: Long = -1L

    /** 上次采样时的前台包名，用于识别应用切换（进而累计启动次数） */
    private var lastForegroundPackage: String? = null

    /**
     * 单应用会话表：`包名 -> 本次连续使用的累计快照`（见 [SingleSession]）。
     *
     * ## 为什么是表而不是单个"当前会话"
     * 只保留一个槽位时，切到桌面（launcher 变成前台）会把上一个应用的累计量**覆盖掉**，
     * 孩子按 Home 再点回来照样是全新会话 —— 绕过一点没堵住。表让"短暂离开再回来"
     * 能续算，只有真的休息满 [SINGLE_SESSION_REST_MS] 才重新起表。
     *
     * 容量上限 [MAX_TRACKED_SESSIONS]：这是内存与磁盘上的有界结构，
     * 不能随安装应用数无限增长（一台设备装 200 个应用时每轮采样遍历 200 条毫无意义）。
     * 超出时按"最久没碰过"淘汰 —— 被淘汰的那个重新进入时从 0 起算，
     * 属于可接受的降级：它本来也已经被冷落超过一轮会话时长。
     *
     * ## 为什么必须是并发容器
     * [track] 跑在守护服务的采样协程里，而 [evaluate] 会被锁屏页 / 悬浮球的 UI 线程调用，
     * 两边都会读这张表。用普通 `LinkedHashMap` 时，一边 `replaceAll`（息屏结算）
     * 一边 `get`（刷新倒计时）会抛 `ConcurrentModificationException` ——
     * 异常落进 runSafely 就是"这一轮管控没跑"，表现为锁屏页闪退或限额偶发失效。
     */
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, SingleSession>()

    /** 本轮之前屏幕是否点亮，用于"息屏只结算一次"（否则息屏期间每轮都把离开时刻刷新成现在） */
    private var wasScreenOn: Boolean = true

    /** 本轮之前是否处于护眼强制休息，作用同 [wasScreenOn]：只在刚进入休息时结算一次 */
    private var wasEyeCareResting: Boolean = false

    /** 上一次落盘的快照集合，避免每轮 15 秒无条件写盘 */
    private var lastPersisted: List<SingleSession>? = null

    /** 是否已尝试过从磁盘恢复会话（只试一次，读失败不每轮重试） */
    private var sessionRestoreAttempted: Boolean = false

    /** 当前日期键，用于跨日重置会话与提醒去重 */
    private var currentDayKey: String = ""

    /** 已提醒过的阈值，避免每次采样都弹提醒。key = "$pkg|$remainingMinutes" */
    private val notifiedReminders = mutableSetOf<String>()

    /**
     * 采样一次并给出限额判定。
     *
     * @param policy 限额策略
     * @param foregroundPackage 当前前台应用包名；null 表示未知（此时只累计全局时长）
     * @param screenOn 屏幕是否点亮 —— 息屏不计时，否则平板放着不动也会耗尽额度
     * @param eyeCareResting 是否正处于护眼强制休息锁屏。
     *        true 时屏幕虽点亮但设备被锁定，这段时间**不计入**任何使用时长
     *        （休息时长由服务端权威会话统一从每日总额度扣减，本地也不累计以免上报口径失真）。
     */
    suspend fun track(
        policy: AppLimitPolicy,
        foregroundPackage: String?,
        screenOn: Boolean,
        eyeCareResting: Boolean = false
    ): LimitVerdict {
        val nowElapsed = timeProvider.elapsedRealtime()
        val nowUptime = timeProvider.uptimeMillis()
        val nowWall = timeProvider.now()
        val dayKey = usageRepository.dayKey(nowWall)

        if (dayKey != currentDayKey) {
            onDayRollover(dayKey)
        }

        val delta = computeDelta(nowElapsed, nowUptime)
        lastSampleElapsed = nowElapsed
        lastSampleUptime = nowUptime

        if (!screenOn) {
            // 息屏：**只停止累计**（不调 addTotalUsage/addAppUsage），但不重置会话上下文。
            //
            // 早期版本这里写的是 `lastForegroundPackage = null; sessionStartElapsed = 0L`，
            // 两个后果都是可被孩子直接利用的：
            // ① **单次限额绕过**：孩子按两次电源键（息屏→亮屏，无需解锁、无需任何工具）
            //    就把会话起点清零，同一个应用的"单次连续使用"永远重新计时。
            // ② **启动次数虚增**：置 null 后亮屏第一帧必然满足"前台包 != 上次前台包"，
            //    被当成一次应用切换，每次息屏/亮屏循环都多记一次 addAppLaunch，
            //    dailyLaunchCount 失真，会反过来误导家长端的使用画像。
            //
            // 现在的处理：息屏 = 孩子离开了这个应用，只把"离开时刻"结算一次
            // （[settleSession] 幂等地把 touch 更新到息屏那一刻），会话**累计量保留**。
            // 睡眠一夜那种长离开会在 [enterSession] 里因超过休息阈值而重新起表。
            if (wasScreenOn) settleSession(nowElapsed, nowWall)
            wasScreenOn = false
            persistSession()
            return LimitVerdict.Allowed
        }
        wasScreenOn = true

        if (eyeCareResting) {
            // 护眼强制休息锁屏：屏幕亮但孩子无法操作，这段时间不属于"使用"。
            // 仍更新前台包名采样基线（避免休息结束后把整段休息当成一次 60s 增量计入），
            // 但不调用 addTotalUsage / addAppUsage，本地累计与服务端权威口径保持一致。
            // 同时对单次会话做一次"离开"结算：20 分钟护眼休息本来就该算休息过。
            //
            // ⚠️ 只在**刚进入**休息时结算一次。每轮都结算的话，休息结束那一刻
            // touch 还是"刚才"，away 只有 15 秒 → 判成"没休息过"→ 继续累计，
            // 那么"护眼休息 20 分钟"对单次额度完全无效（与塞了 20 分钟广告没区别）。
            if (!wasEyeCareResting) settleSession(nowElapsed, nowWall)
            wasEyeCareResting = true
            lastForegroundPackage = foregroundPackage
            persistSession()
            return LimitVerdict.Allowed
        }
        wasEyeCareResting = false

        val pkg = foregroundPackage
        if (pkg == null) {
            // 前台应用未知（无使用情况访问权限 / 正处系统 UI）：无法归因，本轮不动会话。
            // 不能在这里把会话清掉 —— 那等于"权限被撤 → 限额失效"，是比漏计更差的失效方向。
            return LimitVerdict.Allowed
        }

        // 应用切换：为新应用累计一次启动
        if (pkg != lastForegroundPackage) {
            usageRepository.addAppLaunch(pkg, nowWall)
            Logger.v(TAG) { "foreground switched to $pkg" }
            lastForegroundPackage = pkg
        }

        // 只有配了「单次限额」的应用才维护会话表。
        // 无脑给每个前台应用建会话会白占 4 个槽位（桌面、系统弹窗、管控自身都在里头），
        // 更要紧的是**每轮 15 秒落一次盘**——家里根本没启用单次限额时，
        // 这份 I/O 纯属给平板待机时的电量添堵。
        val singleTracked = (policy.rules.firstOrNull { it.packageName == pkg }?.singleMinutes ?: 0) > 0

        if (singleTracked) enterSession(pkg, dayKey, nowElapsed, nowWall)

        if (delta > 0) {
            usageRepository.addTotalUsage(delta, nowWall)
            usageRepository.addAppUsage(pkg, delta, nowWall)
            // 会话累计与 addAppUsage 用**同一个 delta**：两者口径必须一致，
            // 否则家长端看到的"该应用今日用了 30 分钟"与孩子端"单次已达 25 分钟"会互相矛盾。
            if (singleTracked) accumulateSession(pkg, dayKey, delta, nowElapsed, nowWall)
        }
        if (singleTracked) persistSession()

        return evaluate(policy, pkg, dayKey, nowElapsed)
    }

    /**
     * 只判定不计时，供锁屏页倒计时刷新、UI 展示等只读场景使用。
     */
    suspend fun evaluate(
        policy: AppLimitPolicy,
        foregroundPackage: String?,
        dayKey: String = usageRepository.dayKey(timeProvider.now()),
        nowElapsed: Long = timeProvider.elapsedRealtime()
    ): LimitVerdict {
        // 全局日总时长锁定已改由服务端权威会话驱动（UsageSessionManager + PolicyEngine.tick
        // 的 dailyLimitExpired 入参），本地采样累计不再参与锁定判定，以换取三端时间严格一致。
        // 本地仍持续累计 usageRepository 的总/分使用时长，供「今日使用情况」上报家长端，
        // 因此这里不再读取 quotaFor 做全局总时长判定，也不再做全局剩余提醒。

        val pkg = foregroundPackage ?: return LimitVerdict.Allowed
        val rule = policy.rules.firstOrNull { it.packageName == pkg } ?: return LimitVerdict.Allowed
        if (!ruleAppliesToday(rule, dayKey)) return LimitVerdict.Allowed

        val stat = usageRepository.getAppUsage(pkg, dayKey)
        val usedMs = stat?.usedMs ?: 0L
        val launchCount = stat?.launchCount ?: 0

        // 当日累计限额
        if (rule.dailyMinutes > 0) {
            val quotaMs = rule.dailyMinutes * 60_000L
            if (usedMs >= quotaMs) {
                return LimitVerdict.Blocked(
                    packageName = pkg,
                    reason = LimitReason.APP_DAILY,
                    usedMinutes = (usedMs / 60_000L).toInt(),
                    quotaMinutes = rule.dailyMinutes
                )
            }
            reminderFor(policy, pkg, quotaMs - usedMs)?.let { return it }
        }

        // 单次连续使用限额（如"每次最多玩 30 分钟"）
        // 判据是会话的**累计量**，不是"现在 - 起点"：累计量跨应用切换、跨息屏、
        // 跨进程重启都不会凭空归零（见类注释「单次会话的两次加固」）。
        if (rule.singleMinutes > 0) {
            restoreSessionsIfNeeded()
            val s = sessions[pkg]
            if (s != null && s.dayKey == dayKey && s.accumulatedMs >= rule.singleMinutes * 60_000L) {
                return LimitVerdict.Blocked(
                    packageName = pkg,
                    reason = LimitReason.APP_SINGLE,
                    usedMinutes = (s.accumulatedMs / 60_000L).toInt(),
                    quotaMinutes = rule.singleMinutes
                )
            }
        }

        // 当日启动次数限额
        if (rule.dailyLaunchCount > 0 && launchCount > rule.dailyLaunchCount) {
            return LimitVerdict.Blocked(
                packageName = pkg,
                reason = LimitReason.APP_LAUNCH_COUNT,
                usedMinutes = launchCount,
                quotaMinutes = rule.dailyLaunchCount
            )
        }

        return LimitVerdict.Allowed
    }

    /**
     * 剩余额度提醒。
     *
     * 每个阈值每天每应用只提醒一次 —— 采样是每 15 秒一次，不去重会变成疯狂弹窗。
     * 提醒本身不阻断使用，让孩子有"收尾"的机会（正在做的题至少能写完），
     * 这是产品体验上的重要取舍：直接黑屏会引发强烈对抗情绪。
     */
    private fun reminderFor(policy: AppLimitPolicy, pkg: String, remainingMs: Long): LimitVerdict? {
        val remainingMinutes = (remainingMs / 60_000L).toInt()
        val threshold = policy.reminderMinutes
            .filter { it > 0 }
            .sorted()
            .firstOrNull { remainingMinutes <= it }
            ?: return null

        val key = "$pkg|$threshold"
        if (!notifiedReminders.add(key)) return null
        Logger.i(TAG) { "limit reminder: pkg=$pkg, remaining=${remainingMinutes}min" }
        return LimitVerdict.Warning(pkg, remainingMinutes.coerceAtLeast(0))
    }

    /**
     * 规则今天是否适用。
     *
     * 复用 [ScheduleEvaluator.dayTypeOf] 而不是自己判周末：节假日/调休的判定逻辑
     * 必须与时段锁机完全一致，否则会出现"时段锁按节假日放行、限额却按工作日计算"的错乱。
     *
     * ## 为什么日期取自 dayKey 而不是重新算一次
     * 用量分桶用的 [UsageRepository.dayKey] 钉死了 Asia/Shanghai，而这里早期用
     * `ZoneId.systemDefault()` 取 `LocalDate` —— 同一函数里两套日界。
     * 设备时区被改成非东八区（孩子改时区是常规绕过手段，非 DO 设备拦不住）时，
     * 「仅周末可用」的规则会在周六 00:00–08:00 完全不生效（设备本地还是周五），
     * 等到 08:00 才突然开始拦。直接解析 dayKey 可保证"判定按哪一天"与"用量记在哪一天"永远同一口径。
     */
    private fun ruleAppliesToday(rule: AppLimitRule, dayKey: String): Boolean {
        val today = parseDayKey(dayKey) ?: return false
        val dayType = scheduleEvaluator.dayTypeOf(today)
        if (dayType in rule.dayTypes) return true
        // 与 ScheduleEvaluator 保持一致：未单独配置节假日时退化按周末处理
        return dayType == DayType.HOLIDAY && DayType.WEEKEND in rule.dayTypes
    }

    /**
     * 解析用量分桶的 dayKey。
     *
     * 必须同时接受两种写法：`UsageRepository.dayKey()` 产出的是紧凑格式（20261107），
     * 而服务端会话下发的是 ISO（2026-11-07）。只认一种会让"今天适用哪条规则"恒判空 ——
     * 表现就是所有单应用限额静默失效（比原来按设备时区判更糟）。
     */
    private fun parseDayKey(dayKey: String): LocalDate? =
        runCatching { LocalDate.parse(dayKey) }.getOrNull()
            ?: runCatching { LocalDate.parse(dayKey, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()

    /**
     * 计算本次采样的有效增量 = `min(elapsedRealtime 差值, uptimeMillis 差值)`。
     *
     * 三种异常必须挡住：
     * - 首次采样（两个基准任一 < 0）：无基准，本次不计时；
     * - 设备重启（任一差值 < 0）：单调时钟归零，重新起算；
     * - 极端卡顿 / 脏值：由 [MAX_DELTA_MS] 兜一层防御性上限（**不是**挡深睡的）。
     *
     * 深睡不需要靠截断来处理：`uptimeMillis` 在 CPU 挂起期间根本不走，
     * 取较小值天然把睡眠排除在外，同时保住了"主循环真的卡了几分钟"那段真实使用时长的记账。
     */
    private fun computeDelta(nowElapsed: Long, nowUptime: Long): Long {
        val lastMonotonic = lastSampleElapsed
        val lastAwake = lastSampleUptime
        if (lastMonotonic < 0 || lastAwake < 0) return 0L
        val deltaMonotonic = nowElapsed - lastMonotonic
        val deltaAwake = nowUptime - lastAwake
        if (deltaMonotonic < 0 || deltaAwake < 0) {
            Logger.w(TAG) { "monotonic clock went backwards (reboot?), resetting baseline" }
            return 0L
        }
        // 取较小值 = "设备真正醒着的时长"（见类注释「增量必须取两个单调钟的较小值」）：
        // 深睡时 elapsedRealtime 前进而 uptime 不动 → 取 uptime，睡眠不计入使用；
        // 主循环被拖慢时两者同步前进 → 全额计入，不再少算。
        return minOf(deltaMonotonic, deltaAwake).coerceAtMost(MAX_DELTA_MS)
    }

    // ==================== 单次会话状态机 ====================

    /**
     * 确保 [sessions] 里 [pkg] 的条目是"本次连续使用"的正确累计：续算、重开、或新建。
     *
     * 判定只看一个量：**离开该应用多久了**（[awayMs]）。
     * - 不满 [SINGLE_SESSION_REST_MS]：续算（孩子按 Home 再点回来不给新额度）；
     * - 达到阈值、换了日期、或从未有过记录：重新起表。
     *
     * 本方法每轮采样都会调用（间隔 15 秒），所以"离开时长"实际是 `now - 上轮 touch`，
     * 正常路径永远是"续算"；只有真的切走、息屏、重启过才会超过阈值。
     */
    private fun enterSession(pkg: String, dayKey: String, nowElapsed: Long, nowWall: Long) {
        restoreSessionsIfNeeded()
        val cur = sessions[pkg]
        sessions[pkg] = if (cur == null || cur.dayKey != dayKey) {
            SingleSession(pkg, dayKey, accumulatedMs = 0L, touchWallMs = nowWall, touchMonoMs = nowElapsed)
        } else {
            val away = awayMs(cur, nowElapsed, nowWall)
            if (away >= SINGLE_SESSION_REST_MS) {
                Logger.i(TAG) { "single session rest satisfied (away=${away}ms), restarting $pkg" }
                SingleSession(pkg, dayKey, accumulatedMs = 0L, touchWallMs = nowWall, touchMonoMs = nowElapsed)
            } else {
                cur.copy(touchWallMs = nowWall, touchMonoMs = nowElapsed)
            }
        }
        trimSessions(keep = pkg)
    }

    /**
     * 把**全部**会话的"最后一次仍在用"时刻结算到 [nowWall] / [nowElapsed]。
     *
     * 结算全部而不是只结算当前前台：离开时我们并不知道下一次前台是谁，
     * 把所有条目的 touch 都推到"离开那一刻"，各自回来时才能算出正确的离开时长。
     * （若只结算当前前台，被跳到后台的那个应用 touch 会停留在上一轮，
     * 回来时被判成"离开了十几秒"—— 虽然结果也是续算，但息屏一夜的情形会算错）。
     *
     * 幂等：只更新时间戳，不动累计量。调用方保证"只在状态**刚**变化时调用一次"
     * （息屏进入、护眼休息开始），否则息屏期间每轮刷新 touch 会让长离开永远算成"刚离开"。
     */
    private fun settleSession(nowElapsed: Long, nowWall: Long) {
        if (sessions.isEmpty()) return
        sessions.replaceAll { _, s -> s.copy(touchWallMs = nowWall, touchMonoMs = nowElapsed) }
    }

    /** 累加某应用的会话时长；该应用没有会话条目时忽略（不凭空造一条 0 起点的记录） */
    private fun accumulateSession(pkg: String, dayKey: String, deltaMs: Long, nowElapsed: Long, nowWall: Long) {
        val cur = sessions[pkg] ?: return
        if (cur.dayKey != dayKey) return
        sessions[pkg] = cur.copy(
            accumulatedMs = cur.accumulatedMs + deltaMs,
            touchWallMs = nowWall,
            touchMonoMs = nowElapsed
        )
    }

    /**
     * 距离上次"仍在用"过去了多久。
     *
     * 单调钟优先：同一次开机内改系统时间既不能缩短也不能拉长这个值。
     * 设备重启后单调读数归零（`touchMono > nowElapsed`），退回受信墙钟差；
     * 回拨导致差值为负时按 0 处理 —— 即"当没离开过"，续算（宁可少放行，不可多放行）。
     */
    private fun awayMs(s: SingleSession, nowElapsed: Long, nowWall: Long): Long {
        // 单调读数为负（脏数据）或大于当前读数（已重启）时才退回墙钟
        if (s.touchMonoMs in 0..nowElapsed) return nowElapsed - s.touchMonoMs
        return (nowWall - s.touchWallMs).coerceAtLeast(0L)
    }

    /**
     * 淘汰超额条目，保留最近碰过的 [MAX_TRACKED_SESSIONS] 条。
     *
     * [keep] 是当前前台应用，必须留下 —— 否则刚建立就被自己挤掉，
     * 表现为"单次限额间歇性失效"，这种 bug 极难复现。
     */
    private fun trimSessions(keep: String) {
        if (sessions.size <= MAX_TRACKED_SESSIONS) return
        val evictable = sessions.values
            .filter { it.packageName != keep }
            .sortedBy { it.touchWallMs }
        var over = sessions.size - MAX_TRACKED_SESSIONS
        for (s in evictable) {
            if (over <= 0) break
            sessions.remove(s.packageName)
            over--
        }
    }

    /** 惰性从磁盘恢复会话表；只尝试一次（读失败当没有，不每轮重试） */
    private fun restoreSessionsIfNeeded() {
        if (sessionRestoreAttempted) return
        sessionRestoreAttempted = true
        val loaded = runCatching { sessionStore.loadAll() }.getOrNull().orEmpty()
        if (loaded.isEmpty()) return
        for (s in loaded) {
            sessions[s.packageName] = s
        }
        lastPersisted = sessions.values.sortedBy { it.packageName }
        trimSessions(keep = "")
        Logger.i(TAG) { "single sessions restored: ${sessions.values.joinToString { "${it.packageName}=${it.accumulatedMs}ms" }}" }
    }

    /** 落盘；内容未变则跳过（每轮 15 秒无条件写盘没有必要） */
    private fun persistSession() {
        // 按包名排序后再比较：ConcurrentHashMap 的遍历顺序不稳定，
        // 直接比 list 会因为顺序抖动判成"内容变了"，每轮都白写一次盘。
        val snapshot = sessions.values.sortedBy { it.packageName }
        if (snapshot == lastPersisted) return
        lastPersisted = snapshot
        runCatching { sessionStore.saveAll(snapshot) }
    }

    /**
     * 跨日处理。
     *
     * **首次采样（[currentDayKey] 为空）不算跨日**：那是进程刚起来，
     * 此时必须保留从磁盘恢复的会话，否则"重启一次就续杯"的缺口等于没修。
     * 真正的跨日才清会话——新的一天有新的单次额度。
     */
    private fun onDayRollover(newDayKey: String) {
        val realRollover = currentDayKey.isNotEmpty() && currentDayKey != newDayKey
        Logger.i(TAG) { "day key: $currentDayKey -> $newDayKey (rollover=$realRollover)" }
        currentDayKey = newDayKey
        notifiedReminders.clear()
        if (realRollover) {
            sessions.clear()
            lastPersisted = null
            persistSession()
        }
    }

    /** 重启或权限重置后调用，丢弃采样基准，防止把关机时间算进使用时长 */
    fun resetSampling() {
        lastSampleElapsed = -1L
        lastSampleUptime = -1L
        lastForegroundPackage = null
        wasScreenOn = true
        wasEyeCareResting = false
        // ⚠️ 刻意**不清** [sessions]：本方法由 onDeviceBoot() 调用，
        // 清掉就等于"重启一次拿一份全新单次额度"（见类注释「单次会话的两次加固」②）。
        // 需要真正清会话的场景（家长重置策略）请调 [resetSession]。
    }

    /**
     * 真正丢弃单次会话（家长端「重置策略」等明确重置动作）。
     *
     * 与 [resetSampling] 分开是必要的：前者是"重新开始采样"，后者是"清零已用额度"，
     * 合成一个方法时必然有一边语义是错的 —— 早期就是合成一个，导致开机顺带清零了会话。
     */
    fun resetSession() {
        sessions.clear()
        lastPersisted = null
        sessionRestoreAttempted = true
        persistSession()
    }

    /** 家长手动"重置今日额度"时调用（需服务端指令授权），仅清提醒去重标记 */
    fun clearReminderCache() = notifiedReminders.clear()

    companion object {
        private const val TAG = "AppLimitEnforcer"

        /** 建议采样间隔：15 秒。再密耗电明显，再疏则限额误差过大 */
        const val SAMPLE_INTERVAL_MS = 15_000L

        /**
         * 单次采样计入时长的**防御性上限**：10 分钟。
         *
         * 只用于挡住脏值与病态卡顿，**不再承担"排除深睡"的职责** ——
         * 深睡由 `min(elapsedRealtime 差值, uptimeMillis 差值)` 天然排除
         * （见 [computeDelta]）。
         *
         * 为什么从 60 秒抬到 10 分钟：主循环是串行的，一次弱网长轮询 / GC / 系统冻结
         * 就可能让采样间隔超过 60 秒；旧值会把这段真实使用时长截成 1 分钟，
         * 等于每卡一次就白送额度（少记，对孩子有利）。10 分钟足以覆盖任何单次卡顿，
         * 又不至于让一个真实异常值把额度一次吃光。
         */
        const val MAX_DELTA_MS = 10 * 60_000L

        /**
         * 单次会话的"有效休息"阈值：5 分钟。
         *
         * 离开应用满 5 分钟再回来，才算"休息过"，单次额度重新起表；
         * 不满 5 分钟（按 Home 键喝口水就回来）**继续累计**。
         *
         * ## 为什么必须有这个阈值
         * 没有它时，只要前台应用变化就重置计时，孩子按一下 Home 再点回来即可无限续时，
         * `singleMinutes` 形同虚设。阈值不能太小（2 秒就续上等于没拦），
         * 也不能太大（用 30 分钟要休息 15 分钟过于严苛，会引发对抗）。
         * 5 分钟是"起身上个厕所"的量级，既堵住秒切绕过，又不至于把正常使用切碎。
         */
        const val SINGLE_SESSION_REST_MS = 5 * 60_000L

        /**
         * 会话表容量上限：4 个应用。
         *
         * 一台设备可能装上百个应用，会话表若跟着安装量长，每轮采样（15 秒）的遍历
         * 与落盘都会白白放大。4 条足够覆盖"孩子在游戏/视频/聊天之间来回切"的真实场景；
         * 被淘汰的应用下次进入从 0 起算，属可接受降级（它已冷落超过一轮会话时长）。
         */
        const val MAX_TRACKED_SESSIONS = 4
    }
}

/** 限额判定结果 */
sealed interface LimitVerdict {
    data object Allowed : LimitVerdict

    /** 额度即将耗尽的提醒，不阻断使用 */
    data class Warning(val packageName: String, val remainingMinutes: Int) : LimitVerdict

    data class Blocked(
        val packageName: String,
        val reason: LimitReason,
        val usedMinutes: Int,
        val quotaMinutes: Int
    ) : LimitVerdict {
        /** 展示给孩子的文案，避免 UI 层散落 when 分支 */
        fun message(): String = when (reason) {
            LimitReason.DAILY_TOTAL -> "今日总使用时长已达上限 ${quotaMinutes} 分钟"
            LimitReason.APP_DAILY -> "该应用今日可用时长已用完（${quotaMinutes} 分钟）"
            LimitReason.APP_SINGLE -> "单次连续使用已达 ${quotaMinutes} 分钟，先休息一下"
            LimitReason.APP_LAUNCH_COUNT -> "该应用今日打开次数已达上限（${quotaMinutes} 次）"
        }
    }
}

enum class LimitReason { DAILY_TOTAL, APP_DAILY, APP_SINGLE, APP_LAUNCH_COUNT }
