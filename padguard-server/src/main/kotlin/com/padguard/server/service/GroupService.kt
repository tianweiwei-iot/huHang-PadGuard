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

    /**
     * 校验设备存在**且归属** [ownerUserId]，返回设备实体。
     *
     * 注意：方法名带 "Owned"，语义必须是"归属校验"而非仅"存在校验"。
     * 早期实现只查了存在性，等于一个不带鉴权的 `getDevice`——任何登录家长都能拿着别人的
     * deviceId 走到后续写操作（如把他人设备挪进自己的分组），是典型的越权（IDOR）隐患。
     * 这里与 [requireOwnedGroup] 对齐：归属不符一律按"不存在"处理，不泄露设备归属。
     */
    fun requireOwnedDevice(ownerUserId: String, deviceId: String): com.padguard.server.domain.Device {
        val dev = deviceRepository.findById(deviceId).orElse(null)
            ?: throw BizException(ParentErr.DEVICE_NOT_FOUND, "设备不存在", Audience.PARENT)
        if (dev.userId != ownerUserId) {
            throw BizException(ParentErr.DEVICE_NOT_OWNED, "设备不属于当前用户", Audience.PARENT)
        }
        return dev
    }

    fun requireOwnedGroup(ownerUserId: String, groupId: String): DeviceGroup {
        val g = groupRepository.findById(groupId).orElse(null)
            ?: throw BizException(ParentErr.PARAM_ERROR, "分组不存在", Audience.PARENT)
        if (g.ownerUserId != ownerUserId) {
            throw BizException(ParentErr.FORBIDDEN, "分组不属于当前用户", Audience.PARENT)
        }
        return g
    }
}
