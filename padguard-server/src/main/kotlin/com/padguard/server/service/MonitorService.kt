package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.DeviceSetting
import com.padguard.server.domain.MediaTask
import com.padguard.server.domain.Screenshot
import com.padguard.server.dto.*
import com.padguard.server.stream.ScreenStreamHub
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.DeviceSettingRepository
import com.padguard.server.repository.MediaTaskRepository
import com.padguard.server.repository.ScreenshotRepository
import com.padguard.server.ws.WebSocketPushService
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service
class MonitorService(
    private val deviceRepository: DeviceRepository,
    private val ownershipGuard: com.padguard.server.security.DeviceOwnershipGuard,
    private val commandService: CommandService,
    private val screenshotRepository: ScreenshotRepository,
    private val mediaTaskRepository: MediaTaskRepository,
    private val deviceSettingRepository: DeviceSettingRepository,
    private val webSocketPush: WebSocketPushService,
    private val hub: ScreenStreamHub,
    @Value("\${padguard.stream.max-live-devices:1000}") private val maxLiveDevices: Int = 1000
) {
    private val log = LoggerFactory.getLogger(MonitorService::class.java)

    /**
     * 实时看屏"正在观看"会话：家长端打开看屏页期间持续续期，服务端据此在孩子端推流中断时自动重发开流指令。
     *
     * 根因修复：原实现只下发一条一次性 [CommandType.LIVE_VIEW_START]，孩子端回执后即移出 PENDING 不再重发。
     * 孩子端进程被杀/崩溃/断网/息屏卡死时推流消失，画面永远停在上一条死连接——既不自动恢复，也不提示。
     * 把"正在观看"持久化后，[reconcileLiveSessions] 定时扫描，对孩子端未在推流的活跃会话重发开流指令，
     * 覆盖杀进程重开、崩溃、弱网等所有"推流意外中断"场景，家长无需任何操作。
     * 单实例部署下用内存 Map 即可；若未来多实例需改为共享存储（Redis）。
     */
    private val liveSessions = ConcurrentHashMap<String, LiveViewSession>()

    private companion object {
        /** 会话 TTL：家长端续期间隔 120s，取 180s 留足余量，避免续期间隙误清会话 */
        const val SESSION_TTL_MS = 180_000L

        /** 同一会话两次重发开流指令的最小间隔，避免孩子端未推流时高频刷指令 */
        const val REISSUE_COOLDOWN_MS = 8_000L

        /**
         * 判定"仍在推流"的最近帧新鲜度窗口：超过即视为推流已中断（含僵尸连接）。
         * 需与 ParentStreamController.MAX_IDLE_MS(30s) 同量级、略小于它，避免与"设备离线"误判打架。
         */
        const val PUBLISHER_STALE_MS = 15_000L

        /** 正在观看会话：孩子端推流意外中断时由 [reconcileLiveSessions] 重发开流指令 */
        data class LiveViewSession(
            val deviceId: String,
            var highDefinition: Boolean,
            var expiresAt: Long,
            var lastReissueAt: Long
        )
    }

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

    /**
     * 家长请求立即定位：下发 LOCATE 指令。
     *
     * 与截屏/拍照不同，定位**不落"等待回传的记录"**：
     * 位置由孩子端的周期上报持续写入 `location_tracks`，
     * 这条指令只是让端上"现在就补采一次并上报"，家长端随后 GET 最新位置即可看到。
     * 若在此处造一条 PENDING 记录，反而会出现一条永远不会被回填的悬空记录。
     *
     * ⚠️ 端上只会在**孩子正在使用平板**时才能采到位置（Android 10+ 禁止后台读位置，
     * 而儿童向 App 不能申请后台位置权限）。孩子没在用平板时，指令仍会下发，
     * 端上会登记待办、等界面回到前台自动补传，并向家长回执说明原因。
     */
    fun requestLocate(userId: String, deviceId: String): TaskAcceptedDto {
        requireOwned(userId, deviceId)
        commandService.issueCommand(
            deviceId, CommandType.LOCATE,
            mapOf(CommandKey.REASON to "家长请求立即定位"),
            priority = "HIGH"
        )
        return TaskAcceptedDto(
            taskId = "LOCATE", kind = "LOCATE", status = "SENT", requestedAt = System.currentTimeMillis()
        )
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
     * 开启实时看屏。
     *
     * 为什么是"下发一条指令"而不是"家长端直接连孩子端"：
     * 孩子平板在局域网内没有可路由的地址（且家长端往往在移动网络下），
     * 只能由孩子端主动把帧推到服务端、家长端再从服务端拉。
     * 这条指令就是"开始推流"的开关；家长端观看期间会周期性重发它做续期，
     * 一旦家长关掉页面或掉线，续期停止，孩子端的推流会在 TTL 到点后自动结束。
     *
     * 画质档位必须随指令下发（[CommandKey.HIGH_DEFINITION]）：
     * 孩子端的推流宽度与 JPEG 质量原本是写死的常量，家长在设置页改了「高清画面」
     * 以后设备端收不到任何通知，开关形同虚设。续期时也会带上最新档位，
     * 因此家长在观看途中切换开关，下一次续期即可生效，无需退出页面重进。
     */
    fun startLiveView(userId: String, deviceId: String): Map<String, Any> {
        requireOwned(userId, deviceId)
        // 并发准入：服务端中转架构下，每一路实时看屏都是"一份上行 + 一份下行"带宽。
        // 不限制的话，并发推流设备一多出口带宽会被打满、各路一起卡，正是规模化的核心风险。
        // 达到上限直接拒绝新开流（已开的不受影响，避免误杀正常观看），家长端给出明确提示。
        if (hub.publisherCount() >= maxLiveDevices) {
            throw BizException(
                ParentErr.LIVE_DEVICES_FULL,
                "当前实时观看设备数已达上限（${maxLiveDevices}），请稍后再试",
                Audience.PARENT
            )
        }
        val highDefinition = runCatching { deviceSettingOf(deviceId).highDefinition }.getOrDefault(false)
        // 登记/刷新"正在观看"会话：家长端持续续期，服务端据此在孩子端推流中断时自动重发开流指令。
        liveSessions[deviceId] = LiveViewSession(
            deviceId = deviceId,
            highDefinition = highDefinition,
            expiresAt = System.currentTimeMillis() + SESSION_TTL_MS,
            lastReissueAt = 0L
        )
        commandService.issueCommand(
            deviceId, CommandType.LIVE_VIEW_START,
            mapOf(CommandKey.HIGH_DEFINITION to highDefinition),
            priority = "HIGH"
        )
        return mapOf("deviceId" to deviceId, "live" to true, "highDefinition" to highDefinition)
    }

    fun stopLiveView(userId: String, deviceId: String): Map<String, Any> {
        requireOwned(userId, deviceId)
        // 家长主动停止观看：立即清除会话，避免自愈在 TTL 内继续重发开流指令。
        liveSessions.remove(deviceId)
        commandService.issueCommand(
            deviceId, CommandType.LIVE_VIEW_STOP, emptyMap(), priority = "HIGH"
        )
        return mapOf("deviceId" to deviceId, "live" to false)
    }

    /**
     * 实时看屏会话自愈：家长端正在观看期间，孩子端推流可能因进程被杀/崩溃/断网/息屏而中断。
     *
     * 仅依赖家长端周期续期或一次性指令时，中断后画面会永久停在死连接上。把"正在观看"变成服务端
     * 持久会话后，这里每 3s 扫描：
     * - 会话过期（家长端停止观看且未续期）→ 下发 LIVE_VIEW_STOP 并清理；
     * - 会话仍活跃但孩子端未在推流 → 重发 LIVE_VIEW_START，触发孩子端重新推帧。
     *
     * 孩子端收到指令后会等待无障碍通道就绪（进程重启后系统重绑无障碍服务有延迟窗口）再推流，
     * 因此"杀进程重开"能在数秒内自动恢复，家长无需任何操作。重发指令带冷却，避免未推流时高频刷指令。
     */
    @Scheduled(fixedDelay = 3_000L)
    fun reconcileLiveSessions() {
        if (liveSessions.isEmpty()) return
        val now = System.currentTimeMillis()
        val iterator = liveSessions.entries.iterator()
        while (iterator.hasNext()) {
            val (deviceId, session) = iterator.next()
            if (now > session.expiresAt) {
                iterator.remove()
                log.info("live session expired, issuing stop: device=$deviceId")
                runCatching {
                    commandService.issueCommand(deviceId, CommandType.LIVE_VIEW_STOP, emptyMap(), "HIGH")
                }
                continue
            }
            // "正在推流"判定必须叠加最近帧时间：仅看 publishers 会误判"进程被杀但 TCP 未即时断开"的
            // 僵尸连接，导致漏发恢复指令；最近一帧超过 PUBLISHER_STALE_MS 即视为推流已实质中断。
            val activelyUploading = hub.isPublishing(deviceId) &&
                (now - hub.lastFrameAt(deviceId) <= PUBLISHER_STALE_MS)
            if (!activelyUploading && now - session.lastReissueAt >= REISSUE_COOLDOWN_MS) {
                session.lastReissueAt = now
                session.highDefinition =
                    runCatching { deviceSettingOf(deviceId).highDefinition }.getOrDefault(session.highDefinition)
                log.info("live session reissue start: device=$deviceId (child not uploading)")
                runCatching {
                    commandService.issueCommand(
                        deviceId, CommandType.LIVE_VIEW_START,
                        mapOf(CommandKey.HIGH_DEFINITION to session.highDefinition),
                        priority = "HIGH"
                    )
                }
            }
        }
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

    /**
     * 上报媒体文件完成。
     *
     * 必须校验 [deviceId] 与媒体任务归属设备一致，否则任意设备可凭枚举到的 taskId
     * 越权改写他人媒体任务（跨设备劫持）。deviceId 来自设备认证后的 [RequestAttribute]，
     * 不一致时静默丢弃（不报错、不更新），避免泄露任务是否存在。
     */
    fun finishMediaTask(taskId: String, deviceId: String, fileUrl: String, mimeType: String, size: Long, durationSeconds: Int?) {
        mediaTaskRepository.findById(taskId).ifPresent { t ->
            if (t.deviceId != deviceId) {
                log.warn("finishMediaTask 归属不符，已忽略：taskId={}, 任务设备={}, 上报设备={}", taskId, t.deviceId, deviceId)
                return@ifPresent
            }
            t.url = fileUrl
            t.mimeType = mimeType
            t.size = size
            t.durationSeconds = durationSeconds
            t.status = "READY"
            t.finishedAt = System.currentTimeMillis()
            mediaTaskRepository.save(t)
        }
    }

    /**
     * 取媒体任务的完整状态（含文件地址）。
     *
     * 停止录屏是**异步**的：HTTP 下发 stop 指令后，孩子端要收尾编码器、
     * 把 mp4 上传回来，服务端才会把任务置为 READY 并填上 url。
     * 因此家长端点完"停止"不能立刻拿到地址，必须轮询这个接口直到 status=READY。
     */
    fun getMediaTask(userId: String, taskId: String): MediaTaskStatusDto {
        val task = mediaTaskRepository.findById(taskId).orElse(null)
            ?: throw BizException(ParentErr.PARAM_ERROR, "任务不存在", Audience.PARENT)
        requireOwned(userId, task.deviceId)
        return MediaTaskStatusDto(
            taskId = task.id, deviceId = task.deviceId, kind = task.kind, status = task.status,
            url = task.url, mimeType = task.mimeType, size = task.size,
            durationSeconds = task.durationSeconds,
            startedAt = task.startedAt, finishedAt = task.finishedAt
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

    /**
     * 设备归属校验已收敛至 [com.padguard.server.security.DeviceOwnershipGuard]（唯一实现），
     * 消除逐 Service 复制私有副本的漏网风险（详见该组件类注释）。
     */
    private fun requireOwned(userId: String, deviceId: String) =
        ownershipGuard.requireOwned(userId, deviceId)
}
