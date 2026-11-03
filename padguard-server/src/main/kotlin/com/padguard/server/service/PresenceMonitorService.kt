package com.padguard.server.service

import com.padguard.server.repository.DeviceRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import jakarta.annotation.PostConstruct

/**
 * 秒级在线态收敛：把"孩子端不再上报信号"尽快翻译成家长端可见的「离线」。
 *
 * ## 与 [OfflineMonitorService] 的分工（两者不再重叠）
 * | 关注点 | 本服务 | [OfflineMonitorService] |
 * |---|---|---|
 * | 目标 | **状态准确**：在线/离线何时翻转 | **风险通知**：脱管多久才值得打扰家长 |
 * | 阈值 | ≈5 秒（[DevicePresenceRegistry] 自适应） | 持续离线 60 秒 |
 * | 动作 | 置 OFFLINE + 推 `device.offline` WS | 建 `CONTROL_LOST` 告警 |
 *
 * 拆开的原因：状态必须快（家长打开 App 就要看到真实情况），告警必须稳
 * （一次 6 秒的网络抖动弹一条"管控已失效"，家长会失去对告警的信任）。
 * 改造前两者绑在同一个 2 分钟阈值上，结果是"要快的时候慢、不该报的时候报"。
 *
 * ## 成本
 * 每 [scanIntervalMs] 毫秒遍历一次**内存中的在线设备表**（离线设备已被摘除），
 * 不做任何 SQL；只有真的发生翻转的设备才会读写一次 `devices` 行并推送一条 WS。
 * 因此稳态下（设备都在线且没掉线）这个定时任务的开销近似为"每秒一次空转 + N 次比较"。
 */
@Service
class PresenceMonitorService(
    private val registry: DevicePresenceRegistry,
    private val deviceRepository: DeviceRepository,
    private val deviceService: DeviceService,
    @Value("\${padguard.presence.scan-interval-ms:1000}") private val scanIntervalMs: Long = 1_000
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 启动预热：把库里标记为 ONLINE 的设备灌进内存表。
     *
     * 服务端重启后内存表是空的。若不预热，一台"重启前已关机"的设备会一直停留在
     * 库里的 ONLINE，且因为不在内存表里而永远不会被扫描到 —— 又退回分钟级（甚至永久）。
     * 预热后它们会在第一轮扫描里按 `last_seen_at` 立刻被判为离线。
     */
    @PostConstruct
    fun warmUp() {
        runCatching {
            val online = deviceRepository.findByOnlineStatus("ONLINE")
            online.forEach { dev -> registry.seed(dev.id, dev.lastOnlineAt ?: 0L) }
            if (online.isNotEmpty()) log.info("presence warm-up: seeded {} device(s) marked ONLINE", online.size)
        }.onFailure { log.warn("presence warm-up failed (device presence will rebuild on next signal): {}", it.message) }
    }

    @Scheduled(fixedDelayString = "\${padguard.presence.scan-interval-ms:1000}")
    fun scan() {
        val timedOut = runCatching { registry.takeTimedOut() }
            .onFailure { log.error("presence scan failed", it) }
            .getOrDefault(emptyList())
        if (timedOut.isEmpty()) return
        for (id in timedOut) {
            runCatching {
                log.warn("device {} marked OFFLINE (no child signal beyond presence timeout)", id)
                deviceService.setOffline(id)
            }.onFailure { log.error("failed to mark device {} offline", id, it) }
        }
    }

    init {
        log.info("presence monitor scan interval = {} ms", scanIntervalMs)
    }
}
