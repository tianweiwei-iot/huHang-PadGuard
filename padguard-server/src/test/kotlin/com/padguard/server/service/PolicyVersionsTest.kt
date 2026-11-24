package com.padguard.server.service

import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.PolicyRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.slf4j.Logger
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * 覆盖 [PolicyVersions.allocate]：策略版本号分配的并发安全逻辑。
 *
 * 这是"设置保存成功、孩子端没变"这一经典 bug 的根因点——早期各处自己 `version+1`，
 * 并发写入会插出两条同号策略行（孩子端对账取到哪条取决于 DB 返回顺序，无报错无日志）。
 * 现在统一收敛到本对象：事务内对 devices 行加悲观写锁再 `max+1`，多实例下靠 DB 行锁串行化。
 *
 * 两个分支必须被钉死：
 *  - 无事务：降级为不加锁的 `nextVersion`（保功能），但不可静默——必须记 warn；
 *  - 有事务：必须先 `findByIdForUpdate` 加行锁，再 `nextVersion`。
 *
 * 不加载 Spring 上下文，用 [TransactionSynchronizationManager.setActualTransactionActive] 模拟事务态。
 */
class PolicyVersionsTest {

    private val devices = mock(DeviceRepository::class.java)
    private val policies = mock(PolicyRepository::class.java)
    private val log = mock(Logger::class.java)

    @AfterEach
    fun tearDown() {
        // 防止测试间事务态泄漏
        TransactionSynchronizationManager.setActualTransactionActive(false)
    }

    @Test
    fun `无事务时不加行锁但返回 nextVersion 并记 warn`() {
        `when`(policies.nextVersion(anyString())).thenReturn(7)

        val v = PolicyVersions.allocate("d1", devices, policies, log)

        assertEquals(7, v)
        // 无事务不可触碰 devices 行锁
        verify(devices, never()).findByIdForUpdate(anyString())
        // 降级路径必须留痕：SLF4J warn 多重载，Kotlin 在 verify 侧易解析到不同签名，
        // 故按「方法名 + 调用次数」断言，绕开重载歧义但钉死"必须记 warn"这一契约
        assertTrue(mockingDetails(log).invocations.any { it.method.name == "warn" })
        verify(policies).nextVersion(eq("d1") ?: "d1")
    }

    @Test
    fun `有事务时先对设备行加悲观写锁再分配版本号`() {
        TransactionSynchronizationManager.setActualTransactionActive(true)
        `when`(policies.nextVersion(anyString())).thenReturn(42)

        val v = PolicyVersions.allocate("d1", devices, policies, log)

        assertEquals(42, v)
        // 事务内必须先加行锁（串行化同设备并发写入）
        verify(devices).findByIdForUpdate(eq("d1") ?: "d1")
        verify(policies).nextVersion(eq("d1") ?: "d1")
        // 事务内不应再走降级 warn
        assertFalse(mockingDetails(log).invocations.any { it.method.name == "warn" })
    }
}
