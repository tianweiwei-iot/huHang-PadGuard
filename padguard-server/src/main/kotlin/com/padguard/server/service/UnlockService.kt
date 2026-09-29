package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.UnlockTicket
import com.padguard.server.dto.UnlockApproveRequest
import com.padguard.server.dto.UnlockRejectRequest
import com.padguard.server.dto.UnlockTicketDto
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.UnlockTicketRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class UnlockService(
    private val deviceRepository: DeviceRepository,
    private val unlockTicketRepository: UnlockTicketRepository,
    private val commandService: CommandService
) {
    /** 孩子端 UNLOCK_REQUEST 日志 -> 工单（工单 ID = requestId，便于回执对齐） */
    fun createFromRequest(deviceId: String, payload: Map<String, Any?>?) {
        val requestId = (payload?.get("requestId") as? String)?.takeIf { it.isNotBlank() }
            ?: "ur_" + UUID.randomUUID().toString().replace("-", "").take(20)
        if (unlockTicketRepository.existsById(requestId)) return
        val dev = deviceRepository.findById(deviceId).orElse(null) ?: return
        unlockTicketRepository.save(
            UnlockTicket(
                id = requestId, deviceId = deviceId, userId = dev.userId,
                packageName = payload?.get("packageName") as? String,
                appLabel = payload?.get("appLabel") as? String,
                durationMinutes = (payload?.get("durationMinutes") as? String)?.toIntOrNull()
                    ?: (payload?.get("durationMinutes") as? Number)?.toInt(),
                reason = payload?.get("reason") as? String,
                status = "PENDING", createdAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * 列出该设备的全部工单（含已处理与已忽略）。
     *
     * 为什么要把 APPROVED / REJECTED / IGNORED 一起返回：
     * 家长端"小铃铛"是一个**消息中心**，忽略之后必须还能在这里翻出来继续处理。
     * 只返回 PENDING 的话，家长一旦点了忽略，这条申请就从地球上消失了，
     * 想再放行只能等孩子重新提交一次 —— 而孩子往往不会重提。
     */
    fun listTickets(userId: String, deviceId: String): List<UnlockTicketDto> {
        return listOf("PENDING", "APPROVED", "REJECTED", "IGNORED")
            .flatMap { unlockTicketRepository.findByDeviceIdAndStatus(deviceId, it) }
            .filter { it.userId == userId }
            .sortedByDescending { it.createdAt }
            .map { toDto(it) }
    }

    /**
     * 忽略：关闭弹窗并归档，**不下发任何消息给孩子**，且工单仍可被后续批准。
     *
     * 为什么不能复用 reject：
     * reject 会给孩子端下发一条 SHOW_MESSAGE（"申请未通过"），并把工单置为终态不可再批。
     * 但"忽略"的语义是"我现在不想管"，不是"我不同意"——
     * 孩子此刻正在锁屏页盯着，一条拒绝对他是无谓的打击；而家长稍后改变主意时工单已死。
     * 因此单独引入 IGNORED 中间态：弹窗不再打扰，记录留在铃铛里随时可翻出来批准。
     */
    fun dismiss(userId: String, deviceId: String, ticketId: String) {
        val t = ownedOpen(userId, deviceId, ticketId)
        t.status = "IGNORED"
        t.resolvedAt = System.currentTimeMillis()
        unlockTicketRepository.save(t)
    }

    /** 家长同意 -> 下发 TEMP_UNLOCK（带 packageName / durationMinutes） */
    fun approve(userId: String, deviceId: String, ticketId: String, req: UnlockApproveRequest) {
        val t = ownedOpen(userId, deviceId, ticketId)
        t.status = "APPROVED"
        t.durationMinutes = req.durationMinutes ?: t.durationMinutes
        t.resolvedAt = System.currentTimeMillis()
        unlockTicketRepository.save(t)
        commandService.issueCommand(
            deviceId, CommandType.TEMP_UNLOCK,
            mapOf(
                CommandKey.PACKAGE_NAME to (req.packageName ?: t.packageName ?: ""),
                CommandKey.DURATION_MINUTES to (t.durationMinutes ?: 30)
            ),
            priority = "HIGH"
        )
    }

    /** 家长拒绝 -> 下发 SHOW_MESSAGE（带 requestId + 拒因 body） */
    fun reject(userId: String, deviceId: String, ticketId: String, req: UnlockRejectRequest) {
        val t = ownedOpen(userId, deviceId, ticketId)
        t.status = "REJECTED"
        t.resolvedAt = System.currentTimeMillis()
        unlockTicketRepository.save(t)
        commandService.issueCommand(
            deviceId, CommandType.SHOW_MESSAGE,
            mapOf(
                CommandKey.REQUEST_ID to t.id,
                CommandKey.CONTENT to (req.reason ?: "申请未通过")
            ),
            priority = "NORMAL"
        )
    }

    /**
     * 取出"仍可处理"的工单：PENDING（未看过）或 IGNORED（看过但没决定）。
     *
     * 早期这里只认 PENDING，一旦引入忽略态就会把"忽略后想再批准"挡在门外，
     * 只会报一个含糊的"已处理"。忽略不是终态，必须和 PENDING 同等对待。
     */
    private fun ownedOpen(userId: String, deviceId: String, ticketId: String): UnlockTicket {
        val dev = deviceRepository.findById(deviceId).orElse(null)
            ?: throw BizException(ParentErr.DEVICE_NOT_FOUND, "设备不存在", Audience.PARENT)
        if (dev.userId != userId) {
            throw BizException(ParentErr.DEVICE_NOT_OWNED, "设备不属于当前用户", Audience.PARENT)
        }
        val t = unlockTicketRepository.findByIdAndDeviceId(ticketId, deviceId)
            ?: throw BizException(ParentErr.PARAM_ERROR, "工单不存在", Audience.PARENT)
        if (t.status != "PENDING" && t.status != "IGNORED") {
            throw BizException(ParentErr.PARAM_ERROR, "工单已处理（已同意或已拒绝）", Audience.PARENT)
        }
        return t
    }

    private fun toDto(t: UnlockTicket) = UnlockTicketDto(
        id = t.id, deviceId = t.deviceId, packageName = t.packageName, appLabel = t.appLabel,
        durationMinutes = t.durationMinutes, reason = t.reason, status = t.status,
        createdAt = t.createdAt, resolvedAt = t.resolvedAt
    )
}
