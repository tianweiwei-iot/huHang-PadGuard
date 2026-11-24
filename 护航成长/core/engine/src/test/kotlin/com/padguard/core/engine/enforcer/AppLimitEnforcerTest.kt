package com.padguard.core.engine.enforcer

import com.padguard.core.common.Logger
import com.padguard.core.common.TimeProvider
import com.padguard.core.data.db.AppUsageDao
import com.padguard.core.data.db.DailyUsageDao
import com.padguard.core.data.model.AppUsageStat
import com.padguard.core.data.model.policy.AppLimitPolicy
import com.padguard.core.data.model.policy.AppLimitRule
import com.padguard.core.data.model.policy.DayType
import com.padguard.core.data.repository.UsageRepository
import com.padguard.core.engine.schedule.HolidayProvider
import com.padguard.core.engine.schedule.ScheduleEvaluator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

/**
 * [AppLimitEnforcer] 护眼休息期间跳过本地累计的 JVM 行为测试。
 *
 * 需求落点（需求 B / C 的孩子端侧）：护眼强制休息锁屏期间，屏幕虽亮但设备被锁定，
 * 这段时间不属于"使用"，本地不应累计到 usageRepository；否则上报家长端的"今日已用"
 * 会与服务端权威口径（已扣减休息）产生偏差。
 *
 * 验证要点：
 * 1. 正常亮屏 tick 会调用 addTotalUsage / addAppUsage 累计；
 * 2. 当 eyeCareResting=true 时，同样的亮屏 tick 不再新增累计（仅更新前台包名基线以防休息结束后误记）。
 *
 * 关于 suspend 函数验证：Kotlin 会给 suspend 函数追加一个隐藏且非空的 [kotlin.coroutines.Continuation]
 * 参数，纯 Mockito 既无法在源码层显式写出该参数（"Too many arguments"），也无法用 `verify`
 * 干净匹配（matcher 数量与运行时参数数量失配 → InvalidUseOfMatchersException）。
 * 因此这里用 [CountingUsageRepository] 替身覆写 suspend 累计方法做计数，
 * 既绕开 Mockito 的 suspend 限制，又让断言聚焦于"是否累计"这一语义本身。
 */
class AppLimitEnforcerTest {

    private lateinit var repo: CountingUsageRepository
    private lateinit var timeProvider: TimeProvider
    private val store = FakeSessionStore()
    private var elapsed = 0L
    private var wall = 0L

    /**
     * `uptimeMillis` 相对 `elapsedRealtime` 的偏移，默认 0（两个钟同步走 = 设备一直醒着）。
     *
     * 模拟**深度睡眠**时把它往负方向调：elapsed 前进 8 小时、uptime 原地不动，
     * 正是真机上"睡了但 elapsedRealtime 照走"的样子。
     */
    private var awakeDrift = 0L

    private companion object {
        const val GAME = "com.game"
        const val LAUNCHER = "com.huawei.android.launcher"

        /** 2026-11-07 15:00 北京时间：周六，`dayTypes` 默认同时含工作日/周末，规则必然适用 */
        val NOON: Long = ZonedDateTime.of(2026, 11, 7, 15, 0, 0, 0, ZoneId.of("Asia/Shanghai"))
            .toInstant().toEpochMilli()
    }
    private var originalLoggerEnabled = true

    @Before
    fun setUp() {
        originalLoggerEnabled = Logger.enabled
        Logger.enabled = false
        timeProvider = mock(TimeProvider::class.java)
        `when`(timeProvider.elapsedRealtime()).thenAnswer { elapsed }
        `when`(timeProvider.uptimeMillis()).thenAnswer { elapsed + awakeDrift }
        `when`(timeProvider.now()).thenAnswer { wall }
        repo = CountingUsageRepository(timeProvider)
    }

    @After
    fun tearDown() {
        Logger.enabled = originalLoggerEnabled
    }

    /** ScheduleEvaluator 在本测试中无规则命中，mock 即可（空策略 evaluate 不会触碰 dayTypeOf） */
    private fun evaluator(): ScheduleEvaluator = mock(ScheduleEvaluator::class.java)

