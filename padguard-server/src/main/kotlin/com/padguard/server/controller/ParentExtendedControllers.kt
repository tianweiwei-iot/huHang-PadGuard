package com.padguard.server.controller

import com.padguard.server.common.ApiResponse
import com.padguard.server.dto.*
import com.padguard.server.service.*
import org.springframework.web.bind.annotation.*

// ==================== 实时监控 ====================
@RestController
@RequestMapping("/v1/monitor")
class ParentMonitorController(
    private val monitorService: MonitorService,
    private val geofenceService: GeofenceService
) {

    @PostMapping("/{deviceId}/screenshot")
    fun screenshot(@RequestAttribute("userId") userId: String, @PathVariable deviceId: String) =
        ApiResponse.ok(monitorService.requestScreenshot(userId, deviceId))

    @GetMapping("/{deviceId}/screenshots")
    fun screenshots(@RequestAttribute("userId") userId: String, @PathVariable deviceId: String, @RequestParam(required = false, defaultValue = "20") limit: Int) =
        ApiResponse.ok(monitorService.listScreenshots(userId, deviceId, limit))

    @GetMapping("/{deviceId}/location")
    fun location(@RequestAttribute("userId") userId: String, @PathVariable deviceId: String) =
        ApiResponse.ok(geofenceService.currentLocation(deviceId))

    @PostMapping("/{deviceId}/photo")
    fun photo(@RequestAttribute("userId") userId: String, @PathVariable deviceId: String) =
        ApiResponse.ok(monitorService.requestPhoto(userId, deviceId))

    @PostMapping("/{deviceId}/record/start")
    fun recordStart(@RequestAttribute("userId") userId: String, @PathVariable deviceId: String) =
        ApiResponse.ok(monitorService.startRecording(userId, deviceId))

    @PostMapping("/{deviceId}/record/stop")
    fun recordStop(@RequestAttribute("userId") userId: String, @PathVariable deviceId: String) =
        ApiResponse.ok(monitorService.stopByKind(userId, deviceId, "RECORD"))

    @PostMapping("/{deviceId}/screen-record/start")
    fun screenRecordStart(@RequestAttribute("userId") userId: String, @PathVariable deviceId: String, @RequestBody req: StartRecordRequest) =
        ApiResponse.ok(monitorService.startScreenRecord(userId, deviceId, req))

    @PostMapping("/{deviceId}/screen-record/{taskId}/stop")
    fun screenRecordStop(@RequestAttribute("userId") userId: String, @PathVariable deviceId: String, @PathVariable taskId: String) =
        ApiResponse.ok(monitorService.stopScreenRecord(userId, taskId))

    /**
     * 查询媒体任务（录屏 / 录音）状态与文件地址。
     *
     * 停止录屏后文件是孩子端异步上传的，家长端需轮询到这里返回 status=READY 才能拿到 url 播放。
     */
    @GetMapping("/{deviceId}/media/{taskId}")
    fun mediaTask(@RequestAttribute("userId") userId: String, @PathVariable deviceId: String, @PathVariable taskId: String) =
        ApiResponse.ok(monitorService.getMediaTask(userId, taskId))

    /** 开启实时看屏推流（观看期间需周期性重发做续期） */
    @PostMapping("/{deviceId}/live/start")
    fun liveStart(@RequestAttribute("userId") userId: String, @PathVariable deviceId: String) =
        ApiResponse.ok(monitorService.startLiveView(userId, deviceId))

    @PostMapping("/{deviceId}/live/stop")
    fun liveStop(@RequestAttribute("userId") userId: String, @PathVariable deviceId: String) =
        ApiResponse.ok(monitorService.stopLiveView(userId, deviceId))

    @GetMapping("/{deviceId}/screen-settings")
    fun screenSettings(@RequestAttribute("userId") userId: String, @PathVariable deviceId: String) =
        ApiResponse.ok(monitorService.getScreenSettings(userId, deviceId))

    @PutMapping("/{deviceId}/screen-settings")
    fun updateScreenSettings(@RequestAttribute("userId") userId: String, @PathVariable deviceId: String, @RequestBody req: ScreenMonitorSettingsDto) =
        ApiResponse.ok(monitorService.updateScreenSettings(userId, deviceId, req))
}

// ==================== 信息发布 ====================
@RestController
@RequestMapping("/v1/messages")
class ParentMessageController(private val messageService: MessageService) {

    @PostMapping("/{deviceId}/publish")
    fun publish(@RequestAttribute("userId") userId: String, @PathVariable deviceId: String, @RequestBody req: MessagePublishRequest) =
        ApiResponse.ok(messageService.publish(userId, deviceId, req))

    @GetMapping("/{deviceId}/history")
    fun history(@RequestAttribute("userId") userId: String, @PathVariable deviceId: String, @RequestParam(required = false, defaultValue = "20") limit: Int) =
        ApiResponse.ok(messageService.listMessages(userId, deviceId, limit))
}

