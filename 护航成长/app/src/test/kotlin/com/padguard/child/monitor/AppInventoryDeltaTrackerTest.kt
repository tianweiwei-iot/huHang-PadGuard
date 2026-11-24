package com.padguard.child.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [diffInventoryNames] 的纯 JVM 单测（R-P3-10）。
 *
 * 锁定三条必须守住的边界：
 * 1. 首次观测只建基线，绝不把"当前全部应用"当成新装推成告警；
 * 2. 正常增删各归各位，不能互相串味；
 * 3. 顺序稳定（排序输出），便于日志与断言比较，也避免同一批变化两次上报顺序不同。
 */
class AppInventoryDeltaTrackerTest {

    @Test
    fun `首次观测只建立基线，不产生任何事件`() {
        val delta = diffInventoryNames(
            previous = null,
            current = setOf("com.tencent.mm", "com.tencent.mobileqq", "com.taobao.taobao")
        )
        assertTrue(delta.baselineOnly)
        assertTrue("首次观测绝不能报新装", delta.added.isEmpty())
        assertTrue(delta.removed.isEmpty())
    }

    @Test
    fun `新装与卸载各归各位`() {
        val delta = diffInventoryNames(
            previous = setOf("com.tencent.mm", "com.old.game"),
            current = setOf("com.tencent.mm", "com.new.game")
        )
        assertFalse(delta.baselineOnly)
        assertEquals(listOf("com.new.game"), delta.added)
        assertEquals(listOf("com.old.game"), delta.removed)
    }

    @Test
    fun `内容不变时既不报新装也不报卸载`() {
        val same = setOf("com.tencent.mm", "com.taobao.taobao")
        val delta = diffInventoryNames(previous = same, current = same)
        assertTrue(delta.added.isEmpty())
        assertTrue(delta.removed.isEmpty())
        assertFalse(delta.baselineOnly)
    }

    @Test
    fun `空基线（曾观测到但当时没有任何普通应用）不会被误判为首次观测`() {
        // 与"从未观测"必须区分：空集是有效基线，此时新装一个应用必须报出来。
        val delta = diffInventoryNames(previous = emptySet(), current = setOf("com.new.game"))
        assertFalse(delta.baselineOnly)
        assertEquals(listOf("com.new.game"), delta.added)
    }

    @Test
    fun `输出按包名排序，保证同一批变化的上报顺序稳定`() {
        val delta = diffInventoryNames(
            previous = emptySet(),
            current = setOf("com.z.app", "com.a.app", "com.m.app")
        )
        assertEquals(listOf("com.a.app", "com.m.app", "com.z.app"), delta.added)
    }
}
