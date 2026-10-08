package com.padguard.server.service

import com.padguard.server.common.BizException
import com.padguard.server.domain.BindCode
import com.padguard.server.repository.BindCodeRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
 * 绑定码的单元测试。
 *
 * 之所以先给这条链路补测试：它是"家长能不能把孩子平板加上"的唯一入口，
 * 且刚刚改过三处语义（碰撞重试、原子抢占、事务回写），
 * 任何一处回归都直接表现为"扫码后家长端一直转圈最后提示失败"，
 * 而这种故障在真机上要两台设备配合才能复现，靠手工验证成本极高。
 */
class BindCodeServiceTest {

    /** 内存实现：`claimIfUnused` 严格按 SQL 语义（条件更新、返回影响行数）模拟。 */
    private class FakeRepo : BindCodeRepository {
        val table = LinkedHashMap<String, BindCode>()

        override fun <S : BindCode> save(entity: S): S {
            table[entity.code] = entity
            return entity
        }

        override fun findById(code: String): Optional<BindCode> = Optional.ofNullable(table[code])

        override fun existsByCode(code: String): Boolean = table.containsKey(code)

        override fun claimIfUnused(code: String, now: Long): Int {
            val bc = table[code] ?: return 0
            // 与 SQL 的 where 条件完全一致：未使用 且 未过期
            if (bc.used || bc.expiresAt < now) return 0
            bc.used = true
            return 1
        }

        override fun <S : BindCode> saveAll(entities: MutableIterable<S>): MutableList<S> =
            entities.mapTo(mutableListOf()) { save(it) }

        override fun findAll(): MutableList<BindCode> = table.values.toMutableList()
        override fun count(): Long = table.size.toLong()
        override fun existsById(id: String): Boolean = table.containsKey(id)
        override fun deleteById(id: String) { table.remove(id) }
        override fun delete(entity: BindCode) { table.remove(entity.code) }
        override fun deleteAll() { table.clear() }
        override fun deleteAll(entities: MutableIterable<BindCode>) { entities.forEach { delete(it) } }
        override fun deleteAllById(ids: MutableIterable<String>) { ids.forEach { table.remove(it) } }
        override fun deleteAllInBatch() { table.clear() }
        override fun deleteAllInBatch(entities: MutableIterable<BindCode>) { entities.forEach { delete(it) } }
        override fun deleteInBatch(entities: MutableIterable<BindCode>) { entities.forEach { delete(it) } }
        override fun deleteAllByIdInBatch(ids: MutableIterable<String>) { ids.forEach { table.remove(it) } }
        override fun findAll(sort: Sort): MutableList<BindCode> = findAll()
        override fun findAll(pageable: Pageable): Page<BindCode> = TODO("not used")
        override fun findAllById(ids: MutableIterable<String>): MutableList<BindCode> =
            ids.mapNotNull { table[it] }.toMutableList()
        override fun <S : BindCode> findAll(example: Example<S>): MutableList<S> = TODO("not used")
        override fun <S : BindCode> findAll(example: Example<S>, sort: Sort): MutableList<S> = TODO("not used")
        override fun <S : BindCode> findAll(example: Example<S>, pageable: Pageable): Page<S> = TODO("not used")
        override fun <S : BindCode> findOne(example: Example<S>): Optional<S> = TODO("not used")
        override fun <S : BindCode> exists(example: Example<S>): Boolean = TODO("not used")
        override fun <S : BindCode> count(example: Example<S>): Long = TODO("not used")
        override fun flush() = Unit
        override fun <S : BindCode> saveAndFlush(entity: S): S = save(entity)
        override fun <S : BindCode> saveAllAndFlush(entities: MutableIterable<S>): MutableList<S> = saveAll(entities)
        override fun getOne(id: String): BindCode = table[id] ?: throw NoSuchElementException(id)
        override fun getById(id: String): BindCode = getOne(id)
        override fun getReferenceById(id: String): BindCode = getOne(id)
        override fun <S : BindCode, R : Any> findBy(
            example: Example<S>,
            queryFunction: java.util.function.Function<
                org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R>
        ): R = TODO("not used")
    }

    private fun service(): Pair<BindCodeService, FakeRepo> {
        val repo = FakeRepo()
        return BindCodeService(repo) to repo
    }

    @Test
    fun `generate returns a 6 digit code that expires in ten minutes`() {
        val (svc, repo) = service()
        val resp = svc.generate("parent-1")

        assertTrue(resp.bindCode.matches(Regex("\\d{6}")), "绑定码应为 6 位数字，实际=${resp.bindCode}")
        val ttl = resp.expiresAt - System.currentTimeMillis()
        assertTrue(ttl in 590_000..600_000, "有效期应约 10 分钟，实际=$ttl ms")
        assertEquals("parent-1", repo.table[resp.bindCode]?.userId)
    }

