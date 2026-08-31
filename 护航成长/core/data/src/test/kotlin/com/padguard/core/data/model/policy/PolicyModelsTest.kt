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
        assertEquals(0, ScheduleRule.toMinutes("bad"))
        assertEquals(0, ScheduleRule.toMinutes(""))
        // "1:2" -> 1*60 + 2 = 62（无格式校验，仅做容错）
        assertEquals(62, ScheduleRule.toMinutes("1:2"))
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
}
