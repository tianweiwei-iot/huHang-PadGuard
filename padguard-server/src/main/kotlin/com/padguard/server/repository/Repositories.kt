package com.padguard.server.repository

import com.padguard.server.domain.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface UserRepository : JpaRepository<User, String> {
    fun existsByPhone(phone: String): Boolean
    fun findByPhone(phone: String): User?
}

@Repository
interface DeviceRepository : JpaRepository<Device, String> {
    fun findByUserId(userId: String): List<Device>

    /** 按硬件序列号查设备（跨用户）：用于"孤儿设备回收"与重绑复用同一条台账 */
    fun findByDeviceSn(deviceSn: String): List<Device>
}

@Repository
interface BindCodeRepository : JpaRepository<BindCode, String>

@Repository
interface PolicyRepository : JpaRepository<Policy, String> {
    fun findFirstByDeviceIdOrderByVersionDesc(deviceId: String): Policy?
}

@Repository
interface CommandRepository : JpaRepository<Command, String> {
    fun findByMsgId(msgId: String): Command?
    fun findByDeviceIdOrderByCreatedAtDesc(deviceId: String): List<Command>

    /**
     * 轮询下发专用：按创建时间**升序**（FIFO）。
     * 降级轮询通道必须保证先到先执行，避免用 Desc 查询时旧指令被新指令挤出批次上限。
     */
    fun findByDeviceIdOrderByCreatedAtAsc(deviceId: String): List<Command>
}

@Repository
interface UsageLogRepository : JpaRepository<UsageLog, String> {
    fun existsByLogId(logId: String): Boolean
    fun findByDeviceId(deviceId: String): List<UsageLog>
    fun findByDeviceIdAndTimestampBetween(deviceId: String, start: Long, end: Long): List<UsageLog>
}

@Repository
interface DeviceEventRepository : JpaRepository<DeviceEvent, String> {
    fun existsByEventId(eventId: String): Boolean
    fun findByDeviceIdAndTimestampBetween(deviceId: String, start: Long, end: Long): List<DeviceEvent>
}