// ==================== 定位 / 电子围栏 ====================
@RestController
@RequestMapping("/v1/location")
class ParentLocationController(private val geofenceService: GeofenceService) {

    @GetMapping("/{deviceId}/current")
    fun current(@RequestAttribute("userId") userId: String, @PathVariable deviceId: String) =
        ApiResponse.ok(geofenceService.currentLocation(deviceId))

    @GetMapping("/{deviceId}/geofence")
    fun geofence(@RequestAttribute("userId") userId: String, @PathVariable deviceId: String) =
        ApiResponse.ok(geofenceService.getGeofence(deviceId))

    @PutMapping("/{deviceId}/geofence")
    fun updateGeofence(@RequestAttribute("userId") userId: String, @PathVariable deviceId: String, @RequestBody req: GeofenceDto) =
        ApiResponse.ok(geofenceService.updateGeofence(deviceId, req))

    @GetMapping("/{deviceId}/track")
    fun track(@RequestAttribute("userId") userId: String, @PathVariable deviceId: String, @RequestParam(required = false, defaultValue = "200") limit: Int) =
        ApiResponse.ok(geofenceService.trackHistory(deviceId, limit))
}

// ==================== 策略扩展（时段/应用/上网/模板） ====================
@RestController
@RequestMapping("/v1/policies")
class ParentPolicyExtendedController(private val policyExtensionService: PolicyExtensionService) {

    @GetMapping("/{deviceId}/time-restrictions")
    fun timeRestrictions(@RequestAttribute("userId") userId: String, @PathVariable deviceId: String) =
        ApiResponse.ok(policyExtensionService.listTimeRestrictions(userId, deviceId))

    @PostMapping("/{deviceId}/time-restrictions")
    fun addTimeRestriction(@RequestAttribute("userId") userId: String, @PathVariable deviceId: String, @RequestBody req: TimeRestrictionDto) =
        ApiResponse.ok(policyExtensionService.addTimeRestriction(userId, deviceId, req))

    @DeleteMapping("/time-restrictions/{id}")
    fun deleteTimeRestriction(@RequestAttribute("userId") userId: String, @RequestParam deviceId: String, @PathVariable id: String) =
        ApiResponse.ok<Any?>(run { policyExtensionService.deleteTimeRestriction(userId, deviceId, id); null })

    @GetMapping("/{deviceId}/apps")
    fun apps(@RequestAttribute("userId") userId: String, @PathVariable deviceId: String) =
        ApiResponse.ok(policyExtensionService.listAppPolicies(userId, deviceId))

    @PutMapping("/{deviceId}/apps/blacklist")
    fun appBlacklist(@RequestAttribute("userId") userId: String, @PathVariable deviceId: String, @RequestBody req: UpdateBlacklistRequest) =
        ApiResponse.ok<Any?>(run { policyExtensionService.setAppBlacklist(userId, deviceId, req); null })

    @PutMapping("/{deviceId}/apps/limit")
    fun appLimit(@RequestAttribute("userId") userId: String, @PathVariable deviceId: String, @RequestBody req: DailyLimitRequest) =
        ApiResponse.ok<Any?>(run { policyExtensionService.setAppLimit(userId, deviceId, req); null })

    @GetMapping("/{deviceId}/daily-limit")
    fun dailyLimit(@RequestAttribute("userId") userId: String, @PathVariable deviceId: String) =
        ApiResponse.ok(policyExtensionService.getDailyLimit(userId, deviceId))

    @GetMapping("/{deviceId}/web")
    fun web(@RequestAttribute("userId") userId: String, @PathVariable deviceId: String) =
        ApiResponse.ok(policyExtensionService.getWebPolicy(userId, deviceId))

    @PutMapping("/{deviceId}/web/urls")
    fun webUrls(@RequestAttribute("userId") userId: String, @PathVariable deviceId: String, @RequestBody req: UrlBlacklistRequest) =
        ApiResponse.ok<Any?>(run { policyExtensionService.updateWebUrls(userId, deviceId, req); null })

    @PutMapping("/{deviceId}/web/browser")
    fun webBrowser(@RequestAttribute("userId") userId: String, @PathVariable deviceId: String, @RequestBody req: BrowserDisableRequest) =
        ApiResponse.ok<Any?>(run { policyExtensionService.updateBrowser(userId, deviceId, req); null })

    @GetMapping("/templates")
    fun templates(@RequestParam(required = false) sceneType: String?) =
        ApiResponse.ok(policyExtensionService.listTemplates(sceneType))

    @PostMapping("/{deviceId}/apply-template")
    fun applyTemplate(@RequestAttribute("userId") userId: String, @PathVariable deviceId: String, @RequestBody req: ApplyTemplateRequest) =
        ApiResponse.ok<Any?>(run { policyExtensionService.applyTemplate(userId, deviceId, req); null })

