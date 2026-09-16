package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.PublishedMessage
import com.padguard.server.dto.MessagePublishRequest
import com.padguard.server.dto.PublishedMessageDto
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.PublishedMessageRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class MessageService(
    private val deviceRepository: DeviceRepository,
    private val commandService: CommandService,
    private val publishedMessageRepository: PublishedMessageRepository
) {
    /** 家长向设备实时发布信息：下发 SHOW_MESSAGE 指令并记录历史 */
    fun publish(userId: String, deviceId: String, req: MessagePublishRequest): PublishedMessageDto {
        requireOwned(userId, deviceId)
        val now = System.currentTimeMillis()
        val msg = publishedMessageRepository.save(
            PublishedMessage(
                id = UUID.randomUUID().toString(), deviceId = deviceId,
                contentType = req.contentType, text = req.text, mediaUrl = req.mediaUrl,
                mediaName = req.mediaName, displaySeconds = req.displaySeconds,
                fullScreen = req.fullScreen, playAudio = req.playAudio, publishedAt = now
            )
        )
        commandService.issueCommand(
            deviceId, "SHOW_MESSAGE",
            mapOf(
                "contentType" to req.contentType,
                "text" to req.text,
                "mediaUrl" to req.mediaUrl,
                "displaySeconds" to req.displaySeconds,
                "fullScreen" to req.fullScreen,
                "playAudio" to req.playAudio
            ),
            priority = "NORMAL"
        )
        return toDto(msg)
    }

    fun listMessages(userId: String, deviceId: String, limit: Int): List<PublishedMessageDto> {
        requireOwned(userId, deviceId)
        return publishedMessageRepository
            .findByDeviceIdOrderByPublishedAtDesc(deviceId, PageRequest.of(0, limit.coerceAtLeast(1)))
            .map { toDto(it) }
    }

    private fun toDto(m: PublishedMessage) = PublishedMessageDto(
        id = m.id, deviceId = m.deviceId, contentType = m.contentType,
        summary = m.text ?: m.mediaName ?: "", displaySeconds = m.displaySeconds,
        fullScreen = m.fullScreen, publishedAt = m.publishedAt
    )

    private fun requireOwned(userId: String, deviceId: String) {
        val dev = deviceRepository.findById(deviceId).orElse(null)
            ?: throw BizException(ParentErr.DEVICE_NOT_FOUND, "设备不存在", Audience.PARENT)
        if (dev.userId != userId) {
            throw BizException(ParentErr.DEVICE_NOT_OWNED, "设备不属于当前用户", Audience.PARENT)
        }
    }
}
