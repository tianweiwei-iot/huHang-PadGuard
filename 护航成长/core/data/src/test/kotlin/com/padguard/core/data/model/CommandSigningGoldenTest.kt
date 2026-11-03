package com.padguard.core.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 指令签名 canonical payload 的**跨端一致性基准**（终端侧）。
 *
 * 期望值是硬编码的字符序列，与服务端 `CommandSigningTest` 断言同一组向量 ——
 * 任一端改动排序/转义/分隔符都会在这里或那边失败。这不是"重复测试"，
 * 而是唯一能守住"两个仓库里的两份实现必须逐字符相同"的手段。
 *
 * 后果提醒：两端不一致时终端会把所有指令判为签名伪造并拒绝（含锁屏），
 * 家长端只看到"设备不响应"。
 */
class CommandSigningGoldenTest {

    private fun cmd(payload: Map<String, String>) = Command(
        msgId = "m1",
        type = CommandType.LOCK_SCREEN,
        timestamp = 1234L,
        payload = payload
    )

    @Test
    fun `canonical payload matches server golden vector`() {
        val c = cmd(mapOf("b" to "a\"c", "a" to "x\\y", "c" to "line\nbreak"))
        // 排序 a,b,c；\ → \\，"，换行 → \u000a（小写四位）
        val expected = "m1|LOCK_SCREEN|1234|{\"a\":\"x\\\\y\",\"b\":\"a\\\"c\",\"c\":\"line\\u000abreak\"}"
        assertEquals(expected, c.signingPayload())
    }

    @Test
    fun `plain payloads keep the pre-migration layout byte for byte`() {
        // 迁移期最关键的一条：不含特殊字符时新格式必须与旧格式完全相同，
        // 否则服务端/APK 任一侧先升级都会让存量指令全线被拒。
        val c = cmd(mapOf("packageName" to "com.tencent.mm", "durationMinutes" to "30"))
        assertEquals(
            "m1|LOCK_SCREEN|1234|{\"durationMinutes\":\"30\",\"packageName\":\"com.tencent.mm\"}",
            c.signingPayload()
        )
        assertEquals(c.signingPayload(), c.signingPayloadLegacy())
    }

    @Test
    fun `legacy format is preserved for signature fallback`() {
        val c = cmd(mapOf("a" to "1\",\"b\":\"2"))
        // 旧格式：不转义，拼出与 {"a":"1","b":"2"} 完全相同的串（这就是被修掉的歧义）
        assertEquals("m1|LOCK_SCREEN|1234|{\"a\":\"1\",\"b\":\"2\"}", c.signingPayloadLegacy())
        // 新格式：转义后不再歧义
        assertEquals("m1|LOCK_SCREEN|1234|{\"a\":\"1\\\",\\\"b\\\":\\\"2\"}", c.signingPayload())
        assertNotEquals(c.signingPayload(), c.signingPayloadLegacy())
    }

    @Test
    fun `cjk values are emitted as is`() {
        assertEquals("{\"name\":\"小明\"}", cmd(mapOf("name" to "小明")).signingPayload().substringAfter("1234|"))
    }
}
