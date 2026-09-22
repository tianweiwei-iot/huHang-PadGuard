package com.padguard.server.controller

import com.padguard.server.common.DeviceApiResponse
import com.padguard.server.dto.*
import com.padguard.server.service.*
import com.padguard.server.mqtt.ChildUplinkHandler
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*
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

    @PostMapping("/heartbeat")
    fun heartbeat(
        @RequestAttribute("deviceId") deviceId: String,
        @RequestBody hb: HeartbeatDto
    ): DeviceApiResponse<*> {
        deviceService.applyHeartbeat(deviceId, hb)
        return DeviceApiResponse.ok(null)
    }

    /** MQTT 降级轮询通道：返回设备待执行指令包 */
    @GetMapping("/commands")
    fun commands(
        @RequestAttribute("deviceId") deviceId: String,
        @RequestParam since: Long = 0
    ): DeviceApiResponse<*> = DeviceApiResponse.ok(commandService.pendingForPolling(deviceId, since))

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
