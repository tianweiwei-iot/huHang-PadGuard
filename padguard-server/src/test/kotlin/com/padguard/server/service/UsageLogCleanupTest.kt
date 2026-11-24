package com.padguard.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.domain.UsageLog
import com.padguard.server.repository.UsageLogRepository
import com.padguard.server.support.TaskExecutionLock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Pageable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import java.sql.DriverManager

/**
 * 用量日志保留期清理的行为测试（合规凭证防线）。
 *
 * 钉住三条：
 * 1. **AGREEMENT 不随用量 TTL 删除** —— 说明书 §5.4 要求协议签署凭证「可事后追溯」，
 *    按 30 天清掉等于把审计证据删了，而家长端还在显示「已阅已同意」；
 * 2. 普通用量日志照常清理 —— 不能因为加了保护就把整个清理废掉，表会无限膨胀；
 * 3. `type` 为空的历史脏数据仍要清掉 —— 真实 SQL 里 `NULL NOT IN (...)` 结果是 UNKNOWN，
 *    少了 `l.type is null` 那一支就会留下永远删不掉的僵尸行。
 */
class UsageLogCleanupTest {

    private lateinit var repo: FakeUsageLogRepo
    private lateinit var cleanup: UsageLogCleanup

    private fun setup() {
        repo = FakeUsageLogRepo()
        cleanup = UsageLogCleanup(repo)
    }

    private fun log(
        id: String,
        type: String?,
        timestamp: Long?,
        payload: String? = null,
        receivedAt: Long = timestamp ?: 0L
    ) = UsageLog(
        id = id, deviceId = "dev-1", logId = "log-$id", type = type,
        timestamp = timestamp, payloadJson = payload, receivedAt = receivedAt
    )

    @Test
    fun `expired usage logs are deleted but compliance evidence survives`() {
        setup()
        val old = System.currentTimeMillis() - 40 * 86_400_000L
        val fresh = System.currentTimeMillis() - 2 * 86_400_000L
        repo.table["usage-old"] = log("usage-old", "USAGE_TOTAL", old)
        repo.table["usage-fresh"] = log("usage-fresh", "USAGE_TOTAL", fresh)
        repo.table["agree-old"] = log("agree-old", "AGREEMENT", old,
            """{"version":"1.0.0","signedAt":"$old","snMask":"a1b2"}""")

        cleanup.cleanup()

        assertFalse(repo.table.containsKey("usage-old"), "过期的用量日志必须被清理")
        assertTrue(repo.table.containsKey("usage-fresh"), "保留期内的用量日志不能被删")
        assertTrue(repo.table.containsKey("agree-old"), "AGREEMENT 是合规凭证，绝不能按 TTL 删除")
        assertEquals(2, repo.table.size)
    }

    @Test
    fun `rows with null type are still cleaned`() {
        setup()
        val old = System.currentTimeMillis() - 40 * 86_400_000L
        repo.table["dirty"] = log("dirty", null, old)
        repo.table["agree"] = log("agree", "AGREEMENT", old)

        cleanup.cleanup()

        assertFalse(repo.table.containsKey("dirty"), "type 为空的脏数据仍应被清理（对应 SQL 的 l.type is null 分支）")
        assertTrue(repo.table.containsKey("agree"))
    }

    /**
     * 判据是服务端 `received_at`，不是客户端上报的 `timestamp`。
     *
     * 两条都是"设备改时间"能打出来的现实故障，且旧实现（按 `timestamp` 判）必然复现：
     * - 时间调到未来 → 这些日志永远不满足 `< threshold`，只增不删，表无限膨胀；
     * - `timestamp` 缺失（NULL）→ `NULL < ?` 恒为 UNKNOWN，同样永远删不掉。
     */
    @Test
    fun `future device clock does not make expired logs immortal`() {
        setup()
        val receivedLongAgo = System.currentTimeMillis() - 40 * 86_400_000L
        val futureDeviceClock = System.currentTimeMillis() + 40 * 86_400_000L
        repo.table["shifted"] = log("shifted", "USAGE_TOTAL", futureDeviceClock, receivedAt = receivedLongAgo)

        cleanup.cleanup()

        assertFalse(
            repo.table.containsKey("shifted"),
            "落库已超过保留期的日志必须按服务端 received_at 清掉，不能因为设备时间超前而永久留存"
        )
    }

    @Test
    fun `rows without a device timestamp are still cleaned`() {
        setup()
        val receivedLongAgo = System.currentTimeMillis() - 40 * 86_400_000L
        repo.table["no-ts"] = log("no-ts", "USAGE_TOTAL", null, receivedAt = receivedLongAgo)

        cleanup.cleanup()

        assertFalse(
            repo.table.containsKey("no-ts"),
            "timestamp 为 NULL 的历史行曾因 NULL < ? 恒为 UNKNOWN 而永远删不掉"
        )
    }

