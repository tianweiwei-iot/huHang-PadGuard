package com.padguard.server.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * 日志脱敏回归（阶段 1 体检 F-07）。
 *
 * 锁死三条不变量：11 位号码只留前 3 后 4；掩码里绝不出现完整号码；
 * 异常输入（含控制字符）一律折叠成固定占位，不原样进日志。
 */
class SensitiveDataTest {

    @Test
    fun `11 位手机号只保留前 3 后 4`() {
        assertEquals("138****0001", SensitiveData.maskPhone("13800000001"))
    }

    @Test
    fun `掩码不含完整号码`() {
        val phone = "13912345678"
        assertEquals("139****5678", SensitiveData.maskPhone(phone))
        assertFalse(SensitiveData.maskPhone(phone).contains(phone))
    }

    @Test
    fun `异常输入不原样写入日志`() {
        listOf(null, "", "  ", "1380000000", "138000000012", "1380000000a", "13800000001\n注入")
            .forEach { assertEquals("***", SensitiveData.maskPhone(it), "input=$it 必须折叠成占位符") }
    }

    @Test
    fun `前后空白被规范化`() {
        assertEquals("138****0001", SensitiveData.maskPhone("  13800000001  "))
    }
}
