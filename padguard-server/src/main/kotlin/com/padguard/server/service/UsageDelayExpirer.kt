package com.padguard.server.service

import com.padguard.server.repository.UsageDelayRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 跨日/过期延时工单的**作废落库**（REQUIRES_NEW 独立事务）。
 *
 * ## 为什么必须独立成 bean
 * [UsageDelayService.approve] 在检测到「工单创建日 != 今天」或「会话 dayKey 过期」时，
 * 会先把工单 `claimReject` 置 REJECTED，再抛 `BizException`（RuntimeException）告知家长
 * 「已过期」。但 `@Transactional` 默认对 RuntimeException **整体回滚**，作废 UPDATE 会
 * 被一起撤销 —— 工单退回 PENDING，孩子端一直显示"待审核"，家长反复点同意都只得到
 * 过期提示，工单永远不消失。单元测试用 fake repo 无真实事务，断言 `status == REJECTED`
 * 会假绿，生产行为与之分叉。
 *
 * 把作废放进 `REQUIRES_NEW` 独立事务：它在外层 approve 事务回滚前**已单独提交**，
 * 不受调用方抛异常影响。必须是**独立的 Spring bean** —— `REQUIRES_NEW` 通过同类自调用
 * 不生效（不走代理），故不能作为 UsageDelayService 的私有/成员方法调用。
 *
 * 与 [AuditService] / [TrialSubscriptionInserter] 的独立事务范式一致。
 */
@Service
class UsageDelayExpirer(
    private val usageDelayRepository: UsageDelayRepository
) {
    private val log = LoggerFactory.getLogger(UsageDelayExpirer::class.java)

    /** 作废一张未结工单；返回是否真的作废了一条（终态不可回退由 DB WHERE 保证）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun expire(ticketId: String, now: Long): Boolean {
        val claimed = usageDelayRepository.claimReject(ticketId, now)
        if (claimed > 0) {
            log.info("usage delay ticket expired (cross-day): ticket={}", ticketId)
        }
        return claimed > 0
    }
}
