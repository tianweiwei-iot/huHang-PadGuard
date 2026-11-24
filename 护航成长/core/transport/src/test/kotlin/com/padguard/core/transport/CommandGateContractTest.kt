package com.padguard.core.transport

import com.padguard.core.common.Crypto
import com.padguard.core.common.Logger
import com.padguard.core.common.TimeProvider
import com.padguard.core.data.db.BehaviorLogDao
import com.padguard.core.data.db.BehaviorLogEntity
import com.padguard.core.data.db.CommandRecordDao
import com.padguard.core.data.db.CommandRecordEntity
import com.padguard.core.data.db.RiskEventDao
import com.padguard.core.data.db.RiskEventEntity
import com.padguard.core.data.model.AckStatus
import com.padguard.core.data.model.CommandType
import com.padguard.core.data.model.RiskLevel
import com.padguard.core.data.model.RiskType
import com.padguard.core.data.model.Command
import com.padguard.core.data.repository.CommandRepository
import com.padguard.core.data.repository.LogRepository
import com.padguard.core.transport.http.CredentialStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

/**
 * [CommandGate] 三道关行为测试（契约 §4.1）。
 *
 * 关卡 1 的纯判定已由 [CommandFreshnessTest] 单独钉住；本测试锁的是**接线**：
 * 时效拒绝 → REJECTED 回执且不进幂等账本、验签失败 → REJECTED + INVALID_SIGNATURE 高危告警、
 * msgId 重复 → DUPLICATED、合法指令 → Accepted 并落 PENDING 记录。
 * "签名失败必须被看见"（上报而不静默丢弃）是该类的关键安全承诺，源码扫描守卫证不了它。
 *
 * 搭建说明：`CredentialStore` / `TransportSettings` 是 DataStore/Application 依赖的具体类，
 * 纯 JVM 下无法正常构造 —— 用 `ReflectionFactory` 跳过构造器只分配实例（Objenesis 同款原理，
 * 零新依赖），再反射填充被读字段；`CommandRepository`/`LogRepository` 走 fake DAO（与
 * `CommandRepositoryTest`/`LogRepositoryTest` 同惯例）。时间源用未对时的 [TimeProvider]
 * （`now()` 退化为墙钟），指令 timestamp/expiresAt 全部基于当前墙钟构造，避免时间炸弹。
 */
class CommandGateContractTest {

    // ---------- fake DAO ----------

    private class FakeCommandRecordDao : CommandRecordDao {
        val rows = LinkedHashMap<String, CommandRecordEntity>()

        override suspend fun get(msgId: String): CommandRecordEntity? = rows[msgId]

        override suspend fun upsert(entity: CommandRecordEntity) {
            rows[entity.msgId] = entity
        }

        override suspend fun recentIds(): List<String> =
            rows.values.sortedByDescending { it.receivedAt }.take(500).map { it.msgId }

        override suspend fun deleteBefore(before: Long): Int {
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

    private class FakeBehaviorLogDao : BehaviorLogDao {
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

    // ---------- 反射工具 ----------

    /** mock 实例上直写字段（Mockito mock 拦截 getter，字段读取仍走真实字段） */
    private fun setField(target: Any, name: String, value: Any?) {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                f.set(target, value)
                return
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            }
        }
        throw NoSuchFieldException(name)
    }

    // ---------- 环境 ----------

    private val secret = "unit-test-hmac-secret"
    private val timeProvider = TimeProvider()
    private val cmdDao = FakeCommandRecordDao()
    private val riskDao = FakeRiskDao()
    private lateinit var gate: CommandGate
    private var originalLoggerEnabled = true

    /** 凭据快照 mock：hmacSecret=deviceId 按 stub 返回（Mockito 5 inline 支持 final class） */
    private fun credentialsWith(hmacSecret: String): CredentialStore {
        val credentials = Mockito.mock(CredentialStore::class.java)
        Mockito.doReturn("dev-test-1").`when`(credentials).deviceId
        Mockito.doReturn(hmacSecret).`when`(credentials).hmacSecret
        return credentials
    }

    /** 传输配置 mock：useMock=false（非 mock 放行模式） */
    private fun settingsWith(useMock: Boolean): TransportSettings {
        val settings = Mockito.mock(TransportSettings::class.java)
        Mockito.doReturn(MutableStateFlow(useMock)).`when`(settings).useMock
        return settings
    }

    @Before
    fun setUp() {
        originalLoggerEnabled = Logger.enabled
        Logger.enabled = false

        val commandRepository = CommandRepository(cmdDao, timeProvider)
        val logRepository = LogRepository(FakeBehaviorLogDao(), riskDao, timeProvider)
        gate = CommandGate(credentialsWith(secret), timeProvider, commandRepository, logRepository, settingsWith(false))
    }

    @After
    fun tearDown() {
        Logger.enabled = originalLoggerEnabled
    }

    // ---------- 用例 ----------

    private fun wallNow(): Long = System.currentTimeMillis()

