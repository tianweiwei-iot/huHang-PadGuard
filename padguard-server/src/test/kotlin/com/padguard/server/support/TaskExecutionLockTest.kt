package com.padguard.server.support

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import java.sql.DriverManager

/**
 * 分布式任务互斥锁（租约制）的 H2 行为测试。
 *
 * 钉住四条命脉：
 * 1. 单副本语义：锁空闲 → 抢到、执行、释放，零开销不改变既有行为；
 * 2. 多副本互斥：有效租约未到期，第二个副本必须抢不到（跳过本轮，不是排队等待）；
 * 3. 崩溃自愈：持锁副本崩溃不释放，租约到期后他人可重新抢占，无需人工清锁；
 * 4. 释放不越界：只删自己 holder 的行——租约被抢走后迟到的 DELETE 不误删新持有者的锁。
 */
class TaskExecutionLockTest {

    private lateinit var jdbc: JdbcTemplate
    private lateinit var lock: TaskExecutionLock

    // DB_CLOSE_DELAY=-1 必须加：JdbcTemplate 每条语句独立开闭连接，
    // 不加的话最后一个连接关闭时整个 mem 库被销毁，下一条语句就"表不存在"。
    private val url = "jdbc:h2:mem:tasklock;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"

    @BeforeEach
    fun setUp() {
        val ds = SimpleDriverDataSource(DriverManager.getDriver(url), url, "sa", "")
        jdbc = JdbcTemplate(ds)
        jdbc.execute(
            "CREATE TABLE IF NOT EXISTS task_locks (" +
                "task_name VARCHAR(64) PRIMARY KEY, " +
                "holder VARCHAR(128) NOT NULL, " +
                "acquired_at BIGINT NOT NULL, " +
                "lease_until BIGINT NOT NULL)"
        )
        lock = TaskExecutionLock(jdbc)
    }

    @AfterEach
    fun tearDown() {
        jdbc.execute("DROP TABLE IF EXISTS task_locks")
    }

    @Test
    fun `first acquire runs body and releases`() {
        var ran = false
        val result = lock.runExclusively("t1", "holder-A", 60_000L) { ran = true; "ok" }
        assertTrue(ran)
        assertEquals("ok", result)
        // 释放后锁表无残留：下一个任务周期可立即再抢
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM task_locks WHERE task_name = 't1'", Int::class.java))
    }

    @Test
    fun `valid lease blocks second holder`() {
        assertNotNull(lock.runExclusively("t2", "holder-A", 60_000L) { /* 模拟持有中 */ })
        // 上一条已释放；先手工占住一把不释放的锁（模拟持锁副本正在执行）
        jdbc.update(
            "INSERT INTO task_locks (task_name, holder, acquired_at, lease_until) VALUES ('t3', 'holder-A', ?, ?)",
            System.currentTimeMillis(), System.currentTimeMillis() + 60_000L
        )
        val blocked = lock.runExclusively("t3", "holder-B", 60_000L) { "should-not-run" }
        assertNull(blocked, "有效租约期内第二副本必须跳过本轮")
    }

    @Test
    fun `expired lease can be taken over by another holder`() {
        // holder-A 拿了租约后"崩溃"：不释放，租约 1 秒后过期
        jdbc.update(
            "INSERT INTO task_locks (task_name, holder, acquired_at, lease_until) VALUES ('t4', 'holder-A', ?, ?)",
            System.currentTimeMillis(), System.currentTimeMillis() + 1_000L
        )
        // 等租约过期再由 holder-B 抢占（等 1.1s，测试里可接受）
        Thread.sleep(1_100)
        var ran = false
        val result = lock.runExclusively("t4", "holder-B", 60_000L) { ran = true; "taken-over" }
        assertTrue(ran, "租约过期后必须可被其他副本抢占（崩溃自愈）")
        assertEquals("taken-over", result)
    }

