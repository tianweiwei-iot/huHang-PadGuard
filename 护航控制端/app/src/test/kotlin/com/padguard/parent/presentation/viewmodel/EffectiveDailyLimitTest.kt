package com.padguard.parent.presentation.viewmodel

import com.padguard.parent.domain.repository.AgeBand
import com.padguard.parent.domain.repository.AgeBandDefault
import com.padguard.parent.domain.repository.MinorMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [computeEffectiveDailyLimitMinutes] 的口径锁定。
 *
 * 这是"孩子端到底还能玩多久"的唯一计算入口：算错不会报错，只会让家长端显示一个
 * 孩子端并不存在的额度，家长据此判断"还有 30 分钟"而实际已经锁机 —— 属于静默错误，
 * 必须由测试钉死每一档优先级。
 *
 * 口径：
 * 1. 总开关关 且 未成年人模式未开 → 0（不限制）
 * 2. 家长设置的额度是权威（可高于分龄上限）
 * 3. 家长未设置时，未成年人模式开启才回落分龄基线
 */
class EffectiveDailyLimitTest {

    private val defaults = mapOf(
        AgeBand.UNDER_3 to AgeBandDefault(dailyLimitMinutes = 30),
        AgeBand.BAND_3_8 to AgeBandDefault(dailyLimitMinutes = 45),
        AgeBand.BAND_8_12 to AgeBandDefault(dailyLimitMinutes = 60),
        AgeBand.BAND_12_16 to AgeBandDefault(dailyLimitMinutes = 60),
        AgeBand.BAND_16_18 to AgeBandDefault(dailyLimitMinutes = 120)
    )

    @Test
    fun `both switches off means no limit at all`() {
        val result = computeEffectiveDailyLimitMinutes(
            enabled = false,
            weekdayLimitMinutes = 90,
            weekendLimitMinutes = 180,
            minorMode = MinorMode(enabled = false),
            ageBandDefaults = defaults,
            isWeekend = false
        )
        assertEquals(0, result)
    }

    @Test
    fun `parent value wins even when minor mode is on`() {
        val result = computeEffectiveDailyLimitMinutes(
            enabled = true,
            weekdayLimitMinutes = 90,
            weekendLimitMinutes = 180,
            minorMode = MinorMode(enabled = true, ageBand = AgeBand.BAND_8_12),
            ageBandDefaults = defaults,
            isWeekend = false
        )
        assertEquals(90, result)
    }

    @Test
    fun `parent value may exceed the age band ceiling`() {
        // 家长可自由设置高于分龄上限的额度：未成年人模式只作合规兜底，不夹逼家长已设值
        val result = computeEffectiveDailyLimitMinutes(
            enabled = true,
            weekdayLimitMinutes = 150,
            weekendLimitMinutes = 150,
            minorMode = MinorMode(enabled = true, ageBand = AgeBand.BAND_12_16),
            ageBandDefaults = defaults,
            isWeekend = false
        )
        assertEquals(150, result)
    }

    @Test
    fun `weekend uses weekend value when set`() {
        val result = computeEffectiveDailyLimitMinutes(
            enabled = true,
            weekdayLimitMinutes = 90,
            weekendLimitMinutes = 120,
            minorMode = null,
            ageBandDefaults = defaults,
            isWeekend = true
        )
        assertEquals(120, result)
    }

    @Test
    fun `weekend falls back to weekday value when weekend is zero`() {
        val result = computeEffectiveDailyLimitMinutes(
            enabled = true,
            weekdayLimitMinutes = 90,
            weekendLimitMinutes = 0,
            minorMode = null,
            ageBandDefaults = defaults,
            isWeekend = true
        )
        assertEquals(90, result)
    }

    @Test
    fun `no parent value and minor mode on falls back to age band baseline`() {
        val result = computeEffectiveDailyLimitMinutes(
            enabled = true,
            weekdayLimitMinutes = 0,
            weekendLimitMinutes = 0,
            minorMode = MinorMode(enabled = true, ageBand = AgeBand.BAND_3_8),
            ageBandDefaults = defaults,
            isWeekend = false
        )
        assertEquals(45, result)
    }

    @Test
    fun `no parent value and minor mode off keeps weekday value as is`() {
        val result = computeEffectiveDailyLimitMinutes(
            enabled = true,
            weekdayLimitMinutes = 0,
            weekendLimitMinutes = 0,
            minorMode = MinorMode(enabled = false),
            ageBandDefaults = defaults,
            isWeekend = false
        )
        assertEquals(0, result)
    }

    @Test
    fun `missing age band entry falls back to sixty not to zero`() {
        // 分龄默认值没取到（接口部分失败）时不能返回 0 —— 0 在界面语义是"未设置"，
        // 会让家长以为没有限额，而孩子端仍在按 60 分钟生效。
        val result = computeEffectiveDailyLimitMinutes(
            enabled = true,
            weekdayLimitMinutes = 0,
            weekendLimitMinutes = 0,
            minorMode = MinorMode(enabled = true, ageBand = AgeBand.BAND_8_12),
            ageBandDefaults = emptyMap(),
            isWeekend = false
        )
        assertEquals(60, result)
    }
}
