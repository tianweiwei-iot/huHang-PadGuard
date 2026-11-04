package com.padguard.server.service

import com.padguard.server.domain.Subscription
import com.padguard.server.repository.SubscriptionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 试用订阅的"插入"动作，独立成 bean 的唯一目的是让 `REQUIRES_NEW` **真的生效**。
 *
 * ## 为什么不能直接写在 BillingService 里
 * `createTrial` 的调用方里，`markPaid` / `extend` / `manualGrant` 都处在事务中。
 * 在同一个事务里 `save()` 不会立刻发 INSERT —— Hibernate 要等到事务提交才 flush，
 * 而唯一约束冲突正是在提交那一刻抛出的。于是：
 * - `try { save() } catch (DataIntegrityViolationException)` 里的 catch **永远进不去**；
 * - 异常在方法返回之后才炸，直接把整个入账事务打成 rollback-only，
 *   结果是"钱已收到、订单回滚回 PENDING、订阅没开"，回调重投还会重复失败。
 *
 * 放进独立的 `REQUIRES_NEW` 事务后，`saveAndFlush` 在**本方法内**就完成 INSERT 并提交，
 * 撞唯一约束时异常在这里抛出、可被调用方捕获，外层事务完全不受影响（不毒化）。
 *
 * ## 代价
 * REQUIRES_NEW 需要第二条数据库连接（外层仍握着一条）。这个写入只在"某家长第一次产生订阅"
 * 时发生一次，不会落在高频路径上；连接池耗尽的风险可忽略。
 *
 * 单测里 BillingService 直接用内存假仓储，绕过本类（见 BillingService 构造参数默认值），
 * 因为 REQUIRES_NEW 是 Spring 事务基础设施的行为，纯单测无法验证。
 */
interface TrialSubscriptionInserter {
    /** 在独立事务中插入；失败（唯一约束冲突 / 其他数据库错误）直接抛给调用方 */
    fun insert(sub: Subscription)
}

@Service
class TransactionalTrialInserter(
    private val subscriptionRepository: SubscriptionRepository
) : TrialSubscriptionInserter {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun insert(sub: Subscription) {
        // saveAndFlush 而不是 save：必须在本方法返回前把 INSERT 打出去，
        // 否则异常仍会推迟到提交阶段，调用方 catch 不到（这正是本次要修的问题）。
        subscriptionRepository.saveAndFlush(sub)
    }
}
