package com.padguard.server.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * 指令签名 canonical payload 的**跨端一致性基准**。
 *
 * 这里的期望值是硬编码的字符序列，不是"再算一遍"——目的是让**任一端**悄悄改动
 * 排序/转义/分隔符时立刻失败。终端侧 `CommandSigningGoldenTest`（core/data 模块）
 * 断言的是同一组向量，两边必须同时绿。
 *
 * 线上后果提醒：这个格式一旦两端不一致，终端会把所有指令判为签名伪造并拒绝执行
 * （含远程锁屏），家长端只表现为"设备不响应"，极难定位。所以宁可测试啰嗦，也不要"看起来能跑"。
 */
class CommandSigningTest {

    @Test
    fun `keys are sorted and values are json-escaped`() {
        val payload = mapOf(
            "b" to "a\"c",
            "a" to "x\\y",
            "c" to "line\nbreak"
        )
        // 排序：a,b,c；值转义：\ → \\，"，换行 → \u000a（小写四位十六进制）
        val expected = "{\"a\":\"x\\\\y\",\"b\":\"a\\\"c\",\"c\":\"line\\u000abreak\"}"
        assertEquals(expected, CommandSigning.canonical(payload))
    }

    @Test
    fun `plain ascii and cjk are not escaped`() {
        assertEquals(
            "{\"durationMinutes\":\"30\",\"packageName\":\"com.tencent.mm\"}",
            CommandSigning.canonical(
                mapOf("packageName" to "com.tencent.mm", "durationMinutes" to "30")
            )
        )
        // 非 ASCII 直接输出：两端都按 UTF-8 编码字节，若一侧"顺手"转成 \uXXXX 就会不一致
        assertEquals("{\"name\":\"小明\"}", CommandSigning.canonical(mapOf("name" to "小明")))
    }

    @Test
    fun `empty payload is empty object not empty string`() {
        assertEquals("{}", CommandSigning.canonical(null))
        assertEquals("{}", CommandSigning.canonical(emptyMap()))
    }

    @Test
    fun `escaping only kicks in when needed`() {
        // 无特殊字符时逐字符等于原文 —— 保证迁移期绝大多数指令新旧格式一致，
        // 不会因为格式升级导致"全部指令被旧版本拒收"。
        assertEquals("abc", CommandSigning.escape("abc"))
        assertEquals("a\\\\b", CommandSigning.escape("a\\b"))
        assertEquals("a\\\"b", CommandSigning.escape("a\"b"))
        assertEquals("\\u0001", CommandSigning.escape("\u0001"))
    }

    @Test
    fun `escape closes the format ambiguity that motivated the change`() {
        // 攻击面复现：旧实现里这两种 payload 会拼出同一串（都是 {"a":"1","b":"2"}）。
        // 新实现下它们必须不同，否则签名就不是 payload 的单射函数。
        val one = mapOf("a" to "1\",\"b\":\"2")
        val two = mapOf("a" to "1", "b" to "2")
        assertNotEquals(CommandSigning.canonical(one), CommandSigning.canonical(two))
    }
}
