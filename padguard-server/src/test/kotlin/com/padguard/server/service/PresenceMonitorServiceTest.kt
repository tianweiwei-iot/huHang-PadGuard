package com.padguard.server.service

import com.padguard.server.domain.Device
import com.padguard.server.repository.DeviceRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import java.util.Optional

/**
 * `PresenceMonitorService` 是秒级在线态收敛的信任关键服务：把"孩子端不再上报信号"
 * 翻译成家长端可见的「离线」（与 [OfflineMonitorService] 的"脱管告警"分工）。此前**零单测**。
 *
 * 覆盖：
 * - `warmUp`：把库里 ONLINE 设备灌进内存 registry；空列表 / 仓库异常都不抛；
 * - `scan`：对 registry 超时设备逐个调用 `deviceService.setOffline`；空超时列表空转；
 *   单设备处理异常被隔离、不影响其余设备。
 *
 * 不加载 Spring 上下文，沿用既定 Mockito 占位范式（`any()` 仅用于可空参数，
 * 非可空参数用 `anyLong()`/`eq(x)` 等具体匹配）。
 */
class PresenceMonitorServiceTest {
    private val registry = mock(DevicePresenceRegistry::class.java)
    private val deviceRepository = mock(DeviceRepository::class.java)
    private val deviceService = mock(DeviceService::class.java)

    private fun svc(): PresenceMonitorService =
        PresenceMonitorService(registry, deviceRepository, deviceService)

    @Test
    fun warmUp_seedsOnlineDevicesIntoRegistry() {
        `when`(deviceRepository.findByOnlineStatus("ONLINE")).thenReturn(listOf(
            Device(id = "d1", lastOnlineAt = 1000L),
            Device(id = "d2", lastOnlineAt = 2000L)
        ))
        svc().warmUp()
        verify(registry).seed(eq("d1") ?: "d1", eq(1000L) ?: 1000L)
        verify(registry).seed(eq("d2") ?: "d2", eq(2000L) ?: 2000L)
    }

    @Test
    fun warmUp_emptyListSeedsNothing() {
        `when`(deviceRepository.findByOnlineStatus("ONLINE")).thenReturn(emptyList())
        svc().warmUp()
        verify(registry, never()).seed(anyString(), anyLong())
    }

    @Test
    fun warmUp_repositoryFailureDoesNotPropagate() {
        `when`(deviceRepository.findByOnlineStatus("ONLINE")).thenThrow(RuntimeException("db down"))
        // 内部 runCatching 吞掉，不应向外抛
        svc().warmUp()
        verify(registry, never()).seed(anyString(), anyLong())
    }

    @Test
    fun scan_marksEachTimedOutDeviceOffline() {
        `when`(registry.takeTimedOut(anyLong())).thenReturn(listOf("d1", "d2"))
        svc().scan()
        verify(deviceService).setOffline("d1")
        verify(deviceService).setOffline("d2")
    }

    @Test
    fun scan_emptyTimeoutListIsNoOp() {
        `when`(registry.takeTimedOut(anyLong())).thenReturn(emptyList())
        svc().scan()
        verify(deviceService, never()).setOffline(anyString())
    }

    @Test
    fun scan_isolatesPerDeviceFailure() {
        `when`(registry.takeTimedOut(anyLong())).thenReturn(listOf("a", "b", "c"))
        doThrow(RuntimeException("boom")).`when`(deviceService).setOffline("b")
        // 单设备失败不应整体抛出，其余设备照常处理
        svc().scan()
        verify(deviceService).setOffline("a")
        verify(deviceService).setOffline("c")
        verify(deviceService, times(1)).setOffline("b")
    }
}
