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
import com.padguard.server.security.WeChatPayClient
import com.padguard.server.security.WeChatPayVerifier
import com.padguard.server.dto.PayRequest
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** 超时关闭接口：单测直构 BillingService 时可传 [OrderTimeoutCloser.NOOP]。 */
interface OrderCloser {
    fun closeExpired(orderNo: String, now: Long): Boolean
}

/**
 * 订单超时关闭器：把 CLOSED 落库放在 **REQUIRES_NEW 独立事务**里执行。
 *
 * markPaid 的超时分支在落完 CLOSED 后要抛 BizException 提示家长重新下单——
 * 而异常会让当前事务整体回滚。如果 CLOSE 和"抛异常"在同一事务里，
 * CLOSED 状态永远落不了库（死代码），订单永久停在 PENDING，报表口径也不对。
 */
@Service
class OrderTimeoutCloser(private val paymentOrderRepository: PaymentOrderRepository) : OrderCloser {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun closeExpired(orderNo: String, now: Long): Boolean =
        paymentOrderRepository.claimTimeoutClose(orderNo, now) > 0

    companion object {
        val NOOP: OrderCloser = object : OrderCloser {
            override fun closeExpired(orderNo: String, now: Long): Boolean = false
        }
    }
}

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
    private val objectMapper: ObjectMapper,
    // 微信支付客户端/校验器：生产由 Spring 注入；单测手动构造时留空（置灰该渠道），保证测试不依赖微信配置。
    private val weChatPayClient: WeChatPayClient? = null,
    private val weChatVerifier: WeChatPayVerifier? = null,
    private val orderCloser: OrderCloser = OrderTimeoutCloser.NOOP,
    /**
     * 试用订阅插入器（独立事务）。生产由 Spring 注入 [TransactionalTrialInserter]；
     * 单测不传 —— 内存假仓储没有事务语义，传了也验证不了，反而多一层构造噪音。
     * 为 null 时退化为直接 save（与旧行为一致）。
     */
    private val trialInserter: TrialSubscriptionInserter? = null
) {
    private val log = LoggerFactory.getLogger(BillingService::class.java)

    // ------------------------------------------------------------------ 套餐

    /**
     * 套餐单价（分）。
     *
     * **以后台「系统设置 → 套餐定价」写入的值优先，配置文件只作兜底。**
     * 此前这里只读 `@Value` 常量，于是运营在后台改价会看到"保存成功"，
     * 实际下单仍按旧价收款 —— 页面报价与实际扣款不一致，是最直接的资损/客诉来源。
     * 同页 `/v1/admin/settings` 读的是 DB 价、`/v1/admin/billing/plans` 读的是配置价，
     * 三处口径当时互不相同；现在统一收敛到本方法。
     */
    fun amountFenOf(plan: Plan): Int {
        val fromConfig = when (plan) {
            Plan.MONTHLY -> priceMonthly
            Plan.QUARTERLY -> priceQuarterly
            Plan.YEARLY -> priceYearly
            Plan.TRIAL -> 0
            Plan.PERMANENT -> 0
        }
        if (plan == Plan.TRIAL || plan == Plan.PERMANENT) return 0
        val override = runCatching {
            loadSettingsStore(systemSettingRepository, objectMapper).planPrices[plan.key.uppercase()]
        }.getOrNull()
        // 0 / 负数视为"未配置"：宁可回落配置价，也不能出现 0 元套餐被直接下单。
        return if (override != null && override > 0) override else fromConfig
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
        // amountFenOf 会读一次设置（DB 覆盖价），先算一次再复用，
        // 避免同一个套餐为了 priceText 再查一遍库。
        val fen = amountFenOf(p)
        PlanDto(
            key = p.key, label = p.label, months = p.months,
            amountFen = fen, priceText = fenToText(fen),
            deviceLimit = deviceLimitOf(p)
        )
    }

    /** 微信支付是否已按完整配置就绪（开关+商户号+私钥+序列号+APIv3 密钥齐全） */
    private fun weChatReady(): Boolean = weChatPayClient?.isConfigured() ?: false

    /**
     * 收银台可选的支付渠道。
     *
     * 示例支付（mock）开启时全部渠道标记为示例；否则仅"已配置就绪"的真实渠道（当前为微信支付）
     * 显示为可用，其余仍提示"渠道接入中"，避免放出"能下单、没人收款"的口子。
     */
    fun channels(): List<PayChannelDto> = PayChannel.checkoutChannels().map { c ->
        val isWeChat = c == PayChannel.WECHAT
        val available = mockPayEnabled() || (isWeChat && weChatReady())
        PayChannelDto(
            key = c.name,
            label = c.label,
            enabled = available,
            mock = mockPayEnabled(),
            note = when {
                mockPayEnabled() -> "示例支付，不会产生真实扣款"
                isWeChat && weChatReady() -> "微信支付（真实扣款）"
                else -> "渠道接入中，暂不可用"
            }
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

    /**
     * 过期后状态不会自己变，读取时必须刷新，否则家长端会一直显示"生效中"。
     *
     * **不能**用"读出来改 status 再 save"：`statusOf` 是无事务方法，`findByUserId` 返回的是
     * 脱管快照，`save` 走 merge 会整行 UPDATE，把快照里的 `expired_at` 一并写回旧值。
     * 若此时正好有笔续费刚提交（到期日延后、status=ACTIVE），就会被这里打回"过期"，
     * 而下面的早退分支让 EXPIRED 再也不会被刷新 —— 已付费家长被永久锁死，只能人工改库。
     *
     * 改为条件 UPDATE：只动 status/updated_at，且只在"此刻确实仍过期"时命中。
     */
    private fun refreshStatus(sub: Subscription) {
        // 永久会员不受时间约束：状态机永远不进入过期分支
        if (sub.plan == Plan.PERMANENT.key) return
        if (sub.status == SubStatus.EXPIRED.name || sub.status == SubStatus.REVOKED.name) return
        val now = System.currentTimeMillis()
        if (sub.expiredAt >= now) return
        val changed = subscriptionRepository.markExpiredIfStale(sub.userId, SubStatus.EXPIRED.name, now)
        if (changed > 0) {
            // 内存对象同步成落库后的状态，供随后的 toDto 使用（不回写数据库）。
            sub.status = SubStatus.EXPIRED.name
            sub.updatedAt = now
        } else {
            // 影响 0 行说明并发事务已经把它改成可用态（续费刚提交），本地快照已过期：
            // 重读一次，避免本次响应把"刚买完的家长"报成已过期（下一次请求会自愈，但本次界面不该错）。
            subscriptionRepository.findByUserId(sub.userId)?.let {
                sub.plan = it.plan
                sub.status = it.status
                sub.deviceLimit = it.deviceLimit
                sub.expiredAt = it.expiredAt
                sub.updatedAt = it.updatedAt
                sub.autoRenew = it.autoRenew
            }
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
     * 示例支付开关：**部署配置为总闸，后台 DB 只能关不能开**（`config && dbValue`）。
     *
     * 早期是"DB 优先、配置兜底"，看起来 convenient，实际是资金红线：
     * [com.padguard.server.config.ProdConfigAssertion] 只在启动期检查配置，
     * 运行中任何持有 `settings:write` 的会话（或直接写库）都能把 DB 里的 `mockPay`
     * 置 true，从而在生产跳过验签、白嫖订阅。现在与 [ConfigurablePaymentVerifier]
     * 统一为同一口径：生产配置恒 false，DB 无法反向打开；联调档位配置 true 时
     * DB 仍可显式关闭，未保存字段时沿用配置值。
     */
    private fun mockPayFlagFromDb(): Boolean? =
        runCatching {
            systemSettingRepository.findById(SETTINGS_KEY).orElse(null)?.valueJson
                ?.let { objectMapper.readTree(it).get("mockPay")?.asBoolean() }
        }.getOrNull()

    private fun mockPayEnabled(): Boolean {
        if (!configMockPayEnabled) return false
        return mockPayFlagFromDb() ?: true
    }

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
        return try {
            // 走独立事务插入：调用方可能已在事务中（markPaid / extend / manualGrant），
            // 就地 save 的话唯一冲突要到外层提交才抛，catch 永远不执行、且外层事务被毒化。
            trialInserter?.insert(sub) ?: subscriptionRepository.saveAndFlush(sub)
            log.info("trial subscription created: userId={}, days={}", userId, days)
            sub
        } catch (e: org.springframework.dao.DataIntegrityViolationException) {
            // 输家回退读取赢家刚插入的订阅（唯一约束兜底语义，见上方注释）；
            // warn 记录并发创建频率，频繁出现说明注册链路存在重复触发。
            log.warn("trial subscription insert raced, falling back to existing row: userId={}", userId)
            subscriptionRepository.findByUserId(userId)
                ?: throw IllegalStateException("subscription insert raced but row not visible: userId=$userId", e)
        }
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
     *
     * ## 设备数上限的原子性（R-P2-11，TOCTOU 修复）
     * `count → save` 原来是两步 check-then-act：同一家长多台设备并发绑定时
     * （[com.padguard.server.service.DeviceBindLocks] 按**设备指纹**加锁，
     * 锁得住"同一台平板重复建台账"，锁不住"不同设备绑同一家长"），
     * 两个事务都 count 到 limit-1 → 都通过 → 各 save 一台，越上限一台（资损面）。
     * 现在对 subscription 行加悲观锁（SELECT ... FOR UPDATE，与续费 extend 的
     * [SubscriptionRepository.findByUserIdForUpdate] 同口径）：同一家长的并发绑定
     * 在此串行化，后到的事务等前一个提交后重数，超限即拒。
     * `@Transactional`（REQUIRED）确保锁所在事务与调用方的绑定事务**是同一个**，
     * 锁活到绑定 save + 提交；全部调用路径（bindChild / bindChildByAccount /
     * bindLanDevice / importDevices 的 txTemplate 行事务）均在事务内调用本方法。
     */
    @Transactional
    fun ensureCanBind(userId: String) {
        ensureActive(userId)
        // ensureActive 已确保订阅存在（缺失会 createTrial），这里的行锁查询理论不会为空；
        // 但仍用显式业务异常兜底，避免并发下订阅被删时裸 NPE 冒泡成 500。
        val sub = subscriptionRepository.findByUserIdForUpdate(userId)
            ?: throw BizException(ParentErr.SUBSCRIPTION_REQUIRED, "订阅记录不可用，请重新登录后重试", Audience.PARENT)
        val used = deviceRepository.countByUserId(userId).toInt()
        if (used >= sub.deviceLimit) {
            throw BizException(
                ParentErr.DEVICE_LIMIT_EXCEEDED,
                "当前套餐最多绑定 ${sub.deviceLimit} 台设备，升级套餐后可绑定更多",
                Audience.PARENT
            )
        }
    }

    @Transactional
    fun ensureCanBindForChild(userId: String) {
        ensureActiveForChild(userId)
        // 同 ensureCanBind：显式业务异常兜底，杜绝裸 NPE→500。
        val sub = subscriptionRepository.findByUserIdForUpdate(userId)
            ?: throw BizException(ChildErr.SUBSCRIPTION_REQUIRED, "订阅记录不可用，请让家长重新登录后重试", Audience.CHILD)
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

    /**
     * 下单。
     *
     * **刻意不加 `@Transactional`**：真实微信渠道要在建单后同步调微信下单接口（HTTP）。
     * 把这段放进事务里有两个后果：
     * 1. 持着数据库连接等外网（微信侧挂起时连接被长期占用，连接池被拖垮）；
     * 2. 失败方向错误 —— 微信已建预支付单、本地事务随后回滚，微信侧留下一张
     *    用 `out_trade_no` 永远对不上账的孤儿单。
     *
     * 现在拆成"提交建单 → 事务外调渠道"：建单用 `saveAndFlush`，它自带的事务在返回前提交，
     * 撞号异常也因此在**本方法内**可捕获（旧写法在 @Transactional 里 flush，异常抛出时
     * 事务已 rollback-only，只能翻译成提示，无法再补救）。
     * 渠道下单失败则立即把这张单收口成 CLOSED（用户拿不到 payParams，它永远付不了）。
     */
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
        // 客服代开通（MANUAL）永远可用；收银台渠道只有在开了示例支付、或该真实渠道已配置就绪时才可下单 ——
        // 真实渠道未接入前就在生产放开它们，等于开一条"能下单、没人收款、也没人开通"的口子，
        // 家长付款后订阅不变，是最难解释的一类投诉。
        val weChatAvailable = channel == PayChannel.WECHAT && weChatReady()
        if (channel != PayChannel.MANUAL && !mockPayEnabled() && !weChatAvailable) {
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
        log.info("order created: no={}, userId={}, plan={}, amount={}fen", order.orderNo, userId, plan.key, order.amountFen)
        // 第八轮 P2-5：newOrderNo 先查后写仍有微窗口（两事务同秒取到同一候选号且互相未提交），
        // 撞 unique 约束会抛 DataIntegrityViolation → 家长看到 500。
        // 这里用 saveAndFlush 让 INSERT 立刻打出：方法本身没有事务，异常在方法体内抛出，
        // 可以直接翻译成明确的业务提示（家长重新点一次即成功），而不是生硬的 500。
        try {
            paymentOrderRepository.saveAndFlush(order)
        } catch (_: org.springframework.dao.DataIntegrityViolationException) {
            log.warn("order create raced on orderNo: userId={}, regenerating hint", userId)
            throw BizException(ParentErr.PARAM_ERROR, "下单冲突，请重新提交", Audience.PARENT)
        }

        // 真实微信支付：向微信下单并取回调所需的 payParams；其余渠道（示例/代开通）无 payParams。
        var payParams: Map<String, Any?>? = null
        if (weChatAvailable && !mockPayEnabled()) {
            payParams = try {
                weChatPayClient?.createAppOrder(order.orderNo, order.amountFen, "护航成长-${plan.label}")
            } catch (e: Exception) {
                log.warn("微信下单异常，订单转入关闭: no={}, err={}", order.orderNo, e.message)
                null
            }
            if (payParams == null) {
                // 订单已落库，但用户拿不到调起支付的参数 —— 这张单永远付不了，立即收口，
                // 否则后台"待支付"列表会堆满永远不会成交的单据，运营无法分辨真伪。
                val closed = runCatching {
                    paymentOrderRepository.closeIfPending(
                        order.orderNo, OrderStatus.CLOSED.name, OrderStatus.PENDING.name
                    )
                }.getOrDefault(0)
                log.warn("微信下单失败，订单已关闭: no={}, closed={}", order.orderNo, closed)
                throw BizException(ParentErr.PARAM_ERROR, "微信下单失败，请稍后重试", Audience.PARENT)
            }
        }
        return CreateOrderResponse(
            orderNo = order.orderNo,
            amountFen = order.amountFen,
            channel = order.channel,
            payDeadline = order.payDeadline,
            payParams = payParams
        )
    }

    /**
     * 支付成功 → 开通/续费。
     *
     * 幂等（第八轮 P1-B 加固）：回调可能重复投递，且可能**并发**到达。
     * 此前是"读状态→内存判断→写回"的 check-then-act，两个并发事务都能通过检查，
     * 订阅被 extend 两次（资损：多延长一个月）。
     * 现改为 [PaymentOrderRepository.claimPendingOrder] 条件 UPDATE 原子抢占：
     * 行锁保证同一订单只有一个事务能把 PENDING 翻转为 PAID，命中 0 行即视为重复回调幂等返回。
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
            // 必须在独立事务里落 CLOSED：本方法随后抛 BizException 会让当前事务整体回滚，
            // 若就地 save(CLOSED)，该状态永远落不了库（死代码），订单永久停在 PENDING。
            orderCloser.closeExpired(orderNo, now)
            throw BizException(ParentErr.ORDER_STATE_INVALID, "订单已超时关闭，请重新下单", Audience.PARENT)
        }

        // 原子抢占：WHERE status='PENDING' 保证并发重复回调只有一个事务入账
        val claimed = paymentOrderRepository.claimPendingOrder(
            orderNo = orderNo,
            paidStatus = OrderStatus.PAID.name,
            pendingStatus = OrderStatus.PENDING.name,
            paidAt = now,
            txId = transactionId
        )
        if (claimed == 0) {
            log.info("order claimed by concurrent callback first: no={} (duplicate ignored)", orderNo)
            return statusOf(order.userId)
        }

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

    /**
     * 微信支付原生回调入口（[BillingController.notify] 识别到 Wechatpay-Signature 头后调用）。
     *
     * 微信回调是加密报文，不能直接按 [PayRequest] 解析，因此走此专用分支：
     * 1. [WeChatPayVerifier.verifyAndParse] 完成验签 + 解密 + 取单号（内部已校验 trade_state=SUCCESS）；
     * 2. 用 out_trade_no 反查本系统订单，校验其渠道确为 WECHAT（防止串用）；
     * 3. 入账（幂等，markPaid 内部处理重复投递）。
     */
    @Transactional
    fun handleWeChatNotify(rawBody: String, headers: Map<String, String>): SubscriptionDto {
        val result = weChatVerifier?.verifyAndParse(rawBody, headers)
            ?: throw BizException(ParentErr.ORDER_STATE_INVALID, "微信支付校验未通过", Audience.PARENT)

        val order = paymentOrderRepository.findByOrderNo(result.outTradeNo)
            ?: throw BizException(ParentErr.ORDER_NOT_FOUND, "订单不存在", Audience.PARENT)
        if (order.channel != PayChannel.WECHAT.name) {
            log.warn("微信回调单号 {} 对应订单渠道非 WECHAT：{}", result.outTradeNo, order.channel)
            throw BizException(ParentErr.FORBIDDEN, "订单渠道不匹配", Audience.PARENT)
        }
        // 金额比对（微信官方接入规范第 4 步：回调金额必须与商户侧订单一致才可入账）。
        // 验签已保证报文来自微信，这里防的是错账类运营事故：
        // 商户号被多套系统共用 / 测试与生产混用商户号时，另一套系统的回调单号
        // 碰巧命中本系统订单 —— 验签照样通过（同一张平台证书），但金额对不上，
        // 之前会照单全收地开通订阅。金额不符必须拒绝并留痕，订单保持 PENDING 等人工对账。
        if (result.totalFen != order.amountFen.toLong()) {
            log.error(
                "微信回调金额与订单不符，拒绝入账: no={}, callback={}fen, order={}fen",
                result.outTradeNo, result.totalFen, order.amountFen
            )
            throw BizException(ParentErr.ORDER_STATE_INVALID, "支付金额校验未通过", Audience.PARENT)
        }
        return markPaid(result.outTradeNo, result.transactionId)
    }

    /** 延长订阅：从"当前有效期"或"现在"中较晚者起算，避免过期后续费被倒扣时长 */
    @Transactional
    fun extend(userId: String, months: Int, planKey: String) {
        val now = System.currentTimeMillis()
        // 悲观行锁读：两笔订单并发入账时（家长重复拉起支付各付一笔），都走同一行，
        // 行锁把续费串行化——第二个事务读到的是第一个事务提交后的新 expiredAt，
        // 不会出现"后提交者覆盖前者、付两笔钱只延长一个月"的丢更新。
        val sub = subscriptionRepository.findByUserIdForUpdate(userId)
            ?: run {
                // 无行可锁（首单）：createTrial 在**独立事务**里 INSERT 并提交，
                // user_id 唯一约束保证只有一个事务能插入成功，输家回退读取赢家那一行。
                //
                // 旧实现用 `synchronized(this)` 做"双重检查"：它在代码块结束就释放，
                // 而此时外层事务尚未提交 —— 第二个线程进来照样看不到这一行、照样去插，
                // 进程内锁在这里挡不住任何东西；多实例部署更是完全无效。
                // 真正起作用的只有数据库唯一约束。
                createTrial(userId)
                // 重新在**本事务**内加行锁读：拿到的才是受管实体。
                // 若直接用 createTrial 的返回值，那是一个已脱管对象（内层事务已关闭），
                // 下面 save 会走 merge 整行 UPDATE。
                subscriptionRepository.findByUserIdForUpdate(userId)
                    ?: throw IllegalStateException("trial created but row not visible: userId=$userId")
            }
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

    /**
     * 模拟支付：仅开发环境开启，生产必须关闭，否则等于绕过收费（S16 补归属校验）。
     *
     * **必须有 `@Transactional`**：本方法内部调 [markPaid]，而 markPaid 是同类调用，
     * 不经过 Spring 代理、它自己的 `@Transactional` 不会生效。没有事务时
     * `claimPendingOrder`（`@Modifying`，必须有事务）会直接抛 TransactionRequiredException → 500；
     * 即使侥幸落到各自独立的事务里，"置 PAID"与"延长订阅"也不再原子 ——
     * 订单已 PAID 而 extend 失败时，后续回调在 markPaid 开头就被判为"已结算"直接返回，
     * 这笔钱对应的权益**永远不会被开通**。事务由本方法开启后，整段才是原子的。
     */
    @Transactional
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
            .map { toOrderDto(it) }

    /**
     * 单订单查询（家长端支付后轮询用）：调起微信回来后，客户端并不知道服务端是否
     * 已收到回调入账，需要按订单号轮询直至 PAID / CLOSED。
     * 属主校验：只能查自己的订单，拿别人的订单号探测支付状态属于横向越权。
     */
    fun orderStatusOf(userId: String, orderNo: String): OrderDto {
        val order = paymentOrderRepository.findByOrderNo(orderNo)
            ?: throw BizException(ParentErr.ORDER_NOT_FOUND, "订单不存在", Audience.PARENT)
        if (order.userId != userId) {
            throw BizException(ParentErr.DEVICE_NOT_OWNED, "订单不属于当前用户", Audience.PARENT)
        }
        return toOrderDto(order)
    }

    private fun toOrderDto(o: PaymentOrder): OrderDto {
        val plan = Plan.of(o.plan)
        return OrderDto(
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

    /**
     * 微信退款结果回调入口（[BillingController.refundNotify] 调用，公开端点、验签为唯一信任依据）。
     *
     * 责任边界：管理员在后台发起退款申请时订单已标记 REFUNDED（申请受理即入账口径），
     * 回调在这里做**二次确认**：
     * - 订单已是 REFUNDED → 幂等成功（最常见路径），**不再回收权益**（回收已在首次退款时完成；
     *   重投回调若再次回收，会误伤家长此后新购恢复的订阅）；
     * - 订单仍是 PAID（回调先于管理端事务提交等竞态）→ 原子条件 UPDATE 补标 REFUNDED；
     * - 退款结果非 SUCCESS → 由 [WeChatPayVerifier.verifyRefundAndParse] 拒绝并留痕，不推进状态。
     */
    @Transactional
    fun handleWeChatRefundNotify(rawBody: String, headers: Map<String, String>): Map<String, Any> {
        val result = weChatVerifier?.verifyRefundAndParse(rawBody, headers)
            ?: throw BizException(ParentErr.ORDER_STATE_INVALID, "微信退款校验未通过", Audience.PARENT)

        val order = paymentOrderRepository.findByOrderNo(result.outTradeNo)
            ?: throw BizException(ParentErr.ORDER_NOT_FOUND, "订单不存在", Audience.PARENT)
        if (order.channel != PayChannel.WECHAT.name) {
            log.warn("微信退款回调单号 {} 对应订单渠道非 WECHAT：{}", result.outTradeNo, order.channel)
            throw BizException(ParentErr.FORBIDDEN, "订单渠道不匹配", Audience.PARENT)
        }
        val marked = paymentOrderRepository.markRefundedIfPaid(
            orderNo = order.orderNo,
            refundedStatus = OrderStatus.REFUNDED.name,
            paidStatus = OrderStatus.PAID.name
        )
        if (marked == 0) {
            log.info(
                "refund notify confirmed (already settled): orderNo={}, refundId={}, status={}",
                order.orderNo, result.refundId, order.status
            )
            // R42：已经 REFUNDED 的重复/延迟回调只做幂等确认，**绝不能再次回收权益**。
            // 管理员发起退款时已在 AdminBillingService.refund 里回收过一次；若此后家长
            // 新购订单恢复 ACTIVE，微信重投的旧回调会在这里把新订阅重新打回 REVOKED，
            // 造成「付了新单却停服」。回调先到（PAID -> REFUNDED）的那一次才需要回收。
            return mapOf("ok" to true)
        }
        log.info(
            "refund notify settled: orderNo={}, refundId={} (PAID -> REFUNDED)",
            order.orderNo, result.refundId
        )
        // 「退款即回收」：仅本次真正完成 PAID -> REFUNDED 时才回收该家长的会员权益。
        revokeRightsOnRefund(order.userId)
        return mapOf("ok" to true)
    }

    /**
     * 退款后回收会员权益（「退款即回收」策略）。
     *
     * 把该家长的订阅置为 [SubStatus.REVOKED] 并清掉到期时间，使其立即丧失会员权益
     * （[SubStatus.REVOKED] 在 `ensureActive` / `statusOf` 中按「无权益」处理，与 EXPIRED 同效）。
     * 仅对当前仍有效（ACTIVE / TRIAL）的订阅生效；已过期 / 已回收的不动，保证幂等。
     * 退款确认（微信回调二次确认 或 后台手动退款受理）后调用，钱退即停服，避免「既退钱又白用」。
     */
    @Transactional
    fun revokeRightsOnRefund(userId: String) {
        val sub = subscriptionRepository.findByUserId(userId) ?: return
        if (sub.status != SubStatus.ACTIVE.name && sub.status != SubStatus.TRIAL.name) return
        sub.status = SubStatus.REVOKED.name
        sub.expiredAt = System.currentTimeMillis()
        sub.updatedAt = System.currentTimeMillis()
        subscriptionRepository.save(sub)
        log.info("subscription rights revoked on refund: userId={}", userId)
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 生成商户订单号，并在库内确认唯一后才返回。
     *
     * 号段为 `PG + yyyyMMddHHmmss + 4 位随机`，同一秒内的随机空间只有 9000。
     * 而 `order_no` 列带 `unique = true` —— 撞号时落库会抛
     * `DataIntegrityViolationException`，家长看到的是一个没头没尾的 500，
     * 钱没付成也不知道为什么（促销期两位家长同秒下单并非不可能）。
     * 这里先查后写并有限重试，把"撞号"从用户可见的失败降为内部自愈。
     */
    private fun newOrderNo(now: Long): String {
        val prefix = "PG" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
            .format(Instant.ofEpochMilli(now).atZone(SERVER_ZONE_ID))
        repeat(ORDER_NO_MAX_ATTEMPTS) { attempt ->
            val candidate = prefix + (1000..9999).random()
            if (paymentOrderRepository.findByOrderNo(candidate) == null) return candidate
            log.warn("order no collision detected (attempt {}): {}", attempt + 1, candidate)
        }
        // 极端情况（9000 个号全被占满）才走到这里：用 UUID 兜底，绝不返回重复号
        return prefix + UUID.randomUUID().toString().replace("-", "").take(12)
    }

    /**
     * 按**自然月**顺延，而不是按 30 天乘。
     * 30×12=360 天会让年卡每年少 5 天，续费几年后家长会发现"明明买的年卡怎么提前到期"。
     *
     * 月末锚定：LocalDate.plusMonths 会把 1/31 夹到 2/28；若下次续费再以 2/28 为基准，
     * 就得到 3/28 —— 每次续费都少几天，月底到期的月卡/年卡用户几年后会发现到期日
     * 从 31 号悄悄漂到 28 号。因此基准日若是所在月最后一天，目标日也取目标月最后一天，
     * 使 1/31→2/28→3/31 稳定停在月末。普通日期（如 15 号）不受影响。
     */
    private fun plusMonths(ts: Long, months: Int): Long {
        val zone = SERVER_ZONE_ID
        val base: LocalDate = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()
        var date: LocalDate = base.plusMonths(months.toLong())
        if (base.dayOfMonth == base.lengthOfMonth()) {
            // 基准是月末 → 目标也锚到月末，避免 clamp 后逐月收缩
            date = date.withDayOfMonth(date.lengthOfMonth())
        }
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

        /** 订单号撞号时的最大重试次数（见 [newOrderNo]） */
        private const val ORDER_NO_MAX_ATTEMPTS = 8
    }
}
