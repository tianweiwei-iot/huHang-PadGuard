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
        // 提前失败优于下发后被孩子端拒绝：否则家长端看到"发布成功"，
        // 孩子端却以 BAD_PAYLOAD 丢弃，故障无法被及时发现。
        var content = req.text?.trim().orEmpty()
        if (content.isEmpty() && req.mediaUrl.isNullOrBlank()) {
            throw BizException(ParentErr.PARAM_ERROR, "消息内容不能为空", Audience.PARENT)
        }
        // 纯媒体消息（未填附加说明）content 兜底为素材名：
        // 下发的载荷里 CONTENT 为空串时，旧版孩子端 / 历史日志里会看到一条"空消息"，
        // 用素材名兜底让任何一端的展示都至少有可读信息。
        if (content.isEmpty()) content = req.mediaName?.trim().orEmpty()
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
            deviceId, CommandType.SHOW_MESSAGE,
            mapOf(
                CommandKey.CONTENT to content,
                CommandKey.DURATION_SEC to req.displaySeconds,
                CommandKey.BLOCKING to req.fullScreen,
                // 霸屏时孩子端要在屏幕正中展示素材，缺了 mediaUrl 就只剩一行文字
                CommandKey.CONTENT_TYPE to req.contentType,
                CommandKey.MEDIA_URL to (req.mediaUrl ?: ""),
                CommandKey.MEDIA_NAME to (req.mediaName ?: "")
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
