package com.padguard.core.engine.enforcer

import com.padguard.core.common.Logger
import com.padguard.core.common.TimeProvider
import com.padguard.core.data.model.policy.AppLimitPolicy
import com.padguard.core.data.repository.UsageRepository
import com.padguard.core.engine.schedule.ScheduleEvaluator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

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
 */
class AppLimitEnforcerTest {

    private lateinit var repo: UsageRepository
    private lateinit var timeProvider: TimeProvider
    private var elapsed = 0L
    private var wall = 0L
    private var originalLoggerEnabled = true

    @Before
    fun setUp() {
        originalLoggerEnabled = Logger.enabled
        Logger.enabled = false
        repo = mock(UsageRepository::class.java)
        timeProvider = mock(TimeProvider::class.java)
        `when`(timeProvider.elapsedRealtime()).thenAnswer { elapsed }
        `when`(timeProvider.now()).thenAnswer { wall }
        `when`(repo.dayKey(anyLong())).thenReturn("2026-10-16")
    }

    @After
    fun tearDown() {
        Logger.enabled = originalLoggerEnabled
    }

    /** ScheduleEvaluator 在本测试中无规则命中，mock 即可（空策略 evaluate 不会触碰 dayTypeOf） */
    private fun evaluator(): ScheduleEvaluator = mock(ScheduleEvaluator::class.java)

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

        verify(repo, times(2)).addTotalUsage(anyLong(), anyLong())
        verify(repo, times(2)).addAppUsage(eq("com.game"), anyLong(), anyLong())
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
        verify(repo, times(1)).addTotalUsage(anyLong(), anyLong())
        verify(repo, times(1)).addAppUsage(eq("com.game"), anyLong(), anyLong())

        // 3) 进入护眼休息锁屏（eyeCareResting=true），屏幕仍亮：不应再累计
        elapsed = 30_000L; wall = 1_030_000L
        enforcer.track(AppLimitPolicy(), "com.game", true, true)

        // 计数仍为 1，证明休息 tick 未新增任何累计
        verify(repo, times(1)).addTotalUsage(anyLong(), anyLong())
        verify(repo, times(1)).addAppUsage(eq("com.game"), anyLong(), anyLong())
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
        verify(repo, times(1)).addAppUsage(eq("com.game"), anyLong(), anyLong())
        // com.video 在休息期间不应被累计
        verify(repo, times(0)).addAppUsage(eq("com.video"), anyLong(), anyLong())

        // 休息结束后恢复亮屏非休息：com.video 作为新前台应被累计一次
        elapsed = 45_000L; wall = 1_045_000L
        enforcer.track(AppLimitPolicy(), "com.video", true, false)
        verify(repo, times(1)).addAppUsage(eq("com.video"), anyLong(), anyLong())
    }
}
