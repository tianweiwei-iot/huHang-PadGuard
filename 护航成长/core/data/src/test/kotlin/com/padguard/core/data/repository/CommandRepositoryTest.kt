package com.padguard.core.data.repository

import com.padguard.core.common.Logger
import com.padguard.core.common.TimeProvider
import com.padguard.core.data.db.CommandRecordDao
import com.padguard.core.data.db.CommandRecordEntity
import com.padguard.core.data.model.AckStatus
import com.padguard.core.data.model.CommandType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CommandRepository] 的幂等账本语义测试。
 *
 * 钉住的是三条**曾经真实失效**的性质：
 *
 * 1. **终态必须落库**。旧实现里 `recordResult` 零调用点，于是每条指令永远停在 PENDING，
 *    而启动清理会把 PENDING 全删 —— 7 天幂等窗口实际不存在，重启即清零。
 *    本测试用 `resetInFlightOnStartup` 前后的记录数直接暴露这一点。
 * 2. **只有 PENDING 会被启动清理删掉**。终态记录必须活下来，否则服务端重发的旧指令
 *    （如早已失效的 LOCK_SCREEN）会被重新认领执行。
 * 3. **未认领的拒绝也可追溯**。签名/时效被拒时 `tryClaim` 从未执行，
 *    `recordResult` 要补插一条终态，而不是打一行 "unknown command" 就丢掉。
 *
 * fake DAO 说明：Room `@Dao` 是纯接口，内存 Map 即可复刻被测路径的去重/状态迁移语义；
 * 这里钉的是仓库层契约，不是 SQL 本身（SQL 由 Room 编译期校验 + 真人回归覆盖）。
 */
class CommandRepositoryTest {

    /** 内存版 CommandRecordDao：主键 msgId，语义与 `command_record` 表一致 */
    private class FakeCommandRecordDao : CommandRecordDao {
        val rows = LinkedHashMap<String, CommandRecordEntity>()
        /** 记录按年龄清理实际使用的阈值，用于断言它来自受信钟而非裸墙钟 */
        var lastDeleteBefore: Long? = null

        override suspend fun get(msgId: String): CommandRecordEntity? = rows[msgId]

        override suspend fun upsert(entity: CommandRecordEntity) {
            rows[entity.msgId] = entity
        }

        override suspend fun recentIds(): List<String> =
            rows.values.sortedByDescending { it.receivedAt }.take(500).map { it.msgId }

        override suspend fun deleteBefore(before: Long): Int {
            lastDeleteBefore = before
            val victims = rows.values.filter { it.receivedAt < before }.map { it.msgId }
            victims.forEach { rows.remove(it) }
            return victims.size
        }

        override suspend fun deleteByStatus(status: String): Int {
            val victims = rows.values.filter { it.status == status }.map { it.msgId }
            victims.forEach { rows.remove(it) }
            return victims.size
        }

        override suspend fun deleteBeyondWindow(keep: Int): Int {
            val ordered = rows.values.sortedByDescending { it.receivedAt }
            val victims = ordered.drop(keep).map { it.msgId }
            victims.forEach { rows.remove(it) }
            return victims.size
        }
    }

    private lateinit var dao: FakeCommandRecordDao
    private lateinit var repo: CommandRepository
    private var loggerEnabled = true

    @org.junit.Before
    fun setUp() {
        // android.util.Log 在 JVM 单元测试里是未实现的桩，任何一次实际打印都会抛
        // RuntimeException("Method ... not mocked")。仓库在清理残留 PENDING 时会打一条 warn，
        // 必须像 LogRepositoryTest 那样先把日志门面关掉。
        loggerEnabled = Logger.enabled
        Logger.enabled = false
        dao = FakeCommandRecordDao()
        repo = CommandRepository(dao, TimeProvider())
    }

    @org.junit.After
    fun tearDown() {
        Logger.enabled = loggerEnabled
    }

