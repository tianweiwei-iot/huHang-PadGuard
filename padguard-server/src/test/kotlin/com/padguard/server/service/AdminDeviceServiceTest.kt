package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.Device
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.UserRepository
import com.padguard.server.security.AdminPermissionService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import java.util.Optional

/**
 * `AdminDeviceService` 的 `listDevices` 主体与 `unbindDevice` 基础路径已由 `AdminServiceTest`
 * 经门面覆盖，此处只补**自身零覆盖的信任关键边界**：
 *
 * - 分页/关键字钳制（后台列表是运营高频入口，越界 size 会打爆 DB）；
 * - `todayUsageMinutes` 取不到必须保持 null，不能伪造 0 粉饰「今日尚未开始计时」；
 * - 远程锁屏 / 解锁 / 解绑三个写操作各自过 RBAC 闸门、设备不存在时拒绝、
 *   持久化失败不阻断指令下发（否则「操作成功、设备毫无反应」）、并留审计痕迹。
 *
 * 不加载 Spring 上下文，7 个依赖全部 Mockito 桩。
 */
class AdminDeviceServiceTest {

    private lateinit var devices: DeviceRepository
    private lateinit var users: UserRepository
    private lateinit var commands: CommandService
    private lateinit var perm: AdminPermissionService
    private lateinit var audit: AuditService
    private lateinit var usageSessions: UsageSessionService
    private lateinit var deviceService: DeviceService
    private lateinit var svc: AdminDeviceService

    private val adminId = "A1"

    @BeforeEach
    fun setUp() {
        devices = mock(DeviceRepository::class.java)
        users = mock(UserRepository::class.java)
        commands = mock(CommandService::class.java)
        perm = mock(AdminPermissionService::class.java)
        audit = mock(AuditService::class.java)
        usageSessions = mock(UsageSessionService::class.java)
        deviceService = mock(DeviceService::class.java)

        `when`(devices.searchDevices(
            anyString(), anyString(), anyString(), anyLong(),
            any(Pageable::class.java) ?: PageRequest.of(0, 1)
        )).thenReturn(PageImpl(emptyList(), PageRequest.of(0, 20), 0))
        `when`(devices.findById(anyString()))
            .thenReturn(Optional.of(Device(id = "d1", userId = "u1")))
        `when`(usageSessions.usedMinutesBatch(emptyList())).thenReturn(emptyMap())

        svc = AdminDeviceService(devices, users, commands, perm, audit, usageSessions, deviceService)
    }

    private fun deny(action: String) {
        doThrow(BizException(ParentErr.FORBIDDEN, "无权限", Audience.PARENT))
            .`when`(perm).require(adminId, "devices", action)
    }

    /** searchDevices 的 5 个参数中前 3 个 String 与第 5 个 Pageable 抓取用 */
    private fun searchArgs(): List<org.mockito.invocation.Invocation> =
        org.mockito.Mockito.mockingDetails(devices).invocations
            .filter { it.method.name == "searchDevices" }

    // ---------- listDevices 参数边界 ----------

    @Test
    fun `listDevices page 小于 1 钳为 1 且 size 小于 1 钳为 20`() {
        svc.listDevices(adminId, null, null, page = 0, size = 0)
        val cap = ArgumentCaptor.forClass(Pageable::class.java)
        verify(devices).searchDevices(
            anyString(), anyString(), anyString(), anyLong(), cap.capture() ?: PageRequest.of(0, 1)
        )
        assertEquals(0, cap.value.pageNumber)
        assertEquals(20, cap.value.pageSize)
    }

    @Test
    fun `listDevices size 超过 200 钳为 200`() {
        svc.listDevices(adminId, null, null, page = 3, size = 9999)
        val cap = ArgumentCaptor.forClass(Pageable::class.java)
        verify(devices).searchDevices(
            anyString(), anyString(), anyString(), anyLong(), cap.capture() ?: PageRequest.of(0, 1)
        )
        assertEquals(2, cap.value.pageNumber)
        assertEquals(200, cap.value.pageSize)
    }

    @Test
    fun `listDevices keyword 去空格转小写且 online 只认 1 和 0`() {
        svc.listDevices(adminId, "  AbC  ", "1", 1, 20)
        svc.listDevices(adminId, null, "maybe", 1, 20)
        val kws = ArgumentCaptor.forClass(String::class.java)
        val modes = ArgumentCaptor.forClass(String::class.java)
        verify(devices, times(2)).searchDevices(
            kws.capture() ?: "", anyString(), modes.capture() ?: "", anyLong(),
            any(Pageable::class.java) ?: PageRequest.of(0, 1)
        )
        assertEquals(listOf("abc", ""), kws.allValues)
        assertEquals(listOf("1", ""), modes.allValues)
    }

    @Test
    fun `listDevices 今日用量取不到时保持 null 不伪造 0`() {
        val dev = Device(id = "d1", userId = "u1", deviceSn = "SN1")
        `when`(devices.searchDevices(
            anyString(), anyString(), anyString(), anyLong(),
            any(Pageable::class.java) ?: PageRequest.of(0, 1)
        )).thenReturn(PageImpl(listOf(dev), PageRequest.of(0, 20), 1))
        `when`(users.findAllById(listOf("u1"))).thenReturn(emptyList())
        `when`(usageSessions.usedMinutesBatch(listOf("d1"))).thenReturn(emptyMap())

        val row = svc.listDevices(adminId, null, null).items.single()
        assertNull(row.todayUsageMinutes, "null 表示今日尚未开始计时，填 0 会让运营误判活跃度")
    }

