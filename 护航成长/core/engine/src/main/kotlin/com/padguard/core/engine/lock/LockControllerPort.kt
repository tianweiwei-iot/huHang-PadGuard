package com.padguard.core.engine.lock

/**
 * [LockController] 的抽象端口（依赖倒置）。
 *
 * 仅暴露 [com.padguard.core.engine.command.CommandExecutor] 实际调用的锁定 / 解锁能力，
 * 使指令执行器的单元测试可在**不加载任何 Android 框架类型**的前提下 mock 本端口，
 * 从而能在纯 JVM（含本沙箱的 standalone Runner）中跑通，而不依赖 AGP 专用测试 JVM。
 *
 * 其余消费者（[com.padguard.core.engine.PolicyEngine] 等）仍直接依赖具体类 [LockController]，
 * 改动局限在 engine 一个 `@Binds`，无回归。
 */
interface LockControllerPort {
    fun requestLock(reason: LockReason, untilMillis: Long = 0L, detail: String = "")
    fun cancelTempUnlock()
    fun releaseAll()
    fun tempUnlock(durationMinutes: Int, packageName: String = "")

    companion object {
        /** 限时解锁上限（分钟）。与 [LockController.MAX_TEMP_UNLOCK_MINUTES] 保持一致。 */
        const val MAX_TEMP_UNLOCK_MINUTES = 480
    }
}
