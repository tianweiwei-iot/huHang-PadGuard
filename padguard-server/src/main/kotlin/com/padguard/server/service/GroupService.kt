package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.DeviceGroup
import com.padguard.server.dto.DeviceGroupDto
import com.padguard.server.repository.DeviceGroupRepository
import com.padguard.server.repository.DeviceRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class GroupService(
    private val groupRepository: DeviceGroupRepository,
    private val deviceRepository: DeviceRepository
) {
    fun listGroups(ownerUserId: String, sceneType: String?): List<DeviceGroupDto> {
        val groups = if (sceneType.isNullOrBlank()) {
            groupRepository.findByOwnerUserId(ownerUserId)
        } else {
            groupRepository.findByOwnerUserId(ownerUserId).filter { it.sceneType == sceneType }
        }
        return groups.map { g ->
            val count = deviceRepository.findByUserId(ownerUserId).count { it.groupId == g.id }
            DeviceGroupDto(g.id, g.name, g.sceneType, count)
        }
    }

    fun createGroup(ownerUserId: String, name: String, sceneType: String): DeviceGroupDto {
        val g = groupRepository.save(
            DeviceGroup(
                id = UUID.randomUUID().toString(), name = name, sceneType = sceneType,
                ownerUserId = ownerUserId, createdAt = System.currentTimeMillis()
            )
        )
        return DeviceGroupDto(g.id, g.name, g.sceneType, 0)
    }

    /** 校验设备归属并返回设备，供分配使用 */
    fun requireOwnedDevice(ownerUserId: String, deviceId: String) =
        deviceRepository.findById(deviceId).orElse(null)
            ?: throw BizException(ParentErr.DEVICE_NOT_FOUND, "设备不存在", Audience.PARENT)

    fun requireOwnedGroup(ownerUserId: String, groupId: String): DeviceGroup {
        val g = groupRepository.findById(groupId).orElse(null)
            ?: throw BizException(ParentErr.PARAM_ERROR, "分组不存在", Audience.PARENT)
        if (g.ownerUserId != ownerUserId) {
            throw BizException(ParentErr.FORBIDDEN, "分组不属于当前用户", Audience.PARENT)
        }
        return g
    }
}
