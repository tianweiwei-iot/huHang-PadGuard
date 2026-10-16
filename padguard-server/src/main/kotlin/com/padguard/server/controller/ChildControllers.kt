package com.padguard.server.controller

import com.padguard.server.common.DeviceApiResponse
import com.padguard.server.dto.*
import com.padguard.server.service.*
import com.padguard.server.mqtt.ChildUplinkHandler
import com.padguard.server.security.RateLimit
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*
import org.springframework.web.context.request.async.DeferredResult
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/v1/device")
class ChildDeviceController(
    private val deviceService: DeviceService,
    private val policyService: PolicyService,
    private val policyExtensionService: PolicyExtensionService,
    private val rateLimit: RateLimit,
    private val usageSessionService: UsageSessionService
) {
    @PostMapping("/bind")
    fun bind(@RequestBody req: BindRequest, httpRequest: jakarta.servlet.http.HttpServletRequest) =
        DeviceApiResponse.ok(
            run {
                // 绑定码可匿名穷举，限流必须先于任何查询（S14）
                rateLimit.require("bind", httpRequest, req.bindCode, com.padguard.server.common.Audience.CHILD)
                deviceService.bindChild(req)
            }
        )

    /** 账号密码绑定：孩子端输入家长手机号 + 密码，免配对码完成绑定（方式④） */
    @PostMapping("/bind-by-account")
    fun bindByAccount(@RequestBody req: BindByAccountRequest, httpRequest: jakarta.servlet.http.HttpServletRequest) =
        DeviceApiResponse.ok(
            run {
                // 家长密码可匿名穷举，与登录同型爆破面（S14）
                rateLimit.require("bind_by_account", httpRequest, req.phone, com.padguard.server.common.Audience.CHILD)
                deviceService.bindChildByAccount(req)
            }
        )

    @GetMapping("/time")
    fun time() = DeviceApiResponse.ok(mapOf("serverTime" to System.currentTimeMillis()))

    /**
     * 孩子端拉取当前使用计时会话（三端时间一致的来源）。
     *
     * 携带服务端墙钟 serverNow，孩子端据此显示与家长端完全一致的剩余时长。
     */
    @GetMapping("/usage/session")
    fun usageSession(
        @RequestAttribute("deviceId") deviceId: String
    ) = DeviceApiResponse.ok(usageSessionService.get(deviceId))

    /**
     * 孩子端确认"计时开始"：服务端锁定今日计时起点与额度。幂等。
     */
    @PostMapping("/usage/session/start")
    fun startUsageSession(
        @RequestAttribute("deviceId") deviceId: String
    ) = DeviceApiResponse.ok(usageSessionService.start(deviceId))

    /**
     * 孩子端申请延时：仅发起审批工单（PENDING），不立即延长。
     * 必须经家长端审批通过后额度才真正增加。返回刷新后的会话（含 pendingDelayMinutes）。
     */
    @PostMapping("/usage/session/delay/request")
    fun requestDelayUsageSession(
        @RequestAttribute("deviceId") deviceId: String,
        @RequestBody req: com.padguard.server.dto.UsageDelayRequest
    ) = DeviceApiResponse.ok(usageSessionService.requestDelay(deviceId, req.minutes))

    /**
     * 孩子端上报护眼强制休息的实际时长（毫秒），服务端从每日总时长中扣除，
     * 保证「休息不计入每日总时长」在三端（孩子端倒计时 / 家长端看板 / 服务端锁机判定）一致生效。
     */
    @PostMapping("/usage/session/rest")
    fun reportEyeCareRest(
        @RequestAttribute("deviceId") deviceId: String,
        @RequestBody req: com.padguard.server.dto.UsageRestReportRequest
    ) = DeviceApiResponse.ok(usageSessionService.reportRest(deviceId, req.restId, req.restMs))

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
     * 风险事件上报（HTTP 降级通道）。
     *
     * 为什么必须有这条通路：孩子端刻意让"设备正在被卸载""检测到 root"这类高危告警
     * **不走 MQTT**（broker 缺失时 `publish` 返回 true 也会全部静默丢失），
     * 只走 HTTPS。服务端原先没有这个端点，孩子端每次上报都是 500 ——
     * 等于家长"最后一道感知能力"一直是断的，且孩子端只记一条 warn，界面上毫无反馈。
     *
     * `deviceId` 一律取鉴权注入的值而非报文里的：否则孩子端可以伪造 deviceId，
     * 往别人的设备台账里塞事件、给别人的家长推告警。
     */
    @PostMapping("/events")
    fun events(
        @RequestAttribute("deviceId") deviceId: String,
        @RequestBody body: EventsRequest
    ): DeviceApiResponse<*> {
        val (accepted, duplicated) = childUplinkHandler.handleEventPackets(
            deviceId, body.events.map { it.copy(deviceId = deviceId) }
        )
        return DeviceApiResponse.ok(mapOf("accepted" to accepted, "duplicated" to duplicated))
    }

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
        // 在指令轮询响应里"顺带"告知最新策略版本号：孩子端走 HTTPS 轮询（MQTT 未启用）时，
        // 这是把"家长改了时间管控→平板上限立即生效"延迟从「等一次心跳(30s)」压到「一个轮询周期(3s)」的关键。
        // 与心跳回执里的 policyVersion 同源（deviceService.policyVersionOf），两条通道口径一致。
        val wrap = { list: List<CommandPacket> ->
            mapOf(
                "policyVersion" to deviceService.policyVersionOf(deviceId),
                "commands" to list
            )
        }
        if (waitMs <= 0L) {
            return DeviceApiResponse.ok(wrap(commandService.pendingForPolling(deviceId, since)))
        }
        val capped = waitMs.coerceIn(1L, CommandService.MAX_WAIT_MS)
        val deferred = DeferredResult<Any>(capped)
        val onReady: (List<CommandPacket>) -> Unit = { list ->
            runCatching { deferred.setResult(DeviceApiResponse.ok(wrap(list))) }
        }
        // 超时回空列表而不是报错：对终端而言与「这一轮没有指令」等价，不会触发错误退避
        deferred.onTimeout {
            commandService.removeWaiter(deviceId, onReady)
            runCatching { deferred.setResult(DeviceApiResponse.ok(wrap(emptyList()))) }
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
            file.originalFilename ?: "avatar.jpg",
            deviceId = deviceId
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
        val url = fileStorageService.store(
            file.contentType ?: "image/jpeg", file.bytes, file.originalFilename ?: "shot.jpg",
            deviceId = deviceId
        )
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
        val url = fileStorageService.store(mime, file.bytes, file.originalFilename ?: "media.bin", deviceId = deviceId)
        monitorService.finishMediaTask(taskId, deviceId, url, mime, file.size, durationSeconds ?: 0)
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
