package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.OrderStatus
import com.padguard.server.domain.PayChannel
import com.padguard.server.common.ChildErr
import com.padguard.server.common.SERVER_ZONE_ID
import com.padguard.server.domain.PaymentOrder
import com.padguard.server.domain.Plan
import com.padguard.server.domain.SubStatus
import com.padguard.server.domain.Subscription
import com.padguard.server.domain.UserStatus
import com.padguard.server.dto.CreateOrderRequest
import com.padguard.server.dto.CreateOrderResponse
import com.padguard.server.dto.OrderDto
import com.padguard.server.dto.PayChannelDto
import com.padguard.server.dto.PlanDto
import com.padguard.server.dto.SubscriptionDto
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.PaymentOrderRepository
import com.padguard.server.repository.SubscriptionRepository
import com.padguard.server.repository.SystemSettingRepository
import com.padguard.server.repository.UserRepository
import com.padguard.server.security.PaymentVerifier
import com.padguard.server.dto.PayRequest
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * 订阅与计费（SaaS 模式：服务端在云端，订阅状态由服务端强制校验）。
 *
 * 设计要点：
 *
 * 1. **订阅绑定家长账号而非设备**：一个家长一份订阅，其名下所有设备共享额度。
 *    设备级订阅会让"换平板"变成重新付费，是家长最容易投诉的点。
 * 2. **一个家长只有一条当前订阅**，续费=延长 expired_at，不新增记录。
 *    多条并存时"当前是否有效"要按时间排序取最新，一旦出现重叠区间（续费后退款）必然判错。
 * 3. **试用期复用同一套状态机**（status=TRIAL）：这样"能不能用"只有一处判断，
 *    不会出现试用期走一条路径、付费后走另一条路径这种必然漂移的双实现。
 * 4. 价格与设备上限全部走配置，运营调价不需要改代码发版。
 */
