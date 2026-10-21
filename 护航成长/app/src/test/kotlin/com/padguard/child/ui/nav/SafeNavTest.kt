package com.padguard.child.ui.nav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校验安全返回的核心判定逻辑（[shouldPopFrom]）。
 *
 * 该逻辑是"快速连点返回箭头导致返回栈被打空、NavHost 白屏"的修复核心：
 * 只要当前目的地是起始页（首页）或为空，就禁止弹出，确保连点返回最终稳定停在首页，
 * 而不会越过首页把整条返回栈清空。
 */
class SafeNavTest {

    @Test
    fun `当前目的地为空时禁止弹出`() {
        assertFalse(shouldPopFrom(currentDestinationId = null, startDestinationId = 1))
    }

    @Test
    fun `已在起始页即首页时禁止弹出，避免白屏`() {
        // 起始目的地 id 与当前目的地 id 相同，表示用户已经处于首页
        assertFalse(shouldPopFrom(currentDestinationId = 1, startDestinationId = 1))
    }

    @Test
    fun `处于非起始的子页时允许弹出返回`() {
        assertTrue(shouldPopFrom(currentDestinationId = 2, startDestinationId = 1))
        assertTrue(shouldPopFrom(currentDestinationId = 7, startDestinationId = 1))
    }

    @Test
    fun `连点返回落在首页后即被拦截，不会越过首页清空返回栈`() {
        // 模拟：从子页(2)返回 → 到达首页(1)；后续点击必须被拦截，不能把起始页也弹出
        assertTrue(shouldPopFrom(2, 1))   // 子页 → 允许返回，到达首页
        assertFalse(shouldPopFrom(1, 1))  // 已在首页 → 拦截，栈不再被清空
        assertFalse(shouldPopFrom(1, 1))  // 再点一次依旧拦截
    }
}
