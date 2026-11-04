package com.padguard.core.engine.enforcer

import com.padguard.core.common.Logger
import com.padguard.core.common.TimeProvider
import com.padguard.core.data.db.AppUsageDao
import com.padguard.core.data.db.DailyUsageDao
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
    private var elapsed = 0L
    private var wall = 0L
    private var originalLoggerEnabled = true

    @Before
    fun setUp() {
        originalLoggerEnabled = Logger.enabled
        Logger.enabled = false
        timeProvider = mock(TimeProvider::class.java)
        `when`(timeProvider.elapsedRealtime()).thenAnswer { elapsed }
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
            val enforcer = AppLimitEnforcer(repo, timeProvider, evaluator)
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
        val enforcer = AppLimitEnforcer(repo, timeProvider, evaluator())
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
        val enforcer = AppLimitEnforcer(repo, timeProvider, evaluator())
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
        val enforcer = AppLimitEnforcer(repo, timeProvider, evaluator())
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
        val enforcer = AppLimitEnforcer(repo, timeProvider, evaluator())
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
     * 计数替身：覆写 suspend 累计方法做调用计数，绕开纯 Mockito 对 suspend 函数 verify 的限制。
     * 父类 [UsageRepository] 已开放继承；dayKey() 等非 suspend 方法沿用真实实现（依赖 mock 的 timeProvider）。
     */
    private class CountingUsageRepository(
        timeProvider: TimeProvider
    ) : UsageRepository(mock(AppUsageDao::class.java), mock(DailyUsageDao::class.java), timeProvider) {
        var totalUsageCalls = 0
            private set
        private val appUsageByPackage = mutableMapOf<String, Int>()
        private val launchByPackage = mutableMapOf<String, Int>()

        override suspend fun addTotalUsage(deltaMs: Long, timestamp: Long) {
            totalUsageCalls++
        }

        override suspend fun addAppUsage(packageName: String, deltaMs: Long, timestamp: Long) {
            appUsageByPackage[packageName] = appUsageByPackage.getOrDefault(packageName, 0) + 1
        }

        override suspend fun addAppLaunch(packageName: String, timestamp: Long) {
            launchByPackage[packageName] = launchByPackage.getOrDefault(packageName, 0) + 1
        }

        fun appUsageCalls(packageName: String): Int = appUsageByPackage.getOrDefault(packageName, 0)

        fun launchCalls(packageName: String): Int = launchByPackage.getOrDefault(packageName, 0)
    }
}