    /** 认领后再写终态：记录必须停留在 SUCCESS，而不是 PENDING */
    @Test
    fun `recordResult moves claimed command to terminal state`() = runBlocking {
        assertTrue(repo.tryClaim("m1", CommandType.LOCK_SCREEN, receivedAt = 1_000L))
        assertEquals(AckStatus.PENDING.name, dao.rows.getValue("m1").status)

        repo.recordResult("m1", CommandType.LOCK_SCREEN, AckStatus.SUCCESS, executedAt = 2_000L)

        val row = dao.rows.getValue("m1")
        assertEquals(AckStatus.SUCCESS.name, row.status)
        assertEquals(2_000L, row.executedAt)
    }

    /**
     * 核心回归：**已完成**的指令不能被启动清理带走。
     *
     * 旧实现的现场就是这样：`recordResult` 从未被调用 → 全表都是 PENDING →
     * `resetInFlightOnStartup` 一次清空整张表。这里断言"写完终态后清理一条都不删"。
     */
    @Test
    fun `startup cleanup keeps completed commands and only drops in-flight ones`() = runBlocking {
        repo.tryClaim("done", CommandType.UNLOCK, receivedAt = 1_000L)
        repo.recordResult("done", CommandType.UNLOCK, AckStatus.SUCCESS, executedAt = 1_100L)

        repo.tryClaim("crashed", CommandType.SCREEN_RECORD, receivedAt = 1_200L) // 认领后进程被杀

        val cleared = repo.resetInFlightOnStartup()

        assertEquals(1, cleared)
        assertNotNull("终态记录必须幸存，否则旧指令会被重放", dao.rows["done"])
        assertNull("残留 PENDING 必须清掉，否则指令永久丢失", dao.rows["crashed"])
        // 幸存的那条仍可判重：服务端重发同一条会被 tryClaim 拒绝
        assertFalse(repo.tryClaim("done", CommandType.UNLOCK, receivedAt = 9_999L))
    }

    /** 未认领即到终态（签名/时效拒绝）：补插记录而不是丢弃 */
    @Test
    fun `recordResult inserts a terminal row even when never claimed`() = runBlocking {
        repo.recordResult("forged", type = null, status = AckStatus.REJECTED, executedAt = 5_000L, errorMessage = "HMAC signature mismatch")

        val row = dao.rows.getValue("forged")
        assertEquals(AckStatus.REJECTED.name, row.status)
        assertEquals("UNKNOWN", row.type)
        assertEquals("HMAC signature mismatch", row.errorMessage)
        // 被拒过的 msgId 也有了记忆：再来一次直接判重
        assertFalse(repo.tryClaim("forged", CommandType.WIPE, receivedAt = 5_100L))
    }

    /**
     * 双裁剪：时间窗（7 天）与条数窗（500 条）都要生效。
     *
     * 只按时间裁：一次服务端故障重投风暴就能在 7 天里写进几万行；
     * 只按条数裁：长期离线又复连的设备会把窗口压到只剩几小时。
     */
    @Test
    fun `purgeExpired trims by both age and count window`() = runBlocking {
        val now = System.currentTimeMillis()
        val ancient = now - 8L * 24 * 60 * 60 * 1000

        // 1 条超龄记录
        repo.tryClaim("old", CommandType.FLUSH_LOGS, receivedAt = ancient)

        // 500+2 条新记录，触发条数窗
        repeat(CommandRepository.IDEMPOTENT_WINDOW + 2) { i ->
            repo.tryClaim("fresh-$i", CommandType.REFRESH_POLICY, receivedAt = now + i)
        }

        val deleted = repo.purgeExpired()

        assertTrue("应当删掉超龄记录与超出窗口的旧记录", deleted >= 3)
        assertTrue(
            "剩余条数不得超过幂等窗口",
            dao.rows.size <= CommandRepository.IDEMPOTENT_WINDOW
        )
        // 最新的那条必须还在（裁剪只能删最旧）
        assertNotNull(dao.rows["fresh-${CommandRepository.IDEMPOTENT_WINDOW + 1}"])
    }

    /** 同一 msgId 二次认领必须失败（MQTT + 轮询双通道的核心防线） */
    @Test
    fun `tryClaim is exclusive for the same msgId`() = runBlocking {
        assertTrue(repo.tryClaim("dup", CommandType.LOCK_SCREEN, receivedAt = 1L))
        assertFalse(repo.tryClaim("dup", CommandType.LOCK_SCREEN, receivedAt = 2L))
        assertEquals(1, dao.rows.size)
    }

