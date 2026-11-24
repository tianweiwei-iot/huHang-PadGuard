package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.UsageDelayTicket
import com.padguard.server.domain.UsageSession
import com.padguard.server.dto.UsageDelayApproveRequest
import com.padguard.server.dto.UsageDelayRejectRequest
import com.padguard.server.dto.UsageDelayTicketDto
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.UsageDelayRepository
import com.padguard.server.repository.UsageSessionRepository
import com.padguard.server.ws.WebSocketPushService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 延时审批服务（根因修复的核心）。
 *
 * ## 修复前（缺陷）
 * [com.padguard.server.service.UsageSessionService.extend] 在孩子端"申请"这一步就直接累加
 * `extendedMinutes` 并立即生效，家长端既无审批入口、也无法同步该延长 —— 即"孩子自己批准自己"。
 *
 * ## 修复后
 * - `request`：仅落 PENDING 工单，**不**改动额度；同时实时通知家长端（WebSocket）。
 * - `approve`：家长批准后才累加 `extendedMinutes`（真正延长），并通知家长端刷新。
 * - `reject`：驳回工单并给孩子端下发"申请未通过"（SHOW_MESSAGE）。
 * - `dismiss`：归档但不下发、非终态，工单仍可被批准（与 [UnlockService] 语义一致）。
 *
 * 单日累计上限在 [request] 处校验，且把"已批准 + 待审批"一并计入，
 * 防止孩子反复提交绕过上限。
 */
