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
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 订阅计费（套餐列表 / 订阅列表 / 订单列表 / 手动开通 / 退款 / 营收序列）。
 * W25 从 AdminService 抽离。
 */
@Service
class AdminBillingService(
    private val subscriptionRepository: SubscriptionRepository,
    private val paymentOrderRepository: PaymentOrderRepository,
    private val userRepository: UserRepository,
    private val billingService: BillingService,
    private val adminPerm: AdminPermissionService,
    private val auditService: AuditService
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

    fun listSubscriptions(adminId: String, status: String?): List<SubRow> {
        requireRead(adminPerm, adminId, "billing")
        var subs = subscriptionRepository.findAllByOrderByExpiredAtDesc()
        if (!status.isNullOrBlank()) subs = subs.filter { subStatusLower(it.status) == status.lowercase() }
        val userMap = userRepository.findAll().associateBy { it.id }
        return subs.map { s ->
            val owner = userMap[s.userId]
            SubRow(
                parentPhone = owner?.phone ?: s.userId, parentName = owner?.nickname ?: (owner?.phone?.let { "家长" + it.takeLast(4) } ?: ""),
                plan = planCategory(s.plan), planName = planLabel(s.plan),
                status = subStatusLower(s.status), startedAt = fmt(s.startedAt), expiredAt = fmt(s.expiredAt),
                autoRenew = s.autoRenew, channel = ""
            )
        }
    }

    fun listOrders(adminId: String, status: String?): List<OrderRow> {
        requireRead(adminPerm, adminId, "billing")
        var orders = paymentOrderRepository.findAllByOrderByCreatedAtDesc()
        if (!status.isNullOrBlank()) orders = orders.filter { orderStatusLower(it.status) == status.lowercase() }
        val userMap = userRepository.findAll().associateBy { it.id }
        return orders.map { o ->
            val owner = userMap[o.userId]
            OrderRow(
                orderNo = o.orderNo, parentPhone = owner?.phone ?: o.userId,
                parentName = owner?.nickname ?: (owner?.phone?.let { "家长" + it.takeLast(4) } ?: ""),
                plan = planCategory(o.plan), planName = planLabel(o.plan),
                amountFen = o.amountFen, status = orderStatusLower(o.status), channel = o.channel,
                createdAt = fmt(o.createdAt)
            )
        }
    }

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
        sub.expiredAt = base + req.days * 86_400_000L
        sub.updatedAt = now
        subscriptionRepository.save(sub)
        auditService.record(adminId, user.id, AdminAudit.BILLING_MANUAL, targetType = "subscription", targetId = sub.id, detail = "手动开通 ${planEnum.label} ${req.days}天 -> ${user.phone}")
        return mapOf("ok" to true, "status" to subStatusLower(sub.status), "expiredAt" to (fmt(sub.expiredAt) ?: ""))
    }

    @Transactional
    fun refund(adminId: String, orderNo: String): Map<String, Any> {
        adminPerm.require(adminId, "billing", "refund")
        // 设计约定（显性化）：退款仅变更订单状态，不联动回收订阅权益；营收统计基于订单 PAID 状态，自动自洽。
        val order = paymentOrderRepository.findByOrderNo(orderNo)
            ?: throw BizException(ParentErr.ORDER_NOT_FOUND, "订单不存在", Audience.PARENT)
        if (order.status == OrderStatus.REFUNDED.name) {
            return mapOf("ok" to true, "already" to true)
        }
        // 仅允许对「已支付」订单退款，避免形成无效的「假退款」脏数据。
        if (order.status != OrderStatus.PAID.name) {
            throw BizException(ParentErr.PARAM_ERROR, "仅已支付订单可退款（当前状态：${order.status}）", Audience.PARENT)
        }
        order.status = OrderStatus.REFUNDED.name
        paymentOrderRepository.save(order)
        auditService.record(adminId, order.userId, AdminAudit.BILLING_REFUND, targetType = "order", targetId = orderNo, detail = "退款订单 $orderNo")
        return mapOf("ok" to true)
    }

    fun revenue(adminId: String): List<RevenueMonth> {
        requireRead(adminPerm, adminId, "billing")
        return lastMonthsRevenue(paymentOrderRepository, 12)
    }
}