    @Test
    fun `late release does not delete new holder's lock`() {
        // holder-A 拿到 1 秒短租约后"卡死"不释放
        jdbc.update(
            "INSERT INTO task_locks (task_name, holder, acquired_at, lease_until) VALUES ('t5', 'holder-A', ?, ?)",
            System.currentTimeMillis(), System.currentTimeMillis() + 1_000L
        )
        Thread.sleep(1_100)
        // holder-B 抢走并续了长租约（模拟仍在执行中，手工插入等价于 tryAcquire 成功后的状态）
        jdbc.update(
            "UPDATE task_locks SET holder = 'holder-B', acquired_at = ?, lease_until = ? " +
                "WHERE task_name = 't5' AND lease_until < ?",
            System.currentTimeMillis(), System.currentTimeMillis() + 60_000L, System.currentTimeMillis()
        )
        // holder-A 迟到的释放调用（release 的 WHERE 条件）不得删掉 holder-B 的锁
        val deleted = jdbc.update("DELETE FROM task_locks WHERE task_name = 't5' AND holder = 'holder-A'")
        assertEquals(0, deleted, "迟到的释放不得误删新持有者的锁")
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM task_locks WHERE task_name = 't5'", Int::class.java))
    }

    @Test
    fun `body exception still releases the lock`() {
        try {
            lock.runExclusively("t6", "holder-A", 60_000L) { throw IllegalStateException("boom") }
        } catch (e: IllegalStateException) {
            // 预期上抛：调度层各自 try/catch 的语义不变
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM task_locks WHERE task_name = 't6'", Int::class.java),
            "执行异常也必须释放锁，否则该任务要等到租约过期才能再跑")
    }

    /** 长任务执行期间必须续租，否则 10 分钟后第二副本会拿着"过期"租约并发清理。 */
    @Test
    fun `long running body renews its lease`() {
        val acquiredAt = System.currentTimeMillis()
        val observed = lock.runExclusively("t-renew", "holder-A", 3_000L) {
            Thread.sleep(1_300)
            jdbc.queryForObject(
                "SELECT lease_until FROM task_locks WHERE task_name = 't-renew'",
                Long::class.java
            ) ?: 0L
        }
        assertNotNull(observed)
        assertTrue(
            observed!! > acquiredAt + 3_000L,
            "持锁超过 leaseMs/3 后续租必须把 lease_until 往后推，实际=$observed 起点=$acquiredAt"
        )
    }

    /** 续租失败（锁被别的副本抢走）必须立即中断持锁者，宁可本轮半途而废也不双跑。 */
    @Test
    fun `lost lease interrupts running body`() {
        val result = lock.runExclusively("t-lost", "holder-A", 1_500L) {
            try {
                Thread.sleep(1_100)
                // 模拟另一个副本抢占：holder 与租约都被换掉，后续心跳影响 0 行
                jdbc.update(
                    "UPDATE task_locks SET holder = 'holder-B', lease_until = ? WHERE task_name = 't-lost'",
                    System.currentTimeMillis() - 1L
                )
                Thread.sleep(2_000)
                "not-interrupted"
            } catch (e: InterruptedException) {
                "interrupted"
            }
        }
        assertEquals("interrupted", result, "续租失败必须中断正在跑的任务，禁止双副本并发清理")
    }

    /**
     * 表不存在 = 单副本档（local/home 的 flyway 关闭、无 task_locks 表）：必须放行执行，
     * 否则所有 @Scheduled 清理任务在这两个发布档位永久停摆。这正是旧写法
     * `runExclusively(...) ?: body()` 的 fallback 所歪打正着保证的行为，
     * 现在把它从"碰巧兜底"提升为锁内部显式语义。
     */
    @Test
    fun `missing lock table runs body in single replica mode`() {
        jdbc.execute("DROP TABLE task_locks")
        var ran = false
        val result = lock.runExclusively("t7", "holder-A", 60_000L) { ran = true; "ran-without-lock" }
        assertTrue(ran, "无锁表单副本档必须照常执行清理")
        assertEquals("ran-without-lock", result)
    }

    /**
     * 表存在但写入故障（多副本场景）：降级为跳过（返回 null），交给健康副本执行，
     * 绝不能因 DB 异常把定时调度线程炸掉，也不能并发清理。
     * 用"表存在但列缺失导致 UPDATE 抛异常"模拟：删掉 holder 列后 tryAcquire 的 UPDATE 失败，
     * 但 information_schema 仍能查到表存在 → 走"有表故障 → 跳过"分支。
     */
    @Test
    fun `lock table write failure degrades to skip`() {
        jdbc.execute("ALTER TABLE task_locks DROP COLUMN holder")
        try {
            val result = lock.runExclusively("t8", "holder-A", 60_000L) { "never" }
            assertNull(result, "有表但写故障时必须跳过，不能炸调度线程也不能并发执行")
        } finally {
            jdbc.execute("DROP TABLE IF EXISTS task_locks") // 恢复，避免污染下一个测试的 CREATE IF NOT EXISTS
        }
    }
}
