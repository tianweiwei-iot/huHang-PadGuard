package com.padguard.child.login

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ChildLoginViewModel 回归测试（R15 · L1 修复）。
 *
 * 钉住的核心语义：AuthRepository 按安全设计**只持久化账号（手机号）、密码永不落库**，
 * 故 rememberedCredentials 第二元素恒为空串。"记住账号"逻辑此前错误地要求密码非空才回填，
 * 导致该 feature 彻底失效（账号永不回填、复选框永不自动勾选）。修复后仅判断账号非空即回填并勾选。
 *
 * 决策逻辑抽成 companion 纯函数 [ChildLoginViewModel.rememberInitial]，故本测试零协程依赖，
 * 不需要引入 kotlinx-coroutines-test（项目协程版本目录与解析版本存在不一致）。
 */
class ChildLoginViewModelTest {

    @Test
    fun `remembered account (phone only, no password) yields refill and checked`() {
        // 密码按设计永不持久化，第二元素恒为 "" —— 正是旧实现判定失败、功能失效的现场
        val result = ChildLoginViewModel.rememberInitial("13800000000" to "")
        assertEquals("13800000000", result.first)
        assertEquals(true, result.second)
    }

    @Test
    fun `blank remembered account yields empty and unchecked`() {
        val result = ChildLoginViewModel.rememberInitial("" to "")
        assertEquals("", result.first)
        assertEquals(false, result.second)
    }
}
