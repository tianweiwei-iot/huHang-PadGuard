package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.Device
import com.padguard.server.dto.DeviceMini
import com.padguard.server.dto.DeviceRow
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.UserRepository
import com.padguard.server.security.AdminPermissionService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 设备管理（列表 / 远程锁屏 / 远程解锁）。W25 从 AdminService 抽离。
 */
@Service
class AdminDeviceService(
    private val deviceRepository: DeviceRepository,
    private val userRepository: UserRepository,
    private val commandService: CommandService,
    private val adminPerm: AdminPermissionService,
    private val auditService: AuditService
) {
    private val log = LoggerFactory.getLogger(AdminDeviceService::class.java)

    fun listDevices(adminId: String, keyword: String?, online: String?): List<DeviceRow> {
        requireRead(adminPerm, adminId, "devices")
        val users = userRepository.findAll()
        val userMap = users.associateBy { it.id }
        var devs = deviceRepository.findAll()
        if (!keyword.isNullOrBlank()) {
            val k = keyword.lowercase()
            devs = devs.filter { d ->
                d.id.lowercase().contains(k) ||
                (userMap[d.userId]?.phone?.contains(k) == true) ||
                (userMap[d.userId]?.nickname?.lowercase()?.contains(k) == true)
            }
        }
        if (online == "1") devs = devs.filter { online(it) }
        else if (online == "0") devs = devs.filter { !online(it) }
        return devs.map { d ->
            val owner = userMap[d.userId]
            DeviceRow(
                deviceId = d.id, deviceSn = d.deviceSn, name = d.name,
                parentName = owner?.nickname ?: owner?.phone?.let { "家长" + it.takeLast(4) },
                parentPhone = owner?.phone,
                model = d.model, osVersion = d.osVersion, controlMode = d.controlMode,
                online = online(d), lastOnlineAt = fmt(d.lastOnlineAt),
                remoteLocked = d.remoteLocked ?: false, minorMode = d.ageBand != null,
                todayUsageMinutes = 0, region = "", boundAt = fmt(d.createdAt)
            )
        }
    }

    @Transactional
    fun lockDevice(adminId: String, deviceId: String): Map<String, Any> {
        adminPerm.require(adminId, "devices", "lock")
        val dev = deviceRepository.findById(deviceId).orElse(null)
            ?: throw BizException(ParentErr.DEVICE_NOT_FOUND, "设备不存在", Audience.PARENT)
        dev.remoteLocked = true
        runCatching { deviceRepository.save(dev) }.onFailure { log.warn("persist remoteLocked failed: ${it.message}") }
        commandService.issueCommand(deviceId, CommandType.LOCK_SCREEN, mapOf(CommandKey.REASON to "管理员远程锁屏"))
        auditService.record(adminId, deviceId, AdminAudit.DEVICES_LOCK, targetType = "device", targetId = deviceId)
        return mapOf("ok" to true)
    }

    @Transactional
    fun unlockDevice(adminId: String, deviceId: String): Map<String, Any> {
        adminPerm.require(adminId, "devices", "unlock")
        val dev = deviceRepository.findById(deviceId).orElse(null)
            ?: throw BizException(ParentErr.DEVICE_NOT_FOUND, "设备不存在", Audience.PARENT)
        dev.remoteLocked = false
        runCatching { deviceRepository.save(dev) }.onFailure { log.warn("persist remoteLocked failed: ${it.message}") }
        commandService.issueCommand(deviceId, CommandType.UNLOCK, mapOf(CommandKey.REASON to "管理员远程解锁"))
        auditService.record(adminId, deviceId, AdminAudit.DEVICES_UNLOCK, targetType = "device", targetId = deviceId)
        return mapOf("ok" to true)
    }
}
