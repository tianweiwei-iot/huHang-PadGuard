package com.padguard.core.engine.enforcer

import com.padguard.core.common.Logger
import com.padguard.core.common.TimeProvider
import com.padguard.core.data.model.policy.WebPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * [SmartShutdownEnforcer] 纯逻辑测试。
 *
 * 覆盖重点是三个容易出错的边界：
 * 1. **跨零点区间**（22:30–06:30）——误判成 `start < now < end` 会让夜间模式在前半夜完全失效；
 * 2. **区间内的解除时刻是否落在正确的一天**——算错就是"锁到时间也解不开"；
 * 3. **非法/残缺输入一律返回 null**——返回 0 或 true 会把设备锁死一整天，
 *    这是本类最危险的失效模式，必须有用例钉住。
 *
 * 时刻统一以 UTC 构造（判定时刻与时区同时显式给出），绕开本地时区干扰。
 */
class SmartShutdownEnforcerTest {

    private val timeProvider = TimeProvider()
    private lateinit var enforcer: SmartShutdownEnforcer
    private var originalLoggerEnabled = true

    @Before
    fun setUp() {
        originalLoggerEnabled = Logger.enabled
        Logger.enabled = false
        enforcer = SmartShutdownEnforcer(timeProvider)
    }

    @After
    fun tearDown() {
        Logger.enabled = originalLoggerEnabled
    }

    private fun at(hour: Int, minute: Int): Long =
        LocalDateTime.of(2026, 10, 17, hour, minute)
            .atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()

    private fun policy(start: String?, end: String?, enabled: Boolean = true) = WebPolicy(
        smartShutdownEnabled = enabled,
        smartShutdownStartTime = start,
        smartShutdownEndTime = end
    )

    // ---------- 未开启 / 残缺输入 ----------

    @Test
    fun `开关关闭时不锁`() {
        assertNull(enforcer.evaluate(policy("22:00", "07:00", enabled = false), at(23, 0), "UTC"))
    }

    @Test
    fun `开启但未填时间时不锁 - 不得锁死整天`() {
        assertNull(enforcer.evaluate(policy(null, null), at(23, 0), "UTC"))
        assertNull(enforcer.evaluate(policy("22:00", null), at(23, 0), "UTC"))
        assertNull(enforcer.evaluate(policy(null, "07:00"), at(23, 0), "UTC"))
        assertNull(enforcer.evaluate(policy("", ""), at(23, 0), "UTC"))
    }

    @Test
    fun `非法时间格式时不锁`() {
        assertNull(enforcer.evaluate(policy("22点", "07点"), at(23, 0), "UTC"))
        assertNull(enforcer.evaluate(policy("25:00", "30:00"), at(23, 0), "UTC"))
    }

    // ---------- 同日区间（start < end） ----------

    @Test
    fun `同日区间内在抚养期锁`() {
        val until = enforcer.evaluate(policy("09:00", "17:00"), at(12, 0), "UTC")
        assertNotNull(until)
        assertEquals(LocalDateTime.of(2026, 10, 17, 17, 0)
            .atZone(ZoneId.of("UTC")).toInstant().toEpochMilli(), until)
    }

    @Test
    fun `同日区间外不锁`() {
        assertNull(enforcer.evaluate(policy("09:00", "17:00"), at(8, 59), "UTC"))
        assertNull(enforcer.evaluate(policy("09:00", "17:00"), at(17, 0), "UTC"))
    }

    @Test
    fun `左闭右开边界`() {
        assertNotNull(enforcer.evaluate(policy("09:00", "17:00"), at(9, 0), "UTC"))
        assertNull(enforcer.evaluate(policy("09:00", "17:00"), at(17, 0), "UTC"))
    }

    // ---------- 跨零点区间（start > end） ----------

    @Test
    fun `跨零点区间前半夜锁且解除时刻落在次日`() {
        val until = enforcer.evaluate(policy("22:30", "06:30"), at(23, 30), "UTC")
        assertNotNull(until)
        assertEquals(LocalDateTime.of(2026, 10, 18, 6, 30)
            .atZone(ZoneId.of("UTC")).toInstant().toEpochMilli(), until)
    }

    @Test
    fun `跨零点区间后半夜锁且解除时刻落在当日`() {
        val until = enforcer.evaluate(policy("22:30", "06:30"), at(3, 0), "UTC")
        assertNotNull(until)
        assertEquals(LocalDateTime.of(2026, 10, 17, 6, 30)
            .atZone(ZoneId.of("UTC")).toInstant().toEpochMilli(), until)
    }

    @Test
    fun `跨零点区间白天不锁`() {
        assertNull(enforcer.evaluate(policy("22:30", "06:30"), at(22, 29), "UTC"))
        assertNull(enforcer.evaluate(policy("22:30", "06:30"), at(6, 30), "UTC"))
        assertNull(enforcer.evaluate(policy("22:30", "06:30"), at(14, 0), "UTC"))
    }

    @Test
    fun `起止相等视为无效区间，不锁死`() {
        assertNull(enforcer.evaluate(policy("22:00", "22:00"), at(22, 0), "UTC"))
    }

    // ---------- 时区 ----------

    @Test
    fun `时区影响判定结果`() {
        // UTC 14:00 == Asia/Shanghai 22:00
        val p = policy("22:00", "06:00")
        assertNull(enforcer.evaluate(p, at(14, 0), "UTC"))
        assertNotNull(enforcer.evaluate(p, at(14, 0), "Asia/Shanghai"))
    }

    @Test
    fun `非法时区回退到系统默认而不抛异常`() {
        assertNull(enforcer.evaluate(policy("22:00", "07:00"), at(12, 0), "Not/AZone"))
    }
}
