package com.padguard.core.data.model.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PolicyPackage] 与 [ScheduleRule] 纯数据逻辑测试。
 *
 * 这些模型只依赖 kotlinx.serialization 注解与标准库，无任何 Android 依赖，
 * 因此可在普通 JVM 单元测试中直接构造与断言，无需 Robolectric。
 */
class PolicyModelsTest {

    // region ====== ScheduleRule.toMinutes ======

    @Test
    fun toMinutesParsesHHmm() {
        assertEquals(1290, ScheduleRule.toMinutes("21:30"))
        assertEquals(390, ScheduleRule.toMinutes("06:30"))
        assertEquals(0, ScheduleRule.toMinutes("00:00"))
        assertEquals(1439, ScheduleRule.toMinutes("23:59"))
    }

    @Test
    fun toMinutesHandlesMalformedInput() {
        // 畸形输入必须返回 -1（无效），不能返回 0：0 等价 00:00，会把 LOCK/UNLOCK 窗口放大成全天
        assertEquals(-1, ScheduleRule.toMinutes("bad"))
        assertEquals(-1, ScheduleRule.toMinutes(""))
        // "1:2" -> 1*60 + 2 = 62（无格式校验，仅做容错）
        assertEquals(62, ScheduleRule.toMinutes("1:2"))
        // 越界值按畸形处理
        assertEquals(-1, ScheduleRule.toMinutes("25:00"))
        assertEquals(-1, ScheduleRule.toMinutes("12:60"))
        // 合法边界
        assertEquals(0, ScheduleRule.toMinutes("00:00"))
        assertEquals(23 * 60 + 59, ScheduleRule.toMinutes("23:59"))
        // 畸形规则可被 isWellFormed 识别并整条丢弃
        assertFalse(ScheduleRule(start = "bad").isWellFormed())
        assertTrue(ScheduleRule(start = "21:30", end = "06:30").isWellFormed())
        // 畸形规则 crossesMidnight 恒 false（不参与跨零点运算）
        assertFalse(ScheduleRule(start = "bad", end = "06:30").crossesMidnight())
    }

    // endregion

    // region ====== ScheduleRule.crossesMidnight ======

    @Test
    fun crossesMidnightDetection() {
        assertTrue(ScheduleRule(listOf(DayType.WEEKDAY), "21:30", "06:30").crossesMidnight())
        assertFalse(ScheduleRule(listOf(DayType.WEEKDAY), "08:00", "20:00").crossesMidnight())
        assertFalse(ScheduleRule(listOf(DayType.WEEKDAY), "00:00", "23:59").crossesMidnight())
    }

    // endregion

    // region ====== PolicyPackage.mergeWith ======

    @Test
    fun mergeWithIgnoresStaleVersion() {
        val base = PolicyPackage(version = 5, deviceId = "dev-1", scene = SceneType.FAMILY)
        val incoming = PolicyPackage(version = 3, scene = SceneType.SCHOOL)
        val merged = base.mergeWith(incoming)
        // 版本号不递增 -> 直接返回原对象，不合并
        assertSame(base, merged)
        assertEquals(SceneType.FAMILY, merged.scene)
    }

    @Test
    fun mergeWithAppliesNewerVersion() {
        val base = PolicyPackage(version = 5, deviceId = "dev-1", scene = SceneType.FAMILY, updatedAt = 100L)
        val incoming = PolicyPackage(version = 6, scene = SceneType.SCHOOL, updatedAt = 200L)
        val merged = base.mergeWith(incoming)
        assertEquals(6, merged.version)
        assertEquals(SceneType.SCHOOL, merged.scene)
        assertEquals(200L, merged.updatedAt)
    }

    @Test
    fun mergeWithFallsBackToOldDeviceIdWhenBlank() {
        val base = PolicyPackage(version = 5, deviceId = "dev-1")
        val incoming = PolicyPackage(version = 6, deviceId = "")
        val merged = base.mergeWith(incoming)
        // 服务端未下发 deviceId（空串）-> 沿用旧值（兜底防"未绑定即失控"）
        assertEquals("dev-1", merged.deviceId)
    }

    @Test
    fun mergeWithUsesIncomingDeviceIdWhenPresent() {
        val base = PolicyPackage(version = 5, deviceId = "dev-1")
        val incoming = PolicyPackage(version = 6, deviceId = "dev-2")
        val merged = base.mergeWith(incoming)
        assertEquals("dev-2", merged.deviceId)
    }

    @Test
    fun mergeWithReplacesNestedPoliciesWholesale() {
        val base = PolicyPackage(version = 1, peripheral = PeripheralPolicy(wifi = PolicySwitch.DISABLE))
        val incoming = PolicyPackage(version = 2, peripheral = PeripheralPolicy(wifi = PolicySwitch.FORCE_ON))
        val merged = base.mergeWith(incoming)
        assertEquals(PolicySwitch.FORCE_ON, merged.peripheral.wifi)
    }

    // endregion

    // region ====== MinorModePolicy.isCurfewAt（起止相等语义统一，2026-11-18）======

    @Test
    fun curfewWithEqualStartAndEndNeverLocks() {
        val policy = MinorModePolicy(
            enabled = true,
            curfewEnabled = true,
            curfewStart = "22:00",
            curfewEnd = "22:00"
        )
        // 必须与 ScheduleEvaluator/SmartShutdownEnforcer 一致：退化区间不锁。
        // 旧的"全天锁死"会让家长误填一次就把设备锁一整天。
        assertFalse(policy.isCurfewAt(0))
        assertFalse(policy.isCurfewAt(21 * 60 + 59))
        assertFalse(policy.isCurfewAt(22 * 60))
        assertFalse(policy.isCurfewAt(23 * 60 + 59))
    }

    @Test
    fun curfewStillHonoursNormalAndCrossMidnightRanges() {
        val normal = MinorModePolicy(enabled = true, curfewStart = "22:00", curfewEnd = "06:00")
        assertFalse(normal.isCurfewAt(21 * 60 + 59))
        assertTrue(normal.isCurfewAt(22 * 60))
        assertTrue(normal.isCurfewAt(23 * 60 + 59))
        assertTrue(normal.isCurfewAt(0))
        assertTrue(normal.isCurfewAt(5 * 60 + 59))
        assertFalse(normal.isCurfewAt(6 * 60))

        val daytime = MinorModePolicy(enabled = true, curfewStart = "08:00", curfewEnd = "20:00")
        assertFalse(daytime.isCurfewAt(7 * 60 + 59))
        assertTrue(daytime.isCurfewAt(8 * 60))
        assertFalse(daytime.isCurfewAt(20 * 60))
    }

    @Test
    fun curfewDisabledOrExemptNeverLocks() {
        val disabled = MinorModePolicy(enabled = true, curfewEnabled = false, curfewStart = "22:00", curfewEnd = "06:00")
        assertFalse(disabled.isCurfewAt(23 * 60))
        val exempt = MinorModePolicy(enabled = true, curfewStart = "22:00", curfewEnd = "06:00", parentExemptUntil = 1000L)
        assertFalse(exempt.curfewActiveAt(23 * 60, 999L))
        assertTrue(exempt.curfewActiveAt(23 * 60, 1000L))
    }
}
