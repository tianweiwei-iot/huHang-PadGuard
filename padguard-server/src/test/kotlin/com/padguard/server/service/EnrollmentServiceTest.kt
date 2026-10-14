package com.padguard.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.common.BizException
import com.padguard.server.domain.EnrollmentSession
import com.padguard.server.repository.EnrollmentSessionRepository
import com.padguard.server.ws.WebSocketPushService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Example
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import java.util.Optional

/**
 * 设备接入（Enrollment）的单元测试。
 *
 * 这些断言直接对应线上那个"添加设备时看不到/用不了连接信息"的故障：
 * 一个 6 位码同时充当二维码内容、配对码与状态容器，于是家长端为两个展示位置各发一次请求、
 * 拿到两个不同的码，又无从订阅接入进展。测试把它们钉死在模型层面：
 * 二维码与配对码必须同源、重复打开必须幂等、绑定结果必须能被查询与推送。
 */
class EnrollmentServiceTest {

    /** 内存实现：读写同一批对象，模拟"同一持久化上下文里对同一行的连续修改最终合并为一次写库"。 */
    private class FakeRepo : EnrollmentSessionRepository {
        val table = LinkedHashMap<String, EnrollmentSession>()

        override fun <S : EnrollmentSession> save(entity: S): S {
            table[entity.id] = entity
            return entity
        }

        override fun findById(id: String): Optional<EnrollmentSession> = Optional.ofNullable(table[id])

        override fun findByPairingCode(code: String): EnrollmentSession? =
            table.values.firstOrNull { it.pairingCode == code }

        override fun findFirstByUserIdAndStateOrderByCreatedAtDesc(
            userId: String, state: String
        ): EnrollmentSession? = table.values
            .filter { it.userId == userId && it.state == state }
            .maxByOrNull { it.createdAt }

        override fun findFirstByUserIdOrderByCreatedAtDesc(userId: String): EnrollmentSession? =
            table.values.filter { it.userId == userId }.maxByOrNull { it.createdAt }

        override fun existsByPairingCode(code: String): Boolean =
            table.values.any { it.pairingCode == code }

        override fun purgeTerminalBefore(before: Long): Int {
            val dead = table.values.filter {
                it.state != EnrollmentService.STATE_PENDING && it.createdAt < before
            }
            dead.forEach { table.remove(it.id) }
            return dead.size
        }

        override fun <S : EnrollmentSession> saveAll(entities: MutableIterable<S>): MutableList<S> =
            entities.mapTo(mutableListOf()) { save(it) }
        override fun findAll(): MutableList<EnrollmentSession> = table.values.toMutableList()
        override fun count(): Long = table.size.toLong()
        override fun existsById(id: String): Boolean = table.containsKey(id)
        override fun deleteById(id: String) { table.remove(id) }
        override fun delete(entity: EnrollmentSession) { table.remove(entity.id) }
        override fun deleteAll() { table.clear() }
        override fun deleteAll(entities: MutableIterable<EnrollmentSession>) { entities.forEach { delete(it) } }
        override fun deleteAllById(ids: MutableIterable<String>) { ids.forEach { table.remove(it) } }
        override fun deleteAllInBatch() { table.clear() }
        override fun deleteAllInBatch(entities: MutableIterable<EnrollmentSession>) { entities.forEach { delete(it) } }
        override fun deleteInBatch(entities: MutableIterable<EnrollmentSession>) { entities.forEach { delete(it) } }
        override fun deleteAllByIdInBatch(ids: MutableIterable<String>) { ids.forEach { table.remove(it) } }
        override fun findAll(sort: Sort): MutableList<EnrollmentSession> = findAll()
        override fun findAll(pageable: Pageable): Page<EnrollmentSession> = TODO("not used")
        override fun findAllById(ids: MutableIterable<String>): MutableList<EnrollmentSession> =
            ids.mapNotNull { table[it] }.toMutableList()
        override fun <S : EnrollmentSession> findAll(example: Example<S>): MutableList<S> = TODO("not used")
        override fun <S : EnrollmentSession> findAll(example: Example<S>, sort: Sort): MutableList<S> = TODO("not used")
        override fun <S : EnrollmentSession> findAll(example: Example<S>, pageable: Pageable): Page<S> = TODO("not used")
        override fun <S : EnrollmentSession> findOne(example: Example<S>): Optional<S> = TODO("not used")
        override fun <S : EnrollmentSession> exists(example: Example<S>): Boolean = TODO("not used")
        override fun <S : EnrollmentSession> count(example: Example<S>): Long = TODO("not used")
        override fun flush() = Unit
        override fun <S : EnrollmentSession> saveAndFlush(entity: S): S = save(entity)
        override fun <S : EnrollmentSession> saveAllAndFlush(entities: MutableIterable<S>): MutableList<S> = saveAll(entities)
        override fun getOne(id: String): EnrollmentSession = table[id] ?: throw NoSuchElementException(id)
        override fun getById(id: String): EnrollmentSession = getOne(id)
        override fun getReferenceById(id: String): EnrollmentSession = getOne(id)
        override fun <S : EnrollmentSession, R : Any> findBy(
            example: Example<S>,
            queryFunction: java.util.function.Function<
                org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R>
        ): R = TODO("not used")
    }

