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
