package com.padguard.server.service

import com.padguard.server.repository.DeviceRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

/**
 * 管控连接中断监测。
 *
 * ## 背景：家长端「看不见管控失效」是真实痛点
 * 孩子端进程被杀 / 强制停止 / 断网后，[DeviceService.effectiveOnlineStatus] 虽会在
 * 超过 [DeviceService.Companion] 阈值（2 分钟）无心跳时把状态惰性判为 OFFLINE，
 * 但那只是「读时判离线、不回写、不推事件」——家长端只是悄悄变灰，**收不到任何告警**，
 * 于是出现「孩子早把管控结束了，家长还以为在管控」的失控窗口。
 *
 * 本服务每 30 秒扫一次：对仍是 ONLINE、却已超过阈值未上报心跳的设备，
 * 调 [DeviceService.setOffline]（置 OFFLINE + 推 `device.offline` WS）并额外建一条
 * `CONTROL_LOST` 告警推给家长 —— 让「管控失效」从「静默变灰」变成「主动通知」。
 *
 * ## 去重
 * [DeviceService.setOffline] 仅在 ONLINE→OFFLINE 时生效并改写状态，
 * 改写后本服务的下一轮扫描会跳过该设备，自然保证「一次脱管只告警一次」，
 * 直到设备重新上线（[DeviceService.applyHeartbeatOnline] 置回 ONLINE）后若再次掉线才会再告警。
 *
 * ## 为什么不用 MQTT 断线直接告警
 * MQTT 断线可能是瞬断（秒级重连），直接告警会产生大量误报；
 * 用「2 分钟无心跳」的阈值做最终判定，与 [DeviceService] 的在线口径完全一致，避免双重标准。
 */
@Service
class OfflineMonitorService(
    private val deviceRepository: DeviceRepository,
    private val deviceService: DeviceService,
    private val alertService: AlertService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 必须与 [DeviceService.Companion.ONLINE_STALE_MS] 保持一致（后者为 private，此处镜像一份）。 */
    private val ONLINE_STALE_MS = 120_000L

    @Scheduled(fixedDelay = 30_000L)
    fun scan() {
        val staleBefore = System.currentTimeMillis() - ONLINE_STALE_MS
        runCatching {
            deviceRepository.findAll().forEach { dev ->
                val last = dev.lastOnlineAt ?: return@forEach
                if (dev.onlineStatus == "ONLINE" && last < staleBefore) {
                    log.warn("device {} control lost (no heartbeat since {}), notifying parent", dev.id, last)
                    deviceService.setOffline(dev.id)
                    alertService.create(
                        deviceId = dev.id,
                        level = "WARNING",
                        title = "管控连接中断",
                        message = "孩子端已超过一段时间未上报心跳，管控可能已失效。请确认设备是否在线、是否被强制停止或卸载。",
                        category = "CONTROL_LOST"
                    )
                }
            }
        }.onFailure { log.error("offline monitor scan failed", it) }
    }
}
