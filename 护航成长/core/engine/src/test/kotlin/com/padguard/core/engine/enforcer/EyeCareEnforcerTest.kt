package com.padguard.core.engine.enforcer

import com.padguard.core.common.Logger
import com.padguard.core.common.TimeProvider
import com.padguard.core.data.model.policy.EyeCarePolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

/**
 * [EyeCareEnforcer] 护眼强制休息的 JVM 行为测试。
 *
 * 聚焦"休息不计入每日总时长"在端上的前置环节：
 * 1. 触发休息后自然结束 / 家长提前 skip，都应产出 (restId, 实际休息毫秒) 待上报事件；
 * 2. 实际休息毫秒以墙钟差值核算，并钳制到 [0, restCapMs]（restMinutes*60000），防异常值放大扣除；
 * 3. [consumePendingRestReport] 取走即清空，避免重复上报；
 * 4. 非强制休息的自然息屏清零不产出上报事件；
 * 5. 策略被关闭时静默清零、不上报（该状态下本就无每日总时长管控）。
 *
 * JVM 限制：真实 [TimeProvider] 的 elapsedRealtime 会调到 android.os.SystemClock stub 抛 "Stub!"，
 * 故用 Mockito mock 注入可控时钟（:core:engine 已启用 Mockito inline mockmaker，可 mock final 类/方法）。
 */
class EyeCareEnforcerTest {

    private lateinit var timeProvider: TimeProvider
    private var elapsed = 0L          // 受控单调时钟（含休眠）
    private var wall = 0L             // 受控墙钟（服务端校准）
    private var originalLoggerEnabled = true

    @Before
    fun setUp() {
        originalLoggerEnabled = Logger.enabled
        Logger.enabled = false
        timeProvider = mock(TimeProvider::class.java)
        // 测试间手动改写 elapsed / wall 即可驱动状态机；闭包读取当前值
        `when`(timeProvider.elapsedRealtime()).thenAnswer { elapsed }
        `when`(timeProvider.now()).thenAnswer { wall }
    }

    @After
    fun tearDown() {
        Logger.enabled = originalLoggerEnabled
    }

    private fun policy(restMinutes: Int = 10, continuousMinutes: Int = 40, forceLock: Boolean = true) =
        EyeCarePolicy(
            enabled = true,
            continuousMinutes = continuousMinutes,
            restMinutes = restMinutes,
            forceLockDuringRest = forceLock
        )

    /**
     * 把连续使用时长推过阈值，触发一次强制休息。
     * 返回 (触发时刻的 elapsed, 触发时刻的 wall)，供后续结束/跳过测试精确驱动时钟。
     *
     * 每次推进正好 MAX_DELTA_MS(60s) 步长：既符合增量钳制上限，又能稳定累计到 40min 阈值。
     */
    private fun triggerRest(enforcer: EyeCareEnforcer, restMinutes: Int = 10): Pair<Long, Long> {
        elapsed = 0L; wall = 1_000_000L
        enforcer.track(policy(restMinutes = restMinutes), screenOn = true) // 首次采样：建立基线（delta=0）
        val step = 60_000L
        while (true) {
            elapsed += step
            wall += step
            val v = enforcer.track(policy(restMinutes = restMinutes), screenOn = true)
            if (v is EyeCareVerdict.Resting) return Pair(elapsed, wall) // 触发时刻的时钟
        }
    }

    @Test
    fun `natural finish reports rest id and actual elapsed wall time`() {
        val enforcer = EyeCareEnforcer(timeProvider)
        val (triggerElapsed, triggerWall) = triggerRest(enforcer, restMinutes = 10)

        // 休息结束：单调时钟越过 restUntilElapsed；墙钟推进 5 分钟（< 10min 上限）
        elapsed = triggerElapsed + 30 * 60_000L
        wall = triggerWall + 5 * 60_000L
        val verdict = enforcer.track(policy(restMinutes = 10), screenOn = true)
        assertTrue("自然结束应回到 Idle", verdict is EyeCareVerdict.Idle)

        val report = enforcer.consumePendingRestReport()
        assertTrue("应产出待上报的休息事件", report != null)
        val (restId, actualMs) = report!!
        assertEquals("restId 应为触发时的墙钟", triggerWall.toString(), restId)
        assertEquals(5 * 60_000L, actualMs) // 实际休息 5 分钟
    }

    @Test
    fun `actual rest time is clamped to rest cap`() {
        val enforcer = EyeCareEnforcer(timeProvider)
        val (triggerElapsed, triggerWall) = triggerRest(enforcer, restMinutes = 10)

        // 墙钟推进 12 分钟（> 10min 上限），应被钳制到 10min
        elapsed = triggerElapsed + 30 * 60_000L
        wall = triggerWall + 12 * 60_000L
        enforcer.track(policy(restMinutes = 10), screenOn = true)

        val (_, actualMs) = enforcer.consumePendingRestReport()!!
        assertEquals(10 * 60_000L, actualMs)
    }

    @Test
    fun `skipRest reports actual rest time then clears`() {
        val enforcer = EyeCareEnforcer(timeProvider)
        val (triggerElapsed, triggerWall) = triggerRest(enforcer, restMinutes = 10)

        // 休息进行中（单调时钟未到结束点），家长授权提前结束
        elapsed = triggerElapsed + 3 * 60_000L
        wall = triggerWall + 3 * 60_000L
        enforcer.skipRest()

        val (_, actualMs) = enforcer.consumePendingRestReport()!!
        assertEquals(3 * 60_000L, actualMs)

        // 取走即清空
        assertNull(enforcer.consumePendingRestReport())
    }

    @Test
    fun `no report when not in enforced rest (natural screen-off break)`() {
        val enforcer = EyeCareEnforcer(timeProvider)
        // 连续使用远未达阈值，仅息屏清零，不应产出强制休息上报
        elapsed = 0L; wall = 1_000_000L
        enforcer.track(policy(), screenOn = true)
        elapsed = 60_000L; wall = 1_060_000L
        enforcer.track(policy(), screenOn = true)
        elapsed = 120_000L; wall = 1_120_000L
        enforcer.track(policy(), screenOn = false) // 息屏，未达强制休息

        assertNull("自然息屏清零不应产出强制休息上报", enforcer.consumePendingRestReport())
    }

    @Test
    fun `disabled policy resets and reports nothing`() {
        val enforcer = EyeCareEnforcer(timeProvider)
        triggerRest(enforcer, restMinutes = 10)
        // 关闭护眼（该状态下本就无每日总时长管控，静默清零即可，不上报）
        enforcer.track(EyeCarePolicy(enabled = false), screenOn = true)
        assertNull("策略关闭后不应残留上报事件", enforcer.consumePendingRestReport())
    }
}