    @Test
    fun `recently received rows survive even if the device clock is far in the past`() {
        setup()
        val deviceClockInPast = System.currentTimeMillis() - 400 * 86_400_000L
        repo.table["just-arrived"] = log(
            "just-arrived", "USAGE_TOTAL", deviceClockInPast, receivedAt = System.currentTimeMillis()
        )

        cleanup.cleanup()

        assertTrue(
            repo.table.containsKey("just-arrived"),
            "刚上报的数据不能被当成 400 天前的历史删掉（否则家长端记录凭空缺段）"
        )
    }

    @Test
    fun `cleanup deletes expired rows in bounded batches`() {
        setup()
        val old = System.currentTimeMillis() - 40 * 86_400_000L
        repeat(UsageLogCleanup.BATCH_SIZE + 3) { i ->
            repo.table["bulk-$i"] = log("bulk-$i", "USAGE_TOTAL", old)
        }

        cleanup.cleanup()

        assertTrue(repo.table.isEmpty(), "分页清理必须最终删净所有过期行")
        assertTrue(
            repo.findExpiredIdsCalls >= 2,
            "超过单批上限时必须分多批删除，不能回到单事务全量 DELETE（调用次数=${repo.findExpiredIdsCalls}）"
        )
    }

    @Test
    fun `protected types are the declared contract`() {        // 防止有人"顺手"把保护集合清空，让防线静默失效
        assertTrue(UsageLogCleanup.PROTECTED_TYPES.contains("AGREEMENT"))
        assertTrue(UsageLogCleanup.PROTECTED_TYPES.isNotEmpty(), "空集合会让 JPQL not in () 失效")
    }

    // ---- 多副本互斥：抢锁失败的副本必须跳过本轮，不得 fallback 再清一次 ----

    /**
     * 回归防线：`cleanup()` 曾写成 `runExclusively(...) ?: cleanupInternal()`，
     * 导致"锁存在但抢锁失败（返回 null）"时 Elvis 兜底又把 body 执行一遍——多副本
     * 同时清理，互斥形同虚设。这里用真实 H2 锁占住任务锁，验证输家副本确实跳过。
     */
    @Test
    fun `cleanup skipped when another replica holds the lock`() {
        val url = "jdbc:h2:mem:usagelock;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        val ds = SimpleDriverDataSource(DriverManager.getDriver(url), url, "sa", "")
        val jdbc = JdbcTemplate(ds)
        jdbc.execute(
            "CREATE TABLE IF NOT EXISTS task_locks (task_name VARCHAR(64) PRIMARY KEY, " +
                "holder VARCHAR(128) NOT NULL, acquired_at BIGINT NOT NULL, lease_until BIGINT NOT NULL)"
        )
        // 另一副本持有效租约占住 usage-log-cleanup
        jdbc.update(
            "INSERT INTO task_locks (task_name, holder, acquired_at, lease_until) VALUES (?, ?, ?, ?)",
            "usage-log-cleanup", "other-replica", System.currentTimeMillis(), System.currentTimeMillis() + 60_000L
        )
        val lock = TaskExecutionLock(jdbc)
        repo = FakeUsageLogRepo()
        val old = System.currentTimeMillis() - 40 * 86_400_000L
        repo.table["usage-old"] = log("usage-old", "USAGE_TOTAL", old)
        val cleanupWithLock = UsageLogCleanup(repo, lock)

        cleanupWithLock.cleanup()

        // 抢锁失败 → 本轮跳过 → 过期日志仍在（旧写法会把它删掉，此断言即回归防线）
        assertTrue(repo.table.containsKey("usage-old"), "抢锁失败的副本不得再执行清理")
        jdbc.execute("DROP TABLE IF EXISTS task_locks")
    }

    /** 对照：锁空闲时持锁副本照常清理，确保修复没有把互斥做成"永远不跑"。 */
    @Test
    fun `cleanup runs when lock is free`() {
        val url = "jdbc:h2:mem:usagelock2;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        val ds = SimpleDriverDataSource(DriverManager.getDriver(url), url, "sa", "")
        val jdbc = JdbcTemplate(ds)
        jdbc.execute(
            "CREATE TABLE IF NOT EXISTS task_locks (task_name VARCHAR(64) PRIMARY KEY, " +
                "holder VARCHAR(128) NOT NULL, acquired_at BIGINT NOT NULL, lease_until BIGINT NOT NULL)"
        )
        val lock = TaskExecutionLock(jdbc)
        repo = FakeUsageLogRepo()
        val old = System.currentTimeMillis() - 40 * 86_400_000L
        repo.table["usage-old"] = log("usage-old", "USAGE_TOTAL", old)
        val cleanupWithLock = UsageLogCleanup(repo, lock)

        cleanupWithLock.cleanup()

        assertFalse(repo.table.containsKey("usage-old"), "锁空闲时持锁副本必须照常清理")
        jdbc.execute("DROP TABLE IF EXISTS task_locks")
    }

