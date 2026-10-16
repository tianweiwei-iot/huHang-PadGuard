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
    private val auditService: AuditService
) {
    private val log = LoggerFactory.getLogger(UsageDelayService::class.java)

    /**
     * 孩子端发起延时申请：仅创建 PENDING 工单，不立即延长。
     *
     * 校验：今日计时必须已开始；档位须落 [UsageSession.DELAY_OPTIONS]；
     * 同一设备不允许存在未结（PENDING）工单，避免重复提交；
     * 已批准 + 待审批 + 本次申请不得超过 [UsageSession.MAX_DAILY_EXTEND_MINUTES]。
     */
    @Transactional
    fun request(deviceId: String, minutes: Int): UsageDelayTicketDto {
        val now = System.currentTimeMillis()
        val today = dayKey(now)
        val session = usageSessionRepository.findByDeviceId(deviceId)
        if (session == null || session.startEpoch == null || session.dayKey != today) {
            throw BizException(ParentErr.PARAM_ERROR, "今日计时尚未开始，无法申请延时")
        }
        if (minutes !in UsageSession.DELAY_OPTIONS) {
            throw BizException(ParentErr.PARAM_ERROR, "非法的延时档位：$minutes 分钟")
        }
        if (usageDelayRepository.findByDeviceIdAndStatus(deviceId, "PENDING").isNotEmpty()) {
            throw BizException(ParentErr.PARAM_ERROR, "已有待审核的延时申请，请等待家长处理")
        }
        // 单日累计上限 = 已批准（已计入 session.extendedMinutes）+ 待审批 + 本次申请，
        // 三者一并计入，防止孩子反复提交绕过上限。
        val pending = usageDelayRepository.findByDeviceIdAndStatus(deviceId, "PENDING").sumOf { it.minutes }
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

        userId?.let {
            webSocketPush.pushToUser(
                it,
                mapOf(
                    "type" to "usage.delay.requested",
                    "deviceId" to deviceId,
                    "ticketId" to ticket.id,
                    "minutes" to minutes,
                    "createdAt" to now
                )
            )
        }
        return toDto(ticket)
    }

    /** 家长同意：真正累加额度并通知两端刷新 */
    @Transactional
    fun approve(userId: String, deviceId: String, ticketId: String, req: UsageDelayApproveRequest) {
        val t = ownedOpen(userId, deviceId, ticketId)
        val session = usageSessionRepository.findByDeviceId(deviceId)
        if (session != null) {
            session.extendedMinutes += t.minutes
            session.lastDelayEpoch = System.currentTimeMillis()
            session.updatedAt = System.currentTimeMillis()
            usageSessionRepository.save(session)
        }
        t.status = "APPROVED"
        t.resolvedAt = System.currentTimeMillis()
        usageDelayRepository.save(t)
        auditService.record(userId, deviceId, AuditAction.USAGE_DELAY_APPROVE, targetType = "ticket", targetId = ticketId,
            detail = "minutes=${t.minutes}")
        log.info("usage delay approved device={} ticket={} +{}min", deviceId, ticketId, t.minutes)

        userId.let {
            webSocketPush.pushToUser(
                it,
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
        t.status = "REJECTED"
        t.resolvedAt = System.currentTimeMillis()
        usageDelayRepository.save(t)
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

    /**
     * 家长忽略：归档弹窗、不下发任何消息、工单仍可被批准（与 [UnlockService.dismiss] 同语义）。
     */
    @Transactional
    fun dismiss(userId: String, deviceId: String, ticketId: String) {
        val t = ownedOpen(userId, deviceId, ticketId)
        t.status = "IGNORED"
        t.resolvedAt = System.currentTimeMillis()
        usageDelayRepository.save(t)
        auditService.record(userId, deviceId, AuditAction.USAGE_DELAY_DISMISS, targetType = "ticket", targetId = ticketId)
        log.info("usage delay dismissed device={} ticket={}", deviceId, ticketId)

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

    /** 列出该设备全部工单（含已处理），供家长端消息中心翻看历史 */
    fun listTickets(userId: String, deviceId: String): List<UsageDelayTicketDto> {
        return listOf("PENDING", "APPROVED", "REJECTED", "IGNORED")
            .flatMap { usageDelayRepository.findByDeviceIdAndStatus(deviceId, it) }
            .filter { it.userId == userId }
            .sortedByDescending { it.createdAt }
            .map { toDto(it) }
    }

    /** 当前等待家长审批的延时分钟数（PENDING 工单之和），供会话 DTO 透传给两端 */
    fun pendingDelayMinutes(deviceId: String): Int =
        usageDelayRepository.findByDeviceIdAndStatus(deviceId, "PENDING").sumOf { it.minutes }

    /**
     * 取出"仍可处理"的工单：PENDING 或 IGNORED（忽略非终态，必须和 PENDING 同等对待）。
     */
    private fun ownedOpen(userId: String, deviceId: String, ticketId: String): UsageDelayTicket {
        val dev = deviceRepository.findById(deviceId).orElse(null)
            ?: throw BizException(ParentErr.DEVICE_NOT_FOUND, "设备不存在", Audience.PARENT)
        if (dev.userId != userId) {
            throw BizException(ParentErr.DEVICE_NOT_OWNED, "设备不属于当前用户", Audience.PARENT)
        }
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
