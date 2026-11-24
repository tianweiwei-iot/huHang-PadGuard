package com.padguard.server.service

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * [afterCommit] 的提交回调语义护栏。
 *
 * 重点锁死「嵌套」：Spring 的 `TransactionSynchronizationUtils.triggerAfterCommit()`
 * 遍历的是 `getSynchronizations()` 返回的**快照**。在 `afterCommit()` 回调里再次
 * `registerSynchronization()`，新回调既不会被本轮触发，也不会有任何后续轮次来触发，
 * 副作用就此**静默丢失** —— 这类 bug 没有异常、没有日志，只表现为「偶发少一条推送」。
 */
class AfterCommitNestedTest {

    @AfterEach
    fun clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    @Test
    @DisplayName("无事务时立即执行，不注册 synchronization")
    fun `runs inline without synchronization`() {
        var ran = false
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive())
        afterCommit { ran = true }
        assertTrue(ran, "无事务路径必须保持『立即执行』，否则单测直调与只读路径会丢副作用")
    }

    @Test
    @DisplayName("有事务时推迟到提交后，提交前不得提前执行")
    fun `defers until commit`() {
        TransactionSynchronizationManager.initSynchronization()
        var ran = false
        afterCommit { ran = true }
        assertFalse(ran, "提交前执行等于把回滚后的假状态推给家长端")
        assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size)
        TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
        assertTrue(ran)
    }

    @Test
    @DisplayName("嵌套 afterCommit：内层必须执行，且不得再注册 synchronization")
    fun `nested call runs inline instead of registering into an already-committed transaction`() {
        TransactionSynchronizationManager.initSynchronization()
        var innerRan = false
        var registeredExtra: Boolean? = null

        afterCommit {
            val before = TransactionSynchronizationManager.getSynchronizations().size
            afterCommit { innerRan = true }
            registeredExtra = TransactionSynchronizationManager.getSynchronizations().size > before
        }

        assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size)
        assertFalse(innerRan, "提交前内层也不得提前执行")

        // 模拟 Spring 触发提交回调：取快照后逐个执行
        val snapshot = TransactionSynchronizationManager.getSynchronizations().toList()
        snapshot.forEach { it.afterCommit() }

        assertEquals(false, registeredExtra, "提交回调里注册的新 synchronization 永远不会被触发")
        assertTrue(innerRan, "嵌套的副作用必须执行，不能被静默吞掉")
    }

    @Test
    @DisplayName("提交回调抛异常后，嵌套标记必须复位，不能污染同线程的下一条请求")
    fun `nested flag is cleared even when outer callback throws`() {
        TransactionSynchronizationManager.initSynchronization()
        runCatching {
            afterCommit { error("boom") }
            TransactionSynchronizationManager.getSynchronizations().toList().forEach { it.afterCommit() }
        }
        TransactionSynchronizationManager.clearSynchronization()

        // 下一条请求：有事务时应正常推迟，而不是因残留标记被当成「提交回调内」立即执行
        TransactionSynchronizationManager.initSynchronization()
        var ranEarly = false
        afterCommit { ranEarly = true }
        assertFalse(ranEarly, "线程复用下残留标记会让后续事务内的副作用提前执行")
        TransactionSynchronizationManager.clearSynchronization()
    }

    @Test
    @DisplayName("提交后副作用失败不得把已提交的事务变成 500")
    fun `side effect failure never escapes afterCommit`() {
        TransactionSynchronizationManager.initSynchronization()
        var saw = false
        afterCommit {
            saw = true
            error("push failed")
        }
        // 不应抛出：事务已提交，再抛只会让接口返回 500 而改动已经生效
        TransactionSynchronizationManager.getSynchronizations().toList().forEach { it.afterCommit() }
        assertTrue(saw)
        TransactionSynchronizationManager.clearSynchronization()

        TransactionSynchronizationManager.initSynchronization()
        var laterRan = false
        afterCommit { laterRan = true }
        assertFalse(laterRan, "副作用异常不得让嵌套标记残留")
        TransactionSynchronizationManager.clearSynchronization()
    }
}