    /** 合法指令：时效新鲜 + 真实 HMAC 签名 */
    private fun signedCommand(
        msgId: String,
        timestamp: Long = wallNow(),
        expiresAt: Long = timestamp + 5 * 60 * 1000L,
        payload: Map<String, String> = emptyMap()
    ): Command {
        val base = Command(msgId = msgId, type = CommandType.LOCK_SCREEN, timestamp = timestamp, expiresAt = expiresAt, payload = payload)
        return base.copy(signature = Crypto.hmacSha256(base.signingPayload(), secret))
    }

    @Test
    fun `合法指令通过三道关并落幂等账本`() = runBlocking {
        val cmd = signedCommand("msg-ok-1")

        val verdict = gate.evaluate(cmd)

        assertTrue("合法指令应被接受", verdict is CommandGate.Verdict.Accepted)
        assertEquals("msg-ok-1", (verdict as CommandGate.Verdict.Accepted).command.msgId)
        assertEquals("已认领的指令应落 PENDING 记录", 1, cmdDao.rows.size)
    }

    @Test
    fun `payload 含转义字符的新格式签名被接受`() = runBlocking {
        val cmd = signedCommand("msg-ok-2", payload = mapOf("note" to "quote\"back\\slash"))

        val verdict = gate.evaluate(cmd)

        assertTrue(verdict is CommandGate.Verdict.Accepted)
    }

    @Test
    fun `过期指令被拒 REJECTED 且不进幂等账本`() = runBlocking {
        // expiresAt 已过（仍在签名封顶内）：显式过期生效
        val cmd = signedCommand("msg-exp", expiresAt = wallNow() - 10_000)

        val verdict = gate.evaluate(cmd)

        assertTrue(verdict is CommandGate.Verdict.Rejected)
        assertEquals(AckStatus.REJECTED, (verdict as CommandGate.Verdict.Rejected).ackStatus)
        assertEquals("时效拒绝发生在 tryClaim 之前，不得污染幂等账本", 0, cmdDao.rows.size)
    }

    @Test
    fun `重放超过签名封顶的旧指令被拒`() = runBlocking {
        // timestamp 在 20 分钟前：expiresAt=0 时封顶 = timestamp+15min < now，重放被拒
        val old = wallNow() - 20 * 60 * 1000L
        val cmd = signedCommand("msg-replay", timestamp = old, expiresAt = 0L)

        val verdict = gate.evaluate(cmd)

        assertTrue(verdict is CommandGate.Verdict.Rejected)
        assertEquals(AckStatus.REJECTED, (verdict as CommandGate.Verdict.Rejected).ackStatus)
    }

    @Test
    fun `签名不匹配被拒并上报 INVALID_SIGNATURE 高危告警`() = runBlocking {
        val cmd = signedCommand("msg-badsig").copy(signature = "tampered-signature")

        val verdict = gate.evaluate(cmd)

        assertTrue(verdict is CommandGate.Verdict.Rejected)
        assertEquals(AckStatus.REJECTED, (verdict as CommandGate.Verdict.Rejected).ackStatus)
        assertEquals("验签失败必须上报告警，不得静默丢弃", 1, riskDao.rows.size)
        assertEquals(RiskType.INVALID_SIGNATURE.name, riskDao.rows.first().type)
        assertEquals(RiskLevel.HIGH.name, riskDao.rows.first().level)
    }

    @Test
    fun `旧格式签名的指令走兼容回退仍被接受`() = runBlocking {
        // 值含双引号时新旧 canonical 串不同：旧格式签名触发 legacy 回退分支
        val base = Command(
            msgId = "msg-legacy",
            type = CommandType.LOCK_SCREEN,
            timestamp = wallNow(),
            expiresAt = wallNow() + 5 * 60 * 1000L,
            payload = mapOf("k" to "v\"x")
        )
        val cmd = base.copy(signature = Crypto.hmacSha256(base.signingPayloadLegacy(), secret))

        val verdict = gate.evaluate(cmd)

        assertTrue("legacy 签名应走回退分支被接受", verdict is CommandGate.Verdict.Accepted)
    }

    @Test
    fun `hmacSecret 缺失且非 mock 放行模式时被拒并上报`() = runBlocking {
        gate = CommandGate(
            credentialsWith(hmacSecret = ""),
            timeProvider,
            CommandRepository(cmdDao, timeProvider),
            LogRepository(FakeBehaviorLogDao(), riskDao, timeProvider),
            settingsWith(useMock = false)
        )

        val cmd = signedCommand("msg-nosecret")

        val verdict = gate.evaluate(cmd)

        assertTrue(verdict is CommandGate.Verdict.Rejected)
        assertEquals(AckStatus.REJECTED, (verdict as CommandGate.Verdict.Rejected).ackStatus)
        assertEquals("缺密钥无法验签同样必须被看见", 1, riskDao.rows.size)
        assertEquals(RiskType.INVALID_SIGNATURE.name, riskDao.rows.first().type)
    }

    @Test
    fun `同一 msgId 二次投递返回 DUPLICATED 不重复执行`() = runBlocking {
        val cmd = signedCommand("msg-dup")

        val first = gate.evaluate(cmd)
        val second = gate.evaluate(cmd)

        assertTrue(first is CommandGate.Verdict.Accepted)
        assertTrue(second is CommandGate.Verdict.Rejected)
        assertEquals(AckStatus.DUPLICATED, (second as CommandGate.Verdict.Rejected).ackStatus)
    }
}