    @GetMapping("/{deviceId}/tablet-usage-settings")
    fun tabletUsageSettings(@RequestAttribute("userId") userId: String, @PathVariable deviceId: String) =
        ApiResponse.ok(policyExtensionService.getTabletUsageSettings(userId, deviceId))

    @PutMapping("/{deviceId}/tablet-usage-settings")
    fun updateTabletUsageSettings(
        @RequestAttribute("userId") userId: String, @PathVariable deviceId: String,
        @RequestBody req: TabletUsageSettingsDto
    ) = ApiResponse.ok(policyExtensionService.updateTabletUsageSettings(userId, deviceId, req))

    // ---------- 救援通道 ----------
    // 平板被策略封死调试与侧载后，唯一能给它"松绑"的只有它自己（Device Owner）。
    // 这里把松绑动作做成服务端指令：孩子端下次拉策略即解封，之后才能侧载新版 APK
    // 或打开 USB 调试被 adb 接管 —— 否则「要升级必须先升级」是死锁。
    @GetMapping("/{deviceId}/system-lock-relaxed")
    fun systemLockRelaxed(
        @RequestAttribute("userId") userId: String, @PathVariable deviceId: String
    ) = ApiResponse.ok(mapOf("relaxed" to policyExtensionService.getSystemLockRelaxed(userId, deviceId)))

    @PutMapping("/{deviceId}/system-lock-relaxed")
    fun setSystemLockRelaxed(
        @RequestAttribute("userId") userId: String, @PathVariable deviceId: String,
        @RequestBody req: SystemLockRelaxedRequest
    ) = ApiResponse.ok(mapOf("relaxed" to policyExtensionService.setSystemLockRelaxed(userId, deviceId, req.relaxed)))
}

// ==================== 应用监控 / 远程安装维护 ====================
@RestController
@RequestMapping("/v1/devices/{deviceId}/apps")
class ParentAppManageController(private val appManageService: AppManageService) {

    @GetMapping
    fun list(
        @RequestAttribute("userId") userId: String,
        @PathVariable deviceId: String,
        @RequestParam(required = false, defaultValue = "true") onlyInstalled: Boolean
    ) = ApiResponse.ok(appManageService.listApps(userId, deviceId, onlyInstalled))

    @PostMapping("/install")
    fun install(
        @RequestAttribute("userId") userId: String,
        @PathVariable deviceId: String,
        @RequestBody req: AppInstallRequest
    ) = ApiResponse.ok(appManageService.installApp(userId, deviceId, req))

    @PostMapping("/uninstall")
    fun uninstall(
        @RequestAttribute("userId") userId: String,
        @PathVariable deviceId: String,
        @RequestBody req: SuspendRequest
    ): ApiResponse<*> {
        appManageService.uninstallApp(userId, deviceId, req.packageName)
        return ApiResponse.ok<Any?>(null)
    }

    @PutMapping("/suspend")
    fun suspend(
        @RequestAttribute("userId") userId: String,
        @PathVariable deviceId: String,
        @RequestBody req: SuspendRequest
    ): ApiResponse<*> {
        appManageService.setSuspended(userId, deviceId, req.packageName, req.suspended)
        return ApiResponse.ok<Any?>(null)
    }

    /** 使用权限开关：开启 = 应用出现在孩子端桌面；关闭 = 隐藏（不是卸载） */
    @PutMapping("/hidden")
    fun hidden(
        @RequestAttribute("userId") userId: String,
        @PathVariable deviceId: String,
        @RequestBody req: AppHiddenRequest
    ): ApiResponse<*> {
        appManageService.setAppHidden(userId, deviceId, req)
        return ApiResponse.ok<Any?>(null)
    }

    /** 批量设置应用使用权限（隐藏 / 显示）。`all=true` 作用于全部已安装应用。 */
    @PutMapping("/hidden/batch")
    fun hiddenBatch(
        @RequestAttribute("userId") userId: String,
        @PathVariable deviceId: String,
        @RequestBody req: AppHiddenBatchRequest
    ) = ApiResponse.ok(mapOf("sent" to appManageService.setAppsHiddenBatch(userId, deviceId, req)))

    /** 设置单个应用的每日使用时长上限（分钟）。 */
    @PutMapping("/{packageName}/limit")
    fun appLimit(
        @RequestAttribute("userId") userId: String,
        @PathVariable deviceId: String,
        @PathVariable packageName: String,
        @RequestBody req: AppLimitRequest
    ): ApiResponse<*> {
        appManageService.setAppLimit(userId, deviceId, packageName, req.minutes)
        return ApiResponse.ok<Any?>(null)
    }