    // 注：这里原本还有两个用 `mockingDetails(repo)` 统计调用的辅助方法，已删除。
    // 其一，`repo` 是真实对象（CountingUsageRepository）而非 Mockito mock，
    // `mockingDetails` 对它调用必然抛 "NotAMockException"，逻辑本身就是错的；
    // 其二，它们在测试中从未被引用 —— 断言走的是替身自己的计数器
    // （repo.totalUsageCalls / repo.appUsageCalls）。
    // 结果是这两个死方法连同缺失的 import 让 `:core:engine` 的**整个测试源码集编译失败**，
    // 本模块所有单元测试从未真正跑过。删除后测试源码即可编译、用例开始生效。

    /**
     * 回归：规则适用性的「今天」必须与用量分桶同一口径（Asia/Shanghai），不能取设备时区。
     *
     * 构造：JVM 默认时区设成 UTC，时间取北京时间周六 00:30（= UTC 周五 16:30），
     * 规则只在周末生效，且提醒阈值（60 分钟）大于当日额度（30 分钟）——
     * 于是"规则是否生效"直接体现在返回值上：生效必然 Warning，不生效必然 Allowed。
     * 修复前用 systemDefault 判成「周五」，周六 00:00–08:00 的周末规则完全不生效。
     */
    @Test
    fun `rule applicability follows the usage day bucket instead of the device timezone`() = runBlocking {
        val originalTz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            elapsed = 0L
            wall = ZonedDateTime.of(2026, 11, 7, 0, 30, 0, 0, ZoneId.of("Asia/Shanghai"))
                .toInstant().toEpochMilli()
            val evaluator = ScheduleEvaluator(
                timeProvider,
                object : HolidayProvider {
                    override fun isHoliday(date: LocalDate) = false
                    override fun isWorkdayOverride(date: LocalDate) = false
                }
            )
            val enforcer = AppLimitEnforcer(repo, timeProvider, evaluator, store)
            val verdict = enforcer.evaluate(
                AppLimitPolicy(
                    reminderMinutes = listOf(60),
                    rules = listOf(
                        AppLimitRule(
                            packageName = "com.game",
                            dailyMinutes = 30,
                            dayTypes = listOf(DayType.WEEKEND)
                        )
                    )
                ),
                "com.game"
            )
            assertTrue(
                "北京时间周六 00:30 必须按周六判定（额度规则应生效），实际：$verdict",
                verdict is LimitVerdict.Warning
            )
            // JUnit4 要求 @Test 方法返回 void；runBlocking 会返回 lambda 最后一个表达式的类型
            Unit
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun `normal screen-on ticks accumulate usage`() = runBlocking {
        val enforcer = AppLimitEnforcer(repo, timeProvider, evaluator(), store)
        // 1) 建立采样基线（delta=0），前景 com.game
        elapsed = 0L; wall = 1_000_000L
        enforcer.track(AppLimitPolicy(), "com.game", true, false)
        // 2) 亮屏非休息：应累计一次
        elapsed = 15_000L; wall = 1_015_000L
        enforcer.track(AppLimitPolicy(), "com.game", true, false)
        // 3) 再次亮屏非休息：累计第二次
        elapsed = 30_000L; wall = 1_030_000L
        enforcer.track(AppLimitPolicy(), "com.game", true, false)

        assertEquals(2, repo.totalUsageCalls)
        assertEquals(2, repo.appUsageCalls("com.game"))
    }

    @Test
    fun `eyeCareResting skips total and app usage accumulation`() = runBlocking {
        val enforcer = AppLimitEnforcer(repo, timeProvider, evaluator(), store)
        // 1) 建立采样基线（delta=0）
        elapsed = 0L; wall = 1_000_000L
        enforcer.track(AppLimitPolicy(), "com.game", true, false)
        // 2) 亮屏且非休息：正常累计一次
        elapsed = 15_000L; wall = 1_015_000L
        enforcer.track(AppLimitPolicy(), "com.game", true, false)
        assertEquals(1, repo.totalUsageCalls)
        assertEquals(1, repo.appUsageCalls("com.game"))

        // 3) 进入护眼休息锁屏（eyeCareResting=true），屏幕仍亮：不应再累计
        elapsed = 30_000L; wall = 1_030_000L
        enforcer.track(AppLimitPolicy(), "com.game", true, true)

        // 计数仍为 1，证明休息 tick 未新增任何累计
        assertEquals(1, repo.totalUsageCalls)
        assertEquals(1, repo.appUsageCalls("com.game"))
    }

    @Test
    fun `eyeCareResting still updates foreground baseline without accumulating`() = runBlocking {
        val enforcer = AppLimitEnforcer(repo, timeProvider, evaluator(), store)
        // 基线 + 第一次累计（前景 com.game）
        elapsed = 0L; wall = 1_000_000L
        enforcer.track(AppLimitPolicy(), "com.game", true, false)
        elapsed = 15_000L; wall = 1_015_000L
        enforcer.track(AppLimitPolicy(), "com.game", true, false)

        // 休息期间切换到 com.video（eyeCareResting=true）：只更新基线，不累计
        elapsed = 30_000L; wall = 1_030_000L
        enforcer.track(AppLimitPolicy(), "com.video", true, true)
        assertEquals(1, repo.appUsageCalls("com.game"))
        // com.video 在休息期间不应被累计
        assertEquals(0, repo.appUsageCalls("com.video"))

        // 休息结束后恢复亮屏非休息：com.video 作为新前台应被累计一次
        elapsed = 45_000L; wall = 1_045_000L
        enforcer.track(AppLimitPolicy(), "com.video", true, false)
        assertEquals(1, repo.appUsageCalls("com.video"))
    }

    /**
     * 息屏不得重置"单次会话"上下文。
     *
     * 曾经的实现是息屏时 `lastForegroundPackage = null; sessionStartElapsed = 0L`。
     * 两个后果都能被孩子直接利用：
     * ① 单次使用时长（APP_SINGLE）的计时起点被清零 —— 按两次电源键（息屏→亮屏，
     *    不需要解锁、不需要任何工具）就能让同一个应用"重新开始一次连续使用"，
     *    单次限额永远达不到；
     * ② 亮屏后第一帧必然满足"前台包 != 上次前台包"，被误判为应用切换，
     *    每次息屏/亮屏都多记一次启动，dailyLaunchCount 失真。
     *
     * 这条测试盯住 ②（可直接观测）。① 与 ② 出自同一处赋值，改回去时 ① 必然复发。
     */
    @Test
    fun `screen off keeps session context so wake does not fake an app launch`() = runBlocking {
        val enforcer = AppLimitEnforcer(repo, timeProvider, evaluator(), store)
        // 建立基线
        elapsed = 0L; wall = 1_000_000L
        enforcer.track(AppLimitPolicy(), "com.game", true, false)
        // 首次进入 com.game：记一次启动
        elapsed = 10_000L; wall = 1_010_000L
        enforcer.track(AppLimitPolicy(), "com.game", true, false)
        assertEquals(1, repo.launchCalls("com.game"))

        // 息屏：前台包未知（系统不上报），这段不计时
        elapsed = 20_000L; wall = 1_020_000L
        enforcer.track(AppLimitPolicy(), null, false, false)

        // 亮屏仍是同一个应用：这不是一次新的"应用切换"，不能再记启动；
        // 若这里变成 2，说明息屏把 lastForegroundPackage 清掉了（单次计时也被同时清零）。
        elapsed = 30_000L; wall = 1_030_000L
        enforcer.track(AppLimitPolicy(), "com.game", true, false)
        assertEquals(1, repo.launchCalls("com.game"))

        // 息屏期间确实换了应用：亮屏后应正常记一次启动
        elapsed = 45_000L; wall = 1_045_000L
        enforcer.track(AppLimitPolicy(), null, false, false)
        elapsed = 60_000L; wall = 1_060_000L
        enforcer.track(AppLimitPolicy(), "com.video", true, false)
        assertEquals(1, repo.launchCalls("com.video"))
    }

    /**
     * 单应用限额相关用例必须用**真实** [ScheduleEvaluator]：
     * `mock()` 的 `dayTypeOf` 返回 null，`ruleAppliesToday` 里 `null in rule.dayTypes` 为 false，
     * 规则会静默判成"今天不适用"，于是所有断言都在测"什么都没发生"——
     * 这类"测试自己失效"比测试失败更危险，会给出虚假的绿灯。
     */
    private fun realEvaluator(): ScheduleEvaluator = ScheduleEvaluator(
        timeProvider,
        object : HolidayProvider {
            override fun isHoliday(date: LocalDate) = false
            override fun isWorkdayOverride(date: LocalDate) = false
        }
    )

    /** 只配单次额度的策略；dailyMinutes / dailyLaunchCount 为 0 表示不启用 */
    private fun singlePolicy(minutes: Int): AppLimitPolicy = AppLimitPolicy(
        rules = listOf(AppLimitRule(packageName = GAME, singleMinutes = minutes))
    )

    @Test
    fun `single limit does not reset when child presses home and returns immediately`() = runBlocking {
        val e = AppLimitEnforcer(repo, timeProvider, realEvaluator(), store)
        val policy = singlePolicy(30)
        wall = NOON; elapsed = 0L
        e.track(policy, GAME, true, false)                       // 采样基线

        var t = 0L
        repeat(20) { t += 60_000; elapsed = t; wall += 60_000; e.track(policy, GAME, true, false) }

        // 按 Home（前台变桌面）20 秒后立刻点回游戏
        t += 20_000; elapsed = t; wall += 20_000
        e.track(policy, LAUNCHER, true, false)
        t += 10_000; elapsed = t; wall += 10_000
        e.track(policy, GAME, true, false)

        // 20 分钟 + 9 分钟 = 29 分钟：还没到 30 分钟，不该拦
        var before: LimitVerdict = LimitVerdict.Allowed
        repeat(9) { t += 60_000; elapsed = t; wall += 60_000; before = e.track(policy, GAME, true, false) }
        assertTrue("29 分钟不应拦截，实际 $before", before !is LimitVerdict.Blocked)

        // 第 30 分钟必须拦 —— 若按 Home 那一下把计时清零了，这里只累计到 10 分钟，必然不拦
        t += 60_000; elapsed = t; wall += 60_000
        val after = e.track(policy, GAME, true, false)
        assertTrue("按 Home 再回来不得重置单次计时，第 30 分钟应拦截，实际 $after", after is LimitVerdict.Blocked)
        assertEquals(LimitReason.APP_SINGLE, (after as LimitVerdict.Blocked).reason)
    }

    @Test
    fun `single limit restarts after a rest longer than the threshold`() = runBlocking {
        val e = AppLimitEnforcer(repo, timeProvider, realEvaluator(), store)
        val policy = singlePolicy(30)
        wall = NOON; elapsed = 0L
        e.track(policy, GAME, true, false)

        var t = 0L
        repeat(25) { t += 60_000; elapsed = t; wall += 60_000; e.track(policy, GAME, true, false) }

        // 真的走开 6 分钟（> 5 分钟休息阈值）
        t += 20_000; elapsed = t; wall += 20_000
        e.track(policy, LAUNCHER, true, false)
        t += 6 * 60_000; elapsed = t; wall += 6 * 60_000
        e.track(policy, LAUNCHER, true, false)

        // 回到游戏后再用 9 分钟：新会话只累计 9 分钟，总账 34 分钟也不该拦
        t += 10_000; elapsed = t; wall += 10_000
        e.track(policy, GAME, true, false)
        var v: LimitVerdict = LimitVerdict.Allowed
        repeat(9) { t += 60_000; elapsed = t; wall += 60_000; v = e.track(policy, GAME, true, false) }
        assertTrue("休息满 5 分钟应重新起表，实际 $v", v !is LimitVerdict.Blocked)
    }

    @Test
    fun `single limit survives a process restart within the same day`() = runBlocking {
        val policy = singlePolicy(30)
        wall = NOON; elapsed = 0L
        val first = AppLimitEnforcer(repo, timeProvider, realEvaluator(), store)
        first.track(policy, GAME, true, false)

        var t = 0L
        repeat(25) { t += 60_000; elapsed = t; wall += 60_000; first.track(policy, GAME, true, false) }

        // 进程被回收后重建：新实例只能从磁盘恢复会话
        t += 15_000; elapsed = t; wall += 15_000
        val restarted = AppLimitEnforcer(repo, timeProvider, realEvaluator(), store)
        restarted.track(policy, GAME, true, false)              // 新实例首轮：delta=0，只做恢复

        // 再累计 5 分钟就满 30 分钟，必须拦；若不落盘，这里只有 5 分钟，不会拦
        var v: LimitVerdict = LimitVerdict.Allowed
        repeat(5) { t += 60_000; elapsed = t; wall += 60_000; v = restarted.track(policy, GAME, true, false) }
        assertTrue("重启不得刷新单次额度（累计应≥30 分钟），实际 $v", v is LimitVerdict.Blocked)
        assertEquals(LimitReason.APP_SINGLE, (v as LimitVerdict.Blocked).reason)
    }

    @Test
    fun `single session is dropped on a real day rollover`() = runBlocking {
        val e = AppLimitEnforcer(repo, timeProvider, realEvaluator(), store)
        val policy = singlePolicy(30)
        wall = NOON; elapsed = 0L
        e.track(policy, GAME, true, false)

        var t = 0L
        repeat(29) { t += 60_000; elapsed = t; wall += 60_000; e.track(policy, GAME, true, false) }

        // 跨日：新的一天有新的单次额度
        wall += 24 * 60 * 60_000L
        t += 60_000; elapsed = t
        e.track(policy, GAME, true, false)
        var v: LimitVerdict = LimitVerdict.Allowed
        repeat(5) { t += 60_000; elapsed = t; wall += 60_000; v = e.track(policy, GAME, true, false) }
        assertTrue("跨日后单次会话应清零，实际 $v", v !is LimitVerdict.Blocked)
    }

    /**
     * 护眼强制休息也算"休息过"。
     *
     * 反面用例很容易写错：如果休息期间每轮都结算 touch，休息结束那一刻 away 只有 15 秒，
     * 会被判成"没休息过"→ 继续累计，20 分钟护眼休息对单次额度等于不存在。
     */
    @Test
    fun `eye care rest counts as a real rest for the single session`() = runBlocking {
        val e = AppLimitEnforcer(repo, timeProvider, realEvaluator(), store)
        val policy = singlePolicy(30)
        wall = NOON; elapsed = 0L
        e.track(policy, GAME, true, false)

        var t = 0L
        repeat(25) { t += 60_000; elapsed = t; wall += 60_000; e.track(policy, GAME, true, false) }

        // 进入护眼强制休息 20 分钟（每轮都处于休息态）
        repeat(20) { t += 60_000; elapsed = t; wall += 60_000; e.track(policy, GAME, true, true) }

        // 休息结束后再用 9 分钟：应重新起表，不该拦
        var v: LimitVerdict = LimitVerdict.Allowed
        repeat(9) { t += 60_000; elapsed = t; wall += 60_000; v = e.track(policy, GAME, true, false) }
        assertTrue("20 分钟护眼休息应视为有效休息，实际 $v", v !is LimitVerdict.Blocked)
    }

    /**
     * 深睡不得计入使用时长。
     *
     * 旧实现靠"增量截断到 60 秒"掩盖这个问题：睡一夜再点亮，会平白多记 1 分钟。
     * 现在取 `min(elapsedRealtime 差值, uptimeMillis 差值)`，深睡期间 uptime 不走 → 记 0。
     * 这里把 awakeDrift 调负模拟"elapsed 前进 8 小时、uptime 原地不动"。
     */
    @Test
    fun `deep sleep is not charged as usage time`() = runBlocking {
        val e = AppLimitEnforcer(repo, timeProvider, realEvaluator(), store)
        wall = NOON; elapsed = 0L
        e.track(AppLimitPolicy(rules = emptyList()), GAME, true, false)   // 采样基线

        var t = 0L
        repeat(5) { t += 60_000; elapsed = t; wall += 60_000; e.track(AppLimitPolicy(rules = emptyList()), GAME, true, false) }
        val awakeMs = repo.totalUsageMs
        assertEquals("醒着用 5 分钟应记满 5 分钟", 5 * 60_000L, awakeMs)

        // 睡 8 小时：elapsedRealtime 照走，uptimeMillis 停住
        val sleepMs = 8 * 60 * 60_000L
        awakeDrift -= sleepMs
        t += sleepMs; elapsed = t; wall += sleepMs
        e.track(AppLimitPolicy(rules = emptyList()), GAME, true, false)

        assertEquals("深睡期间不得累计任何使用时长", awakeMs, repo.totalUsageMs)
    }

    /**
     * 主循环被拖慢（弱网长轮询 / GC / 系统冻结）时，这段真实使用时长必须**完整**计入。
     *
     * 旧实现把单次增量截断到 60 秒：卡了 3 分钟只记 1 分钟，等于每卡一次白送 2 分钟额度。
     * 两个钟在"设备一直醒着"时同步前进，取较小值仍是全额。
     */
    @Test
    fun `a stalled sampling loop still charges the real awake time`() = runBlocking {
        val e = AppLimitEnforcer(repo, timeProvider, realEvaluator(), store)
        wall = NOON; elapsed = 0L
        e.track(AppLimitPolicy(rules = emptyList()), GAME, true, false)
        repo.reset()

        // 一次卡顿 3 分钟（设备全程醒着，awakeDrift 保持 0）
        elapsed = 3 * 60_000L; wall += 3 * 60_000L
        e.track(AppLimitPolicy(rules = emptyList()), GAME, true, false)

        assertEquals("卡顿 3 分钟应完整计入，而不是被截成 1 分钟", 3 * 60_000L, repo.totalUsageMs)
    }

    /** 防御性上限仍在：异常大的增量被 [AppLimitEnforcer.MAX_DELTA_MS] 兜住，不会一次吃光额度 */
    @Test
    fun `an absurd delta is still capped by the defensive limit`() = runBlocking {
        val e = AppLimitEnforcer(repo, timeProvider, realEvaluator(), store)
        wall = NOON; elapsed = 0L
        e.track(AppLimitPolicy(rules = emptyList()), GAME, true, false)
        repo.reset()

        elapsed = 30 * 60_000L; wall += 30 * 60_000L
        e.track(AppLimitPolicy(rules = emptyList()), GAME, true, false)

        assertEquals(AppLimitEnforcer.MAX_DELTA_MS, repo.totalUsageMs)
    }

    /**
     * 当日累计时长限额（`usedMs >= quota`）必须直接拦截。
     *
     * 这条路径此前没有任何用例断言过 Blocked —— single-session 系列只覆盖了 APP_SINGLE，
     * 而 dailyMinutes 的拦截分支（`evaluate` 里 `usedMs >= quotaMs`）从未被任何测试触达。
     * 静默失效的拦截比失败更危险：家长设了"每天 30 分钟"，孩子端却一路放行。
     */
    @Test
    fun `daily minute cap blocks once used time reaches the quota`() = runBlocking {
        wall = NOON
        val e = AppLimitEnforcer(repo, timeProvider, realEvaluator(), store)
        repo.seedAppUsage(GAME, usedMs = 30 * 60_000L + 1_000L)
        val policy = AppLimitPolicy(rules = listOf(AppLimitRule(packageName = GAME, dailyMinutes = 30)))
        val verdict = e.evaluate(policy, GAME)
        assertTrue("达到每日时长额度应拦截，实际 $verdict", verdict is LimitVerdict.Blocked)
        assertEquals(LimitReason.APP_DAILY, (verdict as LimitVerdict.Blocked).reason)
    }

    /**
     * 当日启动次数限额（`launchCount > limit`）必须拦截。
     *
     * 与 [daily minute cap blocks once used time reaches the quota] 同样：APP_LAUNCH_COUNT
     * 分支此前没有任何用例断言过 Blocked（启动计数的累计由 [CountingUsageRepository] 计数，
     * 但"超限拦截"的判定从未被触达验证）。
     */
    @Test
    fun `daily launch count cap blocks after exceeding the limit`() = runBlocking {
        wall = NOON
        val e = AppLimitEnforcer(repo, timeProvider, realEvaluator(), store)
        // 限额 5 次：第 6 次（>5）必须拦截，前 5 次放行
        repo.seedAppUsage(GAME, launchCount = 6)
        val policy = AppLimitPolicy(rules = listOf(AppLimitRule(packageName = GAME, dailyLaunchCount = 5)))
        val verdict = e.evaluate(policy, GAME)
        assertTrue("当日打开次数超标应拦截，实际 $verdict", verdict is LimitVerdict.Blocked)
        assertEquals(LimitReason.APP_LAUNCH_COUNT, (verdict as LimitVerdict.Blocked).reason)
    }

    /**
     * 会话快照替身：模拟 SharedPreferences 的落盘再读。
     * 用真实编码路径（含分隔符）而不是直接存对象，才能顺带验证编解码的字段顺序。
     */
    private class FakeSessionStore : SingleSessionStore {
        private val saved = mutableMapOf<String, SingleSession>()

        override fun loadAll(): List<SingleSession> = saved.values.toList()

        override fun saveAll(sessions: List<SingleSession>) {
            saved.clear()
            sessions.forEach { saved[it.packageName] = it }
        }
    }

    /** 计数替身：覆写 suspend 累计方法做调用计数，绕开纯 Mockito 对 suspend 函数 verify 的限制。
     * 父类 [UsageRepository] 已开放继承；dayKey() 等非 suspend 方法沿用真实实现（依赖 mock 的 timeProvider）。
     */
    private class CountingUsageRepository(
        timeProvider: TimeProvider
    ) : UsageRepository(mock(AppUsageDao::class.java), mock(DailyUsageDao::class.java), timeProvider) {
        var totalUsageCalls = 0
            private set

        /** 累计记入的总使用毫秒（原样累加 delta，供"深睡/卡顿"用例断言） */
        var totalUsageMs = 0L
            private set

        private val appUsageByPackage = mutableMapOf<String, Int>()
        private val appUsageMsByPackage = mutableMapOf<String, Long>()
        private val launchByPackage = mutableMapOf<String, Int>()
        /** 受控的"当日已用统计"，供断言 dailyMinutes / dailyLaunchCount 拦截分支 */
        private val seededStats = mutableMapOf<String, AppUsageStat>()

        /** 注入某应用当日的累计毫秒 / 启动次数，使 [getAppUsage] 返回受控值 */
        fun seedAppUsage(packageName: String, usedMs: Long = 0L, launchCount: Int = 0) {
            seededStats[packageName] = AppUsageStat(packageName, "20261107", usedMs, launchCount, 0L)
        }

        fun reset() {
            totalUsageCalls = 0
            totalUsageMs = 0L
            appUsageByPackage.clear()
            appUsageMsByPackage.clear()
            launchByPackage.clear()
            seededStats.clear()
        }

        override suspend fun addTotalUsage(deltaMs: Long, timestamp: Long) {
            totalUsageCalls++
            totalUsageMs += deltaMs
        }

        override suspend fun addAppUsage(packageName: String, deltaMs: Long, timestamp: Long) {
            appUsageByPackage[packageName] = appUsageByPackage.getOrDefault(packageName, 0) + 1
            appUsageMsByPackage[packageName] = appUsageMsByPackage.getOrDefault(packageName, 0L) + deltaMs
        }

        override suspend fun addAppLaunch(packageName: String, timestamp: Long) {
            launchByPackage[packageName] = launchByPackage.getOrDefault(packageName, 0) + 1
        }

        override suspend fun getAppUsage(packageName: String, key: String): AppUsageStat? = seededStats[packageName]

        fun appUsageCalls(packageName: String): Int = appUsageByPackage.getOrDefault(packageName, 0)

        fun launchCalls(packageName: String): Int = launchByPackage.getOrDefault(packageName, 0)
    }
}
