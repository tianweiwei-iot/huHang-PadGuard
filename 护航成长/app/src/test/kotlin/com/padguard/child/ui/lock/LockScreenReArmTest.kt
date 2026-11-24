package com.padguard.child.ui.lock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定持续补拉阶段「全屏 intent（FSI）重发」节奏的纯函数契约。
 *
 * 该节奏决定无无障碍设备下系统代拉通道的可靠性：太频繁会在 FSI 权限被撤销时刷屏，
 * 太稀疏则锁屏页被后台丢弃后回不来。首次逃逸立即发一拍，之后按
 * [SUSTAINED_FSI_EVERY] 的整数倍节流。
 */
class LockScreenReArmTest {

    @Test
    fun `零不重发但首次逃逸必须立即重发FSI`() {
        // 0 即"尚未逃逸"；1 是孩子刚划走的第一拍，后台 startActivity 大概率被 ROM 丢弃，
        // 必须同拍发 FSI 借系统代拉，否则裸桌面窗口要等第 4 拍（约 6 秒）。
        assertFalse("relaunchCount=0 不应重发", shouldRepostFullScreenIntent(0))
        assertTrue("relaunchCount=1 必须立即重发", shouldRepostFullScreenIntent(1))
        assertFalse("relaunchCount=3 不应重发", shouldRepostFullScreenIntent(3))
    }

    @Test
    fun `持续阶段达到倍数时重发FSI`() {
        assertTrue("relaunchCount=4 应重发（首个持续阶段倍数）", shouldRepostFullScreenIntent(4))
        assertTrue("relaunchCount=8 应重发", shouldRepostFullScreenIntent(8))
        assertTrue("relaunchCount=12 应重发", shouldRepostFullScreenIntent(12))
    }

    @Test
    fun `持续阶段非倍数不重发FSI`() {
        assertFalse("relaunchCount=5 不应重发", shouldRepostFullScreenIntent(5))
        assertFalse("relaunchCount=7 不应重发", shouldRepostFullScreenIntent(7))
        assertFalse("relaunchCount=11 不应重发", shouldRepostFullScreenIntent(11))
    }
}
