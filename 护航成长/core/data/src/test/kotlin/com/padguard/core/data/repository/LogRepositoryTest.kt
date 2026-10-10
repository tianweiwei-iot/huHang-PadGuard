package com.padguard.core.data.repository

import com.padguard.core.common.Logger
import com.padguard.core.data.db.BehaviorLogDao
import com.padguard.core.data.db.BehaviorLogEntity
import com.padguard.core.data.db.RiskEventDao
import com.padguard.core.data.db.RiskEventEntity
import com.padguard.core.data.model.LogType
import com.padguard.core.data.model.RiskLevel
import com.padguard.core.data.model.RiskType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [LogRepository] take/mark 精确配对（C2'）与容量保护的 JVM 行为测试。
 *
 * 核心回归点 —— **take 与 mark 之间有并发插入时不丢数据**：
 * 旧实现 mark 时"重查一次 pending 前 N 条"当刚上报批次，take 与 mark 之间
 * 新插入的行会被错标成已上传（静默丢失），而本批次的原始行反而滞留 pending。
 * 新实现 mark 按 take 时带出的自增主键精确回写，本测试用内存 fake DAO 复刻
 * "take → 并发插入 → mark" 时序，断言新行仍 pending、本批全部已标记。
 *
 * fake DAO 说明：Room @Dao 是纯接口，内存 List 语义即可满足被测路径；
 * 不引入 Robolectric / Room 测试框架 —— 这里要钉住的是**仓库层的配对逻辑**，
 * 不是 SQL 本身。
 */
class LogRepositoryTest {

    private var originalLoggerEnabled = true

    /** 内存版 BehaviorLogDao：自增 id + uploaded 过滤，语义与 SQL 一致 */
    private class FakeLogDao : BehaviorLogDao {
        val rows = mutableListOf<BehaviorLogEntity>()
        private var nextId = 1L

        override suspend fun pending(limit: Int): List<BehaviorLogEntity> =
            rows.filter { !it.uploaded }.sortedBy { it.timestamp }.take(limit)

        override suspend fun pendingCount(): Int = rows.count { !it.uploaded }

        override fun observePendingCount(): Flow<Int> =
            MutableStateFlow(rows).map { list -> list.count { !it.uploaded } }

        override fun observeRecentOfType(type: String, limit: Int): Flow<List<BehaviorLogEntity>> =
            MutableStateFlow(rows).map { list ->
                list.filter { it.type == type }.sortedByDescending { it.timestamp }.take(limit)
            }

        override suspend fun insert(entity: BehaviorLogEntity): Long {
            val withId = entity.copy(id = nextId++)
            rows.add(withId)
            return withId.id
        }

        override suspend fun insertAll(entities: List<BehaviorLogEntity>) {
            entities.forEach { insert(it) }
        }

        override suspend fun markUploaded(ids: List<Long>) {
            val set = ids.toSet()
            rows.replaceAll { if (it.id in set) it.copy(uploaded = true) else it }
        }

        override suspend fun deleteUploadedBefore(before: Long) {
            val victims = rows.filter { it.uploaded && it.timestamp < before }
            rows.removeAll(victims.toSet())
        }

        override suspend fun deleteOldestPending(count: Int): Int {
            val victims = rows.filter { !it.uploaded }.sortedBy { it.timestamp }.take(count)
            rows.removeAll(victims.toSet())
            return victims.size
        }

        override suspend fun clear() = rows.clear()
    }

    /** 内存版 RiskEventDao：同上 */
    private class FakeRiskDao : RiskEventDao {
        val rows = mutableListOf<RiskEventEntity>()
        private var nextId = 1L

        override suspend fun pending(limit: Int): List<RiskEventEntity> =
            rows.filter { !it.uploaded }.sortedBy { it.timestamp }.take(limit)

        override suspend fun pendingCount(): Int = rows.count { !it.uploaded }

        override fun observeRecent(limit: Int): Flow<List<RiskEventEntity>> =
            MutableStateFlow(rows).map { list -> list.sortedByDescending { it.timestamp }.take(limit) }

