package com.padguard.core.engine.enforcer

import com.padguard.core.engine.admin.OpResult

/**
 * [PeripheralEnforcer] 的抽象端口（依赖倒置）。
 *
 * 仅暴露 [com.padguard.core.engine.command.CommandExecutor] 实际调用的外设临时放行能力，
 * 使指令执行器单元测试可在不加载 Android 框架类型的前提下 mock 本端口。
 * 其余消费者仍直接依赖具体类 [PeripheralEnforcer]，改动局限在 engine 一个 `@Binds`。
 */
interface PeripheralEnforcerPort {
    fun override(item: String, enabled: Boolean): OpResult
}
