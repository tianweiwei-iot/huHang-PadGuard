package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.DeviceSetting
import com.padguard.server.domain.MediaTask
import com.padguard.server.domain.Screenshot
import com.padguard.server.dto.*
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.DeviceSettingRepository
import com.padguard.server.repository.MediaTaskRepository
import com.padguard.server.repository.ScreenshotRepository
import com.padguard.server.ws.WebSocketPushService
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class MonitorService(
    private val deviceRepository: DeviceRepository,
    private val commandService: CommandService,
    private val screenshotRepository: ScreenshotRepository,
    private val mediaTaskRepository: MediaTaskRepository,
    private val deviceSettingRepository: DeviceSettingRepository,
    private val webSocketPush: WebSocketPushService
) {
    /** 家长请求实时截屏：落 PENDING 截图记录 + 下发 SCREENSHOT 指令（带 shotId 便于回传关联） */
    fun requestScreenshot(userId: String, deviceId: String): ScreenshotRequestResult {
        requireOwned(userId, deviceId)
        val shotId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        screenshotRepository.save(
            Screenshot(
                id = shotId, deviceId = deviceId, shotId = shotId, triggerType = "REMOTE",
                capturedAt = null, receivedAt = now, status = "PENDING"
            )
        )
        commandService.issueCommand(
            deviceId, "SCREENSHOT",
            mapOf("triggerType" to "REMOTE", "shotId" to shotId), priority = "HIGH"
        )
        return ScreenshotRequestResult(deviceId, shotId, "PENDING", now)
    }

    fun listScreenshots(userId: String, deviceId: String, limit: Int): List<ScreenshotDto> {
        requireOwned(userId, deviceId)
        return screenshotRepository
            .findByDeviceIdOrderByCapturedAtDesc(deviceId, PageRequest.of(0, limit.coerceAtLeast(1)))
            .map { s ->
                ScreenshotDto(
                    deviceId = s.deviceId, imageUrl = s.fileUrl, thumbnailUrl = s.thumbnailUrl,
                    capturedAt = s.capturedAt, width = s.width, height = s.height, status = s.status
                )
            }
    }

    fun requestPhoto(userId: String, deviceId: String): TaskAcceptedDto {
        requireOwned(userId, deviceId)
        return startMediaTask(deviceId, "PHOTO", "image/jpeg")
    }

    fun startRecording(userId: String, deviceId: String): TaskAcceptedDto {
        requireOwned(userId, deviceId)
        return startMediaTask(deviceId, "RECORD", "audio/mp4")
    }

    fun startScreenRecord(userId: String, deviceId: String, req: StartRecordRequest): TaskAcceptedDto {
        requireOwned(userId, deviceId)
        val t = startMediaTask(deviceId, "SCREEN_RECORD", "video/mp4")
        commandService.issueCommand(
            deviceId, "SCREEN_RECORD",
            mapOf("taskId" to t.taskId, "resolution" to req.resolution, "withAudio" to req.withAudio),
            priority = "NORMAL"
        )
        return t
    }

    fun stopScreenRecord(userId: String, taskId: String): TaskAcceptedDto {
        val task = mediaTaskRepository.findById(taskId).orElse(null)
            ?: throw BizException(ParentErr.PARAM_ERROR, "任务不存在", Audience.PARENT)
        requireOwned(userId, task.deviceId)
        return TaskAcceptedDto(task.id, task.kind, task.status, task.startedAt)
    }

    /** 停止录音/录屏：返回该设备最新一条对应类型任务（媒体文件由孩子端上传后入库） */
    fun stopByKind(userId: String, deviceId: String, kind: String): TaskAcceptedDto {
        requireOwned(userId, deviceId)
        val task = mediaTaskRepository.findByDeviceIdAndKindOrderByStartedAtDesc(deviceId, kind, PageRequest.of(0, 1))
            .firstOrNull() ?: throw BizException(ParentErr.PARAM_ERROR, "无进行中的$kind 任务", Audience.PARENT)
        return TaskAcceptedDto(task.id, task.kind, task.status, task.startedAt)
    }

    fun getScreenSettings(userId: String, deviceId: String): ScreenMonitorSettingsDto {
        requireOwned(userId, deviceId)
        val s = deviceSettingOf(deviceId)
        return ScreenMonitorSettingsDto(
            deviceId = deviceId, autoRefreshSeconds = s.autoRefreshSeconds,
            highDefinition = s.highDefinition, recordResolution = s.recordResolution,
            recordWithAudio = s.recordWithAudio, allowRemoteLock = s.allowRemoteLock
        )
    }

    fun updateScreenSettings(userId: String, deviceId: String, req: ScreenMonitorSettingsDto): ScreenMonitorSettingsDto {
        requireOwned(userId, deviceId)
        val s = deviceSettingOf(deviceId)
        s.autoRefreshSeconds = req.autoRefreshSeconds
        s.highDefinition = req.highDefinition
        s.recordResolution = req.recordResolution
        s.recordWithAudio = req.recordWithAudio
        s.allowRemoteLock = req.allowRemoteLock
        deviceSettingRepository.save(s)
        return getScreenSettings(userId, deviceId)
    }

    // ===== 供孩子端上传调用 =====
    fun storeScreenshot(shotId: String?, deviceId: String, fileUrl: String, width: Int?, height: Int?, capturedAt: Long?) {
        val shot = if (shotId != null) {
            screenshotRepository.findFirstByDeviceIdAndShotId(deviceId, shotId)
        } else null
        val entity = shot ?: Screenshot(deviceId = deviceId)
        entity.deviceId = deviceId
        entity.shotId = shotId
        entity.fileUrl = fileUrl
        entity.width = width
        entity.height = height
        entity.capturedAt = capturedAt ?: System.currentTimeMillis()
        entity.receivedAt = System.currentTimeMillis()
        entity.status = "READY"
        screenshotRepository.save(entity)
        pushScreenshotReady(deviceId, entity)
    }

    fun finishMediaTask(taskId: String, fileUrl: String, mimeType: String, size: Long, durationSeconds: Int?) {
        mediaTaskRepository.findById(taskId).ifPresent { t ->
            t.url = fileUrl
            t.mimeType = mimeType
            t.size = size
            t.durationSeconds = durationSeconds
            t.status = "READY"
            t.finishedAt = System.currentTimeMillis()
            mediaTaskRepository.save(t)
        }
    }

    private fun pushScreenshotReady(deviceId: String, shot: Screenshot) {
        deviceRepository.findById(deviceId).ifPresent { dev ->
            dev.userId?.let { uid ->
                webSocketPush.pushToUser(uid, mapOf(
                    "type" to "screenshot.ready", "deviceId" to deviceId,
                    "imageUrl" to (shot.fileUrl ?: ""), "capturedAt" to (shot.capturedAt ?: 0)
                ))
            }
        }
    }

    private fun startMediaTask(deviceId: String, kind: String, mime: String): TaskAcceptedDto {
        val taskId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        mediaTaskRepository.save(
            MediaTask(id = taskId, deviceId = deviceId, kind = kind, status = "PENDING",
                mimeType = mime, startedAt = now)
        )
        commandService.issueCommand(
            deviceId, kind, mapOf("taskId" to taskId), priority = "NORMAL"
        )
        return TaskAcceptedDto(taskId, kind, "PENDING", now)
    }

    private fun deviceSettingOf(deviceId: String): DeviceSetting =
        deviceSettingRepository.findById(deviceId).orElse(DeviceSetting(deviceId = deviceId))

    private fun requireOwned(userId: String, deviceId: String) {
        val dev = deviceRepository.findById(deviceId).orElse(null)
            ?: throw BizException(ParentErr.DEVICE_NOT_FOUND, "设备不存在", Audience.PARENT)
        if (dev.userId != userId) {
            throw BizException(ParentErr.DEVICE_NOT_OWNED, "设备不属于当前用户", Audience.PARENT)
        }
    }
}
