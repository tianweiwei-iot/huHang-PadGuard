package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.Device
import com.padguard.server.dto.DeviceMini
import com.padguard.server.dto.DeviceRow
import com.padguard.server.dto.PageResult
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.UserRepository
import com.padguard.server.security.AdminPermissionService
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 设备管理（列表 / 远程锁屏 / 远程解锁）。
 */
@Service
class AdminDeviceService(
    private val deviceRepository: DeviceRepository,
    private val userRepository: UserRepository,
    private val commandService: CommandService,
    private val adminPerm: AdminPermissionService,
    private val auditService: AuditService,
    private val usageSessionService: UsageSessionService,
    private val deviceService: DeviceService
) {
    private val log = LoggerFactory.getLogger(AdminDeviceService::class.java)

    /**
     * 设备列表（分页 + 关键字 + 在线态）。
     *
     * 改造前 `findAll()` 把设备表整表读进内存过滤：设备只增不减，
     * 几百台时还能忍，上千台后每次翻页都是一次全表加载 + 全量在线态计算，
     * 后台在早高峰（家长集中查看设备）会把堆和 CPU 一起顶上去。
     *
     * 现在过滤与分页下推数据库，只有当前页需要补家长信息与今日用量，
     * 且都是一次批量查询。在线态判定在 SQL 里与 [online] 同口径（40 秒心跳窗）。
     */
    fun listDevices(adminId: String, keyword: String?, online: String?, page: Int = 1, size: Int = 20): PageResult<DeviceRow> {
        requireRead(adminPerm, adminId, "devices")
        val p = if (page < 1) 1 else page
        val s = when {
            size < 1 -> 20
            size > 200 -> 200
            else -> size
        }
        val k = keyword?.trim()?.lowercase() ?: ""
        val onlineMode = when (online?.trim()) {
            "1" -> "1"
            "0" -> "0"
            else -> ""
        }
        val result = deviceRepository.searchDevices(
            keyword = k,
            kw = "%$k%",
            onlineMode = onlineMode,
            staleCut = System.currentTimeMillis() - ONLINE_STALE_MS,
            pageable = PageRequest.of(p - 1, s)
        )
        val devs = result.content
        // 只取当前页涉及到的家长，而不是全表家长
        val ownerIds = devs.mapNotNull { it.userId }.distinct()
        val userMap = if (ownerIds.isEmpty()) emptyMap()
        else userRepository.findAllById(ownerIds).associateBy { it.id }
        // 一次性取当前页设备的今日用量，避免逐台查询。
        // 取不到的（今日尚未开始计时）保持 null，前端显示"—"而不是伪造一个 0。
        val usedMinutes = usageSessionService.usedMinutesBatch(devs.map { it.id })
        val rows = devs.map { d ->
            val owner = userMap[d.userId]
            DeviceRow(
                deviceId = d.id, deviceSn = d.deviceSn, name = d.name,
                parentName = owner?.nickname ?: owner?.phone?.let { "家长" + it.takeLast(4) },
                parentPhone = owner?.phone,
                model = d.model, osVersion = d.osVersion, controlMode = d.controlMode,
                online = online(d), lastOnlineAt = fmt(d.lastOnlineAt),
                remoteLocked = d.remoteLocked ?: false, minorMode = d.ageBand != null,
                // 不要用 `?: 0` 兜底：DTO 约定 null 表示"今日尚未开始计时"，
                // 填 0 会把"没有数据"粉饰成"今天一分钟都没用"，运营据此判断活跃度必然失真。
                todayUsageMinutes = usedMinutes[d.id], region = owner?.region ?: "",
                boundAt = fmt(d.createdAt)
            )
        }
        val total = result.totalElements.toInt()
        val totalPages = if (total == 0) 1 else ((total + s - 1) / s)
        return PageResult(rows, p, s, total, totalPages)
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

    @Transactional
    fun unbindDevice(adminId: String, deviceId: String): Map<String, Any> {
        adminPerm.require(adminId, "devices", "unbind")
        val dev = deviceRepository.findById(deviceId).orElse(null)
            ?: throw BizException(ParentErr.DEVICE_NOT_FOUND, "设备不存在", Audience.PARENT)
        val userId = dev.userId
        if (userId != null) {
            // 复用家长端解绑逻辑：清归属、作废设备令牌、清理私有数据、下发 unbind 通知。
            deviceService.unbind(userId, deviceId)
        }
        auditService.record(adminId, deviceId, AdminAudit.DEVICES_UNBIND, targetType = "device", targetId = deviceId)
        return mapOf("ok" to true)
    }
}
