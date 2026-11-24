package com.padguard.child.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-P2-10：前台集合解析。
 *
 * 这些用例直接对应"孩子能怎么绕过计时"的现场剧本：
 * 分屏并排、画中画浮层、快速来回切。
 */
class ForegroundResolverTest {

    private fun resumed(pkg: String, at: Long) = ForegroundLifecycleEvent(pkg, true, at)
    private fun paused(pkg: String, at: Long) = ForegroundLifecycleEvent(pkg, false, at)

    @Test
    fun `单应用切换只保留最后一个前台`() {
        val result = ForegroundResolver.resolve(
            listOf(
                resumed("com.a", 100), paused("com.a", 200),
                resumed("com.b", 200)
            )
        )
        assertEquals(listOf("com.b"), result)
    }

    @Test
    fun `分屏时两个应用都在前台集合里`() {
        // 分屏：两个 Activity 同时 RESUMED，这是本修复的核心场景
        val result = ForegroundResolver.resolve(
            listOf(
                resumed("com.calculator", 100),
                resumed("com.game", 200)
            )
        )
        assertEquals(listOf("com.game", "com.calculator"), result)
    }

    @Test
    fun `画中画浮层不算前台（它已 PAUSED）`() {
        val result = ForegroundResolver.resolve(
            listOf(
                resumed("com.video", 100),
                resumed("com.game", 200),
                paused("com.video", 300) // 进入 PiP：可见但不可交互
            )
        )
        assertEquals(listOf("com.game"), result)
    }

    @Test
    fun `退出分屏后回到单前台`() {
        val result = ForegroundResolver.resolve(
            listOf(
                resumed("com.calculator", 100),
                resumed("com.game", 200),
                paused("com.game", 300),
                resumed("com.calculator", 300)
            )
        )
        assertEquals(listOf("com.calculator"), result)
    }

    @Test
    fun `事件时间戳乱序时仍以时间较晚者为准`() {
        // UsageEvents 不保证严格有序；不能简单地"取遍历顺序的最后一条"
        val result = ForegroundResolver.resolve(
            listOf(
                paused("com.a", 500),
                resumed("com.a", 100)
            )
        )
        assertTrue("最后一条是 PAUSED（时间更晚），应判定为不在前台", result.isEmpty())
    }

    @Test
    fun `空事件列表返回空集合（调用方回落到上次结果）`() {
        assertTrue(ForegroundResolver.resolve(emptyList()).isEmpty())
    }

    @Test
    fun `空白包名被忽略`() {
        val result = ForegroundResolver.resolve(
            listOf(
                ForegroundLifecycleEvent("", true, 100),
                resumed("com.a", 200)
            )
        )
        assertEquals(listOf("com.a"), result)
    }
}
