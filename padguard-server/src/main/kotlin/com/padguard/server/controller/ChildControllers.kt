package com.padguard.server.controller

import com.padguard.server.common.DeviceApiResponse
import com.padguard.server.dto.*
import com.padguard.server.service.*
import com.padguard.server.mqtt.ChildUplinkHandler
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*
import org.springframework.web.context.request.async.DeferredResult
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/v1/device")
class ChildDeviceController(
    private val deviceService: DeviceService,
    private val policyService: PolicyService,
    private val policyExtensionService: PolicyExtensionService
) {
    @PostMapping("/bind")
    fun bind(@RequestBody req: BindRequest) = DeviceApiResponse.ok(deviceService.bindChild(req))

    /** 账号密码绑定：孩子端输入家长手机号 + 密码，免配对码完成绑定（方式④） */
    @PostMapping("/bind-by-account")
    fun bindByAccount(@RequestBody req: BindByAccountRequest) =
        DeviceApiResponse.ok(deviceService.bindChildByAccount(req))

    @GetMapping("/time")
    fun time() = DeviceApiResponse.ok(mapOf("serverTime" to System.currentTimeMillis()))

    @GetMapping("/policy")
    fun policy(
        @RequestParam currentVersion: Int,
        @RequestAttribute("deviceId") deviceId: String
    ): Any {
        // 拉取必经路径上校验"子表 → 策略包"一致性：模板遗留规则、漏触发的 rebuild 都在此自愈
        policyExtensionService.ensureRebuilt(deviceId)
        return DeviceApiResponse.ok(policyService.getForChild(deviceId, currentVersion))
    }

    /**
     * 孩子端自定义设备名。凭设备令牌鉴权（[deviceId] 由拦截器注入），
     * 写库即生效；家长端设备台账下次刷新即可看到，满足「自定义 + 实时同步至各端」。
     */
    @PostMapping("/name")
    fun updateName(
        @RequestAttribute("deviceId") deviceId: String,
        @RequestBody req: DeviceNameRequest
    ) = DeviceApiResponse.ok(
        deviceService.updateNameByDevice(deviceId, req.name).let { mapOf("deviceId" to deviceId, "name" to req.name) }
    )
}