    /** 捕获推送：既要断言"推了什么"，也要断言"推给了谁"（推错家长等于没推） */
    private class FakePush : WebSocketPushService(ObjectMapper()) {
        val recipients = mutableListOf<String?>()
        val events = mutableListOf<Map<String, Any?>>()
        override fun pushToUser(userId: String?, payload: Map<String, Any?>) {
            recipients += userId
            events += payload
        }
    }

    private fun service(): Triple<EnrollmentService, FakeRepo, FakePush> {
        val repo = FakeRepo()
        val push = FakePush()
        return Triple(EnrollmentService(repo, push), repo, push)
    }

    /** 核心：二维码与配对码必须来自同一个会话 —— 这是"两个码"故障的根治断言。 */
    @Test
    fun `qr payload carries exactly the displayed pairing code`() {
        val (svc, _, _) = service()
        val info = svc.open("parent-1")

        assertTrue(info.pairingCode.matches(Regex("\\d{6}")), "接入码应为 6 位数字，实际=${info.pairingCode}")
        assertEquals("padguard://bind?code=${info.pairingCode}", info.qrPayload) {
            "二维码载荷必须封装与展示值完全相同的接入码，否则孩子端扫到的是另一个码"
        }
    }

    /** 核心：重复打开接入页必须复用同一个会话，不能每次换一个码。 */
    @Test
    fun `open is idempotent - reopening keeps the same session and code`() {
        val (svc, repo, _) = service()
        val first = svc.open("parent-1")
        val second = svc.open("parent-1")

        assertEquals(first.sessionId, second.sessionId, "重复打开不得另起会话")
        assertEquals(first.pairingCode, second.pairingCode) {
            "重复打开不得换码：家长退出去再进来，孩子手里刚扫到的码会立刻失效"
        }
        assertEquals(1, repo.table.size, "幂等打开不应堆积会话记录")
    }

    @Test
    fun `a fresh session is PENDING and expires in ten minutes`() {
        val (svc, _, _) = service()
        val info = svc.open("parent-1")
        val now = System.currentTimeMillis()

        assertEquals(EnrollmentService.STATE_PENDING, info.state)
        val ttl = info.expiresAt - now
        assertTrue(ttl in 590_000..600_000, "有效期应约 10 分钟，实际=$ttl ms")
        assertTrue(info.serverNow in (now - 5_000)..(now + 5_000), "serverNow 用于家长端倒计时，必须来自服务端时钟")
    }

    @Test
    fun `consume returns the owner and the session, and never succeeds twice`() {
        val (svc, _, _) = service()
        val info = svc.open("parent-1")

        val claim = svc.consume(info.pairingCode)
        assertEquals("parent-1", claim.userId)
        assertEquals(info.sessionId, claim.sessionId)

        assertThrows(BizException::class.java, { svc.consume(info.pairingCode) }, "同一个接入码不得绑上两台设备")
    }

    /**
     * 状态迁移必须发生在抢占（consume）这一步，不能滞后到回写 deviceId 的那次保存。
     *
     * 这条断言锁住的是"状态与结果走同一条写路径"这一约定：抢占成功，会话立刻就是 BOUND，
     * [EnrollmentService.markBound] 只补 deviceId。
     * 历史上这里曾用 `@Modifying` 条件 UPDATE 在库里改状态，而持久化上下文里的实体仍是
     * PENDING，随后回写 deviceId 的 save() 把那个陈旧实体整体写回，把 BOUND 覆盖回 PENDING
     * —— 设备绑上了、deviceId 也回写了，家长端却永远停在"等待孩子扫码"。
     */
    @Test
    fun `consuming a code flips the session to BOUND before the device is written back`() {
        val (svc, repo, _) = service()
        val info = svc.open("parent-1")

        svc.consume(info.pairingCode)

        assertEquals(EnrollmentService.STATE_BOUND, repo.table[info.sessionId]!!.state) {
            "抢占成功即应置为 BOUND：状态迁移不得依赖后续那次保存"
        }
    }

    @Test
    fun `an expired session reads as EXPIRED and can no longer be consumed`() {
        val (svc, repo, _) = service()
        val info = svc.open("parent-1")
        repo.table[info.sessionId]!!.expiresAt = System.currentTimeMillis() - 1

        assertEquals(EnrollmentService.STATE_EXPIRED, svc.current("parent-1")!!.state)
        assertThrows(BizException::class.java) { svc.consume(info.pairingCode) }
    }