@Service
class UsageDelayService(
    private val deviceRepository: DeviceRepository,
    private val usageDelayRepository: UsageDelayRepository,
    private val usageSessionRepository: UsageSessionRepository,
    private val commandService: CommandService,
    private val webSocketPush: WebSocketPushService,
    private val deviceService: DeviceService,
    private val auditService: AuditService,
    private val expirer: UsageDelayExpirer
) {
    private val log = LoggerFactory.getLogger(UsageDelayService::class.java)

    /**
     * 孩子端发起延时申请：仅创建 PENDING 工单，不立即延长。
     *
     * 校验：今日计时必须已开始；档位须落 [UsageSession.DELAY_OPTIONS]；
     * 同一设备不允许存在未结（PENDING）工单，避免重复提交；
     * 已批准 + 待审批 + 本次申请不得超过 [UsageSession.MAX_DAILY_EXTEND_MINUTES]。
     *
     * ## 并发闸门（第 5 批）
     * 「查 PENDING → 判空 → 插工单」原本是 check-then-act：两个并发事务各读不到对方
     * 未提交的 PENDING 行，就会各插一张工单，家长连点同意即变成两笔可批准时长。
     * 根治需要 `(device_id, status)` 部分唯一索引（改表，未授权），这里改为在**任何
     * PENDING 查询之前**先对 `usage_sessions` 行加 `PESSIMISTIC_WRITE` 行锁：同一设备的
     * 申请被数据库排成一列，锁随本方法的事务提交释放，后到者必然看到先到者已提交的工单。
     * 锁在数据库里，多副本同样有效；也不用 JVM 本地锁——那会在「锁内、事务提交在外」
     * 的窗口里失效，且多副本根本不共享。
     */
    @Transactional
    fun request(deviceId: String, minutes: Int): UsageDelayTicketDto {
        val now = System.currentTimeMillis()
        val today = dayKey(now)
        val session = usageSessionRepository.findByDeviceIdForUpdate(deviceId)
        if (session == null || session.startEpoch == null || session.dayKey != today) {
            throw BizException(ParentErr.PARAM_ERROR, "今日计时尚未开始，无法申请延时")
        }
        if (minutes !in UsageSession.DELAY_OPTIONS) {
            throw BizException(ParentErr.PARAM_ERROR, "非法的延时档位：$minutes 分钟")
        }
        // 一次查询同时用于「已有待审」与累计上限，不再重复查一遍库。
        val pendingTickets = usageDelayRepository.findByDeviceIdAndStatus(deviceId, "PENDING")
        if (pendingTickets.isNotEmpty()) {
            throw BizException(ParentErr.PARAM_ERROR, "已有待审核的延时申请，请等待家长处理")
        }
        // 单日累计上限 = 已批准（已计入 session.extendedMinutes）+ 待审批 + 本次申请。
        // 上面已保证无 PENDING，pending 实际恒为 0；保留求和是让上限口径显式可见，
        // 且将来若放开"多条待审"也无需再改这里。硬防线仍在 approve 的条件自增 CAS。
        val pending = pendingTickets.sumOf { it.minutes }
        val approved = session.extendedMinutes
        if (approved + pending + minutes > UsageSession.MAX_DAILY_EXTEND_MINUTES) {
            throw BizException(
                ParentErr.PARAM_ERROR,
                "今日延时已达上限 ${UsageSession.MAX_DAILY_EXTEND_MINUTES} 分钟"
            )
        }
        val userId = deviceService.getUserId(deviceId)
        val ticket = usageDelayRepository.save(
            UsageDelayTicket(
                id = "dl_" + UUID.randomUUID().toString().replace("-", "").take(20),
                deviceId = deviceId,
                userId = userId,
                minutes = minutes,
                status = "PENDING",
                createdAt = now
            )
        )
        auditService.record(userId, deviceId, AuditAction.USAGE_DELAY_REQUEST, targetType = "ticket", targetId = ticket.id,
            detail = "minutes=$minutes")
        log.info("usage delay requested device={} minutes={} ticket={}", deviceId, minutes, ticket.id)

        // 对外副作用挂到提交之后：本方法仍在 @Transactional 内，事务回滚时
        // 不能让家长端收到一条数据库里并不存在的待审申请（见 EnrollmentService 同型说明）。
        userId?.let { ownerId ->
            afterCommit {
                webSocketPush.pushToUser(
                    ownerId,
                    mapOf(
                        "type" to "usage.delay.requested",
                        "deviceId" to deviceId,
                        "ticketId" to ticket.id,
                        "minutes" to minutes,
                        "createdAt" to now
                    )
                )
            }
        }
        return toDto(ticket)
    }

    /** 家长同意：真正累加额度并通知两端刷新 */
    @Transactional
    fun approve(userId: String, deviceId: String, ticketId: String, req: UsageDelayApproveRequest) {
        val t = ownedOpen(userId, deviceId, ticketId)
        val now = System.currentTimeMillis()
        val today = dayKey(now)
        // 跨日防线（按**工单创建日**判，不能按会话日判）：
        // 早期实现只看 `session.dayKey != today`，本意是"昨天的工单批到今天的会话上没意义"。
        // 但只要孩子今天已经起过表（零点自动开始、或早上开过机），session.dayKey 就等于 today，
        // 于是**昨天没处理的工单今天照样能批**，分钟直接加进今天的额度 —— 等于跨日续命。
        // 判据必须回到工单本身：非当日提交的申请一律过期作废，并如实告知家长。
        val ticketDay = dayKey(t.createdAt)
        val session = usageSessionRepository.findByDeviceId(deviceId)
        if (ticketDay != today) {
            // 独立事务作废：紧随其后的 BizException 会回滚 approve 外层事务，
            // 若在这里直接 claimReject，作废会被一起撤销、工单永远留在 PENDING。
            expirer.expire(ticketId, now)
            log.info(
                "usage delay expired (ticket day {} != today {}): device={} ticket={}",
                ticketDay, today, deviceId, ticketId
            )
            throw BizException(ParentErr.PARAM_ERROR, "该延时申请已过期（非今日申请），请让孩子重新提交", Audience.PARENT)
        }
        if (session == null || session.dayKey != today) {
            expirer.expire(ticketId, now)
            log.info("usage delay expired (stale session dayKey): device={} ticket={}", deviceId, ticketId)
            throw BizException(ParentErr.PARAM_ERROR, "该延时申请已过期（非今日申请），请让孩子重新提交", Audience.PARENT)
        }
        // 先抢占工单：并发/重试的第二次请求影响 0 行，直接退出。
        // 不能只靠 ownedOpen 的检查 —— 那是 check-then-act，两个请求都会看到 PENDING，
        // 于是额度被加两次（额度直接决定锁机时间），而工单只剩一条 APPROVED，事后无从追查。
        val claimed = usageDelayRepository.claimApprove(ticketId, now)
        if (claimed == 0) {
            throw BizException(ParentErr.PARAM_ERROR, "该延时申请已被处理，请刷新后查看", Audience.PARENT)
        }
        // 额度用数据库条件自增而不是"读出来 +n 再 save"：后者在并发续批时会丢更新。
        // 上限也一并作为**写条件**：`request` 里的"已批准 + 待审批 + 本次"是读-判-写，
        // 两条工单同时提交时都能通过检查（各读到 0 已批准），家长连点两次同意就能把
        // 120 分钟的单日上限顶穿 —— 孩子靠连点自助延时，正是这条上限要防的事。
        // 影响 0 行 = 已到顶：抛异常让整个事务回滚，工单退回 PENDING，家长看到明确提示。
        val added = usageSessionRepository.addExtendedMinutes(
            deviceId, t.minutes, now, UsageSession.MAX_DAILY_EXTEND_MINUTES
        )
        if (added == 0) {
            log.warn(
                "usage delay rejected at cap: device={} ticket={} +{}min (max {})",
                deviceId, ticketId, t.minutes, UsageSession.MAX_DAILY_EXTEND_MINUTES
            )
            throw BizException(
                ParentErr.PARAM_ERROR,
                "今日延时已达上限 ${UsageSession.MAX_DAILY_EXTEND_MINUTES} 分钟，本次批准未生效",
                Audience.PARENT
            )
        }
        auditService.record(userId, deviceId, AuditAction.USAGE_DELAY_APPROVE, targetType = "ticket", targetId = ticketId,
            detail = "minutes=${t.minutes}")
        log.info("usage delay approved device={} ticket={} +{}min", deviceId, ticketId, t.minutes)

        afterCommit {
            webSocketPush.pushToUser(
                userId,
                mapOf(
                    "type" to "usage.delay.updated",
                    "deviceId" to deviceId,
                    "ticketId" to ticketId,
                    "status" to "APPROVED"
                )
            )
        }
    }

    /** 家长拒绝：驳回工单并给孩子端下发"申请未通过" */
    @Transactional
    fun reject(userId: String, deviceId: String, ticketId: String, req: UsageDelayRejectRequest) {
        val t = ownedOpen(userId, deviceId, ticketId)
        // 原子抢占（同 approve）：并发窗口内 reject 若走"读实体→改→save"，
        // 可把刚被 claimApprove 的 APPROVED 覆盖回 REJECTED，家长再点同意就双倍加额度。
        val claimed = usageDelayRepository.claimReject(ticketId, System.currentTimeMillis())
        if (claimed == 0) {
            throw BizException(ParentErr.PARAM_ERROR, "该延时申请已被处理，请刷新后查看", Audience.PARENT)
        }
        auditService.record(userId, deviceId, AuditAction.USAGE_DELAY_REJECT, targetType = "ticket", targetId = ticketId,
            detail = "reason=${req.reason ?: "申请未通过"}")
        commandService.issueCommand(
            deviceId, CommandType.SHOW_MESSAGE,
            mapOf(
                CommandKey.CONTENT to (req.reason ?: "延时申请未通过"),
                CommandKey.REQUEST_ID to t.id
            ),
            priority = "NORMAL"
        )
        log.info("usage delay rejected device={} ticket={}", deviceId, ticketId)

        afterCommit {
            webSocketPush.pushToUser(
                userId,
                mapOf(
                    "type" to "usage.delay.updated",
                    "deviceId" to deviceId,
                    "ticketId" to ticketId,
                    "status" to "REJECTED"
                )
            )
        }
    }

    /**
     * 家长忽略：归档弹窗、不下发任何消息、工单仍可被批准（与 [UnlockService.dismiss] 同语义）。
     */
    @Transactional
    fun dismiss(userId: String, deviceId: String, ticketId: String) {
        ownedOpen(userId, deviceId, ticketId)
        val claimed = usageDelayRepository.claimDismiss(ticketId, System.currentTimeMillis())
        if (claimed == 0) {
            throw BizException(ParentErr.PARAM_ERROR, "该延时申请已被处理，请刷新后查看", Audience.PARENT)
        }
        auditService.record(userId, deviceId, AuditAction.USAGE_DELAY_DISMISS, targetType = "ticket", targetId = ticketId)
        log.info("usage delay dismissed device={} ticket={}", deviceId, ticketId)

        afterCommit {
            webSocketPush.pushToUser(
                userId,
                mapOf(
                    "type" to "usage.delay.updated",
                    "deviceId" to deviceId,
                    "ticketId" to ticketId,
                    "status" to "IGNORED"
                )
            )
        }
    }

    /** 列出该设备全部工单（含已处理），供家长端消息中心翻看历史 */
    fun listTickets(userId: String, deviceId: String): List<UsageDelayTicketDto> {
        // R40 IDOR 修复：与同构的 UnlockService.listTickets 对齐——必须先校验设备归属，
        // 不能只靠下方 `.filter { it.userId == userId }` 兜底返回 200 空列表。
        // 否则任意登录家长可拿他人 deviceId 探测设备是否存在（IDOR 探测面），
        // 且与全系统"跨用户访问他人设备一律 403"的行为不一致。
        // 边界测试（ParentEndpointOwnershipBoundaryTest）实测：修复前跨用户返回 200。
        requireOwned(userId, deviceId)
        return listOf("PENDING", "APPROVED", "REJECTED", "IGNORED")
            .flatMap { usageDelayRepository.findByDeviceIdAndStatus(deviceId, it) }
            .filter { it.userId == userId }
            .sortedByDescending { it.createdAt }
            .map { toDto(it) }
    }

    /**
     * 设备归属校验（与 [DeviceOwnershipGuard.requireOwned] 同语义）。
     *
     * 本类有 4 处单测直接构造，故不注入新组件，复用已注入的 [deviceRepository]；
     * 逻辑与 [ownedOpen] 中的校验完全同构，抽出来消除重复、避免再次漏抄。
     */
    private fun requireOwned(userId: String, deviceId: String) {
        val dev = deviceRepository.findById(deviceId).orElse(null)
            ?: throw BizException(ParentErr.DEVICE_NOT_FOUND, "设备不存在", Audience.PARENT)
        if (dev.userId != userId) {
            throw BizException(ParentErr.DEVICE_NOT_OWNED, "设备不属于当前用户", Audience.PARENT)
        }
    }

    /** 当前等待家长审批的延时分钟数（PENDING 工单之和），供会话 DTO 透传给两端 */
    fun pendingDelayMinutes(deviceId: String): Int =
        usageDelayRepository.findByDeviceIdAndStatus(deviceId, "PENDING").sumOf { it.minutes }

    /**
     * 取出"仍可处理"的工单：PENDING 或 IGNORED（忽略非终态，必须和 PENDING 同等对待）。
     */
    private fun ownedOpen(userId: String, deviceId: String, ticketId: String): UsageDelayTicket {
        requireOwned(userId, deviceId)
        val t = usageDelayRepository.findByIdAndDeviceId(ticketId, deviceId)
            ?: throw BizException(ParentErr.PARAM_ERROR, "工单不存在", Audience.PARENT)
        if (t.status != "PENDING" && t.status != "IGNORED") {
            throw BizException(ParentErr.PARAM_ERROR, "工单已处理（已同意或已拒绝）", Audience.PARENT)
        }
        return t
    }

    private fun dayKey(epochMillis: Long): String =
        java.time.Instant.ofEpochMilli(epochMillis)
            .atZone(com.padguard.server.common.SERVER_ZONE_ID).toLocalDate().toString()

    private fun toDto(t: UsageDelayTicket) = UsageDelayTicketDto(
        id = t.id, deviceId = t.deviceId, minutes = t.minutes, reason = t.reason,
        status = t.status, createdAt = t.createdAt, resolvedAt = t.resolvedAt
    )
}