    @Test
    fun `listDevices devices read 闸门拒绝时不查库`() {
        doThrow(BizException(ParentErr.FORBIDDEN, "无权限", Audience.PARENT))
            .`when`(perm).require(adminId, "devices", "read")
        assertThrows(BizException::class.java) { svc.listDevices(adminId, null, null) }
        assertEquals(0, searchArgs().size)
    }

    // ---------- 远程锁屏 / 解锁 ----------

    @Test
    fun `lockDevice 闸门拒绝时不下发指令也不留审计`() {
        deny("lock")
        assertThrows(BizException::class.java) { svc.lockDevice(adminId, "d1") }
        verify(commands, never()).issueCommand(anyString(), anyString(), any(), anyString())
        verify(audit, never()).record(any(), any(), anyString(), any(), any(), any(), anyString(), any())
    }

    @Test
    fun `lockDevice 设备不存在抛 DEVICE_NOT_FOUND 且不下发指令`() {
        `when`(devices.findById("d1")).thenReturn(Optional.empty())
        val ex = assertThrows(BizException::class.java) { svc.lockDevice(adminId, "d1") }
        assertEquals(ParentErr.DEVICE_NOT_FOUND, ex.code)
        verify(commands, never()).issueCommand(anyString(), anyString(), any(), anyString())
    }

    @Test
    fun `lockDevice 置 remoteLocked 下发 LOCK_SCREEN 并留审计`() {
        val dev = Device(id = "d1", userId = "u1")
        `when`(devices.findById("d1")).thenReturn(Optional.of(dev))
        assertEquals(mapOf("ok" to true), svc.lockDevice(adminId, "d1"))
        assertEquals(true, dev.remoteLocked)
        verify(commands).issueCommand(
            eq("d1") ?: "d1", eq(CommandType.LOCK_SCREEN) ?: "", any(), eq("HIGH") ?: "HIGH"
        )
        verify(audit).record(
            eq(adminId) ?: adminId, eq("d1") ?: "d1", eq(AdminAudit.DEVICES_LOCK) ?: "",
            eq("device") ?: "device", eq("d1") ?: "d1", any(), eq("SUCCESS") ?: "SUCCESS", any()
        )
    }

    @Test
    fun `lockDevice 持久化失败不阻断指令下发`() {
        `when`(devices.save(any(Device::class.java))).thenThrow(RuntimeException("db down"))
        val r = svc.lockDevice(adminId, "d1")
        assertEquals(true, r["ok"])
        verify(commands).issueCommand(
            eq("d1") ?: "d1", eq(CommandType.LOCK_SCREEN) ?: "", any(), eq("HIGH") ?: "HIGH"
        )
    }

    @Test
    fun `unlockDevice 置 remoteLocked=false 下发 UNLOCK 并留审计`() {
        val dev = Device(id = "d1", userId = "u1").apply { remoteLocked = true }
        `when`(devices.findById("d1")).thenReturn(Optional.of(dev))
        svc.unlockDevice(adminId, "d1")
        assertEquals(false, dev.remoteLocked)
        verify(commands).issueCommand(
            eq("d1") ?: "d1", eq(CommandType.UNLOCK) ?: "", any(), eq("HIGH") ?: "HIGH"
        )
        verify(audit).record(
            eq(adminId) ?: adminId, eq("d1") ?: "d1", eq(AdminAudit.DEVICES_UNLOCK) ?: "",
            eq("device") ?: "device", eq("d1") ?: "d1", any(), eq("SUCCESS") ?: "SUCCESS", any()
        )
    }

    @Test
    fun `unlockDevice 闸门拒绝时不下发解锁指令`() {
        deny("unlock")
        assertThrows(BizException::class.java) { svc.unlockDevice(adminId, "d1") }
        verify(commands, never()).issueCommand(anyString(), anyString(), any(), anyString())
    }

    // ---------- 解绑 ----------

    @Test
    fun `unbindDevice 有归属时复用家长端解绑逻辑并留审计`() {
        svc.unbindDevice(adminId, "d1")
        verify(deviceService).unbind("u1", "d1")
        verify(audit).record(
            eq(adminId) ?: adminId, eq("d1") ?: "d1", eq(AdminAudit.DEVICES_UNBIND) ?: "",
            eq("device") ?: "device", eq("d1") ?: "d1", any(), eq("SUCCESS") ?: "SUCCESS", any()
        )
    }

    @Test
    fun `unbindDevice 无归属时不调用解绑但仍留审计`() {
        `when`(devices.findById("d1")).thenReturn(Optional.of(Device(id = "d1")))
        svc.unbindDevice(adminId, "d1")
        verify(deviceService, never()).unbind(anyString(), anyString())
        verify(audit).record(
            eq(adminId) ?: adminId, eq("d1") ?: "d1", eq(AdminAudit.DEVICES_UNBIND) ?: "",
            eq("device") ?: "device", eq("d1") ?: "d1", any(), eq("SUCCESS") ?: "SUCCESS", any()
        )
    }

    @Test
    fun `unbindDevice 设备不存在抛 DEVICE_NOT_FOUND`() {
        `when`(devices.findById("d1")).thenReturn(Optional.empty())
        val ex = assertThrows(BizException::class.java) { svc.unbindDevice(adminId, "d1") }
        assertEquals(ParentErr.DEVICE_NOT_FOUND, ex.code)
        verify(deviceService, never()).unbind(anyString(), anyString())
    }

    @Test
    fun `unbindDevice 闸门拒绝时不解绑`() {
        deny("unbind")
        assertThrows(BizException::class.java) { svc.unbindDevice(adminId, "d1") }
        verify(deviceService, never()).unbind(anyString(), anyString())
    }
}