        override suspend fun insert(entity: RiskEventEntity): Long {
            val withId = entity.copy(id = nextId++)
            rows.add(withId)
            return withId.id
        }

        override suspend fun markUploaded(ids: List<Long>) {
            val set = ids.toSet()
            rows.replaceAll { if (it.id in set) it.copy(uploaded = true) else it }
        }

        override suspend fun deleteUploadedBefore(before: Long) {
            val victims = rows.filter { it.uploaded && it.timestamp < before }
            rows.removeAll(victims.toSet())
        }
    }

    private lateinit var logDao: FakeLogDao
    private lateinit var riskDao: FakeRiskDao
    private lateinit var repo: LogRepository

    @Before
    fun setUp() {
        originalLoggerEnabled = Logger.enabled
        Logger.enabled = false
        logDao = FakeLogDao()
        riskDao = FakeRiskDao()
        repo = LogRepository(logDao, riskDao)
    }

    @After
    fun tearDown() {
        Logger.enabled = originalLoggerEnabled
    }

    private fun logEntity(logId: String, ts: Long) = BehaviorLogEntity(
        logId = logId, type = LogType.APP_USAGE.name, timestamp = ts, payloadJson = "{}"
    )

    private fun alertEntity(eventId: String, ts: Long) = RiskEventEntity(
        eventId = eventId, type = RiskType.UNINSTALL_ATTEMPT.name,
        level = RiskLevel.HIGH.name, timestamp = ts, detailJson = "{}"
    )

    @Test
    fun `rows inserted between take and mark stay pending - only the taken batch is marked`() = runBlocking {
        // 批次 A：两条
        logDao.insert(logEntity("L1", 1_000L))
        logDao.insert(logEntity("L2", 2_000L))
        val batchA = repo.takePendingLogs()
        assertEquals(2, batchA.size)

        // 模拟上传期间的新上行（并发插入）
        logDao.insert(logEntity("L3", 3_000L))
        logDao.insert(logEntity("L4", 4_000L))

        // 标记批次 A：只有 L1/L2 被标记，新行必须仍然 pending
        repo.markLogsUploaded(batchA)

        assertEquals("并发插入的行不应被错标", 2, logDao.pendingCount())
        val stillPending = logDao.pending(10).map { it.logId }
        assertEquals(listOf("L3", "L4"), stillPending)
    }

    @Test
    fun `alert take and mark use the same precise pairing`() = runBlocking {
        riskDao.insert(alertEntity("E1", 1_000L))
        riskDao.insert(alertEntity("E2", 2_000L))
        val batch = repo.takePendingAlerts()
        assertEquals(2, batch.size)

        riskDao.insert(alertEntity("E3", 3_000L))

        repo.markAlertsUploaded(batch)

        assertEquals(1, riskDao.pendingCount())
        assertEquals("E3", riskDao.pending(10).single().eventId)
    }

    @Test
    fun `empty batch mark is a no-op`() = runBlocking {
        val empty = repo.takePendingLogs()
        assertTrue(empty.isEmpty)
        repo.markLogsUploaded(empty) // 不应抛异常、不应触发 DAO
        repo.markAlertsUploaded(repo.takePendingAlerts())
        assertEquals(0, logDao.pendingCount())
    }

    @Test
    fun `trim drops oldest pending normal logs only when over soft cap`() = runBlocking {
        // 直塞 50_001 条 pending，再走一次 append 触发 trim：
        // append 先插入再裁剪 → pending = 50_002，excess = 2（MAX_PENDING_LOGS = 50_000），
        // 应删除最旧的 2 条（B0、B1），回落到软上限。
        repeat(50_001) { i -> logDao.insert(logEntity("B$i", i.toLong())) }
        repo.append(LogType.APP_USAGE, emptyMap())
        assertEquals("超限后应回落到软上限 50_000", 50_000L, logDao.pendingCount().toLong())
        val remaining = logDao.pending(3).map { it.logId }
        assertEquals("被丢弃的应是最旧的 B0/B1", listOf("B2", "B3", "B4"), remaining)
    }
}
