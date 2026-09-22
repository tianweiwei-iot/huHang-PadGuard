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

    fun listTickets(userId: String, deviceId: String): List<UnlockTicketDto> {
        return unlockTicketRepository.findByDeviceIdAndStatus(deviceId, "PENDING")
            .plus(unlockTicketRepository.findByDeviceIdAndStatus(deviceId, "APPROVED"))
            .plus(unlockTicketRepository.findByDeviceIdAndStatus(deviceId, "REJECTED"))
            .filter { it.userId == userId }
            .map { toDto(it) }
    }

    /** 家长同意 -> 下发 TEMP_UNLOCK（带 packageName / durationMinutes） */
    fun approve(userId: String, deviceId: String, ticketId: String, req: UnlockApproveRequest) {
        val t = ownedPending(userId, deviceId, ticketId)
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
        val t = ownedPending(userId, deviceId, ticketId)
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

    private fun ownedPending(userId: String, deviceId: String, ticketId: String): UnlockTicket {
        val dev = deviceRepository.findById(deviceId).orElse(null)
            ?: throw BizException(ParentErr.DEVICE_NOT_FOUND, "设备不存在", Audience.PARENT)
        if (dev.userId != userId) {
            throw BizException(ParentErr.DEVICE_NOT_OWNED, "设备不属于当前用户", Audience.PARENT)
        }
        val t = unlockTicketRepository.findByIdAndDeviceId(ticketId, deviceId)
            ?: throw BizException(ParentErr.PARAM_ERROR, "工单不存在", Audience.PARENT)
        if (t.status != "PENDING") {
            throw BizException(ParentErr.PARAM_ERROR, "工单已处理", Audience.PARENT)
        }
        return t
    }

    private fun toDto(t: UnlockTicket) = UnlockTicketDto(
        id = t.id, deviceId = t.deviceId, packageName = t.packageName, appLabel = t.appLabel,
        durationMinutes = t.durationMinutes, reason = t.reason, status = t.status,
        createdAt = t.createdAt, resolvedAt = t.resolvedAt
    )
}