    @Test
    fun `consume returns owner and marks the code used`() {
        val (svc, repo) = service()
        val code = svc.generate("parent-1").bindCode

        assertEquals("parent-1", svc.consume(code))
        assertTrue(repo.table[code]!!.used, "消费后必须置为已用")
    }

    @Test
    fun `consume twice fails - the same code must never bind two devices`() {
        val (svc, _) = service()
        val code = svc.generate("parent-1").bindCode

        svc.consume(code)
        // 并发下两个孩子端同时扫码：抢占失败的一方必须拿到异常，
        // 否则同一个码会把两台设备绑到同一个家长名下。
        assertThrows(BizException::class.java) { svc.consume(code) }
    }

    @Test
    fun `consume rejects expired codes`() {
        val (svc, repo) = service()
        val code = svc.generate("parent-1").bindCode
        repo.table[code]!!.expiresAt = System.currentTimeMillis() - 1

        assertThrows(BizException::class.java) { svc.consume(code) }
    }

    @Test
    fun `consume rejects unknown codes`() {
        val (svc, _) = service()
        assertThrows(BizException::class.java) { svc.consume("000000") }
    }

    @Test
    fun `status is PENDING before bind and BOUND after`() {
        val (svc, _) = service()
        val code = svc.generate("parent-1").bindCode

        assertEquals(BindCodeService.STATE_PENDING, svc.statusOf(code, "parent-1").state)

        svc.consume(code)
        svc.markBound(code, "device-9")
        val bound = svc.statusOf(code, "parent-1")
        assertEquals(BindCodeService.STATE_BOUND, bound.state)
        assertEquals("device-9", bound.deviceId)
    }

    @Test
    fun `status is EXPIRED after the code times out`() {
        val (svc, repo) = service()
        val code = svc.generate("parent-1").bindCode
        repo.table[code]!!.expiresAt = System.currentTimeMillis() - 1

        assertEquals(BindCodeService.STATE_EXPIRED, svc.statusOf(code, "parent-1").state)
    }

    @Test
    fun `status hides other parents codes`() {
        val (svc, _) = service()
        val code = svc.generate("parent-1").bindCode
        svc.consume(code)
        svc.markBound(code, "device-9")

        // 6 位码只有 90 万个取值，可穷举。若这里返回 BOUND，
        // 陌生人就能试探出别人家的设备 ID 与绑定结果。
        val other = svc.statusOf(code, "parent-2")
        assertEquals(BindCodeService.STATE_EXPIRED, other.state, "非本人不得看到真实状态")
        assertNull(other.deviceId, "非本人不得拿到 deviceId")
    }

    @Test
    fun `markBound on unknown code is a no-op instead of a crash`() {
        val (svc, _) = service()
        // 绑定码方式之外还有"账号密码绑定"，那条路径没有码；
        // 传空/未知码时必须静默跳过，不能抛异常把整个绑定流程打掉。
        svc.markBound("", "device-1")
        svc.markBound("999999", "device-1")
    }

    /**
     * 碰撞退避：生成的码不得与库中已有的码重复。
     *
     * 注意这里**不能**写成"占掉除一个以外的全部取值再断言命中那一个"——
     * generate 是随机取值，只剩 1/90 万命中率时重试 5 次几乎必败，
     * 那是测试设计错误，不是被测代码的问题（我第一版就是这么写的，确实挂了）。
     * 正确做法是占掉一小部分，然后断言"生成的码永远不在被占集合里"，
     * 这个断言是确定性的，且直接覆盖了碰撞重试这条路径。
     */
    @Test
    fun `generate never returns a code that already exists`() {
        val (svc, repo) = service()
        val now = System.currentTimeMillis()
        val taken = (100000..100499).map { it.toString() }.toSet()
        taken.forEach {
            repo.table[it] = BindCode(code = it, userId = "taken", expiresAt = now + 600_000, createdAt = now)
        }

        repeat(300) {
            val code = svc.generate("parent-1").bindCode
            assertTrue(code !in taken, "生成的码 $code 与已有记录冲突，会造成主键冲突异常")
            assertEquals("parent-1", repo.table[code]?.userId, "每次生成都应落到库里且归属正确")
        }

        // 顺带确认预置记录没有被覆盖
        assertEquals(500, taken.count { repo.table[it]?.userId == "taken" }, "已有记录不得被覆盖")
    }
}
