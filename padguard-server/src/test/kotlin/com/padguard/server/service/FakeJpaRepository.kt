package com.padguard.server.service

import com.padguard.server.domain.SystemSetting
import com.padguard.server.repository.SystemSettingRepository
import org.springframework.data.domain.Example
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.query.FluentQuery
import java.util.Optional
import java.util.function.Function

/**
 * JpaRepository 的内存实现基类：子类只需提供存储表与主键取值方式。
 *
 * 为什么需要它：JpaRepository 有三十多个方法，每个假仓储都手写一遍会让测试文件
 * 被样板代码淹没，真正要表达的断言反而看不清。这里把通用行为实现一次，
 * 子类只实现业务自定义的查询方法。
 *
 * 另外刻意**不使用 Mockito**：计费逻辑大量依赖"保存后实体状态被修改"这一副作用
 * （例如 refreshStatus 会把 EXPIRED 写回实体），Mockito 的默认 mock 会丢弃这些修改，
 * 测出来的结果与真实 JPA 行为不一致，反而掩盖 bug。
 */
abstract class FakeJpaRepository<T : Any, ID : Any> : JpaRepository<T, ID> {

    /** 公开可见：测试里常要直接查表断言落库结果。 */
    abstract val table: LinkedHashMap<ID, T>

    protected abstract fun idOf(entity: T): ID

    override fun <S : T> save(entity: S): S {
        table[idOf(entity)] = entity
        return entity
    }

    override fun <S : T> saveAll(entities: MutableIterable<S>): MutableList<S> =
        entities.mapTo(mutableListOf()) { save(it) }

    override fun findById(id: ID): Optional<T> = Optional.ofNullable(table[id])

    override fun existsById(id: ID): Boolean = table.containsKey(id)

    override fun findAll(): MutableList<T> = table.values.toMutableList()

    override fun findAllById(ids: MutableIterable<ID>): MutableList<T> =
        ids.mapNotNull { table[it] }.toMutableList()

    override fun count(): Long = table.size.toLong()

    override fun deleteById(id: ID) { table.remove(id) }

    override fun delete(entity: T) { table.remove(idOf(entity)) }

    override fun deleteAllById(ids: MutableIterable<ID>) { ids.forEach { table.remove(it) } }

    override fun deleteAll(entities: MutableIterable<T>) { entities.forEach { delete(it) } }

    override fun deleteAll() { table.clear() }

    override fun findAll(sort: Sort): MutableList<T> = findAll()

    override fun findAll(pageable: Pageable): Page<T> = PageImpl(findAll(), pageable, count())

    override fun <S : T> saveAndFlush(entity: S): S = save(entity)

    override fun <S : T> saveAllAndFlush(entities: MutableIterable<S>): MutableList<S> = saveAll(entities)

    override fun flush() = Unit

    override fun deleteAllInBatch(entities: MutableIterable<T>) { entities.forEach { delete(it) } }

    override fun deleteAllByIdInBatch(ids: MutableIterable<ID>) { ids.forEach { table.remove(it) } }

    override fun deleteAllInBatch() { table.clear() }

    override fun getOne(id: ID): T = table[id] ?: throw NoSuchElementException(id.toString())

    override fun getById(id: ID): T = getOne(id)

    override fun getReferenceById(id: ID): T = getOne(id)

    // ---- QueryByExample：本项目的假仓储用不到，保持未实现即可 ----

    override fun <S : T> findAll(example: Example<S>): MutableList<S> = unsupported()
    override fun <S : T> findAll(example: Example<S>, sort: Sort): MutableList<S> = unsupported()
    override fun <S : T> findAll(example: Example<S>, pageable: Pageable): Page<S> = unsupported()
    override fun <S : T> findOne(example: Example<S>): Optional<S> = unsupported()
    override fun <S : T> exists(example: Example<S>): Boolean = unsupported()
    override fun <S : T> count(example: Example<S>): Long = unsupported()
    override fun <S : T, R : Any> findBy(
        example: Example<S>,
        queryFunction: Function<FluentQuery.FetchableFluentQuery<S>, R>
    ): R = unsupported()

    private fun unsupported(): Nothing = TODO("QueryByExample not used by in-memory fakes")
}

/** 系统设置假仓储：findById 默认返回空，使 BillingService.effectiveTrialDays 安全回退到配置值。 */
class FakeSystemSettingRepo : FakeJpaRepository<SystemSetting, String>(), SystemSettingRepository {
    override val table = LinkedHashMap<String, SystemSetting>()
    override fun idOf(entity: SystemSetting): String = entity.key
}
