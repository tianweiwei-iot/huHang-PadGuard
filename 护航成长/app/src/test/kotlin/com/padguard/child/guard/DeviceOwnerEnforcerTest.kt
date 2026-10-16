package com.padguard.child.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2（DO 治本路径）纯函数选型逻辑的单元测试。
 *
 * [DeviceOwnerEnforcer.selectRevocablePackages] 是与 Android 框架解耦的纯函数，
 * 从已安装包中筛出"受控且可安全回收权限"的包——必须排除系统关键（防砖）与系统应用。
 */
class DeviceOwnerEnforcerTest {

    private val systemCritical = setOf("com.android.systemui", "com.padguard.child", "android")
    private val systemApps = setOf("com.android.settings", "com.android.providers.downloads")

    private fun select(
        installed: List<String>,
        isControlled: (String) -> Boolean = { it.startsWith("controlled.") }
    ): List<String> = DeviceOwnerEnforcer.selectRevocablePackages(
        installedPackages = installed,
        isControlled = isControlled,
        isSystemCritical = { it in systemCritical },
        isSystemApp = { it in systemApps }
    )

    @Test
    fun `受控的普通用户应用被选中`() {
        val result = select(listOf("controlled.game", "controlled.social", "free.calculator"))
        assertEquals(listOf("controlled.game", "controlled.social"), result)
    }

    @Test
    fun `系统关键包永不回收，防砖`() {
        val result = select(
            listOf("controlled.mustNotRevoke", "com.android.systemui", "com.padguard.child", "android"),
            isControlled = { true }
        )
        // 受控但非系统关键的普通应用仍应被选中（真回收权限）；
        // 系统关键包（系统 UI / 自身包 / android）必须全部被排除，否则回收会令设备变砖。
        assertEquals(listOf("controlled.mustNotRevoke"), result)
        assertTrue(result.none { it in systemCritical })
    }

    @Test
    fun `系统应用被排除`() {
        val result = select(
            listOf("controlled.settingsApp", "com.android.settings", "com.android.providers.downloads"),
            isControlled = { true }
        )
        assertEquals(listOf("controlled.settingsApp"), result)
    }

    @Test
    fun `未受控的普通应用不被选中`() {
        val result = select(listOf("free.browser", "free.maps"))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `被取消授权但未在受控名单也视为受控`() {
        // isControlled 对 hidden 包返回 true，即便它不在普通受控名单判定里
        val result = select(
            listOf("hidden.video", "controlled.chat"),
            isControlled = { it.startsWith("controlled.") || it.startsWith("hidden.") }
        )
        assertEquals(listOf("hidden.video", "controlled.chat"), result)
    }

    @Test
    fun `空输入返回空`() {
        assertTrue(select(emptyList()).isEmpty())
    }

    @Test
    fun `DANGEROUS_PERMISSIONS 为敏感权限且非空`() {
        assertFalse(DeviceOwnerEnforcer.DANGEROUS_PERMISSIONS.isEmpty())
        assertTrue(
            DeviceOwnerEnforcer.DANGEROUS_PERMISSIONS.contains(android.Manifest.permission.CAMERA)
        )
        assertTrue(
            DeviceOwnerEnforcer.DANGEROUS_PERMISSIONS.contains(android.Manifest.permission.RECORD_AUDIO)
        )
    }
}
