package com.padguard.server.service

import com.padguard.server.domain.Device
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.service.DevicePresenceRegistry
import com.padguard.server.support.TaskExecutionLock
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import java.sql.DriverManager

/**
 * `OfflineMonitorService` 是「管控连接中断」告警的唯一判定点，置信关键：
 *
 * - 误报（短暂抖动就弹"管控失效"）会让家长对真实告警脱敏；
 * - 漏报（守护进程失联、真掉线却不告警）会让家长失去最后一道防线；
 * - 重复告警（每轮扫描都重发同一条）是历史真实缺陷。
 *
 * 此前**零单测**，且逻辑里藏着多处"服务侧"判定（守护失联的心跳新鲜窗口、去重集合、
 * 重新上线后允许再告警），回归改坏无任何门禁。
 *
 * 覆盖：脱管告警触发/去重、重新上线后解除去重、守护进程失联判定（含心跳新鲜窗口与已告警跳过）、
 * 以及"在线但心跳陈旧"被正确忽略。不加载 Spring 上下文，沿用既定 Mockito 占位范式
 * （非空参数 matcher 补 `?: 默认值`；`any()` 仅用于可空参数）。
 */
class OfflineMonitorServiceTest {
    private val deviceRepository = mock(DeviceRepository::class.java)
    private val deviceService = mock(DeviceService::class.java)
    private val alertService = mock(AlertService::class.java)
    private val presence = mock(DevicePresenceRegistry::class.java)

    private fun svc(): OfflineMonitorService = OfflineMonitorService(deviceRepository, deviceService, alertService, presence)

    private fun offlineDevice(id: String, lastOnlineAtMsAgo: Long): Device =
        Device().apply {
            this.id = id
            // 用相对当前时刻的偏移，避免注入 now 的麻烦；扫描会取同一时刻 now，差值稳定。
            this.lastOnlineAt = System.currentTimeMillis() - lastOnlineAtMsAgo
        }

    private fun onlineDevice(id: String, daemonAlive: Boolean?, daemonLostAlerted: Boolean?, lastOnlineAtMsAgo: Long): Device =
        Device().apply {
            this.id = id
            this.daemonAlive = daemonAlive
            this.daemonLostAlerted = daemonLostAlerted
            this.lastOnlineAt = System.currentTimeMillis() - lastOnlineAtMsAgo
        }

    // ---- 脱管告警（OFFLINE + 持续离线超阈值）----

    @Test
    fun controlLost_alertsOnceWhenOfflineBeyondThreshold() {
        // 离线 70s > 60s 阈值；presence 为空（不在在线集合中）→ 不会被清除去重
        `when`(presence.onlineIds()).thenReturn(emptySet())
        `when`(deviceRepository.findByOnlineStatusAndLastOnlineAtLessThan(eq("OFFLINE") ?: "OFFLINE", anyLong()))
            .thenReturn(listOf(offlineDevice("d1", 70_000L)))
        `when`(deviceRepository.findByOnlineStatus(eq("ONLINE") ?: "ONLINE")).thenReturn(emptyList())

        svc().scan()

        // 上报一条 CONTROL_LOST 告警，且 level=WARNING
        verify(alertService).create(
            eq("d1") ?: "d1", eq("WARNING") ?: "WARNING",
            anyString() ?: "", anyString() ?: "", eq("CONTROL_LOST") ?: "CONTROL_LOST"
        )
    }

    @Test
    fun controlLost_dedupesAcrossScans() {
        `when`(presence.onlineIds()).thenReturn(emptySet())
        `when`(deviceRepository.findByOnlineStatusAndLastOnlineAtLessThan(eq("OFFLINE") ?: "OFFLINE", anyLong()))
            .thenReturn(listOf(offlineDevice("d1", 70_000L)))
        `when`(deviceRepository.findByOnlineStatus(eq("ONLINE") ?: "ONLINE")).thenReturn(emptyList())

        val s = svc()
        s.scan() // 第一次：建告警
        s.scan() // 第二次：同一设备已告警，必须去重，不再建

        verify(alertService, times(1)).create(
            eq("d1") ?: "d1", anyString() ?: "", anyString() ?: "", anyString() ?: "", eq("CONTROL_LOST") ?: "CONTROL_LOST"
        )
    }

