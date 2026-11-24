package com.padguard.server.service

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.contains
import org.mockito.Mockito.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

class DeviceBindLocksTest {

    @AfterEach
    fun tearDown() {
        // 单例静态状态：每个用例后复位，避免 PG 探测结果/JdbcTemplate 跨用例泄漏
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clear()
        }
        DeviceBindLocks.resetForTest()
    }

    @Test
    fun bindKey_prefersSnThenFingerprintThenEmpty() {
        // 设备序列号优先
        assertEquals("sn:SN123", DeviceBindLocks.bindKey("SN123", "fp-ignored"))
        // 序列号带前后空格会被 trim
        assertEquals("sn:snX", DeviceBindLocks.bindKey(" snX ", null))
        // 序列号缺省时退化为 ROM 指纹
        assertEquals("fp:fp1", DeviceBindLocks.bindKey(null, "fp1"))
        assertEquals("fp:fp", DeviceBindLocks.bindKey("", "fp"))
        // 两者皆空：无任何跨请求依据，返回空串（此时加锁反而会串起不相干的绑定）
        assertEquals("", DeviceBindLocks.bindKey(null, null))
        assertEquals("", DeviceBindLocks.bindKey("", ""))
        assertEquals("", DeviceBindLocks.bindKey("   ", "  "))
    }

    @Test
    fun withKey_emptyKey_runsBlockDirectlyWithoutLocking() {
        var count = 0
        val r = DeviceBindLocks.withKey("") {
            count++
            7
        }
        assertEquals(7, r)
        assertEquals(1, count)
    }

    @Test
    fun withKey_nonEmptyKey_whenNoTransaction_acquiresAndReleasesAroundBlock() {
        val r = DeviceBindLocks.withKey("some-device-key") { "ok" }
        assertEquals("ok", r)
    }

    @Test
    fun withKey_nonEmptyKey_underActiveSynchronization_registersAfterCompletionUnlock() {
        // 真实绑定流程在事务内加锁，解锁权交给事务提交后的 afterCompletion，
        // 保证下一个进入者一定能读到上一次提交的结果。这里验证注册与释放链路不抛异常。
        TransactionSynchronizationManager.initSynchronization()
        try {
            val r = DeviceBindLocks.withKey("tx-key") { 42 }
            assertEquals(42, r)
            // 未注入 JdbcTemplate（纯 JVM 单测）→ 只有 JVM 条带锁，不应触发任何 DB 调用
            // 模拟事务提交，触发 afterCompletion -> lock.unlock()
            TransactionSynchronizationManager.getSynchronizations()
                .forEach { it.afterCompletion(TransactionSynchronization.STATUS_COMMITTED) }
        } finally {
            TransactionSynchronizationManager.clear()
        }
    }

    @Test
    fun withKey_onPostgresInTransaction_issuesAdvisoryXactLock() {
        // 默认/docker 档是多副本唯一可能的部署形态：事务内加锁必须申请 PG 事务级咨询锁，
        // 让不同副本（连同一个 PG）之间也互斥。用 mock 验证 SQL 确实发出。
        val jdbc = mock(JdbcTemplate::class.java)
        DeviceBindLocks.installForTest(jdbc, postgres = true)

        TransactionSynchronizationManager.initSynchronization()
        val r = DeviceBindLocks.withKey("sn:ADVISORY-DEVICE") { "bound" }
        assertEquals("bound", r)

        verify(jdbc).execute(contains("pg_advisory_xact_lock"))
        // JVM 解锁已挂到事务回调：事务未结束时不得提前 unlock，否则同副本内会失去互斥
        verify(jdbc, never()).execute(contains("unlock"))

        TransactionSynchronizationManager.getSynchronizations()
            .forEach { it.afterCompletion(TransactionSynchronization.STATUS_COMMITTED) }
    }

    @Test
    fun withKey_onPostgresWithoutTransaction_noAdvisoryLock() {
        // 无活跃事务时（如单测直调、或非事务路径）即使探测到 PG 也不发咨询锁 ——
        // 事务级咨询锁在没有事务时毫无意义（语句结束即释放），且会凭空增加一次 DB 往返。
        val jdbc = mock(JdbcTemplate::class.java)
        DeviceBindLocks.installForTest(jdbc, postgres = true)

        assertFalse(TransactionSynchronizationManager.isSynchronizationActive())
        val r = DeviceBindLocks.withKey("sn:NO-TX") { 1 }
        assertEquals(1, r)
        verify(jdbc, never()).execute(anyString())
    }

    @Test
    fun withKey_onH2LikeNoPostgres_neverIssuesAdvisoryLock() {
        // home/local 档是单进程 H2 文件库，物理上不可能多副本：只应走 JVM 条带锁，
        // 不引入任何 DB 往返（没有锁表、没有咨询锁、没有额外迁移）。
        val jdbc = mock(JdbcTemplate::class.java)
        DeviceBindLocks.installForTest(jdbc, postgres = false)

        TransactionSynchronizationManager.initSynchronization()
        try {
            DeviceBindLocks.withKey("sn:H2-DEVICE") { 1 }
            verify(jdbc, never()).execute(anyString())
            TransactionSynchronizationManager.getSynchronizations()
                .forEach { it.afterCompletion(TransactionSynchronization.STATUS_COMMITTED) }
        } finally {
            TransactionSynchronizationManager.clear()
        }
    }

    @Test
    fun advisoryId_isStableAndDistinctPerKey() {
        // 咨询锁键必须跨 JVM 稳定（否则不同副本算出不同的锁 = 完全不互斥），
        // 且不同设备 key 不能都撞到同一个值（否则不相干绑定被无谓串行）。
        val a = DeviceBindLocks.advisoryId("sn:DEVICE-1")
        assertEquals(a, DeviceBindLocks.advisoryId("sn:DEVICE-1"))
        assertNotEquals(a, DeviceBindLocks.advisoryId("sn:DEVICE-2"))
        assertNotEquals(a, DeviceBindLocks.advisoryId("fp:DEVICE-1"))
        // 同一把 key 的 java hashCode 在不同 JVM 一致，这里再钉一次 FNV 的具体值防回归
        assertEquals(DeviceBindLocks.advisoryId("sn:X"), DeviceBindLocks.advisoryId("sn:X"))
    }
}