    @PutMapping("/suspend/batch")
    fun suspendBatch(
        @RequestAttribute("userId") userId: String,
        @PathVariable deviceId: String,
        @RequestBody req: BatchSuspendRequest
    ) = ApiResponse.ok(mapOf("sent" to appManageService.setSuspendedBatch(userId, deviceId, req.packages, req.suspended)))
}

// ==================== 数据统计 ====================
@RestController
@RequestMapping("/v1/statistics")
class ParentStatisticsController(private val statisticsService: StatisticsService) {

    @GetMapping("/{deviceId}/usage")
    fun usage(@RequestAttribute("userId") userId: String, @PathVariable deviceId: String, @RequestParam(required = false, defaultValue = "daily") period: String) =
        ApiResponse.ok(statisticsService.usage(userId, deviceId, period))

    @GetMapping("/{deviceId}/today")
    fun today(@RequestAttribute("userId") userId: String, @PathVariable deviceId: String) =
        ApiResponse.ok(statisticsService.today(userId, deviceId))

    @GetMapping("/{deviceId}/web")
    fun web(@RequestAttribute("userId") userId: String, @PathVariable deviceId: String, @RequestParam(required = false, defaultValue = "daily") period: String) =
        ApiResponse.ok(statisticsService.web(userId, deviceId, period))

    @GetMapping("/{deviceId}/violations")
    fun violations(@RequestAttribute("userId") userId: String, @PathVariable deviceId: String, @RequestParam(required = false, defaultValue = "weekly") period: String) =
        ApiResponse.ok(statisticsService.violations(userId, deviceId, period))

    @GetMapping("/{deviceId}/export")
    fun export(@RequestAttribute("userId") userId: String, @PathVariable deviceId: String, @RequestParam(required = false, defaultValue = "monthly") period: String) =
        ApiResponse.ok(statisticsService.export(userId, deviceId, period))
}

// ==================== 风险预警 ====================
@RestController
@RequestMapping("/v1/alerts")
class ParentAlertController(private val alertService: AlertService) {

    @GetMapping
    fun list(
        @RequestAttribute("userId") userId: String,
        @RequestParam(required = false) deviceId: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false, defaultValue = "0") page: Int,
        @RequestParam(required = false, defaultValue = "20") size: Int
    ) = ApiResponse.ok(alertService.list(userId, deviceId, status, page, size))

    @GetMapping("/unread-count")
    fun unread(@RequestAttribute("userId") userId: String) =
        ApiResponse.ok(mapOf("count" to alertService.unreadCount(userId)))

    @PostMapping("/{alertId}/acknowledge")
    fun acknowledge(@RequestAttribute("userId") userId: String, @PathVariable alertId: String) =
        ApiResponse.ok<Any?>(run { alertService.acknowledge(userId, alertId); null })

    @PostMapping("/{alertId}/close")
    fun close(@RequestAttribute("userId") userId: String, @PathVariable alertId: String, @RequestBody(required = false) req: CloseAlertRequest?) =
        ApiResponse.ok<Any?>(run { alertService.close(userId, alertId, req?.reason); null })
}

// ==================== 临时解锁工单（§9） ====================
@RestController
@RequestMapping("/v1/devices")
class ParentUnlockController(private val unlockService: UnlockService) {

    @GetMapping("/{deviceId}/unlock-tickets")
    fun tickets(@RequestAttribute("userId") userId: String, @PathVariable deviceId: String) =
        ApiResponse.ok(unlockService.listTickets(userId, deviceId))

    @PostMapping("/{deviceId}/unlock-tickets/{ticketId}/approve")
    fun approve(@RequestAttribute("userId") userId: String, @PathVariable deviceId: String, @PathVariable ticketId: String, @RequestBody(required = false) req: UnlockApproveRequest?) =
        ApiResponse.ok<Any?>(run { unlockService.approve(userId, deviceId, ticketId, req ?: UnlockApproveRequest()); null })

    @PostMapping("/{deviceId}/unlock-tickets/{ticketId}/reject")
    fun reject(@RequestAttribute("userId") userId: String, @PathVariable deviceId: String, @PathVariable ticketId: String, @RequestBody(required = false) req: UnlockRejectRequest?) =
        ApiResponse.ok<Any?>(run { unlockService.reject(userId, deviceId, ticketId, req ?: UnlockRejectRequest()); null })

    /**
     * 忽略：关掉弹窗、归档进消息中心，不下发消息给孩子，工单保留为可批准状态。
     * 与 reject 的区别见 [com.padguard.server.service.UnlockService.dismiss]。
     */
    @PostMapping("/{deviceId}/unlock-tickets/{ticketId}/dismiss")
    fun dismiss(@RequestAttribute("userId") userId: String, @PathVariable deviceId: String, @PathVariable ticketId: String) =
        ApiResponse.ok<Any?>(run { unlockService.dismiss(userId, deviceId, ticketId); null })
}
