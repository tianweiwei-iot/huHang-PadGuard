package com.padguard.child.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PermissionHealthChecker.needsA11yDroppedAlert] 纯判定逻辑的单元测试。
 *
 * 验证"DO 形态下静默自愈成功后不再误报无障碍告警"这一根因修复：
 * 旧实现中 checkAccessibility 在 DO 自愈成功后仍无条件上报告警，导致家长端反复出现
 * "无障碍服务已关闭"。本测试锁定该判定，防止回归。
 */
class PermissionHealthCheckerTest {

    @Test
    fun `DO 自愈成功则不再上报告警`() {
        assertFalse(
            "DO 下自愈后已恢复启用，应抑制告警",
            PermissionHealthChecker.needsA11yDroppedAlert(a11yEnabledAfterHeal = true, isDeviceOwner = true)
        )
    }

    @Test
    fun `DO 自愈失败仍上报告警`() {
        assertTrue(
            "DO 下自愈未成功，应继续上报告警",
            PermissionHealthChecker.needsA11yDroppedAlert(a11yEnabledAfterHeal = false, isDeviceOwner = true)
        )
    }

    @Test
    fun `非 DO 设备始终上报告警（平台限制，只能引导重授）`() {
        assertTrue(
            "非 DO 无静默自愈能力，应上报告警引导",
            PermissionHealthChecker.needsA11yDroppedAlert(a11yEnabledAfterHeal = false, isDeviceOwner = false)
        )
    }

    /**
     * [PermissionHealthChecker.computeResolveCategories] 纯判定逻辑的单元测试。
     *
     * 验证"恢复即关闭"闭环的核心安全约束：
     * - `GUARD_PERMISSION_DROPPED` 同覆盖无障碍与悬浮窗，仅当**两者都恢复**才关闭，
     *   避免把"悬浮窗仍缺失"的有效告警一并抹掉；
     * - 边沿触发（prev - now）：当前不缺失且上一轮也未缺失时，不产生任何关闭，避免每轮自检刷请求。
     */
    @Test
    fun `无障碍与悬浮窗均恢复且曾掉失则关闭两类告警`() {
        val set = PermissionHealthChecker.computeResolveCategories(
            a11yOk = true, overlayOk = true,
            prevDropped = setOf("a11y", "overlay"), nowDropped = emptySet()
        )
        assertEquals(setOf("ACCESSIBILITY_DISABLED", "GUARD_PERMISSION_DROPPED"), set)
    }

    @Test
    fun `无障碍恢复但悬浮窗仍缺失时不关闭 GUARD_PERMISSION_DROPPED`() {
        val set = PermissionHealthChecker.computeResolveCategories(
            a11yOk = true, overlayOk = false,
            prevDropped = setOf("a11y", "overlay"), nowDropped = setOf("overlay")
        )
        // 仅关无障碍独立类别；共享的 GUARD_PERMISSION_DROPPED 因悬浮窗仍缺失而保留
        assertEquals(setOf("ACCESSIBILITY_DISABLED"), set)
    }

    @Test
    fun `运行期权限恢复则关闭 PERMISSION_REVOKED`() {
        val set = PermissionHealthChecker.computeResolveCategories(
            a11yOk = false, overlayOk = false,
            prevDropped = setOf("runtime"), nowDropped = emptySet()
        )
        assertEquals(setOf("PERMISSION_REVOKED"), set)
    }

    @Test
    fun `无恢复则空集（边沿触发，不掉失则不关闭）`() {
        val set = PermissionHealthChecker.computeResolveCategories(
            a11yOk = true, overlayOk = true,
            prevDropped = emptySet(), nowDropped = emptySet()
        )
        assertTrue("能力持续正常时不应产生任何关闭请求", set.isEmpty())
    }
}
