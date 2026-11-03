package com.padguard.child.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UninstallShieldResolver] 纯判定逻辑的单元测试。
 *
 * 锁定 2026-11-02 事故（孩子长按桌面图标即卸载成功）的根因修复：
 * "卸载需密码"由三条独立通道承担（DO 系统封死 / 设备管理员 / 无障碍+密码软门），
 * 旧实现从不把三者合并成一个可观测状态，导致**三条全空时界面照样显示"守护中"**。
 * 本测试确保"空即报警"这一判定不被后续改动悄悄放宽。
 */
class UninstallShieldTest {

    @Test
    fun `三条通道全空即事故现场：判定为 NONE 且必须打扰家长`() {
        val shield = UninstallShieldResolver.resolve(
            ownerPrivileges = false,
            uninstallBlockApplied = false,
            adminActive = false,
            a11yAlive = false,
            passwordEnabled = false
        )
        assertEquals(UninstallShield.NONE, shield)
        assertFalse("NONE 不得被当成已保护", shield.blocksCasualUninstall)
        assertTrue("NONE 必须让家长看到", shield.needsParentAttention)
    }

    @Test
    fun `DO 且已封死卸载时，即使无障碍没开也算已保护`() {
        val shield = UninstallShieldResolver.resolve(
            ownerPrivileges = true,
            uninstallBlockApplied = true,
            adminActive = true,
            a11yAlive = false,
            passwordEnabled = false
        )
        assertEquals(UninstallShield.DO_BLOCKED, shield)
        assertTrue(shield.blocksCasualUninstall)
        assertFalse("系统级封死已足够，不该再打扰家长", shield.needsParentAttention)
    }

    @Test
    fun `持有 DO 但封死尚未落地时降级为设备管理员级，不谎报系统级封死`() {
        val shield = UninstallShieldResolver.resolve(
            ownerPrivileges = true,
            uninstallBlockApplied = false,
            adminActive = true,
            a11yAlive = true,
            passwordEnabled = true
        )
        assertEquals(UninstallShield.ADMIN_SHIELD, shield)
        assertTrue(shield.blocksCasualUninstall)
    }

    @Test
    fun `设备管理员激活即拦得住随手卸载（系统要求先停用管理员）`() {
        val shield = UninstallShieldResolver.resolve(
            ownerPrivileges = false,
            uninstallBlockApplied = false,
            adminActive = true,
            a11yAlive = false,
            passwordEnabled = false
        )
        assertEquals(UninstallShield.ADMIN_SHIELD, shield)
        assertTrue(shield.blocksCasualUninstall)
    }

    @Test
    fun `无障碍在但没设密码只能顶回，算半残仍需家长介入`() {
        val shield = UninstallShieldResolver.resolve(
            ownerPrivileges = false,
            uninstallBlockApplied = false,
            adminActive = false,
            a11yAlive = true,
            passwordEnabled = false
        )
        assertEquals(UninstallShield.SOFT_GATE_WITHOUT_PASSWORD, shield)
        assertFalse("无法验身的软门挡不住反复尝试", shield.blocksCasualUninstall)
        assertTrue(shield.needsParentAttention)
    }

    @Test
    fun `无障碍加密码构成完整软门`() {
        val shield = UninstallShieldResolver.resolve(
            ownerPrivileges = false,
            uninstallBlockApplied = false,
            adminActive = false,
            a11yAlive = true,
            passwordEnabled = true
        )
        assertEquals(UninstallShield.SOFT_GATE, shield)
        assertTrue(shield.blocksCasualUninstall)
    }

    @Test
    fun `防护强度单调：越强的通道优先级越高，便于日志一眼看出真实等级`() {
        val strengths = UninstallShield.values().map { it.strength }
        assertEquals(strengths.sorted(), strengths)
        assertEquals(UninstallShield.values().size, strengths.toSet().size)
    }
}
