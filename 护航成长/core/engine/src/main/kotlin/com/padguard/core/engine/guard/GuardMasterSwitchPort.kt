package com.padguard.core.engine.guard

/**
 * [GuardMasterSwitch] 的抽象端口（依赖倒置）。
 *
 * 仅暴露 [com.padguard.core.engine.command.CommandExecutor] 实际调用的整机管控总开关能力，
 * 使指令执行器单元测试可在不加载 Android 框架类型的前提下 mock 本端口。
 * 其余消费者仍直接依赖具体类 [GuardMasterSwitch]，改动局限在 engine 一个 `@Binds`。
 */
interface GuardMasterSwitchPort {
    fun setActive(value: Boolean)
}
