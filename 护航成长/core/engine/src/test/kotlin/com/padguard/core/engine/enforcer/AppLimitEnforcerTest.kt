package com.padguard.core.engine.enforcer

import com.padguard.core.common.Logger
import com.padguard.core.common.TimeProvider
import com.padguard.core.data.db.AppUsageDao
import com.padguard.core.data.db.DailyUsageDao
import com.padguard.core.data.model.policy.AppLimitPolicy
import com.padguard.core.data.repository.UsageRepository
import com.padguard.core.engine.schedule.ScheduleEvaluator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

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

    /** 统计 mock 上某方法的调用次数（不依赖 matcher，天然兼容 suspend 的 continuation 形参）。 */
    private fun callCount(methodName: String): Int =
        mockingDetails(repo).invocations.count { it.method.name == methodName }

    /** 取出所有 addAppUsage 调用的首个实参（即被累计的应用包名），用于按应用断言。 */
    private fun appUsagePackages(): List<String> =
        mockingDetails(repo).invocations
            .filter { it.method.name == "addAppUsage" }
            .map { it.arguments[0] as String }

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
     * 计数替身：覆写 suspend 累计方法做调用计数，绕开纯 Mockito 对 suspend 函数 verify 的限制。
     * 父类 [UsageRepository] 已开放继承；dayKey() 等非 suspend 方法沿用真实实现（依赖 mock 的 timeProvider）。
     */
    private class CountingUsageRepository(
        timeProvider: TimeProvider
    ) : UsageRepository(mock(AppUsageDao::class.java), mock(DailyUsageDao::class.java), timeProvider) {
        var totalUsageCalls = 0
            private set
        private val appUsageByPackage = mutableMapOf<String, Int>()

        override suspend fun addTotalUsage(deltaMs: Long, timestamp: Long) {
            totalUsageCalls++
        }

        override suspend fun addAppUsage(packageName: String, deltaMs: Long, timestamp: Long) {
            appUsageByPackage[packageName] = appUsageByPackage.getOrDefault(packageName, 0) + 1
        }

        fun appUsageCalls(packageName: String): Int = appUsageByPackage.getOrDefault(packageName, 0)
    }
}