@RestController
@RequestMapping("/api/v1/device")
class ChildReportController(
    private val ingestService: IngestService,
    private val deviceService: DeviceService,
    private val commandService: CommandService,
    private val fileStorageService: FileStorageService,
    private val monitorService: MonitorService,
    private val appManageService: AppManageService,
    private val childUplinkHandler: ChildUplinkHandler
) {
    @PostMapping("/logs")
    fun logs(
        @RequestAttribute("deviceId") deviceId: String,
        @RequestBody body: LogsRequest
    ) = DeviceApiResponse.ok(ingestService.saveLogs(body.copy(deviceId = deviceId)))

    @PostMapping("/locations")
    fun locations(
        @RequestAttribute("deviceId") deviceId: String,
        @RequestBody body: LocationsRequest
    ) = DeviceApiResponse.ok(mapOf("accepted" to ingestService.saveLocations(body.copy(deviceId = deviceId))))

    /**
     * 心跳。
     *
     * 回执**必须携带** `pendingCommandCount` / `policyVersion`：
     * 孩子端靠这两个字段在心跳这一刻就"按需"立刻拉指令、重新拉策略，
     * 而不必苦等下一次定时轮询。原先这里返回 `ok(null)`，终端反序列化后拿到全默认值，
     * 那些"有指令就立即拉取"的触发条件永远不成立 —— 远程锁屏 / 解锁 / 实时看屏首帧
     * 因此只能等到兜底轮询才落地，这是端到端延迟高的直接原因之一。
     */
    @PostMapping("/heartbeat")
    fun heartbeat(
        @RequestAttribute("deviceId") deviceId: String,
        @RequestBody hb: HeartbeatDto
    ): DeviceApiResponse<*> {
        deviceService.applyHeartbeat(deviceId, hb)
        val pending = commandService.pendingForPolling(deviceId, 0).size
        return DeviceApiResponse.ok(
            mapOf(
                "pendingCommandCount" to pending,
                "policyVersion" to deviceService.policyVersionOf(deviceId),
                "flushLogs" to false
            )
        )
    }

    /**
     * MQTT 降级轮询通道：返回设备待执行指令包。
     *
     * 支持长轮询：终端带上 `waitMs` 后，若此刻没有待办指令，请求会挂在服务端，
     * 直到有指令入库（[CommandService.notifyWaiters] 唤醒）或超时才返回。
     *
     * 这是无 broker 环境下实时性的关键：定时轮询的周期就是指令延迟的下限，
     * 60s 一轮意味着家长点「锁屏」最坏要等一分钟。长轮询把延迟从「一个轮询周期」
     * 降到「一次 RTT」，实时看屏首帧与远程锁屏 / 解锁才能做到秒级响应。
     *
     * 不带 `waitMs` 时行为与原先完全一致（立即返回），保证旧终端兼容。
     */
    @GetMapping("/commands")
    fun commands(
        @RequestAttribute("deviceId") deviceId: String,
        @RequestParam since: Long = 0,
        @RequestParam(defaultValue = "0") waitMs: Long = 0
    ): Any {
        if (waitMs <= 0L) {
            return DeviceApiResponse.ok(commandService.pendingForPolling(deviceId, since))
        }
        val capped = waitMs.coerceIn(1L, CommandService.MAX_WAIT_MS)
        val deferred = DeferredResult<Any>(capped)
        val onReady: (List<CommandPacket>) -> Unit = { list ->
            runCatching { deferred.setResult(DeviceApiResponse.ok(list)) }
        }
        // 超时回空列表而不是报错：对终端而言与「这一轮没有指令」等价，不会触发错误退避
        deferred.onTimeout {
            commandService.removeWaiter(deviceId, onReady)
            runCatching { deferred.setResult(DeviceApiResponse.ok(emptyList<CommandPacket>())) }
        }
        // 正常完成 / 超时 / 客户端断开都必须摘除回调，否则队列随断连无限增长
        deferred.onCompletion { commandService.removeWaiter(deviceId, onReady) }
        // 返回 false 表示此刻已有待办指令、onReady 已同步 setResult；
        // 结果会暂存在 DeferredResult 里，Spring 拿到它后立刻完成响应。
        commandService.registerWaiter(deviceId, since, onReady)
        return deferred
    }

    /**
     * 孩子端自定义个人资料（姓名 / 昵称 / 头像 URL），凭设备令牌鉴权。
     * 写库即生效，并向家长端推送更新事件，满足「自定义 + 实时同步至家长端」。
     */
    @PostMapping("/profile")
    fun updateProfile(
        @RequestAttribute("deviceId") deviceId: String,
        @RequestBody req: DeviceProfileRequest
    ) = DeviceApiResponse.ok(buildMap {
        deviceService.updateChildProfile(deviceId, req)
        put("deviceId", deviceId)
    })

    /** 孩子端上传头像，返回可访问的 URL；落库后家长端拉取设备台账即可显示。 */
    @PostMapping("/avatar", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadAvatar(
        @RequestAttribute("deviceId") deviceId: String,
        @RequestParam("file") file: org.springframework.web.multipart.MultipartFile
    ): DeviceApiResponse<*> {
        val url = fileStorageService.store(
            file.contentType ?: "image/jpeg",
            file.bytes,
            file.originalFilename ?: "avatar.jpg"
        )
        deviceService.updateChildProfile(deviceId, DeviceProfileRequest(childAvatar = url))
        return DeviceApiResponse.ok(mapOf("url" to url))
    }

    /** HTTP 降级 ack 通道：本地免 Docker（MQTT 关闭）时，孩子端经此回执指令，闭环主链路 */
    @PostMapping("/ack")
    fun ack(
        @RequestAttribute("deviceId") deviceId: String,
        @RequestBody body: String
    ): DeviceApiResponse<*> {
        childUplinkHandler.handleAck(deviceId, body)
        return DeviceApiResponse.ok(null)
    }

    /** 上报截图（multipart）：存文件并推 WS screenshot.ready */
    @PostMapping("/screenshot", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun screenshot(
        @RequestAttribute("deviceId") deviceId: String,
        @RequestParam(required = false) shotId: String?,
        @RequestParam(required = false) capturedAt: Long?,
        @RequestParam(required = false) triggerType: String?,
        @RequestParam("file") file: MultipartFile
    ): DeviceApiResponse<*> {
        val url = fileStorageService.store(file.contentType ?: "image/jpeg", file.bytes, file.originalFilename ?: "shot.jpg")
        monitorService.storeScreenshot(shotId, deviceId, url, null, null, capturedAt)
        return DeviceApiResponse.ok(UploadAckDto(accepted = true, url = url))
    }

    /** 上报媒体文件（录音/录屏/拍照）：关联媒体任务并标记 READY */
    @PostMapping("/media", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun media(
        @RequestAttribute("deviceId") deviceId: String,
        @RequestParam taskId: String,
        @RequestParam(required = false) durationSeconds: Int?,
        @RequestParam("file") file: MultipartFile
    ): DeviceApiResponse<*> {
        val mime = file.contentType ?: "application/octet-stream"
        val url = fileStorageService.store(mime, file.bytes, file.originalFilename ?: "media.bin")
        monitorService.finishMediaTask(taskId, url, mime, file.size, durationSeconds ?: 0)
        return DeviceApiResponse.ok(UploadAckDto(accepted = true, url = url))
    }

    /**
     * 全量上报已安装应用台账（应用监控 / 远程运维的数据源）。
     *
     * 走 HTTP 而不是 MQTT：清单可能有几百条、几十 KB，
     * MQTT 上行走大包容易触发 broker 的报文大小限制，且失败后重传成本高。
     * HTTP 天然支持压缩与更大的 body，也更便于服务端做幂等 upsert。
     */
    @PostMapping("/apps")
    fun apps(
        @RequestAttribute("deviceId") deviceId: String,
        @RequestBody body: AppInventoryRequest
    ): DeviceApiResponse<*> =
        DeviceApiResponse.ok(appManageService.syncInventory(deviceId, body))
}