    @Test
    fun controlLost_noAlertWhenOfflineRecently() {
        // 模拟仓库只返回"确实超阈值"的设备：最近离线的不会被扫描 SQL 命中 → 返回空
        `when`(presence.onlineIds()).thenReturn(emptySet())
        `when`(deviceRepository.findByOnlineStatusAndLastOnlineAtLessThan(eq("OFFLINE") ?: "OFFLINE", anyLong()))
            .thenReturn(emptyList())
        `when`(deviceRepository.findByOnlineStatus(eq("ONLINE") ?: "ONLINE")).thenReturn(emptyList())

        svc().scan()

        verify(alertService, never()).create(anyString() ?: "", anyString() ?: "", anyString() ?: "", anyString() ?: "", anyString() ?: "")
    }

    @Test
    fun controlLost_reAlertsAfterDeviceBackOnline() {
        `when`(deviceRepository.findByOnlineStatus(eq("ONLINE") ?: "ONLINE")).thenReturn(emptyList())
        val s = svc()

        // 第一轮：离线超阈值 → 告警并记入 alerted
        `when`(presence.onlineIds()).thenReturn(emptySet())
        `when`(deviceRepository.findByOnlineStatusAndLastOnlineAtLessThan(eq("OFFLINE") ?: "OFFLINE", anyLong()))
            .thenReturn(listOf(offlineDevice("d1", 70_000L)))
        s.scan()
        verify(alertService, times(1)).create(
            eq("d1") ?: "d1", anyString() ?: "", anyString() ?: "", anyString() ?: "", eq("CONTROL_LOST") ?: "CONTROL_LOST"
        )

        // 第二轮：设备重新上线 → alerted 集合移除 d1（去重复位）；
        // 在线设备不会被 OFFLINE 状态查询命中，故本轮不再告警
        `when`(presence.onlineIds()).thenReturn(setOf("d1"))
        `when`(deviceRepository.findByOnlineStatusAndLastOnlineAtLessThan(eq("OFFLINE") ?: "OFFLINE", anyLong()))
            .thenReturn(emptyList())
        s.scan()
        verify(alertService, times(1)).create(
            eq("d1") ?: "d1", anyString() ?: "", anyString() ?: "", anyString() ?: "", eq("CONTROL_LOST") ?: "CONTROL_LOST"
        )

        // 第三轮：又掉线且不在在线集合 → 去重已解除 → 再次告警（共 2 条）
        `when`(presence.onlineIds()).thenReturn(emptySet())
        `when`(deviceRepository.findByOnlineStatusAndLastOnlineAtLessThan(eq("OFFLINE") ?: "OFFLINE", anyLong()))
            .thenReturn(listOf(offlineDevice("d1", 70_000L)))
        s.scan()
        verify(alertService, times(2)).create(
            eq("d1") ?: "d1", anyString() ?: "", anyString() ?: "", anyString() ?: "", eq("CONTROL_LOST") ?: "CONTROL_LOST"
        )
    }

    // ---- 守护进程失联（ONLINE + 心跳新鲜但 daemonAlive=false）----

    @Test
    fun guardDaemonLost_alertsAndMarksWhenDaemonDeadAndHeartbeatFresh() {
        `when`(presence.onlineIds()).thenReturn(emptySet())
        `when`(deviceRepository.findByOnlineStatusAndLastOnlineAtLessThan(eq("OFFLINE") ?: "OFFLINE", anyLong()))
            .thenReturn(emptyList())
        // 在线、守护已停、尚未告警、心跳新鲜（10s < 30s 窗口）
        `when`(deviceRepository.findByOnlineStatus(eq("ONLINE") ?: "ONLINE"))
            .thenReturn(listOf(onlineDevice("d1", daemonAlive = false, daemonLostAlerted = null, 10_000L)))

        svc().scan()

        verify(deviceService).markDaemonLost(eq("d1") ?: "d1")
        verify(alertService).create(
            eq("d1") ?: "d1", eq("WARNING") ?: "WARNING",
            anyString() ?: "", anyString() ?: "", eq("GUARD_DAEMON_LOST") ?: "GUARD_DAEMON_LOST"
        )
    }

    @Test
    fun guardDaemonLost_noAlertWhenDaemonAlive() {
        `when`(presence.onlineIds()).thenReturn(emptySet())
        `when`(deviceRepository.findByOnlineStatusAndLastOnlineAtLessThan(eq("OFFLINE") ?: "OFFLINE", anyLong()))
            .thenReturn(emptyList())
        `when`(deviceRepository.findByOnlineStatus(eq("ONLINE") ?: "ONLINE"))
            .thenReturn(listOf(onlineDevice("d1", daemonAlive = true, daemonLostAlerted = false, 10_000L)))

        svc().scan()

        verify(deviceService, never()).markDaemonLost(anyString() ?: "")
        verify(alertService, never()).create(anyString() ?: "", anyString() ?: "", anyString() ?: "", anyString() ?: "", anyString() ?: "")
    }