    /** 只实现清理器与本测试用到的查询；其余 JpaRepository 样板由基类提供。 */
    private class FakeUsageLogRepo : FakeJpaRepository<UsageLog, String>(), UsageLogRepository {
        override val table = LinkedHashMap<String, UsageLog>()
        override fun idOf(entity: UsageLog): String = entity.id
        var findExpiredIdsCalls: Int = 0

        override fun findByDeviceIdOrderByReceivedAtDesc(
            deviceId: String,
            pageable: Pageable
        ): List<UsageLog> = table.values
            .filter { it.deviceId == deviceId }
            .sortedByDescending { it.receivedAt }
            .take(pageable.pageSize)

        override fun deleteExpiredExcept(threshold: Long, protectedTypes: Collection<String>): Int {
            // 判据是服务端 received_at，不是客户端可写的 timestamp（见仓储层注释）
            val victims = expiredIds(threshold, protectedTypes)
            victims.forEach { table.remove(it) }
            return victims.size
        }

        override fun findExpiredIds(
            threshold: Long,
            protectedTypes: Collection<String>,
            pageable: Pageable
        ): List<String> {
            findExpiredIdsCalls++
            return expiredIds(threshold, protectedTypes).take(pageable.pageSize)
        }

        override fun deleteByIds(ids: Collection<String>): Int {
            var n = 0
            ids.forEach { if (table.remove(it) != null) n++ }
            return n
        }

        private fun expiredIds(threshold: Long, protectedTypes: Collection<String>): List<String> =
            table.values.filter {
                it.receivedAt < threshold && (it.type == null || it.type !in protectedTypes)
            }.map { it.id }

        override fun findFirstByDeviceIdAndTypeOrderByTimestampDesc(
            deviceId: String, type: String
        ): UsageLog? = table.values
            .filter { it.deviceId == deviceId && it.type == type }
            .maxByOrNull { it.timestamp ?: 0L }

        /**
         * 家长端「通知记录」分页查询（type=NOTIFICATION_COLLECTED）。
         * 接口后续新增了此方法，Fake 未同步实现会让整个服务端 `compileTestKotlin` 失败，
         * 导致全量 `test` 一直跑不起来。按真实语义过滤 + 按 timestamp 倒序即可；
         * 本测试不分页断言，与上面其它 Fake 方法一致忽略 pageable。
         */
        override fun findByDeviceIdAndTypeOrderByTimestampDesc(
            deviceId: String, type: String, pageable: Pageable
        ): List<UsageLog> = table.values
            .filter { it.deviceId == deviceId && it.type == type }
            .sortedByDescending { it.timestamp ?: 0L }

        override fun existsByLogId(logId: String): Boolean = table.values.any { it.logId == logId }
        override fun findByDeviceId(deviceId: String): List<UsageLog> =
            table.values.filter { it.deviceId == deviceId }
        override fun findByDeviceIdAndTimestampBetween(deviceId: String, start: Long, end: Long): List<UsageLog> =
            table.values.filter { it.deviceId == deviceId && (it.timestamp ?: 0) in start..end }
        override fun findByDeviceIdAndTypeInAndTimestampBetween(
            deviceId: String, types: Collection<String>, start: Long, end: Long, pageable: Pageable
        ): List<UsageLog> = table.values.filter {
            it.deviceId == deviceId && it.type in types && (it.timestamp ?: 0) in start..end
        }
        override fun findExistingLogIds(logIds: Collection<String>): Set<String> =
            table.values.mapNotNull { it.logId.takeIf { id -> id in logIds } }.toSet()
        override fun insertIgnoreConflict(
            id: String, deviceId: String, logId: String, type: String?, timestamp: Long?,
            payloadJson: String?, receivedAt: Long
        ): Int {
            if (table.values.any { it.logId == logId }) return 0
            table[id] = UsageLog(id, deviceId, logId, type, timestamp, payloadJson, receivedAt)
            return 1
        }
        override fun deleteByDeviceId(deviceId: String): Int {
            val victims = table.values.filter { it.deviceId == deviceId }
            victims.forEach { table.remove(it.id) }
            return victims.size
        }
    }
}
