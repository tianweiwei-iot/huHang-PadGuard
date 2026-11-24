package com.padguard.server.service

import com.padguard.server.common.SERVER_ZONE_ID
import com.padguard.server.domain.UsageStartRequest
import com.padguard.server.dto.UsageStartRequestDto
import com.padguard.server.repository.UsageStartRequestRepository
import com.padguard.server.ws.WebSocketPushService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * 家长端「开始计时」工单服务。
 *
 * 每日计时须**孩子确认**后才开始（家长可发起/催办，未确认则不开始）。
 * 本服务管理家长侧的动作与状态：
 * - [request]：家长点「开始计时」→ 幂等落 PENDING 工单 + 下发 START_TIMER_CONFIRM 指令
 *   + WS 推送；重复点击 = 催办（重发指令让孩子端浮层 force 弹出），不重复建单；
 * - [confirmPendingToday]：孩子确认（经 [UsageSessionService.start] 起表后）把当日
 *   PENDING 工单置 CONFIRMED + WS 推送；
 * - [todayRequest]：当日工单状态（家长端轮询会话接口时随 DTO 下发）。
 *
 * ## 幂等与并发
 * 同设备同日只允许一张有效（PENDING/CONFIRMED）工单：查询与插入在同一事务内，
 * 并发双击的 worst case 是两张 PENDING —— 由 [confirmPendingToday] 按"当日全量"收敛，
 * 孩子确认一次全部闭环，不会产生第二份额度（额度由 usage_sessions 唯一行锁定，
 * 与工单数量无关）。
 *
 * ## 跨日
 * 非今日 PENDING 懒作废为 EXPIRED（[UsageStartRequestRepository.expireStale]）：
 * 昨天发起没确认的，今天不能凭旧工单续命。孩子在今天确认走的是今天的会话，
 * 与昨日工单无关。
 */
@Service
class UsageStartRequestService(
    private val repository: UsageStartRequestRepository,
    private val commandService: CommandService,
    private val webSocketPush: WebSocketPushService,
    private val deviceService: DeviceService
) {
    private val log = LoggerFactory.getLogger(UsageStartRequestService::class.java)

    private val zoneId: ZoneId = SERVER_ZONE_ID

    private fun dayKey(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(zoneId).toLocalDate().toString()

    /**
     * 家长发起「开始计时」。
     *
     * - 今日无有效工单 → 落 PENDING + 下发指令（事务提交后）+ WS 推送；
     * - 今日已有 PENDING（重复点击=催办）→ 重发指令 + 重推 WS，不建新单；
     * - 今日已 CONFIRMED → 幂等返回，不再打扰孩子端（家长端按钮本已按会话 active 置灰，
     *   此分支是 API 层面的防御）。
     *
     * 设备离线时同样落单发指令：指令走 MQTT + 孩子端轮询双通道，上线后取走；
     * WS 推送只是加速家长端其它会话的刷新，不影响本接口返回的工单状态。
     */
    @Transactional
    fun request(userId: String, deviceId: String): UsageStartRequestDto {
        val now = System.currentTimeMillis()
        val today = dayKey(now)
        repository.expireStale(deviceId, today)

        val existing = repository.findByDeviceIdAndDayKey(deviceId, today)
            .firstOrNull { it.status == "PENDING" || it.status == "CONFIRMED" }
        if (existing != null && existing.status == "CONFIRMED") {
            return toDto(existing)
        }
        val ticket = existing ?: repository.save(
            UsageStartRequest(
                requestId = "st_" + UUID.randomUUID().toString().replace("-", "").take(20),
                deviceId = deviceId,
                dayKey = today,
                status = "PENDING",
                source = "PARENT",
                createdAt = now
            )
        )
        log.info(
            "usage start requested device={} ticket={} resending={}",
            deviceId, ticket.requestId, existing != null
        )

        // 对外副作用挂到提交之后：事务回滚时不能让孩子端执行一条库里不存在的指令，
        // 也不能让家长端收到幽灵工单推送（与 UsageDelayService.request 同型）。
        afterCommit {
            runCatching {
                commandService.issueCommand(
                    deviceId,
                    CommandType.START_TIMER_CONFIRM,
                    mapOf(CommandKey.REQUEST_ID to ticket.requestId)
                )
            }.onFailure {
                log.warn("start-timer command issue failed device={}: {}", deviceId, it.message)
            }
            // 同 confirmPendingToday：推送随本块在提交后执行，避免回滚后留下"家长已发起计时"的幽灵事件。
            pushStartEvent(ownerId(userId, deviceId), deviceId, "usage.start.requested", ticket)
        }
        return toDto(ticket)
    }

    /**
     * 孩子确认后闭环当日工单（由 [UsageSessionService.start] 在起表事务内调用）。
     *
     * 返回是否确有 PENDING 被确认（调用方据此决定是否推 WS —— 孩子自己确认的
     * 情况下家长端也可能没有 PENDING 工单，此时无事件可推）。
     */
    @Transactional
    fun confirmPendingToday(deviceId: String, today: String, now: Long): Boolean {
        val confirmed = repository.confirmPendingToday(deviceId, today, now)
        if (confirmed <= 0) return false
        log.info("usage start confirmed device={} tickets={}", deviceId, confirmed)
        val ticket = repository.findByDeviceIdAndDayKey(deviceId, today)
            .firstOrNull { it.status == "CONFIRMED" }
        // 本方法被 UsageSessionService.start 在其起表事务内调用：推送若在提交前发出，
        // 外层事务回滚（例如随后起表写失败）时家长端已收到"计时已确认"的幽灵事件。
        // 挂 afterCommit 保证只有真正提交成功才通知；pushStartEvent 自带 runCatching。
        afterCommit {
            pushStartEvent(ownerId(null, deviceId), deviceId, "usage.start.confirmed", ticket)
        }
        return true
    }

    /** 当日工单状态（null = 家长今日未发起）。非事务读：懒作废仅发生在 request/confirm 路径。 */
    fun todayRequest(deviceId: String): UsageStartRequestDto? {
        val today = dayKey(System.currentTimeMillis())
        return repository.findByDeviceIdAndDayKey(deviceId, today)
            .firstOrNull { it.status != "EXPIRED" }
            ?.let { toDto(it) }
    }

    private fun ownerId(callerUserId: String?, deviceId: String): String? =
        callerUserId ?: runCatching { deviceService.getUserId(deviceId) }.getOrNull()

    private fun pushStartEvent(userId: String?, deviceId: String, type: String, ticket: UsageStartRequest?) {
        if (userId == null || ticket == null) return
        runCatching {
            webSocketPush.pushToUser(
                userId,
                mapOf(
                    "type" to type,
                    "deviceId" to deviceId,
                    "requestId" to ticket.requestId,
                    "status" to ticket.status,
                    "createdAt" to ticket.createdAt,
                    "confirmedAt" to ticket.confirmedAt
                )
            )
        }.onFailure { log.warn("push {} failed: {}", type, it.message) }
    }

    private fun toDto(r: UsageStartRequest) = UsageStartRequestDto(
        requestId = r.requestId,
        deviceId = r.deviceId,
        status = r.status,
        createdAt = r.createdAt,
        confirmedAt = r.confirmedAt
    )
}