    @Test
    fun guardDaemonLost_skipsWhenHeartbeatStale() {
        // 心跳陈旧（90s > 30s 窗口）→ 视为"主进程也死了"，不按守护失联误报
        `when`(presence.onlineIds()).thenReturn(emptySet())
        `when`(deviceRepository.findByOnlineStatusAndLastOnlineAtLessThan(eq("OFFLINE") ?: "OFFLINE", anyLong()))
            .thenReturn(emptyList())
        `when`(deviceRepository.findByOnlineStatus(eq("ONLINE") ?: "ONLINE"))
            .thenReturn(listOf(onlineDevice("d1", daemonAlive = false, daemonLostAlerted = null, 90_000L)))

        svc().scan()

        verify(deviceService, never()).markDaemonLost(anyString() ?: "")
        verify(alertService, never()).create(anyString() ?: "", anyString() ?: "", anyString() ?: "", anyString() ?: "", anyString() ?: "")
    }

    @Test
    fun guardDaemonLost_skipsWhenAlreadyAlerted() {
        // daemonAlive=false 但 daemonLostAlerted=true → 已告警过，跳过（不放重复推送）
        `when`(presence.onlineIds()).thenReturn(emptySet())
        `when`(deviceRepository.findByOnlineStatusAndLastOnlineAtLessThan(eq("OFFLINE") ?: "OFFLINE", anyLong()))
            .thenReturn(emptyList())
        `when`(deviceRepository.findByOnlineStatus(eq("ONLINE") ?: "ONLINE"))
            .thenReturn(listOf(onlineDevice("d1", daemonAlive = false, daemonLostAlerted = true, 10_000L)))

        svc().scan()

        verify(deviceService, never()).markDaemonLost(anyString() ?: "")
        verify(alertService, never()).create(anyString() ?: "", anyString() ?: "", anyString() ?: "", anyString() ?: "", anyString() ?: "")
    }

    // ---- 多副本互斥：alerted 是 JVM 内存态，必须靠分布式锁阻止 N 副本各推一条告警 ----

    /**
     * 回归防线：另一副本持有效租约时，本副本本轮必须整体跳过扫描。
     * 旧实现无锁，多副本会对同一台离线设备各建一条 CONTROL_LOST、各推一次 WS，
     * 家长端收到 N 条重复"管控失效"推送 —— 这是最容易让家长对真告警脱敏的场景。
     */
    @Test
    fun scan_skippedWhenAnotherReplicaHoldsLock() {
        val url = "jdbc:h2:mem:offlinelock;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        val ds = SimpleDriverDataSource(DriverManager.getDriver(url), url, "sa", "")
        val jdbc = JdbcTemplate(ds)
        jdbc.execute(
            "CREATE TABLE IF NOT EXISTS task_locks (task_name VARCHAR(64) PRIMARY KEY, " +
                "holder VARCHAR(128) NOT NULL, acquired_at BIGINT NOT NULL, lease_until BIGINT NOT NULL)"
        )
        jdbc.update(
            "INSERT INTO task_locks (task_name, holder, acquired_at, lease_until) VALUES (?, ?, ?, ?)",
            "offline-monitor", "other-replica", System.currentTimeMillis(), System.currentTimeMillis() + 60_000L
        )
        val lock = TaskExecutionLock(jdbc)
        `when`(presence.onlineIds()).thenReturn(emptySet())
        `when`(deviceRepository.findByOnlineStatusAndLastOnlineAtLessThan(eq("OFFLINE") ?: "OFFLINE", anyLong()))
            .thenReturn(listOf(offlineDevice("d1", 70_000L)))
        `when`(deviceRepository.findByOnlineStatus(eq("ONLINE") ?: "ONLINE")).thenReturn(emptyList())

        OfflineMonitorService(deviceRepository, deviceService, alertService, presence, lock).scan()

        verify(alertService, never()).create(anyString() ?: "", anyString() ?: "", anyString() ?: "", anyString() ?: "", anyString() ?: "")
        jdbc.execute("DROP TABLE IF EXISTS task_locks")
    }
}
