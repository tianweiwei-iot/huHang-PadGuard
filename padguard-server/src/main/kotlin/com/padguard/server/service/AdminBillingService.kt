package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.*
import com.padguard.server.dto.*
import com.padguard.server.repository.PaymentOrderRepository
import com.padguard.server.repository.SubscriptionRepository
import com.padguard.server.repository.UserRepository
import com.padguard.server.security.AdminPermissionService
import com.padguard.server.security.WeChatPayClient
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 订阅计费（套餐列表 / 订阅列表 / 订单列表 / 手动开通 / 退款 / 营收序列）。
 *
 */
@Service
class AdminBillingService(
    private val subscriptionRepository: SubscriptionRepository,
    private val paymentOrderRepository: PaymentOrderRepository,
    private val userRepository: UserRepository,
    private val billingService: BillingService,
    private val adminPerm: AdminPermissionService,
    private val auditService: AuditService,
    // 测试桩构造未传：可空 + 默认 null；Spring 注入真实 Bean
    private val weChatPayClient: WeChatPayClient? = null
) {
    private val log = LoggerFactory.getLogger(AdminBillingService::class.java)

    fun listPlans(adminId: String): List<AdminPlanDto> {
        requireRead(adminPerm, adminId, "billing")
        return billingService.plans().map { p ->
            AdminPlanDto(
                id = p.key, name = p.label, priceFen = p.amountFen, deviceLimit = p.deviceLimit,
                features = PLAN_FEATURES[p.key] ?: emptyList()
            )
        }
    }

    fun listSubscriptions(adminId: String, status: String?, page: Int = 1, size: Int = 20): PageResult<SubRow> {
        requireRead(adminPerm, adminId, "billing")
        val p = clampPage(page)
        val s = clampSize(size)
        val statusFilter = status?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: ""
        /*
         * 过滤与分页下推数据库。
         *
         * 改造前：`findAllByOrderByExpiredAtDesc()` 全表 → 内存过滤 → 内存分页，
         * 再加上 `userRepository.findAll()` 建全量家长映射 —— 只为了显示一页 20 行里的姓名和手机号。
         * 订阅表与家长表都只增不减，运营每翻一页都要把两张表读进堆里。
         */
        val result = subscriptionRepository.searchSubscriptions(statusFilter, PageRequest.of(p - 1, s))
        val users = usersOf(result.content.map { it.userId })
        return toPageResult(result, p, s) { sub -> toSubRow(sub, users[sub.userId]) }
    }

    fun listOrders(adminId: String, status: String?, page: Int = 1, size: Int = 20): PageResult<OrderRow> {
        requireRead(adminPerm, adminId, "billing")
        val p = clampPage(page)
        val s = clampSize(size)
        val statusFilter = status?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: ""
        // 同上：订单表是流水表，只增不减，全表读进来只为翻一页的代价随运营时间线性变差。
        val result = paymentOrderRepository.searchOrders(statusFilter, PageRequest.of(p - 1, s))
        val users = usersOf(result.content.map { it.userId })
        return toPageResult(result, p, s) { order -> toOrderRow(order, users[order.userId]) }
    }

    /** 只补当前页涉及的家长（一页最多 200 个 id），而不是把整张用户表读进内存 */
    private fun usersOf(userIds: Collection<String>): Map<String, User> {
        val ids = userIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return emptyMap()
        return userRepository.findAllById(ids).associateBy { it.id }
    }

    private fun toSubRow(s: Subscription, owner: User?): SubRow = SubRow(
        parentPhone = owner?.phone ?: s.userId,
        parentName = owner?.nickname ?: (owner?.phone?.let { "家长" + it.takeLast(4) } ?: ""),
        plan = planCategory(s.plan), planName = planLabel(s.plan),
        status = subStatusLower(s.status), startedAt = fmt(s.startedAt), expiredAt = fmt(s.expiredAt),
        autoRenew = s.autoRenew, channel = ""
    )

    private fun toOrderRow(o: PaymentOrder, owner: User?): OrderRow = OrderRow(
        orderNo = o.orderNo, parentPhone = owner?.phone ?: o.userId,
        parentName = owner?.nickname ?: (owner?.phone?.let { "家长" + it.takeLast(4) } ?: ""),
        plan = planCategory(o.plan), planName = planLabel(o.plan),
        amountFen = o.amountFen, status = orderStatusLower(o.status), channel = o.channel,
        createdAt = fmt(o.createdAt)
    )

    @Transactional
    fun manualActivate(adminId: String, req: AdminManualSubscriptionRequest): Map<String, Any> {
        adminPerm.require(adminId, "billing", "manual")
        val user = userRepository.findByPhone(req.parentPhone)
            ?: throw BizException(ParentErr.PARAM_ERROR, "家长账号不存在", Audience.PARENT)
        val planEnum = Plan.of(req.plan)
            ?: throw BizException(ParentErr.PARAM_ERROR, "套餐不存在", Audience.PARENT)
        if (req.days <= 0) {
            throw BizException(ParentErr.PARAM_ERROR, "开通天数必须为正整数", Audience.PARENT)
        }
        val now = System.currentTimeMillis()
        val sub = subscriptionRepository.findByUserId(user.id)
            ?: Subscription(
                id = "S" + UUID.randomUUID().toString().replace("-", ""), userId = user.id,
                plan = Plan.TRIAL.key, status = SubStatus.TRIAL.name,
                deviceLimit = billingService.deviceLimitOf(Plan.TRIAL),
                startedAt = now, expiredAt = now, createdAt = now, updatedAt = now
            )
        val base = maxOf(sub.expiredAt, now)
        sub.plan = planEnum.key
        sub.status = SubStatus.ACTIVE.name
        sub.deviceLimit = billingService.deviceLimitOf(planEnum)
        sub.expiredAt = expiryOf(base, planEnum, req.days)
        sub.updatedAt = now
        subscriptionRepository.save(sub)
        auditService.record(adminId, user.id, AdminAudit.BILLING_MANUAL, targetType = "subscription", targetId = sub.id, detail = "手动开通 ${planEnum.label} ${req.days}天 -> ${user.phone}")
        return mapOf("ok" to true, "status" to subStatusLower(sub.status), "expiredAt" to (fmt(sub.expiredAt) ?: ""))
    }

    /**
     * 后台手动开通的到期时刻。
     *
     * 与家长自助购买必须同一套日历口径：自助走的是"自然月 + 当日 23:59:59.999 收尾"
     * （[BillingService] 的 plusMonths）。后台此前用"天数 × 86400000"固定毫秒，
     * 于是年卡 = 360 天（比自助少 5 天），到期时刻还落在开通那一刻的钟点，
     * 家长端 remainDays 向上取整会显示得比实际少一天，最后一天中途就被锁机 —— 同一产品两种"一年"。
     *
     * 判定规则：套餐本身有月数、且调用方传的天数正好等于旧口径（months × 30）时按月推进；
     * 其余（如运营手工补 45 天）仍按天，保留灵活度。
     */
    private fun expiryOf(base: Long, plan: Plan, days: Int): Long {
        val months = plan.months
        if (months > 0 && days == months * 30) {
            val zone = com.padguard.server.common.SERVER_ZONE_ID
            val date = java.time.Instant.ofEpochMilli(base).atZone(zone).toLocalDate().plusMonths(months.toLong())
            return date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        }
        return base + days * 86_400_000L
    }

    @Transactional
    fun refund(adminId: String, orderNo: String): Map<String, Any> {
        adminPerm.require(adminId, "billing", "refund")
        // 退款口径：订单标记 REFUNDED；订阅权益不自动回收（显性设计约定，见下）。
        // 营收统计基于订单 PAID 状态，退款后自动自洽（PAID 单数减少）。
        val order = paymentOrderRepository.findByOrderNo(orderNo)
            ?: throw BizException(ParentErr.ORDER_NOT_FOUND, "订单不存在", Audience.PARENT)
        if (order.status == OrderStatus.REFUNDED.name) {
            return mapOf("ok" to true, "already" to true)
        }
        // 仅允许对「已支付」订单退款，避免形成无效的「假退款」脏数据。
        if (order.status != OrderStatus.PAID.name) {
            throw BizException(ParentErr.PARAM_ERROR, "仅已支付订单可退款（当前状态：${order.status}）", Audience.PARENT)
        }

        // 真实微信渠道：先向微信申请退款，受理成功才允许标记本系统订单——
        // 顺序反了会出现"订单显示已退款、微信侧钱实际没退"的单边账。
        // 申请失败时明确报错，引导管理员到商户平台手工退款（幂等兜底路径）。
        var refundId: String? = null
        if (order.channel == PayChannel.WECHAT.name && weChatPayClient?.isConfigured() == true) {
            val accepted = weChatPayClient.createRefund(
                orderNo = order.orderNo,
                outRefundNo = "R" + order.orderNo,
                refundFen = order.amountFen,
                totalFen = order.amountFen,
                reason = "用户退款"
            ) ?: throw BizException(
                ParentErr.PARAM_ERROR,
                "微信退款申请失败，请稍后重试或到微信商户平台手工退款",
                Audience.PARENT
            )
            refundId = accepted["refundId"]
            log.info("wechat refund applied: orderNo={}, refundId={}, status={}", orderNo, refundId, accepted["status"])
        }

        val marked = paymentOrderRepository.markRefundedIfPaid(
            orderNo = orderNo,
            refundedStatus = OrderStatus.REFUNDED.name,
            paidStatus = OrderStatus.PAID.name
        )
        if (marked == 0) {
            // 并发退款：另一事务已标记，直接幂等成功
            return mapOf("ok" to true, "already" to true)
        }
        auditService.record(
            adminId, order.userId, AdminAudit.BILLING_REFUND,
            targetType = "order", targetId = orderNo,
            detail = buildString {
                append("退款订单 ").append(orderNo)
                refundId?.let { append(" (微信退款单 ").append(it).append(")") }
            }
        )
        return mapOf("ok" to true, "refundId" to (refundId ?: ""))
    }

    fun revenue(adminId: String): List<RevenueMonth> {
        requireRead(adminPerm, adminId, "billing")
        return lastMonthsRevenue(paymentOrderRepository, 12)
    }
}