@Service
class BillingService(
    private val subscriptionRepository: SubscriptionRepository,
    private val paymentOrderRepository: PaymentOrderRepository,
    private val deviceRepository: DeviceRepository,
    private val userRepository: UserRepository,

    @Value("\${padguard.billing.trial-days:1}") private val trialDays: Long,
    @Value("\${padguard.billing.trial-devices:3}") private val trialDevices: Int,
    @Value("\${padguard.billing.price.monthly:3800}") private val priceMonthly: Int,
    @Value("\${padguard.billing.price.quarterly:9800}") private val priceQuarterly: Int,
    @Value("\${padguard.billing.price.yearly:29800}") private val priceYearly: Int,
    @Value("\${padguard.billing.devices.monthly:3}") private val devicesMonthly: Int,
    @Value("\${padguard.billing.devices.quarterly:3}") private val devicesQuarterly: Int,
    @Value("\${padguard.billing.devices.yearly:5}") private val devicesYearly: Int,
    @Value("\${padguard.billing.pay-timeout-minutes:30}") private val payTimeoutMinutes: Long,
    /** 部署期兜底值；运营在后台改过之后以 DB 为准（见 [mockPayEnabled]） */
    @Value("\${padguard.billing.mock-pay-enabled:false}") private val configMockPayEnabled: Boolean,
    private val paymentVerifier: PaymentVerifier,
    private val systemSettingRepository: SystemSettingRepository,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(BillingService::class.java)

    // ------------------------------------------------------------------ 套餐

    fun amountFenOf(plan: Plan): Int = when (plan) {
        Plan.MONTHLY -> priceMonthly
        Plan.QUARTERLY -> priceQuarterly
        Plan.YEARLY -> priceYearly
        Plan.TRIAL -> 0
        Plan.PERMANENT -> 0
    }

    fun deviceLimitOf(plan: Plan): Int = when (plan) {
        Plan.MONTHLY -> devicesMonthly
        Plan.QUARTERLY -> devicesQuarterly
        Plan.YEARLY -> devicesYearly
        Plan.TRIAL -> trialDevices
        Plan.PERMANENT -> 10
    }

    /** 供家长端展示的可购买套餐列表 */
    fun plans(): List<PlanDto> = Plan.purchasable().map { p ->
        PlanDto(
            key = p.key, label = p.label, months = p.months,
            amountFen = amountFenOf(p), priceText = fenToText(amountFenOf(p)),
            deviceLimit = deviceLimitOf(p)
        )
    }

    /**
     * 收银台可选的支付渠道。
     *
     * 真实渠道未接入前一律返回**示例支付**（[PayChannelDto.mock] = true），
     * UI 据此打上"示例"角标，家长不会产生"我已经付款了"的误解。
     * 接入真实渠道后，这里按渠道返回对应的可用性，前端不用改。
     */
    fun channels(): List<PayChannelDto> = PayChannel.checkoutChannels().map { c ->
        PayChannelDto(
            key = c.name,
            label = c.label,
            enabled = mockPayEnabled(),
            mock = mockPayEnabled(),
            note = if (mockPayEnabled()) "示例支付，不会产生真实扣款" else "渠道接入中，暂不可用"
        )
    }

    // -------------------------------------------------------------- 订阅状态

    /**
     * 查询当前订阅；**若该家长还没有订阅记录则自动开通试用**。
     *
     * 自动开通是必须的：存量账号（本系统上线前注册的）不会走"注册送试用"的逻辑，
     * 没有这一步它们会永远处在"无订阅"状态，一升级就被彻底锁死。
     */
    fun statusOf(userId: String): SubscriptionDto {
        val sub = subscriptionRepository.findByUserId(userId) ?: createTrial(userId)
        refreshStatus(sub)
        return toDto(sub)
    }

    private fun toDto(sub: Subscription): SubscriptionDto {
        val now = System.currentTimeMillis()
        val remainMs = (sub.expiredAt - now).coerceAtLeast(0)
        // 剩余天数用「向上取整」：刚开通的 N 天试用应立即显示「剩余 N 天」，
        // 用整除(向下取整)会因毫秒误差少算一天（7 天试用一开通就显示剩余 6 天），属显示 bug。
        // 永久会员用哨兵 -1 表示"无到期"，UI 据此展示「永久有效」。
        val remainDays = when {
            sub.plan == Plan.PERMANENT.key -> -1
            remainMs <= 0 -> 0
            else -> ((remainMs + 86_399_999L) / 86_400_000L).toInt()
        }
        return SubscriptionDto(
            status = sub.status,
            plan = sub.plan,
            planLabel = Plan.of(sub.plan)?.label ?: sub.plan,
            deviceLimit = sub.deviceLimit,
            deviceUsed = deviceRepository.countByUserId(sub.userId).toInt(),
            startedAt = sub.startedAt,
            expiredAt = sub.expiredAt,
            remainDays = remainDays,
            paid = sub.status == SubStatus.ACTIVE.name,
            active = sub.status == SubStatus.TRIAL.name || sub.status == SubStatus.ACTIVE.name,
            autoRenew = sub.autoRenew
        )
    }

    /** 过期后状态不会自己变，读取时必须刷新，否则家长端会一直显示"生效中" */
    private fun refreshStatus(sub: Subscription) {
        // 永久会员不受时间约束：状态机永远不进入过期分支
        if (sub.plan == Plan.PERMANENT.key) return
        if (sub.status == SubStatus.EXPIRED.name || sub.status == SubStatus.REVOKED.name) return
        if (sub.expiredAt < System.currentTimeMillis()) {
            sub.status = SubStatus.EXPIRED.name
            sub.updatedAt = System.currentTimeMillis()
            subscriptionRepository.save(sub)
        }
    }

    /**
     * 试用期天数的**唯一事实源**：后台系统设置（DB）优先，缺失或非法时回退配置文件。
     *
     * 为什么不能只读 `@Value`：后台「系统设置」里同样有一个 trialDays，
     * 若两边各读各的，运营在后台把试用期改成 3 天后界面显示已生效、实际仍是配置值 ——
     * 这类"改了却不生效"的配置漂移最难排查（界面与行为不一致，且没有任何报错）。
     * 这里收敛为 DB 优先、配置兜底，保证界面显示与实际发放永远是同一个数。
     *
     * 只按字段名读取而非反序列化整个后台设置对象，是为了不把计费绑死在后台设置的结构上：
     * 后台设置将来增删字段不会导致这里解析失败（解析失败也只会安全回退到配置值）。
     */
    /**
     * 示例支付开关：后台系统设置（DB）优先，未保存则回退部署配置。
     *
     * 为什么改：早期后台「系统设置 → 示例支付」只是把一个布尔值存进库里，
     * 计费侧读的却是启动时的 `padguard.billing.mock-pay-enabled` 配置项，
     * 两者完全断开 —— 运营在后台打开开关没有任何效果，必须改配置重启，属于"设了没用"。
     * 现在与 [effectiveTrialDays] 同一口径：DB 优先、配置兜底、解析失败安全回退。
     */
    private fun mockPayEnabled(): Boolean =
        runCatching {
            systemSettingRepository.findById(SETTINGS_KEY).orElse(null)?.valueJson
                ?.let { objectMapper.readTree(it).get("mockPay")?.asBoolean() }
        }.getOrNull() ?: configMockPayEnabled

    private fun effectiveTrialDays(): Long =
        runCatching {
            systemSettingRepository.findById(SETTINGS_KEY).orElse(null)?.valueJson
                ?.let { objectMapper.readTree(it).get("trialDays")?.asLong() }
                ?.takeIf { it > 0 }
        }.getOrNull() ?: trialDays

    private fun createTrial(userId: String): Subscription {
        val now = System.currentTimeMillis()
        val days = effectiveTrialDays()
        val sub = Subscription(
            id = UUID.randomUUID().toString(),
            userId = userId,
            plan = Plan.TRIAL.key,
            status = SubStatus.TRIAL.name,
            deviceLimit = trialDevices,
            startedAt = now,
            expiredAt = now + days * 86_400_000L,
            createdAt = now,
            updatedAt = now
        )
        subscriptionRepository.save(sub)
        log.info("trial subscription created: userId={}, days={}", userId, days)
        return sub
    }

    // ------------------------------------------------------------------ 校验

    /**
     * 校验订阅可用；不可用抛业务异常。
     *
     * 这是全系统"付费才能用"的唯一闸门 —— 所有受控能力都必须经过它，
     * 而不是每个接口各写一遍判断（那样迟早有接口漏掉，变成免费后门）。
     *
     * **账号冻结优先于订阅判定**：一条 status=ACTIVE（甚至永久会员）的订阅，
     * 不代表被冻结的账号能继续用它。之前这里只看订阅，
     * 于是"后台冻结 + 永久会员"组合下家长端所有权益照常可用 —— 正是本次修复的缺陷。
     */
    fun ensureActive(userId: String) {
        requireUsableAccount(userId)
        val sub = subscriptionRepository.findByUserId(userId) ?: createTrial(userId)
        refreshStatus(sub)
        when (sub.status) {
            SubStatus.TRIAL.name, SubStatus.ACTIVE.name -> Unit
            SubStatus.EXPIRED.name -> throw BizException(
                ParentErr.SUBSCRIPTION_REQUIRED,
                if (sub.plan == Plan.TRIAL.key) "免费试用已结束，购买套餐后即可继续使用"
                else "订阅已到期，续费后即可继续使用",
                Audience.PARENT
            )
            else -> throw BizException(
                ParentErr.SUBSCRIPTION_REQUIRED, "订阅不可用，请联系客服", Audience.PARENT
            )
        }
    }

    /**
     * 账号状态闸门：冻结 / 未激活的家长，其名下权益一律停用。
     *
     * 与 [ParentAuthInterceptor] 中的判定是同一份 [UserStatus.isUsable] 口径，
     * 这里再判一次不是为了重复，而是因为订阅闸门还被鉴权链路之外的调用方触达
     * （WebSocket 握手、孩子端绑定、后台手动开通等），那些路径不会经过家长端拦截器。
     */
    private fun requireUsableAccount(userId: String) {
        val user = userRepository.findById(userId).orElse(null)
            ?: throw BizException(ParentErr.UNAUTHORIZED, "账号不存在或已被重置，请重新登录", Audience.PARENT)
        val status = UserStatus.of(user.status)
        if (UserStatus.isUsable(status)) return
        throw BizException(
            if (status == UserStatus.PENDING) ParentErr.ACCOUNT_PENDING else ParentErr.ACCOUNT_SUSPENDED,
            if (status == UserStatus.SUSPENDED) "账号已被冻结，权益暂不可用。如需恢复请联系客服。"
            else "账号尚未激活，请等待开通后再使用。",
            Audience.PARENT
        )
    }

    /** 孩子端视角的校验：错误码走 ChildErr，提示语要让孩子端能直接展示给家长看 */
    fun ensureActiveForChild(userId: String) {
        val user = userRepository.findById(userId).orElse(null)
        if (user != null && !UserStatus.isUsable(user.status)) {
            throw BizException(
                ChildErr.PARENT_ACCOUNT_SUSPENDED,
                "家长账号已被冻结，无法绑定设备。请联系客服恢复后再试。",
                Audience.CHILD
            )
        }
        val sub = subscriptionRepository.findByUserId(userId) ?: createTrial(userId)
        refreshStatus(sub)
        if (sub.status != SubStatus.TRIAL.name && sub.status != SubStatus.ACTIVE.name) {
            throw BizException(
                ChildErr.SUBSCRIPTION_REQUIRED,
                if (sub.plan == Plan.TRIAL.key) "家长账号的免费试用已结束，购买套餐后才能绑定设备"
                else "家长账号的订阅已到期，续费后才能绑定设备",
                Audience.CHILD
            )
        }
    }

    /**
     * 绑定前的校验：订阅可用 **且** 设备数未超限。
     *
     * 顺序很重要 —— 先判订阅再判设备数，否则未付费用户会收到"设备数超限"，
     * 那是完全错误的引导（他会去买更多额度，而不是去付费）。
     */
    fun ensureCanBind(userId: String) {
        ensureActive(userId)
        val sub = subscriptionRepository.findByUserId(userId)!!
        val used = deviceRepository.countByUserId(userId).toInt()
        if (used >= sub.deviceLimit) {
            throw BizException(
                ParentErr.DEVICE_LIMIT_EXCEEDED,
                "当前套餐最多绑定 ${sub.deviceLimit} 台设备，升级套餐后可绑定更多",
                Audience.PARENT
            )
        }
    }

    fun ensureCanBindForChild(userId: String) {
        ensureActiveForChild(userId)
        val sub = subscriptionRepository.findByUserId(userId)!!
        val used = deviceRepository.countByUserId(userId).toInt()
        if (used >= sub.deviceLimit) {
            throw BizException(
                ChildErr.DEVICE_LIMIT_EXCEEDED,
                "家长账号可绑定设备数已达上限（${sub.deviceLimit} 台）",
                Audience.CHILD
            )
        }
    }

    // ------------------------------------------------------------------ 下单

    @Transactional
    fun createOrder(userId: String, req: CreateOrderRequest): CreateOrderResponse {
        val plan = Plan.of(req.plan)
            ?: throw BizException(ParentErr.PARAM_ERROR, "套餐不存在", Audience.PARENT)
        // 只接受收银台可购买套餐：TRIAL（无需下单）与 PERMANENT（一次性后台开通，不参与下单/
        // 金额计算，否则可被 ¥0 下单 + mockPay 白嫖永久会员）都不在此列。
        // 早期只拦截了 TRIAL，PERMANENT 仍能下单 —— 一个 `plan=PERMANENT` 的请求即可生成 ¥0 订单，
        // 经 mockPay / markPaid 直接开通永久会员，是典型"绕过收费"后门。
        if (plan !in Plan.purchasable()) {
            throw BizException(ParentErr.PARAM_ERROR, "该套餐不可购买", Audience.PARENT)
        }
        val channel = req.channel?.let { PayChannel.of(it) }
            ?: throw BizException(ParentErr.PARAM_ERROR, "请选择支付渠道", Audience.PARENT)
        // 客服代开通（MANUAL）永远可用；收银台渠道只有在开了示例支付时才可下单 ——
        // 真实渠道未接入前就在生产放开它们，等于开一条"能下单、没人收款、也没人开通"的口子，
        // 家长付款后订阅不变，是最难解释的一类投诉。
        if (channel != PayChannel.MANUAL && !mockPayEnabled()) {
            throw BizException(
                ParentErr.PARAM_ERROR,
                "支付渠道 ${channel.label} 尚未接入，请稍后开放",
                Audience.PARENT
            )
        }

        val now = System.currentTimeMillis()
        val order = PaymentOrder(
            id = UUID.randomUUID().toString(),
            orderNo = newOrderNo(now),
            userId = userId,
            plan = plan.key,
            months = plan.months,
            amountFen = amountFenOf(plan),
            channel = channel.name,
            status = OrderStatus.PENDING.name,
            createdAt = now,
            payDeadline = now + payTimeoutMinutes * 60_000L
        )
        paymentOrderRepository.save(order)
        log.info("order created: no={}, userId={}, plan={}, amount={}fen", order.orderNo, userId, plan.key, order.amountFen)
        return CreateOrderResponse(
            orderNo = order.orderNo,
            amountFen = order.amountFen,
            channel = order.channel,
            payDeadline = order.payDeadline,
            payParams = null
        )
    }

    /**
     * 支付成功 → 开通/续费。
     *
     * 幂等：回调可能重复投递，重复处理会把订阅延长两次。
     * 只有 PENDING → PAID 才真正入账，其余状态直接返回成功。
     */
    @Transactional
    fun markPaid(orderNo: String, transactionId: String? = null): SubscriptionDto {
        val order = paymentOrderRepository.findByOrderNo(orderNo)
            ?: throw BizException(ParentErr.ORDER_NOT_FOUND, "订单不存在", Audience.PARENT)
        if (order.status != OrderStatus.PENDING.name) {
            log.info("order already settled: no={}, status={} (duplicate callback ignored)", orderNo, order.status)
            return statusOf(order.userId)
        }
        val now = System.currentTimeMillis()
        // 超时拒绝只针对"既超时又无支付凭证"的订单：
        // 异步回调可能晚于 payDeadline 才到达，但订单其实已付款（transactionId 非空即渠道已确认收款），
        // 这种单若直接关掉会变成"已付款却不开通"，是家长最难理解、最难安抚的一类投诉。
        // 而"超时且无凭证"的订单本来就不会收到任何回调，关掉让它重新下单是正确行为。
        if (order.payDeadline < now && transactionId.isNullOrBlank()) {
            order.status = OrderStatus.CLOSED.name
            paymentOrderRepository.save(order)
            throw BizException(ParentErr.ORDER_STATE_INVALID, "订单已超时关闭，请重新下单", Audience.PARENT)
        }

        order.status = OrderStatus.PAID.name
        order.paidAt = now
        order.transactionId = transactionId
        paymentOrderRepository.save(order)

        extend(order.userId, order.months, order.plan)
        log.info("order paid: no={}, userId={}, plan={}", orderNo, order.userId, order.plan)
        return statusOf(order.userId)
    }

    /**
     * 支付回调统一入口（[BillingController.notify] 调用）。
     *
     * 责任边界：这里只做"渠道校验 + 验签 + 防白嫖"，真正的入账逻辑在 [markPaid]。
     * 之所以单独抽一层而不让控制器直接调 markPaid：
     * 1. 公开回调无鉴权，必须先在服务端验签，绝不能直接信任报文体里的 orderNo/transactionId；
     * 2. 后台代开通（MANUAL）订单不应经此公开入口入账，必须拦掉，否则拿后台单号即可白嫖；
     * 3. 将来接真实渠道只需补一个 [com.padguard.server.security.ChannelPaymentVerifier]，本方法不动。
     */
    @Transactional
    fun handleNotify(req: PayRequest, rawBody: String, headers: Map<String, String>): SubscriptionDto {
        val order = paymentOrderRepository.findByOrderNo(req.orderNo)
            ?: throw BizException(ParentErr.ORDER_NOT_FOUND, "订单不存在", Audience.PARENT)

        // 后台代开通订单（MANUAL）不走公开回调：客服代开通由后台 manualGrant 直接入账，
        // 公开 notify 收到 MANUAL 单号说明有人拿后台渠道单来白嫖，直接拒绝。
        if (order.channel == PayChannel.MANUAL.name) {
            log.warn("manual channel does not accept public notify: orderNo={}", req.orderNo)
            throw BizException(ParentErr.FORBIDDEN, "该渠道不支持公开回调", Audience.PARENT)
        }

        if (!paymentVerifier.verify(order.channel, rawBody, headers)) {
            log.warn("payment notify verification failed: orderNo={}, channel={}", req.orderNo, order.channel)
            throw BizException(ParentErr.ORDER_STATE_INVALID, "支付校验未通过", Audience.PARENT)
        }

        return markPaid(req.orderNo, req.transactionId)
    }

    /** 延长订阅：从"当前有效期"或"现在"中较晚者起算，避免过期后续费被倒扣时长 */
    @Transactional
    fun extend(userId: String, months: Int, planKey: String) {
        val now = System.currentTimeMillis()
        val sub = subscriptionRepository.findByUserId(userId) ?: createTrial(userId)
        val base = maxOf(sub.expiredAt, now)
        val newExpire = plusMonths(base, months)
        sub.plan = planKey
        sub.status = SubStatus.ACTIVE.name
        sub.deviceLimit = deviceLimitOf(Plan.of(planKey) ?: Plan.MONTHLY)
        sub.expiredAt = newExpire
        sub.updatedAt = now
        subscriptionRepository.save(sub)
        log.info("subscription extended: userId={}, plan={}, expireAt={}", userId, planKey, newExpire)
    }

    /** 后台手动开通（客服处理"已付款但没到账"的兜底） */
    @Transactional
    fun manualGrant(userId: String, planKey: String): SubscriptionDto {
        val plan = Plan.of(planKey)
            ?: throw BizException(ParentErr.PARAM_ERROR, "套餐不存在", Audience.PARENT)
        extend(userId, plan.months, plan.key)
        return statusOf(userId)
    }

    /** 模拟支付：仅开发环境开启，生产必须关闭，否则等于绕过收费（S16 补归属校验） */
    fun mockPay(userId: String, orderNo: String): SubscriptionDto {
        if (!mockPayEnabled()) {
            throw BizException(ParentErr.PARAM_ERROR, "模拟支付未开启", Audience.PARENT)
        }
        val order = paymentOrderRepository.findByOrderNo(orderNo)
            ?: throw BizException(ParentErr.ORDER_NOT_FOUND, "订单不存在", Audience.PARENT)
        // S16：mockPay 可把任意订单置为已支付，无归属校验时知道 orderNo 就能替别人"付款"
        if (order.userId != userId) {
            throw BizException(ParentErr.DEVICE_NOT_OWNED, "订单不属于当前用户", Audience.PARENT)
        }
        return markPaid(orderNo, "MOCK")
    }

    fun orders(userId: String, limit: Int): List<OrderDto> =
        paymentOrderRepository.findByUserIdOrderByCreatedAtDesc(userId, Pageable.ofSize(limit))
            .map { o ->
                val plan = Plan.of(o.plan)
                OrderDto(
                    orderNo = o.orderNo,
                    plan = o.plan,
                    planLabel = plan?.label ?: o.plan,
                    amountFen = o.amountFen,
                    priceText = fenToText(o.amountFen),
                    channel = o.channel,
                    status = o.status,
                    createdAt = o.createdAt,
                    paidAt = o.paidAt
                )
            }

    // ------------------------------------------------------------------ 工具

    private fun newOrderNo(now: Long): String =
        "PG" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
            .format(Instant.ofEpochMilli(now).atZone(SERVER_ZONE_ID)) +
            (1000..9999).random()

    /**
     * 按**自然月**顺延，而不是按 30 天乘。
     * 30×12=360 天会让年卡每年少 5 天，续费几年后家长会发现"明明买的年卡怎么提前到期"。
     */
    private fun plusMonths(ts: Long, months: Int): Long {
        val zone = SERVER_ZONE_ID
        val date: LocalDate = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate().plusMonths(months.toLong())
        // 到期时刻取顺延当日 23:59:59.999，家长体感是"当天还能用"
        return date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
    }

    fun fenToText(fen: Int): String =
        if (fen % 100 == 0) "¥${fen / 100}" else "¥${String.format("%.2f", fen / 100.0)}"

    /** 当前套餐允许绑定的设备总数 */
    fun deviceLimitOf(userId: String): Int =
        subscriptionRepository.findByUserId(userId)?.deviceLimit ?: trialDevices

    companion object {
        /** 后台「系统设置」在 SystemSetting 表中的主键，必须与 AdminService 使用的值一致 */
        private const val SETTINGS_KEY = "admin.settings"
    }
}
