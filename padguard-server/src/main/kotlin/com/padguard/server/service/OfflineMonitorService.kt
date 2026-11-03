package com.padguard.server.service

import com.padguard.server.repository.DeviceRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * 管控连接中断的**告警**判定（注意：不负责在线态本身）。
 *
 * ## 职责边界已重划
 * 改造前，"在线态翻转"和"脱管告警"共用同一把 2 分钟尺子：本服务每 30 秒扫一次，
 * 发现 `ONLINE` 且心跳超过 2 分钟的设备就 [DeviceService.setOffline] 并建告警。
 * 后果是两件事互相拖累：
 * - 家长只要"看一眼设备在不在线"也要等 2 分钟（状态被告警阈值绑架）；
 * - 真掉线时又只能靠 30 秒扫描 + 2 分钟阈值，最坏 2.5 分钟才弹告警。
 *
 * 现在职责拆开：
 * - 在线态 → [PresenceMonitorService]（约 5 秒，只改状态、推 WS，不打扰家长）；
 * - 告警 → 本服务（设备**持续**离线 [CONTROL_LOST_MS] 才建 `CONTROL_LOST` 告警）。
 *
 * 为什么告警必须比状态慢得多：孩子端 6 秒的网络抖动、切 Wi-Fi、锁屏瞬间的 TCP 重连
 * 都会让状态闪一下。状态闪一下无所谓（自己会回来），但一条"管控可能已失效"的推送
 * 会让家长半夜去翻平板；误报几次之后，真故障的那一条也不会被当真了。
 *
 * ## 去重
 * [alerted] 记录"已经为哪些设备发过脱管告警"，设备重新上线时移除，
 * 从而保证「一次脱管只告警一次」，重新上线后再次掉线才会再告警。
 * 用内存集合而非数据库列：告警去重不需要跨重启精确，
 * 服务端重启后若设备仍离线会再提醒一次 —— 这反而是期望行为（重启期间家长可能没看到）。
 */
@Service
class OfflineMonitorService(
    private val deviceRepository: DeviceRepository,
    private val deviceService: DeviceService,
    private val alertService: AlertService,
    private val presence: DevicePresenceRegistry
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 已告警的设备（deviceId），设备重新上线后移除 */
    private val alerted: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Scheduled(fixedDelay = 30_000L)
    fun scan() {
        val now = System.currentTimeMillis()
        val staleBefore = now - CONTROL_LOST_MS

        // 重新上线的设备允许下次再告警。只移除"当前在线"的那些，
        // 离线且已告警的必须留着，否则每轮扫描都会重复发同一条告警。
        runCatching { alerted.removeAll(presence.onlineIds()) }
            .onFailure { log.warn("clear alerted set failed: {}", it.message) }

        runCatching {
            // ---------- 脱管告警：ONLINE 设备已持续离线超过阈值 ----------
            deviceRepository.findByOnlineStatusAndLastOnlineAtLessThan("OFFLINE", staleBefore).forEach { dev ->
                if (!alerted.add(dev.id)) return@forEach
                log.warn("device {} control lost (offline for > {} ms), notifying parent", dev.id, CONTROL_LOST_MS)
                alertService.create(
                    deviceId = dev.id,
                    level = "WARNING",
                    title = "管控连接中断",
                    message = "孩子端已超过一段时间未上报心跳，管控可能已失效。请确认设备是否在线、是否被强制停止或卸载。",
                    category = "CONTROL_LOST"
                )
            }

            // ---------- T3 守护失联：设备在线，但守护进程已停 ----------
            // 判定条件与改造前一致，只是"心跳新鲜"的窗口从 2 分钟改为 [HEARTBEAT_FRESH_MS]：
            // 落库刷新现在是 15 秒一次，2 分钟的窗口会把"主进程也死了"的设备误判成
            // "主进程活着但守护死了"，从而给出错误的处置建议。
            deviceRepository.findByOnlineStatus("ONLINE").forEach { dev ->
                val last = dev.lastOnlineAt ?: return@forEach
                if (now - last > HEARTBEAT_FRESH_MS) return@forEach
                if (dev.daemonAlive == false && dev.daemonLostAlerted != true) {
                    log.warn("device {} guardian daemon lost (heartbeat alive but daemonAlive=false), notifying parent", dev.id)
                    deviceService.markDaemonLost(dev.id)
                    alertService.create(
                        deviceId = dev.id,
                        level = "WARNING",
                        title = "守护进程疑似失联",
                        message = "孩子端主进程在线，但守护进程（双进程互保）已停止，管控可能在强制停止后被解除。请确认设备是否被强制停止或卸载，并重新打开孩子端。",
                        category = "GUARD_DAEMON_LOST"
                    )
                }
            }
        }.onFailure { log.error("offline monitor scan failed", it) }
    }

    private companion object {
        /**
         * 持续离线多久才打扰家长。
         *
         * 60 秒：短于这个时长的抖动（切网、锁屏、系统临时冻结进程）都会被静默吞掉，
         * 状态本身由 [PresenceMonitorService] 在 5 秒级如实反映，家长打开 App 看得到，
         * 不需要额外推送。真关机 / 真被杀 / 真断网则会稳稳越过这条线。
         */
        const val CONTROL_LOST_MS = 60_000L

        /** "心跳仍然新鲜"的窗口：落库刷新间隔 15s × 2 余量 */
        const val HEARTBEAT_FRESH_MS = 30_000L
    }
}
