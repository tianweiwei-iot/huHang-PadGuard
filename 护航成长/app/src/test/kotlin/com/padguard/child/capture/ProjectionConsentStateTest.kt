package com.padguard.child.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-07「实时看屏还要孩子再点一次授权」的判定层回归测试。
 *
 * Android 14+（targetSdk 35）一次用户确认只能换取一次 MediaProjection：
 * 投影 stop 后仍把 `resultIntent` 当作「有令牌」，就会静默起采集服务、
 * 中途 getMediaProjection 抛 SecurityException，再弹一次系统确认框。
 * 这两条纯函数钉住「consent 一次性」语义，避免再退回旧的假阳性判定。
 */
class ProjectionConsentStateTest {

    @Test
    fun `fresh grant is usable without any dialog`() {
        assertTrue(projectionHasToken(hasProjection = false, hasIntent = true, consumed = false))
        assertFalse(projectionNeedsFreshConsent(false, true, false))
    }

    @Test
    fun `live projection is always reusable regardless of consent flag`() {
        assertTrue(projectionHasToken(hasProjection = true, hasIntent = true, consumed = true))
        assertFalse(projectionNeedsFreshConsent(true, true, true))
    }

    @Test
    fun `stopped projection with consumed consent must not pretend to have a token`() {
        // 旧实现这里返回 true，正是重复弹授权框的根因
        assertFalse(projectionHasToken(hasProjection = false, hasIntent = true, consumed = true))
        assertTrue(projectionNeedsFreshConsent(false, true, true))
    }

    @Test
    fun `nothing granted needs a fresh consent`() {
        assertFalse(projectionHasToken(false, false, false))
        assertTrue(projectionNeedsFreshConsent(false, false, false))
    }

    @Test
    fun `a brand new grant resets the one shot flag`() {
        assertTrue(projectionHasToken(false, true, false))
        assertFalse(projectionNeedsFreshConsent(false, true, false))
    }

    @Test
    fun `system callbacks may drop projection but never revive a consumed consent`() {
        // onStop：投影被系统回收，consent 仍为已消费 → 不能静默复用
        assertFalse(projectionHasToken(false, true, true))
        assertTrue(projectionNeedsFreshConsent(false, true, true))
    }

    // ===== 引导通知文案：区分「从未授权」与「授权过但令牌失效」（2026-11-09） =====

    @Test
    fun `从未申请过采集授权时用原始引导文案`() {
        assertEquals(
            "为实时守护做准备，请确认屏幕采集授权",
            capturePromptText(everRequested = false, everGranted = false)
        )
    }

    @Test
    fun `装机授权过但进程重启令牌失效时如实说明是重新确认`() {
        // MediaProjection 是进程内 IBinder，重启必然作废；再提示不能说得像"装机没授权"。
        val text = capturePromptText(everRequested = true, everGranted = true)
        assertEquals("平板重启后需重新确认屏幕采集授权", text)
        assertFalse("不能再让家长误以为授权被清空", text.contains("未授权"))
    }

    @Test
    fun `曾申请但被拒绝时仍按首次引导处理`() {
        assertEquals(
            "为实时守护做准备，请确认屏幕采集授权",
            capturePromptText(everRequested = true, everGranted = false)
        )
    }
}
