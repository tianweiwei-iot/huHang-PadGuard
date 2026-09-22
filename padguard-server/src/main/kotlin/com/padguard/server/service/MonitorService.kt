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
            deviceId, CommandType.SCREENSHOT,
            mapOf(CommandKey.TRIGGER_TYPE to "REMOTE", CommandKey.SHOT_ID to shotId),
            priority = "HIGH"
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
        val t = createMediaTask(deviceId, "PHOTO", "image/jpeg")
        commandService.issueCommand(
            deviceId, CommandType.PHOTO, mapOf(CommandKey.TASK_ID to t.taskId), priority = "NORMAL"
        )
        return t
    }

    fun startRecording(userId: String, deviceId: String): TaskAcceptedDto {
        requireOwned(userId, deviceId)
        val t = createMediaTask(deviceId, "RECORD", "audio/mp4")
        commandService.issueCommand(
            deviceId, CommandType.RECORD, mapOf(CommandKey.TASK_ID to t.taskId), priority = "NORMAL"
        )
        return t
    }

    /**
     * 发起录屏。任务创建与指令下发必须一一对应：
     * 之前 [createMediaTask] 内部会先下发一条只有 taskId 的 SCREEN_RECORD 指令，
     * 这里又下发一条带分辨率/音频参数的，导致同一次请求产生两条指令、孩子端重复开录
     * （且第一条缺参数只能用默认值）。现在统一由调用方下发**唯一一条**全量参数指令。
     */
    fun startScreenRecord(userId: String, deviceId: String, req: StartRecordRequest): TaskAcceptedDto {
        requireOwned(userId, deviceId)
        val t = createMediaTask(deviceId, "SCREEN_RECORD", "video/mp4")
        commandService.issueCommand(
            deviceId, CommandType.SCREEN_RECORD,
            mapOf(
                CommandKey.TASK_ID to t.taskId,
                CommandKey.RESOLUTION to req.resolution,
                CommandKey.WITH_AUDIO to req.withAudio
            ),
            priority = "NORMAL"
        )
        return t
    }

    /**
     * 停止录屏：必须真正下发 STOP_SCREEN_RECORD 指令。
     *
     * 之前只查库返回任务、**根本没下发指令**，家长端却收到 200 success ——
     * 外部表现为"停止成功"，孩子端却继续录制，正是指令契约里点名的
     * "操作成功、设备毫无反应"一类问题，这里用与 [startScreenRecord] 对称的指令下发修复。
     */
    fun stopScreenRecord(userId: String, taskId: String): TaskAcceptedDto {
        val task = mediaTaskRepository.findById(taskId).orElse(null)
            ?: throw BizException(ParentErr.PARAM_ERROR, "任务不存在", Audience.PARENT)
        requireOwned(userId, task.deviceId)
        issueStopCommand(task.deviceId, task.kind, task.id)
        return TaskAcceptedDto(task.id, task.kind, task.status, task.startedAt)
    }

    /** 停止录音/录屏：下发对应类型的停止指令（媒体文件由孩子端上传后入库） */
    fun stopByKind(userId: String, deviceId: String, kind: String): TaskAcceptedDto {
        requireOwned(userId, deviceId)
        val task = mediaTaskRepository.findByDeviceIdAndKindOrderByStartedAtDesc(deviceId, kind, PageRequest.of(0, 1))
            .firstOrNull() ?: throw BizException(ParentErr.PARAM_ERROR, "无进行中的$kind 任务", Audience.PARENT)
        issueStopCommand(deviceId, kind, task.id)
        return TaskAcceptedDto(task.id, task.kind, task.status, task.startedAt)
    }

    /**
     * 下发停止采集指令。
     *
     * 幂等性由孩子端保证：没有进行中的录制时它只记一条日志、不上传、不报错，
     * 因此重复点击"停止"或对已结束的任务点停止都是安全的。
     */
    private fun issueStopCommand(deviceId: String, kind: String, taskId: String) {
        val type = when (kind) {
            "RECORD" -> CommandType.STOP_RECORD
            "SCREEN_RECORD" -> CommandType.STOP_SCREEN_RECORD
            else -> return   // 一次性任务（如拍照）没有"停止"语义
        }
        commandService.issueCommand(
            deviceId, type,
            mapOf(CommandKey.TASK_ID to taskId),
            priority = "HIGH"
        )
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

    /** 取媒体任务结果（停止录音/录屏后回传文件地址用） */
    fun getMediaResult(taskId: String): MediaResultDto {
        val task = mediaTaskRepository.findById(taskId).orElse(null)
            ?: throw BizException(ParentErr.PARAM_ERROR, "任务不存在", Audience.PARENT)
        return MediaResultDto(
            url = task.url ?: "",
            mimeType = task.mimeType ?: "",
            size = task.size ?: 0L,
            durationSeconds = task.durationSeconds
        )
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

    /**
     * 落一条 PENDING 媒体任务记录。
     * **不下发指令** —— 各媒体类型的指令参数不同（录屏要带分辨率/音频），统一由调用方
     * 下发唯一一条指令，避免"建任务时隐式发一条、调用方再发一条"的重复指令。
     */
    private fun createMediaTask(deviceId: String, kind: String, mime: String): TaskAcceptedDto {
        val taskId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        mediaTaskRepository.save(
            MediaTask(id = taskId, deviceId = deviceId, kind = kind, status = "PENDING",
                mimeType = mime, startedAt = now)
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
