package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.Device
import com.padguard.server.domain.DeviceGroup
import com.padguard.server.repository.DeviceGroupRepository
import com.padguard.server.repository.DeviceRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * 设备分组服务的单元测试。
 *
 * 重点覆盖 [GroupService.requireOwnedDevice]：方法名带 "Owned"，语义必须是"归属校验"，
 * 早期实现只查了存在性（不带鉴权的 getDevice），任何登录家长都能拿别人的 deviceId 走到写操作，
 * 是典型的越权（IDOR）隐患。本测试断言"设备归属他人时按不存在处理"。
 */
class GroupServiceTest {

    private class FakeDeviceGroupRepo : FakeJpaRepository<DeviceGroup, String>(), DeviceGroupRepository {
        override val table = LinkedHashMap<String, DeviceGroup>()
        override fun idOf(entity: DeviceGroup) = entity.id
        override fun findByOwnerUserId(ownerUserId: String): List<DeviceGroup> =
            table.values.filter { it.ownerUserId == ownerUserId }
        override fun findBySceneType(sceneType: String): List<DeviceGroup> = emptyList()
    }

    private class FakeDeviceRepo : FakeJpaRepository<Device, String>(), DeviceRepository {
        override val table = LinkedHashMap<String, Device>()
        override fun idOf(entity: Device) = entity.id
        // GroupService.listGroups 依赖 findByUserId 后按 groupId 计数，必须返回归属用户的真实设备。
        override fun findByUserId(userId: String): List<Device> =
            table.values.filter { it.userId == userId }
        override fun countByUserId(userId: String): Long = 0L
        override fun countByOnlineStatus(onlineStatus: String): Long = 0L
        override fun findByDeviceSn(deviceSn: String): List<Device> = emptyList()
        override fun findByLastActiveAtGreaterThan(since: Long): List<Device> = emptyList()
    }

    private fun svc(): GroupService {
        return GroupService(FakeDeviceGroupRepo(), FakeDeviceRepo())
    }

    @Test
    fun `requireOwnedDevice returns the device when it belongs to the caller`() {
        val deviceRepo = FakeDeviceRepo()
        val s = GroupService(FakeDeviceGroupRepo(), deviceRepo)
        deviceRepo.table["d1"] = Device(id = "d1", userId = "u1")

        val dev = s.requireOwnedDevice("u1", "d1")
        assertEquals("d1", dev.id)
    }

    @Test
    fun `requireOwnedDevice throws DEVICE_NOT_OWNED when the device belongs to another user`() {
        val deviceRepo = FakeDeviceRepo()
        val s = GroupService(FakeDeviceGroupRepo(), deviceRepo)
        // 设备 d2 属于 u1，但调用方是 u2 —— 必须按"不存在"处理，不得泄露归属。
        deviceRepo.table["d2"] = Device(id = "d2", userId = "u1")

        val ex = assertThrows(BizException::class.java) { s.requireOwnedDevice("u2", "d2") }
        assertEquals(ParentErr.DEVICE_NOT_OWNED, ex.code)
        assertEquals(Audience.PARENT, ex.audience)
    }

    @Test
    fun `requireOwnedDevice throws DEVICE_NOT_FOUND when the device does not exist`() {
        val deviceRepo = FakeDeviceRepo()
        val s = GroupService(FakeDeviceGroupRepo(), deviceRepo)

        val ex = assertThrows(BizException::class.java) { s.requireOwnedDevice("u1", "ghost") }
        assertEquals(ParentErr.DEVICE_NOT_FOUND, ex.code)
    }

    @Test
    fun `requireOwnedGroup throws FORBIDDEN when the group belongs to another user`() {
        val groupRepo = FakeDeviceGroupRepo()
        val s = GroupService(groupRepo, FakeDeviceRepo())
        groupRepo.table["g1"] = DeviceGroup(id = "g1", ownerUserId = "u1")

        val ex = assertThrows(BizException::class.java) { s.requireOwnedGroup("u2", "g1") }
        assertEquals(ParentErr.FORBIDDEN, ex.code)
    }

    @Test
    fun `listGroups counts only the caller's devices grouped by groupId`() {
        val groupRepo = FakeDeviceGroupRepo()
        val deviceRepo = FakeDeviceRepo()
        val s = GroupService(groupRepo, deviceRepo)
        groupRepo.table["g1"] = DeviceGroup(id = "g1", name = "学习机", ownerUserId = "u1")
        deviceRepo.table["d1"] = Device(id = "d1", userId = "u1", groupId = "g1")
        deviceRepo.table["d2"] = Device(id = "d2", userId = "u1", groupId = "g1")

        val groups = s.listGroups("u1", null)
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].deviceCount)
    }
}