    /** 核心：绑定完成后，家长端靠查询就能拿到 BOUND 与 deviceId（不依赖设备列表里出现新 ID）。 */
    @Test
    fun `markBound makes the session report BOUND with the device id`() {
        val (svc, _, _) = service()
        val info = svc.open("parent-1")
        svc.consume(info.pairingCode)
        svc.markBound(info.sessionId, "device-9")

        val state = svc.current("parent-1")!!
        assertEquals(EnrollmentService.STATE_BOUND, state.state)
        assertEquals("device-9", state.deviceId)
    }

    /** 核心：绑定完成必须主动推送，家长端无需轮询才知道孩子端已经绑上。 */
    @Test
    fun `markBound pushes an enrolled event to the owning parent`() {
        val (svc, _, push) = service()
        val info = svc.open("parent-1")
        svc.consume(info.pairingCode)
        svc.markBound(info.sessionId, "device-9")

        assertEquals("parent-1", push.recipients.single(), "事件必须推给发起接入的家长本人")
        val event = push.events.single()
        assertEquals("device.enrolled", event["type"])
        assertEquals(info.sessionId, event["sessionId"])
        assertEquals("device-9", event["deviceId"])
    }

    @Test
    fun `refresh issues a new code and invalidates the previous one`() {
        val (svc, _, _) = service()
        val first = svc.open("parent-1")
        val refreshed = svc.refresh("parent-1")

        assertNotEquals(first.sessionId, refreshed.sessionId, "换码应另起会话")
        assertNotEquals(first.pairingCode, refreshed.pairingCode)
        assertThrows(BizException::class.java, { svc.consume(first.pairingCode) }, "换码后旧码必须立即失效")
        assertEquals("parent-1", svc.consume(refreshed.pairingCode).userId)
    }

    @Test
    fun `cancel stops a pending enrollment`() {
        val (svc, _, _) = service()
        val info = svc.open("parent-1")
        svc.cancel("parent-1")

        assertEquals(EnrollmentService.STATE_CANCELLED, svc.current("parent-1")!!.state)
        assertThrows(BizException::class.java) { svc.consume(info.pairingCode) }
    }

    @Test
    fun `unknown codes are rejected`() {
        val (svc, _, _) = service()
        assertThrows(BizException::class.java) { svc.consume("000000") }
    }

    @Test
    fun `there is no session before the parent ever opens one`() {
        val (svc, _, _) = service()
        assertNull(svc.current("parent-1"))
    }

    /** 「账号密码绑定」不经过接入会话：回写必须静默跳过，不能把整条绑定流程打掉。 */
    @Test
    fun `markBound on a blank session is a no-op`() {
        val (svc, _, _) = service()
        svc.markBound("", "device-1")
        svc.markBound("no-such-session", "device-1")
    }

    /**
     * 短码碰撞退避：生成的码不得与库中已有记录冲突（否则唯一约束直接把"打开接入页"打挂）。
     *
     * 与旧测试同理，只占一小段取值再断言"永不命中"，而不是占满空间后赌那一次随机命中。
     */
    @Test
    fun `generated code never collides with an existing one`() {
        val (svc, repo, _) = service()
        val now = System.currentTimeMillis()
        val taken = (100000..100499).map { it.toString() }.toSet()
        taken.forEach {
            repo.table[it] = EnrollmentSession(
                id = it, userId = "taken", pairingCode = it,
                state = EnrollmentService.STATE_BOUND, expiresAt = now + 600_000, createdAt = now
            )
        }

        repeat(300) {
            val info = svc.refresh("parent-1")
            assertTrue(info.pairingCode !in taken, "接入码 ${info.pairingCode} 与已有记录冲突")
            assertEquals("parent-1", repo.table[info.sessionId]?.userId)
        }
        assertEquals(500, taken.count { repo.table[it]?.userId == "taken" }, "已有记录不得被覆盖")
    }

    /** 历史会话必须能被清理，否则短码空间被挤占、碰撞概率单调上升。 */
    @Test
    fun `terminal sessions older than the retention window are purged on open`() {
        val (svc, repo, _) = service()
        val now = System.currentTimeMillis()
        repo.table["old"] = EnrollmentSession(
            id = "old", userId = "parent-1", pairingCode = "111111",
            state = EnrollmentService.STATE_BOUND, expiresAt = now - 1, createdAt = now - 48 * 3600_000
        )
        repo.table["fresh"] = EnrollmentSession(
            id = "fresh", userId = "parent-1", pairingCode = "222222",
            state = EnrollmentService.STATE_BOUND, expiresAt = now - 1, createdAt = now - 60_000
        )

        svc.open("parent-1")

        assertEquals(false, repo.table.containsKey("old"), "超出保留窗口的终态会话应被清理")
        assertEquals(true, repo.table.containsKey("fresh"), "窗口内的记录应保留以供排障")
    }
}
