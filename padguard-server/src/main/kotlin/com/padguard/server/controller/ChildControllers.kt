package com.padguard.server.controller

import com.padguard.server.common.DeviceApiResponse
import com.padguard.server.dto.*
import com.padguard.server.service.*
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/v1/device")
class ChildDeviceController(
    private val deviceService: DeviceService,
    private val policyService: PolicyService
) {
    @PostMapping("/bind")
    fun bind(@RequestBody req: BindRequest) = DeviceApiResponse.ok(deviceService.bindChild(req))

    @GetMapping("/time")
    fun time() = DeviceApiResponse.ok(mapOf("serverTime" to System.currentTimeMillis()))

    @GetMapping("/policy")
    fun policy(
        @RequestParam currentVersion: Int,
        @RequestAttribute("deviceId") deviceId: String
    ) = DeviceApiResponse.ok(policyService.getForChild(deviceId, currentVersion))
}

@RestController
@RequestMapping("/api/v1/device")
class ChildReportController(
    private val ingestService: IngestService,
    private val deviceService: DeviceService,
    private val commandService: CommandService,
    private val fileStorageService: FileStorageService,
    private val monitorService: MonitorService
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
        return DeviceApiResponse.ok(mapOf("accepted" to 1, "url" to url))
    }
}