    // ==================== 新增缺陷 G：幂等账本的时钟域 ====================

    /**
     * 核心回归：按年龄清理的阈值必须来自**受信钟**，不能来自裸墙钟。
     *
     * 这里故意让受信时刻落在 2026-01-01，而 JVM 墙钟是"现在"（晚得多），
     * 等价于设备把系统时间前拨了几个月。旧实现 `deleteBefore(墙钟 - 7天)` 的
     * 阈值会远大于账本里所有 receivedAt，**一次清空整张幂等表** ——
     * 7 天内执行过的 UNLOCK / PAUSE 指令随即全部可被重放。
     */
    @Test
    fun `purge age threshold comes from trusted clock not wall clock`() = runBlocking {
        val retention = 7L * 24 * 60 * 60 * 1000
        val trustedNow = 1_767_225_600_000L // 2026-01-01，显著早于 JVM 当前墙钟
        repo.tryClaim("executed", CommandType.UNLOCK, receivedAt = trustedNow - 1_000L)
        repo.recordResult("executed", CommandType.UNLOCK, AckStatus.SUCCESS, executedAt = trustedNow)

        val deleted = repo.purgeExpired(trustedNowMs = trustedNow)

        assertEquals(trustedNow - retention, dao.lastDeleteBefore!!)
        assertEquals(0, deleted)
        assertNotNull("墙钟前拨不得清空幂等账本", dao.rows["executed"])
    }

    /** 脏受信时刻：宁可跳过一轮按年龄删，也不能拿溢出/负值去 DELETE */
    @Test
    fun `purgeThreshold rejects dirty trusted time`() {
        val retention = 7L * 24 * 60 * 60 * 1000
        assertEquals(1_000L, CommandRepository.purgeThreshold(retention + 1_000L))
        assertNull(CommandRepository.purgeThreshold(0L))
        assertNull(CommandRepository.purgeThreshold(-1L))
        assertNull(CommandRepository.purgeThreshold(Long.MIN_VALUE))
        assertNull("阈值为 0 时也不删", CommandRepository.purgeThreshold(retention))
    }

    /** 脏值只能跳过「按年龄」这一步，条数窗必须照跑，否则指令重投风暴能撑爆表 */
    @Test
    fun `dirty trusted time skips age trim but still runs count window`() = runBlocking {
        repeat(CommandRepository.IDEMPOTENT_WINDOW + 3) { i ->
            repo.tryClaim("fresh-$i", CommandType.REFRESH_POLICY, receivedAt = 1_000L + i)
        }

        val deleted = repo.purgeExpired(trustedNowMs = 0L)

        assertNull("脏值不应发起按年龄 DELETE", dao.lastDeleteBefore)
        assertTrue("条数窗仍须生效", deleted >= 3)
        assertEquals(CommandRepository.IDEMPOTENT_WINDOW, dao.rows.size)
    }

    /**
     * 不显式传时间戳时，仓库必须写受信钟而不是 `System.currentTimeMillis()`。
     * 用一次 1 小时的对时样本人为拉开两个时钟域，断言账本落在受信那一侧。
     */
    @Test
    fun `default timestamps come from trusted clock`() = runBlocking {
        val skew = 60L * 60 * 1000
        val tp = TimeProvider()
        tp.syncServerTime(System.currentTimeMillis() + skew)
        val skewedRepo = CommandRepository(dao, tp)

        skewedRepo.tryClaim("signed", CommandType.LOCK_SCREEN)
        val claimedAt = dao.rows.getValue("signed").receivedAt
        assertTrue(
            "认领时间必须走受信钟（约 +1h）",
            claimedAt > System.currentTimeMillis() + skew - 10_000L
        )

        skewedRepo.recordResult("rejected", null, AckStatus.REJECTED)
        val terminal = dao.rows.getValue("rejected")
        assertTrue("拒绝补插同样走受信钟", terminal.receivedAt > System.currentTimeMillis() + skew - 10_000L)
        assertEquals(terminal.receivedAt, terminal.executedAt)
    }
}
