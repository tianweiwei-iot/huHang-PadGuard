package com.padguard.parent.presentation.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RefreshGate 行为契约（纯 JVM，无 Android/Robolectric 依赖）。
 *
 * 这个类只有两个 AtomicBoolean，却承担「单飞 + 补一次」的并发闸门语义，
 * 且被三个消息中心 VM 共用。旧守卫 `SessionSwitchResetR44Test.刷新闸门提供reset出口`
 * 用 `body.contains("pending.set(false)")` 扫源码文本：改坏 compareAndSet 逻辑、
 * 调换 reset 顺序、把 set(true) 写成 set(false) 都能在守卫全绿时静默击穿。
 * 这里改为直接调真实函数做行为断言，锁死意图而非文本。
 */
class RefreshGateBehaviorTest {

    @Test
    fun `单飞_在途期间只允许一个执行权`() {
        val gate = RefreshGate()
        assertTrue("首轮必须拿到执行权", gate.tryEnter())
        assertFalse("在途时第二轮必须被挡", gate.tryEnter())
        assertFalse("第三轮同样被挡", gate.tryEnter())
    }

    @Test
    fun `补一次_在途期间的请求只兑现一次`() {
        val gate = RefreshGate()
        assertTrue(gate.tryEnter())
        assertFalse("这次被拒应记入补跑意愿", gate.tryEnter())
        assertFalse("多次被拒也只记一次", gate.tryEnter())
        assertTrue("结束时必须兑现一次补跑", gate.exit())
        assertFalse("补跑意愿兑现后不得重复", gate.exit())
    }

    @Test
    fun `无等待请求时_exit不补跑`() {
        val gate = RefreshGate()
        assertTrue(gate.tryEnter())
        assertFalse("期间无人请求就不该补跑", gate.exit())
    }

    @Test
    fun `补跑兑现后重新获得执行权可再次进入`() {
        val gate = RefreshGate()
        assertTrue(gate.tryEnter())
        assertFalse(gate.tryEnter())
        assertTrue(gate.exit())
        // exit 已清 inFlight，下一轮应能正常进入
        assertTrue("补跑兑现后闸门应重新开放", gate.tryEnter())
        assertFalse(gate.exit())
    }

    @Test
    fun `reset清inFlight_新会话首次刷新不被吞`() {
        val gate = RefreshGate()
        assertTrue(gate.tryEnter())
        assertFalse("旧会话在途时被拒", gate.tryEnter())
        gate.reset()
        assertTrue("会话切换后首次刷新必须能进入，否则新会话首屏被吞", gate.tryEnter())
    }

    @Test
    fun `reset清pending_旧会话补跑意愿不带到新会话`() {
        val gate = RefreshGate()
        assertTrue(gate.tryEnter())
        assertFalse("旧会话记下补跑意愿", gate.tryEnter())
        gate.reset()
        assertFalse("reset 必须清掉旧会话的补跑意愿，否则新会话多刷一次", gate.exit())
        assertTrue("reset 后 inFlight 也应清空", gate.tryEnter())
        assertFalse(gate.exit())
    }
}
