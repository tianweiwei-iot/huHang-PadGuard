package com.padguard.server.controller

import com.padguard.server.common.DeviceApiResponse
import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ChildErr
import com.padguard.server.config.TraceIds
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
        // 权益闸门：订阅失效/恢复的翻转沿在此触发"放开/恢复"策略重建（见 syncEntitlement）。
        policyExtensionService.syncEntitlement(deviceId)
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
    private val policyExtensionService: PolicyExtensionService,
    private val fileStorageService: FileStorageService,
    private val monitorService: MonitorService,
    private val appManageService: AppManageService,
    private val childUplinkHandler: ChildUplinkHandler,
    private val alertService: AlertService
) {
    /**
     * 三条孩子端上传入口共用的类型闸门。
     *
     * 之前这里直接信任客户端声明的 contentType：设备令牌持有者上传 `image/svg+xml`，
     * 下载侧按 image 通配类型会 inline 渲染，与 /admin 同源执行脚本。
     * 规则与家长入口共用 [FileStorageService] 的单一来源，避免只堵一边。
     */
    private fun safeUploadMime(file: MultipartFile, fallbackMime: String): String {
        val mime = FileStorageService.resolveMime(file.originalFilename, file.contentType ?: fallbackMime)
        FileStorageService.unsafeUploadReason(file.originalFilename, mime)?.let { reason ->
            throw BizException(ChildErr.PARAM_ERROR, reason, Audience.CHILD)
        }
        return mime
    }


    /**
     * 孩子端自愈成功后关闭对应类别的 ACTIVE 告警（恢复即关闭闭环）。
     *
     * ## 为何需要
     * 此前告警只能由家长手动关闭。DO 设备静默重授予权限后，服务端 ACTIVE 告警仍长期挂起，
     * 家长端持续提示"权限被撤销 / 无障碍已关闭"——"修好了还在报警"。
     * 孩子端在权限自愈成功时调用本接口，形成"自愈成功 → 告警消失"的闭环。
     *
     * ## 安全约束
     * - deviceId 一律取鉴权注入值，禁止报文伪造（防越权关闭他人告警）；
     * - category 限定白名单 [RESOLVABLE_ALERT_CATEGORIES]，孩子端只能关闭「确会自行恢复的状态型告警」，
     *   无法借此关闭越界 / 卸载 / root 等事件型或需人工介入的其它告警。
     */
    @PostMapping("/alerts/resolve")
    fun resolveAlerts(
        @RequestAttribute("deviceId") deviceId: String,
        @RequestBody req: AlertResolveRequest
    ): DeviceApiResponse<*> {
        // 空列表是"无意义成功"：不触发任何校验/写入却返回 200 resolved=0，
        // 端上可能误判为"已处理"。显式拒绝，让调用方拿到明确失败而非静默空操作。
        if (req.categories.isEmpty()) {
            throw BizException(ChildErr.PARAM_ERROR, "告警类别列表不能为空", Audience.CHILD)
        }
        for (category in req.categories) {
            if (category !in RESOLVABLE_ALERT_CATEGORIES) {
                throw BizException(ChildErr.PARAM_ERROR, "不被允许的告警类别: $category", Audience.CHILD)
            }
            alertService.resolveActiveByCategory(deviceId, category, req.reason ?: "已自动恢复")
        }
        return DeviceApiResponse.ok(mapOf("resolved" to req.categories.size))
    }

    private companion object {
        /**
         * 孩子端可自行关闭的**状态型**告警类别（自愈成功后回落）。
         *
         * ## 必须与孩子端 resolve 产出侧全量对齐
         * 两端口径分处两个独立 Gradle 工程，编译器无法互相约束。历史事故：
         * 孩子端 `PermissionHealthChecker.clearLockScreenUnprotected()` 早就传了
         * `LOCK_SCREEN_UNPROTECTED`，本白名单却漏了它 → 服务端 400，而端上把
         * `ApiResult.BizError` 当成功（400 不抛异常、`runCatching` 静默）→
         * 「锁屏已恢复，家长端『锁屏未能生效』告警却永久挂着」。
         *
         * 新增/修改本集合必须同步 `AlertResolveContractCoverageTest`：它会扫描孩子端
         * `PermissionHealthChecker` 实际产出的类别并做双向比对，单边改动会直接红。
         *
         * 刻意**不含**事件型（UNINSTALL_ATTEMPT / BLACKLIST_APP_LAUNCH / CLOCK_TAMPERING …）
         * 与需人工介入型（ROOT_DETECTED）：这些不该被设备端批量关掉。
         */
        val RESOLVABLE_ALERT_CATEGORIES = setOf(
            "ACCESSIBILITY_DISABLED",
            "GUARD_PERMISSION_DROPPED",
            "PERMISSION_REVOKED",
            // 锁屏自愈闭环：孩子端 clearLockScreenUnprotected 的产出（曾被 400 静默拒绝）
            "LOCK_SCREEN_UNPROTECTED",
            // 使用情况访问恢复：TamperDetector 上报 USAGE_ACCESS_LOST，
            // 由 computeResolveCategories 在 usageStats 从掉失到恢复时产出
            "USAGE_ACCESS_LOST",
            // 网页防护隧道重建成功：孩子端 GuardService.watchdogWebGuard 在隧道恢复时产出
            "WEB_GUARD_DOWN",
            // 操作密码经策略设置：孩子端 GuardService.applyGuardPassword 在设密成功时产出
            "GUARD_PASSWORD_NOT_SET"
        )
    }

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
        // 权益闸门：孩子端每 30s 心跳必经此处，订阅失效/恢复在翻转沿触发策略重建。
        // 没有这个挂点，到期后无人触发重建（家长端接口已被订阅拦截器挡住），管控会冻结在最后状态。
        policyExtensionService.syncEntitlement(deviceId)
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
        // 挂起期间孩子端在等这一个响应、不会再发任何 HTTP 请求，而在线态按「秒级无信号」
        // 判离线。不登记宽限的话，每轮 20s 长轮询必被判一次 OFFLINE，返回后又立刻 ONLINE，
        // 家长端就是「一会儿上线一会儿掉线」。挂起中的连接本身就是最强存活证据。
        // 宽限是绝对到期时刻，即便本次回调没触发也会自动失效，不会把设备永久钉在在线。
        deviceService.holdPresence(deviceId, capped)
        // 完成回调由「唤醒者线程」触发：MQTT 下行、家长下发请求或超时定时器，都不是本次
        // HTTP 请求的容器线程，MDC 天然为空（或带着唤醒链路自己的 mqtt- ID）。在请求线程
        // 捕获 ID 再显式传进去，才能把「家长点锁屏 → 这次长轮询把指令带回平板」串成一条链路。
        // withTrace 会恢复旧值，不会抹掉唤醒者原有的 traceId。
        val requestTrace = TraceIds.current() ?: TraceIds.newId("poll")
        val deferred = DeferredResult<Any>(capped)
        val onReady: (List<CommandPacket>) -> Unit = { list ->
            TraceIds.withTrace(requestTrace) {
                runCatching { deferred.setResult(DeviceApiResponse.ok(wrap(list))) }
            }
        }
        // 超时回空列表而不是报错：对终端而言与「这一轮没有指令」等价，不会触发错误退避
        deferred.onTimeout {
            TraceIds.withTrace(requestTrace) {
                commandService.removeWaiter(deviceId, onReady)
                runCatching { deferred.setResult(DeviceApiResponse.ok(wrap(emptyList()))) }
            }
        }
        // 正常完成 / 超时 / 客户端断开都必须摘除回调，否则队列随断连无限增长
        deferred.onCompletion {
            TraceIds.withTrace(requestTrace) { commandService.removeWaiter(deviceId, onReady) }
        }
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
        val url = fileStorageService.storeStream(
            safeUploadMime(file, "image/jpeg"),
            file.inputStream,
            file.originalFilename ?: "avatar.jpg",
            deviceId = deviceId
        ).url
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
        val url = fileStorageService.storeStream(
            safeUploadMime(file, "image/jpeg"), file.inputStream, file.originalFilename ?: "shot.jpg",
            deviceId = deviceId
        ).url
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
        val mime = safeUploadMime(file, "application/octet-stream")
        // 录屏文件不比 APK 小，统一走流式落盘，避免"孩子端传一段课堂录像就把服务端打挂"
        val url = fileStorageService.storeStream(
            mime, file.inputStream, file.originalFilename ?: "media.bin", deviceId = deviceId
        ).url
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
